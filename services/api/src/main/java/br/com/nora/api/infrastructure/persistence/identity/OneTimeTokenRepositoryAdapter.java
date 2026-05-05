package br.com.nora.api.infrastructure.persistence.identity;

import br.com.nora.api.application.ports.OneTimeTokenRepository;
import br.com.nora.api.domain.identity.OneTimeToken;
import br.com.nora.api.domain.identity.OneTimeToken.Purpose;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OneTimeTokenRepositoryAdapter implements OneTimeTokenRepository {

    private final EmailVerificationTokenJpaRepository emailRepo;
    private final PasswordResetTokenJpaRepository pwdRepo;

    public OneTimeTokenRepositoryAdapter(
            EmailVerificationTokenJpaRepository emailRepo,
            PasswordResetTokenJpaRepository pwdRepo) {
        this.emailRepo = emailRepo;
        this.pwdRepo = pwdRepo;
    }

    @Override
    @Transactional
    public OneTimeToken save(OneTimeToken token) {
        return switch (token.purpose()) {
            case EMAIL_VERIFICATION -> {
                EmailVerificationTokenJpaEntity entity =
                        emailRepo
                                .findById(token.id())
                                .map(
                                        e -> {
                                            e.setConsumedAt(token.consumedAt());
                                            return e;
                                        })
                                .orElseGet(
                                        () ->
                                                new EmailVerificationTokenJpaEntity(
                                                        token.id(),
                                                        token.userId(),
                                                        token.tenantId(),
                                                        token.tokenHash(),
                                                        token.expiresAt(),
                                                        token.consumedAt(),
                                                        token.createdAt()));
                EmailVerificationTokenJpaEntity saved = emailRepo.save(entity);
                yield toDomain(saved, Purpose.EMAIL_VERIFICATION);
            }
            case PASSWORD_RESET -> {
                PasswordResetTokenJpaEntity entity =
                        pwdRepo.findById(token.id())
                                .map(
                                        e -> {
                                            e.setConsumedAt(token.consumedAt());
                                            return e;
                                        })
                                .orElseGet(
                                        () ->
                                                new PasswordResetTokenJpaEntity(
                                                        token.id(),
                                                        token.userId(),
                                                        token.tenantId(),
                                                        token.tokenHash(),
                                                        token.expiresAt(),
                                                        token.consumedAt(),
                                                        token.createdAt()));
                PasswordResetTokenJpaEntity saved = pwdRepo.save(entity);
                yield toDomain(saved, Purpose.PASSWORD_RESET);
            }
        };
    }

    @Override
    @Transactional
    public Optional<OneTimeToken> findByTokenHashAndPurpose(String tokenHash, Purpose purpose) {
        return switch (purpose) {
            case EMAIL_VERIFICATION ->
                    emailRepo.findByTokenHash(tokenHash).map(e -> toDomain(e, purpose));
            case PASSWORD_RESET ->
                    pwdRepo.findByTokenHash(tokenHash).map(e -> toDomain(e, purpose));
        };
    }

    @Override
    @Transactional
    public int invalidateActiveForUser(UUID userId, Purpose purpose, Instant now) {
        return switch (purpose) {
            case EMAIL_VERIFICATION -> emailRepo.markAllConsumed(userId, now);
            case PASSWORD_RESET -> pwdRepo.markAllConsumed(userId, now);
        };
    }

    private OneTimeToken toDomain(OneTimeTokenJpaEntity e, Purpose purpose) {
        return new OneTimeToken(
                e.getId(),
                e.getUserId(),
                e.getTenantId(),
                e.getTokenHash(),
                e.getExpiresAt(),
                e.getConsumedAt(),
                e.getCreatedAt(),
                purpose);
    }
}
