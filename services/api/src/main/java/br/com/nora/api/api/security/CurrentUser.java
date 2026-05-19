package br.com.nora.api.api.security;

import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;

/** Acessa o principal autenticado a partir do SecurityContext. */
public final class CurrentUser {

    private CurrentUser() {}

    /**
     * Exige que haja um principal autenticado. Lanca {@link AuthenticationException} (401) — NAO
     * {@link org.springframework.security.access.AccessDeniedException} (403) — porque a falha aqui
     * e ausencia de credenciais (cookie expirado, sem cookie), nao falta de permissao. Sem essa
     * distincao, o front nao consegue diferenciar "preciso renovar token" de "voce nao tem acesso a
     * este recurso".
     */
    public static AuthenticatedPrincipal require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null
                || !auth.isAuthenticated()
                || !(auth.getPrincipal() instanceof AuthenticatedPrincipal principal)) {
            throw new MissingAuthenticationException("authentication required");
        }
        return principal;
    }

    /**
     * Sentinel de "nao autenticado" — usada porque {@code AuthenticationException} e abstract e o
     * {@link org.springframework.security.web.authentication.HttpStatusEntryPoint} (configurado em
     * {@code SecurityConfig}) responde 401 para qualquer subclasse propagada do filter chain.
     */
    public static final class MissingAuthenticationException extends AuthenticationException {
        public MissingAuthenticationException(String message) {
            super(message);
        }
    }
}
