package com.bookmarkdrawer.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// 단일 사용자 HTTP Basic 인증 + 상태 변경 요청 출처 검증.
// 등록은 AuthFilterConfig가 한다(REQUEST + ERROR 디스패치, "/*"). 이 클래스는 빈이 아니다.
//
// 설정(환경변수; Spring relaxed binding으로 BLACKBOX_AUTH_* 가 그대로 매핑된다):
//   blackbox.auth.enabled          기본 true. false는 로컬 전용(확장이 localhost를 자격 증명 없이 호출). 배포 환경에서는 무시한다.
//   blackbox.auth.user             사용자명
//   blackbox.auth.password-hash    PasswordHash 형식의 해시 (평문 금지)
//   blackbox.auth.allowed-origins  쉼표로 구분한 허용 origin (예: https://서비스도메인, 기본 포트는 생략). 비어 있으면 Origin이 붙는 쓰기 요청은 모두 거부
//   blackbox.auth.client-ip-header 선택. 프록시가 덮어쓴다고 확인된 클라이언트 IP 헤더 이름. 설정하지 않으면 클라이언트를 구분하지 않는다
// 활성인데 사용자명/해시가 없거나 잘못되면 fail-closed: 모든 요청을 503으로 거부한다.
//
// 요청마다 PBKDF2를 계산하지 않도록 "검증에 성공한 Authorization 헤더"의 HMAC 다이제스트만 작은 메모리 캐시에 둔다.
// 원본 헤더와 비밀번호는 저장하지 않고, 실패한 헤더는 캐시하지 않으며, 프로세스 재시작(= 비밀번호 변경 반영)으로 비워진다.
// 캐시가 있어도 출처 검증은 항상 먼저 실행된다.
//
// 실패 요청이 정상 사용자에게 미치는 영향:
//  - 이미 검증된 헤더(캐시 적중)는 검증 슬롯을 쓰지 않으므로 실패 폭주와 경쟁하지 않는다.
//  - 캐시에 없는 헤더의 검증은 CPU 코어 수에 비례한 슬롯으로 제한하고, 대기열(슬롯당 16)이 차면 기다리지 않고 즉시 503을 돌려준다
//    (CPU와 스레드 사용량의 상한). 대기 한도는 이 서버에서 측정한 검증 시간에서 정한다.
//  - 클라이언트 IP 헤더를 설정한 경우에만 클라이언트별 실패 횟수를 세어, 실패가 쌓인 클라이언트를 별도의 슬롯 1개로 보낸다.
//    프록시 뒤에서 모든 요청이 같은 주소로 보이는 기본 구성에서는 이 구분을 하지 않는다(정상 사용자를 함께 감속시키므로).
//
// Basic 인증의 한계: 서버가 로그아웃시킬 방법이 없다. 브라우저가 창/세션 동안 자격 증명을 캐시하며,
// 무효화는 해시 변수를 바꾸고 재시작하는 것뿐이다(재시작 후 이전 비밀번호는 즉시 거부된다). MFA도 없다.
@Slf4j
public class AuthFilter implements Filter {

    static final String PASSED_ATTRIBUTE = AuthFilter.class.getName() + ".PASSED";
    private static final int MAX_AUTH_HEADER_CHARS = 1024;
    private static final long MIN_LANE_WAIT_MILLIS = 250;
    private static final long MAX_LANE_WAIT_MILLIS = 3000;
    private static final int FAILURES_BEFORE_PENALTY = 8;
    private static final long FAILURE_WINDOW_MILLIS = 5 * 60_000L;
    private static final int MAX_TRACKED_CLIENTS = 2048;
    // HTTP/2에서는 한 페이지의 첫 리소스 십여 개가 동시에 도착할 수 있다. 이들은 같은 헤더라 앞선 검증 하나로 모두 풀린다.
    private static final int WAITERS_PER_PERMIT = 16;
    private static final String CHALLENGE = "Basic realm=\"blackbox\", charset=\"UTF-8\"";

    private enum Mode { DISABLED, ACTIVE, MISCONFIGURED }

    private record Credentials(String user, char[] password) {
    }

    private final Mode mode;
    private final byte[] userDigest;
    private final PasswordHash passwordHash;
    private final Set<String> allowedOrigins;
    private final String clientIpHeader;
    private final Lane verifyLane;
    private final Lane penaltyLane;
    private final VerifiedCache verified;
    private final FailureTracker failures;
    private final AtomicLong failureCount = new AtomicLong();
    private final AtomicLong verificationCount = new AtomicLong();
    private final AtomicInteger activeVerifications = new AtomicInteger();
    private final AtomicInteger maxActiveVerifications = new AtomicInteger();
    private final AtomicLong shedCount = new AtomicLong();

