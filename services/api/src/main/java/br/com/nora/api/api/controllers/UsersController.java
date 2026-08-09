package br.com.nora.api.api.controllers;

import br.com.nora.api.api.dto.auth.MeResponse;
import br.com.nora.api.api.dto.user.DeleteAccountRequest;
import br.com.nora.api.api.dto.user.UpdateMeRequest;
import br.com.nora.api.api.security.AuthCookies;
import br.com.nora.api.api.security.CurrentUser;
import br.com.nora.api.application.identity.AuthService;
import br.com.nora.api.domain.identity.User;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operations on the user's OWN account (/users/me) — profile and account deletion (LGPD). Scope:
 * always the principal from the JWT; there is no access to other users here (member management is
 * IAM Enterprise).
 */
@RestController
@RequestMapping("/users")
public class UsersController {

    private final AuthService authService;
    private final AuthCookies cookies;

    public UsersController(AuthService authService, AuthCookies cookies) {
        this.authService = authService;
        this.cookies = cookies;
    }

    @PatchMapping("/me")
    public MeResponse updateMe(@Valid @RequestBody UpdateMeRequest req) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        User user = authService.updateDisplayName(principal.userId(), req.displayName());
        return new MeResponse(
                user.id(),
                user.tenantId(),
                user.email().value(),
                user.displayName(),
                user.isEmailVerified(),
                user.createdAt());
    }

    /**
     * LGPD — PERMANENT deletion of the account and of ALL personal workspace data (danger zone).
     * Requires the current password in the body; a shared tenant returns 409. Clears the cookies in
     * the response.
     */
    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteMe(@Valid @RequestBody DeleteAccountRequest req) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        authService.deleteAccount(principal.userId(), principal.tenantId(), req.password());
        HttpHeaders headers = new HttpHeaders();
        AuthCookies.appendSetCookie(headers, cookies.buildClearAccessCookie());
        AuthCookies.appendSetCookie(headers, cookies.buildClearRefreshCookie());
        return ResponseEntity.noContent().headers(headers).build();
    }
}
