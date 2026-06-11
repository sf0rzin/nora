package br.com.nora.api.application.workflow;

import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.ports.WorkflowExecutionRepository;
import br.com.nora.api.application.ports.WorkflowRepository;
import br.com.nora.api.domain.workflow.TriggerType;
import br.com.nora.api.domain.workflow.Workflow;
import br.com.nora.api.domain.workflow.WorkflowExecution;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD + operações dos workflows do NORA Flows. Toda operação é escopada pelo tenant do principal
 * (ADR 0002 + RLS). O triggerType é derivado do nó de gatilho do definition_json no save — a API
 * não o recebe separado (uma fonte de verdade só: o grafo do canvas).
 */
@Service
public class WorkflowService {

    /** Máximo de execuções retornadas no histórico (mais recentes primeiro). */
    public static final int EXECUTIONS_LIMIT = 50;

    private final WorkflowRepository workflows;
    private final WorkflowExecutionRepository executions;
    private final WorkflowDefinitionParser parser;
    private final ActionRegistry actions;
    private final WorkflowEngine engine;
    private final WorkflowContextFactory contextFactory;
    private final Clock clock;

    public WorkflowService(
            WorkflowRepository workflows,
            WorkflowExecutionRepository executions,
            WorkflowDefinitionParser parser,
            ActionRegistry actions,
            WorkflowEngine engine,
            WorkflowContextFactory contextFactory,
            Clock clock) {
        this.workflows = workflows;
        this.executions = executions;
        this.parser = parser;
        this.actions = actions;
        this.engine = engine;
        this.contextFactory = contextFactory;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Workflow> list(UUID tenantId) {
        return workflows.listByTenant(tenantId);
    }

    @Transactional(readOnly = true)
    public Workflow get(UUID id, UUID tenantId) {
        return workflows
                .findByIdAndTenant(id, tenantId)
                .orElseThrow(WorkflowException.NotFound::new);
    }

    @Transactional
    public Workflow create(UUID tenantId, String name, JsonNode definition, boolean active) {
        Validated validated = validate(name, definition);
        OffsetDateTime now = now();
        Workflow workflow =
                new Workflow(
                        UUID.randomUUID(),
                        tenantId,
                        validated.name(),
                        validated.triggerType(),
                        validated.definitionJson(),
                        active,
                        now,
                        now);
        workflows.create(workflow);
        return workflow;
    }

    @Transactional
    public Workflow update(
            UUID id, UUID tenantId, String name, JsonNode definition, boolean active) {
        Workflow existing = get(id, tenantId);
        Validated validated = validate(name, definition);
        Workflow updated =
                new Workflow(
                        existing.id(),
                        existing.tenantId(),
                        validated.name(),
                        validated.triggerType(),
                        validated.definitionJson(),
                        active,
                        existing.createdAt(),
                        now());
        workflows.update(updated);
        return updated;
    }

    @Transactional
    public void delete(UUID id, UUID tenantId) {
        get(id, tenantId);
        workflows.delete(id, tenantId);
    }

    @Transactional(readOnly = true)
    public List<WorkflowExecution> listExecutions(UUID workflowId, UUID tenantId) {
        get(workflowId, tenantId);
        return executions.listByWorkflow(workflowId, tenantId, EXECUTIONS_LIMIT);
    }

    /**
     * "Testar" do canvas: executa o workflow AGORA (síncrono), contra a reunião COMPLETED mais
     * recente do tenant (ou dados de exemplo), e retorna a execução com o log. Sem @Transactional
     * de propósito: ações chamam serviços externos (e-mail) e cada passo do engine persiste em
     * transações curtas próprias.
     */
    public WorkflowExecution test(UUID id, UUID tenantId) {
        Workflow workflow = get(id, tenantId);
        WorkflowEventContext ctx =
                contextFactory.latestCompletedOrSample(tenantId, workflow.triggerType().wire());
        return engine.execute(workflow, ctx);
    }

    private record Validated(String name, TriggerType triggerType, String definitionJson) {}

    private Validated validate(String name, JsonNode definition) {
        if (name == null || name.isBlank()) {
            throw new WorkflowException.InvalidName();
        }
        if (definition == null || definition.isNull()) {
            throw new WorkflowException.InvalidDefinition("definition é obrigatória");
        }
        String canonical = parser.canonicalJson(definition);
        WorkflowDefinition parsed = parser.parse(canonical, actions.types());
        TriggerType triggerType = TriggerType.fromWire(parsed.triggerNode().type());
        return new Validated(name.trim(), triggerType, canonical);
    }

    private OffsetDateTime now() {
        return clock.now().atOffset(ZoneOffset.UTC);
    }
}
