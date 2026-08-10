package br.com.nora.api.application.ports;

import br.com.nora.api.domain.analysis.MeetingAnalysis;
import br.com.nora.api.domain.customer.BuyingSignal;
import br.com.nora.api.domain.customer.ConfidenceBand;
import br.com.nora.api.domain.customer.ConfidenceTrend;
import br.com.nora.api.domain.customer.Objection;
import br.com.nora.api.domain.meeting.productivity.MeetingGoal;
import br.com.nora.api.domain.meeting.productivity.ProductivityAssessment;
import br.com.nora.api.domain.tenant.TenantContext;
import br.com.nora.api.infrastructure.nlp.SplitDtos;
import br.com.nora.api.infrastructure.nlp.WorkerDtos;
import br.com.nora.api.infrastructure.nlp.WorkerDtos.LiveAnalyzeResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * NLP Worker (FastAPI) client. The infrastructure implementation makes the HTTP /analyze call and
 * returns the already-built aggregate (without persisted id/createdAt) together with the
 * productivity opt-in (ADR 0005), when there is a declared goal.
 */
public interface NlpWorkerClient {

    /**
     * Submits the text to the worker and returns the validated analysis + productivity opt-in (when
     * there is a goal).
     *
     * @param meetingId meeting id (correlation id)
     * @param tenantId owning tenant id
     * @param language ISO 639-1 (e.g. "pt-BR"), may be null
     * @param transcript raw transcript text (after upload normalization)
     * @param tenantContext optional commercial context used in the prompt
     * @param goal goal declared by the user (opt-in). When absent, the worker does not emit
     *     productivity.
     */
    AnalysisResult analyze(
            UUID meetingId,
            UUID tenantId,
            String language,
            String transcript,
            Optional<TenantContext> tenantContext,
            Optional<MeetingGoal> goal);

    LiveAnalyzeResponse analyzeLive(
            String transcriptChunk, String language, WorkerDtos.LiveHighlights previousHighlights);

    /**
     * Detects boundaries between distinct meetings concatenated into a single .txt file (split
     * preview). The worker applies PII Shield line by line and returns segments with 1-based {@code
     * startLine}/{@code endLine} valid for the original file; nothing is persisted.
     *
     * <p>A {@code default} that throws: only the real HTTP implementation (and test stubs that
     * exercise split) need to override it — the other IT stubs keep compiling without knowing about
     * this flow.
     */
    default SplitDtos.SplitResponse split(String transcript, String language) {
        throw new UnsupportedOperationException("split not implemented by this client");
    }

    /**
     * Combined result: analysis always present, productivity optional (only when a goal was
     * provided AND the worker managed to compute it) and customerConfidence optional (only for
     * external meetings, when the worker emits the block).
     */
    record AnalysisResult(
            MeetingAnalysis analysis,
            Optional<ProductivityAssessment> productivity,
            Optional<CustomerConfidenceCarrier> customerConfidence) {

        public static AnalysisResult of(MeetingAnalysis analysis) {
            return new AnalysisResult(analysis, Optional.empty(), Optional.empty());
        }

        public static AnalysisResult of(MeetingAnalysis analysis, ProductivityAssessment p) {
            return new AnalysisResult(analysis, Optional.ofNullable(p), Optional.empty());
        }

        public static AnalysisResult of(
                MeetingAnalysis analysis,
                ProductivityAssessment p,
                CustomerConfidenceCarrier confidence) {
            return new AnalysisResult(
                    analysis, Optional.ofNullable(p), Optional.ofNullable(confidence));
        }
    }

    /**
     * Intermediate carrier for the worker's {@code customerConfidence} block (ADR 0015). It is NOT
     * the domain aggregate: {@code CustomerConfidenceAssessment} requires a {@code
     * customerAccountId} that only exists after the get-or-create of the {@link
     * br.com.nora.api.domain.customer.CustomerAccount} in the backend. The application service
     * resolves the account from {@code accountName} and only then builds the aggregate.
     *
     * <p>{@code trend} here is the worker's guess — the backend IGNORES it and recomputes it
     * server-side based on the account's history (the backend is authoritative, see ADR 0015 /
     * schema).
     *
     * @param accountName name of the detected customer/lead (null/blank => internal meeting, no-op)
     */
    record CustomerConfidenceCarrier(
            String accountName,
            int score,
            ConfidenceBand band,
            ConfidenceTrend trend,
            List<BuyingSignal> buyingSignals,
            List<Objection> objections,
            String rationale) {

        public CustomerConfidenceCarrier {
            buyingSignals = buyingSignals == null ? List.of() : List.copyOf(buyingSignals);
            objections = objections == null ? List.of() : List.copyOf(objections);
        }
    }
}
