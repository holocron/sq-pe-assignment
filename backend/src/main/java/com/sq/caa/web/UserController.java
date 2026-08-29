package com.sq.caa.web;

import com.sq.caa.security.SecurityRoles;
import com.sq.caa.service.UserService;
import com.sq.caa.web.dto.AuthDtos.AppUserDto;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * User administration. Admin-only, both here and in the filter chain rules of
 * {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/users")
@PreAuthorize(SecurityRoles.IS_ADMIN)
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /** Every login, ordered by username. Password hashes are never included. */
    @GetMapping
    public List<AppUserDto> listUsers() {
        return userService.listUsers();
    }
}
