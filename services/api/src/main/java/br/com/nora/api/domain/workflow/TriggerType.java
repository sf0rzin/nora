package br.com.nora.api.domain.workflow;

/**
 * Gatilhos suportados pelo NORA Flows. O wire format (ex.: {@code meeting.analysis_completed}) é o
 * que vai na coluna {@code workflows.trigger_type}, no nó de gatilho do definition_json e na API.
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
