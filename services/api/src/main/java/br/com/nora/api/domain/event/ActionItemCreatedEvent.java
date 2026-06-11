package br.com.nora.api.domain.event;

import br.com.nora.api.domain.analysis.Priority;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento de domínio: um action item foi extraído por uma análise concluída. Emitido por {@code
 * AnalysisService.run()} APÓS o commit do COMPLETED, um evento POR item — workflows com o gatilho
 * correspondente executam uma vez por action item.
 *
 * <p>Wire format do gatilho correspondente no Flows: {@code action_item.created}.
 */
public record ActionItemCreatedEvent(
        UUID tenantId,
        UUID meetingId,
        String title,
        String assignee,
        Priority priority,
        Instant occurredAt) {

    public ActionItemCreatedEvent {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (meetingId == null) {
            throw new IllegalArgumentException("meetingId is required");
        }
    }
}
