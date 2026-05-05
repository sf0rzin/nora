package br.com.nora.api.api.controllers;

import br.com.nora.api.api.dto.auth.ConfirmPasswordResetRequest;
import br.com.nora.api.api.dto.auth.LoginRequest;
import br.com.nora.api.api.dto.auth.LoginResponse;
import br.com.nora.api.api.dto.auth.RequestPasswordResetRequest;
import br.com.nora.api.api.dto.auth.RequestPasswordResetResponse;
import br.com.nora.api.api.dto.auth.SignupRequest;
import br.com.nora.api.api.dto.auth.SignupResponse;
import br.com.nora.api.api.dto.auth.VerifyEmailRequest;
import br.com.nora.api.application.identity.AuthService;
import br.com.nora.api.application.identity.AuthService.AuthSettings;
import br.com.nora.api.application.identity.AuthService.ConfirmPasswordResetCommand;
import br.com.nora.api.application.identity.AuthService.LoginCommand;
import br.com.nora.api.application.identity.AuthService.LoginResult;
import br.com.nora.api.application.identity.AuthService.RequestPasswordResetCommand;
import br.com.nora.api.application.identity.AuthService.RequestPasswordResetResult;
import br.com.nora.api.application.identity.AuthService.SignupCommand;
import br.com.nora.api.application.identity.AuthService.SignupResult;
import br.com.nora.api.application.ports.JwtIssuer;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints publicos de autenticacao. Stories US01 (signup), US02 (verificacao), US03 (login),
 * US04 (reset).
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    /** Roles default atribuidas ao primeiro usuario do tenant pessoal (Core). */
    private static final List<String> SELF_SERVICE_ROLES = List.of("ADMIN");

    private final AuthService authService;
    private final JwtIssuer jwtIssuer;
    private final AuthSettings settings;

    public AuthController(AuthService authService, JwtIssuer jwtIssuer, AuthSettings settings) {
        this.authService = authService;
        this.jwtIssuer = jwtIssuer;
        this.settings = settings;
    }

    @PostMapping("/signup")
    public ResponseEntity<SignupResponse> signup(@Valid @RequestBody SignupRequest req) {
        SignupResult result =
                authService.signup(
                        new SignupCommand(req.email(), req.password(), req.displayName()));
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
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        LoginResult result =
                authService.login(new LoginCommand(req.email(), req.password()));
        String token = jwtIssuer.issue(result.user(), SELF_SERVICE_ROLES, settings.jwtTtl());
        LoginResponse body =
                new LoginResponse(
                        token,
                        "Bearer",
                        settings.jwtTtl().toSeconds(),
                        result.user().id(),
                        result.user().tenantId(),
                        result.user().email().value(),
                        result.user().displayName());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/password/reset/request")
    public ResponseEntity<RequestPasswordResetResponse> requestPasswordReset(
            @Valid @RequestBody RequestPasswordResetRequest req) {
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
}
