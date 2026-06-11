package br.com.nora.api.infrastructure.persistence.integration;

import br.com.nora.api.application.ports.IntegrationConnectionRepository;
import br.com.nora.api.domain.integration.IntegrationConnection;
import br.com.nora.api.domain.integration.IntegrationProvider;
import br.com.nora.api.infrastructure.integration.TokenCipher;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adapter JDBC das conexões OAuth (V024). Tokens são cifrados aqui (TokenCipher, AES-GCM) — a porta
 * fala token em claro e o banco só vê {@code enc:v1:...}. Upsert via ON CONFLICT na unique
 * (tenant_id, provider): reconectar substitui tokens/conta preservando a linha.
 */
@Repository
public class IntegrationConnectionRepositoryAdapter implements IntegrationConnectionRepository {

    private static final String SELECT_COLUMNS =
            "SELECT c.id, c.tenant_id, c.user_id, c.provider, c.scopes, c.external_account,"
                    + " c.access_token, c.refresh_token, c.expires_at, c.created_at, c.updated_at"
                    + " FROM integration_connections c ";

    @PersistenceContext private EntityManager em;

    private final TokenCipher cipher;

    public IntegrationConnectionRepositoryAdapter(TokenCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    @Transactional
    public void upsert(IntegrationConnection c) {
        em.createNativeQuery(
                        "INSERT INTO integration_connections (id, tenant_id, user_id, provider,"
                                + " scopes, external_account, access_token, refresh_token, expires_at,"
                                + " created_at, updated_at) VALUES (:id, :tenantId, :userId, :provider,"
                                + " :scopes, :externalAccount, :accessToken, :refreshToken, :expiresAt,"
                                + " :createdAt, :updatedAt) ON CONFLICT (tenant_id, provider) DO UPDATE"
                                + " SET user_id = EXCLUDED.user_id, scopes = EXCLUDED.scopes,"
                                + " external_account = EXCLUDED.external_account, access_token ="
                                + " EXCLUDED.access_token, refresh_token = EXCLUDED.refresh_token,"
                                + " expires_at = EXCLUDED.expires_at, updated_at = EXCLUDED.updated_at")
                .setParameter("id", c.id())
                .setParameter("tenantId", c.tenantId())
                .setParameter("userId", c.connectedByUserId())
                .setParameter("provider", c.provider().wire())
                .setParameter("scopes", c.scopes())
                .setParameter("externalAccount", c.externalAccount())
                .setParameter("accessToken", cipher.encrypt(c.accessToken()))
                .setParameter("refreshToken", cipher.encrypt(c.refreshToken()))
                .setParameter("expiresAt", toTimestamp(c.expiresAt()))
                .setParameter("createdAt", toTimestamp(c.createdAt()))
                .setParameter("updatedAt", toTimestamp(c.updatedAt()))
                .executeUpdate();
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Optional<IntegrationConnection> findByTenantAndProvider(
            UUID tenantId, IntegrationProvider provider) {
        var query =
                em.createNativeQuery(
                        SELECT_COLUMNS
                                + "WHERE c.tenant_id = :tenantId AND c.provider = :provider");
        query.setParameter("tenantId", tenantId);
        query.setParameter("provider", provider.wire());
        List<Object[]> rows = (List<Object[]>) query.getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.of(toConnection(rows.get(0)));
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<IntegrationConnection> listByTenant(UUID tenantId) {
        var query =
                em.createNativeQuery(
                        SELECT_COLUMNS + "WHERE c.tenant_id = :tenantId ORDER BY c.provider");
        query.setParameter("tenantId", tenantId);
        List<Object[]> rows = (List<Object[]>) query.getResultList();
        List<IntegrationConnection> result = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            result.add(toConnection(r));
        }
        return result;
    }

    @Override
    @Transactional
    public void updateTokens(IntegrationConnection c) {
        em.createNativeQuery(
                        "UPDATE integration_connections SET access_token = :accessToken,"
                                + " refresh_token = :refreshToken, expires_at = :expiresAt,"
                                + " updated_at = :updatedAt WHERE id = :id AND tenant_id ="
                                + " :tenantId")
                .setParameter("accessToken", cipher.encrypt(c.accessToken()))
                .setParameter("refreshToken", cipher.encrypt(c.refreshToken()))
                .setParameter("expiresAt", toTimestamp(c.expiresAt()))
                .setParameter("updatedAt", toTimestamp(c.updatedAt()))
                .setParameter("id", c.id())
                .setParameter("tenantId", c.tenantId())
                .executeUpdate();
    }

    @Override
    @Transactional
    public void delete(UUID tenantId, IntegrationProvider provider) {
        em.createNativeQuery(
                        "DELETE FROM integration_connections WHERE tenant_id = :tenantId AND"
                                + " provider = :provider")
                .setParameter("tenantId", tenantId)
                .setParameter("provider", provider.wire())
                .executeUpdate();
    }

    private IntegrationConnection toConnection(Object[] r) {
        return new IntegrationConnection(
                (UUID) r[0],
                (UUID) r[1],
                (UUID) r[2],
                IntegrationProvider.fromWire((String) r[3]),
                (String) r[4],
                (String) r[5],
                cipher.decrypt((String) r[6]),
                cipher.decrypt((String) r[7]),
                toOffset(r[8]),
                toOffset(r[9]),
                toOffset(r[10]));
    }

    private static OffsetDateTime toOffset(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime odt) {
            return odt;
        }
        if (value instanceof java.time.Instant instant) {
            return instant.atOffset(ZoneOffset.UTC);
        }
        return ((Timestamp) value).toInstant().atOffset(ZoneOffset.UTC);
    }

    private static Timestamp toTimestamp(OffsetDateTime value) {
        return value == null ? null : Timestamp.from(value.toInstant());
    }
}
