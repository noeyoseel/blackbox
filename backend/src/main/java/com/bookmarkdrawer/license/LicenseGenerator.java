package com.bookmarkdrawer.license;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

// 판매자(나)만 로컬에서 실행하는 발급 도구. 결제(Stripe/Lemon Squeezy 웹훅 등)가 들어오면
// 이 클래스를 돌려서 라이선스 키를 만들어 구매자에게 보낸다. Spring 빈이 아니라서 앱 실행에는
// 전혀 관여하지 않는다. 개인키는 절대 커밋/배포하지 않고 환경변수로만 넘긴다.
//
// 사용법: BLACKBOX_LICENSE_PRIVATE_KEY=<base64 private key> java -cp ... LicenseGenerator <email> <plan>
public class LicenseGenerator {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("사용법: LicenseGenerator <email> <plan>");
            System.exit(1);
        }
        String email = args[0];
        String plan = args[1];

        String privateKeyBase64 = System.getenv("BLACKBOX_LICENSE_PRIVATE_KEY");
        if (privateKeyBase64 == null || privateKeyBase64.isBlank()) {
            System.err.println("환경변수 BLACKBOX_LICENSE_PRIVATE_KEY 가 설정되지 않았습니다.");
            System.exit(1);
        }

        KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
        PrivateKey privateKey = keyFactory.generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64)));

        LicensePayload payload = new LicensePayload(
                UUID.randomUUID().toString(), email, plan, Instant.now().toString());

        ObjectMapper objectMapper = new ObjectMapper();
        byte[] payloadBytes = objectMapper.writeValueAsBytes(payload);

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(payloadBytes);
        byte[] signatureBytes = signer.sign();

        String licenseKey = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes)
                + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signatureBytes);

        System.out.println(licenseKey);
    }
}
