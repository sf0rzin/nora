package br.com.nora.api.application.privacy;

import br.com.nora.api.application.meeting.MeetingException;
import br.com.nora.api.application.ports.MeetingRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operações de privacidade/LGPD (ADR 0029): direito ao esquecimento (hard-erase) e retenção (purga
 * por idade).
 *
 * <p>Diferente do soft-delete default (ADR 0021), aqui o delete é FÍSICO: o FK {@code ON DELETE
 * CASCADE} (V004) remove o transcript ({@code raw_text} = PII em repouso), participants, tags e
 * análises do meeting. É a "exceção consciente" que o ADR 0021 já previa pra LGPD.
 *
 * <p>{@code @Transactional} garante que, sob RLS enforce (ADR 0028), o {@code TenantRlsAspect}
 * aplique o GUC {@code nora.current_tenant_id} na transação do delete — necessário porque {@code
 * meetings} é tabela enforced.
 */
@Service
public class PrivacyService {

    private static final Logger LOG = LoggerFactory.getLogger(PrivacyService.class);

    private final MeetingRepository meetings;

    public PrivacyService(MeetingRepository meetings) {
        this.meetings = meetings;
    }

    /**
     * Apaga DEFINITIVAMENTE um meeting e todo o PII em cascata (direito ao esquecimento). Lança
     * {@link MeetingException.NotFound} se o meeting não existir no tenant (evita vazar existência
     * cross-tenant).
     */
    @Transactional
    public void eraseMeeting(UUID meetingId, UUID tenantId) {
        int rows = meetings.hardErase(meetingId, tenantId);
        if (rows == 0) {
            throw new MeetingException.NotFound();
        }
        // PII-safe: só ids, nunca conteúdo.
        LOG.info("LGPD erasure: meeting={} purgado (tenant={})", meetingId, tenantId);
    }

    /** Purga meetings do tenant criados antes de {@code cutoff} (retenção). Retorna quantos. */
    @Transactional
    public int purgeOlderThan(UUID tenantId, OffsetDateTime cutoff) {
        int rows = meetings.purgeOlderThan(tenantId, cutoff);
        if (rows > 0) {
            LOG.info(
                    "Retenção: {} meeting(s) purgado(s) do tenant={} (anteriores a {})",
                    rows,
                    tenantId,
                    cutoff);
        }
        return rows;
    }
}
