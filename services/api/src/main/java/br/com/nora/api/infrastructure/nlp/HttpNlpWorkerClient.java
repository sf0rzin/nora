package br.com.nora.api.infrastructure.nlp;

import br.com.nora.api.application.analysis.AnalysisException;
import br.com.nora.api.application.ports.NlpWorkerClient;
import br.com.nora.api.application.ports.NlpWorkerClient.CustomerConfidenceCarrier;
import br.com.nora.api.domain.analysis.ActionItem;
import br.com.nora.api.domain.analysis.Decision;
import br.com.nora.api.domain.analysis.MeetingAnalysis;
import br.com.nora.api.domain.analysis.Opportunity;
import br.com.nora.api.domain.analysis.OpportunityCategory;
import br.com.nora.api.domain.analysis.Priority;
import br.com.nora.api.domain.analysis.Risk;
import br.com.nora.api.domain.analysis.RiskCategory;
import br.com.nora.api.domain.analysis.Sentiment;
import br.com.nora.api.domain.analysis.Severity;
import br.com.nora.api.domain.customer.BuyingSignal;
import br.com.nora.api.domain.customer.BuyingSignalType;
import br.com.nora.api.domain.customer.ConfidenceBand;
import br.com.nora.api.domain.customer.ConfidenceTrend;
import br.com.nora.api.domain.customer.Objection;
import br.com.nora.api.domain.customer.ObjectionSeverity;
import br.com.nora.api.domain.customer.ObjectionType;
import br.com.nora.api.domain.meeting.productivity.CoverageStatus;
import br.com.nora.api.domain.meeting.productivity.ExpectedOutcome;
import br.com.nora.api.domain.meeting.productivity.MeetingGoal;
import br.com.nora.api.domain.meeting.productivity.OutcomeCoverage;
import br.com.nora.api.domain.meeting.productivity.ProductivityAssessment;
import br.com.nora.api.domain.meeting.productivity.ProductivityBand;
import br.com.nora.api.domain.tenant.TenantContext;
import br.com.nora.api.infrastructure.nlp.WorkerDtos.LiveAnalyzeResponse;
import br.com.nora.api.infrastructure.nlp.WorkerDtos.LiveHighlights;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

/**
 * HTTP client for the NLP worker (FastAPI /analyze). Blocking on purpose: called from @Async code
 * in AnalysisService.
 */
@Component
public class HttpNlpWorkerClient implements NlpWorkerClient {

    private static final Logger LOG = LoggerFactory.getLogger(HttpNlpWorkerClient.class);
    private static final String DEFAULT_VALUE_PROPOSITION = "(not provided)";
    private static final String DEFAULT_COMPANY_NAME = "(company not configured)";
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final WebClient client;
    private final WebClient liveClient;
    private final NlpWorkerProperties props;

    public HttpNlpWorkerClient(WebClient.Builder builder, NlpWorkerProperties props) {
        this.props = props;
        long timeoutMs = Math.max(1_000L, props.getTimeoutMillis());
        HttpClient http =
                HttpClient.create()
                        .option(
                                ChannelOption.CONNECT_TIMEOUT_MILLIS,
                                (int) Math.min(timeoutMs, 10_000L))
                        .responseTimeout(Duration.ofMillis(timeoutMs))
                        .doOnConnected(
                                conn ->
                                        conn.addHandlerLast(
                                                        new ReadTimeoutHandler(
                                                                timeoutMs, TimeUnit.MILLISECONDS))
                                                .addHandlerLast(
                                                        new WriteTimeoutHandler(
                                                                timeoutMs, TimeUnit.MILLISECONDS)));
        WebClient.Builder analyzeBuilder =
                builder.baseUrl(props.getBaseUrl())
                        .clientConnector(new ReactorClientHttpConnector(http))
                        .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        applyInternalToken(analyzeBuilder, props);
        this.client = analyzeBuilder.build();
        long liveTimeoutMs = Math.max(1_000L, Math.min(15_000L, props.getTimeoutMillis()));
        HttpClient liveHttp =
                HttpClient.create()
                        .option(
                                ChannelOption.CONNECT_TIMEOUT_MILLIS,
                                (int) Math.min(liveTimeoutMs, 5_000L))
                        .responseTimeout(Duration.ofMillis(liveTimeoutMs))
                        .doOnConnected(
                                conn ->
                                        conn.addHandlerLast(
                                                        new ReadTimeoutHandler(
                                                                liveTimeoutMs,
                                                                TimeUnit.MILLISECONDS))
                                                .addHandlerLast(
                                                        new WriteTimeoutHandler(
                                                                liveTimeoutMs,
                                                                TimeUnit.MILLISECONDS)));
        WebClient.Builder liveBuilder =
                builder.baseUrl(props.getBaseUrl())
                        .clientConnector(new ReactorClientHttpConnector(liveHttp))
                        .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        applyInternalToken(liveBuilder, props);
        this.liveClient = liveBuilder.build();
    }

