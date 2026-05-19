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
 * Cobre US01-US04 + refresh/logout (Round 2 / 1.3 A) com fakes em memoria, sem Spring nem banco.
 */
class AuthServiceTest {

    private FakeClock clock;
    private FakeEmailSender mail;
    private FakeTokenGenerator tokens;
    private InMemoryTenantRepo tenants;
    private InMemoryUserRepo users;
    private InMemoryTokenRepo tokenRepo;
    private InMemoryRefreshTokenRepo refreshRepo;
    private FakeJwtIssuer jwts;
    private AuthService service;

    @BeforeEach
    void setUp() {
        clock = new FakeClock(Instant.parse("2026-05-04T10:00:00Z"));
        mail = new FakeEmailSender();
        tokens = new FakeTokenGenerator();
        tenants = new InMemoryTenantRepo();
        users = new InMemoryUserRepo();
        tokenRepo = new InMemoryTokenRepo();
        refreshRepo = new InMemoryRefreshTokenRepo();
        jwts = new FakeJwtIssuer();
        service =
                new AuthService(
                        tenants,
                        users,
                        tokenRepo,
                        refreshRepo,
                        new PlainHasher(),
                        tokens,
                        jwts,
                        mail,
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

    @Test
    void signupRejectsDuplicateEmail() {
        service.signup(new SignupCommand("dup@nora.dev", "SenhaForte123", "X"));
        assertThatThrownBy(
                        () ->
                                service.signup(
                                        new SignupCommand("DUP@nora.dev", "SenhaForte123", "Y")))
                .isInstanceOf(AuthException.EmailAlreadyTaken.class);
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

    @Test
    void loginPersistsRefreshTokenHashOnly() {
        SignupResult sr = service.signup(new SignupCommand("rp@nora.dev", "SenhaForte123", "RP"));
        service.verifyEmail(sr.emailVerificationDevToken());

        LoginResult login = service.login(new LoginCommand("rp@nora.dev", "SenhaForte123"));

        // Confere que o que ficou em DB e o HASH, nao o plain.
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
    void refreshIssuesNewAccessTokenWithoutRotatingRefresh() {
        SignupResult sr = service.signup(new SignupCommand("rf@nora.dev", "SenhaForte123", "RF"));
        service.verifyEmail(sr.emailVerificationDevToken());
        LoginResult login = service.login(new LoginCommand("rf@nora.dev", "SenhaForte123"));

        // Avanca alguns minutos para garantir um JWT diferente.
        clock.advance(Duration.ofMinutes(1));
        RefreshResult refresh = service.refresh(login.refreshTokenPlain());

        assertThat(refresh.accessToken()).isNotBlank().isNotEqualTo(login.accessToken());
        assertThat(refresh.accessExpiresInSeconds()).isEqualTo(900L);
        // O refresh em DB ainda existe (nao rotacionado) e marcou last_used_at.
        assertThat(refreshRepo.all).hasSize(1);
        assertThat(refreshRepo.all.get(0).lastUsedAt()).isEqualTo(clock.now);
        // Pode ser usado de novo (sem rotacao).
        RefreshResult again = service.refresh(login.refreshTokenPlain());
        assertThat(again.accessToken()).isNotBlank();
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
        // Nenhum token: no-op silencioso.
        service.logout(null);
        service.logout("");
        service.logout("inexistente");
        assertThat(refreshRepo.all).isEmpty();
    }

    @Test
    void logoutRevokesOnlyOwnSessionNotAllUserSessions() {
        SignupResult sr = service.signup(new SignupCommand("mu@nora.dev", "SenhaForte123", "MU"));
        service.verifyEmail(sr.emailVerificationDevToken());
        LoginResult first = service.login(new LoginCommand("mu@nora.dev", "SenhaForte123"));
        LoginResult second = service.login(new LoginCommand("mu@nora.dev", "SenhaForte123"));

        service.logout(first.refreshTokenPlain());

        // Primeiro foi revogado.
        assertThatThrownBy(() -> service.refresh(first.refreshTokenPlain()))
                .isInstanceOf(AuthException.RefreshTokenInvalid.class);
        // Segundo continua valido.
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

        // Senha antiga falha, nova passa
        assertThatThrownBy(() -> service.login(new LoginCommand("rs@nora.dev", "SenhaForte123")))
                .isInstanceOf(AuthException.InvalidCredentials.class);
        var ok = service.login(new LoginCommand("rs@nora.dev", "NovaSenha456"));
        assertThat(ok.user().id()).isEqualTo(sr.userId());
    }

    @Test
    void resetPasswordRevokesAllActiveRefreshTokens() {
        // Regression: confirmPasswordReset DEVE invalidar todos os refresh tokens ativos.
        // Sem isso, atacante com refresh roubado mantem sessao por 30 dias apos a vitima
        // resetar a senha. OWASP recommendation.
        SignupResult sr =
                service.signup(new SignupCommand("revoke@nora.dev", "SenhaForte123", "Revoke"));
        service.verifyEmail(sr.emailVerificationDevToken());

        var loginA = service.login(new LoginCommand("revoke@nora.dev", "SenhaForte123"));
        var loginB = service.login(new LoginCommand("revoke@nora.dev", "SenhaForte123"));

        // Ambas sessoes funcionam antes do reset.
        var r1 = service.refresh(loginA.refreshTokenPlain());
        var r2 = service.refresh(loginB.refreshTokenPlain());
        assertThat(r1.user().id()).isEqualTo(sr.userId());
        assertThat(r2.user().id()).isEqualTo(sr.userId());

        var req = service.requestPasswordReset(new RequestPasswordResetCommand("revoke@nora.dev"));
        service.confirmPasswordReset(
                new ConfirmPasswordResetCommand(req.devToken(), "NovaSenha456"));

        // Refresh tokens antigos deixam de funcionar.
        assertThatThrownBy(() -> service.refresh(loginA.refreshTokenPlain()))
                .isInstanceOf(AuthException.RefreshTokenInvalid.class);
        assertThatThrownBy(() -> service.refresh(loginB.refreshTokenPlain()))
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
        public void sendInvitation(
                String toEmail,
                String tenantName,
                String invitedByName,
                String acceptUrl,
                int expiresInDays) {
            // AuthService nao usa invite; no-op aqui.
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

    /** Fake JWT issuer: codifica um sufixo unico para distinguir tokens entre chamadas. */
    static class FakeJwtIssuer implements JwtIssuer {
        int seq;

        @Override
        public String issue(User user, List<String> roles, Duration ttl) {
            return "jwt-" + user.id() + "-" + (++seq);
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

    static class InMemoryTenantRepo implements TenantRepository {
        private final Map<UUID, Tenant> byId = new LinkedHashMap<>();
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

        Optional<Tenant> byId(UUID id) {
            return findById(id);
        }
    }

    static class InMemoryUserRepo implements UserRepository {
        private final Map<UUID, User> byId = new LinkedHashMap<>();
        private final java.util.Set<UUID> rootIds = new java.util.HashSet<>();

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
    }
}
