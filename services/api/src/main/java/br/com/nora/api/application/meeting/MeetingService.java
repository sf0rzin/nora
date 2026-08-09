package br.com.nora.api.application.meeting;

import br.com.nora.api.application.analysis.AnalysisService;
import br.com.nora.api.application.ports.AuditPort;
import br.com.nora.api.application.ports.MeetingRepository;
import br.com.nora.api.application.ports.MeetingRepository.MeetingFilter;
import br.com.nora.api.application.ports.MeetingRepository.PagedMeetings;
import br.com.nora.api.application.ports.TranscriptRepository;
import br.com.nora.api.domain.meeting.Meeting;
import br.com.nora.api.domain.meeting.Participant;
import br.com.nora.api.domain.meeting.ProcessingStatus;
import br.com.nora.api.domain.meeting.Transcript;
import br.com.nora.api.domain.meeting.TranscriptFormat;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Application service for meetings (US07).
 *
 * <p>Key rules:
 *
 * <ul>
 *   <li>Every write/read receives an explicit tenantId coming from the caller's JWT.
 *   <li>Upload creates meeting + transcript in a single transaction.
 *   <li>After the upload commit, dispatches async analysis via {@link AnalysisService#runAsync}.
 * </ul>
 */
@Service
public class MeetingService {

    private static final Logger LOG = LoggerFactory.getLogger(MeetingService.class);

    /** Batch size when scanning all of a tenant's meetings for the in-memory IAM filter. */
    private static final int LIST_SCAN_BATCH = 200;

    private final MeetingRepository meetings;
    private final TranscriptRepository transcripts;
    private final ObjectProvider<AnalysisService> analysisServiceProvider;
    private final AuditPort audit;
    private final boolean autoDispatchAnalysis;

    /**
     * Template with PROPAGATION_REQUIRES_NEW, to write from inside {@code afterCommit} — where the
     * current transaction has already committed and a {@code REQUIRED} would join it without ever
     * writing.
     */
    private final TransactionTemplate newTransaction;

    public MeetingService(
            MeetingRepository meetings,
            TranscriptRepository transcripts,
            ObjectProvider<AnalysisService> analysisServiceProvider,
            AuditPort audit,
            PlatformTransactionManager transactionManager,
            @Value("${nora.analysis.auto-dispatch:true}") boolean autoDispatchAnalysis) {
        this.meetings = meetings;
        this.transcripts = transcripts;
        this.analysisServiceProvider = analysisServiceProvider;
        this.audit = audit;
        this.autoDispatchAnalysis = autoDispatchAnalysis;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public Meeting upload(UploadCommand cmd) {
        if (cmd.rawTranscript() == null || cmd.rawTranscript().isBlank()) {
            throw new MeetingException.EmptyTranscript();
        }
        if (cmd.rawTranscript().length() > Transcript.MAX_CHAR_COUNT) {
            throw new MeetingException.TranscriptTooLarge(Transcript.MAX_CHAR_COUNT);
        }
        TranscriptFormat format;
        try {
            format = TranscriptFormat.fromString(cmd.format());
        } catch (IllegalArgumentException ex) {
            throw new MeetingException.UnsupportedFormat(cmd.format());
        }

        Meeting meeting =
                Meeting.newPending(
                        cmd.tenantId(),
                        cmd.ownerUserId(),
                        cmd.title(),
                        cmd.startedAt(),
                        cmd.endedAt(),
                        cmd.language(),
                        format,
                        cmd.participants(),
                        cmd.tags(),
                        cmd.attributes());
        Meeting saved = meetings.save(meeting);
        Transcript transcript =
                Transcript.create(saved.id(), saved.tenantId(), format, cmd.rawTranscript());
        transcripts.save(transcript);

        Map<String, Object> auditPayload = new HashMap<>();
        auditPayload.put("title", saved.title());
        auditPayload.put("transcriptLength", cmd.rawTranscript().length());
        auditPayload.put("format", format.name());
        auditPayload.put(
                "participantCount", cmd.participants() == null ? 0 : cmd.participants().size());
        audit.record(
                saved.tenantId(),
                saved.ownerUserId(),
                "meeting.uploaded",
                "MEETING",
                saved.id(),
                auditPayload);

        scheduleAnalysisAfterCommit(saved.id(), saved.tenantId());
        return saved;
    }

    private void scheduleAnalysisAfterCommit(UUID meetingId, UUID tenantId) {
        if (!autoDispatchAnalysis) {
            return;
        }
        AnalysisService svc = analysisServiceProvider.getIfAvailable();
        if (svc == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            dispatchOrMarkFailed(svc, meetingId, tenantId);
                        }
                    });
        } else {
            dispatchOrMarkFailed(svc, meetingId, tenantId);
        }
    }

    /**
     * Schedules the analysis, handling pool saturation.
     *
     * <p>The {@code AsyncConfig} executor has a queue of 50 and {@code AbortPolicy}: when full, the
     * submit throws {@link RejectedExecutionException}. Coming from {@code afterCommit}, that
     * exception bubbled up to the controller AFTER the commit — the client got a 500 about a
     * meeting that exists, with no analysis scheduled and stuck in PENDING forever. The AsyncConfig
     * javadoc already claimed that "the caller handles it and marks the meeting as FAILED"; this is
     * the caller, and now it actually does handle it.
     */
    private void dispatchOrMarkFailed(AnalysisService svc, UUID meetingId, UUID tenantId) {
        try {
            svc.runAsync(meetingId, tenantId);
        } catch (RejectedExecutionException e) {
            LOG.error(
                    "análise rejeitada pelo executor (pool saturado) meetingId={} tenantId={}",
                    meetingId,
                    tenantId,
                    e);
            try {
                // REQUIRES_NEW, not the adapter's @Transactional. In afterCommit the transaction
                // is still the current one — committed, but not closed — so a REQUIRED from the
                // adapter JOINS it instead of opening another, and the save is never written. The
                // meeting stayed PENDING forever and the log said it had been marked FAILED.
                newTransaction.executeWithoutResult(
                        status ->
                                meetings.findByIdAndTenant(meetingId, tenantId)
                                        .map(m -> m.withStatus(ProcessingStatus.FAILED))
                                        .ifPresent(meetings::save));
            } catch (RuntimeException marking) {
                // Marking FAILED is best-effort: if that also fails, the meeting stays PENDING and
                // a manual reprocess resolves it. Propagating here would only swap one error for
                // another.
                LOG.error("falha ao marcar meeting {} como FAILED", meetingId, marking);
            }
        }
    }

    @Transactional(readOnly = true)
    public PagedMeetings list(UUID tenantId, MeetingFilter filter, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        return meetings.listByTenant(
                tenantId, filter == null ? MeetingFilter.empty() : filter, safePage, safeSize);
    }

    /**
     * Variant for the controller when the listing needs an IAM filter with per-item conditions
     * before paginating. Returns <b>all</b> of the tenant's meetings matching the cheap filters
     * (search/status/date already resolved in SQL), in created_at desc order, scanning the database
     * in batches — the controller applies the IAM filter and paginates in-memory.
     *
     * <p>No silent cap: the old cap (500) dropped meetings beyond the limit, hiding from the user
     * meetings he would have permission to see (tenant with &gt;500 meetings). Future optimisation
     * (performance, not correctness): push the attributes predicate into SQL via {@code
     * meeting_attributes @>} + GIN index (V008) when some tenant reaches scale.
     */
    @Transactional(readOnly = true)
    public List<Meeting> listAllForAuthFilter(UUID tenantId, MeetingFilter filter) {
        MeetingFilter f = filter == null ? MeetingFilter.empty() : filter;
        List<Meeting> all = new ArrayList<>();
        int page = 0;
        while (true) {
            PagedMeetings paged = meetings.listByTenant(tenantId, f, page, LIST_SCAN_BATCH);
            all.addAll(paged.items());
            if (paged.items().isEmpty() || all.size() >= paged.totalItems()) {
                break;
            }
            page++;
        }
        return all;
    }

    @Transactional(readOnly = true)
    public Meeting getById(UUID meetingId, UUID tenantId) {
        return meetings.findByIdAndTenant(meetingId, tenantId)
                .orElseThrow(MeetingException.NotFound::new);
    }

    @Transactional
    public Meeting reprocess(UUID meetingId, UUID tenantId) {
        return reprocess(meetingId, tenantId, null, m -> {});
    }

    /**
     * Variant with an authorization callback executed inside the same transaction after resolving
     * the meeting — avoids TOCTOU between the authz check and execution. The callback receives the
     * loaded meeting and must throw if authorization fails.
     *
     * @param actorUserId who is asking. Written to the audit trail; {@code null} only for the
     *     internal overload, which has no caller identity to record. This used to be omitted and
     *     the audit row carried {@code meeting.ownerUserId()} instead — so a reprocess triggered by
     *     anyone other than the uploader was filed under the uploader's name, and the actual caller
     *     left no trace at all. An investigation into who is driving repeated billable re-analyses
     *     named the wrong user every time.
     */
    @Transactional
    public Meeting reprocess(
            UUID meetingId,
            UUID tenantId,
            UUID actorUserId,
            java.util.function.Consumer<Meeting> authorize) {
        Meeting meeting =
                meetings.findByIdAndTenant(meetingId, tenantId)
                        .orElseThrow(MeetingException.NotFound::new);
        // Authorize before writing anything. This read is deliberately unlocked: holding a write
        // lock through the IAM evaluation (which hits the database) let an unauthorized caller
        // block a meeting of his choosing, in a loop.
        authorize.accept(meeting);

        // One atomic statement decides both whether the transition is legal and whether WE made
        // it. Reprocessing is valid from a terminal state (COMPLETED, FAILED -> "re-analyse");
        // PROCESSING and PENDING already have an analysis on the way, and rescheduling would run
        // a second pipeline over the same meeting.
        //
        // This used to be read-then-check-then-write with a row lock in front. It did not work:
        // the authorization read above puts the entity in the persistence context, and Hibernate
        // then answers the SELECT ... FOR UPDATE from that managed instance without refreshing
        // it, so the second transaction read the pre-lock status and passed the guard anyway.
        // Letting the database evaluate the condition removes the window entirely.
        if (meetings.claimForReanalysis(meetingId, tenantId) == 0) {
            throw new MeetingException.CannotReprocess(
                    "A análise já está em andamento ou na fila; aguarde concluir antes de"
                            + " reprocessar.");
        }
        audit.record(
                tenantId,
                actorUserId != null ? actorUserId : meeting.ownerUserId(),
                "meeting.reprocessed",
                "MEETING",
                meetingId,
                Map.of("previousStatus", meeting.processingStatus().name()));
        scheduleAnalysisAfterCommit(meetingId, tenantId);

        // Re-read rather than derive from the snapshot taken before the claim.
        //
        // `meeting.withStatus(PENDING)` ran the domain state machine against a status read
        // BEFORE the authorization callback, and that callback does two database round trips.
        // A worker finishing in that window moves the row PROCESSING -> COMPLETED, the claim
        // then legitimately succeeds, and the state machine rejects PROCESSING -> PENDING with
        // an IllegalStateException — which is not a MeetingException, so it left the caller a
        // bare 500 and rolled back a claim that had already succeeded, taking the audit row and
        // the dispatch with it. The row is PENDING now; read it and say so.
        return meetings.findByIdAndTenant(meetingId, tenantId)
                .orElseThrow(MeetingException.NotFound::new);
    }

    /**
     * Queues the analysis of a meeting whose goal just changed, when it had already been analysed.
     *
     * <p>Exists because {@code MeetingGoalService} used to write PENDING itself and dispatch
     * nothing, leaving the meeting waiting for a user to press "re-analyse". That was already
     * fragile and became a dead end once reprocess started rejecting PENDING: the productivity
     * assessment had been deleted, the status said queued, and no path could put it back.
     *
     * <p>No authorization here — the caller has already checked {@code meeting:update} on this
     * meeting. Returns false when the meeting was not in a terminal state, in which case the
     * analysis already on its way will pick the new goal up.
     */
    boolean queueAnalysisAfterGoalChange(UUID meetingId, UUID tenantId) {
        if (meetings.claimForReanalysis(meetingId, tenantId) == 0) {
            return false;
        }
        scheduleAnalysisAfterCommit(meetingId, tenantId);
        return true;
    }

    @Transactional(readOnly = true)
    public Transcript getTranscript(UUID meetingId, UUID tenantId) {
        // Enforces the scope: the meeting must exist in the tenant before returning the text.
        meetings.findByIdAndTenant(meetingId, tenantId).orElseThrow(MeetingException.NotFound::new);
        return transcripts
                .findByMeetingAndTenant(meetingId, tenantId)
                .orElseThrow(MeetingException.NotFound::new);
    }

    /** Immutable upload command. */
    public record UploadCommand(
            UUID tenantId,
            UUID ownerUserId,
            String title,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            String language,
            String format,
            List<Participant> participants,
            List<String> tags,
            String rawTranscript,
            Map<String, String> attributes) {

        /** Compat: old calls without attributes assume an empty map. */
        public UploadCommand(
                UUID tenantId,
                UUID ownerUserId,
                String title,
                OffsetDateTime startedAt,
                OffsetDateTime endedAt,
                String language,
                String format,
                List<Participant> participants,
                List<String> tags,
                String rawTranscript) {
            this(
                    tenantId,
                    ownerUserId,
                    title,
                    startedAt,
                    endedAt,
                    language,
                    format,
                    participants,
                    tags,
                    rawTranscript,
                    Map.of());
        }

        public UploadCommand {
            if (attributes == null) {
                attributes = Map.of();
            }
        }
    }
}
