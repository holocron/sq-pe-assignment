package com.sq.caa.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds {@code caa.cors.*}. The value is a comma-separated list in
 * {@code application.yml}; Spring converts it to a {@code List}.
 *
 * @param allowedOrigins browser origins allowed to call the API directly
 */
@ConfigurationProperties(prefix = "caa.cors")
public record CorsProperties(@DefaultValue("http://localhost:5173") List<String> allowedOrigins) {
}
