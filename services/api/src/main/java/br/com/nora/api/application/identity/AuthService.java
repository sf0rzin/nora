package br.com.nora.api.application.identity;

import br.com.nora.api.application.ports.AuditPort;
import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.ports.EmailDispatcher;
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
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for the MVP's full identity flow: signup, e-mail verification, login,
 * password reset. Stories: US01-US04 of the backlog.
 *
 * <p>Round 2 / Subphase 1.3 A adds a stateful refresh token + issuance of the
 * access(JWT)+refresh(opaque) pair on login. See also {@link RefreshToken}.
 *
 * <p>Design decision (US01): each Core signup creates its own personal tenant. The user becomes the
 * first member of that tenant. Invitation to an existing tenant (US06) and Enterprise is handled in
 * another story.
 */
public class AuthService {

    private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);

    /** MVP default roles. Moved here so it can be reused by the refresh. */
    private static final List<String> DEFAULT_ROLES = List.of("ADMIN");

    /**
     * Tolerance window for BENIGN reuse of a freshly rotated refresh token.
     *
     * <p>Legitimate races happen in production: the web's proactive timer and the 401 interceptor
     * fire two simultaneous POST /auth/refresh with the same cookie, and each browser tab has its
     * own timer — the second tab re-presents the old cookie right after the first one rotates.
     * Without the window, reuse detection revoked the whole family and logged the user out in all
     * tabs (bug reported in production).
     *
     * <p>Inside the window, and only when the token was actually rotated ({@code replacedById}
     * filled in — a token revoked by logout does NOT come in here), we treat it as a race and issue
     * a new pair in the same family. Outside it, the real protection against token theft remains:
     * whole family revoked.
     */
    private static final Duration REFRESH_REUSE_LEEWAY = Duration.ofSeconds(60);

    /**
     * Cap on hops when following the rotation chain in {@link #chainStillAlive}. Within the 60s
     * window there is no legitimate use that runs more than this; the cap exists in case {@code
     * replacedById} forms a cycle, which nothing in the schema prevents.
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
    private final EmailDispatcher emailDispatcher;
    private final AuditPort audit;
    private final Clock clock;
    private final AuthSettings settings;

    /**
     * Stand-in operand for the password comparison performed on a login whose address has no
     * account, so that both outcomes pay one full hashing round instead of the comparison being
     * skipped whenever there is nothing stored to compare against.
     *
     * <p>Produced by the configured {@link PasswordHasher} at construction time, from a value drawn
     * at random for this instance: that is what makes it a well-formed digest of the deployed cost
     * factor without hard-coding one. It is never a usable credential — the comparison result is
     * discarded and the branch always ends in {@link AuthException.InvalidCredentials}.
     */
    private final String absentAccountPasswordHash;

    public AuthService(
            TenantRepository tenantRepository,
            UserRepository userRepository,
            OneTimeTokenRepository tokenRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordHasher passwordHasher,
            SecureTokenGenerator tokenGenerator,
            JwtIssuer jwtIssuer,
            EmailSender emailSender,
            EmailDispatcher emailDispatcher,
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
        this.emailDispatcher = emailDispatcher;
        this.audit = audit;
        this.clock = clock;
        this.settings = settings;
        this.absentAccountPasswordHash = passwordHasher.hash(UUID.randomUUID().toString());
    }

    // ----- US01: signup -----

    public record SignupCommand(
            String email, String password, String displayName, String companyName, String role) {
        /** Shortcut for personal signup: no named workspace and no declared role. */
        public SignupCommand(String email, String password, String displayName) {
            this(email, password, displayName, null, null);
        }
    }

    public record SignupResult(UUID userId, UUID tenantId, String emailVerificationDevToken) {}

    /**
     * US01 + US02: creates personal tenant, unverified user and fires the verification e-mail.
     *
     * <p>For convenience in dev/CI, returns the raw token in {@code emailVerificationDevToken} only
     * when {@link AuthSettings#exposeDevTokens()} is {@code true}. In production that field comes
     * back null.
     *
     * <p>An address that already has an account takes {@link #signupNoticeForExistingAccount}
     * instead of failing: the public endpoint is not a place to answer questions about who is
     * registered.
     */
    @Transactional
    public SignupResult signup(SignupCommand cmd) {
        Email email = Email.of(cmd.email());
        PasswordPolicy.validate(cmd.password());
        String displayName =
                (cmd.displayName() == null || cmd.displayName().isBlank())
                        ? email.value().split("@")[0]
                        : cmd.displayName().trim();

        Optional<User> existing = userRepository.findByEmail(email);
        if (existing.isPresent()) {
            return signupNoticeForExistingAccount(existing.get(), cmd);
        }

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

        // First user of the freshly created personal tenant automatically becomes Root (ADR 0007).
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
        String toAddress = email.value();
        emailDispatcher.dispatchAfterCommit(
                () -> emailSender.sendEmailVerification(toAddress, displayName, link));

        Map<String, Object> auditPayload = new HashMap<>();
        auditPayload.put("email", email.value());
        auditPayload.put("flow", "signup-personal");
        // Onboarding telemetry (#156): usage intent declared at signup. Persisted in the audit
        // log (no PII) to segment workspaces by individual/team/company in the PLG funnel.
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

    /**
     * Signup aimed at an address that already has an account.
     *
     * <p>The public endpoint has no business confirming who is registered, so this branch is built
     * to be indistinguishable from the one that really creates: same result shape, same hashing
     * cost, same e-mail handed to the same dispatcher. Nothing is written — no tenant, no user, no
     * token — and the ids that come back are freshly drawn values that match nothing in the
     * database. The person who owns the address is told what happened, in their own mailbox, which
     * is the one place that fact belongs.
     *
     * <p>The authenticated invitation flow keeps {@code EMAIL_ALREADY_TAKEN} (see {@code
     * InvitationService}): there the caller already holds a credential for the workspace and is
     * entitled to know its membership.
     */
    private SignupResult signupNoticeForExistingAccount(User existing, SignupCommand cmd) {
        // Hashed and thrown away: the cost of the request must not depend on the branch it took.
        passwordHasher.hash(cmd.password());
        // Generated and never persisted, so the response carries a field of the same shape.
        GeneratedToken decoyToken = tokenGenerator.generate();

        String toAddress = existing.email().value();
        String ownerName = existing.displayName();
        String signInUrl = settings.publicBaseUrl() + "/auth/login";
        emailDispatcher.dispatchAfterCommit(
                () ->
                        emailSender.sendSignupAttemptOnExistingAccount(
                                toAddress, ownerName, signInUrl));

        audit.record(
                existing.tenantId(),
                existing.id(),
                "auth.user.signup.existing_account_notified",
                "USER",
                existing.id(),
                Map.of("email", toAddress, "flow", "signup-personal"));

        return new SignupResult(
                UUID.randomUUID(),
                UUID.randomUUID(),
                settings.exposeDevTokens() ? decoyToken.rawToken() : null);
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
                // Anti-loop guard. The practical chance of getting here is ~zero.
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

    // ----- US02: e-mail verification -----

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
     * Login result: access(JWT)+refresh(opaque) pair. The raw refresh ({@code refreshTokenPlain})
     * only exists in this response and in the httpOnly cookie the controller sets. The refresh hash
     * lives in {@code refresh_tokens}.
     */
    public record LoginResult(
            User user,
            String accessToken,
            long accessExpiresInSeconds,
            String refreshTokenPlain,
            long refreshExpiresInSeconds) {}

    /**
     * US03: validates credentials and issues an access + refresh pair.
     *
     * <p>The access token is an HS256 JWT (short lived, 15min default) with minimal claims. The
     * refresh is a high-entropy opaque token (256 bits), persisted as a SHA-256 hash.
     *
     * <p>Every rejection here costs the same: an address with no account still goes through one
     * comparison, against {@link #absentAccountPasswordHash}, before the same failure is raised.
     * BCrypt at the configured cost factor is by far the heaviest step of a login, so skipping it
     * on that branch made the answer readable from the outside no matter how uniform the body was.
     */
    @Transactional
    public LoginResult login(LoginCommand cmd) {
        Email email;
        try {
            email = Email.of(cmd.email());
        } catch (IllegalArgumentException ex) {
            throw new AuthException.InvalidCredentials();
        }
        Optional<User> maybeUser = userRepository.findByEmail(email);
        if (maybeUser.isEmpty()) {
            passwordHasher.matches(cmd.password(), absentAccountPasswordHash);
            throw new AuthException.InvalidCredentials();
        }
        User user = maybeUser.get();

        if (!passwordHasher.matches(cmd.password(), user.passwordHash())) {
            throw new AuthException.InvalidCredentials();
        }
        switch (user.status()) {
            case DISABLED -> throw new AuthException.UserDisabled();
            case ACTIVE, INVITED -> {
                /* continue */
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
     * Issues an access+refresh pair for an already authenticated user (called by internal login and
     * also by the invite acceptance, which has already validated credentials properly).
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

    // ----- Round 2 / 1.3 A: refresh + logout (with rotation + reuse detection) -----

    /**
     * Refresh result: new access+refresh pair. The previous refresh was revoked in this same
     * transaction; the client must update the httpOnly cookie with the returned {@code
     * refreshTokenPlain}.
     */
    public record RefreshResult(
            User user,
            String accessToken,
            long accessExpiresInSeconds,
            String refreshTokenPlain,
            long refreshExpiresInSeconds) {}

    /**
     * Refresh with rotation + reuse detection (audit follow-up #3 / OAuth2 best practice).
     *
     * <p>Happy path: validates the presented token, revokes it, issues a child in the same family,
     * returns the new raw refresh to the client.
     *
     * <p>Benign race: token revoked by rotation ({@code replacedById} filled in) and used less than
     * {@link #REFRESH_REUSE_LEEWAY} ago — proactive timer + 401 interceptor in the same tab, or
     * another tab re-presenting the old cookie. We issue a new pair in the SAME family, without
     * revoking anything.
     *
     * <p>Reuse detection: if the presented token is already revoked (and still within the TTL)
     * outside the window above, we assume a compromised chain (attacker exfiltrated the cookie and
     * used an old token). We revoke the whole family and log WARN.
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
                // Reuse of a revoked and not yet expired token, outside the race window:
                // compromised chain. Revokes the whole family to log out attacker + victim
                // simultaneously.
                int revoked = refreshTokenRepository.revokeAllByFamilyId(token.familyId(), now);
                LOG.warn(
                        "Refresh token reuse detected family={} userId={} tokensRevoked={}",
                        token.familyId(),
                        token.userId(),
                        revoked);
                throw new AuthException.RefreshTokenInvalid();
            }
            // Benign race (multi-tab / timer + interceptor): issues a new pair in the same family
            // without revoking anything. The parent is left untouched on purpose — lastUsedAt
            // stays anchored to the FIRST use, so the window does not extend on each reuse (an
            // attacker cannot keep the old token alive by re-presenting it every <60s).
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

        // Rotation: issues child in the same family, marks parent as replaced_by_id, revokes it.
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
     * Reuse is benign when the token was actually rotated ({@code replacedById} filled in), the
     * first use happened at most {@link #REFRESH_REUSE_LEEWAY} ago <b>and</b> the child that
     * replaced it is still valid.
     *
     * <p>The last condition is what makes logout-all, password change and password reset really
     * hold. Those operations revoke only the tokens still NOT revoked ({@code revoked_at is null}),
     * so the parent — already revoked by the rotation itself — survives intact, with {@code
     * replacedById} filled in and the old {@code lastUsedAt}. Without checking the child,
     * re-presenting that parent within the 60s window issued a new and active pair: the session the
     * user had just ended came back into existence. If the child is revoked, the whole chain was
     * terminated and there is no possible benign race — it is reuse.
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
     * Walks forward along the rotation chain starting from {@code childId} and says whether it ends
     * in a live token.
     *
     * <p>Looking at ONE hop only is not enough. A revoked child means two different things: either
     * the chain was terminated (logout, password change) or it has itself already rotated again --
     * which is the normal multi-tab case. With three tabs refreshing almost together, the first
     * one's child has already been replaced when the second re-presents the parent; treating that
     * as a compromise revoked the whole family and logged the user out in the middle of a
     * legitimate use.
     *
     * <p>The distinction is in {@code replacedById}: revoked WITH a replacement = rotated, keep
     * going; revoked WITHOUT a replacement = dead end, it was an explicit revocation. The hop cap
     * exists because {@code replacedById} comes from the database and there is nothing in the
     * schema that prevents a cycle.
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
                                return false; // revoked and never replaced: chain terminated
                            }
                            return chainStillAlive(child.replacedById(), hopsLeft - 1);
                        })
                .orElse(false);
    }

    /** Loads the token owner and blocks a user disabled after login. */
    private User loadActiveUser(RefreshToken token) {
        User user =
                userRepository
                        .findById(token.userId())
                        .orElseThrow(AuthException.RefreshTokenInvalid::new);
        if (user.status() == br.com.nora.api.domain.identity.UserStatus.DISABLED) {
            // User disabled after login: refresh must not renew.
            throw new AuthException.RefreshTokenInvalid();
        }
        return user;
    }

    /** Issues an access+refresh pair as a child of an existing family (race path). */
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
     * Single logout: revokes only the refresh token used. Idempotent — a missing or already revoked
     * token is treated as success (no-op).
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

    /** Full logout: revokes all the user's active refresh tokens (e.g. "log out everywhere"). */
    @Transactional
    public int logoutAllSessions(UUID userId) {
        if (userId == null) {
            return 0;
        }
        return refreshTokenRepository.revokeAllByUserId(userId, clock.now());
    }

    // ----- Settings (GOAL Phase 3): account, authenticated password, resend, deletion -----

    /** Current authenticated user (Account tab). */
    @Transactional(readOnly = true)
    public User me(UUID userId) {
        return userRepository.findById(userId).orElseThrow(AuthException.InvalidCredentials::new);
    }

    /** Updates the user's own display name (PATCH /users/me). */
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
     * AUTHENTICATED password change (Security tab; distinct from the e-mail reset of US04).
     * Requires the current password, validates the new one against the policy, revokes ALL sessions
     * (OWASP — a stolen session does not survive the change) and issues a new pair so the current
     * device stays logged in.
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
     * Resends the verification e-mail (login screen, when login fails with EMAIL_NOT_VERIFIED).
     *
     * <p>Three situations reach this method — no such address, address already verified, address
     * pending verification — and all three return the same result. Two things keep them that way
     * beyond the body: the token is generated on every branch, so the CSPRNG and digest cost is not
     * a branch marker, and the message leaves through {@link EmailDispatcher} after the commit, so
     * the provider round trip is never part of the time to respond.
     *
     * <p>Issuing a token retires whatever was outstanding for the user, so only the most recent
     * link works; what is stored is the digest, never the token itself.
     */
    @Transactional
    public RequestPasswordResetResult resendVerificationEmail(String rawEmail) {
        Email email;
        try {
            email = Email.of(rawEmail);
        } catch (IllegalArgumentException ex) {
            return new RequestPasswordResetResult(null);
        }
        // Generated before the branch: every caller pays for it, whatever the address turns out
        // to be.
        GeneratedToken token = tokenGenerator.generate();
        Optional<User> maybeUser = userRepository.findByEmail(email);
        if (maybeUser.isEmpty() || maybeUser.get().isEmailVerified()) {
            return new RequestPasswordResetResult(null);
        }
        User user = maybeUser.get();
        Instant now = clock.now();
        // Only the last generated token is valid (same pattern as the password reset).
        tokenRepository.invalidateActiveForUser(user.id(), Purpose.EMAIL_VERIFICATION, now);

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
        String toAddress = email.value();
        String displayName = user.displayName();
        emailDispatcher.dispatchAfterCommit(
                () -> emailSender.sendEmailVerification(toAddress, displayName, link));
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
     * LGPD — DEFINITIVE account deletion (danger zone). Requires the current password and that the
     * tenant be personal (1 user): the tenant hard-delete CASCADE purges user, meetings,
     * transcripts (PII), analyses, chat, workflows and tokens. Irreversible by design.
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
        // No persisted audit: the trail belongs to the tenant and will be purged along with it
        // (total forgetting is the goal). Only the operational log with ids remains, no PII.
        LOG.info("LGPD account deletion userId={} tenantId={}", userId, tenantId);
        tenantRepository.hardDelete(tenantId);
    }

    // ----- US04: password reset -----

    public record RequestPasswordResetCommand(String email) {}

    public record RequestPasswordResetResult(String devToken) {}

    /**
     * US04 step 1: generates the reset token and fires the e-mail.
     *
     * <p>An address with no account gets the same result as one with an account, and is built to
     * cost the same: the token is generated before the branch and the message leaves through {@link
     * EmailDispatcher} after the commit, so neither the CSPRNG work nor the provider round trip
     * belongs to only one of the two paths.
     */
    @Transactional
    public RequestPasswordResetResult requestPasswordReset(RequestPasswordResetCommand cmd) {
        Email email;
        try {
            email = Email.of(cmd.email());
        } catch (IllegalArgumentException ex) {
            return new RequestPasswordResetResult(null);
        }
        // Generated before the branch: every caller pays for it, whatever the address turns out
        // to be.
        GeneratedToken token = tokenGenerator.generate();
        Optional<User> maybeUser = userRepository.findByEmail(email);
        if (maybeUser.isEmpty()) {
            // Indistinguishable response so as not to leak which e-mails exist in the system.
            return new RequestPasswordResetResult(null);
        }
        User user = maybeUser.get();
        Instant now = clock.now();

        // Invalidates previous tokens: only the last generated one is valid.
        tokenRepository.invalidateActiveForUser(user.id(), Purpose.PASSWORD_RESET, now);

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
        String toAddress = email.value();
        String displayName = user.displayName();
        emailDispatcher.dispatchAfterCommit(
                () -> emailSender.sendPasswordReset(toAddress, displayName, link));

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
     * US04 step 2: changes the password + invalidates active tokens + revokes ALL of the user's
     * active sessions (refresh tokens).
     *
     * <p>Revoking refresh tokens on reset is essential: if an attacker stole a refresh earlier and
     * the victim is resetting the password out of suspicion, keeping the attacker's session active
     * for 30 days (refresh TTL) would nullify the effect of the reset. OWASP standard.
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
        // Revokes all active refresh tokens: invalidates sessions on any device
        // (see docstring above).
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

    /** Injectable settings. Kept as a record so as not to couple the service to Spring. */
    public record AuthSettings(
            String publicBaseUrl,
            Duration emailVerificationTtl,
            Duration passwordResetTtl,
            Duration jwtTtl,
            Duration refreshTokenTtl,
            boolean exposeDevTokens) {}
}
