package br.com.nora.api.application.workflow;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Snapshot dos dados da reunião/análise no momento do disparo — é o que condições avaliam e ações
 * consomem (placeholders {{meeting.title}} etc.). Construído pelo {@link WorkflowContextFactory} a
 * partir do estado commitado no banco. {@code sampleData} marca contexto sintético usado pelo
 * "Testar" quando o tenant ainda não tem reunião analisada.
 */
public record WorkflowEventContext(
        UUID tenantId,
        String eventType,
        UUID meetingId,
        String meetingTitle,
        List<String> tags,
        String summary,
        String summarySnippet,
        int decisionsCount,
        int actionItemsCount,
        int risksCount,
        List<ActionItemView> actionItems,
        Integer productivityScore,
        Integer customerConfidenceScore,
        String meetingUrl,
        OffsetDateTime occurredAt,
        boolean sampleData) {

    /** Projeção mínima de um action item para condições ({@code priority_equals}) e templates. */
    public record ActionItemView(String title, String assignee, String priority) {}
}
