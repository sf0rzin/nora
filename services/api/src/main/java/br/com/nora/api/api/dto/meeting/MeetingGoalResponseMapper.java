package br.com.nora.api.api.dto.meeting;

import br.com.nora.api.domain.meeting.productivity.ExpectedOutcome;
import br.com.nora.api.domain.meeting.productivity.MeetingGoal;
import br.com.nora.api.domain.meeting.productivity.ProductivityAssessment;

/** Maps productivity aggregates to the HTTP DTOs. */
public final class MeetingGoalResponseMapper {

    private MeetingGoalResponseMapper() {}

    public static MeetingGoalResponse from(MeetingGoal goal) {
        return new MeetingGoalResponse(
                goal.id(),
                goal.meetingId(),
                goal.purpose(),
                goal.expectedOutcomes().stream().map(ExpectedOutcome::text).toList(),
                goal.projectStateSnapshot(),
                goal.createdAt(),
                goal.updatedAt());
    }

    public static ProductivityAssessmentResponse from(ProductivityAssessment a) {
        return new ProductivityAssessmentResponse(
                a.score(),
                a.band().name(),
                a.coverage().stream()
                        .map(
                                c ->
                                        new ProductivityCoverageResponse(
                                                c.expectedOutcome(),
                                                c.status().name(),
                                                c.evidence()))
                        .toList(),
                a.offTopicRatio(),
                a.decisionDensity(),
                a.rationale());
    }
}
