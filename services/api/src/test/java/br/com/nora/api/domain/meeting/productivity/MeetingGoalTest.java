package br.com.nora.api.domain.meeting.productivity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MeetingGoalTest {

    private final UUID tenant = UUID.randomUUID();
    private final UUID meeting = UUID.randomUUID();

    @Test
    void createFactoryAssignsPositionsAndTrimsSnapshot() {
        MeetingGoal goal =
                MeetingGoal.create(
                        tenant,
                        meeting,
                        "Refinement do epico X",
                        List.of("Definir criterios de aceite", "Decidir provider de pagamento"),
                        "  estado parcial  ");

        assertThat(goal.tenantId()).isEqualTo(tenant);
        assertThat(goal.meetingId()).isEqualTo(meeting);
        assertThat(goal.purpose()).isEqualTo("Refinement do epico X");
        assertThat(goal.expectedOutcomes()).hasSize(2);
        assertThat(goal.expectedOutcomes().get(0).position()).isZero();
        assertThat(goal.expectedOutcomes().get(1).position()).isEqualTo(1);
        assertThat(goal.projectStateSnapshot()).isEqualTo("estado parcial");
    }

    @Test
    void purposeBlankFails() {
        assertThatThrownBy(
                        () -> MeetingGoal.create(tenant, meeting, "  ", List.of("outcome 1"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void purposeTooShortFails() {
        assertThatThrownBy(
                        () -> MeetingGoal.create(tenant, meeting, "ab", List.of("outcome 1"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyOutcomesFail() {
        assertThatThrownBy(() -> MeetingGoal.create(tenant, meeting, "purpose", List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tooManyOutcomesFail() {
        List<String> outcomes =
                java.util.stream.IntStream.range(0, 11).mapToObj(i -> "outcome " + i).toList();
        assertThatThrownBy(() -> MeetingGoal.create(tenant, meeting, "purpose", outcomes, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void outcomeTextTooShortFails() {
        assertThatThrownBy(
                        () -> MeetingGoal.create(tenant, meeting, "purpose", List.of("ab"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updatedPreservesIdAndCreatedAt() {
        MeetingGoal original =
                MeetingGoal.create(
                        tenant, meeting, "Refinement", List.of("outcome a", "outcome b"), null);
        MeetingGoal updated =
                original.updated(
                        "Refinement revisto",
                        List.of("outcome a", "outcome b", "outcome c"),
                        "snapshot");

        assertThat(updated.id()).isEqualTo(original.id());
        assertThat(updated.createdAt()).isEqualTo(original.createdAt());
        assertThat(updated.updatedAt()).isAfterOrEqualTo(original.updatedAt());
        assertThat(updated.purpose()).isEqualTo("Refinement revisto");
        assertThat(updated.expectedOutcomes()).hasSize(3);
        assertThat(updated.projectStateSnapshot()).isEqualTo("snapshot");
    }

    @Test
    void productivityAssessmentValidatesScoreBounds() {
        assertThatThrownBy(
                        () ->
                                ProductivityAssessment.newAssessment(
                                        tenant,
                                        meeting,
                                        101,
                                        ProductivityBand.HIGH,
                                        List.of(),
                                        null,
                                        null,
                                        "rationale ok"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void productivityAssessmentValidatesOffTopicRatio() {
        assertThatThrownBy(
                        () ->
                                ProductivityAssessment.newAssessment(
                                        tenant,
                                        meeting,
                                        50,
                                        ProductivityBand.MEDIUM,
                                        List.of(),
                                        1.2,
                                        null,
                                        "rationale ok longer"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void productivityAssessmentBuilds() {
        ProductivityAssessment a =
                ProductivityAssessment.newAssessment(
                        tenant,
                        meeting,
                        80,
                        ProductivityBand.HIGH,
                        List.of(
                                new OutcomeCoverage(
                                        "Definir criterios", CoverageStatus.ADDRESSED, null, 0)),
                        0.1,
                        0.5,
                        "rationale com conteudo");

        assertThat(a.id()).isNotNull();
        assertThat(a.score()).isEqualTo(80);
        assertThat(a.band()).isEqualTo(ProductivityBand.HIGH);
        assertThat(a.coverage()).hasSize(1);
        assertThat(a.coverage().get(0).status()).isEqualTo(CoverageStatus.ADDRESSED);
    }
}
