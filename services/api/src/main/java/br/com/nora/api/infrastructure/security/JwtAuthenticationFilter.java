package br.com.nora.api.infrastructure.security;

import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Le o header Authorization: Bearer <jwt> e popula o SecurityContext quando valido. */
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
        String header = req.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)) {
            String token = header.substring(PREFIX.length()).trim();
            try {
                AuthenticatedPrincipal principal = jwtIssuer.parse(token);
                List<SimpleGrantedAuthority> authorities =
                        principal.roles().stream()
                                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                                .toList();
                AbstractAuthenticationToken auth =
                        new AbstractAuthenticationToken(authorities) {
                            @Override
                            public Object getCredentials() {
                                return token;
                            }

                            @Override
                            public Object getPrincipal() {
                                return principal;
                            }
                        };
                auth.setAuthenticated(true);
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception ex) {
                // Token invalido => nao popula contexto. Resposta 401 sai do entry point quando
                // a rota exige autenticacao.
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(req, res);
    }
}
