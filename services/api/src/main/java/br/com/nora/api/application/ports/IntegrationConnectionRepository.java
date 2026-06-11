package br.com.nora.api.application.ports;

import br.com.nora.api.domain.integration.IntegrationConnection;
import br.com.nora.api.domain.integration.IntegrationProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistência das conexões OAuth. Toda consulta exige tenantId (ADR 0002). O adapter é
 * responsável por cifrar/decifrar tokens em repouso — a porta fala token em claro.
 */
public interface IntegrationConnectionRepository {

    /** Insere ou substitui a conexão do (tenant, provider) — reconectar troca os tokens. */
    void upsert(IntegrationConnection connection);

    Optional<IntegrationConnection> findByTenantAndProvider(
            UUID tenantId, IntegrationProvider provider);

    List<IntegrationConnection> listByTenant(UUID tenantId);

    /** Atualiza apenas os tokens (refresh rotation). */
    void updateTokens(IntegrationConnection connection);

    void delete(UUID tenantId, IntegrationProvider provider);
}
