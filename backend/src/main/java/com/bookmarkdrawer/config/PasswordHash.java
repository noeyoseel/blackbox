package com.bookmarkdrawer.config;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

// 단일 사용자 Basic 인증용 비밀번호 해시 (JDK 내장 PBKDF2-HMAC-SHA256, 별도 의존성 없음).
// 저장 형식: pbkdf2-sha256$<반복횟수>$<salt base64>$<hash base64>
// 평문 비밀번호는 어디에도 저장하지 않는다. 해시 생성은 main()으로 하며 비밀번호는 인자가 아니라 stdin으로 받는다.
public final class PasswordHash {

    static final String PREFIX = "pbkdf2-sha256";
    // 하한: 설정 실수로 약한 해시가 운영에 들어가는 것을 막는다. 상한: 해시 문자열 하나로 서버 CPU를 묶지 못하게 한다.
    static final int MIN_ITERATIONS = 100_000;
    static final int MAX_ITERATIONS = 2_000_000;
    // 기본값을 하한과 같게 둔 이유: Basic 인증은 모든 요청(페이지당 10여 개)마다 검증하므로 요청당 비용이 곧 체감 지연이다
    // (개발 PC 실측: 10만회 약 26ms, 31만회 약 84ms, 60만회 약 249ms). 비밀번호를 24자 이상 무작위 문자열로 쓰는 한
    // 반복횟수는 보안의 핵심이 아니다. 사람이 외울 수 있는 비밀번호를 쓸 거라면 반복횟수를 올려 해시를 만들고 캐시 도입을 함께 검토할 것.
    static final int DEFAULT_ITERATIONS = MIN_ITERATIONS;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;

    private final int iterations;
    private final byte[] salt;
    private final byte[] expected;

    private PasswordHash(int iterations, byte[] salt, byte[] expected) {
        this.iterations = iterations;
        this.salt = salt;
        this.expected = expected;
    }

    // 형식이 틀리거나 반복횟수가 범위를 벗어나면 IllegalArgumentException. 메시지에 입력값은 담지 않는다.
    public static PasswordHash parse(String encoded) {
        if (encoded == null) {
            throw new IllegalArgumentException("해시가 비어 있습니다.");
        }
        String[] parts = encoded.trim().split("\\$", -1);
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            throw new IllegalArgumentException("지원하지 않는 해시 형식입니다.");
        }
        int iterations;
        byte[] salt;
        byte[] hash;
        try {
            iterations = Integer.parseInt(parts[1]);
            salt = Base64.getDecoder().decode(parts[2]);
            hash = Base64.getDecoder().decode(parts[3]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("해시 형식이 올바르지 않습니다.");
        }
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            throw new IllegalArgumentException("해시 반복횟수가 허용 범위를 벗어났습니다.");
        }
        if (salt.length < 8 || hash.length != HASH_BITS / 8) {
            throw new IllegalArgumentException("해시 길이가 올바르지 않습니다.");
        }
        return new PasswordHash(iterations, salt, hash);
    }

    public static String create(char[] password, int iterations) {
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            throw new IllegalArgumentException("반복횟수가 허용 범위를 벗어났습니다.");
        }
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] hash = derive(password, salt, iterations);
        return PREFIX + "$" + iterations + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hash);
    }

    // 상수 시간 비교. 호출자가 password 배열을 지우는 것은 호출자 책임.
    public boolean verify(char[] password) {
        byte[] actual = derive(password, salt, iterations);
        try {
            return MessageDigest.isEqual(actual, expected);
        } finally {
            Arrays.fill(actual, (byte) 0);
        }
    }

    // 이 서버에서 검증 1회가 걸리는 시간(ms). 시작 시 로그용 — 실제 비밀번호가 아니라 임의 값으로 잰다.
    public long measureVerifyMillis() {
        char[] probe = "timing-probe".toCharArray();
        long start = System.nanoTime();
        verify(probe);
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2를 사용할 수 없습니다.", e);
        } finally {
            spec.clearPassword();
        }
    }

    // 사용법: echo/파이프 대신 터미널에서 직접 입력하거나, 비밀번호가 셸 기록에 남지 않는 방식으로 stdin에 전달한다.
    //   java -cp app.jar com.bookmarkdrawer.config.PasswordHash [반복횟수]
    public static void main(String[] args) throws Exception {
        int iterations = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_ITERATIONS;
        String line = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        if (line == null || line.isEmpty()) {
            System.err.println("stdin으로 비밀번호를 한 줄 입력하세요.");
            System.exit(1);
        }
        char[] password = line.toCharArray();
        try {
            System.out.println(create(password, iterations));
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
