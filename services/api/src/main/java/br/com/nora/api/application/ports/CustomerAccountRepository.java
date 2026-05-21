package br.com.nora.api.application.ports;

import br.com.nora.api.domain.customer.CustomerAccount;
import java.util.Optional;
import java.util.UUID;

/** Persistencia de Customer Account (ADR 0015). Sempre escopada por tenant. */
public interface CustomerAccountRepository {

    CustomerAccount save(CustomerAccount account);

    /**
     * Busca por {@code (tenantId, LOWER(name))} — suporta o get-or-create do account (dedup
     * case-insensitive garantido pelo UNIQUE index {@code idx_customer_accounts_tenant_name}).
     */
    Optional<CustomerAccount> findByTenantAndLowerName(UUID tenantId, String name);

    Optional<CustomerAccount> findById(UUID id, UUID tenantId);
}
