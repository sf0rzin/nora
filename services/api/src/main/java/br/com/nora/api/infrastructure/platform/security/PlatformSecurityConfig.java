package br.com.nora.api.infrastructure.platform.security;

import br.com.nora.api.infrastructure.platform.PlatformProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Control plane security chains (ADR 0023), split by path and taking precedence over the existing
 * per-tenant JWT chain (which has no @Order ⇒ LOWEST_PRECEDENCE ⇒ evaluated last as a catch-all).
 * Each chain trusts only the internal token (X-Internal-Token):
 *
 * <ul>
 *   <li>@Order(1) /internal/platform/** — service token (worker/BFF)
 *   <li>@Order(2) /admin/platform/** — admin token (nora-admin); auditing via X-Operator-Email read
 *       in the controller
 * </ul>
 *
 * No CORS (server-to-server), stateless, 401 when the token is missing/does not match.
 */
@Configuration
public class PlatformSecurityConfig {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformSecurityConfig.class);

    private final PlatformProperties props;

    public PlatformSecurityConfig(PlatformProperties props) {
        this.props = props;
        if (props.isEnabled()) {
            if (props.getInternalToken() == null || props.getInternalToken().isBlank()) {
                LOG.warn(
                        "Control plane enabled but NORA_PLATFORM_INTERNAL_TOKEN is empty —"
                                + " /internal/platform/** will refuse everything (401).");
            }
            if (props.getAdminToken() == null || props.getAdminToken().isBlank()) {
                // Deliberately not phrased as a recommendation. With the internal token set and
                // this one empty, the WORKER's service token authenticates /admin/platform/**:
                // the operator console and the worker stop being distinct principals. Nothing
                // fails and no request is refused — this line is the only trace, and only when
                // the platform is enabled, because the whole block is inside that guard. The
                // /admin/platform/** chain is registered either way.
                //
                // The compose closes it at the door — NORA_PLATFORM_ADMIN_TOKEN is `:?` there,
                // so the stack refuses to start without it. This warning is what remains for a
                // deployment that does not go through that compose.
                LOG.warn(
                        "NORA_PLATFORM_ADMIN_TOKEN is empty — /admin/platform/** will accept the"
                                + " INTERNAL token, so the worker's service credential"
                                + " authenticates the operator console. Set a distinct admin"
                                + " token.");
            }
        }
    }

    @Bean
    @Order(1)
    public SecurityFilterChain platformInternalChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/internal/platform/**")
                .csrf(c -> c.disable())
                .cors(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .exceptionHandling(
                        e ->
                                e.authenticationEntryPoint(
                                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(
                        new InternalTokenAuthFilter(props.getInternalToken(), "service"),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain platformAdminChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/admin/platform/**")
                .csrf(c -> c.disable())
                .cors(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .exceptionHandling(
                        e ->
                                e.authenticationEntryPoint(
                                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(
                        new InternalTokenAuthFilter(props.adminTokenResolved(), "admin"),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
