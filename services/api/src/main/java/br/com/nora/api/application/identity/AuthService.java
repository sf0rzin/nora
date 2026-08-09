package br.com.nora.api.application.identity;

import br.com.nora.api.application.ports.AuditPort;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

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

    private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);

    /** Default roles do MVP. Movido para ca pra ser reusavel pelo refresh. */
    private static final List<String> DEFAULT_ROLES = List.of("ADMIN");

    /**
     * Janela de tolerancia para reuso BENIGNO de refresh token recem-rotacionado.
     *
     * <p>Corridas legitimas acontecem em producao: o timer proativo do web e o interceptor 401
     * disparam dois POST /auth/refresh simultaneos com o mesmo cookie, e cada aba do navegador tem
     * timer proprio — a segunda aba reapresenta o cookie antigo logo apos a primeira rotacionar.
     * Sem a janela, a reuse detection revogava a family inteira e deslogava o usuario em todas as
     * abas (bug reportado em producao).
     *
     * <p>Dentro da janela, e somente quando o token foi rotacionado de fato ({@code replacedById}
     * preenchido — token revogado por logout NAO entra aqui), tratamos como corrida e emitimos um
     * par novo na mesma family. Fora dela, a protecao real contra roubo de token permanece: family
     * inteira revogada.
     */
    private static final Duration REFRESH_REUSE_LEEWAY = Duration.ofSeconds(60);

    /**
     * Teto de saltos ao seguir a cadeia de rotacao em {@link #chainStillAlive}. Dentro da janela de
     * 60s nao ha uso legitimo que rode mais que isto; o teto existe para o caso de {@code
     * replacedById} formar um ciclo, que nada no schema impede.
     */
    private static final int REUSE_CHAIN_MAX_HOPS = 8;

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final OneTimeTokenRepository tokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordHasher passwordHasher;
    private final SecureTokenGenerator tokenGenerator;
    private final JwtIssuer jwtIssuer;
    private final EmailSender emailSender;
    private final AuditPort audit;
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
            AuditPort audit,
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
        this.audit = audit;
        this.clock = clock;
        this.settings = settings;
    }

    // ----- US01: signup -----

    public record SignupCommand(
            String email, String password, String displayName, String companyName, String role) {
        /** Atalho para signup pessoal: sem workspace nomeado nem role declarada. */
        public SignupCommand(String email, String password, String displayName) {
            this(email, password, displayName, null, null);
        }
    }

    public record SignupResult(UUID userId, UUID tenantId, String emailVerificationDevToken) {}

    /**
     * US01 + US02: cria tenant pessoal, usuario nao verificado e dispara e-mail de verificacao.
     *
     * <p>Para conveniencia em dev/CI, retorna o token cru no {@code emailVerificationDevToken}
     * apenas quando {@link AuthSettings#exposeDevTokens()} e {@code true}. Em producao esse campo
     * vem null.
     */
    @Transactional
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
        Tenant tenant = createTenant(displayName, cmd.companyName(), now);
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

        Map<String, Object> auditPayload = new HashMap<>();
        auditPayload.put("email", email.value());
        auditPayload.put("flow", "signup-personal");
        // Telemetria de onboarding (#156): intenção de uso declarada no signup. Persistida no
        // audit log (sem PII) para segmentar workspaces por individual/team/company no funil PLG.
        auditPayload.put(
                "role",
                (cmd.role() == null || cmd.role().isBlank())
                        ? "unspecified"
                        : cmd.role().trim().toLowerCase());
        auditPayload.put(
                "namedWorkspace", cmd.companyName() != null && !cmd.companyName().isBlank());
        audit.record(
                savedUser.tenantId(),
                savedUser.id(),
                "auth.user.signup",
                "USER",
                savedUser.id(),
                auditPayload);

        return new SignupResult(
                savedUser.id(),
                savedUser.tenantId(),
                settings.exposeDevTokens() ? token.rawToken() : null);
    }

    private Tenant createTenant(String displayName, String companyName, Instant now) {
        boolean hasCompany = companyName != null && !companyName.isBlank();
        String workspaceName = hasCompany ? companyName.trim() : displayName + " (pessoal)";
        String base = Tenant.slugify(hasCompany ? companyName.trim() : displayName);
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
                workspaceName,
                candidate,
                Tenant.Status.ACTIVE,
                Tenant.Plan.FREE,
                now,
                now);
    }

    // ----- US02: verificacao de e-mail -----

    @Transactional
    public void verifyEmail(String rawToken) {
        OneTimeToken token = consumeToken(rawToken, Purpose.EMAIL_VERIFICATION);
        User user =
                userRepository
                        .findById(token.userId())
                        .orElseThrow(AuthException.TokenInvalid::new);
        Instant now = clock.now();
        user.markEmailVerified(now);
        userRepository.save(user);
        audit.record(
                user.tenantId(),
                user.id(),
                "auth.email.verified",
                "USER",
                user.id(),
                Map.of("email", user.email().value()));
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
    @Transactional
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
        LoginResult result = issueTokens(user);
        audit.record(
                user.tenantId(),
                user.id(),
                "auth.user.login",
                "USER",
                user.id(),
                Map.of("email", user.email().value()));
        return result;
    }

    /**
     * Emite par access+refresh para um usuario ja autenticado (chamado por login interno e tambem
     * pelo aceite de convite, que ja validou credenciais propriamente).
     */
    @Transactional
    public LoginResult issueTokens(User user) {
        Instant now = clock.now();
        String access = jwtIssuer.issue(user, DEFAULT_ROLES, settings.jwtTtl());
        GeneratedToken refresh = tokenGenerator.generate();
        refreshTokenRepository.save(
                RefreshToken.issueRoot(
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

    // ----- Round 2 / 1.3 A: refresh + logout (com rotation + reuse detection) -----

    /**
     * Resultado de refresh: novo par access+refresh. O refresh anterior foi revogado nesta mesma
     * transacao; cliente deve atualizar o cookie httpOnly com {@code refreshTokenPlain} retornado.
     */
    public record RefreshResult(
            User user,
            String accessToken,
            long accessExpiresInSeconds,
            String refreshTokenPlain,
            long refreshExpiresInSeconds) {}

    /**
     * Refresh com rotacao + reuse detection (audit follow-up #3 / OAuth2 best practice).
     *
     * <p>Caminho feliz: valida o token apresentado, revoga ele, emite um filho na mesma family,
     * retorna o novo refresh raw pro cliente.
     *
     * <p>Corrida benigna: token revogado por rotacao ({@code replacedById} preenchido) e usado ha
     * menos de {@link #REFRESH_REUSE_LEEWAY} — timer proativo + interceptor 401 na mesma aba, ou
     * outra aba reapresentando o cookie antigo. Emitimos um par novo na MESMA family, sem revogar
     * nada.
     *
     * <p>Reuse detection: se o token apresentado ja esta revogado (e ainda dentro do TTL) fora da
     * janela acima, assumimos cadeia comprometida (atacante exfiltrou o cookie e usou um token
     * velho). Revogamos a family inteira e logamos WARN.
     */
    @Transactional
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

        if (token.isRevoked() && !token.isExpired(now)) {
            if (!isBenignReuse(token, now)) {
                // Reuse de token revogado e ainda nao expirado, fora da janela de corrida:
                // cadeia comprometida. Revoga toda a family pra deslogar atacante + vitima
                // simultaneamente.
                int revoked = refreshTokenRepository.revokeAllByFamilyId(token.familyId(), now);
                LOG.warn(
                        "Refresh token reuse detected family={} userId={} tokensRevoked={}",
                        token.familyId(),
                        token.userId(),
                        revoked);
                throw new AuthException.RefreshTokenInvalid();
            }
            // Corrida benigna (multi-aba / timer + interceptor): emite par novo na mesma family
            // sem revogar nada. O pai fica intocado de proposito — lastUsedAt continua ancorado
            // no PRIMEIRO uso, entao a janela nao se estende a cada reuso (um atacante nao
            // consegue manter o token velho vivo reapresentando-o a cada <60s).
            User user = loadActiveUser(token);
            LOG.info(
                    "Refresh reuse benigno dentro da janela family={} userId={}",
                    token.familyId(),
                    token.userId());
            return issueChildPair(user, token.familyId(), now);
        }
        if (!token.isActive(now)) {
            throw new AuthException.RefreshTokenInvalid();
        }
        User user = loadActiveUser(token);

        // Rotacao: emite filho na mesma family, marca pai como replaced_by_id, revoga pai.
        GeneratedToken next = tokenGenerator.generate();
        UUID childId = UUID.randomUUID();
        RefreshToken child =
                RefreshToken.issueChild(
                        childId,
                        user.id(),
                        user.tenantId(),
                        next.hash(),
                        now,
                        now.plus(settings.refreshTokenTtl()),
                        token.familyId());
        refreshTokenRepository.save(child);

        token.markUsed(now);
        token.markReplacedBy(childId);
        token.revoke(now);
        refreshTokenRepository.save(token);

        String access = jwtIssuer.issue(user, DEFAULT_ROLES, settings.jwtTtl());
        return new RefreshResult(
                user,
                access,
                settings.jwtTtl().toSeconds(),
                next.rawToken(),
                settings.refreshTokenTtl().toSeconds());
    }

    /**
     * Reuso e benigno quando o token foi rotacionado de fato ({@code replacedById} preenchido), o
     * primeiro uso ocorreu ha no maximo {@link #REFRESH_REUSE_LEEWAY} <b>e</b> o filho que o
     * substituiu continua valendo.
     *
     * <p>A ultima condicao e o que faz logout-all, troca e reset de senha valerem de verdade. Essas
     * operacoes revogam apenas os tokens ainda NAO revogados ({@code revoked_at is null}), entao o
     * pai — ja revogado pela propria rotacao — sobrevive intacto, com {@code replacedById}
     * preenchido e o {@code lastUsedAt} antigo. Sem checar o filho, reapresentar esse pai dentro da
     * janela de 60s emitia um par novo e ativo: a sessao que o usuario acabara de encerrar voltava
     * a existir. Se o filho esta revogado, a cadeia inteira foi encerrada e nao ha corrida benigna
     * possivel — e reuse.
     */
    private boolean isBenignReuse(RefreshToken token, Instant now) {
        if (token.replacedById() == null
                || token.lastUsedAt() == null
                || Duration.between(token.lastUsedAt(), now).compareTo(REFRESH_REUSE_LEEWAY) > 0) {
            return false;
        }
        return chainStillAlive(token.replacedById(), REUSE_CHAIN_MAX_HOPS);
    }

    /**
     * Anda para a frente na cadeia de rotacao a partir de {@code childId} e diz se ela termina num
     * token vivo.
     *
     * <p>Olhar UM salto so nao chega. Um filho revogado significa duas coisas diferentes: ou a
     * cadeia foi encerrada (logout, troca de senha) ou ele proprio ja rodou de novo -- que e o caso
     * normal de varias abas. Com tres abas a atualizar quase juntas, o filho da primeira ja foi
     * substituido quando a segunda reapresenta o pai; tratar isso como comprometimento revogava a
     * family inteira e deslogava o usuario no meio de um uso legitimo.
     *
     * <p>A distincao esta no {@code replacedById}: revogado COM substituto = rotacionado, segue em
     * frente; revogado SEM substituto = ponta morta, foi revogacao explicita. O teto de saltos
     * existe porque {@code replacedById} vem do banco e nao ha nada no schema que impeca um ciclo.
     */
    private boolean chainStillAlive(UUID childId, int hopsLeft) {
        if (hopsLeft <= 0) {
            return false;
        }
        return refreshTokenRepository
                .findById(childId)
                .map(
                        child -> {
                            if (!child.isRevoked()) {
                                return true;
                            }
                            if (child.replacedById() == null) {
                                return false; // revogado e nunca substituido: cadeia encerrada
                            }
                            return chainStillAlive(child.replacedById(), hopsLeft - 1);
                        })
                .orElse(false);
    }

    /** Carrega o dono do token e barra usuario desativado depois do login. */
    private User loadActiveUser(RefreshToken token) {
        User user =
                userRepository
                        .findById(token.userId())
                        .orElseThrow(AuthException.RefreshTokenInvalid::new);
        if (user.status() == br.com.nora.api.domain.identity.UserStatus.DISABLED) {
            // Usuario desativado depois do login: refresh nao deve renovar.
            throw new AuthException.RefreshTokenInvalid();
        }
        return user;
    }

    /** Emite um par access+refresh como filho de uma family existente (caminho da corrida). */
    private RefreshResult issueChildPair(User user, UUID familyId, Instant now) {
        GeneratedToken next = tokenGenerator.generate();
        refreshTokenRepository.save(
                RefreshToken.issueChild(
                        UUID.randomUUID(),
                        user.id(),
                        user.tenantId(),
                        next.hash(),
                        now,
                        now.plus(settings.refreshTokenTtl()),
                        familyId));
        String access = jwtIssuer.issue(user, DEFAULT_ROLES, settings.jwtTtl());
        return new RefreshResult(
                user,
                access,
                settings.jwtTtl().toSeconds(),
                next.rawToken(),
                settings.refreshTokenTtl().toSeconds());
    }

    /**
     * Logout pontual: revoga apenas o refresh token usado. Idempotente — token ausente ou ja
     * revogado e tratado como sucesso (no-op).
     */
    @Transactional
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
    @Transactional
    public int logoutAllSessions(UUID userId) {
        if (userId == null) {
            return 0;
        }
        return refreshTokenRepository.revokeAllByUserId(userId, clock.now());
    }

    // ----- Configuracoes (GOAL Fase 3): conta, senha autenticada, reenvio, exclusao -----

    /** Usuario autenticado atual (aba Conta). */
    @Transactional(readOnly = true)
    public User me(UUID userId) {
        return userRepository.findById(userId).orElseThrow(AuthException.InvalidCredentials::new);
    }

    /** Atualiza o nome de exibicao do proprio usuario (PATCH /users/me). */
    @Transactional
    public User updateDisplayName(UUID userId, String displayName) {
        User user =
                userRepository.findById(userId).orElseThrow(AuthException.InvalidCredentials::new);
        user.changeDisplayName(displayName, clock.now());
        User saved = userRepository.save(user);
        audit.record(
                saved.tenantId(),
                saved.id(),
                "auth.profile.display_name.updated",
                "USER",
                saved.id(),
                Map.of("displayName", saved.displayName()));
        return saved;
    }

    /**
     * Troca de senha AUTENTICADA (aba Seguranca; distinta do reset por e-mail do US04). Exige a
     * senha atual, valida a nova na policy, revoga TODAS as sessoes (OWASP — sessao roubada nao
     * sobrevive a troca) e emite um par novo para o dispositivo atual continuar logado.
     */
    @Transactional
    public LoginResult changePassword(UUID userId, String currentPassword, String newPassword) {
        User user =
                userRepository.findById(userId).orElseThrow(AuthException.InvalidCredentials::new);
        if (!passwordHasher.matches(currentPassword, user.passwordHash())) {
            throw new AuthException.InvalidCredentials();
        }
        PasswordPolicy.validate(newPassword);
        Instant now = clock.now();
        user.changePasswordHash(passwordHasher.hash(newPassword), now);
        userRepository.save(user);
        int revokedSessions = refreshTokenRepository.revokeAllByUserId(user.id(), now);
        audit.record(
                user.tenantId(),
                user.id(),
                "auth.password.changed",
                "USER",
                user.id(),
                Map.of("revokedSessions", revokedSessions));
        return issueTokens(user);
    }

    /**
     * Reenvia o e-mail de verificacao (tela de login, quando o login falha com EMAIL_NOT_VERIFIED).
     * Silencioso como o reset: e-mail inexistente ou ja verificado nao e distinguivel na resposta
     * (anti-enumeracao).
     */
    @Transactional
    public RequestPasswordResetResult resendVerificationEmail(String rawEmail) {
        Email email;
        try {
            email = Email.of(rawEmail);
        } catch (IllegalArgumentException ex) {
            return new RequestPasswordResetResult(null);
        }
        var maybeUser = userRepository.findByEmail(email);
        if (maybeUser.isEmpty() || maybeUser.get().isEmailVerified()) {
            return new RequestPasswordResetResult(null);
        }
        User user = maybeUser.get();
        Instant now = clock.now();
        // So o ultimo token gerado vale (mesmo padrao do reset de senha).
        tokenRepository.invalidateActiveForUser(user.id(), Purpose.EMAIL_VERIFICATION, now);

        GeneratedToken token = tokenGenerator.generate();
        tokenRepository.save(
                new OneTimeToken(
                        UUID.randomUUID(),
                        user.id(),
                        user.tenantId(),
                        token.hash(),
                        now.plus(settings.emailVerificationTtl()),
                        null,
                        now,
                        Purpose.EMAIL_VERIFICATION));

        String link = settings.publicBaseUrl() + "/auth/verify-email?token=" + token.rawToken();
        emailSender.sendEmailVerification(email.value(), user.displayName(), link);
        audit.record(
                user.tenantId(),
                user.id(),
                "auth.email.verification.resent",
                "USER",
                user.id(),
                Map.of("email", user.email().value()));
        return new RequestPasswordResetResult(settings.exposeDevTokens() ? token.rawToken() : null);
    }

    /**
     * LGPD — exclusao DEFINITIVA da conta (zona de perigo). Exige a senha atual e que o tenant seja
     * pessoal (1 usuario): o hard-delete do tenant CASCADE purga usuario, reunioes, transcricoes
     * (PII), analises, chat, workflows e tokens. Irreversivel por design.
     */
    @Transactional
    public void deleteAccount(UUID userId, UUID tenantId, String password) {
        User user =
                userRepository.findById(userId).orElseThrow(AuthException.InvalidCredentials::new);
        if (!user.tenantId().equals(tenantId)) {
            throw new AuthException.InvalidCredentials();
        }
        if (!passwordHasher.matches(password, user.passwordHash())) {
            throw new AuthException.InvalidCredentials();
        }
        if (userRepository.countByTenant(tenantId) != 1) {
            throw new AuthException.AccountNotPersonal();
        }
        // Sem audit persistido: a trilha pertence ao tenant e sera purgada junto (esquecimento
        // total e o objetivo). Fica so o log operacional com ids, sem PII.
        LOG.info("LGPD account deletion userId={} tenantId={}", userId, tenantId);
        tenantRepository.hardDelete(tenantId);
    }

    // ----- US04: reset de senha -----

    public record RequestPasswordResetCommand(String email) {}

    public record RequestPasswordResetResult(String devToken) {}

    /**
     * US04 step 1: gera token de reset e dispara e-mail. Por seguranca, e-mails nao cadastrados
     * sofrem o mesmo fluxo silenciosamente (mesma latencia, sem revelar existencia da conta).
     */
    @Transactional
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

        audit.record(
                user.tenantId(),
                user.id(),
                "auth.password.reset.requested",
                "USER",
                user.id(),
                Map.of("email", user.email().value()));

        return new RequestPasswordResetResult(settings.exposeDevTokens() ? token.rawToken() : null);
    }

    public record ConfirmPasswordResetCommand(String token, String newPassword) {}

    /**
     * US04 step 2: troca senha + invalida tokens ativos + revoga TODAS as sessoes ativas (refresh
     * tokens) do usuario.
     *
     * <p>Revogar refresh tokens em reset e essencial: se um atacante roubou um refresh
     * anteriormente e a vitima esta resetando a senha por suspeita, manter a sessao do atacante
     * ativa por 30 dias (refresh TTL) anularia o efeito da reset. Padrao OWASP.
     */
    @Transactional
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
        // Revoga todos os refresh tokens ativos: invalida sessoes em qualquer device
        // (vide docstring acima).
        int revokedSessions = refreshTokenRepository.revokeAllByUserId(user.id(), now);
        audit.record(
                user.tenantId(),
                user.id(),
                "auth.password.reset",
                "USER",
                user.id(),
                Map.of("email", user.email().value(), "revokedSessions", revokedSessions));
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
