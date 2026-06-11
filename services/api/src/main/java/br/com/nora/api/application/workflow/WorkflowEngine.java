package br.com.nora.api.application.workflow;

import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.ports.WorkflowExecutionRepository;
import br.com.nora.api.application.ports.WorkflowRepository;
import br.com.nora.api.application.workflow.WorkflowDefinition.Node;
import br.com.nora.api.domain.event.MeetingAnalysisCompletedEvent;
import br.com.nora.api.domain.workflow.TriggerType;
import br.com.nora.api.domain.workflow.Workflow;
import br.com.nora.api.domain.workflow.WorkflowExecution;
import br.com.nora.api.domain.workflow.WorkflowExecutionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Motor do NORA Flows: casa eventos de domínio com os workflows ATIVOS do tenant, percorre o grafo
 * (gatilho → condições → ações) e registra cada passo em {@code workflow_executions.log_json}.
 *
 * <p>Roda fora de request (listener async pós-commit) — quem chama é responsável por setar o tenant
 * no {@code TenantRlsContext} (igual ao pipeline de análise). Cada execução é isolada: falha em um
 * workflow não impede os demais do mesmo evento.
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

    /** Entrada do gatilho-âncora: análise de reunião concluída (pós-commit). */
    public void onMeetingAnalysisCompleted(MeetingAnalysisCompletedEvent event) {
        List<Workflow> active =
                workflows.findActiveByTenantAndTrigger(
                        event.tenantId(), TriggerType.MEETING_ANALYSIS_COMPLETED);
        if (active.isEmpty()) {
            return;
        }
        WorkflowEventContext ctx;
        try {
            ctx =
                    contextFactory.forMeeting(
                            event.tenantId(),
                            event.meetingId(),
                            TriggerType.MEETING_ANALYSIS_COMPLETED.wire(),
                            event.occurredAt() == null
                                    ? now()
                                    : event.occurredAt().atOffset(ZoneOffset.UTC));
        } catch (RuntimeException ex) {
            LOG.error(
                    "Flows: contexto do evento indisponível meetingId={} tenantId={} cause={}",
                    event.meetingId(),
                    event.tenantId(),
                    ex.getMessage());
            return;
        }
        for (Workflow workflow : active) {
            try {
                execute(workflow, ctx);
            } catch (RuntimeException ex) {
                // execute() já registra a falha na execução; isto cobre erro antes do INSERT.
                LOG.error(
                        "Flows: execução do workflow {} falhou tenantId={} cause={}",
                        workflow.id(),
                        event.tenantId(),
                        ex.getMessage());
            }
        }
    }

    /**
     * Executa um workflow contra um contexto (evento real ou "Testar" do canvas). Sempre persiste a
     * execução com o log passo a passo — sucesso E falha ficam visíveis no histórico.
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
                    "Gatilho \""
                            + ctx.eventType()
                            + "\" disparado para a reunião \""
                            + ctx.meetingTitle()
                            + "\""
                            + (ctx.sampleData() ? " (dados de exemplo)" : ""));
            success = walk(definition, trigger, ctx, log);
        } catch (RuntimeException ex) {
            success = false;
            log.error(null, "Erro na execução: " + ex.getMessage());
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
     * Percorre o grafo a partir do gatilho (BFS). Condição não satisfeita interrompe só aquele
     * caminho; falha de ação marca a execução como FAILED mas deixa os demais ramos continuarem
     * (cada ramo é independente, estilo n8n).
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
            log.info(null, "Nenhum nó ligado ao gatilho — nada a executar");
            return true;
        }
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            if (!seen.add(node.id())) {
                continue;
            }
            switch (node.kind()) {
                case TRIGGER -> {
                    // Parser garante 1 gatilho; aresta apontando pra ele é ignorada.
                }
                case CONDITION -> {
                    ConditionEvaluator.Evaluation eval;
                    try {
                        eval = conditions.evaluate(node.type(), node.params(), ctx);
                    } catch (RuntimeException ex) {
                        success = false;
                        log.error(node.id(), "Condição inválida: " + ex.getMessage());
                        continue;
                    }
                    log.info(node.id(), "Condição: " + eval.description());
                    if (eval.passed()) {
                        queue.addAll(definition.childrenOf(node.id()));
                    } else {
                        log.info(node.id(), "Caminho interrompido — condição não satisfeita");
                    }
                }
                case ACTION -> {
                    Optional<ActionExecutor> executor = actions.byType(node.type());
                    if (executor.isEmpty()) {
                        success = false;
                        log.error(node.id(), "Ação desconhecida: " + node.type());
                        continue;
                    }
                    try {
                        String message = executor.get().execute(ctx, node.params());
                        log.info(node.id(), message);
                        queue.addAll(definition.childrenOf(node.id()));
                    } catch (RuntimeException ex) {
                        success = false;
                        log.error(
                                node.id(), "Ação '" + node.type() + "' falhou: " + ex.getMessage());
                        // Filhos deste ramo não executam — falha não se propaga como sucesso.
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
