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
 * Endpoints publicos de autenticacao.
 *
 * <p>Stories: US01 (signup), US02 (verificacao), US03 (login), US04 (reset). Round 2 / Subfase 1.3
 * A adiciona refresh + logout com cookies httpOnly.
 *
 * <p>Modelo de cookies:
 *
 * <ul>
 *   <li>{@code nora_access} — JWT curto (15min). HttpOnly, SameSite=Lax, Path=/.
 *   <li>{@code nora_refresh} — opaque (30 dias). HttpOnly, SameSite=Strict, Path=/auth.
 * </ul>
 *
 * <p>{@code accessToken} continua no JSON do login para manter compat com clientes antigos durante
 * a migracao; novos clientes devem ignorar e confiar nos cookies (ver brief Round 2 1.3 A).
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
     * Cliente web declara-se via header {@code X-NORA-Client: web} (recebe sessão só por cookie).
     */
    private static boolean isWebClient(HttpServletRequest req) {
        return "web".equalsIgnoreCase(req.getHeader("X-NORA-Client"));
    }

    @PostMapping("/signup")
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
                        "Conta criada. Verifique seu e-mail para ativar.",
                        result.emailVerificationDevToken());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(@Valid @RequestBody VerifyEmailRequest req) {
        authService.verifyEmail(req.token());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest req, HttpServletRequest httpReq) {
        // Dois tetos independentes: por origem e por conta alvo. Trocar de IP não zera o segundo.
        if (!rateLimiter.allowLogin(httpReq)
                || !rateLimiter.allowLoginForEmail(httpReq, req.email())) {
            throw AuthException.rateLimited();
        }
        LoginResult result = authService.login(new LoginCommand(req.email(), req.password()));

        // Cliente web (header X-NORA-Client: web) recebe a sessão SÓ via cookies httpOnly — os
        // tokens ficam FORA do body pra um XSS não conseguir lê-los. Cliente nativo (desktop, sem
        // o header) recebe os tokens no body (guarda no keyring; não usa cookie).
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
     * Renova o access token. Le o refresh do cookie {@code nora_refresh} (Path=/auth). Retorna 401
     * REFRESH_TOKEN_INVALID quando ausente/expirado/revogado.
     */
    @PostMapping("/refresh")
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

        // Refresh rotation: cliente recebe um novo refresh cookie (o anterior foi revogado
        // server-side). Sem reescrever o cookie, o cliente continuaria mandando o velho
        // e bateria em reuse detection na proxima chamada.
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
        // Browser (refresh via cookie httpOnly): tokens NÃO voltam no body — um XSS não tem como
        // apresentar o refresh por Bearer, então fica sem nada. Cliente nativo (Bearer): recebe no
        // body (não usa cookie). Em ambos os casos os cookies foram reescritos acima.
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
     * Idempotente: revoga apenas o refresh deste cookie e limpa cookies do cliente. Logout sem
     * token = 204 (no-op). Nao retorna 401 mesmo sem credencial.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest req) {
        String refresh = readCookie(req, AuthCookies.REFRESH_COOKIE);
        authService.logout(refresh);
        HttpHeaders headers = new HttpHeaders();
        AuthCookies.appendSetCookie(headers, cookies.buildClearAccessCookie());
        AuthCookies.appendSetCookie(headers, cookies.buildClearRefreshCookie());
        return ResponseEntity.noContent().headers(headers).build();
    }

    /** Identidade do usuario autenticado (aba Conta das configuracoes). */
    @GetMapping("/me")
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
     * Troca de senha AUTENTICADA (aba Seguranca). Revoga todas as sessoes (OWASP) e emite um par
     * novo pro dispositivo atual — os cookies sao reescritos na resposta, o usuario nao e deslogado
     * aqui.
     */
    @PostMapping("/password/change")
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
     * "Sair de todos os dispositivos": revoga TODOS os refresh tokens do usuario e limpa os cookies
     * deste cliente. O access token atual segue valido ate expirar (15min) — aceitavel; o front
     * redireciona pro login imediatamente.
     */
    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll() {
        AuthenticatedPrincipal principal = CurrentUser.require();
        authService.logoutAllSessions(principal.userId());
        HttpHeaders headers = new HttpHeaders();
        AuthCookies.appendSetCookie(headers, cookies.buildClearAccessCookie());
        AuthCookies.appendSetCookie(headers, cookies.buildClearRefreshCookie());
        return ResponseEntity.noContent().headers(headers).build();
    }

    /**
     * Reenvia o e-mail de verificacao (publico — usado quando o login falha com
     * EMAIL_NOT_VERIFIED). Resposta 202 indistinguivel (anti-enumeracao), com o mesmo rate limit
     * por e-mail do reset (mesmo vetor de abuso: inundar inbox alheio).
     */
    @PostMapping("/verify-email/resend")
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
    public ResponseEntity<RequestPasswordResetResponse> requestPasswordReset(
            @Valid @RequestBody RequestPasswordResetRequest req) {
        // Limita por email (em vez de IP) — spammer que muda IP nao consegue inundar
        // o inbox da vitima. Em silencio se exceder (retorna a mesma 202 indistinguivel)
        // pra nao vazar quais emails existem.
        if (!rateLimiter.allowPasswordReset(req.email())) {
            // Reply 202 indistinguivel pra nao vazar quais emails existem, mas WARN
            // pra que ops veja o sinal — sem isso, ataque ficaria silencioso em prod.
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
    public ResponseEntity<Void> confirmPasswordReset(
            @Valid @RequestBody ConfirmPasswordResetRequest req) {
        authService.confirmPasswordReset(
                new ConfirmPasswordResetCommand(req.token(), req.newPassword()));
        return ResponseEntity.noContent().build();
    }

    /** Le um cookie por nome do request; retorna {@code null} se ausente. */
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
