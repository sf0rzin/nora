package br.com.nora.api.application.workflow;

import br.com.nora.api.application.ports.CustomerConfidenceAssessmentRepository;
import br.com.nora.api.application.ports.MeetingAnalysisRepository;
import br.com.nora.api.application.ports.MeetingRepository;
import br.com.nora.api.application.ports.MeetingRepository.MeetingFilter;
import br.com.nora.api.application.ports.ProductivityAssessmentRepository;
import br.com.nora.api.application.workflow.WorkflowEventContext.ActionItemView;
import br.com.nora.api.domain.analysis.MeetingAnalysis;
import br.com.nora.api.domain.meeting.Meeting;
import br.com.nora.api.domain.meeting.ProcessingStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the {@link WorkflowEventContext} from the state COMMITTED in the database (the event is
 * published post-commit, so the read here is consistent). For the canvas' "Test", it uses the
 * tenant's most recent COMPLETED meeting — or clearly marked synthetic data when there is none.
 */
@Service
public class WorkflowContextFactory {

    private final MeetingRepository meetings;
    private final MeetingAnalysisRepository analyses;
    private final ProductivityAssessmentRepository assessments;
    private final CustomerConfidenceAssessmentRepository confidences;
    private final String frontendBaseUrl;

    public WorkflowContextFactory(
            MeetingRepository meetings,
            MeetingAnalysisRepository analyses,
            ProductivityAssessmentRepository assessments,
            CustomerConfidenceAssessmentRepository confidences,
            @Value("${nora.frontend.base-url}") String frontendBaseUrl) {
        this.meetings = meetings;
        this.analyses = analyses;
        this.assessments = assessments;
        this.confidences = confidences;
        this.frontendBaseUrl =
                frontendBaseUrl.endsWith("/")
                        ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1)
                        : frontendBaseUrl;
    }

    @Transactional(readOnly = true)
    public WorkflowEventContext forMeeting(
            UUID tenantId, UUID meetingId, String eventType, OffsetDateTime occurredAt) {
        Meeting meeting =
                meetings.findByIdAndTenant(meetingId, tenantId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "meeting do evento não encontrada: " + meetingId));
        MeetingAnalysis analysis =
                analyses.findByMeetingId(meetingId, tenantId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "análise do evento não encontrada: " + meetingId));
        Integer productivityScore =
                assessments.findByMeetingId(meetingId, tenantId).map(a -> a.score()).orElse(null);
        Integer confidenceScore =
                confidences.findByMeetingId(meetingId, tenantId).stream()
                        .findFirst()
                        .map(c -> c.score())
                        .orElse(null);
        List<ActionItemView> actionItems =
                analysis.actionItems().stream()
                        .map(
                                a ->
                                        new ActionItemView(
                                                a.title(),
                                                a.assignee(),
                                                a.priority().name(),
                                                a.dueDate()))
                        .toList();
        return new WorkflowEventContext(
                tenantId,
                eventType,
                meetingId,
                meeting.title(),
                meeting.tags(),
                analysis.summary(),
                analysis.summarySnippet(),
                analysis.decisions().size(),
                analysis.actionItems().size(),
                analysis.risks().size(),
                actionItems,
                productivityScore,
                confidenceScore,
                frontendBaseUrl + "/meetings/" + meetingId,
                occurredAt,
                false);
    }

    /**
     * Context for the canvas' "Test": the tenant's most recent COMPLETED meeting, or sample data
     * (marked as such in the log) when the tenant does not have any analysis yet.
     */
    @Transactional(readOnly = true)
    public WorkflowEventContext latestCompletedOrSample(UUID tenantId, String eventType) {
        List<Meeting> latest =
                meetings.listByTenant(
                                tenantId,
                                new MeetingFilter(null, ProcessingStatus.COMPLETED, null, null),
                                0,
                                1)
                        .items();
        if (!latest.isEmpty()) {
            Meeting meeting = latest.get(0);
            // The meeting may be COMPLETED with the analysis purged (LGPD). Falls back to sample.
            if (analyses.findByMeetingId(meeting.id(), tenantId).isPresent()) {
                return forMeeting(
                        tenantId, meeting.id(), eventType, OffsetDateTime.now(ZoneOffset.UTC));
            }
        }
        return sample(tenantId, eventType);
    }

    private WorkflowEventContext sample(UUID tenantId, String eventType) {
        return new WorkflowEventContext(
                tenantId,
                eventType,
                null,
                "Reunião de exemplo — Renovação Acme",
                List.of("exemplo", "renovacao"),
                "Resumo de exemplo: alinhamento sobre a renovação do contrato com a Acme. Ficou"
                        + " decidido enviar a proposta revisada até sexta-feira; o cliente pediu"
                        + " desconto e prazo de pagamento estendido.",
                "Resumo de exemplo: alinhamento sobre a renovação do contrato com a Acme.",
                1,
                2,
                1,
                List.of(
                        new ActionItemView(
                                "Enviar proposta revisada",
                                "[[PESSOA_1]]",
                                "HIGH",
                                LocalDate.now(ZoneOffset.UTC).plusDays(3)),
                        new ActionItemView("Agendar follow-up", "[[PESSOA_2]]", "MEDIUM", null)),
                62,
                58,
                frontendBaseUrl + "/dashboard",
                OffsetDateTime.now(ZoneOffset.UTC),
                true);
    }
}
