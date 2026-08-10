package br.com.nora.api.application.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.nora.api.application.identity.AuthService.AuthSettings;
import br.com.nora.api.application.identity.AuthService.ConfirmPasswordResetCommand;
import br.com.nora.api.application.identity.AuthService.LoginCommand;
import br.com.nora.api.application.identity.AuthService.LoginResult;
import br.com.nora.api.application.identity.AuthService.RefreshResult;
import br.com.nora.api.application.identity.AuthService.RequestPasswordResetCommand;
import br.com.nora.api.application.identity.AuthService.SignupCommand;
import br.com.nora.api.application.identity.AuthService.SignupResult;
import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.ports.EmailDispatcher;
import br.com.nora.api.application.ports.EmailSender;
import br.com.nora.api.application.ports.JwtIssuer;
import br.com.nora.api.application.ports.OneTimeTokenRepository;
import br.com.nora.api.application.ports.PasswordHasher;
import br.com.nora.api.application.ports.RefreshTokenRepository;
import br.com.nora.api.application.ports.SecureTokenGenerator;
import br.com.nora.api.application.ports.TenantRepository;
import br.com.nora.api.application.ports.UserRepository;
import br.com.nora.api.domain.identity.Email;
import br.com.nora.api.domain.identity.OneTimeToken;
import br.com.nora.api.domain.identity.OneTimeToken.Purpose;
import br.com.nora.api.domain.identity.RefreshToken;
import br.com.nora.api.domain.identity.User;
import br.com.nora.api.domain.tenant.Tenant;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers US01-US04 + refresh/logout (Round 2 / 1.3 A) with in-memory fakes, no Spring or database.
 */
class AuthServiceTest {

    private FakeClock clock;
    private FakeEmailSender mail;
    private PlainHasher hasher;
    private FakeTokenGenerator tokens;
    private InMemoryTenantRepo tenants;
    private InMemoryUserRepo users;
    private InMemoryTokenRepo tokenRepo;
    private InMemoryRefreshTokenRepo refreshRepo;
    private FakeJwtIssuer jwts;
    private RecordingAuditPort audit;
    private AuthService service;

    @BeforeEach
    void setUp() {
        clock = new FakeClock(Instant.parse("2026-05-04T10:00:00Z"));
        mail = new FakeEmailSender();
        hasher = new PlainHasher();
        tokens = new FakeTokenGenerator();
        tenants = new InMemoryTenantRepo();
        users = new InMemoryUserRepo();
        tokenRepo = new InMemoryTokenRepo();
        refreshRepo = new InMemoryRefreshTokenRepo();
        jwts = new FakeJwtIssuer();
        audit = new RecordingAuditPort();
        service =
                new AuthService(
                        tenants,
                        users,
                        tokenRepo,
                        refreshRepo,
                        hasher,
                        tokens,
                        jwts,
                        mail,
                        new InlineEmailDispatcher(),
                        audit,
                        clock,
                        new AuthSettings(
                                "http://localhost:3000",
                                Duration.ofHours(24),
                                Duration.ofHours(1),
                                Duration.ofMinutes(15),
                                Duration.ofDays(30),
                                true));
    }

    // ---------- US01 + US02 ----------

    @Test
    void signupCreatesTenantAndUserAndSendsVerificationEmail() {
        SignupResult result =
                service.signup(new SignupCommand("lucas@nora.dev", "SenhaForte123", "Lucas"));

        assertThat(users.byId(result.userId())).isPresent();
        assertThat(tenants.byId(result.tenantId())).isPresent();
        assertThat(mail.lastVerifyTo).isEqualTo("lucas@nora.dev");
        assertThat(mail.lastVerifyLink).contains(result.emailVerificationDevToken());
    }

