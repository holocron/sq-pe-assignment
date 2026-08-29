package com.sq.caa.web.dto;

import com.sq.caa.domain.AppUser;
import com.sq.caa.domain.UserRole;
import com.sq.caa.security.AppUserPrincipal;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * Wire types for authentication and user administration.
 *
 * <p>Matches BUILD_SPEC section 5:
 * <pre>
 * POST /api/auth/login {username,password} -&gt; {token, expiresAt, user:{username,fullName,role}}
 * GET  /api/auth/me                        -&gt; {username, fullName, role}
 * GET  /api/users                          -&gt; AppUser[]
 * </pre>
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    /** {@code POST /api/auth/login} request body. */
    public record LoginRequest(
            @NotBlank(message = "Username is required") @Size(max = 64) String username,
            @NotBlank(message = "Password is required") @Size(max = 200) String password) {
    }

    /** The signed-in identity, as returned by {@code /api/auth/me}. */
    public record UserSummary(String username, String fullName, UserRole role) {

        public static UserSummary of(AppUserPrincipal principal) {
            return new UserSummary(principal.getUsername(), principal.getFullName(), principal.getRole());
        }
    }

    /** {@code POST /api/auth/login} response body. */
    public record LoginResponse(String token, Instant expiresAt, UserSummary user) {
    }

    /** One row of {@code GET /api/users}. Never carries the password hash. */
    public record AppUserDto(
            UUID userId,
            String username,
            String fullName,
            UserRole role,
            boolean enabled,
            Instant createdAt) {

        public static AppUserDto of(AppUser user) {
            return new AppUserDto(user.getUserId(), user.getUsername(), user.getFullName(), user.getRole(),
                    user.isEnabled(), user.getCreatedAt());
        }
    }
}
