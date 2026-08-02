package com.bookmarkdrawer.tags;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class TagMergeController {

    private final TagMergeService tagMergeService;

    @GetMapping("/tags/merge")
    public String page() {
        return "tags-merge";
    }

    @PostMapping("/api/tags/merge-suggestions")
    @ResponseBody
    public List<TagMergeGroup> suggestions() {
        return tagMergeService.suggestMerges();
    }

    @PostMapping("/api/tags/merge")
    @ResponseBody
    public TagMergeResult merge(@RequestBody TagMergeRequest request) {
        return tagMergeService.applyMerge(request.canonicalTag(), request.tags());
    }
}
