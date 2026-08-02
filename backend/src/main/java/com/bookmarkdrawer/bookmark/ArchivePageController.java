package com.bookmarkdrawer.bookmark;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class ArchivePageController {

    private final BookmarkService bookmarkService;

    @GetMapping("/archive")
    public String archive(Model model) {
        model.addAttribute("bookmarks", bookmarkService.listArchived());
        return "archive";
    }
}
