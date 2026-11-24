package com.bookmarkdrawer.interestmap;

import com.bookmarkdrawer.bookmark.Bookmark;
import com.bookmarkdrawer.bookmark.BookmarkRepository;
import jakarta.annotation.PostConstruct;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 9차 embedding 실험(임베딩+clustering)이 만든 결과 파일 두 개를 읽기만 하는 서비스다.
// embedding 재생성, clustering 재실행은 여기서 절대 하지 않는다 — 파일이 없으면 빈 결과를
// 돌려주고(화면에서는 안내 문구), Bookmark 조회도 findByTweetIdIn 읽기 전용 조회뿐이다.
@Slf4j
@Service
@RequiredArgsConstructor
public class InterestMapService {

    // 배포본마다 다른 clustering 결과 캐시를 가리킬 수 있도록 디렉토리를 설정 가능하게 뺐다
    // (예: 공개 데모 배포는 별도로 재계산한 안전한 서브셋 캐시를 가리키는 식). 기본값은 실사용
    // 배포가 써 온 경로 그대로.
    @Value("${blackbox.interest-map.cache-dir:.embedding-cache}")
    private String cacheDir;

    private File jsonFile;
    private File txtFile;
    private File umapFile;
    private File labelsFile;

    @PostConstruct
    private void initFiles() {
        jsonFile = new File(cacheDir, "clustering-1415.json");
        txtFile = new File(cacheDir, "clustering-1415.txt");
        umapFile = new File(cacheDir, "umap-1415.json");
        labelsFile = new File(cacheDir, "cluster-labels-1415.json");
    }

    private static final int REPRESENTATIVE_COUNT = 5;
    private static final int MAX_CLUSTER_NAME_LENGTH = 40;
    private static final int TEXT_PREVIEW_LENGTH = 180;
    private static final int UMAP_TEXT_PREVIEW_LENGTH = 80;

    // clustering-1415.txt의 "대표 5개:" 블록에서 tweetId만 뽑아온다 — 이 파일은 clustering
    // 실행 시점에 embedding 중심(centroid)과의 거리로 이미 계산해 저장해 둔 결과라, 여기서
    // 다시 계산하지 않는다(=clustering 재실행 아님, 이미 있는 결과를 읽기만 함).
    private static final Pattern CLUSTER_HEADER = Pattern.compile("^---------- Cluster #(\\d+) ");
    private static final Pattern REP_LINE = Pattern.compile("^\\s*\\d+\\.\\s+tweetId=(\\S+)");

