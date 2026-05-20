package br.com.nora.api.domain.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Refresh token stateful com rotation + reuse detection (audit follow-up #3).
 *
 * <p>Token cru existe apenas no cookie httpOnly do navegador e em memoria efemera durante o login.
 * Persistencia armazena somente o SHA-256 hex ({@code tokenHash}). Vazamento do banco nao permite
 * reuso direto dos tokens.
 *
 * <p>Estado:
 *
 * <ul>
 *   <li>{@code revokedAt} != null -> revogado e nunca mais valido.
 *   <li>{@code expiresAt} no passado -> expirado pelo TTL longo (30 dias por padrao).
 *   <li>{@code lastUsedAt} marca o ultimo refresh.
 *   <li>{@code familyId} agrupa tokens da mesma cadeia de rotacao. Em /auth/refresh, geramos um
 *       filho com mesma family e revogamos o pai. Se um token revogado e reapresentado, revogamos a
 *       family inteira (reuse detection).
 *   <li>{@code replacedById} aponta para o sucessor quando o token e rotacionado (audit).
 * </ul>
 */
public final class RefreshToken {

    private final UUID id;
    private final UUID userId;
    private final UUID tenantId;
    private final String tokenHash;
    private final Instant expiresAt;
    private Instant revokedAt;
    private final Instant createdAt;
    private Instant lastUsedAt;
    private final UUID familyId;
    private UUID replacedById;

    public RefreshToken(
            UUID id,
            UUID userId,
            UUID tenantId,
            String tokenHash,
            Instant expiresAt,
            Instant revokedAt,
            Instant createdAt,
            Instant lastUsedAt,
            UUID familyId,
            UUID replacedById) {
        this.id = Objects.requireNonNull(id);
        this.userId = Objects.requireNonNull(userId);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.tokenHash = Objects.requireNonNull(tokenHash);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        this.revokedAt = revokedAt;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.lastUsedAt = lastUsedAt;
        this.familyId = Objects.requireNonNull(familyId);
        this.replacedById = replacedById;
    }

    /** Cria um refresh raiz: {@code familyId} = {@code id} (cada login comeca uma cadeia nova). */
    public static RefreshToken issueRoot(
            UUID id,
            UUID userId,
            UUID tenantId,
            String tokenHash,
            Instant createdAt,
            Instant expiresAt) {
        return new RefreshToken(
                id, userId, tenantId, tokenHash, expiresAt, null, createdAt, null, id, null);
    }

    /**
     * Cria um filho na mesma cadeia: {@code familyId} herdado, {@code replacedById} parent->child
     * deve ser setado externamente apos a criacao.
     */
    public static RefreshToken issueChild(
            UUID id,
            UUID userId,
            UUID tenantId,
            String tokenHash,
            Instant createdAt,
            Instant expiresAt,
            UUID familyId) {
        return new RefreshToken(
                id, userId, tenantId, tokenHash, expiresAt, null, createdAt, null, familyId, null);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isActive(Instant now) {
        return !isRevoked() && !isExpired(now);
    }

    /** Idempotente: revogar duas vezes mantem o primeiro timestamp. */
    public void revoke(Instant now) {
        if (this.revokedAt == null) {
            this.revokedAt = Objects.requireNonNull(now);
        }
    }

    public void markUsed(Instant now) {
        this.lastUsedAt = Objects.requireNonNull(now);
    }

    public void markReplacedBy(UUID childId) {
        this.replacedById = Objects.requireNonNull(childId);
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastUsedAt() {
        return lastUsedAt;
    }

    public UUID familyId() {
        return familyId;
    }

    public UUID replacedById() {
        return replacedById;
    }
}
