package com.bookmarkdrawer.bookmark;

import java.util.List;

public record BatchActionResult(int requested, List<String> updatedTweetIds, List<String> notFound) {
}