    // BookmarkRepository.findFeedPage와 같은 기준("최근에 북마크한 순") — bookmarkOrder가 없는
    // 옛날 레코드는 뒤로 밀리고, 그 안에서는 collectedAt 역순.
    private static final Comparator<Bookmark> RECENT_FIRST =
            Comparator.comparing(Bookmark::getBookmarkOrder, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(Bookmark::getCollectedAt, Comparator.reverseOrder());

    private final BookmarkRepository bookmarkRepository;
    private final ObjectMapper objectMapper;

    // 데모/공개용 배포에서 클러스터 이름·트윗 내용이 노출되지 않도록 기능 전체를 끌 수 있는
    // 스위치. 꺼져 있으면 실험 결과 파일이 이미지에 들어있어도 전부 "결과 없음"으로 취급한다.
    @Value("${blackbox.interest-map.enabled:true}")
    private boolean enabled;

    public InterestMapOverview loadOverview() {
        if (!enabled) {
            return new InterestMapOverview(0, 0, 0, List.of());
        }
        JsonNode root = readJson();
        if (root == null) {
            return new InterestMapOverview(0, 0, 0, List.of());
        }

        Map<Integer, List<String>> repsByCluster = parseRepresentatives(txtFile);
        List<JsonNode> clusterNodes = new ArrayList<>();
        root.path("clusters").forEach(clusterNodes::add);

        // 대표 tweetId를 모아서 DB 조회는 클러스터당 한 번이 아니라 전체 한 번만 한다.
        Map<Integer, List<String>> effectiveReps = new LinkedHashMap<>();
        Set<String> allRepTweetIds = new LinkedHashSet<>();
        for (JsonNode c : clusterNodes) {
            int number = c.path("clusterNumber").asInt();
            List<String> reps = repsByCluster.get(number);
            if (reps == null || reps.isEmpty()) {
                // txt 파싱 실패/누락 시 JSON에 저장된 순서상 앞 5개로 대체한다 — 진짜 medoid
                // 순서는 아니지만, 결과 파일이 없는 것보다는 화면을 비워두지 않는 쪽을 택했다.
                reps = firstN(c.path("memberTweetIds"), REPRESENTATIVE_COUNT);
            }
            effectiveReps.put(number, reps);
            allRepTweetIds.addAll(reps);
        }
        Map<String, Bookmark> byId = fetchBookmarks(allRepTweetIds);
        Map<Integer, String> names = loadClusterNames();

        List<ClusterSummary> clusters = new ArrayList<>();
        for (JsonNode c : clusterNodes) {
            int number = c.path("clusterNumber").asInt();
            int size = c.path("size").asInt();
            List<BookmarkPreview> previews = effectiveReps.getOrDefault(number, List.of()).stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .map(this::toPreview)
                    .toList();
            clusters.add(new ClusterSummary(number, size, names.get(number), previews));
        }

        return new InterestMapOverview(
                root.path("totalBookmarks").asInt(),
                root.path("clusterCount").asInt(),
                root.path("noiseCount").asInt(),
                clusters);
    }

    // 선택한 cluster를 "훑어보며 필요한 것만 처리"할 수 있어야 한다는 요구(8번)에 따라, 여기서는
    // 경량 미리보기가 아니라 Bookmark 엔티티를 그대로 반환한다 — FeedPage가 이미 하는 것과 같은
    // 패턴(태그/보관/폴더 등 기존 편집 기능을 클라이언트에서 그대로 재사용하기 위함). DB는
    // 읽기만 하고 여기서 아무것도 저장/수정하지 않는다.
    public List<Bookmark> loadClusterMembers(int clusterNumber) {
        if (!enabled) {
            return List.of();
        }
        JsonNode root = readJson();
        if (root == null) {
            return List.of();
        }
        for (JsonNode c : root.path("clusters")) {
            if (c.path("clusterNumber").asInt() == clusterNumber) {
                List<String> memberIds = new ArrayList<>();
                c.path("memberTweetIds").forEach(n -> memberIds.add(n.asText()));
                return orderedBookmarks(memberIds);
            }
        }
        return List.of();
    }

    public List<Bookmark> loadNoise() {
        if (!enabled) {
            return List.of();
        }
        JsonNode root = readJson();
        if (root == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        root.path("noiseTweetIds").forEach(n -> ids.add(n.asText()));
        return orderedBookmarks(ids);
    }

    // 파일에 저장된 순서(=clustering 계산 시 표본을 훑은 순서, 대략 DB id 오름차순)는 무시하고
    // 최근에 북마크한 순으로 다시 정렬한다 — 메인 피드와 동일한 기준을 쓰기 위함.
    private List<Bookmark> orderedBookmarks(List<String> tweetIds) {
        if (tweetIds.isEmpty()) {
            return List.of();
        }
        Map<String, Bookmark> byId = fetchBookmarks(new LinkedHashSet<>(tweetIds));
        return byId.values().stream().sorted(RECENT_FIRST).toList();
    }

    public boolean hasUmapData() {
        return enabled && umapFile.exists();
    }

    // .embedding-cache/umap-1415.json은 일회성 preprocessing 스크립트(UMAP, 로컬 계산,
    // API 호출 없음)가 미리 만들어 둔 결과다 — 여기서는 좌표를 다시 계산하지 않고 읽기만
    // 하고, 미리보기 텍스트/링크를 붙이기 위해 Bookmark를 한 번에 배치 조회한다.
    public List<UmapPoint> loadUmapPoints() {
        if (!enabled || !umapFile.exists()) {
            log.warn("UMAP 결과 파일이 없습니다: {}", umapFile.getPath());
            return List.of();
        }
        JsonNode arr;
        try {
            arr = objectMapper.readTree(umapFile);
        } catch (IOException e) {
            log.error("UMAP 결과 파일 파싱 실패: {}", umapFile.getPath(), e);
            return List.of();
        }

        List<String> tweetIds = new ArrayList<>();
        arr.forEach(n -> tweetIds.add(n.path("tweetId").asText()));
        Map<String, Bookmark> byId = fetchBookmarks(new LinkedHashSet<>(tweetIds));

        List<UmapPoint> points = new ArrayList<>();
        for (JsonNode n : arr) {
            String tweetId = n.path("tweetId").asText();
            Bookmark b = byId.get(tweetId);
            if (b == null) {
                continue; // 그 사이 삭제됐을 수 있음 — 조용히 건너뛴다.
            }
            JsonNode clusterNode = n.path("cluster");
            Integer cluster = clusterNode.isNull() || clusterNode.isMissingNode() ? null : clusterNode.asInt();
            points.add(new UmapPoint(
                    tweetId,
                    n.path("x").asDouble(),
                    n.path("y").asDouble(),
                    cluster,
                    truncate(b.getText(), UMAP_TEXT_PREVIEW_LENGTH),
                    "https://x.com/i/status/" + tweetId));
        }
        return points;
    }

    // .embedding-cache/cluster-labels-1415.json은 별도 라운드에서 결정론적으로(태그 빈도) +
    // 사람이 상위 개념으로 다듬어 미리 만들어 둔 결과다 — 여기서 새로 이름을 짓지 않고
    // 읽기만 한다. 파일이 없으면 빈 맵을 돌려주고, 화면에서는 "Cluster #N"으로 대체 표시한다.
    private Map<Integer, String> loadClusterNames() {
        if (!labelsFile.exists()) {
            return Map.of();
        }
        try {
            JsonNode arr = objectMapper.readTree(labelsFile);
            Map<Integer, String> names = new LinkedHashMap<>();
            for (JsonNode row : arr) {
                names.put(row.path("clusterId").asInt(), row.path("name").asText(null));
            }
            return names;
        } catch (IOException e) {
            log.warn("cluster 이름 파일 파싱 실패, 번호만 표시합니다: {}", e.getMessage());
            return Map.of();
        }
    }

    // cluster 이름만 수정한다 — 결정론적으로 만들어 둔 이름을 사람이 다듬는 용도다. cluster
    // 구성(어느 북마크가 어느 cluster인지)이나 clustering-1415.json/.txt는 전혀 건드리지 않고,
    // 오직 cluster-labels-1415.json의 name 필드 하나만 읽기-수정-쓰기 한다.
    public String updateClusterName(int clusterNumber, String name) {
        if (!enabled) {
            throw new IllegalStateException("Interest Map 기능이 비활성화되어 있습니다.");
        }
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("이름은 비어 있을 수 없습니다.");
        }
        if (trimmed.length() > MAX_CLUSTER_NAME_LENGTH) {
            trimmed = trimmed.substring(0, MAX_CLUSTER_NAME_LENGTH);
        }
        if (!labelsFile.exists()) {
            throw new IllegalStateException("cluster 이름 파일이 없습니다: " + labelsFile.getPath());
        }

        ArrayNode arr;
        try {
            JsonNode root = objectMapper.readTree(labelsFile);
            if (!root.isArray()) {
                throw new IllegalStateException("cluster 이름 파일 형식이 올바르지 않습니다.");
            }
            arr = (ArrayNode) root;
        } catch (IOException e) {
            throw new IllegalStateException("cluster 이름 파일을 읽을 수 없습니다: " + e.getMessage(), e);
        }

        boolean found = false;
        for (JsonNode node : arr) {
            if (node.path("clusterId").asInt() == clusterNumber) {
                ((ObjectNode) node).put("name", trimmed);
                found = true;
                break;
            }
        }
        if (!found) {
            throw new NoSuchElementException("cluster를 찾을 수 없습니다: " + clusterNumber);
        }

        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(labelsFile, arr);
        } catch (IOException e) {
            throw new IllegalStateException("cluster 이름 파일 저장 실패: " + e.getMessage(), e);
        }
        return trimmed;
    }

