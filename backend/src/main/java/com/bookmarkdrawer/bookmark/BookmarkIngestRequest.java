package com.bookmarkdrawer.bookmark;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record BookmarkIngestRequest(
        @NotEmpty @Valid List<TweetPayload> tweets
) {
}
