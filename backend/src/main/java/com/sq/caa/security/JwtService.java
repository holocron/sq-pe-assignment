package com.sq.caa.security;

import com.sq.caa.domain.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies the stateless bearer tokens used by the API.
 *
 * <p>Tokens are HS256-signed JWTs (jjwt 0.13.0). Besides the standard claims the
 * payload carries {@code uid} (the {@code app_users} primary key), {@code role}
 * and {@code name}, so a request can be described without a database lookup.
 */
@Service
public class JwtService {

    /** {@code app_users.user_id}. */
    public static final String CLAIM_USER_ID = "uid";

    /** {@link UserRole} name, e.g. {@code ADMIN}. */
    public static final String CLAIM_ROLE = "role";

    /** Display name. */
    public static final String CLAIM_FULL_NAME = "name";

    private static final String ISSUER = "customer-activity-analytics";

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey signingKey;
    private final Duration ttl;

    public JwtService(JwtProperties properties) {
        byte[] secret = properties.secret() == null
                ? new byte[0]
                : properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < JwtProperties.MIN_SECRET_BYTES) {
            throw new IllegalStateException("caa.security.jwt.secret must be at least "
                    + JwtProperties.MIN_SECRET_BYTES + " bytes for HS256, but was " + secret.length);
        }
        if (properties.ttlMinutes() <= 0) {
            throw new IllegalStateException("caa.security.jwt.ttl-minutes must be positive");
        }
        warnIfDevelopmentSecret(properties);
        this.signingKey = Keys.hmacShaKeyFor(secret);
        this.ttl = properties.ttl();
    }

    /**
     * The zero-configuration demo must keep working, so a missing {@code JWT_SECRET} is not a boot
     * failure - but it must never be silent either. The built-in fallback is committed to the
     * repository, so with it in place anyone holding the source can sign a token for any existing
     * username and the {@link JwtAuthenticationFilter} will load that user's real role from the
     * database. That is a full authentication bypass, and the only thing standing between a demo and
     * a deployment is this line in the log.
     */
    private static void warnIfDevelopmentSecret(JwtProperties properties) {
        if (!properties.usesDevelopmentSecret()) {
            log.info("JWT signing secret loaded from configuration ({} bytes), token ttl {} minutes.",
                    properties.secret().getBytes(StandardCharsets.UTF_8).length, properties.ttlMinutes());
            return;
        }
        log.warn("""

                ********************************************************************
                * SECURITY WARNING - signing JWTs with the built-in DEVELOPMENT secret.
                *
                * caa.security.jwt.secret fell back to the value committed in
                * application.yml, which is public. Anyone who has read this repository
                * can forge a token for any account, including admin, without ever
                * calling /api/auth/login.
                *
                * Fine for the local demo, never for anything else. Set a private key:
                *   export JWT_SECRET="$(openssl rand -base64 48)"
                ********************************************************************
                """);
    }

    /** Signs a token for an authenticated principal. */
    public IssuedToken issue(AppUserPrincipal principal) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(ttl);
        String token = Jwts.builder()
                .issuer(ISSUER)
                .subject(principal.getUsername())
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_USER_ID, principal.getUserId().toString())
                .claim(CLAIM_ROLE, principal.getRole().name())
                .claim(CLAIM_FULL_NAME, principal.getFullName())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    /**
     * Verifies the signature, issuer and expiry.
     *
     * @throws JwtException             when the token is malformed, expired or not ours
     * @throws IllegalArgumentException when the token is null or blank
     */
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(ISSUER)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String username(Claims claims) {
        return claims.getSubject();
    }

    public String fullName(Claims claims) {
        return claims.get(CLAIM_FULL_NAME, String.class);
    }

    /**
     * @return the role encoded in the token, or {@code null} when it is absent or
     *         no longer a known {@link UserRole}
     */
    public UserRole role(Claims claims) {
        String role = claims.get(CLAIM_ROLE, String.class);
        if (role == null) {
            return null;
        }
        try {
            return UserRole.valueOf(role);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * @return the user id encoded in the token, or {@code null} when it is absent
     *         or not a UUID
     */
    public UUID userId(Claims claims) {
        String id = claims.get(CLAIM_USER_ID, String.class);
        if (id == null) {
            return null;
        }
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public Instant expiresAt(Claims claims) {
        Date expiration = claims.getExpiration();
        return expiration == null ? null : expiration.toInstant();
    }

    /**
     * A freshly signed token and the instant it stops being accepted.
     *
     * @param token     the compact JWT
     * @param expiresAt UTC expiry, serialised as ISO-8601 to the client
     */
    public record IssuedToken(String token, Instant expiresAt) {
    }
}