    private JsonNode readJson() {
        if (!jsonFile.exists()) {
            log.warn("Interest Map 결과 파일이 없습니다: {} — 9차 실험(fullcluster 모드)을 먼저 실행해야 합니다.",
                    jsonFile.getPath());
            return null;
        }
        try {
            return objectMapper.readTree(jsonFile);
        } catch (IOException e) {
            log.error("Interest Map 결과 파일 파싱 실패: {}", jsonFile.getPath(), e);
            return null;
        }
    }

    private Map<Integer, List<String>> parseRepresentatives(File txtFile) {
        Map<Integer, List<String>> result = new LinkedHashMap<>();
        if (!txtFile.exists()) {
            return result;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(txtFile), StandardCharsets.UTF_8))) {
            int currentCluster = -1;
            boolean inRepsBlock = false;
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher headerMatcher = CLUSTER_HEADER.matcher(line);
                if (headerMatcher.find()) {
                    currentCluster = Integer.parseInt(headerMatcher.group(1));
                    inRepsBlock = false;
                    continue;
                }
                if (currentCluster < 0) {
                    continue;
                }
                if (line.startsWith("대표 5개:") || line.startsWith("대표 5개")) {
                    inRepsBlock = true;
                    continue;
                }
                if (line.startsWith("전체 구성원:")) {
                    inRepsBlock = false;
                    continue;
                }
                if (inRepsBlock) {
                    Matcher repMatcher = REP_LINE.matcher(line);
                    if (repMatcher.find()) {
                        result.computeIfAbsent(currentCluster, k -> new ArrayList<>()).add(repMatcher.group(1));
                    }
                }
            }
        } catch (IOException e) {
            log.warn("대표 북마크 파싱 실패, JSON 순서상 앞 {}개로 대체합니다: {}", REPRESENTATIVE_COUNT, e.getMessage());
        }
        return result;
    }

    private List<String> firstN(JsonNode arrayNode, int n) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < arrayNode.size() && i < n; i++) {
            result.add(arrayNode.get(i).asText());
        }
        return result;
    }

    private Map<String, Bookmark> fetchBookmarks(Set<String> tweetIds) {
        if (tweetIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Bookmark> byId = new LinkedHashMap<>();
        for (Bookmark b : bookmarkRepository.findByTweetIdIn(new HashSet<>(tweetIds))) {
            byId.put(b.getTweetId(), b);
        }
        return byId;
    }

    private BookmarkPreview toPreview(Bookmark b) {
        return new BookmarkPreview(
                b.getTweetId(),
                truncate(b.getText(), TEXT_PREVIEW_LENGTH),
                new ArrayList<>(b.getTags()),
                "https://x.com/i/status/" + b.getTweetId());
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > maxLen ? oneLine.substring(0, maxLen) + "..." : oneLine;
    }
}
