package br.com.nora.api.application.ports;

import br.com.nora.api.domain.customer.CustomerAccount;
import java.util.Optional;
import java.util.UUID;

/** Customer Account persistence (ADR 0015). Always scoped by tenant. */
public interface CustomerAccountRepository {

    CustomerAccount save(CustomerAccount account);

    /**
     * Lookup by {@code (tenantId, LOWER(name))} — supports the account get-or-create
     * (case-insensitive dedup guaranteed by the UNIQUE index {@code
     * idx_customer_accounts_tenant_name}).
     */
    Optional<CustomerAccount> findByTenantAndLowerName(UUID tenantId, String name);

    Optional<CustomerAccount> findById(UUID id, UUID tenantId);

    /**
     * Links (idempotently) a meeting to an account in the {@code meeting_account_links} table.
     * Re-linking the same pair is a no-op (composite PK (meeting_id, customer_account_id)).
     */
    void linkMeeting(UUID meetingId, UUID accountId, UUID tenantId);
}
