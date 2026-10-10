package com.bookmarkdrawer.tagging;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tagging")
@RequiredArgsConstructor
public class MetadataExtractionController {

    private final MetadataExtractionService metadataExtractionService;

    @PostMapping("/metadata-extract")
    public MetadataExtractionResult run() {
        return metadataExtractionService.extractUnclassified();
    }

    // 진행 중인 /metadata-extract 호출의 진행 상황을 프론트가 폴링해서 보여주기 위한 엔드포인트.
    @GetMapping("/metadata-extract/progress")
    public MetadataExtractionProgress progress() {
        return metadataExtractionService.getProgress();
    }
}
