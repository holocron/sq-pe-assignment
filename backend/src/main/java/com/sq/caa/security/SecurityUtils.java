package com.sq.caa.security;

import com.sq.caa.domain.UserRole;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Read-only access to the caller behind the current request.
 *
 * <p>Handy for audit columns such as {@code analysis_runs.requested_by} and
 * {@code knowledge_documents.uploaded_by} without threading the principal
 * through every service signature.
 */
public final class SecurityUtils {

    /** Stand-in used when work runs outside a request (schedulers, seeding). */
    public static final String SYSTEM_USERNAME = "system";

    private SecurityUtils() {
    }

    public static Optional<AppUserPrincipal> currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        return authentication.getPrincipal() instanceof AppUserPrincipal principal
                ? Optional.of(principal)
                : Optional.empty();
    }

    public static Optional<String> currentUsername() {
        return currentPrincipal().map(AppUserPrincipal::getUsername);
    }

    /** The caller's username, or {@link #SYSTEM_USERNAME} when there is no caller. */
    public static String currentUsernameOrSystem() {
        return currentUsername().orElse(SYSTEM_USERNAME);
    }

    public static boolean isAdmin() {
        return currentPrincipal().map(principal -> principal.getRole() == UserRole.ADMIN).orElse(false);
    }
}
