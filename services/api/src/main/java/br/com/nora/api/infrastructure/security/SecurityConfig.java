package br.com.nora.api.infrastructure.security;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Base security configuration.
 *
 * <p>Default policy: everything authenticated, except public endpoints. JWT filter installed by
 * stories US01-US04: for every request with an Authorization Bearer header, populates the
 * SecurityContext.
 */
@Configuration
public class SecurityConfig {

    @Value("${nora.cors.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

    @Value("${nora.cors.allowed-methods:GET,POST,PUT,PATCH,DELETE,OPTIONS}")
    private String allowedMethods;

    @Value("${nora.cors.allowed-headers:*}")
    private String allowedHeaders;

    @Value("${nora.cors.allow-credentials:true}")
    private boolean allowCredentials;

    @Value("${nora.cors.max-age-seconds:3600}")
    private long maxAgeSeconds;

    private static final String[] PUBLIC_ENDPOINTS = {
        "/healthz",
        "/actuator/health",
        "/actuator/info",
        "/auth/signup",
        "/auth/verify-email",
        "/auth/login",
        // Round 2 / Subphase 1.3 A: refresh has no valid JWT when it arrives (that is the point);
        // logout is idempotent — no token = no-op instead of 401.
        "/auth/refresh",
        "/auth/logout",
        "/auth/password/reset/request",
        "/auth/password/reset/confirm",
        // GOAL Phase 3: verification resend is for those who CANNOT log in (EMAIL_NOT_VERIFIED)
        // — public by design, with per-email rate limit + anti-enumeration response.
        "/auth/verify-email/resend",
        // US06: invitation acceptance uses the token as the credential — public endpoint by design.
        "/iam/invites/*/accept",
        // NORA Flows Phase 2: the OAuth callback arrives by provider redirect (no guaranteed JWT);
        // the signed state (HMAC, tenant/user embedded, exp 10min) is the credential.
        "/integrations/*/oauth/callback",
        // Public JWKS (RFC 7517): external validators fetch the RSA public key here.
        // Active only when algorithm=RS256 (conditional bean); on HS256 it naturally returns 404.
        "/.well-known/jwks.json",
        "/v3/api-docs/**",
        "/swagger-ui/**",
        "/swagger-ui.html"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
        http.cors(c -> c.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(PUBLIC_ENDPOINTS)
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .exceptionHandling(
                        e ->
                                e.authenticationEntryPoint(
                                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = Arrays.asList(allowedOrigins.split(","));
        config.setAllowedOrigins(origins.stream().map(String::trim).toList());
        config.setAllowedMethods(Arrays.asList(allowedMethods.split(",")));
        config.setAllowedHeaders(List.of(allowedHeaders));
        config.setAllowCredentials(allowCredentials);
        config.setMaxAge(maxAgeSeconds);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // OWASP 2023+ recommends strength >= 12 for BCrypt. SpringSec's default is 10.
        // Strength 12 ~ 250ms per hash on modern hw; acceptable for occasional login
        // but real defense against offline password cracking in case of a DB dump.
        return new BCryptPasswordEncoder(12);
    }
}
