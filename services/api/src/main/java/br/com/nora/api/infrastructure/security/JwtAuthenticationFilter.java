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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Le credencial de acesso de duas fontes (ordem de prioridade):
 *
 * <ol>
 *   <li>Header {@code Authorization: Bearer <jwt>} — back-compat com clientes antigos e CLI/MCP.
 *   <li>Cookie httpOnly {@value AuthCookies#ACCESS_COOKIE} — fluxo novo do web (Round 2 / 1.3 A).
 * </ol>
 *
 * <p>Aceitar ambos durante a migracao do web evita janela quebrada e nao baixa a barra de
 * seguranca: o cookie e {@code HttpOnly+SameSite}, e o header continua exigindo posse.
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
                // Popula o holder pra RLS aspect ler. Limpamos no finally pra nao vazar entre
                // requests reusando a mesma thread (Tomcat pool).
                TenantContextHolder.set(principal.tenantId());
                // Enriquece o MDC com tenant/user pra correlacionar os logs apos a autenticacao
                // (o requestId ja foi setado pelo RequestIdFilter, antes da cadeia de seguranca).
                MDC.put("tenantId", principal.tenantId().toString());
                MDC.put("userId", principal.userId().toString());
            } catch (Exception ex) {
                // Token invalido => nao popula contexto. Resposta 401 sai do entry point quando
                // a rota exige autenticacao.
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
