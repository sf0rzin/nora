package br.com.nora.api.infrastructure.security;

import br.com.nora.api.api.security.AuthCookies;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads the access credential from two sources (priority order):
 *
 * <ol>
 *   <li>Header {@code Authorization: Bearer <jwt>} — back-compat with old clients and CLI/MCP.
 *   <li>httpOnly cookie {@value AuthCookies#ACCESS_COOKIE} — new web flow (Round 2 / 1.3 A).
 * </ol>
 *
 * <p>Accepting both during the web migration avoids a broken window and does not lower the security
 * bar: the cookie is {@code HttpOnly+SameSite}, and the header still requires possession.
 *
 * <p><b>It yields to a request another chain has already authenticated.</b> This filter is a bean,
 * so Spring Boot registers it in the servlet container as well as in the JWT chain that adds it
 * explicitly. On the JWT chain the explicit registration wins and the outer one is skipped, which
 * is why nothing noticed. The MCP endpoint (ADR 0041) runs on its own {@code SecurityFilterChain}
 * and resolves its own credential, so this filter is NOT part of that chain — and the outer
 * registration therefore fires DOWNSTREAM of it, on a request whose bearer value is not a JWT.
 * Parsing fails, and the {@code catch} below used to clear a {@code SecurityContext} this filter
 * never set: the request had already passed {@code anyRequest().authenticated()} and arrived at the
 * handler unauthenticated. A filter must not tear down another filter's authentication, so the
 * guard below is the fix rather than a special case for MCP.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JjwtJwtIssuer jwtIssuer;

    public JwtAuthenticationFilter(JjwtJwtIssuer jwtIssuer) {
        this.jwtIssuer = jwtIssuer;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (alreadyAuthenticated()) {
            chain.doFilter(req, res);
            return;
        }
        String token = extractBearer(req);
        if (token == null) {
            token = extractAccessCookie(req);
        }
        if (token != null && !token.isBlank()) {
            try {
                AuthenticatedPrincipal principal = jwtIssuer.parse(token);
                List<SimpleGrantedAuthority> authorities =
                        principal.roles().stream()
                                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                                .toList();
                final String credential = token;
                AbstractAuthenticationToken auth =
                        new AbstractAuthenticationToken(authorities) {
                            @Override
                            public Object getCredentials() {
                                return credential;
                            }

                            @Override
                            public Object getPrincipal() {
                                return principal;
                            }
                        };
                auth.setAuthenticated(true);
                SecurityContextHolder.getContext().setAuthentication(auth);
                // Populates the holder for the RLS aspect to read. We clear it in the finally so it
                // does not leak between requests reusing the same thread (Tomcat pool).
                TenantContextHolder.set(principal.tenantId());
                // Enriches the MDC with tenant/user to correlate logs after authentication
                // (the requestId was already set by RequestIdFilter, before the security chain).
                MDC.put("tenantId", principal.tenantId().toString());
                MDC.put("userId", principal.userId().toString());
            } catch (Exception ex) {
                // Invalid token => does not populate the context. The 401 response comes from the
                // entry point when the route requires authentication.
                SecurityContextHolder.clearContext();
                TenantContextHolder.clear();
            }
        }
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContextHolder.clear();
            MDC.remove("tenantId");
            MDC.remove("userId");
        }
    }

    /**
     * Whether some earlier filter already resolved a NORA principal for this request. Narrowed to
     * {@link AuthenticatedPrincipal} on purpose: the control plane's shared-token chains
     * authenticate a string, and those paths are not served by this filter anyway.
     */
    private static boolean alreadyAuthenticated() {
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        return existing != null
                && existing.isAuthenticated()
                && existing.getPrincipal() instanceof AuthenticatedPrincipal;
    }

    private static String extractBearer(HttpServletRequest req) {
        String header = req.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)) {
            return header.substring(PREFIX.length()).trim();
        }
        return null;
    }

    private static String extractAccessCookie(HttpServletRequest req) {
        Cookie[] all = req.getCookies();
        if (all == null) {
            return null;
        }
        for (Cookie c : all) {
            if (AuthCookies.ACCESS_COOKIE.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
