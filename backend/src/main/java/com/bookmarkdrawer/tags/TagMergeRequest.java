package com.bookmarkdrawer.tags;

import java.util.List;

public record TagMergeRequest(String canonicalTag, List<String> tags) {
}
