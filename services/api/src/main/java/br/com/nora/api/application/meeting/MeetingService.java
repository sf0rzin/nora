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

    private static final Logger LOG = LoggerFactory.getLogger(MeetingService.class);

    /** Tamanho do lote ao varrer todas as meetings de um tenant para filtro IAM in-memory. */
    private static final int LIST_SCAN_BATCH = 200;

    private final MeetingRepository meetings;
    private final TranscriptRepository transcripts;
    private final ObjectProvider<AnalysisService> analysisServiceProvider;
    private final AuditPort audit;
    private final boolean autoDispatchAnalysis;

    /**
     * Template com PROPAGATION_REQUIRES_NEW, para escrever de dentro do {@code afterCommit} — onde
     * a transação corrente já commitou e um {@code REQUIRED} se juntaria a ela sem nunca gravar.
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
     * Agenda a análise, tratando saturação do pool.
     *
     * <p>O executor de {@code AsyncConfig} tem fila 50 e {@code AbortPolicy}: cheio, o submit lança
     * {@link RejectedExecutionException}. Vindo do {@code afterCommit}, essa exceção subia pro
     * controller DEPOIS do commit — o cliente levava 500 sobre um meeting que existe, sem análise
     * agendada e preso em PENDING para sempre. O javadoc do AsyncConfig já afirmava que "o caller
     * trata e marca o meeting como FAILED"; este é o caller, e agora ele de fato trata.
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
                // REQUIRES_NEW, não o @Transactional do adapter. No afterCommit a transação ainda
                // é a corrente — commitada, mas não encerrada —, então um REQUIRED do adapter
                // JUNTA-SE a ela em vez de abrir outra, e o save nunca chega a ser gravado. O
                // meeting ficava PENDING para sempre e o log dizia que tinha sido marcado FAILED.
                newTransaction.executeWithoutResult(
                        status ->
                                meetings.findByIdAndTenant(meetingId, tenantId)
                                        .map(m -> m.withStatus(ProcessingStatus.FAILED))
                                        .ifPresent(meetings::save));
            } catch (RuntimeException marking) {
                // Marcar FAILED é best-effort: se também falhar, o meeting fica PENDING e o
                // reprocess manual resolve. Propagar aqui só trocaria um erro por outro.
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
        // Autoriza ANTES de tomar o lock. Tomá-lo primeiro deixava um chamador sem permissão
        // segurar um lock de escrita na linha durante toda a avaliação de IAM (que vai ao banco),
        // repetidamente — negação de serviço sobre uma reunião à escolha dele.
        Meeting snapshot =
                meetings.findByIdAndTenant(meetingId, tenantId)
                        .orElseThrow(MeetingException.NotFound::new);
        authorize.accept(snapshot);

        // Só então serializa. A guarda abaixo é um check-then-act: sem lock, duas chamadas
        // concorrentes leem o mesmo status pré-update, ambas passam e ambas agendam — dois
        // pipelines sobre o mesmo meeting, LLM cobrado a dobrar e efeitos externos duplicados.
        Meeting meeting =
                meetings.findByIdAndTenantForUpdate(meetingId, tenantId)
                        .orElseThrow(MeetingException.NotFound::new);
        // Reavalia com a linha travada: entre o snapshot e o lock os atributos podem ter mudado,
        // e é sobre eles que as conditions de IAM decidem.
        authorize.accept(meeting);

        // Reprocessar é válido a partir de um estado terminal (FAILED, COMPLETED -> "reanalisar").
        // PROCESSING e PENDING são barrados: nos dois casos já existe análise a caminho, e
        // reagendar criaria uma execução concorrente.
        //
        // PENDING passava antes, com a intenção de "analisar agora" uma reunião que nunca chegou a
        // ser despachada. Mas o lock não resolvia o duplo despacho por causa disso: o vencedor
        // grava PENDING, e a segunda chamada — libertada pelo lock — lia PENDING e passava na
        // guarda. Um duplo clique bastava. Barrar PENDING é seguro agora que um despacho rejeitado
        // marca FAILED de verdade (ver dispatchOrMarkFailed): PENDING passou a significar mesmo
        // "na fila", nunca "ficou esquecido".
        if (meeting.processingStatus() == ProcessingStatus.PROCESSING
                || meeting.processingStatus() == ProcessingStatus.PENDING) {
            throw new MeetingException.CannotReprocess(
                    "A análise já está em andamento ou na fila; aguarde concluir antes de"
                            + " reprocessar.");
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
