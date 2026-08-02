package com.bookmarkdrawer.dashboard;

import com.bookmarkdrawer.bookmark.Bookmark;
import com.bookmarkdrawer.bookmark.BookmarkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardService {

    // 트위터 legacy created_at 형식: "Wed Oct 10 20:19:24 +0000 2018"
    private static final DateTimeFormatter TWEET_DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH);
    private static final int TOP_TAG_LIMIT = 20;

    private final BookmarkRepository bookmarkRepository;

    public DashboardStats computeStats() {
        List<Bookmark> tagged = bookmarkRepository.findByTaggedTrue();
        int totalBookmarks = (int) bookmarkRepository.count();

        Map<YearMonth, Long> monthlyCounts = new TreeMap<>();
        Map<String, Long> tagCounts = new LinkedHashMap<>();

        for (Bookmark bookmark : tagged) {
            parseMonth(bookmark.getTweetCreatedAt())
                    .ifPresent(month -> monthlyCounts.merge(month, 1L, Long::sum));
            for (String tag : bookmark.getTags()) {
                tagCounts.merge(tag, 1L, Long::sum);
            }
        }

        List<String> monthLabels = monthlyCounts.keySet().stream()
                .map(YearMonth::toString)
                .toList();
        List<Long> monthCountValues = List.copyOf(monthlyCounts.values());

        List<Map.Entry<String, Long>> topTags = tagCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(TOP_TAG_LIMIT)
                .toList();
        List<String> topTagLabels = topTags.stream().map(Map.Entry::getKey).toList();
        List<Long> topTagCounts = topTags.stream().map(Map.Entry::getValue).toList();

        return new DashboardStats(
                totalBookmarks,
                tagged.size(),
                monthLabels,
                monthCountValues,
                topTagLabels,
                topTagCounts
        );
    }

    private java.util.Optional<YearMonth> parseMonth(String tweetCreatedAt) {
        if (tweetCreatedAt == null || tweetCreatedAt.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            OffsetDateTime dateTime = OffsetDateTime.parse(tweetCreatedAt, TWEET_DATE_FORMAT);
            return java.util.Optional.of(YearMonth.from(dateTime));
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }
}
