package com.bookmarkdrawer.config;

import java.util.Set;

// 상태 변경 요청의 출처 검증 (CSRF 방어). 인증 필터에서 호출한다.
//
// 정책:
//  1. GET/HEAD/OPTIONS/TRACE는 상태를 바꾸지 않는 메서드라 검사하지 않는다.
//  2. Sec-Fetch-Site는 "거부 전용 보조 신호"다. same-origin/none이 아닌 값(cross-site, same-site 등)이면 거부하고,
//     어떤 값도 그 자체로 요청을 허용하지는 않는다.
//  3. Origin이 있으면 허용 목록과 정확히 일치해야 한다. "null"과 목록에 없는 값은 거부.
//  4. Origin이 없으면 허용한다. 브라우저는 교차 출처 POST/PATCH/DELETE에 Origin을 항상 붙이므로,
//     Origin이 없는 비안전 요청은 브라우저 밖 클라이언트(curl 등)로 보며 그래도 Basic 자격 증명은 요구한다.
//     (단점: 아주 오래된 브라우저의 Origin 없는 요청까지 통과한다.)
final class OriginPolicy {

    private OriginPolicy() {
    }

    static boolean isSafeMethod(String method) {
        return "GET".equals(method) || "HEAD".equals(method)
                || "OPTIONS".equals(method) || "TRACE".equals(method);
    }

    static boolean allows(String method, String origin, String secFetchSite, Set<String> allowedOrigins) {
        if (isSafeMethod(method)) {
            return true;
        }
        if (secFetchSite != null && !secFetchSite.isBlank()) {
            String site = secFetchSite.trim();
            if (!"same-origin".equalsIgnoreCase(site) && !"none".equalsIgnoreCase(site)) {
                return false;
            }
        }
        if (origin == null) {
            return true;
        }
        return allowedOrigins.contains(origin.trim().toLowerCase());
    }
}
