package br.com.nora.api.application.workflow.actions;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.nora.api.application.workflow.WorkflowEventContext;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FollowUpScheduleTest {

    // Friday 12/06/2026 at 3pm in SP (-03:00).
    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 6, 12, 15, 0, 0, 0, ZoneOffset.ofHours(-3));

    private static WorkflowEventContext ctx(WorkflowEventContext.ActionItemView... items) {
        return new WorkflowEventContext(
                UUID.randomUUID(),
                "meeting.analysis_completed",
                UUID.randomUUID(),
                "Reunião",
                List.of(),
                "resumo",
                "resumo",
                0,
                items.length,
                0,
                List.of(items),
                null,
                null,
                null,
                NOW,
                false);
    }

    private static WorkflowEventContext.ActionItemView item(String title, LocalDate due) {
        return new WorkflowEventContext.ActionItemView(title, null, "MEDIUM", due);
    }

    @Test
    void usesTheNearestActionItemDeadlineWhenNoExplicitStartInDays() {
        WorkflowEventContext ctx =
                ctx(
                        item("Enviar contrato", LocalDate.of(2026, 6, 25)),
                        item("Enviar proposta", LocalDate.of(2026, 6, 20)),
                        item("Sem prazo", null));

        FollowUpSchedule.Resolved r = FollowUpSchedule.resolve(ctx, Map.of(), NOW);

        assertThat(r.start().toLocalDate()).isEqualTo(LocalDate.of(2026, 6, 20));
        assertThat(r.start().getHour()).isEqualTo(10);
        assertThat(r.end()).isEqualTo(r.start().plusMinutes(30));
        assertThat(r.origem()).contains("Enviar proposta").contains("20/06");
    }

    @Test
    void dueDateTodayOrPastDoesNotCount_fallsBackToTomorrowDefault() {
        WorkflowEventContext ctx =
                ctx(
                        item("Vence hoje", NOW.toLocalDate()),
                        item("Venceu", LocalDate.of(2026, 6, 1)));

        FollowUpSchedule.Resolved r = FollowUpSchedule.resolve(ctx, Map.of(), NOW);

        assertThat(r.start().toLocalDate()).isEqualTo(NOW.toLocalDate().plusDays(1));
        assertThat(r.origem()).isNull();
    }

    @Test
    void explicitStartInDaysAlwaysOverridesDeadlines() {
        WorkflowEventContext ctx = ctx(item("Enviar proposta", LocalDate.of(2026, 6, 20)));

        FollowUpSchedule.Resolved r =
                FollowUpSchedule.resolve(ctx, Map.of("startInDays", 3, "hour", 14), NOW);

        assertThat(r.start().toLocalDate()).isEqualTo(NOW.toLocalDate().plusDays(3));
        assertThat(r.start().getHour()).isEqualTo(14);
        assertThat(r.origem()).isNull();
    }

    @Test
    void noActionItemsUsesTomorrowWithDefaults() {
        FollowUpSchedule.Resolved r = FollowUpSchedule.resolve(ctx(), Map.of(), NOW);

        assertThat(r.start().toLocalDate()).isEqualTo(NOW.toLocalDate().plusDays(1));
        assertThat(r.start().getHour()).isEqualTo(10);
        assertThat(r.start().getMinute()).isZero();
        assertThat(r.origem()).isNull();
    }

    @Test
    void invalidHourAndMinimumDurationAreNormalized() {
        FollowUpSchedule.Resolved r =
                FollowUpSchedule.resolve(ctx(), Map.of("hour", "99", "durationMinutes", 1), NOW);

        assertThat(r.start().getHour()).isEqualTo(23);
        assertThat(r.end()).isEqualTo(r.start().plusMinutes(5));
    }
}
