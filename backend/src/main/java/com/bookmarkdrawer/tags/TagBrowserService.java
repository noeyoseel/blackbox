package com.bookmarkdrawer.tags;

import com.bookmarkdrawer.bookmark.Bookmark;
import com.bookmarkdrawer.bookmark.BookmarkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TagBrowserService {

    private final BookmarkRepository bookmarkRepository;

    public List<TagCount> listTags() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Bookmark bookmark : bookmarkRepository.findByArchivedFalseAndTaggedTrue()) {
            for (String tag : bookmark.getTags()) {
                counts.merge(tag, 1L, Long::sum);
            }
        }
        return counts.entrySet().stream()
                .map(e -> new TagCount(e.getKey(), e.getValue()))
                .sorted((a, b) -> Long.compare(b.count(), a.count()))
                .toList();
    }

    public List<Bookmark> bookmarksForTag(String tag) {
        return bookmarkRepository.findByTag(tag);
    }
}
