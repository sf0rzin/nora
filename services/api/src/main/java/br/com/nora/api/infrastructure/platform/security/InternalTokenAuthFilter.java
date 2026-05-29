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
 * Autentica requests de /internal/platform/** e /admin/platform/** por token compartilhado
 * (X-Internal-Token), comparado em tempo constante (ADR 0023). Sem JWT, sem Easy Auth no Spring — a
 * borda (Easy Auth + IP allowlist) vive no nora-admin. Um filtro por chain, com o token esperado e
 * o escopo (service|admin) injetados.
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
        // Compara digests SHA-256 (sempre 32 bytes) em tempo constante: remove o side-channel de
        // tamanho do MessageDigest.isEqual sobre bytes crus de comprimentos diferentes.
        return MessageDigest.isEqual(sha256(expectedToken), sha256(provided));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 indisponível", ex);
        }
    }
}
