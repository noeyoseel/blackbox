package com.bookmarkdrawer.bookmark;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface BookmarkRepository extends JpaRepository<Bookmark, Long> {

    boolean existsByTweetId(String tweetId);

    Optional<Bookmark> findByTweetId(String tweetId);

    List<Bookmark> findByTaggedFalse();

    List<Bookmark> findByTaggedTrue();

    List<Bookmark> findByTweetIdIn(Set<String> tweetIds);

    @Query("SELECT b FROM Bookmark b JOIN b.tags t WHERE t = :tag AND b.archived = false ORDER BY b.tweetCreatedAt DESC")
    List<Bookmark> findByTag(@Param("tag") String tag);

    List<Bookmark> findByArchivedFalseAndTaggedTrue();

    List<Bookmark> findByArchivedFalseAndTextContainingIgnoreCaseOrderByTweetCreatedAtDesc(String keyword);

    List<Bookmark> findByArchivedTrueOrderByCollectedAtDesc();

    List<Bookmark> findByFolderIdAndArchivedFalseOrderByCollectedAtDesc(Long folderId);

    long countByFolderIdAndArchivedFalse(Long folderId);

    List<Bookmark> findByFolderId(Long folderId);

    long countByArchivedFalse();

    @Query("SELECT DISTINCT t FROM Bookmark b JOIN b.tags t ORDER BY t")
    List<String> findAllDistinctTags();

    // 가장 최근에 북마크한 글이 맨 위로 오도록 정렬. bookmarkOrder가 없는 옛날 레코드는
    // 뒤로 밀어내되, 그 안에서는 수집 시각 역순으로 정렬한다. 보관 처리된 글은 피드에서 제외.
    @Query("SELECT b FROM Bookmark b WHERE b.archived = false " +
            "ORDER BY CASE WHEN b.bookmarkOrder IS NULL THEN 1 ELSE 0 END, b.bookmarkOrder DESC, b.collectedAt DESC")
    List<Bookmark> findFeedPage(Pageable pageable);

    // 가장 오래전에 북마크한 글이 맨 위. bookmarkOrder가 없는 옛날 레코드는 순서를 알 수 없으니
    // "가장 오래된 쪽"으로 취급해서 맨 앞으로 보낸다.
    @Query("SELECT b FROM Bookmark b WHERE b.archived = false " +
            "ORDER BY CASE WHEN b.bookmarkOrder IS NULL THEN 0 ELSE 1 END, b.bookmarkOrder ASC, b.collectedAt ASC")
    List<Bookmark> findFeedPageOldestFirst(Pageable pageable);

    // 트위터가 이번에 내려준 응답 구간[min, max] 안에 있어야 할 북마크 후보. bookmarkOrder가
    // 이 범위 안인데 응답에 없었다면 트위터에서 삭제된 것으로 보고 보관 처리하는 데 쓴다.
    // max가 null이면 "이 구간이 최상단(가장 최근)"이라는 뜻이라 위쪽은 무제한으로 본다.
    @Query("SELECT b FROM Bookmark b WHERE b.archived = false AND b.bookmarkOrder IS NOT NULL " +
            "AND b.bookmarkOrder >= :min AND (:max IS NULL OR b.bookmarkOrder <= :max)")
    List<Bookmark> findByBookmarkOrderRange(@Param("min") Long min, @Param("max") Long max);
}