    /**
     * Adds {@code X-Internal-Token} only when a value is configured.
     *
     * <p>The guard is not cosmetic: {@code defaultHeader} with an empty string sends the header
     * present and empty, which is a different thing from not sending it — it reads as a malformed
     * credential rather than as an absent one.
     *
     * <p>Both WebClients go through here. The live one is easy to forget, and forgetting it would
     * break only {@code /analyze-live}, the path with the thinnest test coverage.
     */
    private static void applyInternalToken(WebClient.Builder target, NlpWorkerProperties props) {
        String token = props.getInternalToken();
        if (token != null && !token.isBlank()) {
            target.defaultHeader(INTERNAL_TOKEN_HEADER, token);
        }
    }

    @Override
    public AnalysisResult analyze(
            UUID meetingId,
            UUID tenantId,
            String language,
            String transcript,
            Optional<TenantContext> tenantContext,
            Optional<MeetingGoal> goal) {
        WorkerDtos.AnalyzeRequest body =
                new WorkerDtos.AnalyzeRequest(
                        meetingId.toString(),
                        tenantId.toString(),
                        language == null || language.isBlank() ? "pt-BR" : language,
                        transcript,
                        toWorkerContext(tenantContext),
                        new WorkerDtos.Options(true, true, 20, "meeting-analysis-v1"),
                        toWorkerGoal(goal));

        WorkerDtos.AnalyzeResponse response;
        try {
            response =
                    client.post()
                            .uri("/analyze")
                            .bodyValue(body)
                            .retrieve()
                            .bodyToMono(WorkerDtos.AnalyzeResponse.class)
                            .block(Duration.ofMillis(Math.max(1_000L, props.getTimeoutMillis())));
        } catch (WebClientResponseException ex) {
            LOG.error(
                    "NLP worker responded with error status={} body={}",
                    ex.getStatusCode(),
                    ex.getResponseBodyAsString());
            throw new AnalysisException.WorkerUnavailable("status " + ex.getStatusCode(), ex);
        } catch (RuntimeException ex) {
            LOG.error("Failed to call NLP worker", ex);
            throw new AnalysisException.WorkerUnavailable(ex.getMessage(), ex);
        }
        if (response == null) {
            throw new AnalysisException.WorkerUnavailable("empty response", null);
        }

        try {
            MeetingAnalysis analysis = toDomain(meetingId, tenantId, response);
            ProductivityAssessment productivity =
                    response.productivity() == null
                            ? null
                            : toProductivity(tenantId, meetingId, response.productivity());
            CustomerConfidenceCarrier confidence =
                    response.customerConfidence() == null
                            ? null
                            : toCustomerConfidence(response.customerConfidence());
            return AnalysisResult.of(analysis, productivity, confidence);
        } catch (RuntimeException ex) {
            throw new AnalysisException.InvalidWorkerResponse(ex.getMessage(), ex);
        }
    }

