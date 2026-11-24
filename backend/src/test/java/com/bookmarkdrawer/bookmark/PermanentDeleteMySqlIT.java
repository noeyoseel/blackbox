package com.bookmarkdrawer.bookmark;

import com.bookmarkdrawer.duplicates.DuplicateGroup;
import com.bookmarkdrawer.duplicates.DuplicateGroupRepository;
import com.bookmarkdrawer.duplicates.DuplicateSignal;
import com.bookmarkdrawer.duplicates.DuplicateType;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 영구 삭제를 실제 MySQL에서 검증한다(SQL, 행 잠금, 트랜잭션 롤백). 가상 데이터만 쓴다.
//
// 기본 빌드에서는 건너뛴다. 실행하려면 비어 있는 로컬 전용 스키마를 만들고 환경변수를 지정한다:
//   BLACKBOX_IT_MYSQL_URL=jdbc:mysql://127.0.0.1:<port>/blackbox_it
//   BLACKBOX_IT_MYSQL_USER=<user>  BLACKBOX_IT_MYSQL_PASSWORD=<password>
// 테이블을 create-drop 하므로 URL이 로컬호스트의 blackbox_it 스키마일 때만 실행된다(실제 DB 보호).
@EnabledIfEnvironmentVariable(named = "BLACKBOX_IT_MYSQL_URL",
        matches = "jdbc:mysql://(127\\.0\\.0\\.1|localhost)(:\\d+)?/blackbox_it(\\?.*)?")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "ANTHROPIC_API_KEY=test-not-a-real-key",
        "BLACKBOX_AUTH_ENABLED=false",
        "blackbox.interest-map.enabled=false"
})
class PermanentDeleteMySqlIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("BLACKBOX_IT_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("BLACKBOX_IT_MYSQL_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("BLACKBOX_IT_MYSQL_PASSWORD", ""));
    }

    @Autowired BookmarkService bookmarkService;
    @Autowired BookmarkRepository bookmarkRepository;
    @Autowired DuplicateGroupRepository duplicateGroupRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired DataSource dataSource;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.execute("DROP TRIGGER IF EXISTS it_fail_bookmark_delete");
        for (String t : List.of("bookmark_tag", "bookmark_media", "duplicate_group_member", "duplicate_group", "bookmark")) {
            jdbc.execute("DELETE FROM " + t);
        }
    }

    // ---------- fixtures (가상 데이터) ----------

    private void bookmark(String tweetId, boolean archived, int tagCount, int mediaCount) {
        tx.executeWithoutResult(s -> {
            List<BookmarkMedia> media = new ArrayList<>();
            for (int i = 0; i < mediaCount; i++) media.add(new BookmarkMedia("https://example.invalid/" + tweetId + "/" + i, "photo"));
            Bookmark b = new Bookmark(tweetId, "fake text for " + tweetId, null, null, null, null, null,
                    null, null, null, null, null, 100L, media);
            Set<String> tags = new TreeSet<>();
            for (int i = 0; i < tagCount; i++) tags.add("fake-tag-" + i);
            if (tagCount > 0) b.applyTags(tags);
            b.setArchived(archived);
            bookmarkRepository.save(b);
        });
    }

    private long group(String key, boolean dismissed, String... members) {
        return tx.execute(s -> {
            DuplicateGroup g = new DuplicateGroup(DuplicateType.NEAR, DuplicateSignal.TEXT_SIMILAR, 0.9, key, Set.of(members));
            if (dismissed) g.dismiss();
            return duplicateGroupRepository.save(g).getId();
        });
    }

    private long idOf(String tweetId) {
        return jdbc.queryForObject("SELECT id FROM bookmark WHERE tweet_id = ?", Long.class, tweetId);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private Set<String> members(long groupId) {
        return new TreeSet<>(jdbc.queryForList("SELECT tweet_id FROM duplicate_group_member WHERE group_id = ?", String.class, groupId));
    }

    private int orphanMemberRows() {
        return count("SELECT COUNT(*) FROM duplicate_group_member d LEFT JOIN bookmark b ON b.tweet_id = d.tweet_id WHERE b.id IS NULL");
    }

    // ---------- tests ----------

    @Test
    void deletesTheArchivedBookmarkWithItsTagsAndMediaOnly() {
        bookmark("a", true, 3, 2);
        bookmark("keep", false, 2, 1);
        long a = idOf("a");

        bookmarkService.deletePermanently("a");

        assertThat(count("SELECT COUNT(*) FROM bookmark WHERE tweet_id = 'a'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM bookmark_tag WHERE bookmark_id = ?", a)).isZero();
        assertThat(count("SELECT COUNT(*) FROM bookmark_media WHERE bookmark_id = ?", a)).isZero();
        long keep = idOf("keep");
        assertThat(count("SELECT COUNT(*) FROM bookmark_tag WHERE bookmark_id = ?", keep)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM bookmark_media WHERE bookmark_id = ?", keep)).isEqualTo(1);
    }

    @Test
    void refusesActiveAndMissingBookmarksWithoutChangingAnything() {
        bookmark("active", false, 2, 1);

        assertThatThrownBy(() -> bookmarkService.deletePermanently("active")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> bookmarkService.deletePermanently("missing")).isInstanceOf(EntityNotFoundException.class);

        assertThat(count("SELECT COUNT(*) FROM bookmark")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM bookmark_tag")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM bookmark_media")).isEqualTo(1);
    }

    @Test
    void cleansDuplicateGroupMembershipForPendingAndDismissedGroups() {
        bookmark("a", true, 5, 1); // 태그가 가장 많아 p3 그룹의 "추천 유지" 대상이 되는 북마크
        for (String t : List.of("b", "c", "d", "e", "f", "g", "h", "i")) bookmark(t, false, 1, 0);
        long p3 = group("key-p3", false, "a", "b", "c");
        long p2 = group("key-p2", false, "a", "d");
        long d2 = group("key-d2", true, "a", "e");
        long d3 = group("key-d3", true, "a", "f", "g");
        long unrelated = group("key-u", false, "h", "i");

        bookmarkService.deletePermanently("a");

        assertThat(members(p3)).containsExactly("b", "c");
        assertThat(count("SELECT COUNT(*) FROM duplicate_group WHERE id = ? AND dismissed = false AND group_key = 'key-p3'", p3)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM duplicate_group WHERE id IN (?, ?)", p2, d2)).isZero();
        assertThat(members(p2)).isEmpty();
        assertThat(members(d2)).isEmpty();
        assertThat(members(d3)).containsExactly("f", "g");
        assertThat(count("SELECT COUNT(*) FROM duplicate_group WHERE id = ? AND dismissed = true AND group_key = 'key-d3'", d3)).isEqualTo(1);
        assertThat(members(unrelated)).containsExactly("h", "i");
        assertThat(orphanMemberRows()).isZero();
    }

    @Test
    void anyFailureRollsBackTheWholeDeleteIncludingGroupChanges() {
        bookmark("a", true, 3, 2);
        bookmark("d", false, 1, 0);
        bookmark("f", false, 1, 0);
        bookmark("g", false, 1, 0);
        long p2 = group("key-p2", false, "a", "d");
        long d3 = group("key-d3", true, "a", "f", "g");
        long a = idOf("a");
        // 태그·미디어·그룹 변경이 실행된 뒤 마지막 bookmark 삭제에서 실패하게 만든다(테스트 스키마 한정)
        jdbc.execute("CREATE TRIGGER it_fail_bookmark_delete BEFORE DELETE ON bookmark FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'it forced failure'");

        assertThatThrownBy(() -> bookmarkService.deletePermanently("a")).isInstanceOf(RuntimeException.class);

        assertThat(count("SELECT COUNT(*) FROM bookmark WHERE id = ? AND archived = true", a)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM bookmark_tag WHERE bookmark_id = ?", a)).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM bookmark_media WHERE bookmark_id = ?", a)).isEqualTo(2);
        assertThat(members(p2)).containsExactly("a", "d");
        assertThat(members(d3)).containsExactly("a", "f", "g");
    }

    @Test
    void concurrentDeletesOfTheSameBookmarkYieldOneSuccessAndOnlyNotFound() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 5; round++) {
                String id = "same-" + round;
                bookmark(id, true, 3, 2);
                bookmark("other-" + round, false, 1, 0);
                group("key-same-" + round, false, id, "other-" + round);

                List<Object> outcomes = runTogether(pool, 8, () -> bookmarkService.deletePermanently(id));

                assertThat(outcomes.stream().filter("ok"::equals).count()).as("round %d", round).isEqualTo(1);
                assertThat(outcomes.stream().filter(o -> !"ok".equals(o)))
                        .as("round %d: losers must be 404 (EntityNotFoundException), not 500", round)
                        .allMatch(o -> o instanceof EntityNotFoundException);
                assertThat(count("SELECT COUNT(*) FROM bookmark WHERE tweet_id = ?", id)).isZero();
                assertThat(orphanMemberRows()).isZero();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentDeletesOfBothMembersOfOneGroupBothSucceed() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                String p = "p-" + round, q = "q-" + round;
                bookmark(p, true, 1, 0);
                bookmark(q, true, 1, 0);
                long g = group("key-pq-" + round, true, p, q);
                CountDownLatch start = new CountDownLatch(1);
                Future<?> f1 = pool.submit(() -> { start.await(); bookmarkService.deletePermanently(p); return null; });
                Future<?> f2 = pool.submit(() -> { start.await(); bookmarkService.deletePermanently(q); return null; });
                start.countDown();
                f1.get(30, TimeUnit.SECONDS);
                f2.get(30, TimeUnit.SECONDS);

                assertThat(count("SELECT COUNT(*) FROM bookmark WHERE tweet_id IN (?, ?)", p, q)).isZero();
                assertThat(count("SELECT COUNT(*) FROM duplicate_group WHERE id = ?", g)).isZero();
                assertThat(orphanMemberRows()).isZero();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void unarchiveCommittedWhileDeleteWaitsIsRespected() throws Exception {
        bookmark("race", true, 2, 1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            // 다른 요청이 보관 해제를 진행 중(행 잠금 보유, 아직 커밋 전)
            try (var st = other.prepareStatement("UPDATE bookmark SET archived = false WHERE tweet_id = 'race'")) {
                st.executeUpdate();
            }
            Future<?> delete = pool.submit(() -> { bookmarkService.deletePermanently("race"); return null; });
            Thread.sleep(1500);
            assertThat(delete.isDone()).as("delete must wait for the row lock instead of reading a stale archived=true").isFalse();

            other.commit();

            assertThatThrownBy(() -> delete.get(30, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
        } finally {
            pool.shutdownNow();
        }
        Map<String, Object> row = jdbc.queryForMap("SELECT archived FROM bookmark WHERE tweet_id = 'race'");
        assertThat(row.get("archived")).isIn(false, 0, (byte) 0);
        assertThat(count("SELECT COUNT(*) FROM bookmark_tag WHERE bookmark_id = ?", idOf("race"))).isEqualTo(2);
    }

    private static List<Object> runTogether(ExecutorService pool, int n, Runnable action) throws Exception {
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Callable<Object> c = () -> {
                ready.countDown();
                start.await();
                try {
                    action.run();
                    return "ok";
                } catch (RuntimeException e) {
                    return e;
                }
            };
            futures.add(pool.submit(c));
        }
        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> f : futures) results.add(f.get(60, TimeUnit.SECONDS));
        return results;
    }
}
