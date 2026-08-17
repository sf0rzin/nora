package br.com.nora.api.domain.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.nora.api.domain.workflow.ScheduleSpec.Frequency;
import java.time.DayOfWeek;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The schedule vocabulary is a CLOSED set (ADR 0047 §2), and these tests are what keeps it closed.
 * The property that matters is not any single message: it is that nothing this parses can fire more
 * often than hourly, because the alternative — an expression language that accepts {@code * * * * *
 * *} — is what let {@code schedule.cron} be declared and never run in the first place.
 */
class ScheduleSpecTest {

    @Test
    void hourlyCompilesToAnExpressionThatFiresOncePerHour() {
        ScheduleSpec spec = ScheduleSpec.parse(Map.of("frequency", "hourly", "minute", 30));
        assertThat(spec.frequency()).isEqualTo(Frequency.HOURLY);
        assertThat(spec.cron()).isEqualTo("0 30 * * * *");
        assertThat(spec.describe()).isEqualTo("hourly at minute 30");
    }

    @Test
    void dailyCompilesToAnExpressionThatFiresOncePerDay() {
        ScheduleSpec spec =
                ScheduleSpec.parse(Map.of("frequency", "daily", "hour", 9, "minute", 5));
        assertThat(spec.cron()).isEqualTo("0 5 9 * * *");
        assertThat(spec.describe()).isEqualTo("daily at 09:05");
    }

    @Test
    void weeklyCarriesTheWeekdayIntoTheExpression() {
        ScheduleSpec spec =
                ScheduleSpec.parse(
                        Map.of("frequency", "weekly", "weekday", "MON", "hour", 17, "minute", 0));
        assertThat(spec.weekday()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(spec.cron()).isEqualTo("0 0 17 * * MON");
        assertThat(spec.describe()).isEqualTo("weekly on MON at 17:00");
    }

    /**
     * The canvas sends numbers, but a hand-written definition or a form that stringifies its inputs
     * sends text. Accepting both is not laxity — rejecting {@code "9"} would be a 422 the user has
     * no way to act on.
     */
    @Test
    void acceptsNumericStringsForHourAndMinute() {
        ScheduleSpec spec =
                ScheduleSpec.parse(Map.of("frequency", "daily", "hour", "9", "minute", "0"));
        assertThat(spec.cron()).isEqualTo("0 0 9 * * *");
    }

    @Test
    void weekdayAcceptsTheFullNameAndTheThreeLetterForm() {
        assertThat(weekly("FRI").weekday()).isEqualTo(DayOfWeek.FRIDAY);
        assertThat(weekly("friday").weekday()).isEqualTo(DayOfWeek.FRIDAY);
    }

    /** A prefix match would resolve "S" to SATURDAY, which is a guess dressed up as a parse. */
    @Test
    void weekdayRefusesAnAmbiguousPrefix() {
        assertThatThrownBy(() -> weekly("S"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weekday");
    }

    /**
     * The rejection this whole design exists for. A raw expression is refused BY NAME rather than
     * ignored: a param that is silently dropped is a schedule firing at a time the user did not ask
     * for, and the message has to point at the four keys that do exist.
     */
    @Test
    void refusesARawCronExpressionAndSaysWhatToUseInstead() {
        Map<String, Object> params = new HashMap<>();
        params.put("cron", "* * * * * *");
        params.put("frequency", "daily");
        params.put("hour", 9);
        params.put("minute", 0);
        assertThatThrownBy(() -> ScheduleSpec.parse(params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not take a raw")
                .hasMessageContaining("frequency");
    }

    @Test
    void refusesAnUnknownFrequency() {
        assertThatThrownBy(
                        () -> ScheduleSpec.parse(Map.of("frequency", "every_minute", "minute", 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hourly, daily or weekly");
    }

    @Test
    void refusesAMissingFrequency() {
        assertThatThrownBy(() -> ScheduleSpec.parse(Map.of("hour", 9, "minute", 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frequency");
    }

    @Test
    void refusesAnHourOrMinuteOutOfRange() {
        assertThatThrownBy(
                        () ->
                                ScheduleSpec.parse(
                                        Map.of("frequency", "daily", "hour", 24, "minute", 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hour");
        assertThatThrownBy(
                        () ->
                                ScheduleSpec.parse(
                                        Map.of("frequency", "daily", "hour", 9, "minute", 60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minute");
    }

    @Test
    void refusesANonNumericMinute() {
        assertThatThrownBy(
                        () -> ScheduleSpec.parse(Map.of("frequency", "hourly", "minute", "meia")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minute");
    }

    @Test
    void refusesAWeeklyWithNoWeekday() {
        assertThatThrownBy(
                        () ->
                                ScheduleSpec.parse(
                                        Map.of("frequency", "weekly", "hour", 9, "minute", 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weekday");
    }

    /** An hour on an hourly schedule would read as configuration that does something. */
    @Test
    void normalisesAwayParamsTheFrequencyDoesNotUse() {
        ScheduleSpec spec =
                ScheduleSpec.parse(
                        Map.of("frequency", "hourly", "minute", 15, "hour", 9, "weekday", "MON"));
        assertThat(spec.hour()).isZero();
        assertThat(spec.weekday()).isNull();
        assertThat(spec.cron()).isEqualTo("0 15 * * * *");
    }

    private static ScheduleSpec weekly(String weekday) {
        return ScheduleSpec.parse(
                Map.of("frequency", "weekly", "weekday", weekday, "hour", 9, "minute", 0));
    }
}
