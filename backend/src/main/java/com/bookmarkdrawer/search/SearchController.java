package com.bookmarkdrawer.search;

import com.bookmarkdrawer.bookmark.Bookmark;
import com.bookmarkdrawer.bookmark.BookmarkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class SearchController {

    private final BookmarkRepository bookmarkRepository;

    @GetMapping("/search")
    public String search(@RequestParam(required = false) String q, Model model) {
        List<Bookmark> results = (q == null || q.isBlank())
                ? List.of()
                : bookmarkRepository.findByArchivedFalseAndTextContainingIgnoreCaseOrderByTweetCreatedAtDesc(q.trim());
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("results", results);
        return "search";
    }
}
