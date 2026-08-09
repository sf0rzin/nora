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
 * Use cases of the e-mail invite flow (US06, ADR 0011). Covers creation, listing with on-read
 * expire, revocation and acceptance (which creates the user and returns a JWT).
 *
 * <p>Non-obvious decisions documented inline:
 *
 * <ul>
 *   <li><b>Idempotency:</b> if a non-expired PENDING invite already exists for the same e-mail in
 *       the tenant, we return the existing one without creating a new one or resending the e-mail.
 *       This avoids duplicating invites when the frontend double-clicks or the user clicks "resend"
 *       before the refresh.
 *   <li><b>On-read expire:</b> when listing/accepting, any PENDING invite with {@code expiresAt
 *       &lt; now} is updated to EXPIRED before the response. Avoids running a separate job in the
 *       MVP.
 *   <li><b>Token as secret:</b> we persist only the SHA-256 of the token (same pattern as the other
 *       one-time tokens — email-verification, password-reset, refresh). The raw token exists only
 *       in memory during {@link #inviteUser}, to build the {@code acceptUrl} of the e-mail; we
 *       never return it in listings, never log it and never persist it. On acceptance, we hash the
 *       received token and look it up by hash (O(1) via index). A database dump exposes only the
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
     * Creates an invite (US06). Validates corporate domain, e-mail format, tenant groups,
     * deduplicates PENDINGs, generates a token via {@link SecureTokenGenerator}, persists and fires
     * the e-mail.
     *
     * @return the created invite (or the already existing PENDING, in case of idempotency)
     */
    @Transactional
    public IamInvitation inviteUser(
            UUID tenantId,
            UUID invitedBy,
            String rawEmail,
            Set<UUID> groupIds,
            Integer expiresInDays) {

        // 1. Tenant exists? Gets domain + name.
        Tenant tenant =
                tenants.findById(tenantId)
                        .orElseThrow(() -> new TenantException.NotFound(tenantId));

        // 2. Email format. Reuses Email.of (lowercase + trim + regex).
        Email email = parseEmail(rawEmail);

        // 3. Corporate domain restriction (US32).
        if (tenant.allowedEmailDomain() != null) {
            String domain = extractDomain(email.value());
            if (!tenant.allowedEmailDomain().equalsIgnoreCase(domain)) {
                throw InvitationException.emailDomainNotAllowed(
                        email.value(), tenant.allowedEmailDomain());
            }
        }

        // 4. Validates groupIds.
        Set<UUID> normalizedGroups = normalizeGroups(groupIds);
        for (UUID gid : normalizedGroups) {
            iam.findGroup(gid, tenantId).orElseThrow(IamException::groupNotFound);
        }

        Instant now = clock.now();

        // 5. Idempotency: valid PENDING invite for the same email -> returns it.
        var existing = invitations.findPendingByEmail(tenantId, email.value());
        if (existing.isPresent() && !existing.get().isExpired(now)) {
            return existing.get();
        }
        // If an expired PENDING existed, mark it as EXPIRED so as not to confuse (on-read).
        existing.filter(inv -> inv.isExpired(now))
                .ifPresent(inv -> invitations.save(inv.markExpired()));

        // 6. Computes expiresAt with clamping.
        int days = clampDays(expiresInDays);
        Instant expiresAt = now.plus(Duration.ofDays(days));

        // 7. Generates token (raw + hash). We persist ONLY the SHA-256 (token.hash()) — same
        //    pattern as email-verification / password-reset / refresh. The raw token stays only
        //    in memory in this call to build the acceptUrl of the e-mail; it is never persisted.
        //    Lookup on acceptance hashes the received token and searches by hash (O(1) via index).
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

        // 9. E-mail. Renders absolute link with token.
        String invitedByName = lookupDisplayName(invitedBy);
        String acceptUrl = settings.frontendBaseUrl() + "/auth/invites/accept/" + token.rawToken();
        emailSender.sendInvitation(email.value(), tenant.name(), invitedByName, acceptUrl, days);

        return saved;
    }

    /**
     * Accepts the invite, creating the user and returning a JWT (automatic login). Public endpoint:
     * {@code POST /iam/invites/{token}/accept}.
     *
     * <p>{@code noRollbackFor = InvitationException.class}: we need to persist the on-read expire
     * ({@code markExpired}) even when the call ends up throwing 410 INVITE_EXPIRED. By default
     * Spring rolls back the transaction on any RuntimeException, which would delete the status
     * UPDATE.
     */
    @Transactional(noRollbackFor = InvitationException.class)
    public AcceptResult acceptInvite(String rawToken, String displayName, String password) {
        if (rawToken == null || rawToken.isBlank()) {
            throw InvitationException.inviteNotFound();
        }
        // Lookup by hash: we hash the raw token received and search by the token_hash column
        // (indexed). The raw token never touches the database.
        String tokenHash = tokenGenerator.hash(rawToken);
        IamInvitation invite =
                invitations
                        .findByTokenHash(tokenHash)
                        .orElseThrow(InvitationException::inviteNotFound);

        Instant now = clock.now();

        // Status: if it is not PENDING, it was already consumed/cancelled/expired.
        if (invite.status() == InvitationStatus.ACCEPTED
                || invite.status() == InvitationStatus.REVOKED) {
            throw InvitationException.inviteAlreadyAccepted();
        }
        if (invite.status() == InvitationStatus.EXPIRED) {
            throw InvitationException.inviteExpired();
        }
        // PENDING but with expiresAt in the past: persists EXPIRED and returns 410.
        if (invite.isExpired(now)) {
            invitations.save(invite.markExpired());
            throw InvitationException.inviteExpired();
        }

        // Password validation by the domain (same policy as signup).
        PasswordPolicy.validate(password);
        String safeDisplayName =
                (displayName == null || displayName.isBlank())
                        ? invite.email().split("@")[0]
                        : displayName.trim();

        // Detects collision: e-mail already exists in the database (in case the invite was issued
        // against an address that has meanwhile been registered by another flow).
        Email email = Email.of(invite.email());
        users.findByEmail(email)
                .ifPresent(
                        u -> {
                            throw new AuthException.EmailAlreadyTaken();
                        });

        // Creates the user in the invite's tenant, already verified (it came via the invite).
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

        // Attaches to the groups.
        for (UUID gid : invite.groupIds()) {
            iam.addUserToGroup(savedUser.id(), gid, invite.tenantId(), invite.invitedBy());
        }

        // Marks invite ACCEPTED.
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

        // Issues access+refresh pair (Round 2 / Subphase 1.3 A): same format as AuthService.login.
        // Default roles: empty — IAM via policies.
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
     * Lists the tenant's invites. Applies on-read expire to any overdue PENDING before returning.
     * Persisting the EXPIRED ensures the subsequent listing is consistent.
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

    /** Revokes a PENDING invite. Accepts the actor identity for audit purposes. */
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
            // Email.of has already validated the format; this is defensive.
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
     * Acceptance result: principal + access(JWT)+refresh(opaque) pair + TTLs. The refresh in {@code
     * refreshTokenPlain} only exists in the initial response and in the httpOnly cookie the
     * controller sets; in the DB only the hash is persisted.
     */
    public record AcceptResult(
            User user,
            String accessToken,
            long expiresInSeconds,
            String refreshTokenPlain,
            long refreshExpiresInSeconds) {}

    /**
     * Injected configuration (keeps the service free of Spring). Round 2 / Subphase 1.3 A adds
     * {@code refreshTokenTtl} to align the window of the refresh issued on acceptance with that of
     * the regular login.
     */
    public record InvitationSettings(
            String frontendBaseUrl, Duration jwtTtl, Duration refreshTokenTtl) {}
}