    /**
     * Replaces the old {@code signupRejectsDuplicateEmail}, which asserted that the second signup
     * threw {@code EmailAlreadyTaken}. That behaviour was the defect: signup is public and
     * unauthenticated, and a distinct outcome for a registered address answered, for any address
     * submitted, a question the caller had not earned an answer to. The rejection now lives only in
     * the authenticated invitation flow (covered by {@code InvitationServiceTest}); what has to
     * hold here is that the second call looks like the first and changes nothing.
     */
    @Test
    void signupOnExistingAddressLooksLikeAFreshSignupAndCreatesNothing() {
        SignupResult first =
                service.signup(new SignupCommand("dup@nora.dev", "SenhaForte123", "X"));

        SignupResult second =
                service.signup(new SignupCommand("DUP@nora.dev", "OutraSenha456", "Y"));

        // Same shape as the path that really creates: ids and dev token all present.
        assertThat(second.userId()).isNotNull().isNotEqualTo(first.userId());
        assertThat(second.tenantId()).isNotNull().isNotEqualTo(first.tenantId());
        assertThat(second.emailVerificationDevToken()).isNotBlank();

        // Nothing was persisted: the returned ids match no row and the token verifies nothing.
        assertThat(users.byId(second.userId())).isEmpty();
        assertThat(tenants.byId(second.tenantId())).isEmpty();
        assertThatThrownBy(() -> service.verifyEmail(second.emailVerificationDevToken()))
                .isInstanceOf(AuthException.TokenInvalid.class);

        // The owner of the address is the one who gets told, and the notice only points them at
        // the sign-in page — it carries nothing that grants access.
        assertThat(mail.lastSignupAttemptTo).isEqualTo("dup@nora.dev");
        assertThat(mail.lastSignupAttemptSignInUrl).isEqualTo("http://localhost:3000/auth/login");

        // And the account that already existed is untouched: the password sent on the second
        // attempt does not log in, the original one does.
        service.verifyEmail(first.emailVerificationDevToken());
        assertThatThrownBy(() -> service.login(new LoginCommand("dup@nora.dev", "OutraSenha456")))
                .isInstanceOf(AuthException.InvalidCredentials.class);
        assertThat(service.login(new LoginCommand("dup@nora.dev", "SenhaForte123")).user().id())
                .isEqualTo(first.userId());
    }

    @Test
    void verifyEmailTransitionsUserToVerified() {
        SignupResult sr = service.signup(new SignupCommand("v@nora.dev", "SenhaForte123", "V"));
        service.verifyEmail(sr.emailVerificationDevToken());

        User u = users.byId(sr.userId()).orElseThrow();
        assertThat(u.isEmailVerified()).isTrue();
    }

    @Test
    void verifyEmailTokenCannotBeReused() {
        SignupResult sr = service.signup(new SignupCommand("once@nora.dev", "SenhaForte123", "O"));
        service.verifyEmail(sr.emailVerificationDevToken());
        assertThatThrownBy(() -> service.verifyEmail(sr.emailVerificationDevToken()))
                .isInstanceOf(AuthException.TokenInvalid.class);
    }

    @Test
    void verifyEmailRejectsExpiredToken() {
        SignupResult sr = service.signup(new SignupCommand("exp@nora.dev", "SenhaForte123", "E"));
        clock.advance(Duration.ofDays(2));
        assertThatThrownBy(() -> service.verifyEmail(sr.emailVerificationDevToken()))
                .isInstanceOf(AuthException.TokenInvalid.class);
    }

    /**
     * Issue #399: only the most recently issued verification link may work. Without this, every
     * resend left one more live link on the account, and each one is a credential that verifies the
     * address on its own.
     */
    @Test
    void resendingVerificationInvalidatesThePreviousToken() {
        SignupResult sr = service.signup(new SignupCommand("rv@nora.dev", "SenhaForte123", "RV"));

        var resent = service.resendVerificationEmail("rv@nora.dev");
        assertThat(resent.devToken()).isNotBlank().isNotEqualTo(sr.emailVerificationDevToken());

        // The link from the signup is dead the moment a new one is issued.
        assertThatThrownBy(() -> service.verifyEmail(sr.emailVerificationDevToken()))
                .isInstanceOf(AuthException.TokenInvalid.class);

        service.verifyEmail(resent.devToken());
        assertThat(users.byId(sr.userId()).orElseThrow().isEmailVerified()).isTrue();
    }

    /**
     * Unknown, already verified and pending all come back the same. The dev token is the one field
     * that differs, and only because {@code exposeDevTokens} is on in this fixture — production
     * leaves it off and the whole result is identical.
     */
    @Test
    void resendVerificationAnswersTheSameForEveryAccountState() {
        SignupResult pending =
                service.signup(new SignupCommand("rs-pend@nora.dev", "SenhaForte123", "P"));
        SignupResult done =
                service.signup(new SignupCommand("rs-done@nora.dev", "SenhaForte123", "D"));
        service.verifyEmail(done.emailVerificationDevToken());
        mail.lastVerifyTo = null;

        assertThat(service.resendVerificationEmail("rs-ghost@nora.dev").devToken()).isNull();
        assertThat(mail.lastVerifyTo).isNull();

        assertThat(service.resendVerificationEmail("rs-done@nora.dev").devToken()).isNull();
        assertThat(mail.lastVerifyTo).isNull();

        assertThat(service.resendVerificationEmail("rs-pend@nora.dev").devToken())
                .isNotBlank()
                .isNotEqualTo(pending.emailVerificationDevToken());
        assertThat(mail.lastVerifyTo).isEqualTo("rs-pend@nora.dev");
    }

