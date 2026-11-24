package com.bookmarkdrawer.duplicates;

import com.bookmarkdrawer.bookmark.Bookmark;
import com.bookmarkdrawer.bookmark.BookmarkMedia;
import com.bookmarkdrawer.bookmark.BookmarkRepository;
import com.bookmarkdrawer.bookmark.BookmarkService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// 중복/거의 중복 탐지. 전부 로컬 문자열 연산이라 외부 API 호출이 없다(1,438건 기준으로도
// 동기 처리가 충분히 빠르다고 판단 — 설계 단계에서 성능 검토 완료).
//
// EXACT: 정규화한 본문이 완전히 같은 경우.
// NEAR: 본문 유사(3-gram Jaccard) / 동일 미디어 / 인용 관계(quotedTweetId) 신호를
//       Union-Find로 묶은 경우. 인용 관계는 의도적으로 가장 낮은 점수를 준다 — 원본과
//       인용 글 모두 저장할 가치가 있을 수 있어서 강한 중복 판정 기준으로 쓰지 않는다.
@Slf4j
@Service
@RequiredArgsConstructor
public class DuplicateDetectionService {

    private static final double TEXT_SIMILAR_THRESHOLD = 0.85;
    private static final double MEDIA_MATCH_SCORE = 0.75;
    private static final double QUOTE_LINK_SCORE = 0.35;
    private static final int SHINGLE_SIZE = 3;
    private static final double LENGTH_RATIO_PREFILTER = 0.5;
    // t.co 링크를 [LINK]로 마스킹하기 때문에, 본문이 사실상 링크 하나뿐인 트윗(이미지 전용
    // 트윗 등)은 서로 전혀 무관한데도 정규화 후 전부 "[LINK]"로 동일해져 버린다. 실제
    // 데이터로 스캔해보고 발견한 오탐이라, 의미 있는 비교가 되기엔 너무 짧은 본문은
    // 완전/거의 중복 판정 모두에서 제외한다.
    private static final int MIN_MEANINGFUL_TEXT_LENGTH = 15;
    private static final Pattern TCO_LINK = Pattern.compile("https?://t\\.co/\\w+");

    private final BookmarkRepository bookmarkRepository;
    private final DuplicateGroupRepository duplicateGroupRepository;
    private final BookmarkService bookmarkService;

    @Transactional
    public DuplicateScanResult scan() {
        List<Bookmark> active = bookmarkRepository.findByArchivedFalse();

        Map<String, String> normalizedTextByTweetId = new HashMap<>();
        Map<String, Set<String>> shinglesByTweetId = new HashMap<>();
        for (Bookmark b : active) {
            String norm = normalize(b.getText());
            normalizedTextByTweetId.put(b.getTweetId(), norm);
            shinglesByTweetId.put(b.getTweetId(), shingle(norm, SHINGLE_SIZE));
        }

        List<ComputedGroup> exactGroups = findExactGroups(normalizedTextByTweetId);
        List<ComputedGroup> nearGroups = findNearGroups(active, normalizedTextByTweetId, shinglesByTweetId);

        List<ComputedGroup> all = new ArrayList<>(exactGroups);
        all.addAll(nearGroups);
        return persist(all);
    }

    private List<ComputedGroup> findExactGroups(Map<String, String> normalizedTextByTweetId) {
        Map<String, List<String>> byNormText = new HashMap<>();
        for (var entry : normalizedTextByTweetId.entrySet()) {
            if (entry.getValue().length() < MIN_MEANINGFUL_TEXT_LENGTH) continue;
            byNormText.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }
        List<ComputedGroup> groups = new ArrayList<>();
        for (List<String> members : byNormText.values()) {
            if (members.size() >= 2) {
                groups.add(new ComputedGroup(DuplicateType.EXACT, DuplicateSignal.TEXT_EXACT, 1.0, new HashSet<>(members)));
            }
        }
        return groups;
    }

