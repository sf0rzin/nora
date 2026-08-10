package br.com.nora.api.api.controllers;

import br.com.nora.api.api.dto.auth.ConfirmPasswordResetRequest;
import br.com.nora.api.api.dto.auth.LoginRequest;
import br.com.nora.api.api.dto.auth.LoginResponse;
import br.com.nora.api.api.dto.auth.MeResponse;
import br.com.nora.api.api.dto.auth.PasswordChangeRequest;
import br.com.nora.api.api.dto.auth.RefreshResponse;
import br.com.nora.api.api.dto.auth.RequestPasswordResetRequest;
import br.com.nora.api.api.dto.auth.RequestPasswordResetResponse;
import br.com.nora.api.api.dto.auth.ResendVerificationRequest;
import br.com.nora.api.api.dto.auth.ResendVerificationResponse;
import br.com.nora.api.api.dto.auth.SignupRequest;
import br.com.nora.api.api.dto.auth.SignupResponse;
import br.com.nora.api.api.dto.auth.VerifyEmailRequest;
import br.com.nora.api.api.security.AuthCookies;
import br.com.nora.api.api.security.AuthorizationNotRequired;
import br.com.nora.api.api.security.CurrentUser;
import br.com.nora.api.application.identity.AuthException;
import br.com.nora.api.application.identity.AuthService;
import br.com.nora.api.application.identity.AuthService.ConfirmPasswordResetCommand;
import br.com.nora.api.application.identity.AuthService.LoginCommand;
import br.com.nora.api.application.identity.AuthService.LoginResult;
import br.com.nora.api.application.identity.AuthService.RefreshResult;
import br.com.nora.api.application.identity.AuthService.RequestPasswordResetCommand;
import br.com.nora.api.application.identity.AuthService.RequestPasswordResetResult;
import br.com.nora.api.application.identity.AuthService.SignupCommand;
import br.com.nora.api.application.identity.AuthService.SignupResult;
import br.com.nora.api.domain.identity.User;
import br.com.nora.api.infrastructure.security.AuthRateLimiter;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public authentication endpoints.
 *
 * <p>Stories: US01 (signup), US02 (verification), US03 (login), US04 (reset). Round 2 / Subphase
 * 1.3 A adds refresh + logout with httpOnly cookies.
 *
 * <p>Cookie model:
 *
 * <ul>
 *   <li>{@code nora_access} — short-lived JWT (15min). HttpOnly, SameSite=Lax, Path=/.
 *   <li>{@code nora_refresh} — opaque (30 days). HttpOnly, SameSite=Strict, Path=/auth.
 * </ul>
 *
 * <p>{@code accessToken} stays in the login JSON to keep compat with old clients during the
 * migration; new clients must ignore it and rely on the cookies (see brief Round 2 1.3 A).
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger LOG = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;
    private final AuthCookies cookies;
    private final AuthRateLimiter rateLimiter;

    public AuthController(
            AuthService authService, AuthCookies cookies, AuthRateLimiter rateLimiter) {
        this.authService = authService;
        this.cookies = cookies;
        this.rateLimiter = rateLimiter;
    }

    /**
     * The web client declares itself via the {@code X-NORA-Client: web} header (gets the session by
     * cookie only).
     */
    private static boolean isWebClient(HttpServletRequest req) {
        return "web".equalsIgnoreCase(req.getHeader("X-NORA-Client"));
    }

    /**
     * Creates the account and fires the verification e-mail.
     *
     * <p>Answers 201 with the same body shape and the same message for every address, including one
     * that already has an account — the endpoint is public and unauthenticated, so it is not a
     * place to confirm who is registered. When the address is already taken nothing is created and
     * its owner is notified instead; see {@code AuthService#signup}. The message is worded to hold
     * either way, which is why it talks about the e-mail rather than about a workspace.
     */
    @PostMapping("/signup")
    @AuthorizationNotRequired(reason = "Public: creating the account precedes any principal.")
    public ResponseEntity<SignupResponse> signup(
            @Valid @RequestBody SignupRequest req, HttpServletRequest httpReq) {
        if (!rateLimiter.allowSignup(httpReq)) {
            throw AuthException.rateLimited();
        }
        SignupResult result =
                authService.signup(
                        new SignupCommand(
                                req.email(),
                                req.password(),
                                req.displayName(),
                                req.companyName(),
                                req.role()));
        SignupResponse body =
                new SignupResponse(
                        result.userId(),
                        result.tenantId(),
                        "Verifique seu e-mail para ativar sua conta.",
                        result.emailVerificationDevToken());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping("/verify-email")
    @AuthorizationNotRequired(reason = "Public: the verification token is the credential.")
    public ResponseEntity<Void> verifyEmail(@Valid @RequestBody VerifyEmailRequest req) {
        authService.verifyEmail(req.token());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/login")
    @AuthorizationNotRequired(reason = "Public: this is where credentials become a JWT.")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest req, HttpServletRequest httpReq) {
        // Two independent caps: by origin and by target account. Changing IP does not reset the
        // second one.
        if (!rateLimiter.allowLogin(httpReq)
                || !rateLimiter.allowLoginForEmail(httpReq, req.email())) {
            throw AuthException.rateLimited();
        }
        LoginResult result = authService.login(new LoginCommand(req.email(), req.password()));

        // Web client (X-NORA-Client: web header) gets the session ONLY via httpOnly cookies — the
        // tokens stay OUT of the body so an XSS cannot read them. Native client (desktop, without
        // the header) gets the tokens in the body (stores them in the keyring; does not use
        // cookies).
        boolean nativeClient = !isWebClient(httpReq);
        LoginResponse body =
                new LoginResponse(
                        nativeClient ? result.accessToken() : null,
                        nativeClient ? result.refreshTokenPlain() : null,
                        "Bearer",
                        result.accessExpiresInSeconds(),
                        result.user().id(),
                        result.user().tenantId(),
                        result.user().email().value(),
                        result.user().displayName());

        HttpHeaders headers = new HttpHeaders();
        AuthCookies.appendSetCookie(
                headers,
                cookies.buildAccessCookie(
                        result.accessToken(), Duration.ofSeconds(result.accessExpiresInSeconds())));
        AuthCookies.appendSetCookie(
                headers,
                cookies.buildRefreshCookie(
                        result.refreshTokenPlain(),
                        Duration.ofSeconds(result.refreshExpiresInSeconds())));
        return ResponseEntity.ok().headers(headers).body(body);
    }

    /**
     * Renews the access token. Reads the refresh from the {@code nora_refresh} cookie (Path=/auth).
     * Returns 401 REFRESH_TOKEN_INVALID when missing/expired/revoked.
     */
    @PostMapping("/refresh")
    @AuthorizationNotRequired(reason = "Public: the refresh token is the credential.")
    public ResponseEntity<RefreshResponse> refresh(HttpServletRequest req) {
        String refresh = readCookie(req, AuthCookies.REFRESH_COOKIE);
        boolean fromCookie = refresh != null && !refresh.isBlank();
        if (!fromCookie) {
            String bearer = req.getHeader("Authorization");
            if (bearer != null && bearer.startsWith("Bearer ")) {
                refresh = bearer.substring(7);
            }
        }
        RefreshResult result = authService.refresh(refresh);

        // Refresh rotation: the client gets a new refresh cookie (the previous one was revoked
        // server-side). Without rewriting the cookie, the client would keep sending the old one
        // and would hit reuse detection on the next call.
        HttpHeaders headers = new HttpHeaders();
        AuthCookies.appendSetCookie(
                headers,
                cookies.buildAccessCookie(
                        result.accessToken(), Duration.ofSeconds(result.accessExpiresInSeconds())));
        AuthCookies.appendSetCookie(
                headers,
                cookies.buildRefreshCookie(
                        result.refreshTokenPlain(),
                        Duration.ofSeconds(result.refreshExpiresInSeconds())));
        // Browser (refresh via httpOnly cookie): tokens do NOT come back in the body — an XSS has
        // no way to present the refresh via Bearer, so it gets nothing. Native client (Bearer):
        // gets them in the body (does not use cookies). In both cases the cookies were rewritten
        // above.
        return ResponseEntity.ok()
                .headers(headers)
                .body(
                        new RefreshResponse(
                                fromCookie ? null : result.accessToken(),
                                fromCookie ? null : result.refreshTokenPlain(),
                                "Bearer",
                                result.accessExpiresInSeconds()));
    }

    /**
     * Idempotent: revokes only the refresh from this cookie and clears the client's cookies. Logout
     * without a token = 204 (no-op). Does not return 401 even without a credential.
     */
    @PostMapping("/logout")
    @AuthorizationNotRequired(reason = "Public: idempotent; no token means a no-op.")
    public ResponseEntity<Void> logout(HttpServletRequest req) {
        String refresh = readCookie(req, AuthCookies.REFRESH_COOKIE);
        authService.logout(refresh);
        HttpHeaders headers = new HttpHeaders();
        AuthCookies.appendSetCookie(headers, cookies.buildClearAccessCookie());
        AuthCookies.appendSetCookie(headers, cookies.buildClearRefreshCookie());
        return ResponseEntity.noContent().headers(headers).build();
    }

    /** Identity of the authenticated user (Account tab in settings). */
    @GetMapping("/me")
    @AuthorizationNotRequired(reason = "Self: reads only the caller's own user row.")
    public MeResponse me() {
        AuthenticatedPrincipal principal = CurrentUser.require();
        User user = authService.me(principal.userId());
        return new MeResponse(
                user.id(),
                user.tenantId(),
                user.email().value(),
                user.displayName(),
                user.isEmailVerified(),
                user.createdAt());
    }

    /**
     * AUTHENTICATED password change (Security tab). Revokes all sessions (OWASP) and issues a new
     * pair for the current device — the cookies are rewritten in the response, the user is not
     * logged out here.
     */
    @PostMapping("/password/change")
    @AuthorizationNotRequired(reason = "Self: changes only the caller's own password.")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody PasswordChangeRequest req) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        LoginResult result =
                authService.changePassword(
                        principal.userId(), req.currentPassword(), req.newPassword());
        HttpHeaders headers = new HttpHeaders();
        AuthCookies.appendSetCookie(
                headers,
                cookies.buildAccessCookie(
                        result.accessToken(), Duration.ofSeconds(result.accessExpiresInSeconds())));
        AuthCookies.appendSetCookie(
                headers,
                cookies.buildRefreshCookie(
                        result.refreshTokenPlain(),
                        Duration.ofSeconds(result.refreshExpiresInSeconds())));
        return ResponseEntity.noContent().headers(headers).build();
    }

    /**
     * "Log out of all devices": revokes ALL of the user's refresh tokens and clears this client's
     * cookies. The current access token stays valid until it expires (15min) — acceptable; the
     * front end redirects to the login immediately.
     */
    @PostMapping("/logout-all")
    @AuthorizationNotRequired(reason = "Self: revokes only the caller's own sessions.")
    public ResponseEntity<Void> logoutAll() {
        AuthenticatedPrincipal principal = CurrentUser.require();
        authService.logoutAllSessions(principal.userId());
        HttpHeaders headers = new HttpHeaders();
        AuthCookies.appendSetCookie(headers, cookies.buildClearAccessCookie());
        AuthCookies.appendSetCookie(headers, cookies.buildClearRefreshCookie());
        return ResponseEntity.noContent().headers(headers).build();
    }

    /**
     * Resends the verification e-mail (public — used when the login fails with EMAIL_NOT_VERIFIED).
     * Indistinguishable 202 response (anti-enumeration), with the same per-email rate limit as the
     * reset (same abuse vector: flooding someone else's inbox).
     */
    @PostMapping("/verify-email/resend")
    @AuthorizationNotRequired(reason = "Public: for users who cannot log in; rate-limited.")
    public ResponseEntity<ResendVerificationResponse> resendVerification(
            @Valid @RequestBody ResendVerificationRequest req) {
        String message = "Se houver uma conta nao verificada para este e-mail, reenviamos o link.";
        if (!rateLimiter.allowPasswordReset(req.email())) {
            LOG.warn(
                    "Verification resend rate-limited for email-hash={}",
                    Integer.toHexString(req.email().toLowerCase(java.util.Locale.ROOT).hashCode()));
            return ResponseEntity.accepted().body(new ResendVerificationResponse(message, null));
        }
        RequestPasswordResetResult result = authService.resendVerificationEmail(req.email());
        return ResponseEntity.accepted()
                .body(new ResendVerificationResponse(message, result.devToken()));
    }

    @PostMapping("/password/reset/request")
    @AuthorizationNotRequired(reason = "Public: for users who cannot log in; rate-limited.")
    public ResponseEntity<RequestPasswordResetResponse> requestPasswordReset(
            @Valid @RequestBody RequestPasswordResetRequest req) {
        // Limits by email (instead of IP) — a spammer who changes IP cannot flood
        // the victim's inbox. Silent when exceeded (returns the same indistinguishable 202)
        // so it does not leak which emails exist.
        if (!rateLimiter.allowPasswordReset(req.email())) {
            // Indistinguishable 202 reply so it does not leak which emails exist, but WARN
            // so ops sees the signal — without it, an attack would be silent in prod.
            LOG.warn(
                    "Password reset rate-limited for email-hash={}",
                    Integer.toHexString(req.email().toLowerCase(java.util.Locale.ROOT).hashCode()));
            return ResponseEntity.accepted()
                    .body(
                            new RequestPasswordResetResponse(
                                    "Se houver uma conta para este e-mail, enviaremos"
                                            + " instrucoes.",
                                    null));
        }
        RequestPasswordResetResult result =
                authService.requestPasswordReset(new RequestPasswordResetCommand(req.email()));
        return ResponseEntity.accepted()
                .body(
                        new RequestPasswordResetResponse(
                                "Se houver uma conta para este e-mail, enviaremos instrucoes.",
                                result.devToken()));
    }

    @PostMapping("/password/reset/confirm")
    @AuthorizationNotRequired(reason = "Public: the reset token is the credential.")
    public ResponseEntity<Void> confirmPasswordReset(
            @Valid @RequestBody ConfirmPasswordResetRequest req) {
        authService.confirmPasswordReset(
                new ConfirmPasswordResetCommand(req.token(), req.newPassword()));
        return ResponseEntity.noContent().build();
    }

    /** Reads a cookie by name from the request; returns {@code null} if absent. */
    private static String readCookie(HttpServletRequest req, String name) {
        Cookie[] all = req.getCookies();
        if (all == null) {
            return null;
        }
        for (Cookie c : all) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
