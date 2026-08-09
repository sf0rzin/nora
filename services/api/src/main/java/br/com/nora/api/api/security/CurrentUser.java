package br.com.nora.api.api.security;

import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;

/** Accesses the authenticated principal from the SecurityContext. */
public final class CurrentUser {

    private CurrentUser() {}

    /**
     * Requires an authenticated principal. Throws {@link AuthenticationException} (401) — NOT
     * {@link org.springframework.security.access.AccessDeniedException} (403) — because the failure
     * here is absence of credentials (expired cookie, no cookie), not lack of permission. Without
     * that distinction, the frontend cannot tell "I need to refresh the token" apart from "you do
     * not have access to this resource".
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
     * "Not authenticated" sentinel — used because {@code AuthenticationException} is abstract and
     * {@link org.springframework.security.web.authentication.HttpStatusEntryPoint} (configured in
     * {@code SecurityConfig}) answers 401 for any subclass propagated from the filter chain.
     */
    public static final class MissingAuthenticationException extends AuthenticationException {
        public MissingAuthenticationException(String message) {
            super(message);
        }
    }
}
