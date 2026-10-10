package com.bookmarkdrawer.interestmap;

import java.util.List;

public record InterestMapOverview(int totalBookmarks, int clusterCount, int noiseCount,
                                   List<ClusterSummary> clusters) {
}
