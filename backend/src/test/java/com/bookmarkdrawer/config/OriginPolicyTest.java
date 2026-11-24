package com.bookmarkdrawer.config;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OriginPolicyTest {

    private static final Set<String> ALLOWED = Set.of("https://blackbox.example", "http://localhost:8080");

    private static boolean allows(String method, String origin, String site) {
        return OriginPolicy.allows(method, origin, site, ALLOWED);
    }

    @Test
    void safeMethodsAreNeverChecked() {
        for (String method : new String[]{"GET", "HEAD", "OPTIONS", "TRACE"}) {
            assertThat(allows(method, "https://evil.example", "cross-site")).isTrue();
        }
    }

    @Test
    void unsafeMethodWithAllowedOriginPasses() {
        for (String method : new String[]{"POST", "PUT", "PATCH", "DELETE"}) {
            assertThat(allows(method, "https://blackbox.example", "same-origin")).isTrue();
            assertThat(allows(method, "http://localhost:8080", null)).isTrue();
        }
    }

    @Test
    void unsafeMethodWithOtherOriginNullOriginOrLookalikeIsRejected() {
        assertThat(allows("POST", "https://evil.example", null)).isFalse();
        assertThat(allows("PATCH", "null", null)).isFalse();
        assertThat(allows("DELETE", "https://blackbox.example.evil.example", null)).isFalse();
        assertThat(allows("POST", "http://blackbox.example", null)).isFalse();
        assertThat(allows("POST", "https://blackbox.example:8443", null)).isFalse();
        assertThat(allows("POST", "multiple", null)).isFalse();
    }

    @Test
    void secFetchSiteCanOnlyVetoNeverGrant() {
        assertThat(allows("POST", "https://blackbox.example", "cross-site")).isFalse();
        assertThat(allows("POST", "https://blackbox.example", "same-site")).isFalse();
        assertThat(allows("POST", null, "cross-site")).isFalse();
        // same-origin 이라는 값만으로는 목록에 없는 Origin을 허용하지 않는다.
        assertThat(allows("POST", "https://evil.example", "same-origin")).isFalse();
    }

    @Test
    void missingOriginIsAllowedByPolicyWhenSecFetchSiteIsAbsentOrBenign() {
        assertThat(allows("POST", null, null)).isTrue();
        assertThat(allows("POST", null, "same-origin")).isTrue();
        assertThat(allows("POST", null, "none")).isTrue();
        assertThat(allows("POST", null, "")).isTrue();
    }

    @Test
    void emptyAllowListRejectsEveryOrigin() {
        assertThat(OriginPolicy.allows("POST", "https://blackbox.example", "same-origin", Set.of())).isFalse();
        assertThat(OriginPolicy.allows("POST", null, null, Set.of())).isTrue();
    }
}
