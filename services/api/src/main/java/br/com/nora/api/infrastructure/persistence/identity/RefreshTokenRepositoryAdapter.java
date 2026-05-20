package br.com.nora.api.infrastructure.persistence.identity;

import br.com.nora.api.application.ports.RefreshTokenRepository;
import br.com.nora.api.domain.identity.RefreshToken;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RefreshTokenRepositoryAdapter implements RefreshTokenRepository {

    private final RefreshTokenJpaRepository jpa;

    public RefreshTokenRepositoryAdapter(RefreshTokenJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional
    public RefreshToken save(RefreshToken token) {
        RefreshTokenJpaEntity entity =
                jpa.findById(token.id())
                        .map(
                                existing -> {
                                    existing.setRevokedAt(token.revokedAt());
                                    existing.setLastUsedAt(token.lastUsedAt());
                                    existing.setReplacedById(token.replacedById());
                                    return existing;
                                })
                        .orElseGet(
                                () ->
                                        new RefreshTokenJpaEntity(
                                                token.id(),
                                                token.userId(),
                                                token.tenantId(),
                                                token.tokenHash(),
                                                token.expiresAt(),
                                                token.revokedAt(),
                                                token.createdAt(),
                                                token.lastUsedAt(),
                                                token.familyId(),
                                                token.replacedById()));
        return toDomain(jpa.save(entity));
    }

    @Override
    @Transactional
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return jpa.findByTokenHash(tokenHash).map(RefreshTokenRepositoryAdapter::toDomain);
    }

    @Override
    @Transactional
    public List<RefreshToken> findActiveByUserId(UUID userId) {
        return jpa.findAllByUserIdAndRevokedAtIsNull(userId).stream()
                .map(RefreshTokenRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public int revokeAllByUserId(UUID userId, Instant now) {
        return jpa.revokeAllForUser(userId, now);
    }

    @Override
    @Transactional
    public int revokeAllByFamilyId(UUID familyId, Instant now) {
        return jpa.revokeAllForFamily(familyId, now);
    }

    private static RefreshToken toDomain(RefreshTokenJpaEntity e) {
        return new RefreshToken(
                e.getId(),
                e.getUserId(),
                e.getTenantId(),
                e.getTokenHash(),
                e.getExpiresAt(),
                e.getRevokedAt(),
                e.getCreatedAt(),
                e.getLastUsedAt(),
                e.getFamilyId(),
                e.getReplacedById());
    }
}
