package com.bookmarkdrawer.bookmark;

import java.util.List;

public record OrganizePage(List<Bookmark> bookmarks, boolean hasMore, long totalCandidates) {
}
