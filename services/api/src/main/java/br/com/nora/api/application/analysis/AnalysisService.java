package br.com.nora.api.application.analysis;

import br.com.nora.api.application.customer.CustomerConfidenceService;
import br.com.nora.api.application.embedding.EmbeddingService;
import br.com.nora.api.application.meeting.MeetingException;
import br.com.nora.api.application.platform.UsageRecorder;
import br.com.nora.api.application.ports.DomainEventPublisher;
import br.com.nora.api.application.ports.MeetingAnalysisRepository;
import br.com.nora.api.application.ports.MeetingGoalRepository;
import br.com.nora.api.application.ports.MeetingRepository;
import br.com.nora.api.application.ports.NlpWorkerClient;
import br.com.nora.api.application.ports.NlpWorkerClient.AnalysisResult;
import br.com.nora.api.application.ports.ProductivityAssessmentRepository;
import br.com.nora.api.application.ports.TenantContextRepository;
import br.com.nora.api.application.ports.TenantRlsContext;
import br.com.nora.api.application.ports.TranscriptRepository;
import br.com.nora.api.domain.analysis.ActionItem;
import br.com.nora.api.domain.analysis.MeetingAnalysis;
import br.com.nora.api.domain.analysis.Risk;
import br.com.nora.api.domain.analysis.Severity;
import br.com.nora.api.domain.event.ActionItemCreatedEvent;
import br.com.nora.api.domain.event.MeetingAnalysisCompletedEvent;
import br.com.nora.api.domain.event.MeetingRiskDetectedEvent;
import br.com.nora.api.domain.meeting.Meeting;
import br.com.nora.api.domain.meeting.ProcessingStatus;
import br.com.nora.api.domain.meeting.Transcript;
import br.com.nora.api.domain.meeting.productivity.MeetingGoal;
import br.com.nora.api.domain.meeting.productivity.ProductivityAssessment;
import br.com.nora.api.domain.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the analysis pipeline: loads transcript + context + optional goal, calls the worker,
 * persists the analysis (and the productivity assessment when there is a goal) and updates the
 * Meeting's processingStatus.
 *
 * <p>{@link #runAsync(UUID, UUID)} is fired after the upload (fire-and-forget) and runs on a
 * separate thread via @Async. Each step changes the meeting status (PROCESSING -> COMPLETED |
 * FAILED) in short, independent transactions.
 */
@Service
public class AnalysisService {

    private static final Logger LOG = LoggerFactory.getLogger(AnalysisService.class);

    private final MeetingRepository meetings;
    private final TranscriptRepository transcripts;
    private final TenantContextRepository tenantContexts;
    private final MeetingAnalysisRepository analyses;
    private final MeetingGoalRepository goals;
    private final ProductivityAssessmentRepository assessments;
    private final NlpWorkerClient worker;
    private final CustomerConfidenceService customerConfidence;
    private final UsageRecorder usageRecorder;
    private final TenantRlsContext rlsContext;
    private final EmbeddingService embeddings;
    private final DomainEventPublisher events;

    public AnalysisService(
            MeetingRepository meetings,
            TranscriptRepository transcripts,
            TenantContextRepository tenantContexts,
            MeetingAnalysisRepository analyses,
            MeetingGoalRepository goals,
            ProductivityAssessmentRepository assessments,
            NlpWorkerClient worker,
            CustomerConfidenceService customerConfidence,
            UsageRecorder usageRecorder,
            TenantRlsContext rlsContext,
            EmbeddingService embeddings,
            DomainEventPublisher events) {
        this.meetings = meetings;
        this.transcripts = transcripts;
        this.tenantContexts = tenantContexts;
        this.analyses = analyses;
        this.goals = goals;
        this.assessments = assessments;
        this.worker = worker;
        this.customerConfidence = customerConfidence;
        this.usageRecorder = usageRecorder;
        this.rlsContext = rlsContext;
        this.embeddings = embeddings;
        this.events = events;
    }

    /**
     * Runs the pipeline in the background. Fire-and-forget call from MeetingService after upload.
     */
    @Async
    public void runAsync(UUID meetingId, UUID tenantId) {
        // Under RLS enforce (ADR 0028) this executor thread does NOT inherit the request's
        // TenantContextHolder — we propagate the tenant explicitly so that TenantRlsAspect applies
        // the GUC on the pipeline transactions (transcript read + analysis write, enforced
        // tables). We clear it in the finally (the pool thread is reused across tasks).
        rlsContext.set(tenantId);
        try {
            run(meetingId, tenantId);
        } catch (RuntimeException ex) {
            LOG.error(
                    "Analysis pipeline failed meetingId={} tenantId={} cause={}",
                    meetingId,
                    tenantId,
                    ex.getMessage());
            safeMarkFailed(meetingId, tenantId);
        } finally {
            rlsContext.clear();
        }
    }

    /**
     * Synchronous pipeline (testable). Does not wrap the external call in a transaction to avoid
     * holding Postgres connections busy during the roundtrip to the worker.
     */
    public MeetingAnalysis run(UUID meetingId, UUID tenantId) {
        Meeting meeting = loadMeeting(meetingId, tenantId);
        // `markStatus` persists with the new status but the local `meeting`
        // reference stays in the old state. Reassigning the return value ensures
        // that the `markStatusAndSnippet` below finds a valid transition in the
        // state machine (PROCESSING -> COMPLETED) instead of the old
        // PENDING -> COMPLETED that would be rejected.
        meeting = markStatus(meeting, ProcessingStatus.PROCESSING);

        Transcript transcript = loadTranscript(meetingId, tenantId);
        Optional<TenantContext> ctx = tenantContexts.findByTenantId(tenantId);
        Optional<MeetingGoal> goal = goals.findByMeetingId(meetingId, tenantId);

        AnalysisResult result =
                worker.analyze(
                        meetingId, tenantId, meeting.language(), transcript.rawText(), ctx, goal);
        MeetingAnalysis saved = analyses.save(result.analysis());
        result.productivity()
                .ifPresentOrElse(
                        p -> assessments.save(p),
                        () -> assessments.deleteByMeetingId(meetingId, tenantId));
        persistCustomerConfidence(meetingId, tenantId, result);
        emitUsage(tenantId, saved);
        markStatusAndSnippet(meeting, ProcessingStatus.COMPLETED, saved.summarySnippet());
        // NORA Flows domain events: emitted AFTER the commit of the COMPLETED above
        // (markStatusAndSnippet is @Transactional and has already returned). Fail-soft: workflow
        // never takes down nor reverts the analysis.
        publishDomainEvents(meetingId, tenantId, saved);
        // RAG: indexes ONLY the SUMMARY (summarySnippet), which has already been through the PII
        // Shield (it was generated from the redacted transcript). The meeting title comes raw
        // from the upload and may contain unredacted PII — that is why it stays OUT of the
        // embedding payload (ADR 0012). Best-effort — the EmbeddingService swallows failures and
        // never takes down the analysis.
        String snippet = saved.summarySnippet() == null ? "" : saved.summarySnippet().trim();
        if (!snippet.isEmpty()) {
            embeddings.index(meetingId, tenantId, snippet);
        }
        return saved;
    }

    /**
     * Emits the NORA Flows domain events post-COMPLETED: analysis completed + one event per action
     * item + one per HIGH severity risk (only the top level becomes an alert — low/medium risk is
     * noise as a trigger). Each publish is fail-soft individually: a failure in one event does not
     * prevent the others and NEVER takes down the pipeline.
     */
    private void publishDomainEvents(UUID meetingId, UUID tenantId, MeetingAnalysis saved) {
        Instant occurredAt = Instant.now();
        publishSafely(
                new MeetingAnalysisCompletedEvent(tenantId, meetingId, saved.id(), occurredAt));
        for (ActionItem item : saved.actionItems()) {
            publishSafely(
                    new ActionItemCreatedEvent(
                            tenantId,
                            meetingId,
                            item.title(),
                            item.assignee(),
                            item.priority(),
                            occurredAt));
        }
        for (Risk risk : saved.risks()) {
            if (risk.severity() == Severity.HIGH) {
                publishSafely(
                        new MeetingRiskDetectedEvent(
                                tenantId, meetingId, risk.text(), risk.severity(), occurredAt));
            }
        }
    }

    private void publishSafely(Object event) {
        try {
            events.publish(event);
        } catch (RuntimeException ex) {
            LOG.warn(
                    "Failed to publish domain event {} cause={}",
                    event.getClass().getSimpleName(),
                    ex.getMessage());
        }
    }

    /**
     * Emits in-process cost telemetry for the analysis (ADR 0024). Uses the {@code metadata} the
     * worker already reports (model + tokens + latency, persisted in the {@link MeetingAnalysis}).
     * Tolerant: a failure here NEVER takes down the pipeline (the {@link UsageRecorder} is no-op
     * when the control plane is off and is already fail-soft when on).
     */
    private void emitUsage(UUID tenantId, MeetingAnalysis a) {
        try {
            boolean stub = a.modelVersion() != null && a.modelVersion().startsWith("stub");
            usageRecorder.recordAnalysisUsage(
                    tenantId,
                    a.modelVersion(),
                    a.tokensInput(),
                    a.tokensOutput(),
                    a.processingMillis(),
                    stub);
        } catch (RuntimeException ex) {
            LOG.warn(
                    "Failed to emit analysis usage meetingId={} tenantId={} cause={}",
                    a.meetingId(),
                    tenantId,
                    ex.getMessage());
        }
    }

    /**
     * Persists the opt-in Customer Confidence (ADR 0015) when the worker emitted the block.
     * Resilient: a failure here does not take down the analysis — the {@link MeetingAnalysis} has
     * already been written and the meeting still transitions to COMPLETED (log + continue, same as
     * the productivity tolerance).
     */
    private void persistCustomerConfidence(UUID meetingId, UUID tenantId, AnalysisResult result) {
        if (result.customerConfidence().isEmpty()) {
            return;
        }
        try {
            customerConfidence.persist(tenantId, meetingId, result.customerConfidence().get());
        } catch (RuntimeException ex) {
            // PII-safe: do not log accountName nor quotes, only ids and the cause.
            LOG.warn(
                    "Failed to persist customer confidence meetingId={} tenantId={} cause={}",
                    meetingId,
                    tenantId,
                    ex.getMessage());
        }
    }

    private Meeting loadMeeting(UUID meetingId, UUID tenantId) {
        return meetings.findByIdAndTenant(meetingId, tenantId)
                .orElseThrow(() -> new AnalysisException.MeetingNotFound(meetingId));
    }

    private Transcript loadTranscript(UUID meetingId, UUID tenantId) {
        return transcripts
                .findByMeetingAndTenant(meetingId, tenantId)
                .orElseThrow(() -> new AnalysisException.TranscriptMissing(meetingId));
    }

    @Transactional
    Meeting markStatus(Meeting meeting, ProcessingStatus status) {
        Meeting updated = meeting.withStatus(status);
        return meetings.save(updated);
    }

    @Transactional
    void markStatusAndSnippet(Meeting meeting, ProcessingStatus status, String snippet) {
        Meeting updated = meeting.withStatus(status).withSummarySnippet(snippet);
        meetings.save(updated);
    }

    @Transactional
    void safeMarkFailed(UUID meetingId, UUID tenantId) {
        meetings.findByIdAndTenant(meetingId, tenantId)
                .ifPresent(m -> meetings.save(m.withStatus(ProcessingStatus.FAILED)));
    }

    @Transactional(readOnly = true)
    public Optional<MeetingAnalysis> findByMeeting(UUID meetingId, UUID tenantId) {
        // Ensures scope: meeting must exist in the tenant.
        meetings.findByIdAndTenant(meetingId, tenantId).orElseThrow(MeetingException.NotFound::new);
        return analyses.findByMeetingId(meetingId, tenantId);
    }

    /** Retrieves the persisted productivity (opt-in). Empty when there is none. */
    @Transactional(readOnly = true)
    public Optional<ProductivityAssessment> findProductivity(UUID meetingId, UUID tenantId) {
        meetings.findByIdAndTenant(meetingId, tenantId).orElseThrow(MeetingException.NotFound::new);
        return assessments.findByMeetingId(meetingId, tenantId);
    }

    /** Listing row enrichment: counts + productivity band/score. */
    public record ListEnrichment(
            int actionItems,
            int risks,
            int opportunities,
            String productivityBand,
            Integer productivityScore) {}

    /**
     * Enriches the meeting listing items in BATCH: counts (action items/risks/opportunities) and
     * productivity band/score, in TWO aggregate queries for the whole set of IDs — instead of one
     * full analysis per item (an N+1 that loaded 4 collections).
     */
    @Transactional(readOnly = true)
    public java.util.Map<UUID, ListEnrichment> enrichListItems(
            java.util.Collection<UUID> meetingIds, UUID tenantId) {
        if (meetingIds == null || meetingIds.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<UUID, MeetingAnalysisRepository.AnalysisCounts> countsByMeeting =
                new java.util.HashMap<>();
        for (MeetingAnalysisRepository.AnalysisCounts c :
                analyses.countsByMeetingIds(meetingIds, tenantId)) {
            countsByMeeting.put(c.meetingId(), c);
        }
        java.util.Map<UUID, ProductivityAssessmentRepository.BandScore> bandByMeeting =
                new java.util.HashMap<>();
        for (ProductivityAssessmentRepository.BandScore b :
                assessments.bandsByMeetingIds(meetingIds, tenantId)) {
            bandByMeeting.put(b.meetingId(), b);
        }
        java.util.Map<UUID, ListEnrichment> out = new java.util.HashMap<>();
        for (UUID id : meetingIds) {
            MeetingAnalysisRepository.AnalysisCounts c = countsByMeeting.get(id);
            ProductivityAssessmentRepository.BandScore b = bandByMeeting.get(id);
            out.put(
                    id,
                    new ListEnrichment(
                            c == null ? 0 : c.actionItems(),
                            c == null ? 0 : c.risks(),
                            c == null ? 0 : c.opportunities(),
                            b == null ? null : b.band(),
                            b == null ? null : b.score()));
        }
        return out;
    }
}
