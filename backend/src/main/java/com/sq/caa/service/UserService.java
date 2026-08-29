package com.sq.caa.service;

import com.sq.caa.domain.AppUser;
import com.sq.caa.repository.AppUserRepository;
import com.sq.caa.web.dto.AuthDtos.AppUserDto;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read access to {@code app_users}. The single entry point to the user table:
 * authentication, {@code /api/auth/me} and {@code /api/users} all go through here.
 */
@Service
@Transactional(readOnly = true)
public class UserService {

    private final AppUserRepository userRepository;

    public UserService(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Looks a login up by name. Matching ignores case so operators are not locked
     * out by a capitalised username, while the stored value keeps its casing.
     */
    public Optional<AppUser> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return userRepository.findByUsernameIgnoreCase(username.trim());
    }

    /** Every login, ordered by username. Admin-only at the controller. */
    public List<AppUserDto> listUsers() {
        return userRepository.findAllByOrderByUsernameAsc().stream().map(AppUserDto::of).toList();
    }
}
