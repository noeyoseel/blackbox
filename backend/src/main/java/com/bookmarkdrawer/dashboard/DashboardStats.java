package com.bookmarkdrawer.dashboard;

import java.util.List;

public record DashboardStats(
        int totalBookmarks,
        int taggedBookmarks,
        List<String> monthLabels,
        List<Long> monthlyCounts,
        List<String> topTagLabels,
        List<Long> topTagCounts
) {
}
