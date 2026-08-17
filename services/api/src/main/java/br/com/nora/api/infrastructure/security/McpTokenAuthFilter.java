package br.com.nora.api.infrastructure.security;

import br.com.nora.api.application.mcp.McpTokenService;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Exchanges an MCP bearer token for the same authenticated principal the JWT filter produces (ADR
 * 0041 §3), on the security chain that serves the MCP endpoint and only that endpoint.
 *
 * <p><b>The scope is the point.</b> This filter is instantiated by {@code McpSecurityConfig} inside
 * a chain whose {@code securityMatcher} is the single MCP path, so an MCP token presented to {@code
 * /meetings} or {@code /tasks} authenticates nothing at all. Without that boundary, ADR 0041 §4's
 * read-only first cut would be a property of which tools happen to exist rather than of the
 * credential, and a leaked MCP token would be able to upload a meeting over REST.
 *
 * <p><b>It does not compete with the JWT filter.</b> Different chain, different path, no ordering
 * to get wrong. Only one of the two ever runs for a given request.
 *
 * <p><b>Origin is validated before anything else.</b> The Streamable HTTP transport requires a
 * server to refuse a request whose {@code Origin} header is present and not one it trusts —
 * defence against a page in a browser driving the server through DNS rebinding. Non-browser
 * clients, which is what MCP clients usually are, send no {@code Origin} and are unaffected; the
 * trusted set is the application's own CORS origins, so there is one list rather than two that can
 * drift.
 *
 * <p>An absent, malformed or dead credential leaves the context empty and the request then meets
 * {@code anyRequest().authenticated()}, which answers 401. The refusal is deliberately uniform:
 * this filter never says whether a token was unknown, revoked or expired.
 */
public class McpTokenAuthFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER = "Bearer ";
    private static final String ORIGIN = "Origin";

    private final McpTokenService tokens;
    private final Set<String> allowedOrigins;

    public McpTokenAuthFilter(McpTokenService tokens, Set<String> allowedOrigins) {
        this.tokens = tokens;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String origin = req.getHeader(ORIGIN);
        if (origin != null && !allowedOrigins.contains(origin)) {
            res.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        Optional<McpTokenService.ResolvedToken> resolved = resolve(req);
        if (resolved.isEmpty()) {
            chain.doFilter(req, res);
            return;
        }

        McpTokenService.ResolvedToken token = resolved.get();
        AuthenticatedPrincipal principal =
                new AuthenticatedPrincipal(
                        token.userId(), token.tenantId(), token.email(), List.of());
        AbstractAuthenticationToken auth =
                new AbstractAuthenticationToken(List.of()) {
                    @Override
                    public Object getCredentials() {
                        // The raw token is deliberately not carried into the context: nothing
                        // downstream needs it, and anything that logged the credentials of an
                        // Authentication would print a live credential.
                        return null;
                    }

                    @Override
                    public Object getPrincipal() {
                        return principal;
                    }
                };
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
        // Same three side effects the JWT filter has: the RLS aspect reads the holder, and the
        // MDC correlates the request's logs. Cleared in the finally so nothing leaks into the
        // next request served by the same pooled thread.
        TenantContextHolder.set(token.tenantId());
        MDC.put("tenantId", token.tenantId().toString());
        MDC.put("userId", token.userId().toString());
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContextHolder.clear();
            MDC.remove("tenantId");
            MDC.remove("userId");
        }
    }

    /**
     * The bearer value, resolved into a principal. The roles list is empty on purpose: the JWT
     * carries a vestigial {@code ADMIN} claim that nothing in this codebase reads, since
     * authorization is IAM. Inventing one here would mean that whatever starts reading roles one
     * day silently grants them to a credential that was never a login.
     */
    private Optional<McpTokenService.ResolvedToken> resolve(HttpServletRequest req) {
        String header = req.getHeader(AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            return Optional.empty();
        }
        try {
            return tokens.authenticate(header.substring(BEARER.length()).trim());
        } catch (RuntimeException ex) {
            // A failure while resolving the credential is a refusal, never an authenticated
            // request. Swallowed so the chain answers 401 rather than 500.
            return Optional.empty();
        }
    }
}
