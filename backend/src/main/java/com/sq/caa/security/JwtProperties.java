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

    public Duration ttl() {
        return Duration.ofMinutes(ttlMinutes);
    }
}
