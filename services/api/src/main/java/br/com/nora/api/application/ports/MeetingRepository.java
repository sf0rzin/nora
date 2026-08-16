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
     * Atomically moves a meeting to PENDING for re-analysis. Returns 1 when THIS caller performed
     * the transition and 0 when it did not — because the meeting is running, was queued moments
     * ago, or does not exist in the tenant.
     *
     * <p>Claimable means COMPLETED, FAILED, or a PENDING row that has sat untouched long enough to
     * count as abandoned rather than queued. The adapter owns that window; a restart, a disabled
     * auto-dispatch or a missing analysis bean all leave a meeting PENDING with nothing in flight,
     * and nothing else ever moves it.
     *
     * <p>The caller must dispatch the analysis only on 1. That is what keeps a double click from
     * scheduling two pipelines over one meeting: the condition is evaluated by the database against
     * the committed row, so of two concurrent statements only one can match.
     */
    int claimForReanalysis(UUID id, UUID tenantId);

    /**
     * Moves every PROCESSING meeting of the tenant last touched before {@code staleBefore} to
     * FAILED, and returns how many were moved.
     *
     * <p>PROCESSING is written by the analysis pipeline and only the pipeline itself ever leaves it
     * — COMPLETED on success, FAILED from its own catch. A JVM that dies mid-roundtrip never runs
     * that catch, so the row keeps claiming an analysis is in flight when nothing is left to finish
     * it. {@link #claimForReanalysis} refuses PROCESSING by design (it cannot tell "running" from
     * "abandoned"), which left such a meeting with its re-analyse button disabled forever, with no
     * way out through the product.
     *
     * <p>Time is the only signal available, so the caller owns the window and must keep it
     * comfortably above the worker timeout: a meeting that is merely slow must never be reaped.
     * Once FAILED, the meeting is claimable again and the user can re-analyse it.
     *
     * <p>The condition is evaluated by the database inside the write, so a pipeline that commits
     * COMPLETED concurrently is not overwritten.
     */
    int failStuckProcessing(UUID tenantId, OffsetDateTime staleBefore);

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
