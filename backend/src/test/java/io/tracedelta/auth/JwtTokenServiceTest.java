package io.tracedelta.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class JwtTokenServiceTest {

    private static final String SECRET = "this-is-a-secure-jwt-secret-with-more-than-32-chars";

    @Test
    void issueThenVerifyReturnsUsername() {
        JwtTokenService service = new JwtTokenService(SECRET, 60);
        String token = service.issue("admin");
        assertThat(service.verify(token)).contains("admin");
    }

    @Test
    void tamperedTokenReturnsEmpty() {
        JwtTokenService service = new JwtTokenService(SECRET, 60);
        String token = service.issue("admin");
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertThat(service.verify(tampered)).isEmpty();
    }

    @Test
    void expiredTokenReturnsEmpty() {
        Instant base = Instant.parse("2026-11-26T10:00:00Z");
        JwtTokenService issuer = new JwtTokenService(SECRET, 30, Clock.fixed(base, ZoneOffset.UTC));
        String token = issuer.issue("admin");

        JwtTokenService verifier = new JwtTokenService(
                SECRET, 30, Clock.fixed(base.plus(31, ChronoUnit.MINUTES), ZoneOffset.UTC));
        assertThat(verifier.verify(token)).isEmpty();
    }

    @Test
    void shortSecretIsRejected() {
        assertThatThrownBy(() -> new JwtTokenService("too-short", 60))
                .isInstanceOf(IllegalStateException.class);
    }
}
