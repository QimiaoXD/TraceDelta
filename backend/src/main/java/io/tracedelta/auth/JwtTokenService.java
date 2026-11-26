package io.tracedelta.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;

public class JwtTokenService {

    private static final int MIN_SECRET_LENGTH = 32;

    private final SecretKey signingKey;
    private final long ttlMinutes;
    private final Clock clock;

    public JwtTokenService(String secret, long ttlMinutes) {
        this(secret, ttlMinutes, Clock.systemUTC());
    }

    public JwtTokenService(String secret, long ttlMinutes, Clock clock) {
        if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("TRACEDELTA_JWT_SECRET must be at least 32 characters");
        }
        if (ttlMinutes <= 0) {
            throw new IllegalStateException("JWT TTL must be positive");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlMinutes = ttlMinutes;
        this.clock = clock;
    }

    public String issue(String username) {
        Instant now = clock.instant();
        Instant exp = now.plus(ttlMinutes, ChronoUnit.MINUTES);
        return Jwts.builder()
                .subject(username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(signingKey)
                .compact();
    }

    public Optional<String> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.ofNullable(claims.getSubject()).filter(s -> !s.isBlank());
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
