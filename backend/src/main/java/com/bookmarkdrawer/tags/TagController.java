package com.bookmarkdrawer.tags;

import com.bookmarkdrawer.bookmark.Bookmark;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class TagController {

    private final TagBrowserService tagBrowserService;

    @GetMapping("/tags")
    public String list(Model model) {
        List<TagCount> tags = tagBrowserService.listTags();
        model.addAttribute("tags", tags);
        return "tags";
    }

    @GetMapping("/tags/{tag}")
    public String detail(@PathVariable String tag, Model model) {
        List<Bookmark> bookmarks = tagBrowserService.bookmarksForTag(tag);
        model.addAttribute("tag", tag);
        model.addAttribute("bookmarks", bookmarks);
        return "tag-detail";
    }
}
