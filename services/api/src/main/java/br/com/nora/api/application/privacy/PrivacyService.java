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
 * Privacy/LGPD operations (ADR 0029): right to be forgotten (hard-erase) and retention (purge by
 * age).
 *
 * <p>Unlike the default soft-delete (ADR 0021), here the delete is PHYSICAL: the FK {@code ON
 * DELETE CASCADE} (V004) removes the meeting's transcript ({@code raw_text} = PII at rest),
 * participants, tags and analyses. It is the "conscious exception" that ADR 0021 already foresaw
 * for LGPD.
 *
 * <p>{@code @Transactional} guarantees that, under RLS enforce (ADR 0028), the {@code
 * TenantRlsAspect} applies the GUC {@code nora.current_tenant_id} on the delete's transaction —
 * necessary because {@code meetings} is an enforced table.
 */
@Service
public class PrivacyService {

    private static final Logger LOG = LoggerFactory.getLogger(PrivacyService.class);

    private final MeetingRepository meetings;

    public PrivacyService(MeetingRepository meetings) {
        this.meetings = meetings;
    }

    /**
     * PERMANENTLY deletes a meeting and all cascading PII (right to be forgotten). Throws {@link
     * MeetingException.NotFound} if the meeting does not exist in the tenant (avoids leaking
     * cross-tenant existence).
     */
    @Transactional
    public void eraseMeeting(UUID meetingId, UUID tenantId) {
        int rows = meetings.hardErase(meetingId, tenantId);
        if (rows == 0) {
            throw new MeetingException.NotFound();
        }
        // PII-safe: ids only, never content.
        LOG.info("LGPD erasure: meeting={} purgado (tenant={})", meetingId, tenantId);
    }

    /** Purges the tenant's meetings created before {@code cutoff} (retention). Returns how many. */
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
