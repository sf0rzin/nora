package br.com.nora.api.application.ports;

import br.com.nora.api.domain.workflow.TriggerType;
import br.com.nora.api.domain.workflow.Workflow;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Porta de persistência dos workflows do NORA Flows. Toda consulta exige tenantId (ADR 0002). */
public interface WorkflowRepository {

    void create(Workflow workflow);

    void update(Workflow workflow);

    Optional<Workflow> findByIdAndTenant(UUID id, UUID tenantId);

    /** Lista todos os workflows do tenant, mais recentes primeiro. */
    List<Workflow> listByTenant(UUID tenantId);

    /** Match do engine: workflows ATIVOS do tenant para um gatilho. Caminho quente do listener. */
    List<Workflow> findActiveByTenantAndTrigger(UUID tenantId, TriggerType triggerType);

    void delete(UUID id, UUID tenantId);
}
