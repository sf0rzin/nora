package br.com.nora.api.infrastructure.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates requests to /internal/platform/** and /admin/platform/** by shared token
 * (X-Internal-Token), compared in constant time (ADR 0023). No JWT, no Easy Auth in Spring — the
 * edge (Easy Auth + IP allowlist) lives in nora-admin. One filter per chain, with the expected
 * token and the scope (service|admin) injected.
 */
public class InternalTokenAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Token";

    private final String expectedToken;
    private final String scope;

    public InternalTokenAuthFilter(String expectedToken, String scope) {
        this.expectedToken = expectedToken;
        this.scope = scope;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (isValid(req.getHeader(HEADER))) {
            String role = "ROLE_PLATFORM_" + scope.toUpperCase(Locale.ROOT);
            var auth =
                    new UsernamePasswordAuthenticationToken(
                            "platform-" + scope, null, List.of(new SimpleGrantedAuthority(role)));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(req, res);
    }

    private boolean isValid(String provided) {
        if (expectedToken == null || expectedToken.isBlank() || provided == null) {
            return false;
        }
        // Compares SHA-256 digests (always 32 bytes) in constant time: removes the length
        // side-channel of MessageDigest.isEqual over raw bytes of differing lengths.
        return MessageDigest.isEqual(sha256(expectedToken), sha256(provided));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
