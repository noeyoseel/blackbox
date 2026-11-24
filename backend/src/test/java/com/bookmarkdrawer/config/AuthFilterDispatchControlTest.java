package com.bookmarkdrawer.config;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

// 대조 시험: 예전 방식(디스패치 타입 REQUEST만)으로 등록하면 "필터 앞에서 만들어진 오류"가 인증 없이 앱의 오류 응답으로 나간다.
// AuthFilterIntegrationTest가 이 결함을 실제로 검출할 수 있음을 보여 주는 용도이며, 운영 코드의 동작이 아니다.
@SpringBootTest(classes = AuthFilterDispatchControlTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthFilterDispatchControlTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            JpaRepositoriesAutoConfiguration.class})
    @Import({AuthFilterIntegrationTest.Probe.class, AuthFilterIntegrationTest.PreFilterError.class})
    static class TestApp {
        @Bean
        FilterRegistrationBean<AuthFilter> requestOnlyAuthFilter() {
            FilterRegistrationBean<AuthFilter> registration = new FilterRegistrationBean<>(new AuthFilter(
                    true, false, "owner", AuthFilterIntegrationTest.HASH, "", "", 2, 500, System::currentTimeMillis));
            registration.setDispatcherTypes(DispatcherType.REQUEST);
            registration.setOrder(AuthFilterConfig.ORDER);
            return registration;
        }
    }

    @LocalServerPort
    int port;

    @Test
    void requestOnlyRegistrationLeaksTheAppErrorPageForAnUnauthenticatedContainerLevelError() throws Exception {
        String raw = rawGet("/pre-filter-error");
        assertThat(raw).startsWith("HTTP/1.1 400");
        assertThat(raw).contains("\"status\":400").doesNotContain("Unauthorized");
        // 같은 환경에서 일반 요청은 여전히 막혀 있다: 결함은 ERROR 디스패치 경로에만 있다.
        assertThat(rawGet("/ok")).startsWith("HTTP/1.1 401");
    }

    private String rawGet(String path) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(15_000);
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost:" + port
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
