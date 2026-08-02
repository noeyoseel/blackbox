package com.bookmarkdrawer.bookmark;

import java.util.Set;

public record TagsView(String tweetId, Set<String> tags) {
}
