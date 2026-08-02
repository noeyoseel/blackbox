package com.bookmarkdrawer.bookmark;

import java.util.List;

public record SyncCheckRequest(List<String> tweetIds, String minOrder, String maxOrder) {
}
