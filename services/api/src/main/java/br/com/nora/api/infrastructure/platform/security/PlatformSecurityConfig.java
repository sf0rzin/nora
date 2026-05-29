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
 * Chains de segurança do control plane (ADR 0023), separadas por path e com precedência sobre a
 * chain JWT por-tenant existente (que não tem @Order ⇒ LOWEST_PRECEDENCE ⇒ avaliada por último como
 * catch-all). Cada chain confia apenas no token interno (X-Internal-Token):
 *
 * <ul>
 *   <li>@Order(1) /internal/platform/** — service token (worker/BFF)
 *   <li>@Order(2) /admin/platform/** — admin token (nora-admin); auditoria via X-Operator-Email
 *       lido no controller
 * </ul>
 *
 * Sem CORS (server-to-server), stateless, 401 quando o token falta/não bate.
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
                        "Control plane habilitado mas NORA_PLATFORM_INTERNAL_TOKEN está vazio —"
                                + " /internal/platform/** vai recusar tudo (401).");
            }
            if (props.getAdminToken() == null || props.getAdminToken().isBlank()) {
                LOG.warn(
                        "NORA_PLATFORM_ADMIN_TOKEN vazio — /admin/platform/** cai no internal-token"
                                + " (recomendado configurar tokens distintos).");
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
