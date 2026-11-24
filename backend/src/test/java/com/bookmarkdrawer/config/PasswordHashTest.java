package com.bookmarkdrawer.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordHashTest {

    private static final int FAST = PasswordHash.MIN_ITERATIONS;

    @Test
    void createdHashVerifiesOnlyTheSamePassword() {
        String encoded = PasswordHash.create("correct horse".toCharArray(), FAST);
        PasswordHash hash = PasswordHash.parse(encoded);
        assertThat(hash.verify("correct horse".toCharArray())).isTrue();
        assertThat(hash.verify("correct horsE".toCharArray())).isFalse();
        assertThat(hash.verify("".toCharArray())).isFalse();
    }

    @Test
    void unicodePasswordWorks() {
        PasswordHash hash = PasswordHash.parse(PasswordHash.create("비밀🔑번호".toCharArray(), FAST));
        assertThat(hash.verify("비밀🔑번호".toCharArray())).isTrue();
        assertThat(hash.verify("비밀번호".toCharArray())).isFalse();
    }

    @Test
    void saltIsRandomPerHashAndPlaintextIsNotStored() {
        String a = PasswordHash.create("same".toCharArray(), FAST);
        String b = PasswordHash.create("same".toCharArray(), FAST);
        assertThat(a).isNotEqualTo(b);
        assertThat(a).startsWith("pbkdf2-sha256$" + FAST + "$").doesNotContain("same");
    }

    @Test
    void malformedOrOutOfRangeHashesAreRejectedWithoutEchoingInput() {
        String valid = PasswordHash.create("pw".toCharArray(), FAST);
        String[] parts = valid.split("\\$");
        String[] bad = {
                null, "", "plaintext-password", "bcrypt$10$abc$def",
                "pbkdf2-sha256$" + (PasswordHash.MIN_ITERATIONS - 1) + "$" + parts[2] + "$" + parts[3],
                "pbkdf2-sha256$" + (PasswordHash.MAX_ITERATIONS + 1) + "$" + parts[2] + "$" + parts[3],
                "pbkdf2-sha256$abc$" + parts[2] + "$" + parts[3],
                "pbkdf2-sha256$" + FAST + "$!!!$" + parts[3],
                "pbkdf2-sha256$" + FAST + "$" + parts[2] + "$AAAA",
                "pbkdf2-sha256$" + FAST + "$" + parts[2],
        };
        for (String value : bad) {
            assertThatThrownBy(() -> PasswordHash.parse(value))
                    .isInstanceOf(IllegalArgumentException.class)
                    .satisfies(e -> {
                        if (value != null && value.length() > 3) {
                            assertThat(e.getMessage()).doesNotContain(value);
                        }
                    });
        }
    }

    @Test
    void createRejectsIterationCountsOutsideTheAllowedRange() {
        assertThatThrownBy(() -> PasswordHash.create("pw".toCharArray(), 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PasswordHash.create("pw".toCharArray(), PasswordHash.MAX_ITERATIONS + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
