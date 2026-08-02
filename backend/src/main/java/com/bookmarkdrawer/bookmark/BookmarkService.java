package com.bookmarkdrawer.bookmark;

import com.bookmarkdrawer.folder.Folder;
import com.bookmarkdrawer.folder.FolderRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
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
    private final FolderRepository folderRepository;

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

    public FeedPage feed(int page, int size, String sort) {
        // size+1을 Pageable의 페이지 크기로 쓰면 offset(= page * pageSize)이 페이지마다
        // size만큼씩 어긋나서, 스크롤을 계속하면 항목을 건너뛰다가 실제로는 더 남았는데도
        // hasMore가 false가 되는 버그가 있었다. offset은 size로 정확히 계산하고,
        // hasMore는 전체 개수와 비교해서 별도로 판단한다.
        Pageable pageable = PageRequest.of(page, size);
        List<Bookmark> items = "oldest".equals(sort)
                ? bookmarkRepository.findFeedPageOldestFirst(pageable)
                : bookmarkRepository.findFeedPage(pageable);
        long total = bookmarkRepository.countByArchivedFalse();
        boolean hasMore = (long) (page + 1) * size < total;
        return new FeedPage(items, hasMore);
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
    public void assignFolder(String tweetId, Long folderId) {
        Bookmark bookmark = bookmarkRepository.findByTweetId(tweetId)
                .orElseThrow(() -> new EntityNotFoundException("트윗을 찾을 수 없음: " + tweetId));
        if (folderId == null) {
            bookmark.assignFolder(null, null);
        } else {
            Folder folder = folderRepository.findById(folderId)
                    .orElseThrow(() -> new EntityNotFoundException("폴더를 찾을 수 없음: " + folderId));
            bookmark.assignFolder(folder.getId(), folder.getName());
        }
        bookmarkRepository.save(bookmark);
    }
}
