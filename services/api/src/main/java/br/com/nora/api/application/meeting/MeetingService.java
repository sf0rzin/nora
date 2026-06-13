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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Servico de aplicacao para reunioes (US07).
 *
 * <p>Regras chave:
 *
 * <ul>
 *   <li>Toda escrita/leitura recebe tenantId explicito vindo do JWT do chamador.
 *   <li>Upload cria meeting + transcript em uma unica transacao.
 *   <li>Apos commit do upload, dispara analise async via {@link AnalysisService#runAsync}.
 * </ul>
 */
@Service
public class MeetingService {

    /** Tamanho do lote ao varrer todas as meetings de um tenant para filtro IAM in-memory. */
    private static final int LIST_SCAN_BATCH = 200;

    private final MeetingRepository meetings;
    private final TranscriptRepository transcripts;
    private final ObjectProvider<AnalysisService> analysisServiceProvider;
    private final AuditPort audit;
    private final boolean autoDispatchAnalysis;

    public MeetingService(
            MeetingRepository meetings,
            TranscriptRepository transcripts,
            ObjectProvider<AnalysisService> analysisServiceProvider,
            AuditPort audit,
            @Value("${nora.analysis.auto-dispatch:true}") boolean autoDispatchAnalysis) {
        this.meetings = meetings;
        this.transcripts = transcripts;
        this.analysisServiceProvider = analysisServiceProvider;
        this.audit = audit;
        this.autoDispatchAnalysis = autoDispatchAnalysis;
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
                            svc.runAsync(meetingId, tenantId);
                        }
                    });
        } else {
            svc.runAsync(meetingId, tenantId);
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
     * Variante para o controller quando a listagem precisa de filtro IAM com conditions por item
     * antes de paginar. Devolve <b>todas</b> as meetings do tenant que casam os filtros baratos
     * (search/status/data ja resolvidos no SQL), em ordem created_at desc, varrendo o banco em
     * lotes — o controller aplica o filtro IAM e pagina in-memory.
     *
     * <p>Sem teto silencioso: o cap antigo (500) descartava reunioes alem do limite, escondendo do
     * usuario meetings que ele teria permissao de ver (tenant com &gt;500 reunioes). Otimizacao
     * futura (performance, nao correcao): empurrar o predicado de attributes para o SQL via {@code
     * meeting_attributes @>} + indice GIN (V008) quando algum tenant atingir escala.
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
        return reprocess(meetingId, tenantId, m -> {});
    }

    /**
     * Variante com callback de autorizacao executado dentro da mesma transacao apos resolver o
     * meeting — evita TOCTOU entre check de authz e execucao. O callback recebe o meeting carregado
     * e deve lancar caso a autorizacao falhe.
     */
    @Transactional
    public Meeting reprocess(
            UUID meetingId, UUID tenantId, java.util.function.Consumer<Meeting> authorize) {
        Meeting meeting =
                meetings.findByIdAndTenant(meetingId, tenantId)
                        .orElseThrow(MeetingException.NotFound::new);
        authorize.accept(meeting);
        // Reprocessar é válido a partir de qualquer estado terminal/de espera (FAILED, COMPLETED
        // -> "reanalisar", PENDING -> "analisar agora"). Só PROCESSING é barrado: a análise já está
        // em andamento e reagendar criaria uma execução concorrente sobre o mesmo meeting.
        if (meeting.processingStatus() == ProcessingStatus.PROCESSING) {
            throw new MeetingException.CannotReprocess(
                    "A análise já está em andamento; aguarde concluir antes de reprocessar.");
        }
        ProcessingStatus previousStatus = meeting.processingStatus();
        Meeting updated = meeting.withStatus(ProcessingStatus.PENDING);
        meetings.save(updated);
        audit.record(
                tenantId,
                meeting.ownerUserId(),
                "meeting.reprocessed",
                "MEETING",
                meetingId,
                Map.of("previousStatus", previousStatus.name()));
        scheduleAnalysisAfterCommit(meetingId, tenantId);
        return updated;
    }

    @Transactional(readOnly = true)
    public Transcript getTranscript(UUID meetingId, UUID tenantId) {
        // Garante escopo: a meeting precisa existir no tenant antes de devolver o texto.
        meetings.findByIdAndTenant(meetingId, tenantId).orElseThrow(MeetingException.NotFound::new);
        return transcripts
                .findByMeetingAndTenant(meetingId, tenantId)
                .orElseThrow(MeetingException.NotFound::new);
    }

    /** Comando imutavel de upload. */
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

        /** Compat: chamadas antigas sem attributes assumem mapa vazio. */
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
