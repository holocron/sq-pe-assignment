package com.sq.caa.security;

import com.sq.caa.service.UserService;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Resolves a username against the {@code app_users} table.
 *
 * <p>Returns the narrow {@link AppUserPrincipal} type so callers that need the
 * role or full name do not have to downcast.
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserService userService;

    public AppUserDetailsService(UserService userService) {
        this.userService = userService;
    }

    @Override
    public AppUserPrincipal loadUserByUsername(String username) throws UsernameNotFoundException {
        return userService.findByUsername(username)
                .map(AppUserPrincipal::from)
                .orElseThrow(() -> new UsernameNotFoundException("No account named '" + username + "'"));
    }
}
