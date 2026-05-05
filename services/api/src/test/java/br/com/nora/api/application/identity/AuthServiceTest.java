package br.com.nora.api.application.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.nora.api.application.identity.AuthService.AuthSettings;
import br.com.nora.api.application.identity.AuthService.ConfirmPasswordResetCommand;
import br.com.nora.api.application.identity.AuthService.LoginCommand;
import br.com.nora.api.application.identity.AuthService.RequestPasswordResetCommand;
import br.com.nora.api.application.identity.AuthService.SignupCommand;
import br.com.nora.api.application.identity.AuthService.SignupResult;
import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.ports.EmailSender;
import br.com.nora.api.application.ports.OneTimeTokenRepository;
import br.com.nora.api.application.ports.PasswordHasher;
import br.com.nora.api.application.ports.SecureTokenGenerator;
import br.com.nora.api.application.ports.TenantRepository;
import br.com.nora.api.application.ports.UserRepository;
import br.com.nora.api.domain.identity.Email;
import br.com.nora.api.domain.identity.OneTimeToken;
import br.com.nora.api.domain.identity.OneTimeToken.Purpose;
import br.com.nora.api.domain.identity.User;
import br.com.nora.api.domain.tenant.Tenant;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Cobre US01-US04 com fakes em memoria, sem Spring nem banco. */
class AuthServiceTest {

    private FakeClock clock;
    private FakeEmailSender mail;
    private FakeTokenGenerator tokens;
    private InMemoryTenantRepo tenants;
    private InMemoryUserRepo users;
    private InMemoryTokenRepo tokenRepo;
    private AuthService service;

    @BeforeEach
    void setUp() {
        clock = new FakeClock(Instant.parse("2026-05-04T10:00:00Z"));
        mail = new FakeEmailSender();
        tokens = new FakeTokenGenerator();
        tenants = new InMemoryTenantRepo();
        users = new InMemoryUserRepo();
        tokenRepo = new InMemoryTokenRepo();
        service =
                new AuthService(
                        tenants,
                        users,
                        tokenRepo,
                        new PlainHasher(),
                        tokens,
                        mail,
                        clock,
                        new AuthSettings(
                                "http://localhost:3000",
                                Duration.ofHours(24),
                                Duration.ofHours(1),
                                Duration.ofHours(1),
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

        var result = service.login(new LoginCommand("ok@nora.dev", "SenhaForte123"));
        assertThat(result.user().email().value()).isEqualTo("ok@nora.dev");
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

        Optional<User> byId(UUID id) {
            return findById(id);
        }
    }

    static class InMemoryTokenRepo implements OneTimeTokenRepository {
        private final List<OneTimeToken> all = new java.util.ArrayList<>();

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
}
