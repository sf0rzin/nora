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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Monta o {@link WorkflowEventContext} a partir do estado COMMITADO no banco (o evento é publicado
 * pós-commit, então a leitura aqui é consistente). Para o "Testar" do canvas, usa a reunião
 * COMPLETED mais recente do tenant — ou dados sintéticos claramente marcados quando não há nenhuma.
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
                        .map(a -> new ActionItemView(a.title(), a.assignee(), a.priority().name()))
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
     * Contexto para o "Testar" do canvas: a reunião COMPLETED mais recente do tenant, ou dados de
     * exemplo (marcados como tal no log) quando o tenant ainda não tem nenhuma análise.
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
            // A reunião pode estar COMPLETED com análise purgada (LGPD). Cai pro sample nesse caso.
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
                        new ActionItemView("Enviar proposta revisada", "[[PESSOA_1]]", "HIGH"),
                        new ActionItemView("Agendar follow-up", "[[PESSOA_2]]", "MEDIUM")),
                62,
                58,
                frontendBaseUrl + "/dashboard",
                OffsetDateTime.now(ZoneOffset.UTC),
                true);
    }
}
