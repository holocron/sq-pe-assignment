package com.sq.caa.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sq.caa.domain.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Token issuing and verification, without a Spring context.
 *
 * <p>These are the guarantees the whole API rests on: a token this service
 * signed round-trips with every claim intact, and anything else - tampered,
 * expired, foreign issuer, wrong key - is refused.
 */
class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-key-that-is-comfortably-longer-than-32-bytes";
    private static final String ISSUER = "customer-activity-analytics";

    private static JwtService service() {
        return new JwtService(new JwtProperties(SECRET, 480));
    }

    private static AppUserPrincipal principal() {
        return new AppUserPrincipal(UUID.fromString("11111111-2222-4333-8444-555555555555"), "ada",
                "$2a$10$notarealhash", "Ada Lovelace", UserRole.ADMIN, true);
    }

    private static SecretKey key(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("an issued token round-trips with every claim intact")
    void roundTrip() {
        JwtService service = service();
        AppUserPrincipal principal = principal();

        JwtService.IssuedToken issued = service.issue(principal);
        Claims claims = service.parseClaims(issued.token());

        assertEquals("ada", service.username(claims));
        assertEquals(principal.getUserId(), service.userId(claims));
        assertEquals(UserRole.ADMIN, service.role(claims));
        assertEquals("Ada Lovelace", service.fullName(claims));
        assertEquals(ISSUER, claims.getIssuer());
        assertNotNull(claims.getId(), "jti keeps tokens individually identifiable");
        assertEquals(issued.expiresAt().truncatedTo(ChronoUnit.SECONDS),
                service.expiresAt(claims).truncatedTo(ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("expiry is the configured ttl")
    void expiryFollowsTtl() {
        JwtService service = new JwtService(new JwtProperties(SECRET, 30));

        JwtService.IssuedToken issued = service.issue(principal());

        long minutes = ChronoUnit.MINUTES.between(Instant.now(), issued.expiresAt());
        assertTrue(minutes >= 29 && minutes <= 30, "expected roughly 30 minutes, was " + minutes);
    }

    @Test
    @DisplayName("a tampered payload fails signature verification")
    void tamperedTokenRejected() {
        JwtService service = service();
        String token = service.issue(principal()).token();
        String[] parts = token.split("\\.");
        String forged = parts[0] + "." + parts[1] + "." + reverse(parts[2]);

        assertThrows(JwtException.class, () -> service.parseClaims(forged));
    }

    @Test
    @DisplayName("a token signed with another key is refused")
    void foreignKeyRejected() {
        String token = Jwts.builder()
                .issuer(ISSUER)
                .subject("ada")
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(key("a-completely-different-secret-of-at-least-32-bytes"), Jwts.SIG.HS256)
                .compact();

        assertThrows(JwtException.class, () -> service().parseClaims(token));
    }

    @Test
    @DisplayName("an expired token is refused")
    void expiredTokenRejected() {
        Instant past = Instant.now().minus(2, ChronoUnit.HOURS);
        String token = Jwts.builder()
                .issuer(ISSUER)
                .subject("ada")
                .issuedAt(Date.from(past))
                .expiration(Date.from(past.plus(1, ChronoUnit.MINUTES)))
                .signWith(key(SECRET), Jwts.SIG.HS256)
                .compact();

        assertThrows(io.jsonwebtoken.ExpiredJwtException.class, () -> service().parseClaims(token));
    }

    @Test
    @DisplayName("a token minted by someone else's issuer is refused")
    void foreignIssuerRejected() {
        String token = Jwts.builder()
                .issuer("some-other-service")
                .subject("ada")
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(key(SECRET), Jwts.SIG.HS256)
                .compact();

        assertThrows(JwtException.class, () -> service().parseClaims(token));
    }

    @Test
    @DisplayName("garbage input is refused rather than crashing the filter")
    void garbageRejected() {
        JwtService service = service();

        assertThrows(RuntimeException.class, () -> service.parseClaims("not-a-jwt"));
        assertThrows(IllegalArgumentException.class, () -> service.parseClaims(""));
    }

    @Test
    @DisplayName("a secret too short for HS256 fails fast at startup")
    void shortSecretFailsFast() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new JwtService(new JwtProperties("too-short", 480)));
        assertTrue(failure.getMessage().contains("at least 32 bytes"), failure.getMessage());
    }

    @Test
    @DisplayName("a non-positive ttl fails fast at startup")
    void nonPositiveTtlFailsFast() {
        assertThrows(IllegalStateException.class, () -> new JwtService(new JwtProperties(SECRET, 0)));
        assertThrows(IllegalStateException.class, () -> new JwtService(new JwtProperties(SECRET, -5)));
    }

    @Test
    @DisplayName("the built-in development secret is recognised, and still boots the demo")
    void developmentSecretIsRecognisedWithoutBreakingTheDemo() {
        JwtProperties properties = new JwtProperties(JwtProperties.DEVELOPMENT_SECRET, 480);

        assertTrue(properties.usesDevelopmentSecret(),
                "the committed fallback must be detectable so startup can warn about it");

        // Zero configuration must keep working: the warning is loud, not fatal.
        JwtService service = new JwtService(properties);
        assertNotNull(service.parseClaims(service.issue(principal()).token()));
    }

    @Test
    @DisplayName("a secret of your own is not flagged as the development one")
    void configuredSecretIsNotFlagged() {
        assertFalse(new JwtProperties(SECRET, 480).usesDevelopmentSecret());
        assertFalse(new JwtProperties(null, 480).usesDevelopmentSecret());
    }

    @Test
    @DisplayName("application.yml's fallback is exactly the value the startup warning is about")
    void applicationYmlFallbackMatchesTheConstant() throws Exception {
        String config;
        try (var stream = JwtServiceTest.class.getResourceAsStream("/application.yml")) {
            assertNotNull(stream, "application.yml must be on the test classpath");
            config = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(config.contains("${JWT_SECRET:" + JwtProperties.DEVELOPMENT_SECRET + "}"),
                "the configured fallback drifted from JwtProperties.DEVELOPMENT_SECRET, so the "
                        + "startup warning would go silent");
    }

    private static String reverse(String value) {
        return new StringBuilder(value).reverse().toString();
    }
}
