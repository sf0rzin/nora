package br.com.nora.api.application.identity;

import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.ports.EmailSender;
import br.com.nora.api.application.ports.JwtIssuer;
import br.com.nora.api.application.ports.OneTimeTokenRepository;
import br.com.nora.api.application.ports.PasswordHasher;
import br.com.nora.api.application.ports.RefreshTokenRepository;
import br.com.nora.api.application.ports.SecureTokenGenerator;
import br.com.nora.api.application.ports.SecureTokenGenerator.GeneratedToken;
import br.com.nora.api.application.ports.TenantRepository;
import br.com.nora.api.application.ports.UserRepository;
import br.com.nora.api.domain.identity.Email;
import br.com.nora.api.domain.identity.OneTimeToken;
import br.com.nora.api.domain.identity.OneTimeToken.Purpose;
import br.com.nora.api.domain.identity.PasswordPolicy;
import br.com.nora.api.domain.identity.RefreshToken;
import br.com.nora.api.domain.identity.User;
import br.com.nora.api.domain.tenant.Tenant;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Servico de aplicacao para o fluxo completo de identidade do MVP: signup, verificacao de e-mail,
 * login, reset de senha. Stories: US01-US04 do backlog.
 *
 * <p>Round 2 / Subfase 1.3 A adiciona refresh token stateful + emissao de par
 * access(JWT)+refresh(opaque) no login. Ver tambem {@link RefreshToken}.
 *
 * <p>Decisao de design (US01): cada signup Core cria um tenant pessoal proprio. O usuario fica como
 * primeiro membro desse tenant. Convite a tenant existente (US06) e Enterprise e tratado em outra
 * story.
 */
public class AuthService {

    /** Default roles do MVP. Movido para ca pra ser reusavel pelo refresh. */
    private static final List<String> DEFAULT_ROLES = List.of("ADMIN");

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final OneTimeTokenRepository tokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordHasher passwordHasher;
    private final SecureTokenGenerator tokenGenerator;
    private final JwtIssuer jwtIssuer;
    private final EmailSender emailSender;
    private final Clock clock;
    private final AuthSettings settings;

