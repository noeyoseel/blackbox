package com.bookmarkdrawer.tags;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// 홈 필터 패널의 태그 자동완성용. 최소 사용 횟수 기준은 /tags 페이지 기본값과 맞춘다
// (한 번만 쓰인 태그까지 전부 노출하면 자동완성이 무의미해진다).
@RestController
@RequestMapping("/api/tags")
@RequiredArgsConstructor
public class TagApiController {

    private final TagBrowserService tagBrowserService;

    @GetMapping
    public List<String> list() {
        return tagBrowserService.listTags(TagBrowserService.DEFAULT_MIN_COUNT).stream()
                .map(TagCount::tag)
                .toList();
    }
}
