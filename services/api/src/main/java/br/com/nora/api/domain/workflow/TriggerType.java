package br.com.nora.api.domain.workflow;

/**
 * Triggers supported by NORA Flows. The wire format (e.g. {@code meeting.analysis_completed}) is
 * what goes in the {@code workflows.trigger_type} column, in the definition_json trigger node and
 * in the API.
 */
public enum TriggerType {
    MEETING_ANALYSIS_COMPLETED("meeting.analysis_completed"),
    ACTION_ITEM_CREATED("action_item.created"),
    MEETING_RISK_DETECTED("meeting.risk_detected"),
    SCHEDULE_CRON("schedule.cron");

    private final String wire;

    TriggerType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
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
