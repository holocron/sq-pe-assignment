package com.sq.caa.web;

import com.sq.caa.security.AppUserPrincipal;
import com.sq.caa.security.JwtService;
import com.sq.caa.security.JwtService.IssuedToken;
import com.sq.caa.web.dto.AuthDtos.LoginRequest;
import com.sq.caa.web.dto.AuthDtos.LoginResponse;
import com.sq.caa.web.dto.AuthDtos.UserSummary;
import jakarta.validation.Valid;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sign-in and "who am I". See BUILD_SPEC section 5. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthController(AuthenticationManager authenticationManager, JwtService jwtService) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    /**
     * Exchanges credentials for a bearer token.
     *
     * <p>Bad credentials and disabled accounts surface as 401 {@code problem+json}
     * through {@code GlobalExceptionHandler}.
     */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        IssuedToken issued = jwtService.issue(principal);
        return new LoginResponse(issued.token(), issued.expiresAt(), UserSummary.of(principal));
    }

    /** Revalidates the caller's token and echoes the identity behind it. */
    @GetMapping("/me")
    public UserSummary me(@AuthenticationPrincipal AppUserPrincipal principal) {
        if (principal == null) {
            throw new InsufficientAuthenticationException("No authenticated principal on the request");
        }
        return UserSummary.of(principal);
    }
}
