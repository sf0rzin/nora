package br.com.nora.api.application.ports;

import br.com.nora.api.domain.integration.IntegrationConnection;
import br.com.nora.api.domain.integration.IntegrationProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for OAuth connections. Every query requires tenantId (ADR 0002). The adapter is
 * responsible for encrypting/decrypting tokens at rest — the port speaks cleartext token.
 */
public interface IntegrationConnectionRepository {

    /** Inserts or replaces the (tenant, provider) connection — reconnecting swaps the tokens. */
    void upsert(IntegrationConnection connection);

    Optional<IntegrationConnection> findByTenantAndProvider(
            UUID tenantId, IntegrationProvider provider);

    List<IntegrationConnection> listByTenant(UUID tenantId);

    /** Updates only the tokens (refresh rotation). */
    void updateTokens(IntegrationConnection connection);

    void delete(UUID tenantId, IntegrationProvider provider);
}
