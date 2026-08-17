package br.com.nora.api.infrastructure.persistence.identity;

import br.com.nora.api.application.ports.McpTokenRepository;
import br.com.nora.api.domain.identity.McpToken;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class McpTokenRepositoryAdapter implements McpTokenRepository {

    private final McpTokenJpaRepository jpa;

    public McpTokenRepositoryAdapter(McpTokenJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional
    public McpToken save(McpToken token) {
        McpTokenJpaEntity entity =
                jpa.findById(token.id())
                        .map(
                                existing -> {
                                    existing.setRevokedAt(token.revokedAt());
                                    existing.setLastUsedAt(token.lastUsedAt());
                                    return existing;
                                })
                        .orElseGet(
                                () ->
                                        new McpTokenJpaEntity(
                                                token.id(),
                                                token.tenantId(),
                                                token.userId(),
                                                token.name(),
                                                token.tokenHash(),
                                                token.createdAt(),
                                                token.expiresAt(),
                                                token.revokedAt(),
                                                token.lastUsedAt()));
        return toDomain(jpa.save(entity));
    }

    @Override
    @Transactional
    public Optional<McpToken> findByTokenHash(String tokenHash) {
        return jpa.findByTokenHash(tokenHash).map(McpTokenRepositoryAdapter::toDomain);
    }

    @Override
    @Transactional
    public List<McpToken> findByOwner(UUID tenantId, UUID userId) {
        return jpa.findAllByTenantIdAndUserIdOrderByCreatedAtDesc(tenantId, userId).stream()
                .map(McpTokenRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public Optional<McpToken> findByIdAndOwner(UUID id, UUID tenantId, UUID userId) {
        return jpa.findByIdAndTenantIdAndUserId(id, tenantId, userId)
                .map(McpTokenRepositoryAdapter::toDomain);
    }

    private static McpToken toDomain(McpTokenJpaEntity e) {
        return new McpToken(
                e.getId(),
                e.getTenantId(),
                e.getUserId(),
                e.getName(),
                e.getTokenHash(),
                e.getCreatedAt(),
                e.getExpiresAt(),
                e.getRevokedAt(),
                e.getLastUsedAt());
    }
}
