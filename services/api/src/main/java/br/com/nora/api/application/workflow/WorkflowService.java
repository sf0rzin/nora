package br.com.nora.api.application.workflow;

import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.ports.WorkflowExecutionRepository;
import br.com.nora.api.application.ports.WorkflowRepository;
import br.com.nora.api.application.ports.WorkflowScheduleRepository;
import br.com.nora.api.domain.workflow.ScheduleSpec;
import br.com.nora.api.domain.workflow.TriggerType;
import br.com.nora.api.domain.workflow.Workflow;
import br.com.nora.api.domain.workflow.WorkflowExecution;
import br.com.nora.api.domain.workflow.WorkflowSchedule;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD + operations for NORA Flows workflows. Every operation is scoped by the principal's tenant
 * (ADR 0002 + RLS). The triggerType is derived from the trigger node in definition_json on save —
 * the API does not receive it separately (a single source of truth: the canvas graph).
 *
 * <p>A save is also where a {@code schedule.cron} flow's run state is written (US75, ADR 0047): the
 * trigger node's vocabulary compiles to a cron expression and lands in {@code workflow_schedules},
 * and a flow that stops being scheduled has its row deleted. This is the only place a principal
 * exists in a scheduled flow's life, which is why it is also where the authorization decision is
 * made — the timer that fires it later has no principal at all.
 */
@Service
public class WorkflowService {

    /** Maximum number of executions returned in the history (most recent first). */
    public static final int EXECUTIONS_LIMIT = 50;

    private final WorkflowRepository workflows;
    private final WorkflowExecutionRepository executions;
    private final WorkflowScheduleRepository schedules;
    private final WorkflowDefinitionParser parser;
    private final ActionRegistry actions;
    private final WorkflowEngine engine;
    private final WorkflowContextFactory contextFactory;
    private final ScheduleOccurrences occurrences;
    private final Clock clock;

    public WorkflowService(
            WorkflowRepository workflows,
            WorkflowExecutionRepository executions,
            WorkflowScheduleRepository schedules,
            WorkflowDefinitionParser parser,
            ActionRegistry actions,
            WorkflowEngine engine,
            WorkflowContextFactory contextFactory,
            ScheduleOccurrences occurrences,
            Clock clock) {
        this.workflows = workflows;
        this.executions = executions;
        this.schedules = schedules;
        this.parser = parser;
        this.actions = actions;
        this.engine = engine;
        this.contextFactory = contextFactory;
        this.occurrences = occurrences;
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
        syncSchedule(workflow, validated.schedule(), now);
        return workflow;
    }

    @Transactional
    public Workflow update(
            UUID id, UUID tenantId, String name, JsonNode definition, boolean active) {
        Workflow existing = get(id, tenantId);
        Validated validated = validate(name, definition);
        OffsetDateTime now = now();
        Workflow updated =
                new Workflow(
                        existing.id(),
                        existing.tenantId(),
                        validated.name(),
                        validated.triggerType(),
                        validated.definitionJson(),
                        active,
                        existing.createdAt(),
                        now);
        workflows.update(updated);
        syncSchedule(updated, validated.schedule(), now);
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
     * Canvas "Test": runs the workflow NOW (synchronous), against the tenant's most recent
     * COMPLETED meeting (or sample data), and returns the execution with the log. Deliberately
     * not @Transactional: actions call external services (e-mail) and each engine step persists in
     * its own short transactions.
     */
    public WorkflowExecution test(UUID id, UUID tenantId) {
        Workflow workflow = get(id, tenantId);
        WorkflowEventContext ctx =
                contextFactory.latestCompletedOrSample(tenantId, workflow.triggerType().wire());
        return engine.execute(workflow, ctx);
    }

    private record Validated(
            String name, TriggerType triggerType, String definitionJson, ScheduleSpec schedule) {}

    private Validated validate(String name, JsonNode definition) {
        if (name == null || name.isBlank()) {
            throw new WorkflowException.InvalidName();
        }
        if (definition == null || definition.isNull()) {
            throw new WorkflowException.InvalidDefinition("definition is required");
        }
        String canonical = parser.canonicalJson(definition);
        WorkflowDefinition parsed = parser.parse(canonical, actions.types());
        TriggerType triggerType = TriggerType.fromWire(parsed.triggerNode().type());
        // The parser has already validated the vocabulary and thrown a 422 if it did not hold, so
        // re-reading it here cannot fail on a definition that got this far.
        ScheduleSpec schedule =
                triggerType == TriggerType.SCHEDULE_CRON
                        ? ScheduleSpec.parse(parsed.triggerNode().params())
                        : null;
        return new Validated(name.trim(), triggerType, canonical, schedule);
    }

    /**
     * Keeps {@code workflow_schedules} in step with the saved graph. A flow that stopped being
     * scheduled has its row deleted rather than left behind, because a stale row would keep a timer
     * pointed at a trigger the definition no longer declares.
     *
     * <p>The row carries the compiled cron expression, the zone it is evaluated in, and a first
     * occurrence computed from now. {@code windowFrom} starts at the save instant on a new row —
     * never at the beginning of time — so a schedule created today does not immediately fan out
     * over the tenant's entire history. The adapter's upsert is what decides whether an existing
     * row keeps its running state; see {@link WorkflowScheduleRepository#upsert}.
     */
    private void syncSchedule(Workflow workflow, ScheduleSpec schedule, OffsetDateTime now) {
        if (schedule == null) {
            schedules.deleteByWorkflowId(workflow.id(), workflow.tenantId());
            return;
        }
        String cron = schedule.cron();
        OffsetDateTime nextFireAt = occurrences.nextAfter(cron, now);
        if (nextFireAt == null) {
            // Unreachable through the parser: the vocabulary only compiles to expressions that
            // always have a next occurrence. Refusing the save is the only honest response left —
            // persisting a schedule that can never fire is exactly the defect US75 closed.
            throw new WorkflowException.InvalidDefinition(
                    "this schedule has no next occurrence and would never run");
        }
        schedules.upsert(
                new WorkflowSchedule(
                        workflow.id(),
                        workflow.tenantId(),
                        cron,
                        occurrences.zoneId(),
                        nextFireAt,
                        now,
                        null,
                        null,
                        null));
    }

    private OffsetDateTime now() {
        return clock.now().atOffset(ZoneOffset.UTC);
    }
}
