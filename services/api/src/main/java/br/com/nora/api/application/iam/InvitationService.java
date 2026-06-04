package br.com.nora.api.application.iam;

import br.com.nora.api.application.identity.AuthException;
import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.ports.EmailSender;
import br.com.nora.api.application.ports.IamRepository;
import br.com.nora.api.application.ports.InvitationRepository;
import br.com.nora.api.application.ports.JwtIssuer;
import br.com.nora.api.application.ports.PasswordHasher;
import br.com.nora.api.application.ports.RefreshTokenRepository;
import br.com.nora.api.application.ports.SecureTokenGenerator;
import br.com.nora.api.application.ports.SecureTokenGenerator.GeneratedToken;
import br.com.nora.api.application.ports.TenantRepository;
import br.com.nora.api.application.ports.UserRepository;
import br.com.nora.api.application.tenant.TenantException;
import br.com.nora.api.domain.iam.IamInvitation;
import br.com.nora.api.domain.iam.InvitationStatus;
import br.com.nora.api.domain.identity.Email;
import br.com.nora.api.domain.identity.PasswordPolicy;
import br.com.nora.api.domain.identity.RefreshToken;
import br.com.nora.api.domain.identity.User;
import br.com.nora.api.domain.tenant.Tenant;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso do fluxo de convite por e-mail (US06, ADR 0011). Cobre criacao, listagem com on-read
 * expire, revogacao e aceite (que cria o user e devolve JWT).
 *
 * <p>Decisoes nao-obvias documentadas inline:
 *
 * <ul>
 *   <li><b>Idempotencia:</b> se ja existe um invite PENDING nao-expirado pro mesmo e-mail no
 *       tenant, retornamos o existente sem criar um novo nem reenviar e-mail. Isso evita duplicar
 *       convites quando o frontend faz double-click ou o usuario clica em "reenviar" antes do
 *       refresh.
 *   <li><b>On-read expire:</b> ao listar/aceitar, qualquer invite PENDING com {@code expiresAt &lt;
 *       now} e atualizado para EXPIRED antes da resposta. Evita rodar job separado no MVP.
 *   <li><b>Token como secret:</b> persistimos apenas o SHA-256 do token (mesmo padrao dos demais
 *       one-time tokens — email-verification, password-reset, refresh). O token cru existe apenas
 *       em memoria durante {@link #inviteUser}, para montar o {@code acceptUrl} do e-mail; nunca o
 *       devolvemos em listagens, nunca o logamos e nunca o persistimos. No aceite, hasheamos o
 *       token recebido e fazemos lookup por hash (O(1) por indice). Um dump do banco expoe so o
 *       hash.
 * </ul>
 */
public class InvitationService {

    public static final int DEFAULT_EXPIRES_IN_DAYS = 7;
    public static final int MAX_EXPIRES_IN_DAYS = 30;
    public static final int MIN_EXPIRES_IN_DAYS = 1;

    private final InvitationRepository invitations;
    private final TenantRepository tenants;
    private final UserRepository users;
    private final IamRepository iam;
    private final SecureTokenGenerator tokenGenerator;
    private final PasswordHasher passwordHasher;
    private final EmailSender emailSender;
    private final JwtIssuer jwtIssuer;
    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;
    private final InvitationSettings settings;

    public InvitationService(
            InvitationRepository invitations,
            TenantRepository tenants,
            UserRepository users,
            IamRepository iam,
            SecureTokenGenerator tokenGenerator,
            PasswordHasher passwordHasher,
            EmailSender emailSender,
            JwtIssuer jwtIssuer,
            RefreshTokenRepository refreshTokenRepository,
            Clock clock,
            InvitationSettings settings) {
        this.invitations = invitations;
        this.tenants = tenants;
        this.users = users;
        this.iam = iam;
        this.tokenGenerator = tokenGenerator;
        this.passwordHasher = passwordHasher;
        this.emailSender = emailSender;
        this.jwtIssuer = jwtIssuer;
        this.refreshTokenRepository = refreshTokenRepository;
        this.clock = clock;
        this.settings = settings;
    }

    /**
     * Cria um convite (US06). Valida dominio corporativo, formato de e-mail, grupos do tenant,
     * deduplica PENDINGs, gera token via {@link SecureTokenGenerator}, persiste e dispara e-mail.
     *
     * @return o invite criado (ou o PENDING ja existente, em caso de idempotencia)
     */
    @Transactional
    public IamInvitation inviteUser(
            UUID tenantId,
            UUID invitedBy,
            String rawEmail,
            Set<UUID> groupIds,
            Integer expiresInDays) {

        // 1. Tenant existe? Obtem dominio + nome.
        Tenant tenant =
                tenants.findById(tenantId)
                        .orElseThrow(() -> new TenantException.NotFound(tenantId));

        // 2. Email format. Reusa Email.of (lowercase + trim + regex).
        Email email = parseEmail(rawEmail);

        // 3. Restricao de dominio corporativo (US32).
        if (tenant.allowedEmailDomain() != null) {
            String domain = extractDomain(email.value());
            if (!tenant.allowedEmailDomain().equalsIgnoreCase(domain)) {
                throw InvitationException.emailDomainNotAllowed(
                        email.value(), tenant.allowedEmailDomain());
            }
        }

        // 4. Valida groupIds.
        Set<UUID> normalizedGroups = normalizeGroups(groupIds);
        for (UUID gid : normalizedGroups) {
            iam.findGroup(gid, tenantId).orElseThrow(IamException::groupNotFound);
        }

        Instant now = clock.now();

        // 5. Idempotencia: invite PENDING valido pro mesmo email -> retorna ele.
        var existing = invitations.findPendingByEmail(tenantId, email.value());
        if (existing.isPresent() && !existing.get().isExpired(now)) {
            return existing.get();
        }
        // Se existia um PENDING expirado, marca como EXPIRED para nao confundir (on-read).
        existing.filter(inv -> inv.isExpired(now))
                .ifPresent(inv -> invitations.save(inv.markExpired()));

        // 6. Calcula expiresAt com clamping.
        int days = clampDays(expiresInDays);
        Instant expiresAt = now.plus(Duration.ofDays(days));

        // 7. Gera token (cru + hash). Persistimos APENAS o SHA-256 (token.hash()) — mesmo padrao
        //    de email-verification / password-reset / refresh. O token cru fica apenas em memoria
        //    nesta chamada para montar o acceptUrl do e-mail; jamais e persistido. O lookup no
        //    aceite hasheia o token recebido e busca pelo hash (O(1) por indice).
        GeneratedToken token = tokenGenerator.generate();

        IamInvitation invite =
                new IamInvitation(
                        UUID.randomUUID(),
                        tenantId,
                        email.value(),
                        token.hash(),
                        InvitationStatus.PENDING,
                        invitedBy,
                        now,
                        expiresAt,
                        null,
                        null,
                        normalizedGroups);
        IamInvitation saved = invitations.save(invite);

        // 8. Audit.
        Map<String, Object> payload = new HashMap<>();
        payload.put("email", email.value());
        payload.put("expiresAt", expiresAt.toString());
        payload.put("groupIds", normalizedGroups.stream().map(UUID::toString).toList());
        iam.recordAudit(tenantId, invitedBy, "iam.user.invited", "INVITATION", saved.id(), payload);

        // 9. E-mail. Renderiza link absoluto com token.
        String invitedByName = lookupDisplayName(invitedBy);
        String acceptUrl = settings.frontendBaseUrl() + "/auth/invites/accept/" + token.rawToken();
        emailSender.sendInvitation(email.value(), tenant.name(), invitedByName, acceptUrl, days);

        return saved;
    }

    /**
     * Aceita o convite, criando o user e devolvendo JWT (login automatico). Endpoint publico:
     * {@code POST /iam/invites/{token}/accept}.
     *
     * <p>{@code noRollbackFor = InvitationException.class}: precisamos persistir o on-read expire
     * ({@code markExpired}) mesmo quando a chamada termina lancando 410 INVITE_EXPIRED. Por padrao
     * Spring rolla a transacao em qualquer RuntimeException, o que apagaria o UPDATE de status.
     */
    @Transactional(noRollbackFor = InvitationException.class)
    public AcceptResult acceptInvite(String rawToken, String displayName, String password) {
        if (rawToken == null || rawToken.isBlank()) {
            throw InvitationException.inviteNotFound();
        }
        // Lookup por hash: hasheamos o token cru recebido e buscamos pela coluna token_hash
        // (indexada). O token cru nunca toca o banco.
        String tokenHash = tokenGenerator.hash(rawToken);
        IamInvitation invite =
                invitations
                        .findByTokenHash(tokenHash)
                        .orElseThrow(InvitationException::inviteNotFound);

        Instant now = clock.now();

        // Status: se nao for PENDING, ja foi consumido/cancelado/expirado.
        if (invite.status() == InvitationStatus.ACCEPTED
                || invite.status() == InvitationStatus.REVOKED) {
            throw InvitationException.inviteAlreadyAccepted();
        }
        if (invite.status() == InvitationStatus.EXPIRED) {
            throw InvitationException.inviteExpired();
        }
        // PENDING porem com expiresAt passado: persiste EXPIRED e retorna 410.
        if (invite.isExpired(now)) {
            invitations.save(invite.markExpired());
            throw InvitationException.inviteExpired();
        }

        // Validacao de senha pelo dominio (mesma politica do signup).
        PasswordPolicy.validate(password);
        String safeDisplayName =
                (displayName == null || displayName.isBlank())
                        ? invite.email().split("@")[0]
                        : displayName.trim();

        // Detecta colisao: e-mail ja existe no banco (caso o invite tenha sido emitido contra
        // um endereco que entretanto foi cadastrado por outro fluxo).
        Email email = Email.of(invite.email());
        users.findByEmail(email)
                .ifPresent(
                        u -> {
                            throw new AuthException.EmailAlreadyTaken();
                        });

        // Cria o user no tenant do invite, ja verificado (passou pelo invite).
        User newUser =
                new User(
                        UUID.randomUUID(),
                        invite.tenantId(),
                        email,
                        passwordHasher.hash(password),
                        safeDisplayName,
                        br.com.nora.api.domain.identity.UserStatus.ACTIVE,
                        now,
                        now,
                        now);
        User savedUser = users.save(newUser);

        // Anexa aos grupos.
        for (UUID gid : invite.groupIds()) {
            iam.addUserToGroup(savedUser.id(), gid, invite.tenantId(), invite.invitedBy());
        }

        // Marca invite ACCEPTED.
        IamInvitation accepted = invite.accept(savedUser.id(), now);
        invitations.save(accepted);

        // Audit.
        Map<String, Object> auditPayload = new HashMap<>();
        auditPayload.put("inviteId", accepted.id().toString());
        auditPayload.put("email", accepted.email());
        iam.recordAudit(
                accepted.tenantId(),
                savedUser.id(),
                "iam.invite.accepted",
                "INVITATION",
                accepted.id(),
                auditPayload);

        // Emite par access+refresh (Round 2 / Subfase 1.3 A): mesmo formato de AuthService.login.
        // Roles default: vazia — IAM via policies.
        String jwt = jwtIssuer.issue(savedUser, List.of(), settings.jwtTtl());
        GeneratedToken refresh = tokenGenerator.generate();
        refreshTokenRepository.save(
                RefreshToken.issueRoot(
                        UUID.randomUUID(),
                        savedUser.id(),
                        savedUser.tenantId(),
                        refresh.hash(),
                        now,
                        now.plus(settings.refreshTokenTtl())));
        return new AcceptResult(
                savedUser,
                jwt,
                settings.jwtTtl().toSeconds(),
                refresh.rawToken(),
                settings.refreshTokenTtl().toSeconds());
    }

    /**
     * Lista convites do tenant. Aplica on-read expire para qualquer PENDING vencido antes de
     * devolver. Persistir o EXPIRED garante que a listagem subsequente seja consistente.
     */
    @Transactional
    public List<IamInvitation> listInvites(UUID tenantId, InvitationStatus statusFilter) {
        Instant now = clock.now();
        List<IamInvitation> all = invitations.listByTenant(tenantId, null);
        List<IamInvitation> result = new ArrayList<>(all.size());
        for (IamInvitation inv : all) {
            IamInvitation current = inv;
            if (inv.status() == InvitationStatus.PENDING && inv.isExpired(now)) {
                current = invitations.save(inv.markExpired());
            }
            if (statusFilter == null || current.status() == statusFilter) {
                result.add(current);
            }
        }
        return result;
    }

    /** Revoga um convite PENDING. Aceita identidade do actor para fins de audit. */
    @Transactional
    public IamInvitation revokeInvite(UUID invitationId, UUID tenantId, UUID actorUserId) {
        IamInvitation invite =
                invitations
                        .findById(invitationId, tenantId)
                        .orElseThrow(InvitationException::inviteNotFound);

        if (invite.status() != InvitationStatus.PENDING) {
            throw InvitationException.inviteAlreadyAccepted();
        }

        IamInvitation revoked = invite.revoke();
        IamInvitation saved = invitations.save(revoked);

        Map<String, Object> payload = new HashMap<>();
        payload.put("email", saved.email());
        iam.recordAudit(
                tenantId, actorUserId, "iam.invite.revoked", "INVITATION", saved.id(), payload);

        return saved;
    }

    // ---------- helpers ----------

    private Email parseEmail(String rawEmail) {
        try {
            return Email.of(rawEmail);
        } catch (IllegalArgumentException ex) {
            throw new InvitationException("INVITE_EMAIL_INVALID", ex.getMessage());
        }
    }

    private String extractDomain(String email) {
        int at = email.indexOf('@');
        if (at < 0 || at == email.length() - 1) {
            // Email.of ja validou formato; isso e defensivo.
            throw new InvitationException("INVITE_EMAIL_INVALID", "email missing domain");
        }
        return email.substring(at + 1).toLowerCase(Locale.ROOT);
    }

    private static Set<UUID> normalizeGroups(Set<UUID> groupIds) {
        if (groupIds == null) {
            return Set.of();
        }
        Set<UUID> out = new LinkedHashSet<>();
        for (UUID g : groupIds) {
            if (g != null) {
                out.add(g);
            }
        }
        return out;
    }

    private static int clampDays(Integer days) {
        if (days == null) {
            return DEFAULT_EXPIRES_IN_DAYS;
        }
        if (days < MIN_EXPIRES_IN_DAYS) {
            return MIN_EXPIRES_IN_DAYS;
        }
        if (days > MAX_EXPIRES_IN_DAYS) {
            return MAX_EXPIRES_IN_DAYS;
        }
        return days;
    }

    private String lookupDisplayName(UUID userId) {
        return users.findById(userId).map(User::displayName).orElse("Administrador");
    }

    /**
     * Resultado do aceite: principal + par access(JWT)+refresh(opaque) + TTLs. O refresh em {@code
     * refreshTokenPlain} so existe na resposta inicial e no cookie httpOnly que o controller seta;
     * em DB persiste apenas o hash.
     */
    public record AcceptResult(
            User user,
            String accessToken,
            long expiresInSeconds,
            String refreshTokenPlain,
            long refreshExpiresInSeconds) {}

    /**
     * Configuracao injetada (mantem o servico free of Spring). Round 2 / Subfase 1.3 A adiciona
     * {@code refreshTokenTtl} para alinhar a janela do refresh emitido no aceite com a do login
     * regular.
     */
    public record InvitationSettings(
            String frontendBaseUrl, Duration jwtTtl, Duration refreshTokenTtl) {}
}
