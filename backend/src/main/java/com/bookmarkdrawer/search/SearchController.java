package com.bookmarkdrawer.search;

import com.bookmarkdrawer.bookmark.Bookmark;
import com.bookmarkdrawer.bookmark.BookmarkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class SearchController {

    private static final int PAGE_SIZE = 20;

    private final BookmarkRepository bookmarkRepository;

    @GetMapping("/search")
    public String search(@RequestParam(required = false) String q,
                          @RequestParam(defaultValue = "0") int page,
                          Model model) {
        String keyword = q == null ? "" : q.trim();
        model.addAttribute("q", q == null ? "" : q);

        if (keyword.isEmpty()) {
            model.addAttribute("results", List.of());
            model.addAttribute("totalCount", 0L);
            model.addAttribute("page", 0);
            model.addAttribute("totalPages", 0);
            return "search";
        }

        // 결과를 통째로 렌더링하던 구조라 "AI" 같은 흔한 키워드에서 수백 건이 한 번에 그려졌다.
        // 카드마다 tags/media 지연로딩이 붙으므로 페이지 단위로 끊어야 쿼리 수도 함께 줄어든다.
        Page<Bookmark> result = bookmarkRepository.searchByTextOrTag(keyword, PageRequest.of(page, PAGE_SIZE));
        model.addAttribute("results", result.getContent());
        model.addAttribute("totalCount", result.getTotalElements());
        model.addAttribute("page", result.getNumber());
        model.addAttribute("totalPages", result.getTotalPages());
        return "search";
    }
}
