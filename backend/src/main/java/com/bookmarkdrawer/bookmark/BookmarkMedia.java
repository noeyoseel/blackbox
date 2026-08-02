package com.bookmarkdrawer.bookmark;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor
public class BookmarkMedia {

    @Column(name = "url", length = 512)
    private String url;

    @Column(name = "type", length = 16)
    private String type;

    public BookmarkMedia(String url, String type) {
        this.url = url;
        this.type = type;
    }
}