    public AuthService(
            TenantRepository tenantRepository,
            UserRepository userRepository,
            OneTimeTokenRepository tokenRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordHasher passwordHasher,
            SecureTokenGenerator tokenGenerator,
            JwtIssuer jwtIssuer,
            EmailSender emailSender,
            Clock clock,
            AuthSettings settings) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordHasher = passwordHasher;
        this.tokenGenerator = tokenGenerator;
        this.jwtIssuer = jwtIssuer;
        this.emailSender = emailSender;
        this.clock = clock;
        this.settings = settings;
    }

    // ----- US01: signup -----

    public record SignupCommand(String email, String password, String displayName) {}

    public record SignupResult(UUID userId, UUID tenantId, String emailVerificationDevToken) {}

    /**
     * US01 + US02: cria tenant pessoal, usuario nao verificado e dispara e-mail de verificacao.
     *
     * <p>Para conveniencia em dev/CI, retorna o token cru no {@code emailVerificationDevToken}
     * apenas quando {@link AuthSettings#exposeDevTokens()} e {@code true}. Em producao esse campo
     * vem null.
     */
    public SignupResult signup(SignupCommand cmd) {
        Email email = Email.of(cmd.email());
        PasswordPolicy.validate(cmd.password());
        String displayName =
                (cmd.displayName() == null || cmd.displayName().isBlank())
                        ? email.value().split("@")[0]
                        : cmd.displayName().trim();

        userRepository
                .findByEmail(email)
                .ifPresent(
                        u -> {
                            throw new AuthException.EmailAlreadyTaken();
                        });

        Instant now = clock.now();
        Tenant tenant = createPersonalTenant(displayName, email, now);
        Tenant saved = tenantRepository.save(tenant);

        User user =
                User.newUnverified(
                        UUID.randomUUID(),
                        saved.id(),
                        email,
                        passwordHasher.hash(cmd.password()),
                        displayName,
                        now);
        User savedUser = userRepository.save(user);

        // Primeiro usuario do tenant pessoal recem-criado vira o Root automaticamente (ADR 0007).
        userRepository.markAsRoot(savedUser.id(), savedUser.tenantId());

        GeneratedToken token = tokenGenerator.generate();
        tokenRepository.save(
                new OneTimeToken(
                        UUID.randomUUID(),
                        savedUser.id(),
                        savedUser.tenantId(),
                        token.hash(),
                        now.plus(settings.emailVerificationTtl()),
                        null,
                        now,
                        Purpose.EMAIL_VERIFICATION));

        String link = settings.publicBaseUrl() + "/auth/verify-email?token=" + token.rawToken();
        emailSender.sendEmailVerification(email.value(), displayName, link);

        return new SignupResult(
                savedUser.id(),
                savedUser.tenantId(),
                settings.exposeDevTokens() ? token.rawToken() : null);
    }

    private Tenant createPersonalTenant(String displayName, Email email, Instant now) {
        String base = Tenant.slugify(displayName);
        String candidate = base;
        int suffix = 1;
        while (tenantRepository.existsBySlug(candidate)) {
            suffix++;
            candidate = base + "-" + suffix;
            if (suffix > 50) {
                // Garantia anti-loop. A chance pratica de chegar aqui e ~zero.
                candidate = base + "-" + UUID.randomUUID().toString().substring(0, 8);
                break;
            }
        }
        return new Tenant(
                UUID.randomUUID(),
                displayName + " (pessoal)",
                candidate,
                Tenant.Status.ACTIVE,
                Tenant.Plan.FREE,
                now,
                now);
    }

    // ----- US02: verificacao de e-mail -----

    public void verifyEmail(String rawToken) {
        OneTimeToken token = consumeToken(rawToken, Purpose.EMAIL_VERIFICATION);
        User user =
                userRepository
                        .findById(token.userId())
                        .orElseThrow(AuthException.TokenInvalid::new);
        Instant now = clock.now();
        user.markEmailVerified(now);
        userRepository.save(user);
    }

    // ----- US03: login -----

    public record LoginCommand(String email, String password) {}

    /**
     * Resultado do login: par access(JWT)+refresh(opaque). O refresh cru ({@code
     * refreshTokenPlain}) so existe nesta resposta e no cookie httpOnly que o controller seta. O
     * hash do refresh fica em {@code refresh_tokens}.
     */
    public record LoginResult(
            User user,
            String accessToken,
            long accessExpiresInSeconds,
            String refreshTokenPlain,
            long refreshExpiresInSeconds) {}

    /**
     * US03: valida credenciais e emite par access + refresh.
     *
     * <p>O access token e um JWT HS256 (curta vida, 15min default) com claims minimas. O refresh e
     * um opaque token de alta entropia (256 bits), persistido como hash SHA-256.
     */
    public LoginResult login(LoginCommand cmd) {
        Email email;
        try {
            email = Email.of(cmd.email());
        } catch (IllegalArgumentException ex) {
            throw new AuthException.InvalidCredentials();
        }
        User user =
                userRepository
                        .findByEmail(email)
                        .orElseThrow(AuthException.InvalidCredentials::new);

        if (!passwordHasher.matches(cmd.password(), user.passwordHash())) {
            throw new AuthException.InvalidCredentials();
        }
        switch (user.status()) {
            case DISABLED -> throw new AuthException.UserDisabled();
            case ACTIVE, INVITED -> {
                /* segue */
            }
        }
        if (!user.isEmailVerified()) {
            throw new AuthException.EmailNotVerified();
        }
        return issueTokens(user);
    }

    /**
     * Emite par access+refresh para um usuario ja autenticado (chamado por login interno e tambem
     * pelo aceite de convite, que ja validou credenciais propriamente).
     */
    public LoginResult issueTokens(User user) {
        Instant now = clock.now();
        String access = jwtIssuer.issue(user, DEFAULT_ROLES, settings.jwtTtl());
        GeneratedToken refresh = tokenGenerator.generate();
        refreshTokenRepository.save(
                RefreshToken.issue(
                        UUID.randomUUID(),
                        user.id(),
                        user.tenantId(),
                        refresh.hash(),
                        now,
                        now.plus(settings.refreshTokenTtl())));
        return new LoginResult(
                user,
                access,
                settings.jwtTtl().toSeconds(),
                refresh.rawToken(),
                settings.refreshTokenTtl().toSeconds());
    }

    // ----- Round 2 / 1.3 A: refresh + logout -----

    /**
     * Resultado de refresh: novo access (JWT curto) + nova janela de expiracao. O refresh em si nao
     * e rotacionado nesta fatia (vide brief — rotacao fica pra futuro), portanto continua valido no
     * cookie httpOnly do cliente.
     */
    public record RefreshResult(User user, String accessToken, long accessExpiresInSeconds) {}

    /**
     * Valida o refresh token cru contra o hash em DB, atualiza {@code last_used_at} e emite um
     * access JWT novo. Sem rotacao: o refresh em si continua valido ate {@code expires_at}.
     */
    public RefreshResult refresh(String refreshTokenPlain) {
        if (refreshTokenPlain == null || refreshTokenPlain.isBlank()) {
            throw new AuthException.RefreshTokenInvalid();
        }
        String hash = tokenGenerator.hash(refreshTokenPlain);
        RefreshToken token =
                refreshTokenRepository
                        .findByTokenHash(hash)
                        .orElseThrow(AuthException.RefreshTokenInvalid::new);
        Instant now = clock.now();
        if (!token.isActive(now)) {
            throw new AuthException.RefreshTokenInvalid();
        }
        User user =
                userRepository
                        .findById(token.userId())
                        .orElseThrow(AuthException.RefreshTokenInvalid::new);
        if (user.status() == br.com.nora.api.domain.identity.UserStatus.DISABLED) {
            // Usuario desativado depois do login: refresh nao deve renovar.
            throw new AuthException.RefreshTokenInvalid();
        }
        token.markUsed(now);
        refreshTokenRepository.save(token);
        String access = jwtIssuer.issue(user, DEFAULT_ROLES, settings.jwtTtl());
        return new RefreshResult(user, access, settings.jwtTtl().toSeconds());
    }

    /**
     * Logout pontual: revoga apenas o refresh token usado. Idempotente — token ausente ou ja
     * revogado e tratado como sucesso (no-op).
     */
    public void logout(String refreshTokenPlain) {
        if (refreshTokenPlain == null || refreshTokenPlain.isBlank()) {
            return;
        }
        String hash = tokenGenerator.hash(refreshTokenPlain);
        refreshTokenRepository
                .findByTokenHash(hash)
                .ifPresent(
                        t -> {
                            if (!t.isRevoked()) {
                                t.revoke(clock.now());
                                refreshTokenRepository.save(t);
                            }
                        });
    }

    /** Logout total: revoga todos os refresh tokens ativos do usuario (ex: "sair de tudo"). */
    public int logoutAllSessions(UUID userId) {
        if (userId == null) {
            return 0;
        }
        return refreshTokenRepository.revokeAllByUserId(userId, clock.now());
    }

    // ----- US04: reset de senha -----

    public record RequestPasswordResetCommand(String email) {}

    public record RequestPasswordResetResult(String devToken) {}

    /**
     * US04 step 1: gera token de reset e dispara e-mail. Por seguranca, e-mails nao cadastrados
     * sofrem o mesmo fluxo silenciosamente (mesma latencia, sem revelar existencia da conta).
     */
    public RequestPasswordResetResult requestPasswordReset(RequestPasswordResetCommand cmd) {
        Email email;
        try {
            email = Email.of(cmd.email());
        } catch (IllegalArgumentException ex) {
            return new RequestPasswordResetResult(null);
        }
        var maybeUser = userRepository.findByEmail(email);
        if (maybeUser.isEmpty()) {
            // Resposta indistinguivel para nao vazar quais e-mails existem no sistema.
            return new RequestPasswordResetResult(null);
        }
        User user = maybeUser.get();
        Instant now = clock.now();

        // Invalida tokens anteriores: so o ultimo gerado vale.
        tokenRepository.invalidateActiveForUser(user.id(), Purpose.PASSWORD_RESET, now);

        GeneratedToken token = tokenGenerator.generate();
        tokenRepository.save(
                new OneTimeToken(
                        UUID.randomUUID(),
                        user.id(),
                        user.tenantId(),
                        token.hash(),
                        now.plus(settings.passwordResetTtl()),
                        null,
                        now,
                        Purpose.PASSWORD_RESET));

        String link =
                settings.publicBaseUrl() + "/auth/password/reset/confirm?token=" + token.rawToken();
        emailSender.sendPasswordReset(email.value(), user.displayName(), link);

        return new RequestPasswordResetResult(settings.exposeDevTokens() ? token.rawToken() : null);
    }

    public record ConfirmPasswordResetCommand(String token, String newPassword) {}

    public void confirmPasswordReset(ConfirmPasswordResetCommand cmd) {
        PasswordPolicy.validate(cmd.newPassword());
        OneTimeToken token = consumeToken(cmd.token(), Purpose.PASSWORD_RESET);
        User user =
                userRepository
                        .findById(token.userId())
                        .orElseThrow(AuthException.TokenInvalid::new);
        Instant now = clock.now();
        user.changePasswordHash(passwordHasher.hash(cmd.newPassword()), now);
        userRepository.save(user);
    }

    // ----- helpers -----

    private OneTimeToken consumeToken(String rawToken, Purpose purpose) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new AuthException.TokenInvalid();
        }
        String hash = tokenGenerator.hash(rawToken);
        OneTimeToken token =
                tokenRepository
                        .findByTokenHashAndPurpose(hash, purpose)
                        .orElseThrow(AuthException.TokenInvalid::new);
        Instant now = clock.now();
        if (!token.isUsable(now)) {
            throw new AuthException.TokenInvalid();
        }
        token.consume(now);
        tokenRepository.save(token);
        return token;
    }

    /** Configuracoes injetaveis. Mantidas como record para nao acoplar a Spring no servico. */
    public record AuthSettings(
            String publicBaseUrl,
            Duration emailVerificationTtl,
            Duration passwordResetTtl,
            Duration jwtTtl,
            Duration refreshTokenTtl,
            boolean exposeDevTokens) {}
}
