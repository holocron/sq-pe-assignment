package com.sq.caa.security;

import com.sq.caa.domain.AppUser;
import com.sq.caa.domain.UserRole;
import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The authenticated principal. Carries just enough of {@link AppUser} for the
 * API to answer {@code /api/auth/me} and to stamp {@code requested_by} /
 * {@code uploaded_by} columns without another database round trip.
 *
 * <p>The entity itself never leaves the persistence layer.
 */
public final class AppUserPrincipal implements UserDetails {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID userId;
    private final String username;
    private final String passwordHash;
    private final String fullName;
    private final UserRole role;
    private final boolean enabled;

    public AppUserPrincipal(UUID userId, String username, String passwordHash, String fullName, UserRole role,
            boolean enabled) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.username = Objects.requireNonNull(username, "username");
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        this.fullName = Objects.requireNonNull(fullName, "fullName");
        this.role = Objects.requireNonNull(role, "role");
        this.enabled = enabled;
    }

    public static AppUserPrincipal from(AppUser user) {
        return new AppUserPrincipal(user.getUserId(), user.getUsername(), user.getPasswordHash(), user.getFullName(),
                user.getRole(), user.isEnabled());
    }

    public UUID getUserId() {
        return userId;
    }

    public String getFullName() {
        return fullName;
    }

    public UserRole getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AppUserPrincipal principal && userId.equals(principal.userId);
    }

    @Override
    public int hashCode() {
        return userId.hashCode();
    }

    /** Deliberately omits the password hash. */
    @Override
    public String toString() {
        return "AppUserPrincipal[username=" + username + ", role=" + role + "]";
    }
}
