package br.com.nora.api.application.workflow;

import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.ports.WorkflowExecutionRepository;
import br.com.nora.api.application.ports.WorkflowRepository;
import br.com.nora.api.application.workflow.WorkflowDefinition.Node;
import br.com.nora.api.domain.event.ActionItemCreatedEvent;
import br.com.nora.api.domain.event.MeetingAnalysisCompletedEvent;
import br.com.nora.api.domain.event.MeetingRiskDetectedEvent;
import br.com.nora.api.domain.workflow.TriggerType;
import br.com.nora.api.domain.workflow.Workflow;
import br.com.nora.api.domain.workflow.WorkflowExecution;
import br.com.nora.api.domain.workflow.WorkflowExecutionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * NORA Flows engine: matches domain events with the tenant's ACTIVE workflows, walks the graph
 * (trigger → conditions → actions) and records each step in {@code workflow_executions.log_json}.
 *
 * <p>Runs outside a request (async post-commit listener) — the caller is responsible for setting
 * the tenant in the {@code TenantRlsContext} (same as the analysis pipeline). Each execution is
 * isolated: a failure in one workflow does not stop the others from the same event.
 */
@Service
public class WorkflowEngine {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowEngine.class);

    private final WorkflowRepository workflows;
    private final WorkflowExecutionRepository executions;
    private final WorkflowDefinitionParser parser;
    private final ConditionEvaluator conditions;
    private final ActionRegistry actions;
    private final WorkflowContextFactory contextFactory;
    private final ObjectMapper mapper;
    private final Clock clock;

    public WorkflowEngine(
            WorkflowRepository workflows,
            WorkflowExecutionRepository executions,
            WorkflowDefinitionParser parser,
            ConditionEvaluator conditions,
            ActionRegistry actions,
            WorkflowContextFactory contextFactory,
            ObjectMapper mapper,
            Clock clock) {
        this.workflows = workflows;
        this.executions = executions;
        this.parser = parser;
        this.conditions = conditions;
        this.actions = actions;
        this.contextFactory = contextFactory;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Entry point of the anchor trigger: meeting analysis completed (post-commit). */
    public void onMeetingAnalysisCompleted(MeetingAnalysisCompletedEvent event) {
        dispatchForMeeting(
                event.tenantId(),
                event.meetingId(),
                TriggerType.MEETING_ANALYSIS_COMPLETED,
                event.occurredAt());
    }

    /** Entry point of the "action item created" trigger (one event per item; post-commit). */
    public void onActionItemCreated(ActionItemCreatedEvent event) {
        dispatchForMeeting(
                event.tenantId(),
                event.meetingId(),
                TriggerType.ACTION_ITEM_CREATED,
                event.occurredAt());
    }

    /** Entry point of the "risk detected" trigger (only HIGH severity is emitted; post-commit). */
    public void onMeetingRiskDetected(MeetingRiskDetectedEvent event) {
        dispatchForMeeting(
                event.tenantId(),
                event.meetingId(),
                TriggerType.MEETING_RISK_DETECTED,
                event.occurredAt());
    }

    /**
     * Matches the tenant's ACTIVE workflows with the trigger and executes each one. The meeting
     * snapshot is the same for any trigger derived from the analysis — only the eventType recorded
     * in the log changes.
     */
    private void dispatchForMeeting(
            UUID tenantId, UUID meetingId, TriggerType trigger, Instant occurredAt) {
        List<Workflow> active = workflows.findActiveByTenantAndTrigger(tenantId, trigger);
        if (active.isEmpty()) {
            return;
        }
        WorkflowEventContext ctx;
        try {
            ctx =
                    contextFactory.forMeeting(
                            tenantId,
                            meetingId,
                            trigger.wire(),
                            occurredAt == null ? now() : occurredAt.atOffset(ZoneOffset.UTC));
        } catch (RuntimeException ex) {
            LOG.error(
                    "Flows: event context unavailable meetingId={} tenantId={} cause={}",
                    meetingId,
                    tenantId,
                    ex.getMessage());
            return;
        }
        for (Workflow workflow : active) {
            try {
                execute(workflow, ctx);
            } catch (RuntimeException ex) {
                // execute() already records the failure on the execution; this covers an error
                // before the INSERT.
                LOG.error(
                        "Flows: workflow {} execution failed tenantId={} cause={}",
                        workflow.id(),
                        tenantId,
                        ex.getMessage());
            }
        }
    }

    /**
     * Executes a workflow against a context (real event or the canvas' "Test"). Always persists the
     * execution with the step-by-step log — success AND failure stay visible in the history.
     */
    public WorkflowExecution execute(Workflow workflow, WorkflowEventContext ctx) {
        ExecutionLogBuilder log = new ExecutionLogBuilder();
        UUID executionId = UUID.randomUUID();
        OffsetDateTime startedAt = now();
        executions.create(
                new WorkflowExecution(
                        executionId,
                        workflow.id(),
                        workflow.tenantId(),
                        ctx.eventType(),
                        WorkflowExecutionStatus.RUNNING,
                        "[]",
                        startedAt,
                        null));

        boolean success;
        try {
            WorkflowDefinition definition =
                    parser.parse(workflow.definitionJson(), actions.types());
            Node trigger = definition.triggerNode();
            log.info(
                    trigger.id(),
                    "Trigger \""
                            + ctx.eventType()
                            + "\" fired for meeting \""
                            + ctx.meetingTitle()
                            + "\""
                            + (ctx.sampleData() ? " (sample data)" : ""));
            success = walk(definition, trigger, ctx, log);
        } catch (RuntimeException ex) {
            success = false;
            log.error(null, "Execution error: " + ex.getMessage());
        }

        WorkflowExecutionStatus status =
                success ? WorkflowExecutionStatus.SUCCESS : WorkflowExecutionStatus.FAILED;
        OffsetDateTime finishedAt = now();
        String logJson = log.toJson(mapper);
        executions.finish(executionId, workflow.tenantId(), status, logJson, finishedAt);
        return new WorkflowExecution(
                executionId,
                workflow.id(),
                workflow.tenantId(),
                ctx.eventType(),
                status,
                logJson,
                startedAt,
                finishedAt);
    }

    /**
     * Walks the graph from the trigger (BFS). An unsatisfied condition stops only that path; an
     * action failure marks the execution as FAILED but lets the other branches carry on (each
     * branch is independent, n8n-style).
     */
    private boolean walk(
            WorkflowDefinition definition,
            Node trigger,
            WorkflowEventContext ctx,
            ExecutionLogBuilder log) {
        boolean success = true;
        Deque<Node> queue = new ArrayDeque<>(definition.childrenOf(trigger.id()));
        Set<String> seen = new HashSet<>();
        if (queue.isEmpty()) {
            log.info(null, "No node wired to the trigger — nothing to execute");
            return true;
        }
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            if (!seen.add(node.id())) {
                continue;
            }
            switch (node.kind()) {
                case TRIGGER -> {
                    // Parser guarantees 1 trigger; an edge pointing at it is ignored.
                }
                case CONDITION -> {
                    ConditionEvaluator.Evaluation eval;
                    try {
                        eval = conditions.evaluate(node.type(), node.params(), ctx);
                    } catch (RuntimeException ex) {
                        success = false;
                        log.error(node.id(), "Invalid condition: " + ex.getMessage());
                        continue;
                    }
                    log.info(node.id(), "Condition: " + eval.description());
                    if (eval.passed()) {
                        queue.addAll(definition.childrenOf(node.id()));
                    } else {
                        log.info(node.id(), "Path stopped — condition not satisfied");
                    }
                }
                case ACTION -> {
                    Optional<ActionExecutor> executor = actions.byType(node.type());
                    if (executor.isEmpty()) {
                        success = false;
                        log.error(node.id(), "Unknown action: " + node.type());
                        continue;
                    }
                    try {
                        String message = executor.get().execute(ctx, node.params());
                        log.info(node.id(), message);
                        queue.addAll(definition.childrenOf(node.id()));
                    } catch (RuntimeException ex) {
                        success = false;
                        log.error(
                                node.id(),
                                "Action '" + node.type() + "' failed: " + ex.getMessage());
                        // Children of this branch do not run — a failure does not propagate as
                        // success.
                    }
                }
            }
        }
        return success;
    }

    private OffsetDateTime now() {
        return clock.now().atOffset(ZoneOffset.UTC);
    }
}
