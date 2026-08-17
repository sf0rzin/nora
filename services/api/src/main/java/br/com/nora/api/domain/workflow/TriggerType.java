package br.com.nora.api.domain.workflow;

/**
 * Triggers supported by NORA Flows. The wire format (e.g. {@code meeting.analysis_completed}) is
 * what goes in the {@code workflows.trigger_type} column, in the definition_json trigger node and
 * in the API.
 *
 * <p>All four are dispatched. The first three are domain events {@code AnalysisService} publishes,
 * each with a handler in {@code WorkflowEngine}; {@link #SCHEDULE_CRON} is fired by {@code
 * ScheduledFlowRunner}, the {@code @Scheduled} tick US75 added (ADR 0047).
 *
 * <p>Until then it was declared with NO dispatcher and {@code WorkflowDefinitionParser} refused it
 * on save — the enum value survived only so rows persisted before that rule kept deserialising.
 * {@link #hasDispatcher()} outlives the fix on purpose: it is the invariant that a declared trigger
 * must be a trigger something actually fires, and the next value added is either dispatched or
 * refused rather than accepted and silently never run.
 */
public enum TriggerType {
    MEETING_ANALYSIS_COMPLETED("meeting.analysis_completed", true),
    ACTION_ITEM_CREATED("action_item.created", true),
    MEETING_RISK_DETECTED("meeting.risk_detected", true),
    SCHEDULE_CRON("schedule.cron", true);

    private final String wire;
    private final boolean dispatched;

    TriggerType(String wire, boolean dispatched) {
        this.wire = wire;
        this.dispatched = dispatched;
    }

    public String wire() {
        return wire;
    }

    /**
     * True when something in the backend actually fires this trigger — the three events {@code
     * AnalysisService} publishes plus the scheduler tick. False means the value exists for
     * backwards compatibility only and must be refused on save. Every value returns true today; the
     * check stays because the alternative is a catalogue entry that advertises a trigger no code
     * fires, which is the defect US75 closed.
     */
    public boolean hasDispatcher() {
        return dispatched;
    }

    public static TriggerType fromWire(String raw) {
        if (raw != null) {
            for (TriggerType t : values()) {
                if (t.wire.equals(raw.trim())) {
                    return t;
                }
            }
        }
        throw new IllegalArgumentException("unknown trigger type: " + raw);
    }
}
