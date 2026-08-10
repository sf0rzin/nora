package br.com.nora.api.application.privacy;

import br.com.nora.api.application.meeting.MeetingException;
import br.com.nora.api.application.ports.MeetingRepository;
import br.com.nora.api.domain.meeting.Meeting;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Consumer;
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
     *
     * <p>The meeting is resolved and authorized INSIDE this transaction — the same shape {@code
     * MeetingService.reprocess} uses, so the attributes the decision was taken on cannot change
     * between the check and the delete (#51). Resolution comes first on purpose: a meeting of
     * another tenant answers 404 and never reaches the IAM decision, so this endpoint keeps not
     * leaking cross-tenant existence.
     *
     * @param authorize decides on the loaded meeting, with its attributes in hand, and must throw
     *     if authorization fails. Authorizing on the id alone leaves the condition attributes out
     *     of the request context, and a condition over a missing attribute makes the statement not
     *     match — which silently drops a Deny, on an operation that is a permanent hard-delete of
     *     the meeting and every piece of PII linked to it.
     */
    @Transactional
    public void eraseMeeting(UUID meetingId, UUID tenantId, Consumer<Meeting> authorize) {
        Meeting meeting =
                meetings.findByIdAndTenant(meetingId, tenantId)
                        .orElseThrow(MeetingException.NotFound::new);
        authorize.accept(meeting);
        int rows = meetings.hardErase(meetingId, tenantId);
        if (rows == 0) {
            throw new MeetingException.NotFound();
        }
        // PII-safe: ids only, never content.
        LOG.info("LGPD erasure: meeting={} purged (tenant={})", meetingId, tenantId);
    }

    /** Purges the tenant's meetings created before {@code cutoff} (retention). Returns how many. */
    @Transactional
    public int purgeOlderThan(UUID tenantId, OffsetDateTime cutoff) {
        int rows = meetings.purgeOlderThan(tenantId, cutoff);
        if (rows > 0) {
            LOG.info(
                    "Retention: {} meeting(s) purged for tenant={} (older than {})",
                    rows,
                    tenantId,
                    cutoff);
        }
        return rows;
    }
}
