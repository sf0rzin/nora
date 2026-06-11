package br.com.nora.api.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento de domínio: a análise de uma reunião terminou com sucesso (meeting transicionou para
 * COMPLETED e a {@code MeetingAnalysis} já está persistida). Emitido por {@code
 * AnalysisService.run()} APÓS o commit da transição de status — listeners (ex.: WorkflowEngine do
 * NORA Flows) podem ler o estado completo do banco com segurança.
 *
 * <p>Wire format do gatilho correspondente no Flows: {@code meeting.analysis_completed}.
 */
public record MeetingAnalysisCompletedEvent(
        UUID tenantId, UUID meetingId, UUID analysisId, Instant occurredAt) {

    public MeetingAnalysisCompletedEvent {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (meetingId == null) {
            throw new IllegalArgumentException("meetingId is required");
        }
    }
}
