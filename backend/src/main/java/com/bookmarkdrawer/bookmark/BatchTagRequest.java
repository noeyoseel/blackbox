package com.bookmarkdrawer.bookmark;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record BatchTagRequest(
        @NotEmpty List<String> tweetIds,
        @NotEmpty List<String> tagsToAdd
) {
}
