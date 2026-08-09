package br.com.nora.api.infrastructure.events;

import br.com.nora.api.application.ports.TenantRlsContext;
import br.com.nora.api.application.workflow.WorkflowEngine;
import br.com.nora.api.domain.event.ActionItemCreatedEvent;
import br.com.nora.api.domain.event.MeetingAnalysisCompletedEvent;
import br.com.nora.api.domain.event.MeetingRiskDetectedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Connects the event bus to NORA Flows' {@link WorkflowEngine}. Asynchronous (does not delay the
 * analysis pipeline) and with the tenant propagated explicitly: under RLS enforce (ADR 0028), the
 * executor thread needs the tenant GUC to read workflows and write executions (same care as {@code
 * AnalysisService.runAsync}).
 */
@Component
public class WorkflowEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowEventListener.class);

    private final WorkflowEngine engine;
    private final TenantRlsContext rlsContext;

    public WorkflowEventListener(WorkflowEngine engine, TenantRlsContext rlsContext) {
        this.engine = engine;
        this.rlsContext = rlsContext;
    }

    @Async
    @EventListener
    public void onMeetingAnalysisCompleted(MeetingAnalysisCompletedEvent event) {
        rlsContext.set(event.tenantId());
        try {
            engine.onMeetingAnalysisCompleted(event);
        } catch (RuntimeException ex) {
            // Last line of defense: a workflow error NEVER goes back to the analysis pipeline.
            LOG.error(
                    "Flows: listener falhou meetingId={} tenantId={} cause={}",
                    event.meetingId(),
                    event.tenantId(),
                    ex.getMessage());
        } finally {
            rlsContext.clear();
        }
    }

    @Async
    @EventListener
    public void onActionItemCreated(ActionItemCreatedEvent event) {
        rlsContext.set(event.tenantId());
        try {
            engine.onActionItemCreated(event);
        } catch (RuntimeException ex) {
            // Last line of defense: a workflow error NEVER goes back to the analysis pipeline.
            LOG.error(
                    "Flows: listener falhou meetingId={} tenantId={} cause={}",
                    event.meetingId(),
                    event.tenantId(),
                    ex.getMessage());
        } finally {
            rlsContext.clear();
        }
    }

    @Async
    @EventListener
    public void onMeetingRiskDetected(MeetingRiskDetectedEvent event) {
        rlsContext.set(event.tenantId());
        try {
            engine.onMeetingRiskDetected(event);
        } catch (RuntimeException ex) {
            // Last line of defense: a workflow error NEVER goes back to the analysis pipeline.
            LOG.error(
                    "Flows: listener falhou meetingId={} tenantId={} cause={}",
                    event.meetingId(),
                    event.tenantId(),
                    ex.getMessage());
        } finally {
            rlsContext.clear();
        }
    }
}
