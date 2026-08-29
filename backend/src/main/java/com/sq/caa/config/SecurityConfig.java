package com.sq.caa.config;

import static com.sq.caa.security.SecurityRoles.ADMIN;
import static com.sq.caa.security.SecurityRoles.OPERATOR;

import com.sq.caa.security.AppUserDetailsService;
import com.sq.caa.security.JwtAccessDeniedHandler;
import com.sq.caa.security.JwtAuthenticationEntryPoint;
import com.sq.caa.security.JwtAuthenticationFilter;
import com.sq.caa.security.JwtProperties;
import com.sq.caa.security.JwtService;
import com.sq.caa.security.LoginThrottle;
import com.sq.caa.security.LoginThrottleProperties;
import com.sq.caa.security.ThrottledAuthenticationManager;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Stateless JWT security.
 *
 * <p>No sessions, no CSRF token, no form login: every call carries a bearer token
 * that {@link JwtAuthenticationFilter} turns into an authentication. Coarse role
 * rules live here so they are auditable in one place; controllers may narrow them
 * further with {@code @PreAuthorize} (method security is enabled below).
 *
 * <p>Role model (BUILD_SPEC section 5): ADMIN owns the rule set, the knowledge
 * base and user administration; OPERATOR can read rules, search the knowledge
 * base and run analyses.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({JwtProperties.class, LoginThrottleProperties.class})
public class SecurityConfig {

    private final JwtService jwtService;
    private final AppUserDetailsService userDetailsService;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;
    private final JwtAccessDeniedHandler accessDeniedHandler;
    private final CorsConfigurationSource corsConfigurationSource;

    public SecurityConfig(JwtService jwtService, AppUserDetailsService userDetailsService,
            JwtAuthenticationEntryPoint authenticationEntryPoint, JwtAccessDeniedHandler accessDeniedHandler,
            CorsConfigurationSource corsConfigurationSource) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.corsConfigurationSource = corsConfigurationSource;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** The login brute-force brake; see {@link LoginThrottle} for the limit it enforces. */
    @Bean
    public LoginThrottle loginThrottle(LoginThrottleProperties properties) {
        return new LoginThrottle(properties, Clock.systemUTC());
    }

    /**
     * Username/password authentication, used by {@code POST /api/auth/login}.
     *
     * <p>Wrapped in {@link ThrottledAuthenticationManager} so the endpoint cannot be ground for
     * guesses: repeated failures for one username from one address are refused before the password
     * is checked at all. See {@link LoginThrottle} for the limit and its scope.
     */
    @Bean
    public AuthenticationManager authenticationManager(PasswordEncoder passwordEncoder,
            LoginThrottle loginThrottle) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ThrottledAuthenticationManager(new ProviderManager(provider), loginThrottle);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        // Pre-flight and the public surface.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/auth/login").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/error").permitAll()

                        // Risk rules: admins author them, operators may read them.
                        .requestMatchers(HttpMethod.GET, "/api/rules/field-catalog").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/rules/test").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/rules").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.PUT, "/api/rules/**").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.DELETE, "/api/rules/**").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/rules", "/api/rules/**")
                        .hasAnyRole(ADMIN, OPERATOR)

                        // Knowledge base: admins curate it, everyone may search it.
                        .requestMatchers(HttpMethod.POST, "/api/knowledge/documents").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.DELETE, "/api/knowledge/documents/**").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/knowledge/search").hasAnyRole(ADMIN, OPERATOR)
                        .requestMatchers(HttpMethod.GET, "/api/knowledge/documents", "/api/knowledge/documents/**")
                        .hasAnyRole(ADMIN, OPERATOR)

                        // User administration.
                        .requestMatchers("/api/users", "/api/users/**").hasRole(ADMIN)

                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, userDetailsService),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
