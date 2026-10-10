package com.bookmarkdrawer.duplicates;

import java.util.List;

public record DuplicateResolveResult(String action, List<String> archivedTweetIds) {
}