    @Override
    public LiveAnalyzeResponse analyzeLive(
            String transcriptChunk, String language, LiveHighlights previousHighlights) {
        WorkerDtos.LiveAnalyzeRequest body =
                new WorkerDtos.LiveAnalyzeRequest(
                        transcriptChunk,
                        previousHighlights,
                        language == null || language.isBlank() ? "pt-BR" : language);

        try {
            LiveAnalyzeResponse response =
                    liveClient
                            .post()
                            .uri("/analyze-live")
                            .bodyValue(body)
                            .retrieve()
                            .bodyToMono(LiveAnalyzeResponse.class)
                            .block(Duration.ofSeconds(15));
            if (response == null) {
                throw new AnalysisException.WorkerUnavailable("live: empty response", null);
            }
            return response;
        } catch (WebClientResponseException ex) {
            LOG.error(
                    "NLP worker live responded with error status={} body={}",
                    ex.getStatusCode(),
                    ex.getResponseBodyAsString());
            throw new AnalysisException.WorkerUnavailable("live: status " + ex.getStatusCode(), ex);
        } catch (RuntimeException ex) {
            LOG.error("Failed to call NLP worker (live)", ex);
            throw new AnalysisException.WorkerUnavailable("live: " + ex.getMessage(), ex);
        }
    }

    @Override
    public SplitDtos.SplitResponse split(String transcript, String language) {
        SplitDtos.SplitRequest body =
                new SplitDtos.SplitRequest(
                        transcript, language == null || language.isBlank() ? "pt-BR" : language);

        try {
            SplitDtos.SplitResponse response =
                    client.post()
                            .uri("/split")
                            .bodyValue(body)
                            .retrieve()
                            .bodyToMono(SplitDtos.SplitResponse.class)
                            .block(Duration.ofMillis(Math.max(1_000L, props.getTimeoutMillis())));
            if (response == null) {
                throw new AnalysisException.WorkerUnavailable("split: empty response", null);
            }
            return response;
        } catch (WebClientResponseException ex) {
            LOG.error(
                    "NLP worker split responded with error status={} body={}",
                    ex.getStatusCode(),
                    ex.getResponseBodyAsString());
            throw new AnalysisException.WorkerUnavailable(
                    "split: status " + ex.getStatusCode(), ex);
        } catch (AnalysisException.WorkerUnavailable ex) {
            throw ex;
        } catch (RuntimeException ex) {
            LOG.error("Failed to call NLP worker (split)", ex);
            throw new AnalysisException.WorkerUnavailable("split: " + ex.getMessage(), ex);
        }
    }

    private WorkerDtos.TenantContext toWorkerContext(Optional<TenantContext> ctx) {
        if (ctx.isEmpty()) {
            return new WorkerDtos.TenantContext(
                    DEFAULT_COMPANY_NAME,
                    null,
                    DEFAULT_VALUE_PROPOSITION,
                    List.of(),
                    List.of(),
                    null,
                    List.of(),
                    List.of());
        }
        TenantContext c = ctx.get();
        List<WorkerDtos.Product> products =
                c.products().stream()
                        .map(
                                p ->
                                        new WorkerDtos.Product(
                                                p.name(), p.description(), p.keyDifferentiators()))
                        .toList();
        return new WorkerDtos.TenantContext(
                c.companyName(),
                c.industry(),
                c.valueProposition() == null || c.valueProposition().isBlank()
                        ? DEFAULT_VALUE_PROPOSITION
                        : c.valueProposition(),
                products,
                c.competitors(),
                c.idealCustomerProfile(),
                c.objectionHandling(),
                List.of());
    }

    private WorkerDtos.Goal toWorkerGoal(Optional<MeetingGoal> goal) {
        if (goal.isEmpty()) {
            return null;
        }
        MeetingGoal g = goal.get();
        return new WorkerDtos.Goal(
                g.purpose(),
                g.expectedOutcomes().stream().map(ExpectedOutcome::text).toList(),
                g.projectStateSnapshot());
    }

