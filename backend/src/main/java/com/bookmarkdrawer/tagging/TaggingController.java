package com.bookmarkdrawer.tagging;

import com.bookmarkdrawer.license.LicenseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/tagging")
@RequiredArgsConstructor
public class TaggingController {

    private final TaggingService taggingService;
    private final LicenseService licenseService;

    @PostMapping("/run")
    public TaggingResult run() {
        if (!licenseService.isLicensed()) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "AI 자동 태깅은 라이선스 활성화가 필요합니다.");
        }
        return taggingService.tagUntagged();
    }
}
