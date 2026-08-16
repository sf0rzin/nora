package br.com.nora.api.domain.workflow;

/**
 * Triggers supported by NORA Flows. The wire format (e.g. {@code meeting.analysis_completed}) is
 * what goes in the {@code workflows.trigger_type} column, in the definition_json trigger node and
 * in the API.
 *
 * <p>{@link #SCHEDULE_CRON} is declared but has NO dispatcher: nothing in the backend schedules a
 * workflow, so a flow saved with it would sit ACTIVE and never run. It stays in the enum because
 * rows already persisted with that value have to keep reading ({@link #fromWire} would throw on
 * them otherwise); {@link #hasDispatcher()} is what keeps it out of new definitions, and {@code
 * WorkflowDefinitionParser} refuses it on save.
 */
public enum TriggerType {
    MEETING_ANALYSIS_COMPLETED("meeting.analysis_completed", true),
    ACTION_ITEM_CREATED("action_item.created", true),
    MEETING_RISK_DETECTED("meeting.risk_detected", true),
    SCHEDULE_CRON("schedule.cron", false);

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
     * True when something in the backend actually fires this trigger — today the three events
     * {@code AnalysisService} publishes, each with a handler in {@code WorkflowEngine}. False means
     * the value exists for backwards compatibility only and must be refused on save.
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
