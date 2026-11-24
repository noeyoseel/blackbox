package com.bookmarkdrawer.tags;

import com.bookmarkdrawer.bookmark.Bookmark;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class TagController {

    private final TagBrowserService tagBrowserService;

    @GetMapping("/tags")
    public String list(@RequestParam(name = "min", required = false) Long min, Model model) {
        long minCount = min != null ? Math.max(1, min) : TagBrowserService.DEFAULT_MIN_COUNT;
        List<TagCount> tags = tagBrowserService.listTags(minCount);
        model.addAttribute("tags", tags);
        model.addAttribute("minCount", minCount);
        model.addAttribute("showingAll", minCount <= TagBrowserService.SHOW_ALL_MIN_COUNT);
        model.addAttribute("totalDistinctTags", tagBrowserService.countDistinctTags());
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
