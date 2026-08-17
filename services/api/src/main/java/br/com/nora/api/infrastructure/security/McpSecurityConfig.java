package br.com.nora.api.infrastructure.security;

import br.com.nora.api.application.mcp.McpTokenService;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
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
 * Security chain for the MCP endpoint (ADR 0041), taking precedence over the per-tenant JWT chain,
 * which has no {@code @Order} and is therefore evaluated last as a catch-all. Follows the shape the
 * control plane already uses in {@code PlatformSecurityConfig}: one chain per credential, matched
 * by path, with its filter constructed here rather than injected as a component — so no bean of
 * this filter exists for the servlet container to register a second time, and no relative ordering
 * against another filter has to be guessed.
 *
 * <p><b>The matcher is exact, and that is load-bearing.</b> It matches the MCP endpoint itself and
 * nothing beneath it, so {@code /mcp/tokens} — where an already-logged-in user mints and revokes
 * these credentials — falls through to the JWT chain. A token cannot mint another token, and an MCP
 * client cannot manage the credentials of the user it acts for.
 *
 * <p>{@code @Order(3)} after the two control-plane chains: they match disjoint paths, so the number
 * only has to sit above the unordered catch-all.
 */
@Configuration
public class McpSecurityConfig {

    /** The single MCP endpoint of the Streamable HTTP transport. */
    public static final String MCP_ENDPOINT = "/mcp";

    private final String allowedOrigins;

    public McpSecurityConfig(
            @Value("${nora.cors.allowed-origins:http://localhost:3000}") String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    @Order(3)
    public SecurityFilterChain mcpChain(HttpSecurity http, McpTokenService tokens)
            throws Exception {
        http.securityMatcher(MCP_ENDPOINT)
                .csrf(c -> c.disable())
                // No CORS: this endpoint is for non-browser clients, and the Origin header is
                // handled by the filter, which refuses an untrusted one outright instead of
                // omitting a response header and letting the browser decide.
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
                        new McpTokenAuthFilter(tokens, trustedOrigins()),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private Set<String> trustedOrigins() {
        Set<String> origins = new LinkedHashSet<>();
        for (String origin : Arrays.asList(allowedOrigins.split(","))) {
            String trimmed = origin.trim();
            if (!trimmed.isEmpty()) {
                origins.add(trimmed);
            }
        }
        return origins;
    }
}
