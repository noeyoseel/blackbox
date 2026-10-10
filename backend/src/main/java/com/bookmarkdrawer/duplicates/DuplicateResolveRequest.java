package com.bookmarkdrawer.duplicates;

import java.util.List;

public record DuplicateResolveRequest(String action, List<String> tweetIds) {
}
