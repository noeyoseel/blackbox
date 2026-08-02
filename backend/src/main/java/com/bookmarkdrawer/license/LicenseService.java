package com.bookmarkdrawer.license;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

// 라이선스 활성화 상태를 로컬 파일(~/.blackbox/license.key)에 저장/로드한다.
// 서버 없이 완전히 오프라인으로 동작 — 앱 시작 시 딱 한 번 서명을 검증하고 메모리에 캐시한다.
@Service
@RequiredArgsConstructor
public class LicenseService {

    private static final Path LICENSE_FILE =
            Path.of(System.getProperty("user.home"), ".blackbox", "license.key");

    private final LicenseVerifier licenseVerifier;

    private LicensePayload activeLicense;

    @PostConstruct
    public void loadFromDisk() {
        if (!Files.exists(LICENSE_FILE)) return;
        try {
            String key = Files.readString(LICENSE_FILE, StandardCharsets.UTF_8);
            activeLicense = licenseVerifier.verify(key);
        } catch (Exception e) {
            activeLicense = null;
        }
    }

    public LicensePayload activate(String licenseKey) {
        LicensePayload payload = licenseVerifier.verify(licenseKey);
        try {
            Files.createDirectories(LICENSE_FILE.getParent());
            Files.writeString(LICENSE_FILE, licenseKey.trim(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new InvalidLicenseException("라이선스 키를 저장하지 못했습니다.");
        }
        activeLicense = payload;
        return payload;
    }

    public boolean isLicensed() {
        return activeLicense != null;
    }

    public LicensePayload currentLicense() {
        return activeLicense;
    }
}