    public AuthFilter(Environment env, boolean enabled, String user, String passwordHash, String allowedOrigins,
                      String clientIpHeader) {
        this(enabled, isRailway(env), user, passwordHash, allowedOrigins, clientIpHeader,
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2), -1, System::currentTimeMillis);
    }

    // 테스트용: 슬롯 수를 지정하고, laneWaitMillis가 음수면 기동 시 측정한 검증 시간에서 정한다.
    AuthFilter(boolean enabled, boolean onRailway, String user, String passwordHashValue, String allowedOriginsValue,
               String clientIpHeader, int verifyPermits, long laneWaitMillis, LongSupplier clock) {
        this.clientIpHeader = clientIpHeader == null ? "" : clientIpHeader.trim();
        this.failures = new FailureTracker(clock);
        this.verified = new VerifiedCache(clock);
        this.allowedOrigins = parseOrigins(allowedOriginsValue);

        PasswordHash parsed = null;
        byte[] digest = null;
        long wait = laneWaitMillis >= 0 ? laneWaitMillis : MIN_LANE_WAIT_MILLIS;
        Mode resolved;
        if (!enabled && !onRailway) {
            resolved = Mode.DISABLED;
            log.warn("blackbox 인증이 비활성화되어 있습니다. 로컬 개발 전용 설정입니다.");
        } else {
            if (!enabled) {
                log.error("배포 환경에서는 blackbox.auth.enabled=false 를 무시하고 인증을 유지합니다.");
            }
            try {
                if (user == null || user.isBlank()) {
                    throw new IllegalArgumentException("blackbox.auth.user 가 비어 있습니다.");
                }
                parsed = PasswordHash.parse(passwordHashValue);
                digest = sha256(user.getBytes(StandardCharsets.UTF_8));
                resolved = Mode.ACTIVE;
                parsed.measureVerifyMillis(); // 첫 호출의 JIT 비용을 측정값에서 빼기 위한 예열
                long measured = Math.max(1, parsed.measureVerifyMillis());
                if (laneWaitMillis < 0) {
                    wait = Math.min(MAX_LANE_WAIT_MILLIS, Math.max(MIN_LANE_WAIT_MILLIS, measured * 4));
                }
                log.info("blackbox 인증 활성. PBKDF2 검증 1회 약 {}ms(이 서버 기준), 검증 슬롯 {}개, 슬롯 대기 한도 {}ms. "
                        + "검증에 성공한 헤더는 캐시되어 이후 요청은 검증하지 않는다.", measured, verifyPermits, wait);
            } catch (IllegalArgumentException e) {
                resolved = Mode.MISCONFIGURED;
                log.error("blackbox 인증 설정이 올바르지 않아 모든 요청을 거부합니다(fail-closed): {}", e.getMessage());
            }
        }
        this.verifyLane = new Lane(verifyPermits, wait);
        this.penaltyLane = new Lane(1, wait);
        this.mode = resolved;
        this.passwordHash = parsed;
        this.userDigest = digest;
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        // 이미 인증을 통과한 요청의 내부 재디스패치(오류 페이지 등)는 다시 검사하지 않는다.
        // 이 속성은 서버 코드만 설정할 수 있고, 인증을 통과한 뒤에만 설정된다.
        if (mode == Mode.DISABLED || request.getAttribute(PASSED_ATTRIBUTE) != null) {
            chain.doFilter(request, response);
            return;
        }
        if (mode == Mode.MISCONFIGURED) {
            reply(response, 503, "Authentication is not configured.", false, null);
            return;
        }

        // 출처 검증을 먼저 한다(캐시 적중 여부와 무관): 교차 출처 요청은 PBKDF2 계산을 일으키기 전에 거부된다.
        if (!OriginPolicy.isSafeMethod(request.getMethod())
                && !OriginPolicy.allows(request.getMethod(), singleOrigin(request),
                request.getHeader("Sec-Fetch-Site"), allowedOrigins)) {
            reply(response, 403, "Forbidden", false, null);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null) {
            // 브라우저의 첫 요청은 자격 증명이 없는 것이 정상이므로 실패로 세지 않는다.
            reply(response, 401, "Unauthorized", true, null);
            return;
        }
        String clientKey = clientKey(request);
        if (header.length() > MAX_AUTH_HEADER_CHARS
                || Collections.list(request.getHeaders("Authorization")).size() > 1) {
            recordFailure(clientKey);
            reply(response, 401, "Unauthorized", true, null);
            return;
        }

        String headerDigest = verified.digest(header);
        if (verified.isValid(headerDigest)) {
            pass(request, response, chain, clientKey);
            return;
        }

        Credentials credentials = parseBasic(header);
        if (credentials == null) {
            recordFailure(clientKey);
            reply(response, 401, "Unauthorized", true, null);
            return;
        }
        try {
            // 사용자명이 틀리면 PBKDF2를 계산하지 않는다(무의미한 CPU 소모 방지).
            // 대가: 사용자명 일치 여부가 응답 시간 차이로 드러날 수 있다. 사용자명은 비밀이 아니라 긴 임의 비밀번호에 의존한다.
            if (!MessageDigest.isEqual(userDigest, sha256(credentials.user().getBytes(StandardCharsets.UTF_8)))) {
                recordFailure(clientKey);
                reply(response, 401, "Unauthorized", true, null);
                return;
            }
            Lane lane = failures.isLimited(clientKey) ? penaltyLane : verifyLane;
            if (!lane.acquire()) {
                shedCount.incrementAndGet();
                reply(response, 503, "Busy", false, 1);
                return;
            }
            boolean ok;
            try {
                // 같은 헤더의 동시 첫 요청(페이지의 병렬 리소스)은 앞선 요청이 끝난 뒤 캐시를 다시 보고 계산을 건너뛴다.
                if (verified.isValid(headerDigest)) {
                    ok = true;
                } else {
                    ok = verifyPassword(credentials.password());
                    if (ok) {
                        verified.put(headerDigest);
                    }
                }
            } finally {
                lane.release();
            }
            if (!ok) {
                recordFailure(clientKey);
                reply(response, 401, "Unauthorized", true, null);
                return;
            }
        } finally {
            Arrays.fill(credentials.password(), '\0');
        }
        pass(request, response, chain, clientKey);
    }

    private void pass(HttpServletRequest request, HttpServletResponse response, FilterChain chain, String clientKey)
            throws IOException, ServletException {
        failures.clear(clientKey);
        request.setAttribute(PASSED_ATTRIBUTE, Boolean.TRUE);
        chain.doFilter(request, response);
    }

    private boolean verifyPassword(char[] password) {
        int active = activeVerifications.incrementAndGet();
        maxActiveVerifications.accumulateAndGet(active, Math::max);
        try {
            verificationCount.incrementAndGet();
            return passwordHash.verify(password);
        } finally {
            activeVerifications.decrementAndGet();
        }
    }

    private void recordFailure(String clientKey) {
        failures.record(clientKey);
        long n = failureCount.incrementAndGet();
        if (n == 1 || n % 50 == 0) {
            log.warn("인증 실패 누적 {}건", n);
        }
    }

    // Origin 헤더가 둘 이상이면 비정상 요청으로 보고 허용 목록에 있을 수 없는 값으로 바꾼다.
    private static String singleOrigin(HttpServletRequest request) {
        var values = Collections.list(request.getHeaders("Origin"));
        if (values.isEmpty()) {
            return null;
        }
        return values.size() == 1 ? values.get(0) : "multiple";
    }

    // 클라이언트 IP 헤더를 명시적으로 설정하고 값이 IP 형식일 때만 키를 만든다. 아니면 null(구분하지 않음).
    private String clientKey(HttpServletRequest request) {
        if (clientIpHeader.isEmpty()) {
            return null;
        }
        String value = request.getHeader(clientIpHeader);
        if (value == null) {
            return null;
        }
        String first = value.split(",", 2)[0].trim();
        return !first.isEmpty() && first.length() <= 64 && first.matches("[0-9a-fA-F:.]+") ? first : null;
    }

    // null이면 형식 오류(Basic 아님, base64/UTF-8 오류, ':' 없음).
    private static Credentials parseBasic(String header) {
        if (header.length() < 7 || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(header.substring(6).trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw));
            char[] all = new char[decoded.remaining()];
            decoded.get(all);
            int colon = -1;
            for (int i = 0; i < all.length; i++) {
                if (all[i] == ':') {
                    colon = i;
                    break;
                }
            }
            if (colon < 0) {
                Arrays.fill(all, '\0');
                return null;
            }
            String user = new String(all, 0, colon);
            char[] password = Arrays.copyOfRange(all, colon + 1, all.length);
            Arrays.fill(all, '\0');
            return new Credentials(user, password);
        } catch (CharacterCodingException e) {
            return null;
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    private static Set<String> parseOrigins(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Stream.of(value.split(","))
                .map(String::trim)
                .map(s -> s.endsWith("/") ? s.substring(0, s.length() - 1) : s)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean isRailway(Environment env) {
        return env.getProperty("RAILWAY_ENVIRONMENT_ID") != null
                || env.getProperty("RAILWAY_PROJECT_ID") != null
                || env.getProperty("RAILWAY_ENVIRONMENT_NAME") != null;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void reply(HttpServletResponse response, int status, String body, boolean challenge,
                              Integer retryAfterSeconds) throws IOException {
        // sendError 대신 setStatus: 오류 디스패치(/error)로 넘어가 내용이 노출되는 경로를 만들지 않는다.
        response.setStatus(status);
        if (challenge) {
            response.setHeader("WWW-Authenticate", CHALLENGE);
        }
        if (retryAfterSeconds != null) {
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        }
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(body);
        response.flushBuffer();
    }

    // ---- 시험용 관찰 지점 ------------------------------------------------------------------------

    boolean isLimited(String key) {
        return failures.isLimited(key);
    }

    long verificationCount() {
        return verificationCount.get();
    }

    int maxActiveVerifications() {
        return maxActiveVerifications.get();
    }

    long shedCount() {
        return shedCount.get();
    }

    int cacheSize() {
        return verified.size();
    }

    Set<String> cacheKeys() {
        return verified.keys();
    }

    Semaphore verifyPermitsForTest() {
        return verifyLane.permits;
    }

    // ---- 내부 구조 -------------------------------------------------------------------------------

    // 검증 슬롯. 슬롯이 없으면 대기하되, 대기자가 한도를 넘으면 기다리지 않고 거절한다(CPU·스레드 상한).
    private static final class Lane {
        private final Semaphore permits;
        private final AtomicInteger waiting = new AtomicInteger();
        private final int maxWaiting;
        private final long waitMillis;

        Lane(int permits, long waitMillis) {
            this.permits = new Semaphore(permits, true);
            this.maxWaiting = Math.max(1, permits * WAITERS_PER_PERMIT);
            this.waitMillis = waitMillis;
        }

        boolean acquire() {
            if (waiting.incrementAndGet() > maxWaiting) {
                waiting.decrementAndGet();
                return false;
            }
            try {
                return permits.tryAcquire(waitMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } finally {
                waiting.decrementAndGet();
            }
        }

        void release() {
            permits.release();
        }
    }

    // 검증에 성공한 Authorization 헤더의 HMAC-SHA256 다이제스트(프로세스마다 무작위 키)만 보관한다.
    // 작은 크기의 LRU, 마지막 사용 후 유휴 TTL, 생성 후 절대 TTL. 값이 아니라 "성공했다는 사실"만 저장한다.
    static final class VerifiedCache {
        static final int MAX_ENTRIES = 16;
        static final long IDLE_TTL_MILLIS = 12L * 60 * 60 * 1000;
        static final long MAX_AGE_MILLIS = 24L * 60 * 60 * 1000;

        private final LongSupplier clock;
        private final SecretKeySpec key;
        private final Map<String, long[]> entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, long[]> eldest) {
                return size() > MAX_ENTRIES;
            }
        };

        VerifiedCache(LongSupplier clock) {
            this.clock = clock;
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.key = new SecretKeySpec(random, "HmacSHA256");
        }

        String digest(String header) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(key);
                return HexFormat.of().formatHex(mac.doFinal(header.getBytes(StandardCharsets.UTF_8)));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }

        synchronized boolean isValid(String digest) {
            long[] entry = entries.get(digest);
            if (entry == null) {
                return false;
            }
            long now = clock.getAsLong();
            if (now > entry[0] || now - entry[1] > MAX_AGE_MILLIS) {
                entries.remove(digest);
                return false;
            }
            entry[0] = now + IDLE_TTL_MILLIS;
            return true;
        }

        synchronized void put(String digest) {
            long now = clock.getAsLong();
            entries.put(digest, new long[]{now + IDLE_TTL_MILLIS, now});
        }

        synchronized int size() {
            return entries.size();
        }

        synchronized Set<String> keys() {
            return new TreeSet<>(entries.keySet());
        }
    }

    // 클라이언트별 실패 횟수 (제한된 크기, 접근 순서 LRU). 키가 null이면 아무것도 하지 않는다.
    static final class FailureTracker {
        private final LongSupplier clock;
        private final Map<String, long[]> entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, long[]> eldest) {
                return size() > MAX_TRACKED_CLIENTS;
            }
        };

        FailureTracker(LongSupplier clock) {
            this.clock = clock;
        }

        synchronized boolean isLimited(String key) {
            if (key == null) {
                return false;
            }
            long[] entry = entries.get(key);
            if (entry == null) {
                return false;
            }
            if (clock.getAsLong() - entry[1] > FAILURE_WINDOW_MILLIS) {
                entries.remove(key);
                return false;
            }
            return entry[0] >= FAILURES_BEFORE_PENALTY;
        }

        synchronized void record(String key) {
            if (key == null) {
                return;
            }
            long now = clock.getAsLong();
            long[] entry = entries.get(key);
            if (entry == null || now - entry[1] > FAILURE_WINDOW_MILLIS) {
                entries.put(key, new long[]{1, now});
            } else {
                entry[0]++;
            }
        }

        synchronized void clear(String key) {
            if (key != null) {
                entries.remove(key);
            }
        }
    }
}
