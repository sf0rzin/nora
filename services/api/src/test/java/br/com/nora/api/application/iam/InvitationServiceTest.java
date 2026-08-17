package br.com.nora.api.application.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.nora.api.application.iam.InvitationService.AcceptResult;
import br.com.nora.api.application.iam.InvitationService.InvitationSettings;
import br.com.nora.api.application.identity.AuthException;
import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.ports.EmailSender;
import br.com.nora.api.application.ports.IamRepository;
import br.com.nora.api.application.ports.InvitationRepository;
import br.com.nora.api.application.ports.JwtIssuer;
import br.com.nora.api.application.ports.PasswordHasher;
import br.com.nora.api.application.ports.RefreshTokenRepository;
import br.com.nora.api.application.ports.SecureTokenGenerator;
import br.com.nora.api.application.ports.TenantRepository;
import br.com.nora.api.application.ports.UserRepository;
import br.com.nora.api.application.tenant.TenantException;
import br.com.nora.api.domain.iam.AttachedPolicy;
import br.com.nora.api.domain.iam.IamAuditEvent;
import br.com.nora.api.domain.iam.IamGroup;
import br.com.nora.api.domain.iam.IamInvitation;
import br.com.nora.api.domain.iam.IamPolicy;
import br.com.nora.api.domain.iam.InvitationStatus;
import br.com.nora.api.domain.iam.PolicyStatement;
import br.com.nora.api.domain.identity.Email;
import br.com.nora.api.domain.identity.RefreshToken;
import br.com.nora.api.domain.identity.User;
import br.com.nora.api.domain.identity.UserStatus;
import br.com.nora.api.domain.tenant.Tenant;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InvitationService} (US06). Covers domain validation, idempotency, audit,
 * on-read expire, revocation and acceptance. Uses Fakes instead of Mockito (same pattern as
 * TenantServiceTest and AuthServiceTest).
 */
class InvitationServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID invitedBy = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();
    private final Instant fixedNow = Instant.parse("2026-05-11T13:00:00Z");

    private FakeClock clock;
    private InMemoryTenantRepo tenants;
    private InMemoryUserRepo users;
    private InMemoryInvitationRepo invitations;
    private RecordingIamRepo iam;
    private FakeEmailSender mail;
    private FakeTokenGenerator tokens;
    private PlainHasher hasher;
    private FakeJwtIssuer jwt;
    private InMemoryRefreshTokenRepo refreshTokens;
    private InvitationService service;

    @BeforeEach
    void setUp() {
        clock = new FakeClock(fixedNow);
        tenants = new InMemoryTenantRepo();
        users = new InMemoryUserRepo();
        invitations = new InMemoryInvitationRepo();
        iam = new RecordingIamRepo();
        mail = new FakeEmailSender();
        tokens = new FakeTokenGenerator();
        hasher = new PlainHasher();
        jwt = new FakeJwtIssuer();
        refreshTokens = new InMemoryRefreshTokenRepo();

        Tenant tenant =
                new Tenant(
                        tenantId,
                        "Acme",
                        "acme",
                        Tenant.Status.ACTIVE,
                        Tenant.Plan.ENTERPRISE,
                        null,
                        fixedNow,
                        fixedNow);
        tenants.save(tenant);

        // valid group
        iam.groups.put(
                groupId,
                new IamGroup(
                        groupId,
                        tenantId,
                        "Vendas",
                        null,
                        invitedBy,
                        OffsetDateTime.now(),
                        OffsetDateTime.now()));

        // inviting user (for invitedByName lookup)
        users.save(
                new User(
                        invitedBy,
                        tenantId,
                        Email.of("camila@acme.com"),
                        "h:irrelevant1",
                        "Camila Root",
                        UserStatus.ACTIVE,
                        fixedNow,
                        fixedNow,
                        fixedNow));

        InvitationSettings settings =
                new InvitationSettings(
                        "http://localhost:3000", Duration.ofMinutes(15), Duration.ofDays(30));
        service =
                new InvitationService(
                        invitations,
                        tenants,
                        users,
                        iam,
                        tokens,
                        hasher,
                        mail,
                        jwt,
                        refreshTokens,
                        clock,
                        settings);
    }

    // ---------- inviteUser ----------

    @Test
    void invite_succeeds_persistsPending_sendsEmail_auditsEvent() {
        IamInvitation inv =
                service.inviteUser(tenantId, invitedBy, "carlos@acme.com", Set.of(groupId), 7);

        assertThat(inv.status()).isEqualTo(InvitationStatus.PENDING);
        assertThat(inv.email()).isEqualTo("carlos@acme.com");
        assertThat(inv.tenantId()).isEqualTo(tenantId);
        assertThat(inv.invitedBy()).isEqualTo(invitedBy);
        assertThat(inv.groupIds()).containsExactly(groupId);
        assertThat(inv.expiresAt()).isEqualTo(fixedNow.plus(Duration.ofDays(7)));

        // E-mail sent.
        assertThat(mail.lastInviteTo).isEqualTo("carlos@acme.com");
        assertThat(mail.lastInviteTenant).isEqualTo("Acme");
        assertThat(mail.lastInviteInvitedBy).isEqualTo("Camila Root");
        assertThat(mail.lastInviteAcceptUrl)
                .startsWith("http://localhost:3000/auth/invites/accept/");
        assertThat(mail.lastInviteExpiresInDays).isEqualTo(7);

        // SECURITY: we persist ONLY the token hash, never the raw token. The e-mail URL carries
        // the raw one; the aggregate/database store the SHA-256 (same pattern as the other
        // one-time tokens).
        String rawToken = lastRawToken();
        assertThat(inv.tokenHash()).isEqualTo(tokens.hash(rawToken));
        assertThat(inv.tokenHash()).isNotEqualTo(rawToken);
        assertThat(mail.lastInviteAcceptUrl).contains(rawToken);
        assertThat(mail.lastInviteAcceptUrl).doesNotContain(inv.tokenHash());

        // Audit recorded.
        assertThat(iam.audits).hasSize(1);
        RecordedAudit rec = iam.audits.get(0);
        assertThat(rec.action).isEqualTo("iam.user.invited");
        assertThat(rec.tenantId).isEqualTo(tenantId);
        assertThat(rec.actor).isEqualTo(invitedBy);
        assertThat(rec.targetType).isEqualTo("INVITATION");
        assertThat(rec.payload).containsEntry("email", "carlos@acme.com");
    }

    @Test
    void invite_rejectsEmail_whenDomainDoesNotMatchTenantAllowedDomain() {
        Tenant restricted = tenants.byId(tenantId);
        tenants.save(restricted.withAllowedEmailDomain("acme.com", fixedNow));

        assertThatThrownBy(
                        () ->
                                service.inviteUser(
                                        tenantId, invitedBy, "alex@gmail.com", Set.of(), 7))
                .isInstanceOf(InvitationException.class)
                .satisfies(
                        ex ->
                                assertThat(((InvitationException) ex).code())
                                        .isEqualTo("EMAIL_DOMAIN_NOT_ALLOWED"));
        assertThat(mail.lastInviteTo).isNull();
        assertThat(iam.audits).isEmpty();
    }

    @Test
    void invite_acceptsEmail_caseInsensitive_whenDomainMatches() {
        Tenant restricted = tenants.byId(tenantId);
        tenants.save(restricted.withAllowedEmailDomain("acme.com", fixedNow));

        IamInvitation inv = service.inviteUser(tenantId, invitedBy, "carlos@ACME.com", Set.of(), 7);
        // Email.of normalizes to lowercase.
        assertThat(inv.email()).isEqualTo("carlos@acme.com");
    }

    @Test
    void invite_rejectsInvalidEmailFormat() {
        assertThatThrownBy(
                        () -> service.inviteUser(tenantId, invitedBy, "not-an-email", Set.of(), 7))
                .isInstanceOf(InvitationException.class);
    }

    @Test
    void invite_rejectsUnknownGroup() {
        UUID otherGroup = UUID.randomUUID();
        assertThatThrownBy(
                        () ->
                                service.inviteUser(
                                        tenantId,
                                        invitedBy,
                                        "alex@acme.com",
                                        Set.of(otherGroup),
                                        7))
                .isInstanceOf(IamException.class);
    }

    @Test
    void invite_isIdempotent_returnsExistingPending_doesNotResendEmail_orDuplicateAudit() {
        IamInvitation first =
                service.inviteUser(tenantId, invitedBy, "carlos@acme.com", Set.of(groupId), 7);
        int auditsAfterFirst = iam.audits.size();
        int emailsAfterFirst = mail.inviteCount;

        IamInvitation second =
                service.inviteUser(tenantId, invitedBy, "carlos@acme.com", Set.of(groupId), 7);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.tokenHash()).isEqualTo(first.tokenHash());
        assertThat(iam.audits).hasSize(auditsAfterFirst);
        assertThat(mail.inviteCount).isEqualTo(emailsAfterFirst);
    }

    @Test
    void invite_whenPreviousPendingExpired_createsNewInvite_andMarksOldAsExpired() {
        IamInvitation first =
                service.inviteUser(tenantId, invitedBy, "carlos@acme.com", Set.of(), 1);

        clock.advance(Duration.ofDays(2));

        IamInvitation second =
                service.inviteUser(tenantId, invitedBy, "carlos@acme.com", Set.of(), 7);

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.tokenHash()).isNotEqualTo(first.tokenHash());

        // The old one must have been marked as EXPIRED.
        IamInvitation oldNow = invitations.byId(first.id()).orElseThrow();
        assertThat(oldNow.status()).isEqualTo(InvitationStatus.EXPIRED);
    }

    @Test
    void invite_clampExpiresInDays_belowMinimum() {
        IamInvitation inv =
                service.inviteUser(tenantId, invitedBy, "carlos@acme.com", Set.of(), -3);
        assertThat(inv.expiresAt())
                .isEqualTo(fixedNow.plus(Duration.ofDays(InvitationService.MIN_EXPIRES_IN_DAYS)));
    }

    @Test
    void invite_clampExpiresInDays_aboveMaximum() {
        IamInvitation inv =
                service.inviteUser(tenantId, invitedBy, "carlos@acme.com", Set.of(), 9999);
        assertThat(inv.expiresAt())
                .isEqualTo(fixedNow.plus(Duration.ofDays(InvitationService.MAX_EXPIRES_IN_DAYS)));
    }

    @Test
    void invite_nullExpiresInDays_usesDefault() {
        IamInvitation inv =
                service.inviteUser(tenantId, invitedBy, "carlos@acme.com", Set.of(), null);
        assertThat(inv.expiresAt())
                .isEqualTo(
                        fixedNow.plus(Duration.ofDays(InvitationService.DEFAULT_EXPIRES_IN_DAYS)));
    }

    @Test
    void invite_unknownTenant_throwsTenantNotFound() {
        assertThatThrownBy(
                        () ->
                                service.inviteUser(
                                        UUID.randomUUID(),
                                        invitedBy,
                                        "carlos@acme.com",
                                        Set.of(),
                                        7))
                .isInstanceOf(TenantException.NotFound.class);
    }

    // ---------- acceptInvite ----------

    @Test
    void accept_happyPath_createsUser_attachesGroups_marksAccepted_audits_returnsJwt() {
        IamInvitation inv =
                service.inviteUser(tenantId, invitedBy, "carlos@acme.com", Set.of(groupId), 7);
        String token = lastRawToken();

        AcceptResult result = service.acceptInvite(token, "Carlos Silva", "SenhaForte123");

        // User created and linked to the tenant.
        assertThat(result.user().email().value()).isEqualTo("carlos@acme.com");
        assertThat(result.user().tenantId()).isEqualTo(tenantId);
        assertThat(result.user().status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(result.user().isEmailVerified()).isTrue();
        assertThat(hasher.matches("SenhaForte123", result.user().passwordHash())).isTrue();
        assertThat(result.accessToken()).isNotBlank();

        // Invite turned ACCEPTED.
        IamInvitation reload = invitations.byId(inv.id()).orElseThrow();
        assertThat(reload.status()).isEqualTo(InvitationStatus.ACCEPTED);
        assertThat(reload.acceptedUserId()).isEqualTo(result.user().id());
        assertThat(reload.acceptedAt()).isEqualTo(fixedNow);

        // Membership attached.
        assertThat(iam.userGroupMemberships).contains(result.user().id() + ":" + groupId);

        // Audit invite.accepted.
        boolean hasAcceptedAudit =
                iam.audits.stream().anyMatch(a -> a.action.equals("iam.invite.accepted"));
        assertThat(hasAcceptedAudit).isTrue();
    }

    @Test
    void accept_issuesAccessAndRefreshTokenPair() {
        IamInvitation inv = service.inviteUser(tenantId, invitedBy, "carol@acme.com", Set.of(), 7);
        String token = lastRawToken();

        AcceptResult result = service.acceptInvite(token, "Carol", "SenhaForte123");

        // access+refresh pair issued.
        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.expiresInSeconds()).isEqualTo(Duration.ofMinutes(15).toSeconds());
        assertThat(result.refreshTokenPlain()).isNotBlank();
        assertThat(result.refreshExpiresInSeconds()).isEqualTo(Duration.ofDays(30).toSeconds());

        // Refresh persisted as HASH (not plain).
        assertThat(refreshTokens.all)
                .hasSize(1)
                .allSatisfy(
                        rt -> {
                            assertThat(rt.tokenHash())
                                    .isEqualTo(tokens.hash(result.refreshTokenPlain()));
                            assertThat(rt.tokenHash()).isNotEqualTo(result.refreshTokenPlain());
                            assertThat(rt.userId()).isEqualTo(result.user().id());
                            assertThat(rt.tenantId()).isEqualTo(tenantId);
                        });
    }

    @Test
    void accept_unknownToken_throwsInviteNotFound() {
        assertThatThrownBy(() -> service.acceptInvite("does-not-exist", "Name", "SenhaForte123"))
                .isInstanceOf(InvitationException.class)
                .satisfies(
                        ex ->
                                assertThat(((InvitationException) ex).code())
                                        .isEqualTo("INVITE_NOT_FOUND"));
    }

    @Test
    void accept_alreadyAccepted_throwsAlreadyAccepted() {
        IamInvitation inv = service.inviteUser(tenantId, invitedBy, "carlos@acme.com", Set.of(), 7);
        String token = lastRawToken();
        service.acceptInvite(token, "Carlos", "SenhaForte123");

        assertThatThrownBy(() -> service.acceptInvite(token, "Other", "SenhaForte123"))
                .isInstanceOf(InvitationException.class)
                .satisfies(
                        ex ->
                                assertThat(((InvitationException) ex).code())
                                        .isEqualTo("INVITE_ALREADY_ACCEPTED"));
    }

    @Test
    void accept_revoked_throwsAlreadyAccepted() {
        IamInvitation inv = service.inviteUser(tenantId, invitedBy, "carlos@acme.com", Set.of(), 7);
        String token = lastRawToken();
        service.revokeInvite(inv.id(), tenantId, invitedBy);

        assertThatThrownBy(() -> service.acceptInvite(token, "Carlos", "SenhaForte123"))
                .isInstanceOf(InvitationException.class)
                .satisfies(
                        ex ->
                                assertThat(((InvitationException) ex).code())
                                        .isEqualTo("INVITE_ALREADY_ACCEPTED"));
    }

    @Test
    void accept_expired_throwsExpired_andPersistsStatus() {
        IamInvitation inv = service.inviteUser(tenantId, invitedBy, "carlos@acme.com", Set.of(), 1);
        String token = lastRawToken();
        clock.advance(Duration.ofDays(2));

        assertThatThrownBy(() -> service.acceptInvite(token, "Carlos", "SenhaForte123"))
                .isInstanceOf(InvitationException.class)
                .satisfies(
                        ex ->
                                assertThat(((InvitationException) ex).code())
                                        .isEqualTo("INVITE_EXPIRED"));

        IamInvitation reload = invitations.byId(inv.id()).orElseThrow();
        assertThat(reload.status()).isEqualTo(InvitationStatus.EXPIRED);
    }

    @Test
    void accept_weakPassword_rejectsViaPasswordPolicy() {
        IamInvitation inv = service.inviteUser(tenantId, invitedBy, "carlos@acme.com", Set.of(), 7);
        String token = lastRawToken();

        assertThatThrownBy(() -> service.acceptInvite(token, "Carlos", "short"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accept_collidingEmail_throwsEmailAlreadyTaken() {
        // A user with the same e-mail already exists (rare but possible scenario).
        users.save(
                new User(
                        UUID.randomUUID(),
                        tenantId,
                        Email.of("carlos@acme.com"),
                        "h:other",
                        "Old Carlos",
                        UserStatus.ACTIVE,
                        fixedNow,
                        fixedNow,
                        fixedNow));

        IamInvitation inv = service.inviteUser(tenantId, invitedBy, "carlos@acme.com", Set.of(), 7);
        String token = lastRawToken();

        assertThatThrownBy(() -> service.acceptInvite(token, "Carlos", "SenhaForte123"))
                .isInstanceOf(AuthException.EmailAlreadyTaken.class);
    }

    @Test
    void accept_blankDisplayName_fallsBackToEmailLocalPart() {
        IamInvitation inv = service.inviteUser(tenantId, invitedBy, "carlos@acme.com", Set.of(), 7);
        String token = lastRawToken();

        AcceptResult result = service.acceptInvite(token, "", "SenhaForte123");
        assertThat(result.user().displayName()).isEqualTo("carlos");
    }

    // ---------- listInvites ----------

    @Test
    void list_appliesOnReadExpire_andFiltersByStatus() {
        IamInvitation a = service.inviteUser(tenantId, invitedBy, "a@acme.com", Set.of(), 1);
        clock.advance(Duration.ofHours(1));
        IamInvitation b = service.inviteUser(tenantId, invitedBy, "b@acme.com", Set.of(), 7);

        clock.advance(Duration.ofDays(2));

        List<IamInvitation> all = service.listInvites(tenantId, null);
        assertThat(all).hasSize(2);
        assertThat(reloadStatus(all, a.id())).isEqualTo(InvitationStatus.EXPIRED);
        assertThat(reloadStatus(all, b.id())).isEqualTo(InvitationStatus.PENDING);

        // On-read expire is persisted.
        assertThat(invitations.byId(a.id()).orElseThrow().status())
                .isEqualTo(InvitationStatus.EXPIRED);

        List<IamInvitation> pending = service.listInvites(tenantId, InvitationStatus.PENDING);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).id()).isEqualTo(b.id());

        List<IamInvitation> expired = service.listInvites(tenantId, InvitationStatus.EXPIRED);
        assertThat(expired).hasSize(1);
        assertThat(expired.get(0).id()).isEqualTo(a.id());
    }

    // ---------- revokeInvite ----------

    @Test
    void revoke_pendingInvite_marksRevoked_andAudits() {
        IamInvitation inv = service.inviteUser(tenantId, invitedBy, "carlos@acme.com", Set.of(), 7);
        iam.audits.clear();

        IamInvitation revoked = service.revokeInvite(inv.id(), tenantId, invitedBy);

        assertThat(revoked.status()).isEqualTo(InvitationStatus.REVOKED);
        assertThat(invitations.byId(inv.id()).orElseThrow().status())
                .isEqualTo(InvitationStatus.REVOKED);

        assertThat(iam.audits).hasSize(1);
        assertThat(iam.audits.get(0).action).isEqualTo("iam.invite.revoked");
    }

    @Test
    void revoke_alreadyAccepted_throwsConflict() {
        IamInvitation inv = service.inviteUser(tenantId, invitedBy, "carlos@acme.com", Set.of(), 7);
        String token = lastRawToken();
        service.acceptInvite(token, "Carlos", "SenhaForte123");

        assertThatThrownBy(() -> service.revokeInvite(inv.id(), tenantId, invitedBy))
                .isInstanceOf(InvitationException.class)
                .satisfies(
                        ex ->
                                assertThat(((InvitationException) ex).code())
                                        .isEqualTo("INVITE_ALREADY_ACCEPTED"));
    }

    @Test
    void revoke_unknownInvite_throwsNotFound() {
        assertThatThrownBy(() -> service.revokeInvite(UUID.randomUUID(), tenantId, invitedBy))
                .isInstanceOf(InvitationException.class)
                .satisfies(
                        ex ->
                                assertThat(((InvitationException) ex).code())
                                        .isEqualTo("INVITE_NOT_FOUND"));
    }

    // ---------- helpers ----------

    private static InvitationStatus reloadStatus(List<IamInvitation> list, UUID id) {
        return list.stream().filter(i -> i.id().equals(id)).findFirst().orElseThrow().status();
    }

    /**
     * Extracts the RAW token from the last accept URL sent by e-mail. The domain only keeps the
     * hash; the raw token exists only in the e-mail URL (exactly what the invitee clicks). Mirrors
     * the real flow.
     */
    private String lastRawToken() {
        String url = mail.lastInviteAcceptUrl;
        return url.substring(url.lastIndexOf('/') + 1);
    }

    // ============================================================
    // Fakes
    // ============================================================

    static class FakeClock implements Clock {
        Instant now;

        FakeClock(Instant t) {
            this.now = t;
        }

        @Override
        public Instant now() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    static class FakeTokenGenerator implements SecureTokenGenerator {
        int seq;

        @Override
        public GeneratedToken generate() {
            String raw = "tok-" + (++seq);
            return new GeneratedToken(raw, "hash:" + raw);
        }

        @Override
        public String hash(String rawToken) {
            return "hash:" + rawToken;
        }
    }

    static class PlainHasher implements PasswordHasher {
        @Override
        public String hash(String raw) {
            return "h:" + raw;
        }

        @Override
        public boolean matches(String raw, String hash) {
            return hash.equals("h:" + raw);
        }
    }

    static class FakeEmailSender implements EmailSender {
        String lastVerifyTo, lastVerifyLink, lastResetTo, lastResetLink;
        String lastInviteTo, lastInviteTenant, lastInviteInvitedBy, lastInviteAcceptUrl;
        int lastInviteExpiresInDays;
        int inviteCount;

        @Override
        public void sendEmailVerification(String to, String name, String link) {
            lastVerifyTo = to;
            lastVerifyLink = link;
        }

        @Override
        public void sendPasswordReset(String to, String name, String link) {
            lastResetTo = to;
            lastResetLink = link;
        }

        @Override
        public void sendSignupAttemptOnExistingAccount(
                String toEmail, String displayName, String signInUrl) {
            // InvitationService does not use the signup notice; no-op here.
        }

        @Override
        public void sendInvitation(
                String toEmail,
                String tenantName,
                String invitedByName,
                String acceptUrl,
                int expiresInDays) {
            lastInviteTo = toEmail;
            lastInviteTenant = tenantName;
            lastInviteInvitedBy = invitedByName;
            lastInviteAcceptUrl = acceptUrl;
            lastInviteExpiresInDays = expiresInDays;
            inviteCount++;
        }

        @Override
        public void sendWorkflowNotification(String toEmail, String subject, String htmlBody) {
            // InvitationService does not use workflow notification; no-op here.
        }
    }

    static class FakeJwtIssuer implements JwtIssuer {
        @Override
        public String issue(User user, List<String> roles, Duration ttl) {
            return "jwt-for-" + user.id();
        }
    }

    static class InMemoryTenantRepo implements TenantRepository {
        final Map<UUID, Tenant> store = new HashMap<>();

        @Override
        public java.util.List<UUID> allActiveTenantIds() {
            return java.util.List.copyOf(store.keySet());
        }

        @Override
        public Optional<Tenant> findById(UUID id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public boolean existsBySlug(String slug) {
            return store.values().stream().anyMatch(t -> t.slug().equals(slug));
        }

        @Override
        public Tenant save(Tenant tenant) {
            store.put(tenant.id(), tenant);
            return tenant;
        }

        @Override
        public void hardDelete(UUID tenantId) {
            store.remove(tenantId);
        }

        Tenant byId(UUID id) {
            return store.get(id);
        }
    }

    static class InMemoryUserRepo implements UserRepository {
        final Map<UUID, User> byId = new LinkedHashMap<>();
        final Set<UUID> rootIds = new HashSet<>();

        @Override
        public int countByTenant(UUID tenantId) {
            return (int) byId.values().stream().filter(u -> u.tenantId().equals(tenantId)).count();
        }

        @Override
        public Optional<User> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<User> findByEmail(Email email) {
            return byId.values().stream().filter(u -> u.email().equals(email)).findFirst();
        }

        @Override
        public User save(User u) {
            byId.put(u.id(), u);
            return u;
        }

        @Override
        public void markAsRoot(UUID userId, UUID tenantId) {
            User u = byId.get(userId);
            if (u != null && u.tenantId().equals(tenantId)) {
                rootIds.add(userId);
            }
        }

        @Override
        public boolean isRoot(UUID userId, UUID tenantId) {
            User u = byId.get(userId);
            return u != null && u.tenantId().equals(tenantId) && rootIds.contains(userId);
        }
    }

    static class InMemoryInvitationRepo implements InvitationRepository {
        final Map<UUID, IamInvitation> store = new LinkedHashMap<>();

        @Override
        public Optional<IamInvitation> findByTokenHash(String tokenHash) {
            return store.values().stream().filter(i -> i.tokenHash().equals(tokenHash)).findFirst();
        }

        @Override
        public Optional<IamInvitation> findById(UUID invitationId, UUID tenantId) {
            return Optional.ofNullable(store.get(invitationId))
                    .filter(i -> i.tenantId().equals(tenantId));
        }

        @Override
        public Optional<IamInvitation> findPendingByEmail(UUID tenantId, String email) {
            return store.values().stream()
                    .filter(
                            i ->
                                    i.tenantId().equals(tenantId)
                                            && i.email().equals(email)
                                            && i.status() == InvitationStatus.PENDING)
                    .findFirst();
        }

        @Override
        public IamInvitation save(IamInvitation invitation) {
            store.put(invitation.id(), invitation);
            return invitation;
        }

        @Override
        public List<IamInvitation> listByTenant(UUID tenantId, InvitationStatus status) {
            return store.values().stream()
                    .filter(i -> i.tenantId().equals(tenantId))
                    .filter(i -> status == null || i.status() == status)
                    .sorted((a, b) -> b.invitedAt().compareTo(a.invitedAt()))
                    .toList();
        }

        Optional<IamInvitation> byId(UUID id) {
            return Optional.ofNullable(store.get(id));
        }
    }

    record RecordedAudit(
            UUID tenantId,
            UUID actor,
            String action,
            String targetType,
            UUID targetId,
            Map<String, Object> payload) {}

    static class RecordingIamRepo implements IamRepository {
        final Map<UUID, IamGroup> groups = new HashMap<>();
        final List<RecordedAudit> audits = new ArrayList<>();
        final Set<String> userGroupMemberships = new HashSet<>();

        @Override
        public Optional<IamGroup> findGroup(UUID groupId, UUID tenantId) {
            return Optional.ofNullable(groups.get(groupId))
                    .filter(g -> g.tenantId().equals(tenantId));
        }

        @Override
        public void addUserToGroup(UUID userId, UUID groupId, UUID tenantId, UUID attachedBy) {
            userGroupMemberships.add(userId + ":" + groupId);
        }

        @Override
        public void recordAudit(
                UUID tenantId,
                UUID actorUserId,
                String action,
                String targetType,
                UUID targetId,
                Map<String, Object> payload) {
            Map<String, Object> copy = new LinkedHashMap<>();
            if (payload != null) {
                copy.putAll(payload);
            }
            audits.add(
                    new RecordedAudit(tenantId, actorUserId, action, targetType, targetId, copy));
        }

        // ----- not used -----

        @Override
        public IamGroup createGroup(
                UUID tenantId, String name, String description, UUID createdBy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<IamGroup> listGroups(UUID tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteGroup(UUID groupId, UUID tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeUserFromGroup(UUID userId, UUID groupId, UUID tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<UUID> listGroupMembers(UUID groupId, UUID tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<UUID> listUserGroups(UUID userId, UUID tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IamPolicy createPolicy(
                UUID tenantId,
                String name,
                String description,
                String documentJson,
                UUID createdBy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IamPolicy updatePolicyDocument(
                UUID policyId, UUID tenantId, String documentJson, UUID updatedBy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<IamPolicy> findPolicy(UUID policyId, UUID tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<IamPolicy> listPolicies(UUID tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deletePolicy(UUID policyId, UUID tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void attachPolicyToGroup(
                UUID policyId, UUID groupId, UUID tenantId, UUID attachedBy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void detachPolicyFromGroup(UUID policyId, UUID groupId, UUID tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void attachPolicyToUser(UUID policyId, UUID userId, UUID tenantId, UUID attachedBy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void detachPolicyFromUser(UUID policyId, UUID userId, UUID tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PolicyStatement> collectStatementsForUser(UUID userId, UUID tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AttachedPolicy> collectAttachedPoliciesForUser(UUID userId, UUID tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<IamAuditEvent> listAudit(UUID tenantId, OffsetDateTime since, int limit) {
            throw new UnsupportedOperationException();
        }
    }

    static class InMemoryRefreshTokenRepo implements RefreshTokenRepository {
        final List<RefreshToken> all = new ArrayList<>();

        @Override
        public RefreshToken save(RefreshToken token) {
            all.removeIf(t -> t.id().equals(token.id()));
            all.add(token);
            return token;
        }

        @Override
        public Optional<RefreshToken> findByTokenHash(String hash) {
            return all.stream().filter(t -> t.tokenHash().equals(hash)).findFirst();
        }

        @Override
        public Optional<RefreshToken> findById(UUID id) {
            return all.stream().filter(t -> t.id().equals(id)).findFirst();
        }

        @Override
        public List<RefreshToken> findActiveByUserId(UUID userId) {
            return all.stream().filter(t -> t.userId().equals(userId) && !t.isRevoked()).toList();
        }

        @Override
        public int revokeAllByUserId(UUID userId, Instant now) {
            int count = 0;
            for (RefreshToken t : all) {
                if (t.userId().equals(userId) && !t.isRevoked()) {
                    t.revoke(now);
                    count++;
                }
            }
            return count;
        }

        @Override
        public int revokeAllByFamilyId(UUID familyId, Instant now) {
            int count = 0;
            for (RefreshToken t : all) {
                if (t.familyId().equals(familyId) && !t.isRevoked()) {
                    t.revoke(now);
                    count++;
                }
            }
            return count;
        }
    }
}
