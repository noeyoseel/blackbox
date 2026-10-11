package com.bookmarkdrawer.config;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

// AuthFilter 등록. Spring Boot는 FilterRegistrationBean 없이 등록한 일반 Filter를 REQUEST 디스패치에만 건다.
// 컨테이너가 필터 앞에서 거부한 요청(필터가 돌기 전 오류)은 ERROR 디스패치로 /error를 호출하므로,
// 이 경로에서 앱의 오류 응답이 인증 없이 나가지 않도록 REQUEST와 ERROR를 명시한다.
// ASYNC/FORWARD/INCLUDE는 걸지 않는다: 서블릿 비동기·forward·include를 쓰지 않고, 쓰더라도 이미 REQUEST에서 인증을 통과한 요청이다.
// AuthFilter를 별도 빈으로 노출하지 않는다(이중 등록 방지).
@Configuration
public class AuthFilterConfig {

    // 인코딩 필터(HIGHEST_PRECEDENCE)보다 뒤, 나머지 앱 필터보다 앞.
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 100;

    @Bean
    public FilterRegistrationBean<AuthFilter> blackboxAuthFilter(
            Environment env,
            @Value("${blackbox.auth.enabled:true}") boolean enabled,
            @Value("${blackbox.auth.user:}") String user,
            @Value("${blackbox.auth.password-hash:}") String passwordHash,
            @Value("${blackbox.auth.allowed-origins:}") String allowedOrigins,
            @Value("${blackbox.auth.client-ip-header:}") String clientIpHeader) {
        FilterRegistrationBean<AuthFilter> registration = new FilterRegistrationBean<>(
                new AuthFilter(env, enabled, user, passwordHash, allowedOrigins, clientIpHeader));
        registration.setName("blackboxAuthFilter");
        registration.addUrlPatterns("/*");
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR);
        registration.setOrder(ORDER);
        return registration;
    }
}
