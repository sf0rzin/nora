package br.com.nora.api.application.analysis;

import br.com.nora.api.application.customer.CustomerConfidenceService;
import br.com.nora.api.application.meeting.MeetingException;
import br.com.nora.api.application.ports.MeetingAnalysisRepository;
import br.com.nora.api.application.ports.MeetingGoalRepository;
import br.com.nora.api.application.ports.MeetingRepository;
import br.com.nora.api.application.ports.NlpWorkerClient;
import br.com.nora.api.application.ports.NlpWorkerClient.AnalysisResult;
import br.com.nora.api.application.ports.ProductivityAssessmentRepository;
import br.com.nora.api.application.ports.TenantContextRepository;
import br.com.nora.api.application.ports.TranscriptRepository;
import br.com.nora.api.domain.analysis.MeetingAnalysis;
import br.com.nora.api.domain.meeting.Meeting;
import br.com.nora.api.domain.meeting.ProcessingStatus;
import br.com.nora.api.domain.meeting.Transcript;
import br.com.nora.api.domain.meeting.productivity.MeetingGoal;
import br.com.nora.api.domain.meeting.productivity.ProductivityAssessment;
import br.com.nora.api.domain.tenant.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orquestra o pipeline de analise: carrega transcricao + contexto + goal opcional, chama o worker,
 * persiste a analise (e o productivity assessment quando ha goal) e atualiza o processingStatus do
 * Meeting.
 *
 * <p>{@link #runAsync(UUID, UUID)} e disparado depois do upload (fire-and-forget) e roda em uma
 * thread separada via @Async. Cada etapa muda o status do meeting (PROCESSING -> COMPLETED |
 * FAILED) em transacoes curtas e independentes.
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

    public AnalysisService(
            MeetingRepository meetings,
            TranscriptRepository transcripts,
            TenantContextRepository tenantContexts,
            MeetingAnalysisRepository analyses,
            MeetingGoalRepository goals,
            ProductivityAssessmentRepository assessments,
            NlpWorkerClient worker,
            CustomerConfidenceService customerConfidence) {
        this.meetings = meetings;
        this.transcripts = transcripts;
        this.tenantContexts = tenantContexts;
        this.analyses = analyses;
        this.goals = goals;
        this.assessments = assessments;
        this.worker = worker;
        this.customerConfidence = customerConfidence;
    }

    /**
     * Executa o pipeline em background. Chamada fire-and-forget pelo MeetingService apos o upload.
     */
    @Async
    public void runAsync(UUID meetingId, UUID tenantId) {
        try {
            run(meetingId, tenantId);
        } catch (RuntimeException ex) {
            LOG.error(
                    "Pipeline de analise falhou meetingId={} tenantId={} cause={}",
                    meetingId,
                    tenantId,
                    ex.getMessage());
            safeMarkFailed(meetingId, tenantId);
        }
    }

    /**
     * Pipeline sincrono (testavel). Nao envolve a chamada externa em transacao para evitar manter
     * conexoes Postgres ocupadas durante o roundtrip ao worker.
     */
    public MeetingAnalysis run(UUID meetingId, UUID tenantId) {
        Meeting meeting = loadMeeting(meetingId, tenantId);
        // `markStatus` persiste com novo status mas a referencia local `meeting`
        // continua no estado antigo. Reatribuir o retorno garante que o
        // `markStatusAndSnippet` abaixo encontre uma transicao valida na
        // state machine (PROCESSING -> COMPLETED) em vez do antigo
        // PENDING -> COMPLETED que seria rejeitado.
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
        markStatusAndSnippet(meeting, ProcessingStatus.COMPLETED, saved.summarySnippet());
        return saved;
    }

    /**
     * Persiste o Customer Confidence opt-in (ADR 0015) quando o worker emitiu o bloco. Resiliente:
     * uma falha aqui nao derruba a analise — a {@link MeetingAnalysis} ja foi gravada e o meeting
     * ainda transiciona para COMPLETED (log + continua, igual a tolerancia do productivity).
     */
    private void persistCustomerConfidence(UUID meetingId, UUID tenantId, AnalysisResult result) {
        if (result.customerConfidence().isEmpty()) {
            return;
        }
        try {
            customerConfidence.persist(tenantId, meetingId, result.customerConfidence().get());
        } catch (RuntimeException ex) {
            // PII-safe: nao logar accountName nem quotes, apenas ids e a causa.
            LOG.warn(
                    "Falha ao persistir customer confidence meetingId={} tenantId={} cause={}",
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
        // Garante escopo: meeting precisa existir no tenant.
        meetings.findByIdAndTenant(meetingId, tenantId).orElseThrow(MeetingException.NotFound::new);
        return analyses.findByMeetingId(meetingId, tenantId);
    }

    /** Recupera o productivity persistido (opt-in). Vazio quando nao ha. */
    @Transactional(readOnly = true)
    public Optional<ProductivityAssessment> findProductivity(UUID meetingId, UUID tenantId) {
        meetings.findByIdAndTenant(meetingId, tenantId).orElseThrow(MeetingException.NotFound::new);
        return assessments.findByMeetingId(meetingId, tenantId);
    }
}
