package br.com.nora.api.application.ports;

import br.com.nora.api.domain.workflow.TriggerType;
import br.com.nora.api.domain.workflow.Workflow;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for NORA Flows workflows. Every query requires tenantId (ADR 0002). */
public interface WorkflowRepository {

    void create(Workflow workflow);

    void update(Workflow workflow);

    Optional<Workflow> findByIdAndTenant(UUID id, UUID tenantId);

    /** Lists all of the tenant's workflows, most recent first. */
    List<Workflow> listByTenant(UUID tenantId);

    /** Engine match: the tenant's ACTIVE workflows for a trigger. Hot path of the listener. */
    List<Workflow> findActiveByTenantAndTrigger(UUID tenantId, TriggerType triggerType);

    void delete(UUID id, UUID tenantId);
}
