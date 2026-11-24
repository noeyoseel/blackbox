package com.bookmarkdrawer.bookmark;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BookmarkService {

    private final BookmarkRepository bookmarkRepository;

    @Transactional
    public BookmarkIngestResponse ingest(List<TweetPayload> tweets) {
        Map<String, TweetPayload> payloadByTweetId = tweets.stream()
                .collect(Collectors.toMap(TweetPayload::id, t -> t, (a, b) -> a));
        Map<String, Bookmark> existingByTweetId = bookmarkRepository.findByTweetIdIn(payloadByTweetId.keySet()).stream()
                .collect(Collectors.toMap(Bookmark::getTweetId, b -> b));

        List<Bookmark> newBookmarks = payloadByTweetId.values().stream()
                .filter(t -> !existingByTweetId.containsKey(t.id()))
                .map(t -> new Bookmark(t.id(), t.text(), t.quotedText(), t.quotedTweetId(),
                        t.quotedAuthorName(), t.quotedAuthorHandle(), t.quotedAuthorAvatar(),
                        t.authorId(), t.authorName(), t.authorHandle(), t.authorAvatar(),
                        t.createdAt(), parseBookmarkOrder(t.bookmarkOrder()), toMediaList(t.media())))
                .toList();
        bookmarkRepository.saveAll(newBookmarks);

        // 이미 저장된 트윗이라도, 이번에 더 완전한 본문/인용문/작성자/미디어 정보가 들어오면 갱신한다.
        // (과거 잘린 본문으로 수집된 북마크를 재스크롤로 백필하기 위함)
        List<Bookmark> updatedBookmarks = existingByTweetId.values().stream()
                .filter(b -> {
                    TweetPayload payload = payloadByTweetId.get(b.getTweetId());
                    return b.refreshTextIfLonger(payload.text(), payload.quotedText(), payload.quotedTweetId(),
                            payload.quotedAuthorName(), payload.quotedAuthorHandle(), payload.quotedAuthorAvatar(),
                            parseBookmarkOrder(payload.bookmarkOrder()),
                            payload.authorName(), payload.authorHandle(), payload.authorAvatar(),
                            toMediaList(payload.media()));
                })
                .toList();
        bookmarkRepository.saveAll(updatedBookmarks);

        return new BookmarkIngestResponse(tweets.size(), newBookmarks.size(), existingByTweetId.size());
    }

    private Long parseBookmarkOrder(String bookmarkOrder) {
        if (bookmarkOrder == null) return null;
        try {
            return Long.parseLong(bookmarkOrder);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<BookmarkMedia> toMediaList(List<MediaItemPayload> media) {
        // Hibernate가 @ElementCollection을 merge할 때 clear()를 호출하므로 반드시 가변 리스트여야 한다.
        // List.of()/Stream.toList()는 불변이라 UnsupportedOperationException을 던진다.
        if (media == null) return new ArrayList<>();
        return media.stream()
                .map(m -> new BookmarkMedia(m.url(), m.type()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    // contentType/tag는 모두 선택적 필터다(홈의 "필터" 패널). 둘 다 비어있으면
    // 기존 무필터 피드 쿼리를 그대로 쓰고, 하나라도 있으면 필터 쿼리로 분기한다.
    public FeedPage feed(int page, int size, String sort, ContentType contentType, String tag) {
        // size+1을 Pageable의 페이지 크기로 쓰면 offset(= page * pageSize)이 페이지마다
        // size만큼씩 어긋나서, 스크롤을 계속하면 항목을 건너뛰다가 실제로는 더 남았는데도
        // hasMore가 false가 되는 버그가 있었다. offset은 size로 정확히 계산하고,
        // hasMore는 전체 개수와 비교해서 별도로 판단한다.
        Pageable pageable = PageRequest.of(page, size);
        String cleanTag = (tag == null || tag.isBlank()) ? null : tag.trim();
        boolean filtered = contentType != null || cleanTag != null;

        List<Bookmark> items;
        long total;
        if (filtered) {
            items = "oldest".equals(sort)
                    ? bookmarkRepository.findFilteredFeedOldestFirst(contentType, cleanTag, pageable)
                    : bookmarkRepository.findFilteredFeed(contentType, cleanTag, pageable);
            total = bookmarkRepository.countFilteredFeed(contentType, cleanTag);
        } else {
            items = "oldest".equals(sort)
                    ? bookmarkRepository.findFeedPageOldestFirst(pageable)
                    : bookmarkRepository.findFeedPage(pageable);
            total = bookmarkRepository.countByArchivedFalse();
        }
        boolean hasMore = (long) (page + 1) * size < total;
        return new FeedPage(items, hasMore);
    }

    // 홈 상단 "AI로 북마크 정리하기" 배너 노출 여부 판단용.
    public long countUnclassified() {
        return bookmarkRepository.countByArchivedFalseAndContentTypeIsNull();
    }

    @Transactional
    public TagsView updateTags(String tweetId, List<String> tags) {
        Bookmark bookmark = bookmarkRepository.findByTweetId(tweetId)
                .orElseThrow(() -> new EntityNotFoundException("트윗을 찾을 수 없음: " + tweetId));
        Set<String> cleaned = tags.stream()
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        bookmark.applyTags(cleaned);
        bookmarkRepository.save(bookmark);
        return new TagsView(bookmark.getTweetId(), bookmark.getTags());
    }

    @Transactional
    public void setArchived(String tweetId, boolean archived) {
        Bookmark bookmark = bookmarkRepository.findByTweetId(tweetId)
                .orElseThrow(() -> new EntityNotFoundException("트윗을 찾을 수 없음: " + tweetId));
        bookmark.setArchived(archived);
        bookmarkRepository.save(bookmark);
    }

    public List<Bookmark> listArchived() {
        return bookmarkRepository.findByArchivedTrueOrderByCollectedAtDesc();
    }

    @Transactional
    public void deletePermanently(String tweetId) {
        Bookmark bookmark = bookmarkRepository.findByTweetId(tweetId)
                .orElseThrow(() -> new EntityNotFoundException("트윗을 찾을 수 없음: " + tweetId));
        if (!bookmark.isArchived()) {
            throw new IllegalStateException("보관함에 있는 북마크만 영구 삭제할 수 있습니다.");
        }
        bookmarkRepository.delete(bookmark);
    }

    @Transactional
    public SyncCheckResult syncCheck(List<String> tweetIds, String minOrder, String maxOrder) {
        Long min = parseBookmarkOrder(minOrder);
        if (min == null) return new SyncCheckResult(0);
        Long max = parseBookmarkOrder(maxOrder);

        Set<String> presentIds = new HashSet<>(tweetIds);
        List<Bookmark> candidates = bookmarkRepository.findByBookmarkOrderRange(min, max);

        int archivedCount = 0;
        for (Bookmark bookmark : candidates) {
            if (!presentIds.contains(bookmark.getTweetId())) {
                bookmark.setArchived(true);
                archivedCount++;
            }
        }
        bookmarkRepository.saveAll(candidates);
        return new SyncCheckResult(archivedCount);
    }

    @Transactional
    public BookmarkMetadataView updateMetadata(String tweetId, ContentType contentType, String place) {
        Bookmark bookmark = bookmarkRepository.findByTweetId(tweetId)
                .orElseThrow(() -> new EntityNotFoundException("트윗을 찾을 수 없음: " + tweetId));
        bookmark.applyMetadata(contentType, place);
        bookmarkRepository.save(bookmark);
        return new BookmarkMetadataView(bookmark.getTweetId(), bookmark.getContentType(), bookmark.getPlace());
    }

    // 정리 모드 필터 조회. tagged 플래그가 아니라 tags IS EMPTY로 "태그 없음"을 판단한다
    // (Bookmark.applyTags는 빈 태그로도 tagged=true를 남기므로 tagged는 이 목적에 안 맞음).
    public OrganizePage organize(OrganizeFilter filter, int page, int size, String sort) {
        Pageable pageable = PageRequest.of(page, size);
        boolean oldest = "oldest".equals(sort);
        Page<Bookmark> result = switch (filter) {
            case NO_TAG -> oldest
                    ? bookmarkRepository.findNoTagCandidatesOldestFirst(pageable)
                    : bookmarkRepository.findNoTagCandidates(pageable);
            case NO_CONTENT_TYPE -> oldest
                    ? bookmarkRepository.findNoContentTypeCandidatesOldestFirst(pageable)
                    : bookmarkRepository.findNoContentTypeCandidates(pageable);
            case CANDIDATES -> oldest
                    ? bookmarkRepository.findAllCandidatesOldestFirst(pageable)
                    : bookmarkRepository.findAllCandidates(pageable);
        };
        return new OrganizePage(result.getContent(), result.hasNext(), result.getTotalElements());
    }

    // 아래 세 배치 메서드는 TagMergeService.applyMerge()와 같은 구조를 따른다:
    // tweetId로 대상을 모아(findByTweetIdIn) 메모리에서 도메인 메서드로 변경한 뒤 saveAll로 한 번에 반영.
    // 존재하지 않는 tweetId는 조용히 건너뛰고 notFound로만 보고한다(선택 당시와 처리 시점 사이에
    // 다른 곳에서 삭제/변경됐을 수 있으므로, 부분 실패로 전체 배치를 막지 않기 위함).

    @Transactional
    public BatchActionResult batchAddTags(List<String> tweetIds, List<String> tagsToAdd) {
        List<String> cleanedNewTags = tagsToAdd.stream()
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .toList();
        List<Bookmark> found = bookmarkRepository.findByTweetIdIn(new LinkedHashSet<>(tweetIds));
        for (Bookmark bookmark : found) {
            Set<String> merged = new LinkedHashSet<>(bookmark.getTags());
            merged.addAll(cleanedNewTags);
            bookmark.applyTags(merged);
        }
        bookmarkRepository.saveAll(found);
        return buildBatchResult(tweetIds, found);
    }

    // 배치 태그/보관과 달리 부분 업데이트다: contentType이 요청에 없으면(null) 손대지
    // 않고, place는 null이면 손대지 않지만 빈 문자열이면 명시적으로 지운다. "유형 지정"과
    // "장소 지정"이 액션바에서 서로 독립된 컨트롤이라, 하나만 바꿔도 다른 북마크들의 기존
    // 값을 실수로 날리지 않아야 하기 때문이다(개별 편집은 폼 하나에서 항상 둘 다 같이
    // 제출되므로 이 문제가 없어 Bookmark.applyMetadata를 그대로 씀).
    @Transactional
    public BatchActionResult batchUpdateMetadata(List<String> tweetIds, ContentType contentType, String place) {
        List<Bookmark> found = bookmarkRepository.findByTweetIdIn(new LinkedHashSet<>(tweetIds));
        for (Bookmark bookmark : found) {
            ContentType nextContentType = contentType != null ? contentType : bookmark.getContentType();
            String nextPlace = bookmark.getPlace();
            if (place != null) {
                nextPlace = place.isBlank() ? null : place.trim();
            }
            bookmark.applyMetadata(nextContentType, nextPlace);
        }
        bookmarkRepository.saveAll(found);
        return buildBatchResult(tweetIds, found);
    }

    @Transactional
    public BatchActionResult batchSetArchived(List<String> tweetIds, boolean archived) {
        List<Bookmark> found = bookmarkRepository.findByTweetIdIn(new LinkedHashSet<>(tweetIds));
        for (Bookmark bookmark : found) {
            bookmark.setArchived(archived);
        }
        bookmarkRepository.saveAll(found);
        return buildBatchResult(tweetIds, found);
    }

    private BatchActionResult buildBatchResult(List<String> requestedTweetIds, List<Bookmark> found) {
        Set<String> foundIds = found.stream()
                .map(Bookmark::getTweetId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> notFound = requestedTweetIds.stream()
                .distinct()
                .filter(id -> !foundIds.contains(id))
                .toList();
        return new BatchActionResult(requestedTweetIds.size(), new ArrayList<>(foundIds), notFound);
    }
}