    private MeetingAnalysis toDomain(UUID meetingId, UUID tenantId, WorkerDtos.AnalyzeResponse r) {
        WorkerDtos.Metadata md =
                r.metadata() == null
                        ? new WorkerDtos.Metadata(null, null, 0, 0, 0, 0)
                        : r.metadata();
        List<Decision> decisions =
                safe(r.decisions()).stream()
                        .map(
                                d ->
                                        new Decision(
                                                d.text(),
                                                d.confidence() == null ? 0.0 : d.confidence()))
                        .toList();
        List<ActionItem> actionItems =
                safe(r.actionItems()).stream()
                        .map(
                                ai ->
                                        ActionItem.fresh(
                                                ai.title(),
                                                ai.assignee(),
                                                ai.dueDate(),
                                                Priority.valueOf(ai.priority()),
                                                ai.sourceQuote()))
                        .toList();
        List<Risk> risks =
                safe(r.risks()).stream()
                        .map(
                                rk ->
                                        new Risk(
                                                rk.text(),
                                                Severity.valueOf(rk.severity()),
                                                RiskCategory.valueOf(rk.category()),
                                                rk.sourceQuote()))
                        .toList();
        List<Opportunity> opportunities =
                safe(r.opportunities()).stream()
                        .map(
                                op ->
                                        new Opportunity(
                                                op.text(),
                                                Severity.valueOf(op.estimatedValue()),
                                                OpportunityCategory.valueOf(op.category()),
                                                op.sourceQuote()))
                        .toList();
        return MeetingAnalysis.newAnalysis(
                meetingId,
                tenantId,
                r.summary(),
                Sentiment.valueOf(r.sentimentOverall()),
                safe(r.topics()),
                decisions,
                actionItems,
                risks,
                opportunities,
                md.modelVersion(),
                md.promptVersion(),
                md.tokensInput() == null ? 0 : md.tokensInput(),
                md.tokensOutput() == null ? 0 : md.tokensOutput(),
                md.processingMillis() == null ? 0 : md.processingMillis(),
                md.piiRedactionsApplied() == null ? 0 : md.piiRedactionsApplied());
    }

    private ProductivityAssessment toProductivity(
            UUID tenantId, UUID meetingId, WorkerDtos.ProductivityDto p) {
        List<WorkerDtos.CoverageDto> rawCoverage = safe(p.coverage());
        List<OutcomeCoverage> coverage =
                java.util.stream.IntStream.range(0, rawCoverage.size())
                        .mapToObj(
                                i -> {
                                    WorkerDtos.CoverageDto c = rawCoverage.get(i);
                                    return new OutcomeCoverage(
                                            c.expectedOutcome(),
                                            CoverageStatus.valueOf(c.status()),
                                            c.evidence(),
                                            i);
                                })
                        .toList();
        return ProductivityAssessment.newAssessment(
                tenantId,
                meetingId,
                p.score() == null ? 0 : p.score(),
                ProductivityBand.valueOf(p.band()),
                coverage,
                p.offTopicRatio(),
                p.decisionDensity(),
                p.rationale());
    }

    /**
     * Maps the worker's {@code customerConfidence} block to the application carrier (ADR 0015). The
     * account ({@code customerAccountId}) does not exist here yet — {@code
     * CustomerConfidenceService} resolves it via get-or-create from {@code accountName}. {@code
     * trend} is parsed as the worker's guess but the backend ignores it (server-side
     * recalculation).
     */
    private CustomerConfidenceCarrier toCustomerConfidence(WorkerDtos.CustomerConfidenceDto c) {
        List<WorkerDtos.BuyingSignalDto> rawSignals = safe(c.buyingSignals());
        List<BuyingSignal> signals =
                java.util.stream.IntStream.range(0, rawSignals.size())
                        .mapToObj(
                                i -> {
                                    WorkerDtos.BuyingSignalDto s = rawSignals.get(i);
                                    return new BuyingSignal(
                                            BuyingSignalType.valueOf(s.type()),
                                            s.quote(),
                                            s.weight(),
                                            i);
                                })
                        .toList();
        List<WorkerDtos.ObjectionDto> rawObjections = safe(c.objections());
        List<Objection> objections =
                java.util.stream.IntStream.range(0, rawObjections.size())
                        .mapToObj(
                                i -> {
                                    WorkerDtos.ObjectionDto o = rawObjections.get(i);
                                    return new Objection(
                                            ObjectionType.valueOf(o.type()),
                                            o.quote(),
                                            ObjectionSeverity.valueOf(o.severity()),
                                            o.competitor(),
                                            i);
                                })
                        .toList();
        return new CustomerConfidenceCarrier(
                c.accountName(),
                c.score() == null ? 0 : c.score(),
                ConfidenceBand.valueOf(c.band()),
                c.trend() == null || c.trend().isBlank()
                        ? null
                        : ConfidenceTrend.valueOf(c.trend()),
                signals,
                objections,
                c.rationale());
    }

    private static <T> List<T> safe(List<T> in) {
        return in == null ? List.of() : in;
    }
}
