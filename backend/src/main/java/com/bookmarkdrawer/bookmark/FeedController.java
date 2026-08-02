package com.bookmarkdrawer.bookmark;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequiredArgsConstructor
public class FeedController {

    private static final int PAGE_SIZE = 20;

    private final BookmarkService bookmarkService;

    @GetMapping("/")
    public String feed(@RequestParam(defaultValue = "newest") String sort, Model model) {
        FeedPage feedPage = bookmarkService.feed(0, PAGE_SIZE, sort);
        model.addAttribute("bookmarks", feedPage.bookmarks());
        model.addAttribute("hasMore", feedPage.hasMore());
        model.addAttribute("pageSize", PAGE_SIZE);
        model.addAttribute("sort", sort);
        return "feed";
    }
}
