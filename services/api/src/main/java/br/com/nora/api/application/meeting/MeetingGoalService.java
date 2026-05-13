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
 * Servico de aplicacao para MeetingGoal e ProductivityAssessment (ADR 0005).
 *
 * <p>Regras:
 *
 * <ul>
 *   <li>Toda operacao recebe tenantId explicito vindo do JWT.
 *   <li>PUT cria ou atualiza idempotentemente (upsert por meetingId).
 *   <li>Atualizar goal apos analise marca o meeting como PENDING para reprocessamento.
 *   <li>DELETE remove goal e assessment vinculada (cascata logica).
 * </ul>
 */
@Service
public class MeetingGoalService {

    private final MeetingRepository meetings;
    private final MeetingGoalRepository goals;
    private final ProductivityAssessmentRepository assessments;

    public MeetingGoalService(
            MeetingRepository meetings,
            MeetingGoalRepository goals,
            ProductivityAssessmentRepository assessments) {
        this.meetings = meetings;
        this.goals = goals;
        this.assessments = assessments;
    }

    /**
     * Cria/atualiza o objetivo. Quando ja havia analise concluida, marca a meeting como PENDING
     * para reprocessamento (o productivity assessment so e calculado pelo worker, e precisa do goal
     * atualizado).
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
            meetings.save(meeting.withStatus(ProcessingStatus.PENDING));
        }
        return new GoalSaveResult(saved, shouldReprocess);
    }

    /** Remove goal + assessment. Idempotente: ausencia de goal e tratada como sucesso. */
    @Transactional
    public void delete(UUID meetingId, UUID tenantId) {
        // Garante escopo do tenant.
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

    /** Resultado do save: o goal persistido e se houve trigger de reprocessamento. */
    public record GoalSaveResult(MeetingGoal goal, boolean reprocessTriggered) {}
}
