package br.com.nora.api.application.analysis;

import br.com.nora.api.application.meeting.MeetingException;
import br.com.nora.api.application.ports.MeetingAnalysisRepository;
import br.com.nora.api.application.ports.MeetingRepository;
import br.com.nora.api.application.ports.NlpWorkerClient;
import br.com.nora.api.application.ports.TenantContextRepository;
import br.com.nora.api.application.ports.TranscriptRepository;
import br.com.nora.api.domain.analysis.MeetingAnalysis;
import br.com.nora.api.domain.meeting.Meeting;
import br.com.nora.api.domain.meeting.ProcessingStatus;
import br.com.nora.api.domain.meeting.Transcript;
import br.com.nora.api.domain.tenant.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orquestra o pipeline de analise: carrega transcricao + contexto, chama o worker, persiste a
 * analise e atualiza o processingStatus do Meeting.
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
    private final NlpWorkerClient worker;

    public AnalysisService(
            MeetingRepository meetings,
            TranscriptRepository transcripts,
            TenantContextRepository tenantContexts,
            MeetingAnalysisRepository analyses,
            NlpWorkerClient worker) {
        this.meetings = meetings;
        this.transcripts = transcripts;
        this.tenantContexts = tenantContexts;
        this.analyses = analyses;
        this.worker = worker;
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
        markStatus(meeting, ProcessingStatus.PROCESSING);

        Transcript transcript = loadTranscript(meetingId, tenantId);
        Optional<TenantContext> ctx = tenantContexts.findByTenantId(tenantId);

        MeetingAnalysis result =
                worker.analyze(meetingId, tenantId, meeting.language(), transcript.rawText(), ctx);
        MeetingAnalysis saved = analyses.save(result);
        markStatusAndSnippet(meeting, ProcessingStatus.COMPLETED, saved.summarySnippet());
        return saved;
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
    void markStatus(Meeting meeting, ProcessingStatus status) {
        Meeting updated = meeting.withStatus(status);
        meetings.save(updated);
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
}
