package com.bookmarkdrawer.license;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

// 라이선스 키를 오프라인으로 검증한다(서버 호출 없음). 형식: base64url(payload json) + "." + base64url(서명).
// 개인키는 절대 앱에 포함하지 않는다 — 결제(Stripe/Lemon Squeezy 등) 발생 시 판매자만
// LicenseGenerator를 로컬에서 실행해 라이선스 키를 발급한다. 앱은 공개키로 서명만 검증한다.
@Component
public class LicenseVerifier {

    // 데모용 Ed25519 공개키. 실제 배포 시 본인 키페어로 교체할 것.
    private static final String PUBLIC_KEY_BASE64 =
            "MCowBQYDK2VwAyEAT30kp/S6lhW7Dp4CqgE6LJWb5Zvt+EB86mnyVEwXPEk=";

    private final PublicKey publicKey;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LicenseVerifier() {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
            byte[] keyBytes = Base64.getDecoder().decode(PUBLIC_KEY_BASE64);
            this.publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            throw new IllegalStateException("라이선스 공개키 로딩 실패", e);
        }
    }

    public LicensePayload verify(String licenseKey) {
        if (licenseKey == null || licenseKey.isBlank()) {
            throw new InvalidLicenseException("라이선스 키가 비어있습니다.");
        }
        String[] parts = licenseKey.trim().split("\\.");
        if (parts.length != 2) {
            throw new InvalidLicenseException("라이선스 키 형식이 올바르지 않습니다.");
        }

        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[0]);
            byte[] signatureBytes = Base64.getUrlDecoder().decode(parts[1]);

            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(payloadBytes);
            if (!verifier.verify(signatureBytes)) {
                throw new InvalidLicenseException("라이선스 서명이 유효하지 않습니다.");
            }

            return objectMapper.readValue(new String(payloadBytes, StandardCharsets.UTF_8), LicensePayload.class);
        } catch (InvalidLicenseException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidLicenseException("라이선스 키를 파싱할 수 없습니다.");
        }
    }
}
