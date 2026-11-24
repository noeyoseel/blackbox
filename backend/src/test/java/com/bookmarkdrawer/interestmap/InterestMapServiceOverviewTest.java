package com.bookmarkdrawer.interestmap;

import com.bookmarkdrawer.bookmark.Bookmark;
import com.bookmarkdrawer.bookmark.BookmarkRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Interest Map 개요 수치가 결과 파일 당시 값이 아니라 DB에 남은 북마크 기준인지(가상 데이터).
class InterestMapServiceOverviewTest {

    @TempDir
    Path cacheDir;

    private BookmarkRepository repository;
    private InterestMapService service;

    private static final String CLUSTERING_JSON = """
            {"threshold":0.6,"totalBookmarks":9,"clusterCount":3,"noiseCount":2,
             "clusters":[
               {"clusterNumber":1,"size":3,"memberTweetIds":["a1","a2","a3"]},
               {"clusterNumber":2,"size":2,"memberTweetIds":["b1","b2"]},
               {"clusterNumber":3,"size":2,"memberTweetIds":["c1","c2"]}],
             "noiseTweetIds":["n1","n2"]}
            """;
    private static final String CLUSTERING_TXT = """
            ---------- Cluster #1 (size=3) ----------
            대표 5개:
              1. tweetId=a2 fake
              2. tweetId=a1 fake
            전체 구성원:
            ---------- Cluster #2 (size=2) ----------
            대표 5개:
              1. tweetId=b1 fake
            전체 구성원:
            ---------- Cluster #3 (size=2) ----------
            대표 5개:
              1. tweetId=c1 fake
            전체 구성원:
            """;
    private static final String LABELS_JSON = """
            [{"clusterId":1,"size":3,"name":"가짜A"},{"clusterId":2,"size":2,"name":"가짜B"},{"clusterId":3,"size":2,"name":"가짜C"}]
            """;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(cacheDir.resolve("clustering-1415.json"), CLUSTERING_JSON, StandardCharsets.UTF_8);
        Files.writeString(cacheDir.resolve("clustering-1415.txt"), CLUSTERING_TXT, StandardCharsets.UTF_8);
        Files.writeString(cacheDir.resolve("cluster-labels-1415.json"), LABELS_JSON, StandardCharsets.UTF_8);
        repository = mock(BookmarkRepository.class);
        service = new InterestMapService(repository, new ObjectMapper());
        ReflectionTestUtils.setField(service, "cacheDir", cacheDir.toString());
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.invokeMethod(service, "initFiles");
    }

    private void dbContains(String... tweetIds) {
        Set<String> present = Set.of(tweetIds);
        when(repository.findExistingTweetIds(anySet())).thenAnswer(inv -> {
            Set<String> asked = inv.getArgument(0);
            return asked.stream().filter(present::contains).toList();
        });
        when(repository.findByTweetIdIn(anySet())).thenAnswer(inv -> {
            Set<String> asked = inv.getArgument(0);
            return asked.stream().filter(present::contains).map(InterestMapServiceOverviewTest::bookmark).toList();
        });
    }

    private static Bookmark bookmark(String tweetId) {
        return new Bookmark(tweetId, "fake " + tweetId, null, null, null, null, null,
                null, null, null, null, null, 1L, List.of());
    }

    private String filesHash() throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var files = Files.list(cacheDir)) {
            for (Path p : files.sorted().toList()) md.update(Files.readAllBytes(p));
        }
        return HexFormat.of().formatHex(md.digest());
    }

    @Test
    void withNothingDeletedNumbersMatchTheFile() {
        dbContains("a1", "a2", "a3", "b1", "b2", "c1", "c2", "n1", "n2");

        InterestMapOverview o = service.loadOverview();

        assertThat(o.totalBookmarks()).isEqualTo(9);
        assertThat(o.clusterCount()).isEqualTo(3);
        assertThat(o.noiseCount()).isEqualTo(2);
        assertThat(o.clusters()).extracting(ClusterSummary::number).containsExactly(1, 2, 3);
        assertThat(o.clusters()).extracting(ClusterSummary::size).containsExactly(3, 2, 2);
    }

    @Test
    void countsOnlyBookmarksStillInTheDatabase() throws Exception {
        String before = filesHash();
        // a2·a3(cluster 1의 대표 포함), c1·c2(cluster 3 전체), n2(미분류) 삭제됨
        dbContains("a1", "b1", "b2", "n1");

        InterestMapOverview o = service.loadOverview();

        assertThat(o.totalBookmarks()).isEqualTo(4);          // 1 + 2 + 미분류 1
        assertThat(o.clusterCount()).isEqualTo(2);            // 비어 버린 cluster 3 제외
        assertThat(o.noiseCount()).isEqualTo(1);
        // 크기가 바뀐 뒤에도 크기 내림차순(cluster 2가 앞으로)
        assertThat(o.clusters()).extracting(ClusterSummary::number).containsExactly(2, 1);
        assertThat(o.clusters()).extracting(ClusterSummary::size).containsExactly(2, 1);
        ClusterSummary one = o.clusters().stream().filter(c -> c.number() == 1).findFirst().orElseThrow();
        assertThat(one.name()).isEqualTo("가짜A");
        assertThat(one.representatives()).extracting(BookmarkPreview::tweetId).containsExactly("a1");
        // 결과 파일은 읽기만 한다
        assertThat(filesHash()).isEqualTo(before);
    }

    @Test
    void everythingDeletedShowsAnEmptyOverviewWithoutErrors() {
        dbContains();

        InterestMapOverview o = service.loadOverview();

        assertThat(o.totalBookmarks()).isZero();
        assertThat(o.clusterCount()).isZero();
        assertThat(o.noiseCount()).isZero();
        assertThat(o.clusters()).isEmpty();
    }

    @Test
    void clusterMembersAndNoiseListsStillComeFromTheDatabase() {
        dbContains("a1", "n1");

        assertThat(service.loadClusterMembers(1)).extracting(Bookmark::getTweetId).containsExactly("a1");
        assertThat(service.loadClusterMembers(3)).isEmpty();
        assertThat(service.loadNoise().stream().map(Bookmark::getTweetId).collect(Collectors.toSet())).containsExactly("n1");
    }
}
