package com.bookmarkdrawer.bookmark;

import java.util.List;

public record FeedPage(List<Bookmark> bookmarks, boolean hasMore) {
}
