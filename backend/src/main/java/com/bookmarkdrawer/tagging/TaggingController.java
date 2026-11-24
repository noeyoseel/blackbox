package com.bookmarkdrawer.tagging;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tagging")
@RequiredArgsConstructor
public class TaggingController {

    private final TaggingService taggingService;

    @PostMapping("/run")
    public TaggingResult run() {
        return taggingService.tagUntagged();
    }
}
