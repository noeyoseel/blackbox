package com.bookmarkdrawer.bookmark;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record TweetPayload(
        @NotBlank String id,
        @NotBlank String text,
        String quotedText,
        String quotedTweetId,
        String quotedAuthorName,
        String quotedAuthorHandle,
        String quotedAuthorAvatar,
        String createdAt,
        String authorId,
        String authorName,
        String authorHandle,
        String authorAvatar,
        String bookmarkOrder,
        List<MediaItemPayload> media
) {
}
