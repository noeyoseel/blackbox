package com.bookmarkdrawer.bookmark;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record BatchArchiveRequest(
        @NotEmpty List<String> tweetIds,
        boolean archived
) {
}
