package br.com.nora.api.application.ports;

import br.com.nora.api.domain.meeting.Meeting;
import br.com.nora.api.domain.meeting.ProcessingStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Meeting persistence port. Every query requires tenantId for enforcement. */
public interface MeetingRepository {

    Meeting save(Meeting meeting);

    Optional<Meeting> findByIdAndTenant(UUID id, UUID tenantId);

    /**
     * Atomically moves a meeting from a terminal state (COMPLETED, FAILED) to PENDING. Returns 1
     * when THIS caller performed the transition and 0 when it did not — because the meeting was
     * already queued or running, or does not exist in the tenant.
     *
     * <p>The caller must dispatch the analysis only on 1. That is what keeps a double click from
     * scheduling two pipelines over one meeting: the condition is evaluated by the database against
     * the committed row, so of two concurrent statements only one can match.
     */
    int claimForReanalysis(UUID id, UUID tenantId);

    /** Paginated list by tenant ordered by created_at desc. Page and size are 0-based. */
    PagedMeetings listByTenant(UUID tenantId, MeetingFilter filter, int page, int size);

    /**
     * PHYSICAL hard-delete of a meeting (LGPD: right to be forgotten, ADR 0029). Ignores the
     * soft-delete; the FK CASCADE purges transcript (raw_text = PII), participants, tags and
     * analyses. Returns affected rows (0 = it did not exist in the tenant).
     */
    int hardErase(UUID meetingId, UUID tenantId);

    /**
     * PHYSICAL hard-delete of the tenant's meetings created before {@code cutoff} (retention, ADR
     * 0029). Returns how many were purged.
     */
    int purgeOlderThan(UUID tenantId, OffsetDateTime cutoff);

    /**
     * Optional filters for the listing. Any null field means "no restriction". The search matches
     * by case-insensitive substring over the title.
     */
    record MeetingFilter(
            String search, ProcessingStatus status, OffsetDateTime from, OffsetDateTime to) {
        public static MeetingFilter empty() {
            return new MeetingFilter(null, null, null, null);
        }
    }

    record PagedMeetings(List<Meeting> items, long totalItems, int page, int size) {
        public int totalPages() {
            if (size <= 0) {
                return 0;
            }
            return (int) Math.ceil((double) totalItems / (double) size);
        }
    }
}
