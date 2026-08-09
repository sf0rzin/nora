package br.com.nora.api.application.meeting;

import br.com.nora.api.application.ports.MeetingGoalRepository;
import br.com.nora.api.application.ports.MeetingRepository;
import br.com.nora.api.application.ports.ProductivityAssessmentRepository;
import br.com.nora.api.domain.meeting.Meeting;
import br.com.nora.api.domain.meeting.ProcessingStatus;
import br.com.nora.api.domain.meeting.productivity.MeetingGoal;
import br.com.nora.api.domain.meeting.productivity.ProductivityAssessment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for MeetingGoal and ProductivityAssessment (ADR 0005).
 *
 * <p>Rules:
 *
 * <ul>
 *   <li>Every operation receives an explicit tenantId coming from the JWT.
 *   <li>PUT creates or updates idempotently (upsert by meetingId).
 *   <li>Updating the goal after analysis marks the meeting as PENDING for reprocessing.
 *   <li>DELETE removes the goal and the linked assessment (logical cascade).
 * </ul>
 */
@Service
public class MeetingGoalService {

    private final MeetingRepository meetings;
    private final MeetingGoalRepository goals;
    private final ProductivityAssessmentRepository assessments;

    /**
     * Owns the transition to PENDING and the dispatch — see {@code queueAnalysisAfterGoalChange}.
     */
    private final MeetingService meetingService;

    public MeetingGoalService(
            MeetingRepository meetings,
            MeetingGoalRepository goals,
            ProductivityAssessmentRepository assessments,
            MeetingService meetingService) {
        this.meetings = meetings;
        this.goals = goals;
        this.assessments = assessments;
        this.meetingService = meetingService;
    }

    /**
     * Creates/updates the goal. When an analysis had already completed, marks the meeting as
     * PENDING for reprocessing (the productivity assessment is only computed by the worker, and it
     * needs the updated goal).
     */
    @Transactional
    public GoalSaveResult save(
            UUID meetingId,
            UUID tenantId,
            String purpose,
            List<String> expectedOutcomes,
            String projectStateSnapshot) {
        Meeting meeting =
                meetings.findByIdAndTenant(meetingId, tenantId)
                        .orElseThrow(MeetingException.NotFound::new);

        Optional<MeetingGoal> existing = goals.findByMeetingId(meetingId, tenantId);
        MeetingGoal toSave =
                existing.map(g -> g.updated(purpose, expectedOutcomes, projectStateSnapshot))
                        .orElseGet(
                                () ->
                                        MeetingGoal.create(
                                                tenantId,
                                                meetingId,
                                                purpose,
                                                expectedOutcomes,
                                                projectStateSnapshot));
        MeetingGoal saved = goals.save(toSave);

        boolean shouldReprocess =
                meeting.processingStatus() == ProcessingStatus.COMPLETED
                        || meeting.processingStatus() == ProcessingStatus.FAILED;
        if (shouldReprocess) {
            assessments.deleteByMeetingId(meetingId, tenantId);
            // Queue it for real. Writing PENDING here and dispatching nothing left the meeting
            // waiting for a user to press "re-analyse" — and once reprocess started rejecting
            // PENDING that became a dead end, with the productivity assessment already deleted.
            shouldReprocess = meetingService.queueAnalysisAfterGoalChange(meetingId, tenantId);
        }
        return new GoalSaveResult(saved, shouldReprocess);
    }

    /** Removes goal + assessment. Idempotent: a missing goal is treated as success. */
    @Transactional
    public void delete(UUID meetingId, UUID tenantId) {
        // Enforces the tenant scope.
        meetings.findByIdAndTenant(meetingId, tenantId).orElseThrow(MeetingException.NotFound::new);
        assessments.deleteByMeetingId(meetingId, tenantId);
        goals.deleteByMeetingId(meetingId, tenantId);
    }

    @Transactional(readOnly = true)
    public Optional<MeetingGoal> findGoal(UUID meetingId, UUID tenantId) {
        return goals.findByMeetingId(meetingId, tenantId);
    }

    @Transactional(readOnly = true)
    public Optional<ProductivityAssessment> findAssessment(UUID meetingId, UUID tenantId) {
        return assessments.findByMeetingId(meetingId, tenantId);
    }

    /** Save result: the persisted goal and whether reprocessing was triggered. */
    public record GoalSaveResult(MeetingGoal goal, boolean reprocessTriggered) {}
}
