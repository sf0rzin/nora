package br.com.nora.api.domain.workflow;

import java.time.DayOfWeek;
import java.util.Locale;
import java.util.Map;

/**
 * The schedule of a {@link TriggerType#SCHEDULE_CRON} flow. It is a RESTRICTED VOCABULARY, not a
 * cron expression (ADR 0047 §2): three frequencies, and the fastest one that can be expressed is
 * hourly.
 *
 * <p>The vocabulary lives in the trigger node's {@code params}:
 *
 * <ul>
 *   <li>{@code frequency=hourly} + {@code minute} (0-59) — every hour at that minute
 *   <li>{@code frequency=daily} + {@code hour} (0-23) + {@code minute} — every day at that time
 *   <li>{@code frequency=weekly} + {@code weekday} (MON..SUN) + {@code hour} + {@code minute}
 * </ul>
 *
 * <p>Why not a real expression: a full parser accepts {@code * * * * * *}, which fires every
 * second, and the only honest answers to that are to run it — which a single host cannot — or to
 * accept it and quietly not run it, which is the exact defect US75 exists to close. A closed
 * vocabulary makes the bound structural instead of a rejection rule somebody has to remember.
 *
 * <p>The wire value stays {@code schedule.cron} because rows already carry it, and the name stays
 * accurate: {@link #cron()} compiles the vocabulary to a canonical six-field Spring expression,
 * which is what gets stored and what computes the next occurrence. Storing the compiled form is
 * also what makes widening the vocabulary later a parser decision rather than a schema change.
 *
 * <p>Every rejection here is thrown as {@link IllegalArgumentException} with a message written for
 * the person editing the canvas; {@code WorkflowDefinitionParser} turns it into a 422.
 */
public record ScheduleSpec(Frequency frequency, int hour, int minute, DayOfWeek weekday) {

    /** Params key holding the frequency. The other three are named after themselves. */
    public static final String PARAM_FREQUENCY = "frequency";

    public static final String PARAM_HOUR = "hour";
    public static final String PARAM_MINUTE = "minute";
    public static final String PARAM_WEEKDAY = "weekday";

    /**
     * Keys a technical user reaches for when they expect to hand-write an expression. They are
     * refused by name instead of ignored: a param that is silently dropped is a schedule that fires
     * at a time the user did not ask for, which is the failure this trigger is here to end.
     */
    private static final String[] EXPRESSION_KEYS = {"cron", "expression"};

    /** The three shapes the engine can honour. The wire form is the lowercase name. */
    public enum Frequency {
        HOURLY,
        DAILY,
        WEEKLY;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Frequency fromWire(String raw) {
            if (raw != null) {
                for (Frequency f : values()) {
                    if (f.wire().equals(raw.trim().toLowerCase(Locale.ROOT))) {
                        return f;
                    }
                }
            }
            throw new IllegalArgumentException(
                    "schedule 'frequency' must be one of hourly, daily or weekly (received: "
                            + raw
                            + ")");
        }
    }

    public ScheduleSpec {
        if (frequency == null) {
            throw new IllegalArgumentException("schedule frequency is required");
        }
        if (minute < 0 || minute > 59) {
            throw new IllegalArgumentException("schedule 'minute' must be between 0 and 59");
        }
        if (hour < 0 || hour > 23) {
            throw new IllegalArgumentException("schedule 'hour' must be between 0 and 23");
        }
        if (frequency == Frequency.WEEKLY && weekday == null) {
            throw new IllegalArgumentException(
                    "a weekly schedule needs 'weekday' (MON, TUE, WED, THU, FRI, SAT or SUN)");
        }
        if (frequency != Frequency.WEEKLY) {
            // Normalised away rather than kept: an hour on an hourly schedule and a weekday on a
            // daily one would read as configuration that does something.
            weekday = null;
        }
        if (frequency == Frequency.HOURLY) {
            hour = 0;
        }
    }

    /** Reads the vocabulary out of a trigger node's params. Throws with an actionable message. */
    public static ScheduleSpec parse(Map<String, Object> params) {
        Map<String, Object> safe = params == null ? Map.of() : params;
        rejectRawExpression(safe);
        Frequency frequency = Frequency.fromWire(text(safe, PARAM_FREQUENCY));
        int minute = intInRange(safe, PARAM_MINUTE, 0, 59);
        int hour = frequency == Frequency.HOURLY ? 0 : intInRange(safe, PARAM_HOUR, 0, 23);
        DayOfWeek weekday = frequency == Frequency.WEEKLY ? weekday(safe) : null;
        return new ScheduleSpec(frequency, hour, minute, weekday);
    }

    /**
     * Canonical six-field Spring cron expression ({@code second minute hour day-of-month month
     * day-of-week}) this schedule compiles to. Second is always {@code 0} — the vocabulary has no
     * sub-minute shape, so it cannot produce one.
     */
    public String cron() {
        return switch (frequency) {
            case HOURLY -> "0 " + minute + " * * * *";
            case DAILY -> "0 " + minute + " " + hour + " * * *";
            case WEEKLY -> "0 " + minute + " " + hour + " * * " + weekday.name().substring(0, 3);
        };
    }

    /** One-line English rendering for logs. The canvas writes its own, in pt-BR. */
    public String describe() {
        return switch (frequency) {
            case HOURLY -> "hourly at minute " + two(minute);
            case DAILY -> "daily at " + two(hour) + ":" + two(minute);
            case WEEKLY ->
                    "weekly on "
                            + weekday.name().substring(0, 3)
                            + " at "
                            + two(hour)
                            + ":"
                            + two(minute);
        };
    }

    private static void rejectRawExpression(Map<String, Object> params) {
        for (String key : EXPRESSION_KEYS) {
            if (params.containsKey(key)) {
                throw new IllegalArgumentException(
                        "the schedule trigger does not take a raw '"
                                + key
                                + "' expression: use frequency (hourly, daily or weekly) with"
                                + " hour, minute and weekday");
            }
        }
    }

    private static String text(Map<String, Object> params, String key) {
        Object raw = params.get(key);
        return raw == null ? null : String.valueOf(raw);
    }

    private static int intInRange(Map<String, Object> params, String key, int min, int max) {
        Object raw = params.get(key);
        Integer value = null;
        if (raw instanceof Number n) {
            value = n.intValue();
        } else if (raw instanceof String s && !s.isBlank()) {
            try {
                value = Integer.valueOf(s.trim());
            } catch (NumberFormatException ignored) {
                // falls through to the throw below, with the same message as a missing value
            }
        }
        if (value == null || value < min || value > max) {
            throw new IllegalArgumentException(
                    "schedule '" + key + "' must be a number between " + min + " and " + max);
        }
        return value;
    }

    private static DayOfWeek weekday(Map<String, Object> params) {
        String raw = text(params, PARAM_WEEKDAY);
        if (raw != null && !raw.isBlank()) {
            // Only the full name or the exact 3-letter abbreviation. A plain prefix match would
            // resolve "S" to SATURDAY, which is a guess dressed up as a parse.
            String normalized = raw.trim().toUpperCase(Locale.ROOT);
            for (DayOfWeek day : DayOfWeek.values()) {
                if (day.name().equals(normalized)
                        || day.name().substring(0, 3).equals(normalized)) {
                    return day;
                }
            }
        }
        throw new IllegalArgumentException(
                "a weekly schedule needs 'weekday' (MON, TUE, WED, THU, FRI, SAT or SUN)");
    }

    private static String two(int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }
}
