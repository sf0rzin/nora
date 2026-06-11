package br.com.nora.api.domain.event;

import br.com.nora.api.domain.analysis.Severity;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento de domínio: a análise identificou um risco de severidade ALTA na reunião. Emitido por
 * {@code AnalysisService.run()} APÓS o commit do COMPLETED, um evento POR risco — e SÓ para {@code
 * Severity.HIGH} (alerta de risco é exceção, não ruído).
 *
 * <p>Wire format do gatilho correspondente no Flows: {@code meeting.risk_detected}.
 */
public record MeetingRiskDetectedEvent(
        UUID tenantId, UUID meetingId, String description, Severity severity, Instant occurredAt) {

    public MeetingRiskDetectedEvent {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (meetingId == null) {
            throw new IllegalArgumentException("meetingId is required");
        }
    }
}
