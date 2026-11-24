package com.bookmarkdrawer.config;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class AuthFilterTest {

    private static final String USER = "owner";
    private static final String PASSWORD = "test-only-password-4f9a7c";
    private static final String OTHER_PASSWORD = "another-test-password-8d21";
    private static final String ORIGINS = "https://blackbox.example, http://localhost:18080/";
    private static final long HOUR = 60 * 60 * 1000L;
    private static String hash;
    private static String otherHash;

    @BeforeAll
    static void createHashes() {
        hash = PasswordHash.create(PASSWORD.toCharArray(), PasswordHash.MIN_ITERATIONS);
        otherHash = PasswordHash.create(OTHER_PASSWORD.toCharArray(), PasswordHash.MIN_ITERATIONS);
    }

    private static AuthFilter filter() {
        return filter(4, 500, "", System::currentTimeMillis);
    }

    private static AuthFilter filter(int permits, long waitMillis, String clientIpHeader, LongSupplier clock) {
        return new AuthFilter(true, false, USER, hash, ORIGINS, clientIpHeader, permits, waitMillis, clock);
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }

    private static MockHttpServletRequest authed(String method, String uri, String authorization) {
        MockHttpServletRequest request = request(method, uri);
        request.addHeader("Authorization", authorization);
        return request;
    }

    private static Result run(AuthFilter filter, MockHttpServletRequest request)
            throws IOException, ServletException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Result(response, chain.getRequest() != null);
    }

    private record Result(MockHttpServletResponse response, boolean reachedApplication) {
        int status() {
            return response.getStatus();
        }
    }

    // ---- 인증 강제 -------------------------------------------------------------------------

    @Test
    void everyPathAndMethodWithoutCredentialsIsChallengedAndNeverReachesTheApplication() throws Exception {
        AuthFilter filter = filter();
        String[] uris = {"/", "/feed", "/archive", "/search", "/tags", "/tags/merge", "/interest-map",
                "/css/blackbox.css", "/js/feed.js", "/favicon.ico", "/error", "/no-such-page",
                "/api/bookmarks/feed", "/api/bookmarks/batch", "/api/tagging/run", "/api/interest-map/umap",
                "/api/license/status", "/actuator/health", "/%2e%2e/etc", "/static/../x"};
        for (String method : new String[]{"GET", "HEAD", "OPTIONS", "POST", "PUT", "PATCH", "DELETE"}) {
            for (String uri : uris) {
                Result result = run(filter, request(method, uri));
                assertThat(result.status()).as(method + " " + uri).isEqualTo(401);
                assertThat(result.reachedApplication()).as(method + " " + uri).isFalse();
                assertThat(result.response().getHeader("WWW-Authenticate")).startsWith("Basic realm=");
                assertThat(result.response().getHeader("Cache-Control")).isEqualTo("no-store");
                assertThat(result.response().getCookies()).isEmpty();
                assertThat(result.response().getErrorMessage()).isNull();
            }
        }
    }

    @Test
    void correctCredentialsReachTheApplicationForSafeAndAllowedUnsafeRequests() throws Exception {
        AuthFilter filter = filter();
        assertThat(run(filter, authed("GET", "/feed", basic(USER, PASSWORD))).reachedApplication()).isTrue();

        MockHttpServletRequest patch = authed("PATCH", "/api/bookmarks/1/archive", basic(USER, PASSWORD));
        patch.addHeader("Origin", "https://blackbox.example");
        patch.addHeader("Sec-Fetch-Site", "same-origin");
        assertThat(run(filter, patch).reachedApplication()).isTrue();
    }

    @Test
    void wrongPasswordOrUserIsRejectedWithoutLeakingAnything() throws Exception {
        AuthFilter filter = filter();
        for (String[] pair : new String[][]{{USER, "wrong"}, {"intruder", PASSWORD}, {USER, ""}, {"", PASSWORD},
                {USER.toUpperCase(), PASSWORD}, {USER, PASSWORD + " "}}) {
            Result result = run(filter, authed("GET", "/", basic(pair[0], pair[1])));
            assertThat(result.status()).isEqualTo(401);
            assertThat(result.reachedApplication()).isFalse();
            String body = result.response().getContentAsString();
            assertThat(body).doesNotContain(PASSWORD).doesNotContain(hash).doesNotContain(USER);
        }
    }

    @Test
    void passwordMayContainColonsAndNonAsciiCharacters() throws Exception {
        String tricky = "pa:ss:wörd-비밀";
        AuthFilter filter = new AuthFilter(true, false, USER,
                PasswordHash.create(tricky.toCharArray(), PasswordHash.MIN_ITERATIONS), ORIGINS, "", 4, 500,
                System::currentTimeMillis);
        assertThat(run(filter, authed("GET", "/", basic(USER, tricky))).reachedApplication()).isTrue();
    }

    @Test
    void abnormalAuthorizationHeadersAreRejected() throws Exception {
        AuthFilter filter = filter();
        String longValue = "Basic " + "A".repeat(2000);
        String noColon = "Basic " + Base64.getEncoder().encodeToString("nocolon".getBytes(StandardCharsets.UTF_8));
        String badUtf8 = "Basic " + Base64.getEncoder().encodeToString(new byte[]{'o', ':', (byte) 0xC3, (byte) 0x28});
        String[] headers = {"Basic", "Basic ", "Basic !!!not-base64!!!", "Bearer abc", "basic", noColon, badUtf8,
                longValue, "Digest username=\"owner\"", "Basic " + Base64.getEncoder().encodeToString(new byte[0])};
        for (String value : headers) {
            Result result = run(filter, authed("GET", "/", value));
            assertThat(result.status()).as(value.length() > 40 ? "long header" : value).isEqualTo(401);
            assertThat(result.reachedApplication()).isFalse();
        }
        MockHttpServletRequest twoHeaders = authed("GET", "/", basic(USER, PASSWORD));
        twoHeaders.addHeader("Authorization", basic(USER, PASSWORD));
        assertThat(run(filter, twoHeaders).status()).isEqualTo(401);
        assertThat(filter.cacheSize()).isZero();
    }

    @Test
    void duplicatedAuthorizationHeaderIsRejectedEvenWhenTheSameValueIsAlreadyCached() throws Exception {
        AuthFilter filter = filter();
        assertThat(run(filter, authed("GET", "/", basic(USER, PASSWORD))).reachedApplication()).isTrue();
        MockHttpServletRequest twoHeaders = authed("GET", "/", basic(USER, PASSWORD));
        twoHeaders.addHeader("Authorization", basic(USER, PASSWORD));
        assertThat(run(filter, twoHeaders).status()).isEqualTo(401);
    }

    @Test
    void schemeNameIsCaseInsensitive() throws Exception {
        MockHttpServletRequest request = authed("GET", "/", "bAsIc " + basic(USER, PASSWORD).substring(6));
        assertThat(run(filter(), request).reachedApplication()).isTrue();
    }

    // ---- fail-closed / 설정 오류 / 비활성 --------------------------------------------------------

    @Test
    void misconfigurationFailsClosedEvenForValidLookingRequests() throws Exception {
        String[][] configs = {{"", hash}, {"  ", hash}, {null, hash}, {USER, ""}, {USER, null}, {USER, "garbage"},
                {USER, "pbkdf2-sha256$1000$AAAAAAAAAAAAAAAAAAAAAA==$AAAA"}, {USER, PASSWORD}};
        for (String[] config : configs) {
            AuthFilter filter = new AuthFilter(true, false, config[0], config[1], ORIGINS, "", 4, 500,
                    System::currentTimeMillis);
            for (String method : new String[]{"GET", "POST"}) {
                Result result = run(filter, authed(method, "/feed", basic(USER, PASSWORD)));
                assertThat(result.status()).isEqualTo(503);
                assertThat(result.reachedApplication()).isFalse();
                assertThat(result.response().getContentAsString()).doesNotContain(PASSWORD).doesNotContain("garbage");
            }
        }
    }

    @Test
    void disabledIsHonoredOnlyOutsideRailway() throws Exception {
        AuthFilter local = new AuthFilter(false, false, "", "", "", "", 4, 500, System::currentTimeMillis);
        assertThat(run(local, request("POST", "/api/bookmarks/batch")).reachedApplication()).isTrue();
        assertThat(run(local, request("GET", "/")).reachedApplication()).isTrue();

        AuthFilter railwayWithDisableFlag = new AuthFilter(false, true, USER, hash, ORIGINS, "", 4, 500,
                System::currentTimeMillis);
        assertThat(run(railwayWithDisableFlag, request("GET", "/")).status()).isEqualTo(401);

        AuthFilter railwayWithoutConfig = new AuthFilter(false, true, "", "", "", "", 4, 500, System::currentTimeMillis);
        assertThat(run(railwayWithoutConfig, request("GET", "/")).status()).isEqualTo(503);
    }

    // ---- 상태 변경 요청 출처 검증 ------------------------------------------------------------------

    @Test
    void crossOriginStateChangeIsRejectedBeforeAnyPasswordWorkEvenWithValidCredentials() throws Exception {
        AuthFilter filter = filter();
        String[] bodylessEndpoints = {"/api/tagging/run", "/api/tagging/metadata-extract",
                "/api/duplicates/scan", "/api/tags/merge-suggestions"};
        for (String uri : bodylessEndpoints) {
            MockHttpServletRequest request = authed("POST", uri, basic(USER, PASSWORD));
            request.addHeader("Origin", "https://evil.example");
            request.addHeader("Sec-Fetch-Site", "cross-site");
            Result result = run(filter, request);
            assertThat(result.status()).as(uri).isEqualTo(403);
            assertThat(result.reachedApplication()).isFalse();
        }
        MockHttpServletRequest wrongPassword = authed("DELETE", "/api/bookmarks/1", basic(USER, "wrong"));
        wrongPassword.addHeader("Origin", "https://evil.example");
        assertThat(run(filter, wrongPassword).status()).isEqualTo(403);
        assertThat(filter.verificationCount()).isZero();
    }

    @Test
    void originPolicyMatrixIsAppliedToUnsafeMethodsOnly() throws Exception {
        AuthFilter filter = filter();
        Object[][] cases = {
                {"POST", "https://blackbox.example", "same-origin", true},
                {"PATCH", "http://localhost:18080", null, true},
                {"POST", null, null, true},
                {"POST", null, "same-origin", true},
                {"POST", "null", null, false},
                {"POST", "https://evil.example", "same-origin", false},
                {"POST", "https://blackbox.example", "cross-site", false},
                {"POST", "https://blackbox.example", "same-site", false},
                {"DELETE", "chrome-extension://abcdefghijklmnop", null, false},
                {"GET", "https://evil.example", "cross-site", true},
                {"HEAD", "https://evil.example", null, true},
        };
        for (Object[] c : cases) {
            MockHttpServletRequest request = authed((String) c[0], "/api/x", basic(USER, PASSWORD));
            if (c[1] != null) request.addHeader("Origin", c[1]);
            if (c[2] != null) request.addHeader("Sec-Fetch-Site", c[2]);
            assertThat(run(filter, request).reachedApplication())
                    .as(c[0] + " origin=" + c[1] + " site=" + c[2]).isEqualTo((boolean) c[3]);
        }
    }

    @Test
    void multipleOriginHeadersAreRejected() throws Exception {
        MockHttpServletRequest request = authed("POST", "/api/x", basic(USER, PASSWORD));
        request.addHeader("Origin", "https://blackbox.example");
        request.addHeader("Origin", "https://blackbox.example");
        assertThat(run(filter(), request).status()).isEqualTo(403);
    }

    @Test
    void missingAllowedOriginsRejectsBrowserWritesButKeepsSafeRequestsWorking() throws Exception {
        AuthFilter filter = new AuthFilter(true, false, USER, hash, "", "", 4, 500, System::currentTimeMillis);
        MockHttpServletRequest post = authed("POST", "/api/x", basic(USER, PASSWORD));
        post.addHeader("Origin", "https://blackbox.example");
        assertThat(run(filter, post).status()).isEqualTo(403);
        assertThat(run(filter, authed("GET", "/feed", basic(USER, PASSWORD))).reachedApplication()).isTrue();
    }

    @Test
    void unauthenticatedPreflightIsNotAnswered() throws Exception {
        MockHttpServletRequest preflight = request("OPTIONS", "/api/bookmarks/1/archive");
        preflight.addHeader("Origin", "https://evil.example");
        preflight.addHeader("Access-Control-Request-Method", "PATCH");
        Result result = run(filter(), preflight);
        assertThat(result.status()).isEqualTo(401);
        assertThat(result.response().getHeader("Access-Control-Allow-Origin")).isNull();
    }

    // ---- 성공 인증 캐시 -----------------------------------------------------------------------------

    @Test
    void successfulHeaderIsVerifiedOnceAndThenServedFromTheCache() throws Exception {
        AuthFilter filter = filter();
        for (int i = 0; i < 6; i++) {
            assertThat(run(filter, authed("GET", "/css/blackbox.css", basic(USER, PASSWORD))).reachedApplication())
                    .isTrue();
        }
        assertThat(filter.verificationCount()).isEqualTo(1);
        assertThat(filter.cacheSize()).isEqualTo(1);
    }

    @Test
    void cacheHoldsOnlyOpaqueDigestsNeverTheHeaderOrThePassword() throws Exception {
        AuthFilter filter = filter();
        String header = basic(USER, PASSWORD);
        run(filter, authed("GET", "/", header));
        assertThat(filter.cacheKeys()).hasSize(1);
        for (String key : filter.cacheKeys()) {
            assertThat(key).matches("[0-9a-f]{64}");
            assertThat(key).doesNotContain(PASSWORD).doesNotContain(USER).doesNotContain(header.substring(6));
        }
    }

    @Test
    void failedAttemptsAreNeverCached() throws Exception {
        AuthFilter filter = filter();
        for (int i = 0; i < 5; i++) {
            run(filter, authed("GET", "/", basic(USER, "bad" + i)));
            run(filter, authed("GET", "/", basic("nobody", PASSWORD)));
        }
        assertThat(filter.cacheSize()).isZero();
        assertThat(run(filter, authed("GET", "/", basic(USER, "bad0"))).status()).isEqualTo(401);
        run(filter, authed("GET", "/", basic(USER, PASSWORD)));
        assertThat(filter.cacheSize()).isEqualTo(1);
    }

    @Test
    void aCachedHeaderDoesNotLetADifferentHeaderThrough() throws Exception {
        AuthFilter filter = filter();
        run(filter, authed("GET", "/", basic(USER, PASSWORD)));
        assertThat(run(filter, authed("GET", "/", basic(USER, PASSWORD + "x"))).status()).isEqualTo(401);
        assertThat(run(filter, authed("GET", "/", basic(USER, OTHER_PASSWORD))).status()).isEqualTo(401);
    }

    @Test
    void cacheHitsStillGoThroughTheOriginPolicy() throws Exception {
        AuthFilter filter = filter();
        run(filter, authed("GET", "/", basic(USER, PASSWORD)));
        assertThat(filter.verificationCount()).isEqualTo(1);

        MockHttpServletRequest evil = authed("POST", "/api/tagging/run", basic(USER, PASSWORD));
        evil.addHeader("Origin", "https://evil.example");
        assertThat(run(filter, evil).status()).isEqualTo(403);
        MockHttpServletRequest crossSite = authed("PATCH", "/api/bookmarks/1/archive", basic(USER, PASSWORD));
        crossSite.addHeader("Origin", "https://blackbox.example");
        crossSite.addHeader("Sec-Fetch-Site", "cross-site");
        assertThat(run(filter, crossSite).status()).isEqualTo(403);
        MockHttpServletRequest nullOrigin = authed("DELETE", "/api/bookmarks/1", basic(USER, PASSWORD));
        nullOrigin.addHeader("Origin", "null");
        assertThat(run(filter, nullOrigin).status()).isEqualTo(403);

        MockHttpServletRequest legit = authed("PATCH", "/api/bookmarks/1/archive", basic(USER, PASSWORD));
        legit.addHeader("Origin", "https://blackbox.example");
        assertThat(run(filter, legit).reachedApplication()).isTrue();
        assertThat(filter.verificationCount()).isEqualTo(1);
    }

    @Test
    void cacheEntriesExpireAfterIdleAndAbsoluteLimits() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000);
        AuthFilter filter = filter(4, 500, "", now::get);
        String header = basic(USER, PASSWORD);

        run(filter, authed("GET", "/", header));                       // t0: 검증 1회
        now.addAndGet(11 * HOUR);
        assertThat(run(filter, authed("GET", "/", header)).reachedApplication()).isTrue();   // 유휴 TTL 안: 적중, 유휴 시간 갱신
        now.addAndGet(11 * HOUR);
        assertThat(run(filter, authed("GET", "/", header)).reachedApplication()).isTrue();   // 22h: 여전히 적중
        assertThat(filter.verificationCount()).isEqualTo(1);
        now.addAndGet(3 * HOUR);                                       // 25h: 절대 TTL(24h) 초과 → 다시 검증
        assertThat(run(filter, authed("GET", "/", header)).reachedApplication()).isTrue();
        assertThat(filter.verificationCount()).isEqualTo(2);

        now.addAndGet(12 * HOUR + 1);                                  // 유휴 TTL(12h) 초과 → 다시 검증
        assertThat(run(filter, authed("GET", "/", header)).reachedApplication()).isTrue();
        assertThat(filter.verificationCount()).isEqualTo(3);
    }

    @Test
    void cacheSizeIsBoundedAndEvictsTheLeastRecentlyUsedHeader() throws Exception {
        AuthFilter filter = filter();
        String b64 = basic(USER, PASSWORD).substring(6);
        int variants = AuthFilter.VerifiedCache.MAX_ENTRIES + 4;
        for (int i = 0; i < variants; i++) {
            // 같은 자격 증명을 서로 다른 문자열로 표현한 헤더 (공백 개수만 다름)
            assertThat(run(filter, authed("GET", "/", "Basic " + " ".repeat(i) + b64)).reachedApplication()).isTrue();
        }
        assertThat(filter.cacheSize()).isEqualTo(AuthFilter.VerifiedCache.MAX_ENTRIES);
        long before = filter.verificationCount();
        run(filter, authed("GET", "/", "Basic " + " ".repeat(variants - 1) + b64));        // 가장 최근: 적중
        assertThat(filter.verificationCount()).isEqualTo(before);
        run(filter, authed("GET", "/", "Basic " + b64));                                  // 가장 오래된 것: 쫓겨났으므로 재검증
        assertThat(filter.verificationCount()).isEqualTo(before + 1);
    }

    @Test
    void aRestartedFilterStartsWithAnEmptyCacheAndTheRotatedPasswordWins() throws Exception {
        AuthFilter before = filter();
        run(before, authed("GET", "/", basic(USER, PASSWORD)));
        assertThat(before.cacheSize()).isEqualTo(1);

        AuthFilter rotated = new AuthFilter(true, false, USER, otherHash, ORIGINS, "", 4, 500, System::currentTimeMillis);
        assertThat(rotated.cacheSize()).isZero();
        assertThat(run(rotated, authed("GET", "/", basic(USER, PASSWORD))).status()).isEqualTo(401);
        assertThat(run(rotated, authed("GET", "/", basic(USER, OTHER_PASSWORD))).reachedApplication()).isTrue();
        // 같은 원본 헤더라도 새 인스턴스의 캐시 키(프로세스별 HMAC 키)는 다르다.
        run(before, authed("GET", "/", basic(USER, OTHER_PASSWORD)));
        assertThat(before.cacheKeys()).doesNotContainAnyElementsOf(rotated.cacheKeys());
    }

    // ---- 병렬성 / 슬롯 포화 -------------------------------------------------------------------------

    @Test
    void parallelFirstRequestsWithTheSameHeaderCauseAtMostOneVerificationPerSlot() throws Exception {
        AuthFilter filter = filter(2, 5000, "", System::currentTimeMillis);
        List<Result> results = runParallel(12, i -> {
            try {
                return run(filter, authed("GET", "/js/feed.js", basic(USER, PASSWORD)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(results).allMatch(Result::reachedApplication);
        assertThat(filter.verificationCount()).isLessThanOrEqualTo(2);
        assertThat(filter.maxActiveVerifications()).isLessThanOrEqualTo(2);
    }

    @Test
    void whileEverySlotIsBusyCachedRequestsKeepWorkingAndOthersAreRefusedWithinTheBound() throws Exception {
        AuthFilter filter = filter(1, 400, "", System::currentTimeMillis);
        assertThat(run(filter, authed("GET", "/", basic(USER, PASSWORD))).reachedApplication()).isTrue();   // 캐시에 올림

        filter.verifyPermitsForTest().acquire();           // 슬롯을 모두 점유한 상태를 만든다
        try {
            long started = System.nanoTime();
            List<Result> flood = runParallel(40, i -> {
                try {
                    return run(filter, authed("GET", "/", basic(USER, "guess-" + i)));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            long floodMillis = (System.nanoTime() - started) / 1_000_000;
            assertThat(flood).allMatch(r -> r.status() == 503 && !r.reachedApplication());
            assertThat(filter.shedCount()).isEqualTo(40);       // 대기열(슬롯 x16) 밖은 즉시, 안쪽은 대기 한도 후 거절 - 어느 쪽도 인증되지 않는다
            assertThat(floodMillis).isLessThan(5_000);

            List<Result> owner = runParallel(12, i -> {
                try {
                    return run(filter, authed("GET", "/css/blackbox.css", basic(USER, PASSWORD)));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            assertThat(owner).allMatch(r -> r.status() == 200 && r.reachedApplication());
        } finally {
            filter.verifyPermitsForTest().release();
        }
    }

    @Test
    void failuresMustNotConsumeMoreThanTheConfiguredNumberOfVerificationSlots() throws Exception {
        AuthFilter filter = filter(2, 3000, "", System::currentTimeMillis);
        runParallel(24, i -> {
            try {
                return run(filter, authed("GET", "/", basic(USER, "wrong-" + i)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(filter.maxActiveVerifications()).isLessThanOrEqualTo(2);
        assertThat(filter.cacheSize()).isZero();
    }

    // ---- 클라이언트 구분은 신뢰할 헤더를 설정했을 때만 -------------------------------------------------------

    @Test
    void withoutAClientIpHeaderFailuresNeverPushAnyoneIntoThePenaltyPath() throws Exception {
        AuthFilter filter = filter();
        for (int i = 0; i < 30; i++) {
            run(filter, authed("GET", "/", basic(USER, "bad" + i)));
        }
        assertThat(filter.isLimited("127.0.0.1")).isFalse();
        assertThat(filter.isLimited(null)).isFalse();
    }

    @Test
    void repeatedFailuresFromOneConfiguredClientMoveOnlyThatClientToThePenaltyLane() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000);
        AuthFilter filter = filter(4, 500, "X-Real-IP", now::get);
        for (int i = 0; i < 8; i++) {
            MockHttpServletRequest request = authed("GET", "/", basic(USER, "bad" + i));
            request.addHeader("X-Real-IP", "203.0.113.9");
            assertThat(run(filter, request).status()).isEqualTo(401);
        }
        assertThat(filter.isLimited("203.0.113.9")).isTrue();
        assertThat(filter.isLimited("198.51.100.7")).isFalse();

        // 제한된 클라이언트도 올바른 비밀번호는 통과한다(차단이 아니라 감속).
        MockHttpServletRequest good = authed("GET", "/", basic(USER, PASSWORD));
        good.addHeader("X-Real-IP", "203.0.113.9");
        assertThat(run(filter, good).reachedApplication()).isTrue();
        assertThat(filter.isLimited("203.0.113.9")).isFalse();

        for (int i = 0; i < 8; i++) {
            MockHttpServletRequest request = authed("GET", "/", basic(USER, "bad" + i));
            request.addHeader("X-Real-IP", "203.0.113.9");
            run(filter, request);
        }
        assertThat(filter.isLimited("203.0.113.9")).isTrue();
        now.addAndGet(5 * 60_000L + 1);
        assertThat(filter.isLimited("203.0.113.9")).isFalse();
    }

    @Test
    void malformedClientIpHeaderValuesAreIgnored() throws Exception {
        AuthFilter filter = filter(4, 500, "X-Real-IP", System::currentTimeMillis);
        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest garbage = authed("GET", "/", basic(USER, "bad"));
            garbage.addHeader("X-Real-IP", "<script>");
            run(filter, garbage);
        }
        assertThat(filter.isLimited("<script>")).isFalse();
    }

    @Test
    void missingAuthorizationHeaderIsNotCountedAsAFailure() throws Exception {
        AuthFilter filter = filter(4, 500, "X-Real-IP", System::currentTimeMillis);
        for (int i = 0; i < 20; i++) {
            MockHttpServletRequest request = request("GET", "/");
            request.addHeader("X-Real-IP", "203.0.113.9");
            run(filter, request);
        }
        assertThat(filter.isLimited("203.0.113.9")).isFalse();
    }

    @Test
    void whenNoVerificationSlotBecomesAvailableTheAnswerIsBusyNotAuthenticated() throws Exception {
        AuthFilter filter = filter(0, 100, "", System::currentTimeMillis);
        Result result = run(filter, authed("GET", "/", basic(USER, PASSWORD)));
        assertThat(result.status()).isEqualTo(503);
        assertThat(result.response().getHeader("Retry-After")).isEqualTo("1");
        assertThat(result.reachedApplication()).isFalse();
        assertThat(filter.cacheSize()).isZero();
    }

    // ---- 비밀번호 변경 / 재진입 ---------------------------------------------------------------------------

    @Test
    void changingTheStoredHashInvalidatesTheOldPasswordImmediately() throws Exception {
        assertThat(run(filter(), authed("GET", "/", basic(USER, PASSWORD))).reachedApplication()).isTrue();

        AuthFilter rotated = new AuthFilter(true, false, USER, otherHash, ORIGINS, "", 4, 500, System::currentTimeMillis);
        assertThat(run(rotated, authed("GET", "/", basic(USER, PASSWORD))).status()).isEqualTo(401);
        assertThat(run(rotated, authed("GET", "/", basic(USER, OTHER_PASSWORD))).reachedApplication()).isTrue();
    }

    @Test
    void internalRedispatchOfAnAlreadyAuthenticatedRequestIsNotChallengedAgain() throws Exception {
        MockHttpServletRequest request = request("GET", "/error");
        request.setAttribute(AuthFilter.PASSED_ATTRIBUTE, Boolean.TRUE);
        assertThat(run(filter(), request).reachedApplication()).isTrue();
    }

    // ---- 보조 ---------------------------------------------------------------------------------------------

    private interface IndexedCall {
        Result call(int index);
    }

    private static List<Result> runParallel(int count, IndexedCall call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            List<Future<Result>> futures = new ArrayList<>();
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    go.await();
                    return call.call(index);
                }));
            }
            go.countDown();
            List<Result> results = new ArrayList<>();
            for (Future<Result> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
