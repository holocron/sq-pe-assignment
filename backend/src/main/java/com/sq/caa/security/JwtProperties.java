package com.sq.caa.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds {@code caa.security.jwt.*}.
 *
 * @param secret     HMAC signing secret; must be at least 32 bytes for HS256
 * @param ttlMinutes how long an issued token stays valid
 */
@ConfigurationProperties(prefix = "caa.security.jwt")
public record JwtProperties(String secret, @DefaultValue("480") long ttlMinutes) {

    /** Minimum key length accepted by {@code HS256}. */
    public static final int MIN_SECRET_BYTES = 32;

    /**
     * The fallback in {@code application.yml}, kept so the demo starts with no configuration at all.
     *
     * <p>It is committed to the repository, so anyone who has read the source can mint a token for
     * any username. {@link JwtService} therefore warns loudly at startup whenever it is in use; set
     * the {@code JWT_SECRET} environment variable to silence the warning and own a real key.
     */
    public static final String DEVELOPMENT_SECRET =
            "change-me-in-production-this-is-a-demo-secret-key-of-sufficient-length-256bits";

    /** {@code true} when the built-in, publicly known development secret is in use. */
    public boolean usesDevelopmentSecret() {
        return DEVELOPMENT_SECRET.equals(secret);
    }

    public Duration ttl() {
        return Duration.ofMinutes(ttlMinutes);
    }
}
