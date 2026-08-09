package br.com.nora.api.application.workflow.actions;

import br.com.nora.api.application.workflow.WorkflowEventContext;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/**
 * Follow-up scheduling for the calendar actions (Google and Outlook) — shared so both behave the
 * same way.
 *
 * <p>Rule (PO request, 2026-06-12): the event should come from the MEETING DATA whenever possible.
 * Without an explicit {@code startInDays} on the node, we use the nearest due date among the action
 * items extracted by the analysis (strictly after today — a due date today at 10am may already have
 * passed); with no usable due date, it falls back to the "tomorrow" default. A {@code startInDays}
 * filled in by the user ALWAYS wins — explicit configuration is not guessed.
 */
public final class FollowUpSchedule {

    private static final DateTimeFormatter DATA_BR = DateTimeFormatter.ofPattern("dd/MM");

    private FollowUpSchedule() {}

    /**
     * Resolved window for the event. {@code origem} explains where the date came from when it was
     * not the default (goes to the execution log and to the action's return) — null on the
     * default/manual path.
     */
    public record Resolved(OffsetDateTime start, OffsetDateTime end, String origem) {}

    /** {@code now} must come in the calendar time zone (America/Sao_Paulo in current actions). */
    public static Resolved resolve(
            WorkflowEventContext ctx, Map<String, Object> params, OffsetDateTime now) {
        int hour = clamp(intParam(params, "hour", 10), 0, 23);
        int durationMinutes = Math.max(intParam(params, "durationMinutes", 30), 5);

        Integer explicitStart = intParamOrNull(params, "startInDays");
        OffsetDateTime start;
        String origem = null;
        if (explicitStart != null) {
            start = atHour(now.plusDays(explicitStart), hour);
        } else {
            Optional<WorkflowEventContext.ActionItemView> comPrazo =
                    earliestFutureDueDate(ctx, now.toLocalDate());
            if (comPrazo.isPresent()) {
                WorkflowEventContext.ActionItemView item = comPrazo.get();
                start = OffsetDateTime.of(item.dueDate(), LocalTime.of(hour, 0), now.getOffset());
                origem =
                        "prazo de \""
                                + item.title()
                                + "\" ("
                                + DATA_BR.format(item.dueDate())
                                + ")";
            } else {
                start = atHour(now.plusDays(1), hour);
            }
        }
        return new Resolved(start, start.plusMinutes(durationMinutes), origem);
    }

    private static Optional<WorkflowEventContext.ActionItemView> earliestFutureDueDate(
            WorkflowEventContext ctx, LocalDate today) {
        return ctx.actionItems().stream()
                .filter(i -> i.dueDate() != null && i.dueDate().isAfter(today))
                .min(Comparator.comparing(WorkflowEventContext.ActionItemView::dueDate));
    }

    private static OffsetDateTime atHour(OffsetDateTime base, int hour) {
        return base.withHour(hour).withMinute(0).withSecond(0).withNano(0);
    }

    private static int clamp(int v, int min, int max) {
        return Math.min(Math.max(v, min), max);
    }

    private static int intParam(Map<String, Object> params, String key, int defaultValue) {
        Integer v = intParamOrNull(params, key);
        return v == null ? defaultValue : v;
    }

    private static Integer intParamOrNull(Map<String, Object> params, String key) {
        Object raw = params.get(key);
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // no usable value
            }
        }
        return null;
    }
}