    // ---------- US03 ----------

    @Test
    void loginSucceedsAfterVerification() {
        SignupResult sr = service.signup(new SignupCommand("ok@nora.dev", "SenhaForte123", "Ok"));
        service.verifyEmail(sr.emailVerificationDevToken());

        LoginResult result = service.login(new LoginCommand("ok@nora.dev", "SenhaForte123"));
        assertThat(result.user().email().value()).isEqualTo("ok@nora.dev");
        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.accessExpiresInSeconds()).isEqualTo(900L);
        assertThat(result.refreshTokenPlain()).isNotBlank();
        assertThat(result.refreshExpiresInSeconds()).isEqualTo(Duration.ofDays(30).toSeconds());
    }

    @Test
    void loginBlockedBeforeVerification() {
        service.signup(new SignupCommand("nv@nora.dev", "SenhaForte123", "NV"));
        assertThatThrownBy(() -> service.login(new LoginCommand("nv@nora.dev", "SenhaForte123")))
                .isInstanceOf(AuthException.EmailNotVerified.class);
    }

    @Test
    void loginRejectsWrongPassword() {
        SignupResult sr = service.signup(new SignupCommand("wp@nora.dev", "SenhaForte123", "WP"));
        service.verifyEmail(sr.emailVerificationDevToken());
        assertThatThrownBy(() -> service.login(new LoginCommand("wp@nora.dev", "errada123")))
                .isInstanceOf(AuthException.InvalidCredentials.class);
    }

    @Test
    void loginUnknownEmailReturnsGenericError() {
        assertThatThrownBy(() -> service.login(new LoginCommand("nada@nora.dev", "SenhaForte123")))
                .isInstanceOf(AuthException.InvalidCredentials.class);
    }

    /**
     * The two rejected logins must run the same number of password comparisons. Asserting on the
     * call count and not on elapsed time is deliberate: with BCrypt at the configured cost factor
     * the comparison IS the cost of a login, so proving it happens on both branches proves they
     * cost the same, and a wall-clock assertion would only add flakiness to CI.
     */
    @Test
    void loginComparesPasswordEvenWhenTheAddressHasNoAccount() {
        SignupResult sr = service.signup(new SignupCommand("cmp@nora.dev", "SenhaForte123", "CMP"));
        service.verifyEmail(sr.emailVerificationDevToken());

        hasher.matchCalls = 0;
        assertThatThrownBy(() -> service.login(new LoginCommand("cmp@nora.dev", "errada123")))
                .isInstanceOf(AuthException.InvalidCredentials.class);
        int forRegistered = hasher.matchCalls;

        hasher.matchCalls = 0;
        assertThatThrownBy(() -> service.login(new LoginCommand("ghost@nora.dev", "errada123")))
                .isInstanceOf(AuthException.InvalidCredentials.class);
        int forUnknown = hasher.matchCalls;

        assertThat(forRegistered).isEqualTo(1);
        assertThat(forUnknown).isEqualTo(forRegistered);
    }

    @Test
    void loginPersistsRefreshTokenHashOnly() {
        SignupResult sr = service.signup(new SignupCommand("rp@nora.dev", "SenhaForte123", "RP"));
        service.verifyEmail(sr.emailVerificationDevToken());

        LoginResult login = service.login(new LoginCommand("rp@nora.dev", "SenhaForte123"));

        // Checks that what ended up in the DB is the HASH, not the plain one.
        assertThat(refreshRepo.all)
                .hasSize(1)
                .allSatisfy(
                        rt -> {
                            assertThat(rt.tokenHash())
                                    .isEqualTo(tokens.hash(login.refreshTokenPlain()));
                            assertThat(rt.tokenHash()).isNotEqualTo(login.refreshTokenPlain());
                        });
    }

    // ---------- Round 2: refresh + logout ----------

    @Test
    void refreshRotatesIssuingNewPairAndRevokingPrevious() {
        SignupResult sr = service.signup(new SignupCommand("rf@nora.dev", "SenhaForte123", "RF"));
        service.verifyEmail(sr.emailVerificationDevToken());
        LoginResult login = service.login(new LoginCommand("rf@nora.dev", "SenhaForte123"));

        clock.advance(Duration.ofMinutes(1));
        RefreshResult refresh = service.refresh(login.refreshTokenPlain());

        assertThat(refresh.accessToken()).isNotBlank().isNotEqualTo(login.accessToken());
        assertThat(refresh.refreshTokenPlain())
                .isNotBlank()
                .isNotEqualTo(login.refreshTokenPlain());
        // 2 records: revoked parent + active child, both in the same family.
        assertThat(refreshRepo.all).hasSize(2);
        RefreshToken parent =
                refreshRepo.all.stream().filter(RefreshToken::isRevoked).findFirst().orElseThrow();
        RefreshToken child =
                refreshRepo.all.stream().filter(t -> !t.isRevoked()).findFirst().orElseThrow();
        assertThat(parent.familyId()).isEqualTo(child.familyId());
        assertThat(parent.replacedById()).isEqualTo(child.id());

        // The new refresh works; the previous one, already revoked, triggers reuse detection
        // after the benign race window (60s).
        RefreshResult again = service.refresh(refresh.refreshTokenPlain());
        assertThat(again.accessToken()).isNotBlank();
        clock.advance(Duration.ofSeconds(61));
        assertThatThrownBy(() -> service.refresh(login.refreshTokenPlain()))
                .isInstanceOf(AuthException.RefreshTokenInvalid.class);
    }

    @Test
    void refreshReuseAfterLeewayRevokesEntireFamily() {
        SignupResult sr = service.signup(new SignupCommand("ru@nora.dev", "SenhaForte123", "RU"));
        service.verifyEmail(sr.emailVerificationDevToken());
        LoginResult login = service.login(new LoginCommand("ru@nora.dev", "SenhaForte123"));

        // Rotate once. Attacker recovers the old token.
        RefreshResult rotated = service.refresh(login.refreshTokenPlain());

        // Outside the benign race window (60s), reuse is treated as token theft.
        clock.advance(Duration.ofSeconds(61));

        // Attacker re-presents the old (revoked) token → reuse detection revokes the family.
        assertThatThrownBy(() -> service.refresh(login.refreshTokenPlain()))
                .isInstanceOf(AuthException.RefreshTokenInvalid.class);

        // After the reuse, the rotated token (which was valid) was also invalidated.
        assertThatThrownBy(() -> service.refresh(rotated.refreshTokenPlain()))
                .isInstanceOf(AuthException.RefreshTokenInvalid.class);
        assertThat(refreshRepo.all).allMatch(RefreshToken::isRevoked);
    }

    @Test
    void refreshReuseWithinLeewayIsTreatedAsBenignRaceAndKeepsFamilyAlive() {
        // Real production scenario: proactive timer + 401 interceptor (or two tabs)
        // fire an almost simultaneous refresh with the same cookie. The second arrives
        // with the token already rotated — it must NOT tear down the family (spontaneous
        // logout).
        SignupResult sr = service.signup(new SignupCommand("br@nora.dev", "SenhaForte123", "BR"));
        service.verifyEmail(sr.emailVerificationDevToken());
        LoginResult login = service.login(new LoginCommand("br@nora.dev", "SenhaForte123"));

        // Tab A rotates normally.
        RefreshResult rotated = service.refresh(login.refreshTokenPlain());

        // Tab B re-presents the old token 30s later (inside the 60s window).
        clock.advance(Duration.ofSeconds(30));
        RefreshResult raced = service.refresh(login.refreshTokenPlain());

        // Gets a new valid pair, in the SAME family, without revoking anything else.
        assertThat(raced.accessToken()).isNotBlank();
        assertThat(raced.refreshTokenPlain())
                .isNotBlank()
                .isNotEqualTo(login.refreshTokenPlain())
                .isNotEqualTo(rotated.refreshTokenPlain());
        assertThat(refreshRepo.all).hasSize(3);
        UUID familyId = refreshRepo.all.get(0).familyId();
        assertThat(refreshRepo.all).allMatch(t -> t.familyId().equals(familyId));
        // Only the parent (rotated) is revoked; both children stay active.
        assertThat(refreshRepo.all.stream().filter(RefreshToken::isRevoked)).hasSize(1);

        // Both "tabs" keep working: A's and B's tokens remain refreshable.
        assertThat(service.refresh(rotated.refreshTokenPlain()).accessToken()).isNotBlank();
        assertThat(service.refresh(raced.refreshTokenPlain()).accessToken()).isNotBlank();
    }

    @Test
    void refreshReuseWindowIsAnchoredOnFirstUseNotExtendedByEachReuse() {
        // Anti-abuse: reusing the old token inside the window does NOT renew the window.
        // 30s + 31s = 61s since the FIRST use → full reuse detection.
        SignupResult sr = service.signup(new SignupCommand("an@nora.dev", "SenhaForte123", "AN"));
        service.verifyEmail(sr.emailVerificationDevToken());
        LoginResult login = service.login(new LoginCommand("an@nora.dev", "SenhaForte123"));

        service.refresh(login.refreshTokenPlain());

        clock.advance(Duration.ofSeconds(30));
        RefreshResult raced = service.refresh(login.refreshTokenPlain());
        assertThat(raced.accessToken()).isNotBlank();

        clock.advance(Duration.ofSeconds(31));
        assertThatThrownBy(() -> service.refresh(login.refreshTokenPlain()))
                .isInstanceOf(AuthException.RefreshTokenInvalid.class);
        assertThat(refreshRepo.all).allMatch(RefreshToken::isRevoked);
    }

    @Test
    void refreshFailsForUnknownToken() {
        assertThatThrownBy(() -> service.refresh("nao-existe"))
                .isInstanceOf(AuthException.RefreshTokenInvalid.class);
    }

    @Test
    void refreshFailsForBlankToken() {
        assertThatThrownBy(() -> service.refresh(""))
                .isInstanceOf(AuthException.RefreshTokenInvalid.class);
        assertThatThrownBy(() -> service.refresh(null))
                .isInstanceOf(AuthException.RefreshTokenInvalid.class);
    }

    @Test
    void refreshFailsAfterExpiration() {
        SignupResult sr = service.signup(new SignupCommand("ex@nora.dev", "SenhaForte123", "EX"));
        service.verifyEmail(sr.emailVerificationDevToken());
        LoginResult login = service.login(new LoginCommand("ex@nora.dev", "SenhaForte123"));

        clock.advance(Duration.ofDays(31));
        assertThatThrownBy(() -> service.refresh(login.refreshTokenPlain()))
                .isInstanceOf(AuthException.RefreshTokenInvalid.class);
    }

    @Test
    void refreshFailsAfterRevokedByLogout() {
        SignupResult sr = service.signup(new SignupCommand("lo@nora.dev", "SenhaForte123", "LO"));
        service.verifyEmail(sr.emailVerificationDevToken());
        LoginResult login = service.login(new LoginCommand("lo@nora.dev", "SenhaForte123"));

        service.logout(login.refreshTokenPlain());

        assertThatThrownBy(() -> service.refresh(login.refreshTokenPlain()))
                .isInstanceOf(AuthException.RefreshTokenInvalid.class);
    }

    @Test
    void logoutIsIdempotentOnAbsentOrInvalidToken() {
        // No token: silent no-op.
        service.logout(null);
        service.logout("");
        service.logout("inexistente");
        assertThat(refreshRepo.all).isEmpty();
    }

    @Test
    void logoutRevokesOnlyOwnFamilyNotAllUserSessions() {
        SignupResult sr = service.signup(new SignupCommand("mu@nora.dev", "SenhaForte123", "MU"));
        service.verifyEmail(sr.emailVerificationDevToken());
        LoginResult first = service.login(new LoginCommand("mu@nora.dev", "SenhaForte123"));
        LoginResult second = service.login(new LoginCommand("mu@nora.dev", "SenhaForte123"));

        service.logout(first.refreshTokenPlain());

        // First family was revoked (logout).
        assertThatThrownBy(() -> service.refresh(first.refreshTokenPlain()))
                .isInstanceOf(AuthException.RefreshTokenInvalid.class);
        // Second family stays valid.
        RefreshResult ok = service.refresh(second.refreshTokenPlain());
        assertThat(ok.accessToken()).isNotBlank();
    }

    @Test
    void logoutAllSessionsRevokesEverySessionOfUser() {
        SignupResult sr = service.signup(new SignupCommand("la@nora.dev", "SenhaForte123", "LA"));
        service.verifyEmail(sr.emailVerificationDevToken());
        LoginResult first = service.login(new LoginCommand("la@nora.dev", "SenhaForte123"));
        LoginResult second = service.login(new LoginCommand("la@nora.dev", "SenhaForte123"));

        int revoked = service.logoutAllSessions(sr.userId());
        assertThat(revoked).isEqualTo(2);

        assertThatThrownBy(() -> service.refresh(first.refreshTokenPlain()))
                .isInstanceOf(AuthException.RefreshTokenInvalid.class);
        assertThatThrownBy(() -> service.refresh(second.refreshTokenPlain()))
                .isInstanceOf(AuthException.RefreshTokenInvalid.class);
    }

    // ---------- US04 ----------

    @Test
    void requestResetForUnknownEmailIsSilentlyOk() {
        var r = service.requestPasswordReset(new RequestPasswordResetCommand("ghost@nora.dev"));
        assertThat(r.devToken()).isNull();
        assertThat(mail.lastResetTo).isNull();
    }

    @Test
    void resetFlowChangesPassword() {
        SignupResult sr = service.signup(new SignupCommand("rs@nora.dev", "SenhaForte123", "RS"));
        service.verifyEmail(sr.emailVerificationDevToken());

        var req = service.requestPasswordReset(new RequestPasswordResetCommand("rs@nora.dev"));
        assertThat(req.devToken()).isNotBlank();

        service.confirmPasswordReset(
                new ConfirmPasswordResetCommand(req.devToken(), "NovaSenha456"));

        // Old password fails, new one passes
        assertThatThrownBy(() -> service.login(new LoginCommand("rs@nora.dev", "SenhaForte123")))
                .isInstanceOf(AuthException.InvalidCredentials.class);
        var ok = service.login(new LoginCommand("rs@nora.dev", "NovaSenha456"));
        assertThat(ok.user().id()).isEqualTo(sr.userId());
    }

    @Test
    void resetPasswordRevokesAllActiveRefreshTokens() {
        // Regression: confirmPasswordReset MUST invalidate every active refresh token.
        // Without it, an attacker with a stolen refresh keeps the session for 30 days after
        // the victim resets the password. OWASP recommendation.
        SignupResult sr =
                service.signup(new SignupCommand("revoke@nora.dev", "SenhaForte123", "Revoke"));
        service.verifyEmail(sr.emailVerificationDevToken());

        var loginA = service.login(new LoginCommand("revoke@nora.dev", "SenhaForte123"));
        var loginB = service.login(new LoginCommand("revoke@nora.dev", "SenhaForte123"));

        // Both sessions work before the reset; they rotate into a new active refresh.
        var r1 = service.refresh(loginA.refreshTokenPlain());
        var r2 = service.refresh(loginB.refreshTokenPlain());
        assertThat(r1.user().id()).isEqualTo(sr.userId());
        assertThat(r2.user().id()).isEqualTo(sr.userId());

        var req = service.requestPasswordReset(new RequestPasswordResetCommand("revoke@nora.dev"));
        service.confirmPasswordReset(
                new ConfirmPasswordResetCommand(req.devToken(), "NovaSenha456"));

        // The post-rotation active refreshes stop working too.
        assertThatThrownBy(() -> service.refresh(r1.refreshTokenPlain()))
                .isInstanceOf(AuthException.RefreshTokenInvalid.class);
        assertThatThrownBy(() -> service.refresh(r2.refreshTokenPlain()))
                .isInstanceOf(AuthException.RefreshTokenInvalid.class);
    }

    @Test
    void requestingNewResetInvalidatesPreviousTokens() {
        SignupResult sr = service.signup(new SignupCommand("two@nora.dev", "SenhaForte123", "TW"));
        service.verifyEmail(sr.emailVerificationDevToken());

        var first = service.requestPasswordReset(new RequestPasswordResetCommand("two@nora.dev"));
        service.requestPasswordReset(new RequestPasswordResetCommand("two@nora.dev"));

        assertThatThrownBy(
                        () ->
                                service.confirmPasswordReset(
                                        new ConfirmPasswordResetCommand(
                                                first.devToken(), "OutraSenha789")))
                .isInstanceOf(AuthException.TokenInvalid.class);
    }

    // ---------- fakes ----------

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

    static class FakeEmailSender implements EmailSender {
        String lastVerifyTo, lastVerifyLink, lastResetTo, lastResetLink;
        String lastSignupAttemptTo, lastSignupAttemptSignInUrl;

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
            lastSignupAttemptTo = toEmail;
            lastSignupAttemptSignInUrl = signInUrl;
        }

        @Override
        public void sendInvitation(
                String toEmail,
                String tenantName,
                String invitedByName,
                String acceptUrl,
                int expiresInDays) {
            // AuthService does not use invite; no-op here.
        }

        @Override
        public void sendWorkflowNotification(String toEmail, String subject, String htmlBody) {
            // AuthService does not use workflow notification; no-op here.
        }
    }

    static class FakeTokenGenerator implements SecureTokenGenerator {
        int seq;

        @Override
        public GeneratedToken generate() {
            String raw = "raw-token-" + (++seq);
            return new GeneratedToken(raw, hash(raw));
        }

        @Override
        public String hash(String rawToken) {
            return "hash:" + rawToken;
        }
    }

    /** Fake JWT issuer: encodes a unique suffix to tell tokens apart across calls. */
    static class FakeJwtIssuer implements JwtIssuer {
        int seq;

        @Override
        public String issue(User user, List<String> roles, Duration ttl) {
            return "jwt-" + user.id() + "-" + (++seq);
        }
    }

    /** Runs the send inline, so a test can assert on it without a thread hop. */
    static class InlineEmailDispatcher implements EmailDispatcher {
        @Override
        public void dispatchAfterCommit(Runnable send) {
            send.run();
        }
    }

    /** Counts {@code matches} calls so a test can check both login branches pay for one. */
    static class PlainHasher implements PasswordHasher {
        int matchCalls;

        @Override
        public String hash(String raw) {
            return "h:" + raw;
        }

        @Override
        public boolean matches(String raw, String hash) {
            matchCalls++;
            return hash.equals("h:" + raw);
        }
    }

    static class InMemoryTenantRepo implements TenantRepository {
        private final Map<UUID, Tenant> byId = new LinkedHashMap<>();

        @Override
        public java.util.List<UUID> allActiveTenantIds() {
            return java.util.List.copyOf(byId.keySet());
        }

        private final Map<String, UUID> bySlug = new HashMap<>();

        @Override
        public Optional<Tenant> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public boolean existsBySlug(String slug) {
            return bySlug.containsKey(slug);
        }

        @Override
        public Tenant save(Tenant t) {
            byId.put(t.id(), t);
            bySlug.put(t.slug(), t.id());
            return t;
        }

        @Override
        public void hardDelete(UUID tenantId) {
            Tenant removed = byId.remove(tenantId);
            if (removed != null) {
                bySlug.remove(removed.slug());
            }
        }

        Optional<Tenant> byId(UUID id) {
            return findById(id);
        }
    }

    static class InMemoryUserRepo implements UserRepository {
        private final Map<UUID, User> byId = new LinkedHashMap<>();
        private final java.util.Set<UUID> rootIds = new java.util.HashSet<>();

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

        Optional<User> byId(UUID id) {
            return findById(id);
        }
    }

    static class InMemoryTokenRepo implements OneTimeTokenRepository {
        private final List<OneTimeToken> all = new ArrayList<>();

        @Override
        public OneTimeToken save(OneTimeToken token) {
            all.removeIf(t -> t.id().equals(token.id()));
            all.add(token);
            return token;
        }

        @Override
        public Optional<OneTimeToken> findByTokenHashAndPurpose(String hash, Purpose p) {
            return all.stream()
                    .filter(t -> t.purpose() == p && t.tokenHash().equals(hash))
                    .findFirst();
        }

        @Override
        public int invalidateActiveForUser(UUID userId, Purpose p, Instant now) {
            int count = 0;
            for (OneTimeToken t : all) {
                if (t.userId().equals(userId) && t.purpose() == p && !t.isConsumed()) {
                    t.consume(now);
                    count++;
                }
            }
            return count;
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

    static class RecordingAuditPort implements br.com.nora.api.application.ports.AuditPort {
        final List<String> actions = new ArrayList<>();

        @Override
        public void record(
                UUID tenantId,
                UUID actorUserId,
                String action,
                String targetType,
                UUID targetId,
                java.util.Map<String, Object> payload) {
            actions.add(action);
        }
    }
}