    private List<ComputedGroup> findNearGroups(List<Bookmark> active,
                                                Map<String, String> normalizedTextByTweetId,
                                                Map<String, Set<String>> shinglesByTweetId) {
        UnionFind uf = new UnionFind();
        Map<String, EdgeInfo> bestEdgeByPairKey = new HashMap<>();

        // 1) 본문 유사도(같은 정규화 텍스트끼리는 이미 EXACT라 건너뜀)
        List<String> ids = new ArrayList<>(normalizedTextByTweetId.keySet());
        for (int i = 0; i < ids.size(); i++) {
            String idA = ids.get(i);
            String normA = normalizedTextByTweetId.get(idA);
            if (normA.length() < MIN_MEANINGFUL_TEXT_LENGTH) continue;
            Set<String> shA = shinglesByTweetId.get(idA);
            for (int j = i + 1; j < ids.size(); j++) {
                String idB = ids.get(j);
                String normB = normalizedTextByTweetId.get(idB);
                if (normB.length() < MIN_MEANINGFUL_TEXT_LENGTH || normA.equals(normB)) continue;
                double ratio = (double) Math.min(normA.length(), normB.length()) / Math.max(normA.length(), normB.length());
                if (ratio < LENGTH_RATIO_PREFILTER) continue;
                double sim = jaccard(shA, shinglesByTweetId.get(idB));
                if (sim >= TEXT_SIMILAR_THRESHOLD) {
                    addEdge(uf, bestEdgeByPairKey, idA, idB, DuplicateSignal.TEXT_SIMILAR, sim);
                }
            }
        }

        // 2) 동일 미디어(이미 EXACT인 쌍은 건너뜀)
        Map<String, List<String>> byMediaKey = new HashMap<>();
        for (Bookmark b : active) {
            if (b.getMedia() == null || b.getMedia().isEmpty()) continue;
            List<String> urls = b.getMedia().stream()
                    .map(BookmarkMedia::getUrl)
                    .filter(Objects::nonNull)
                    .sorted()
                    .toList();
            if (urls.isEmpty()) continue;
            byMediaKey.computeIfAbsent(String.join("|", urls), k -> new ArrayList<>()).add(b.getTweetId());
        }
        for (List<String> group : byMediaKey.values()) {
            if (group.size() < 2) continue;
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    String idA = group.get(i), idB = group.get(j);
                    if (normalizedTextByTweetId.get(idA).equals(normalizedTextByTweetId.get(idB))) continue;
                    addEdge(uf, bestEdgeByPairKey, idA, idB, DuplicateSignal.MEDIA_MATCH, MEDIA_MATCH_SCORE);
                }
            }
        }

        // 3) 인용 관계 — 낮은 신뢰도의 보조 신호로만
        Set<String> activeIds = normalizedTextByTweetId.keySet();
        for (Bookmark b : active) {
            String q = b.getQuotedTweetId();
            if (q != null && activeIds.contains(q) && !q.equals(b.getTweetId())) {
                addEdge(uf, bestEdgeByPairKey, q, b.getTweetId(), DuplicateSignal.QUOTE_LINK, QUOTE_LINK_SCORE);
            }
        }

        Set<String> connectedIds = bestEdgeByPairKey.values().stream()
                .flatMap(e -> Stream.of(e.a(), e.b()))
                .collect(Collectors.toSet());
        Map<String, List<String>> components = new HashMap<>();
        for (String id : connectedIds) {
            components.computeIfAbsent(uf.find(id), k -> new ArrayList<>()).add(id);
        }

        List<ComputedGroup> groups = new ArrayList<>();
        for (List<String> comp : components.values()) {
            if (comp.size() < 2) continue;
            Set<String> memberSet = new HashSet<>(comp);
            List<EdgeInfo> edges = bestEdgeByPairKey.values().stream()
                    .filter(e -> memberSet.contains(e.a()) && memberSet.contains(e.b()))
                    .toList();
            double avgScore = edges.stream().mapToDouble(EdgeInfo::score).average().orElse(0);
            DuplicateSignal dominant = edges.stream()
                    .max(Comparator.comparingDouble(EdgeInfo::score))
                    .map(EdgeInfo::signal)
                    .orElse(DuplicateSignal.TEXT_SIMILAR);
            groups.add(new ComputedGroup(DuplicateType.NEAR, dominant, avgScore, memberSet));
        }
        return groups;
    }

    private DuplicateScanResult persist(List<ComputedGroup> computedGroups) {
        Set<String> freshKeys = new HashSet<>();
        int created = 0, alreadyKnown = 0;
        for (ComputedGroup g : computedGroups) {
            String key = computeGroupKey(g.type(), g.members());
            freshKeys.add(key);
            if (duplicateGroupRepository.findByGroupKey(key).isPresent()) {
                alreadyKnown++;
                continue;
            }
            duplicateGroupRepository.save(new DuplicateGroup(g.type(), g.signal(), g.score(), key, g.members()));
            created++;
        }

        // 이번 스캔에서 더는 나오지 않는(멤버가 바뀌었거나 더는 유사하지 않은) pending 그룹은 정리.
        // dismissed 그룹은 재스캔에서 다시 안 뜨게 하기 위해 건드리지 않는다.
        List<DuplicateGroup> stale = duplicateGroupRepository.findByDismissedFalse().stream()
                .filter(g -> !freshKeys.contains(g.getGroupKey()))
                .toList();
        duplicateGroupRepository.deleteAll(stale);

        return new DuplicateScanResult(created, alreadyKnown, stale.size());
    }

    public List<DuplicateGroupView> listGroups(DuplicateType type) {
        List<DuplicateGroup> groups = duplicateGroupRepository.findByTypeAndDismissedFalseOrderByScoreDesc(type);
        List<DuplicateGroupView> views = new ArrayList<>();
        for (DuplicateGroup g : groups) {
            List<Bookmark> members = bookmarkRepository.findByTweetIdIn(g.getMemberTweetIds());
            if (members.size() < 2) continue; // 멤버가 이미 다른 경로로 정리됨(보관 등)

            Bookmark recommendedKeep = members.stream()
                    .max(Comparator.comparingInt(this::completeness))
                    .orElse(null);
            Set<String> defaultArchive = g.getSignal() == DuplicateSignal.QUOTE_LINK
                    ? Set.of()
                    : members.stream()
                            .map(Bookmark::getTweetId)
                            .filter(id -> recommendedKeep == null || !id.equals(recommendedKeep.getTweetId()))
                            .collect(Collectors.toSet());
            Map<String, String> normalizedText = members.stream()
                    .collect(Collectors.toMap(Bookmark::getTweetId, b -> normalize(b.getText())));

            views.add(new DuplicateGroupView(g.getId(), g.getType(), g.getSignal(), g.getScore(), members, defaultArchive, normalizedText));
        }
        return views;
    }

    @Transactional
    public DuplicateResolveResult resolve(Long groupId, String action, List<String> tweetIds) {
        DuplicateGroup group = duplicateGroupRepository.findById(groupId)
                .orElseThrow(() -> new EntityNotFoundException("그룹을 찾을 수 없음: " + groupId));

        if ("dismiss".equals(action)) {
            group.dismiss();
            return new DuplicateResolveResult(action, List.of());
        }
        if ("archive_selected".equals(action)) {
            List<String> targets = tweetIds == null ? List.of() : tweetIds.stream()
                    .filter(id -> group.getMemberTweetIds().contains(id))
                    .toList();
            if (!targets.isEmpty()) {
                bookmarkService.batchSetArchived(targets, true);
            }
            duplicateGroupRepository.delete(group);
            return new DuplicateResolveResult(action, targets);
        }
        throw new IllegalArgumentException("알 수 없는 action: " + action);
    }

    private int completeness(Bookmark b) {
        int score = 0;
        if (!b.getTags().isEmpty()) score++;
        if (b.getContentType() != null) score++;
        if (b.getText() != null) score += b.getText().length() / 1000;
        return score;
    }

    // 리트윗 접두어 제거, t.co 링크 마스킹(트윗마다 코드가 달라서 내용이 같아도 문자열이
    // 달라지는 걸 방지), 공백 정리, 소문자화(영문 표기 흔들림 방지).
    private String normalize(String text) {
        if (text == null) return "";
        String t = text.trim();
        if (t.startsWith("RT ")) t = t.substring(3);
        t = TCO_LINK.matcher(t).replaceAll("[LINK]");
        t = t.toLowerCase(Locale.ROOT);
        t = t.replaceAll("\\s+", " ").trim();
        return t;
    }

    private Set<String> shingle(String text, int n) {
        Set<String> result = new HashSet<>();
        if (text.length() < n) {
            if (!text.isEmpty()) result.add(text);
            return result;
        }
        for (int i = 0; i <= text.length() - n; i++) {
            result.add(text.substring(i, i + n));
        }
        return result;
    }

    private double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        int unionSize = a.size() + b.size() - intersection.size();
        return unionSize == 0 ? 0 : (double) intersection.size() / unionSize;
    }

    private void addEdge(UnionFind uf, Map<String, EdgeInfo> edges, String a, String b,
                          DuplicateSignal signal, double score) {
        uf.union(a, b);
        String key = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
        EdgeInfo existing = edges.get(key);
        if (existing == null || score > existing.score()) {
            edges.put(key, new EdgeInfo(a, b, signal, score));
        }
    }

    private String computeGroupKey(DuplicateType type, Set<String> members) {
        String joined = type.name() + ":" + members.stream().sorted().collect(Collectors.joining(","));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(joined.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record ComputedGroup(DuplicateType type, DuplicateSignal signal, double score, Set<String> members) {
    }

    private record EdgeInfo(String a, String b, DuplicateSignal signal, double score) {
    }

    private static class UnionFind {
        private final Map<String, String> parent = new HashMap<>();

        String find(String x) {
            parent.putIfAbsent(x, x);
            if (!parent.get(x).equals(x)) {
                parent.put(x, find(parent.get(x)));
            }
            return parent.get(x);
        }

        void union(String a, String b) {
            String ra = find(a), rb = find(b);
            if (!ra.equals(rb)) parent.put(ra, rb);
        }
    }
}
