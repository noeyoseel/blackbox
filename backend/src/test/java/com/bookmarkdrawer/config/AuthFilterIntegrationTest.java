package com.bookmarkdrawer.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 임베디드 Tomcat 위에서 AuthFilterConfig가 등록한 필터를 검사한다(DB 없는 최소 앱).
// 단위 테스트는 doFilter를 직접 호출하므로 등록·순서·디스패치 타입을 검증하지 못한다.
@SpringBootTest(classes = AuthFilterIntegrationTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"blackbox.auth.user=owner", "server.error.include-message=never"})
class AuthFilterIntegrationTest {

    static final String PASSWORD = "integration-only-password-71c3";
    static final String HASH = PasswordHash.create(PASSWORD.toCharArray(), PasswordHash.MIN_ITERATIONS);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("blackbox.auth.password-hash", () -> HASH);
    }

    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext context;

    // ---- 테스트용 최소 앱 -------------------------------------------------------------------------

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            JpaRepositoriesAutoConfiguration.class})
    @Import({AuthFilterConfig.class, Probe.class, PreFilterError.class})
    static class TestApp {
    }

    @RestController
    public static class Probe {
        @GetMapping("/ok")
        public String ok() {
            return "ok";
        }

        @GetMapping("/boom")
        public String boom() {
            throw new IllegalStateException("secret-internal-detail");
        }

        @PostMapping("/write")
        public String write() {
            return "written";
        }
    }

    // 인증 필터보다 먼저 실행되어 sendError를 호출한다: "필터가 돌기 전에 컨테이너가 만든 오류"를 결정적으로 재현한다.
    @Configuration
    public static class PreFilterError {
        @Bean
        FilterRegistrationBean<Filter> preFilterError() {
            Filter filter = (request, response, chain) -> {
                if ("/pre-filter-error".equals(((HttpServletRequest) request).getRequestURI())) {
                    ((HttpServletResponse) response).sendError(400, "container-detail-must-not-leak");
                    return;
                }
                chain.doFilter(request, response);
            };
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
            registration.setName("preFilterError");
            registration.setOrder(AuthFilterConfig.ORDER - 50);
            return registration;
        }
    }

    // ---- 등록 ---------------------------------------------------------------------------------------

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void authFilterIsRegisteredExactlyOnceForRequestAndErrorDispatchesWithTheExpectedOrder() {
        List<FilterRegistrationBean> registrations = new ArrayList<>(
                context.getBeansOfType(FilterRegistrationBean.class).values());
        List<FilterRegistrationBean> auth = registrations.stream()
                .filter(r -> r.getFilter() instanceof AuthFilter).toList();
        assertThat(auth).hasSize(1);
        FilterRegistrationBean registration = auth.get(0);
        assertThat(registration.determineDispatcherTypes()).isEqualTo(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ERROR));
        assertThat(registration.getOrder()).isEqualTo(AuthFilterConfig.ORDER).isGreaterThan(Ordered.HIGHEST_PRECEDENCE);
        assertThat(registration.getUrlPatterns()).containsExactly("/*");
        assertThat(context.getBeansOfType(AuthFilter.class)).isEmpty();
    }

    // ---- 인증 없는 REQUEST / ERROR -------------------------------------------------------------------------

    @Test
    void unauthenticatedRequestsAreChallengedAndNeverReachTheApplication() throws Exception {
        for (String path : new String[]{"/ok", "/boom", "/nope", "/error"}) {
            Http response = get(path, null);
            assertThat(response.status).as(path).isEqualTo(401);
            assertThat(response.header("www-authenticate")).as(path).startsWith("Basic realm=");
            assertThat(response.body).as(path).isEqualTo("Unauthorized");
        }
        Http post = send("POST /write HTTP/1.1", "Content-Length: 0");
        assertThat(post.status).isEqualTo(401);
        assertThat(post.body).doesNotContain("written");
    }

    @Test
    void anUnauthenticatedErrorDispatchRaisedBeforeTheFilterIsChallengedInsteadOfLeakingTheAppErrorPage() throws Exception {
        Http response = get("/pre-filter-error", null);
        assertThat(response.status).isEqualTo(401);
        assertThat(response.body).isEqualTo("Unauthorized")
                .doesNotContain("container-detail-must-not-leak").doesNotContain("timestamp").doesNotContain("\"error\"");
        assertThat(response.header("www-authenticate")).startsWith("Basic realm=");
    }

    // ---- 정상 인증 후 오류 처리 ---------------------------------------------------------------------------------

    @Test
    void authenticatedRequestsKeepWorkingIncludingTheApplicationsOwnErrorHandling() throws Exception {
        String auth = basic(PASSWORD);
        assertThat(get("/ok", auth).status).isEqualTo(200);

        Http boom = get("/boom", auth);
        assertThat(boom.status).isEqualTo(500);
        assertThat(boom.body).contains("\"status\":500").doesNotContain("secret-internal-detail");

        Http missing = get("/nope", auth);
        assertThat(missing.status).isEqualTo(404);
        assertThat(missing.body).contains("\"status\":404");

        // 필터보다 먼저 만들어진 오류도 인증된 요청이면 앱의 오류 응답이 그대로 나간다(ERROR 디스패치에서 다시 인증됨).
        Http container = get("/pre-filter-error", auth);
        assertThat(container.status).isEqualTo(400);
        assertThat(container.body).contains("\"status\":400").doesNotContain("container-detail-must-not-leak");

        Http write = send("POST /write HTTP/1.1", "Content-Length: 0", "Authorization: " + auth);
        assertThat(write.status).isEqualTo(200);
        assertThat(write.body).isEqualTo("written");
    }

    // ---- 비정상 URI / 헤더 --------------------------------------------------------------------------------------

    @Test
    void abnormalUrisAndHeadersAreNeverServedAndNeverLeakApplicationErrorDetails() throws Exception {
        String longPath = "/" + "a".repeat(20_000);
        String hugeHeader = "X-Padding: " + "p".repeat(20_000);
        String[][] requests = {
                {"GET /%2e%2e/ok HTTP/1.1"},
                {"GET /%zz HTTP/1.1"},
                {"GET /ok%00 HTTP/1.1"},
                {"GET //ok/../../boom HTTP/1.1"},
                {"GET " + longPath + " HTTP/1.1"},
                {"GET /ok HTTP/1.1", hugeHeader},
                {"GET http://evil.example/ok HTTP/1.1"},
                {"OPTIONS * HTTP/1.1"},
                {"TRACE /ok HTTP/1.1"},
                {"GET /ok HTTP/1.1", "Authorization: Basic " + "A".repeat(5000)},
                {"GET /ok HTTP/1.1", "Authorization: Basic !!!", "Authorization: Basic !!!"},
                {"GET /ok HTTP/1.1", "Authorization: Basic " + Base64.getEncoder().encodeToString(new byte[]{'o', ':', (byte) 0xC3, (byte) 0x28})},
                {"GET /ok HTTP/1.1", "Origin: null", "Origin: https://evil.example"},
        };
        // OPTIONS * 는 서블릿 매핑 이전에 Tomcat이 직접 답한다(서버 전체 질의). 앱 코드·데이터에 닿지 않으므로 본문이 비어 있는지만 확인한다.
        Http serverOptions = send("OPTIONS * HTTP/1.1");
        assertThat(serverOptions.body).isEmpty();
        assertThat(serverOptions.headers.toLowerCase()).doesNotContain("set-cookie");
        for (String[] request : requests) {
            if (request[0].startsWith("OPTIONS *")) {
                continue;
            }
            Http response = send(request);
            String label = request[0].length() > 60 ? request[0].substring(0, 60) + "..." : request[0];
            assertThat(response.status < 200 || response.status > 399).as(label + " status=" + response.status).isTrue();
            assertThat(response.body).as(label).doesNotContain("secret-internal-detail").doesNotContain("\"trace\"")
                    .doesNotContain("\"timestamp\"").doesNotContain("container-detail-must-not-leak");
        }
    }

    // ---- 동시 리소스 요청 / 실패 폭주 ----------------------------------------------------------------------------

    @Test
    void aBurstOfParallelFirstRequestsWithTheSameHeaderIsVerifiedByAtMostOneSlotEach() throws Exception {
        AuthFilter filter = authFilter();
        int permits = filter.verifyPermitsForTest().availablePermits();
        long before = filter.verificationCount();
        String auth = basic(PASSWORD) + " ";        // 다른 테스트의 캐시와 겹치지 않는 표기
        List<Http> responses = parallel(24, i -> get("/ok", auth));
        assertThat(responses).allMatch(r -> r.status == 200);
        assertThat(filter.verificationCount() - before).isLessThanOrEqualTo(permits);
        assertThat(filter.maxActiveVerifications()).isLessThanOrEqualTo(permits);
    }

    @Test
    void ownerRequestsAreNotAffectedByAFailureFloodAndVerificationStaysWithinTheSlotLimit() throws Exception {
        AuthFilter filter = authFilter();
        int permits = filter.verifyPermitsForTest().availablePermits();
        String ownerAuth = basic(PASSWORD) + "  ";
        assertThat(get("/ok", ownerAuth).status).isEqualTo(200);            // 소유자 헤더가 캐시에 올라 있는 정상 상태

        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger attackerRequests = new AtomicInteger();
        ExecutorService attackers = Executors.newFixedThreadPool(24);
        for (int t = 0; t < 24; t++) {
            int id = t;
            attackers.submit(() -> {
                int n = 0;
                while (!stop.get()) {
                    try {
                        get("/ok", basic("wrong-" + id + "-" + n++));
                        attackerRequests.incrementAndGet();
                    } catch (Exception ignored) {
                        // 폭주 중 연결 실패는 시험 대상이 아니다
                    }
                }
            });
        }
        List<Integer> ownerStatuses = new ArrayList<>();
        try {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < end) {
                for (Http r : parallel(6, i -> get("/ok", ownerAuth))) {          // 페이지 하나의 병렬 리소스처럼
                    ownerStatuses.add(r.status);
                }
            }
        } finally {
            stop.set(true);
            attackers.shutdown();
            attackers.awaitTermination(20, TimeUnit.SECONDS);
        }
        assertThat(attackerRequests.get()).isGreaterThan(100);
        assertThat(ownerStatuses).isNotEmpty().allMatch(s -> s == 200);
        assertThat(filter.maxActiveVerifications()).isLessThanOrEqualTo(permits);
        System.out.printf("flood: attacker requests=%d owner requests=%d all 200, max concurrent verifications=%d (slots=%d), shed=%d%n",
                attackerRequests.get(), ownerStatuses.size(), filter.maxActiveVerifications(), permits, filter.shedCount());
    }

    @Test
    void aFreshLoginDuringAFloodSucceedsWithinAFewRetries() throws Exception {
        AuthFilter filter = authFilter();
        AtomicBoolean stop = new AtomicBoolean();
        ExecutorService attackers = Executors.newFixedThreadPool(24);
        for (int t = 0; t < 24; t++) {
            int id = t;
            attackers.submit(() -> {
                int n = 0;
                while (!stop.get()) {
                    try {
                        get("/ok", basic("wrong-" + id + "-" + n++));
                    } catch (Exception ignored) {
                    }
                }
            });
        }
        int attempts = 0;
        int status = 0;
        try {
            String freshAuth = basic(PASSWORD) + "   ";         // 캐시에 없는 새 로그인
            while (attempts < 10 && status != 200) {
                attempts++;
                status = get("/ok", freshAuth).status;
                if (status != 200) {
                    assertThat(status).isEqualTo(503);           // 거절되더라도 인증 실패(401)가 아니라 재시도 가능한 503
                    Thread.sleep(100);
                }
            }
        } finally {
            stop.set(true);
            attackers.shutdown();
            attackers.awaitTermination(20, TimeUnit.SECONDS);
        }
        System.out.printf("fresh login under flood: status=%d after %d attempt(s)%n", status, attempts);
        assertThat(status).isEqualTo(200);
        assertThat(filter.cacheSize()).isGreaterThanOrEqualTo(1);
    }

    // ---- 보조 ---------------------------------------------------------------------------------------------------

    @SuppressWarnings("rawtypes")
    private AuthFilter authFilter() {
        for (FilterRegistrationBean registration : context.getBeansOfType(FilterRegistrationBean.class).values()) {
            if (registration.getFilter() instanceof AuthFilter filter) {
                return filter;
            }
        }
        throw new IllegalStateException("AuthFilter registration not found");
    }

    private static String basic(String password) {
        return "Basic " + Base64.getEncoder().encodeToString(("owner:" + password).getBytes(StandardCharsets.UTF_8));
    }

    private Http get(String path, String authorization) throws Exception {
        return authorization == null
                ? send("GET " + path + " HTTP/1.1")
                : send("GET " + path + " HTTP/1.1", "Authorization: " + authorization);
    }

    // 원시 소켓 요청: 클라이언트 라이브러리가 URI/헤더를 정규화하거나 거부하지 않게 한다.
    private Http send(String... lines) throws Exception {
        StringBuilder request = new StringBuilder(lines[0]).append("\r\nHost: localhost:").append(port)
                .append("\r\nConnection: close\r\n");
        for (int i = 1; i < lines.length; i++) {
            request.append(lines[i]).append("\r\n");
        }
        request.append("\r\n");
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(15_000);
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[8192];
            try {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                }
            } catch (java.net.SocketException ignored) {
                // 서버가 비정상 요청 뒤 연결을 끊는 경우: 읽은 데까지 사용
            }
            return Http.parse(out.toString(StandardCharsets.UTF_8));
        }
    }

    private interface Call {
        Http call(int index) throws Exception;
    }

    private static List<Http> parallel(int count, Call call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Http>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    go.await();
                    return call.call(index);
                }));
            }
            go.countDown();
            List<Http> results = new ArrayList<>();
            for (Future<Http> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    record Http(int status, String headers, String body) {
        static Http parse(String raw) {
            int split = raw.indexOf("\r\n\r\n");
            String head = split >= 0 ? raw.substring(0, split) : raw;
            String body = split >= 0 ? raw.substring(split + 4) : "";
            String statusLine = head.lines().findFirst().orElse("");
            int status = -1;
            String[] parts = statusLine.split(" ");
            if (parts.length >= 2) {
                try {
                    status = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {
                }
            }
            if (head.toLowerCase().contains("transfer-encoding: chunked")) {
                body = dechunk(body);
            }
            return new Http(status, head, body.strip());
        }

        private static String dechunk(String raw) {
            StringBuilder out = new StringBuilder();
            int pos = 0;
            while (pos < raw.length()) {
                int eol = raw.indexOf("\r\n", pos);
                if (eol < 0) break;
                int size;
                try {
                    size = Integer.parseInt(raw.substring(pos, eol).trim(), 16);
                } catch (NumberFormatException e) {
                    break;
                }
                if (size == 0) break;
                int start = eol + 2;
                int end = Math.min(raw.length(), start + size);
                out.append(raw, start, end);
                pos = end + 2;
            }
            return out.toString();
        }

        String header(String name) {
            return headers.lines().filter(l -> l.toLowerCase().startsWith(name.toLowerCase() + ":"))
                    .map(l -> l.substring(name.length() + 1).trim()).findFirst().orElse(null);
        }
    }
}
