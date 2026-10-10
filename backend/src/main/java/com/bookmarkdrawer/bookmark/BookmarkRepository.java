package com.bookmarkdrawer.bookmark;

import org.springframework.data.domain.Page;
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

    List<Bookmark> findByArchivedFalse();

    List<Bookmark> findByContentTypeIsNull();

    List<Bookmark> findByTaggedTrue();

    List<Bookmark> findByTweetIdIn(Set<String> tweetIds);

    // 정렬은 피드(findFeedPage)와 같은 기준을 쓴다. tweetCreatedAt은 트위터 원본 문자열
    // ("Wed Oct 10 20:19:24 +0000 2018")이라 사전순 정렬이 곧 요일 이름 정렬이 되어 무의미했고,
    // collectedAt은 배치 스크랩 탓에 1,439건 중 99.7%가 동률이라 정렬 키로 쓸 수 없다.
    // bookmarkOrder(트위터 sortIndex)만이 "최근에 북마크한 순"이라는 실제 의미를 가진다.
    @Query("SELECT b FROM Bookmark b JOIN b.tags t WHERE t = :tag AND b.archived = false " +
            "ORDER BY CASE WHEN b.bookmarkOrder IS NULL THEN 1 ELSE 0 END, b.bookmarkOrder DESC, b.collectedAt DESC")
    List<Bookmark> findByTag(@Param("tag") String tag);

    // 태그 병합 전용: 보관된 북마크의 태그까지 통합해야 나중에 복원했을 때 옛 표기가 부활하지 않는다.
    @Query("SELECT b FROM Bookmark b JOIN b.tags t WHERE t = :tag")
    List<Bookmark> findByTagIncludingArchived(@Param("tag") String tag);

    // 태그 집계를 SQL로 수행한다. 기존에는 북마크 엔티티를 전부 로드한 뒤 Java에서 세느라
    // @ElementCollection 지연로딩 때문에 북마크 수만큼 추가 쿼리가 발생했다(N+1).
    // 반환 행은 [String tag, Long count] — DTO 조립은 서비스에서 한다.
    @Query("SELECT t, COUNT(b) FROM Bookmark b JOIN b.tags t WHERE b.archived = false " +
            "GROUP BY t HAVING COUNT(b) >= :minCount ORDER BY COUNT(b) DESC, t ASC")
    List<Object[]> countTagsWithMinimum(@Param("minCount") long minCount);

    // 표기 변형 탐지용. 병합이 보관 북마크까지 대상으로 하므로 집계도 archived를 포함한다.
    @Query("SELECT t, COUNT(b) FROM Bookmark b JOIN b.tags t GROUP BY t ORDER BY COUNT(b) DESC, t ASC")
    List<Object[]> countAllTagsIncludingArchived();

    List<Bookmark> findByArchivedFalseAndTaggedTrue();

    List<Bookmark> findByArchivedFalseAndTextContainingIgnoreCaseOrderByTweetCreatedAtDesc(String keyword);

    // 본문과 태그를 함께 검색한다. 태그 적용 6,739회 중 4,709회(69.9%)가 본문에 없는 문자열이라
    // 본문만 뒤지면 그 정보에 도달할 방법이 없었다.
    // - LEFT JOIN이어야 태그가 없는 북마크의 본문 매칭이 유지된다(INNER면 누락된다).
    // - 태그 여러 개가 동시에 매칭되면 같은 북마크가 중복되므로 DISTINCT가 필요하다.
    @Query(value = "SELECT DISTINCT b FROM Bookmark b LEFT JOIN b.tags t " +
            "WHERE b.archived = false AND (" +
            "  LOWER(b.text) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "  OR LOWER(t) LIKE LOWER(CONCAT('%', :q, '%'))) " +
            "ORDER BY CASE WHEN b.bookmarkOrder IS NULL THEN 1 ELSE 0 END, b.bookmarkOrder DESC, b.collectedAt DESC",
            countQuery = "SELECT COUNT(DISTINCT b) FROM Bookmark b LEFT JOIN b.tags t " +
                    "WHERE b.archived = false AND (" +
                    "  LOWER(b.text) LIKE LOWER(CONCAT('%', :q, '%')) " +
                    "  OR LOWER(t) LIKE LOWER(CONCAT('%', :q, '%')))")
    Page<Bookmark> searchByTextOrTag(@Param("q") String q, Pageable pageable);

    List<Bookmark> findByArchivedTrueOrderByCollectedAtDesc();

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

    // --- 정리 모드: tagged 플래그가 아니라 tags 컬렉션이 실제로 비어있는지로 판단한다.
    // (tagged=true인데 태그가 0개인 경우가 있을 수 있음 — Bookmark.applyTags 참고)

    @Query(value = "SELECT b FROM Bookmark b WHERE b.archived = false AND b.tags IS EMPTY " +
            "ORDER BY CASE WHEN b.bookmarkOrder IS NULL THEN 1 ELSE 0 END, b.bookmarkOrder DESC, b.collectedAt DESC",
            countQuery = "SELECT COUNT(b) FROM Bookmark b WHERE b.archived = false AND b.tags IS EMPTY")
    Page<Bookmark> findNoTagCandidates(Pageable pageable);

    @Query(value = "SELECT b FROM Bookmark b WHERE b.archived = false AND b.tags IS EMPTY " +
            "ORDER BY CASE WHEN b.bookmarkOrder IS NULL THEN 0 ELSE 1 END, b.bookmarkOrder ASC, b.collectedAt ASC",
            countQuery = "SELECT COUNT(b) FROM Bookmark b WHERE b.archived = false AND b.tags IS EMPTY")
    Page<Bookmark> findNoTagCandidatesOldestFirst(Pageable pageable);

    // place는 필터에서 제외한다 — 장소가 없는 게 정상인 콘텐츠(개발/재테크 정보 등)가 많아서
    // place IS NULL을 "미정리"로 취급하면 대부분의 정상 콘텐츠가 영원히 후보에 남는다.
    @Query(value = "SELECT b FROM Bookmark b WHERE b.archived = false AND b.contentType IS NULL " +
            "ORDER BY CASE WHEN b.bookmarkOrder IS NULL THEN 1 ELSE 0 END, b.bookmarkOrder DESC, b.collectedAt DESC",
            countQuery = "SELECT COUNT(b) FROM Bookmark b WHERE b.archived = false AND b.contentType IS NULL")
    Page<Bookmark> findNoContentTypeCandidates(Pageable pageable);

    @Query(value = "SELECT b FROM Bookmark b WHERE b.archived = false AND b.contentType IS NULL " +
            "ORDER BY CASE WHEN b.bookmarkOrder IS NULL THEN 0 ELSE 1 END, b.bookmarkOrder ASC, b.collectedAt ASC",
            countQuery = "SELECT COUNT(b) FROM Bookmark b WHERE b.archived = false AND b.contentType IS NULL")
    Page<Bookmark> findNoContentTypeCandidatesOldestFirst(Pageable pageable);

    @Query(value = "SELECT b FROM Bookmark b WHERE b.archived = false " +
            "AND (b.tags IS EMPTY OR b.contentType IS NULL) " +
            "ORDER BY CASE WHEN b.bookmarkOrder IS NULL THEN 1 ELSE 0 END, b.bookmarkOrder DESC, b.collectedAt DESC",
            countQuery = "SELECT COUNT(b) FROM Bookmark b WHERE b.archived = false " +
                    "AND (b.tags IS EMPTY OR b.contentType IS NULL)")
    Page<Bookmark> findAllCandidates(Pageable pageable);

    @Query(value = "SELECT b FROM Bookmark b WHERE b.archived = false " +
            "AND (b.tags IS EMPTY OR b.contentType IS NULL) " +
            "ORDER BY CASE WHEN b.bookmarkOrder IS NULL THEN 0 ELSE 1 END, b.bookmarkOrder ASC, b.collectedAt ASC",
            countQuery = "SELECT COUNT(b) FROM Bookmark b WHERE b.archived = false " +
                    "AND (b.tags IS EMPTY OR b.contentType IS NULL)")
    Page<Bookmark> findAllCandidatesOldestFirst(Pageable pageable);

    // --- 홈 필터(Content Type / Tag). 정리 모드의 "없음" 필터와 달리 특정 값으로
    // 좁히는 탐색용이다. 두 조건 모두 선택적이라 findByBookmarkOrderRange와 같은
    // ":param IS NULL OR ..." 패턴을 쓴다. 태그는 @ElementCollection이라 JOIN 대신
    // MEMBER OF로 존재 여부만 확인한다(다른 태그가 있어도 매칭에 영향 없음).
    @Query("SELECT b FROM Bookmark b WHERE b.archived = false " +
            "AND (:contentType IS NULL OR b.contentType = :contentType) " +
            "AND (:tag IS NULL OR :tag MEMBER OF b.tags) " +
            "ORDER BY CASE WHEN b.bookmarkOrder IS NULL THEN 1 ELSE 0 END, b.bookmarkOrder DESC, b.collectedAt DESC")
    List<Bookmark> findFilteredFeed(@Param("contentType") ContentType contentType, @Param("tag") String tag,
                                     Pageable pageable);

    @Query("SELECT b FROM Bookmark b WHERE b.archived = false " +
            "AND (:contentType IS NULL OR b.contentType = :contentType) " +
            "AND (:tag IS NULL OR :tag MEMBER OF b.tags) " +
            "ORDER BY CASE WHEN b.bookmarkOrder IS NULL THEN 0 ELSE 1 END, b.bookmarkOrder ASC, b.collectedAt ASC")
    List<Bookmark> findFilteredFeedOldestFirst(@Param("contentType") ContentType contentType, @Param("tag") String tag,
                                                Pageable pageable);

    @Query("SELECT COUNT(b) FROM Bookmark b WHERE b.archived = false " +
            "AND (:contentType IS NULL OR b.contentType = :contentType) " +
            "AND (:tag IS NULL OR :tag MEMBER OF b.tags)")
    long countFilteredFeed(@Param("contentType") ContentType contentType, @Param("tag") String tag);

    // AI 자동분류 배너 노출 여부 판단용.
    long countByArchivedFalseAndContentTypeIsNull();
}
