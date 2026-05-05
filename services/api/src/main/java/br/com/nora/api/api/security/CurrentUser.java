package br.com.nora.api.api.security;

import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Acessa o principal autenticado a partir do SecurityContext. */
public final class CurrentUser {

    private CurrentUser() {}

    public static AuthenticatedPrincipal require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null
                || !auth.isAuthenticated()
                || !(auth.getPrincipal() instanceof AuthenticatedPrincipal principal)) {
            throw new AccessDeniedException("authentication required");
        }
        return principal;
    }
}
