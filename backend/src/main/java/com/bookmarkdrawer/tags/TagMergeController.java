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
    private final TagNormalizationService tagNormalizationService;

    @GetMapping("/tags/merge")
    public String page() {
        return "tags-merge";
    }

    // 결정적 표기 변형 후보. AI를 호출하지 않으므로 GET이고, 비용 없이 몇 번이든 다시 볼 수 있다.
    @GetMapping("/api/tags/normalization-candidates")
    @ResponseBody
    public List<TagMergeGroup> normalizationCandidates() {
        return tagNormalizationService.findVariantGroups();
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
