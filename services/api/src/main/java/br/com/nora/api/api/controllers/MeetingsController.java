package br.com.nora.api.api.controllers;

import br.com.nora.api.api.dto.analysis.AnalysisResponse;
import br.com.nora.api.api.dto.analysis.AnalysisResponseMapper;
import br.com.nora.api.api.dto.meeting.CustomerConfidenceResponse;
import br.com.nora.api.api.dto.meeting.LiveAnalyzeDtos;
import br.com.nora.api.api.dto.meeting.MeetingDetailResponse;
import br.com.nora.api.api.dto.meeting.MeetingGoalRequest;
import br.com.nora.api.api.dto.meeting.MeetingGoalResponse;
import br.com.nora.api.api.dto.meeting.MeetingGoalResponseMapper;
import br.com.nora.api.api.dto.meeting.MeetingListItem;
import br.com.nora.api.api.dto.meeting.MeetingListResponse;
import br.com.nora.api.api.dto.meeting.MeetingSearchResponse;
import br.com.nora.api.api.dto.meeting.MeetingUploadMetadata;
import br.com.nora.api.api.dto.meeting.MeetingUploadResponse;
import br.com.nora.api.api.dto.meeting.ProductivityAssessmentResponse;
import br.com.nora.api.api.dto.meeting.SplitPreviewDtos;
import br.com.nora.api.api.security.CurrentUser;
import br.com.nora.api.application.analysis.AnalysisException;
import br.com.nora.api.application.analysis.AnalysisService;
import br.com.nora.api.application.analysis.LiveAnalysisService;
import br.com.nora.api.application.customer.CustomerConfidenceService;
import br.com.nora.api.application.embedding.EmbeddingService;
import br.com.nora.api.application.iam.AuthorizationService;
import br.com.nora.api.application.meeting.MeetingException;
import br.com.nora.api.application.meeting.MeetingGoalService;
import br.com.nora.api.application.meeting.MeetingService;
import br.com.nora.api.application.meeting.MeetingService.UploadCommand;
import br.com.nora.api.application.meeting.TranscriptSplitService;
import br.com.nora.api.application.ports.MeetingRepository.MeetingFilter;
import br.com.nora.api.domain.analysis.MeetingAnalysis;
import br.com.nora.api.domain.customer.CustomerConfidenceAssessment;
import br.com.nora.api.domain.meeting.Meeting;
import br.com.nora.api.domain.meeting.Participant;
import br.com.nora.api.domain.meeting.ProcessingStatus;
import br.com.nora.api.infrastructure.nlp.SplitDtos;
import br.com.nora.api.infrastructure.nlp.WorkerDtos;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Endpoints da reuniao (US07): upload de transcricao textual, listagem e detalhe. Tudo escopado por
 * tenant_id do JWT.
 */
@RestController
@RequestMapping("/meetings")
public class MeetingsController {

    private static final Set<String> ALLOWED_FORMATS = Set.of("TXT", "VTT", "SRT");

    private final MeetingService meetings;
    private final AnalysisService analyses;
    private final MeetingGoalService meetingGoals;
    private final LiveAnalysisService liveAnalysis;
    private final CustomerConfidenceService customerConfidence;
    private final TranscriptSplitService transcriptSplit;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final AuthorizationService authz;
    private final EmbeddingService embeddings;

    public MeetingsController(
            MeetingService meetings,
            AnalysisService analyses,
            MeetingGoalService meetingGoals,
            LiveAnalysisService liveAnalysis,
            CustomerConfidenceService customerConfidence,
            TranscriptSplitService transcriptSplit,
            ObjectMapper objectMapper,
            Validator validator,
            AuthorizationService authz,
            EmbeddingService embeddings) {
        this.meetings = meetings;
        this.analyses = analyses;
        this.meetingGoals = meetingGoals;
        this.liveAnalysis = liveAnalysis;
        this.customerConfidence = customerConfidence;
        this.transcriptSplit = transcriptSplit;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.authz = authz;
        this.embeddings = embeddings;
    }

    private static String meetingResource(UUID tenantId, UUID meetingId) {
        return "nora:tenant/" + tenantId + ":meeting/" + (meetingId == null ? "*" : meetingId);
    }

    /**
     * Busca semântica (RAG): retorna as reuniões mais RELEVANTES à query {@code q} por similaridade
     * de embedding (não as mais recentes). Usado pelo chat pra montar contexto + citação. Vazio se
     * o embedding estiver desligado (sem credencial) ou o tenant ainda não ter reuniões indexadas —
     * o caller deve ter fallback. Spring roteia {@code /search} (literal) antes de {@code /{id}}.
     */
    @GetMapping("/search")
    public MeetingSearchResponse search(
            @RequestParam("q") String q, @RequestParam(name = "k", defaultValue = "5") int k) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        authz.require(
                principal.userId(),
                principal.tenantId(),
                "meeting:read",
                meetingResource(principal.tenantId(), null));
        int limit = Math.min(Math.max(k, 1), 10);
        List<MeetingSearchResponse.Item> items = new ArrayList<>();
        for (UUID id : embeddings.search(principal.tenantId(), q, limit)) {
            try {
                Meeting m = meetings.getById(id, principal.tenantId());
                items.add(
                        new MeetingSearchResponse.Item(
                                m.id(),
                                m.title(),
                                m.summarySnippet(),
                                m.startedAt(),
                                m.processingStatus().name()));
            } catch (RuntimeException ignored) {
                // embedding órfão (corrida com delete/erasure) — pula silenciosamente.
            }
        }
        return new MeetingSearchResponse(items);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MeetingUploadResponse> upload(
            @RequestPart("metadata") String metadataJson, @RequestPart("file") MultipartFile file) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        authz.require(
                principal.userId(),
                principal.tenantId(),
                "meeting:upload",
                meetingResource(principal.tenantId(), null));
        MeetingUploadMetadata metadata = parseMetadata(metadataJson);

        String rawTranscript = readFile(file);
        UploadCommand cmd =
                new UploadCommand(
                        principal.tenantId(),
                        principal.userId(),
                        metadata.title(),
                        metadata.startedAt(),
                        metadata.endedAt(),
                        metadata.language(),
                        metadata.transcriptFormat(),
                        toDomainParticipants(metadata.participants()),
                        metadata.tags() == null ? List.of() : metadata.tags(),
                        rawTranscript,
                        metadata.attributes() == null ? Map.of() : metadata.attributes());

        Meeting saved = meetings.upload(cmd);
        MeetingUploadResponse body =
                new MeetingUploadResponse(
                        saved.id(),
                        saved.tenantId(),
                        saved.title(),
                        saved.startedAt(),
                        saved.endedAt(),
                        saved.ownerUserId(),
                        saved.processingStatus().name(),
                        saved.createdAt());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    /**
     * Preview de separacao de um arquivo .txt com varias reunioes concatenadas. Chama o worker
     * {@code /split} e devolve as fronteiras propostas ({@code startLine}/{@code endLine} 1-based
     * sobre o arquivo original) + previews ja redigidos pelo PII Shield. NAO cria reuniao e NAO
     * persiste nada — a confirmacao e o fatiamento sao client-side.
     *
     * <p>Aceita APENAS .txt por enquanto: VTT/SRT carregam timestamps/cues proprios e respondem 400
     * com mensagem clara.
     */
    @PostMapping(value = "/split-preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SplitPreviewDtos.SplitPreviewResponse splitPreview(
            @RequestPart("file") MultipartFile file,
            @RequestParam(name = "language", required = false) String language) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        authz.require(
                principal.userId(),
                principal.tenantId(),
                "meeting:upload",
                meetingResource(principal.tenantId(), null));

        requireTxtFile(file);
        // Pre-checa o tamanho ANTES do readFile: o readFile lanca
        // IllegalArgumentException (mascarada como "Invalid request." em ingles),
        // mas aqui queremos uma mensagem PT-BR clara (413). As demais defesas do
        // upload normal (filename safety, content-type, magic bytes) seguem no readFile.
        if (file != null && file.getSize() > MAX_UPLOAD_BYTES) {
            throw new MeetingException.FileTooLarge(MAX_UPLOAD_BYTES / (1024 * 1024));
        }
        String transcript = readFile(file);

        SplitDtos.SplitResponse response =
                transcriptSplit.preview(principal.tenantId(), transcript, language);
        return toApiSplitResponse(response);
    }

    /** Split-preview e .txt-only: VTT/SRT ficam para uma fatia futura. */
    private static void requireTxtFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new MeetingException.EmptyTranscript();
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(java.util.Locale.ROOT).endsWith(".txt")) {
            throw new MeetingException.SplitUnsupportedFormat();
        }
    }

    private static SplitPreviewDtos.SplitPreviewResponse toApiSplitResponse(
            SplitDtos.SplitResponse r) {
        List<SplitPreviewDtos.SegmentDto> segments =
                r.segments() == null
                        ? List.of()
                        : r.segments().stream()
                                .map(
                                        s ->
                                                new SplitPreviewDtos.SegmentDto(
                                                        s.index() == null ? 0 : s.index(),
                                                        s.title(),
                                                        s.startLine() == null ? 0 : s.startLine(),
                                                        s.endLine() == null ? 0 : s.endLine(),
                                                        s.confidence() == null
                                                                ? 0.0
                                                                : s.confidence(),
                                                        s.preview()))
                                .toList();
        SplitDtos.SplitMetadata md =
                r.metadata() != null
                        ? r.metadata()
                        : new SplitDtos.SplitMetadata("", "", 0, 0, 0, 0);
        return new SplitPreviewDtos.SplitPreviewResponse(
                segments,
                r.totalLines() == null ? 0 : r.totalLines(),
                new SplitPreviewDtos.MetadataDto(
                        md.modelVersion(),
                        md.promptVersion(),
                        md.tokensInput() == null ? 0 : md.tokensInput(),
                        md.tokensOutput() == null ? 0 : md.tokensOutput(),
                        md.processingMillis() == null ? 0 : md.processingMillis(),
                        md.piiRedactionsApplied() == null ? 0 : md.piiRedactionsApplied()));
    }

    @GetMapping
    public MeetingListResponse list(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        // Pre-check: usuario precisa de meeting:read em pelo menos algum recurso do tenant.
        // Sem isso, devolve 403 antes de tocar o banco. Filtragem fina por attributes acontece
        // abaixo.
        authz.requireAnyAllow(
                principal.userId(),
                principal.tenantId(),
                "meeting:read",
                meetingResource(principal.tenantId(), null));

        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        MeetingFilter filter = buildFilter(search, status, from, to);
        // Carrega todas as meetings do tenant que casam os filtros baratos, filtra por IAM
        // (conditions por item) e pagina apos — totalItems reflete o conjunto realmente visivel.
        List<Meeting> candidates = meetings.listAllForAuthFilter(principal.tenantId(), filter);
        // Filtro IAM por item resolvendo o bypass de Root + os statements do usuario UMA vez para
        // toda a lista (antes: isAllowed por reuniao -> 2 queries IAM por item = N+1 no endpoint
        // mais quente do produto).
        List<Meeting> visible =
                authz.filterAllowed(
                        principal.userId(),
                        principal.tenantId(),
                        "meeting:read",
                        candidates,
                        m -> meetingResource(principal.tenantId(), m.id()),
                        Meeting::attributes);

        long totalItems = visible.size();
        int fromIdx = Math.min(safePage * safeSize, visible.size());
        int toIdx = Math.min(fromIdx + safeSize, visible.size());
        List<MeetingListItem> items =
                visible.subList(fromIdx, toIdx).stream()
                        .map(
                                m -> {
                                    Optional<MeetingAnalysis> a =
                                            analyses.findByMeeting(m.id(), principal.tenantId());
                                    return new MeetingListItem(
                                            m.id(),
                                            m.title(),
                                            m.startedAt(),
                                            m.durationSeconds(),
                                            null,
                                            m.processingStatus().name(),
                                            m.summarySnippet(),
                                            a.map(x -> x.actionItems().size()).orElse(0),
                                            a.map(x -> x.risks().size()).orElse(0),
                                            a.map(x -> x.opportunities().size()).orElse(0),
                                            m.tags());
                                })
                        .toList();
        int totalPages =
                safeSize <= 0 ? 0 : (int) Math.ceil((double) totalItems / (double) safeSize);
        return new MeetingListResponse(items, safePage, safeSize, totalItems, totalPages);
    }

    @GetMapping("/{id}")
    public MeetingDetailResponse get(@PathVariable("id") UUID id) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        // Resolve primeiro (404 se de outro tenant) e usa attributes no context da authz.
        Meeting m = meetings.getById(id, principal.tenantId());
        authz.require(
                principal.userId(),
                principal.tenantId(),
                "meeting:read",
                meetingResource(principal.tenantId(), m.id()),
                m.attributes());
        AnalysisResponse analysisDto =
                analyses.findByMeeting(m.id(), principal.tenantId())
                        .map(AnalysisResponseMapper::from)
                        .orElse(null);
        MeetingGoalResponse goalDto =
                meetingGoals
                        .findGoal(m.id(), principal.tenantId())
                        .map(MeetingGoalResponseMapper::from)
                        .orElse(null);
        ProductivityAssessmentResponse productivityDto =
                meetingGoals
                        .findAssessment(m.id(), principal.tenantId())
                        .map(MeetingGoalResponseMapper::from)
                        .orElse(null);
        // Customer Confidence (ADR 0015): no maximo um assessment por conta; o detalhe expoe o
        // primeiro (uma reuniao tipicamente toca uma conta). Null para reunioes internas.
        CustomerConfidenceResponse confidenceDto =
                customerConfidence.findViewByMeetingId(m.id(), principal.tenantId()).stream()
                        .findFirst()
                        .map(MeetingsController::toConfidenceResponse)
                        .orElse(null);
        return new MeetingDetailResponse(
                m.id(),
                m.tenantId(),
                m.title(),
                m.startedAt(),
                m.endedAt(),
                m.durationSeconds(),
                m.language(),
                new MeetingDetailResponse.OwnerSummary(m.ownerUserId(), null),
                m.participants().stream()
                        .map(
                                p ->
                                        new MeetingDetailResponse.ParticipantPayload(
                                                p.displayName(), p.email(), p.isInternal()))
                        .toList(),
                m.tags(),
                m.processingStatus().name(),
                analysisDto,
                goalDto,
                productivityDto,
                confidenceDto,
                m.createdAt(),
                m.updatedAt());
    }

    /**
     * Define ou atualiza o objetivo declarado da reuniao (ADR 0005). Quando a reuniao ja foi
     * analisada, o status muda para PENDING para reprocessamento posterior.
     */
    @PutMapping("/{id}/goal")
    public ResponseEntity<MeetingGoalResponse> putGoal(
            @PathVariable("id") UUID id, @Valid @RequestBody MeetingGoalRequest request) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        Meeting m = meetings.getById(id, principal.tenantId());
        authz.require(
                principal.userId(),
                principal.tenantId(),
                "meeting:update",
                meetingResource(principal.tenantId(), m.id()),
                m.attributes());
        MeetingGoalService.GoalSaveResult result =
                meetingGoals.save(
                        m.id(),
                        principal.tenantId(),
                        request.purpose(),
                        request.expectedOutcomes(),
                        request.projectStateSnapshot());
        return ResponseEntity.ok(MeetingGoalResponseMapper.from(result.goal()));
    }

    /** Remove o objetivo + productivity vinculado (ADR 0005). Idempotente. */
    @DeleteMapping("/{id}/goal")
    public ResponseEntity<Void> deleteGoal(@PathVariable("id") UUID id) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        Meeting m = meetings.getById(id, principal.tenantId());
        authz.require(
                principal.userId(),
                principal.tenantId(),
                "meeting:update",
                meetingResource(principal.tenantId(), m.id()),
                m.attributes());
        meetingGoals.delete(m.id(), principal.tenantId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reprocess")
    public ResponseEntity<MeetingUploadResponse> reprocess(@PathVariable("id") UUID id) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        // Reprocess autoriza com authz callback dentro da mesma transacao do service para evitar
        // TOCTOU (attributes nao mudam entre check e execucao).
        Meeting updated =
                meetings.reprocess(
                        id,
                        principal.tenantId(),
                        current ->
                                authz.require(
                                        principal.userId(),
                                        principal.tenantId(),
                                        "meeting:reprocess",
                                        meetingResource(principal.tenantId(), current.id()),
                                        current.attributes()));
        MeetingUploadResponse body =
                new MeetingUploadResponse(
                        updated.id(),
                        updated.tenantId(),
                        updated.title(),
                        updated.startedAt(),
                        updated.endedAt(),
                        updated.ownerUserId(),
                        updated.processingStatus().name(),
                        updated.createdAt());
        return ResponseEntity.accepted().body(body);
    }

    @PostMapping("/live-analyze")
    public LiveAnalyzeDtos.LiveAnalyzeResponse liveAnalyze(
            @Valid @RequestBody LiveAnalyzeDtos.LiveAnalyzeRequest req) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        authz.require(
                principal.userId(),
                principal.tenantId(),
                "meeting:analyze:live",
                meetingResource(principal.tenantId(), null));

        WorkerDtos.LiveHighlights previous = toWorkerHighlights(req.previousHighlights());
        String language =
                req.language() == null || req.language().isBlank() ? "pt-BR" : req.language();

        try {
            WorkerDtos.LiveAnalyzeResponse response =
                    liveAnalysis.analyze(req.transcriptChunk(), language, previous);
            return toApiLiveResponse(response);
        } catch (AnalysisException.WorkerUnavailable ex) {
            throw ex;
        }
    }

    private WorkerDtos.LiveHighlights toWorkerHighlights(LiveAnalyzeDtos.LiveHighlightsDto dto) {
        if (dto == null) {
            return null;
        }
        return new WorkerDtos.LiveHighlights(
                toWorkerHighlightItems(dto.decisions()),
                toWorkerHighlightItems(dto.nextSteps()),
                toWorkerHighlightItems(dto.observations()),
                toWorkerLiveTaskItems(dto.tasks()));
    }

    private List<WorkerDtos.LiveHighlightItem> toWorkerHighlightItems(
            List<LiveAnalyzeDtos.LiveHighlightItemDto> items) {
        if (items == null) {
            return null;
        }
        return items.stream()
                .map(
                        i ->
                                new WorkerDtos.LiveHighlightItem(
                                        i.text(), i.confidence(), i.sourceQuote()))
                .toList();
    }

    private List<WorkerDtos.LiveTaskItem> toWorkerLiveTaskItems(
            List<LiveAnalyzeDtos.LiveTaskItemDto> items) {
        if (items == null) {
            return null;
        }
        return items.stream()
                .map(
                        i ->
                                new WorkerDtos.LiveTaskItem(
                                        i.title(), i.assignee(), i.priority(), i.sourceQuote()))
                .toList();
    }

    private LiveAnalyzeDtos.LiveAnalyzeResponse toApiLiveResponse(
            WorkerDtos.LiveAnalyzeResponse r) {
        WorkerDtos.LiveMetadata md =
                r.metadata() != null ? r.metadata() : new WorkerDtos.LiveMetadata(0, 0, 0, 0, "");
        return new LiveAnalyzeDtos.LiveAnalyzeResponse(
                toApiHighlightItems(r.decisions()),
                toApiHighlightItems(r.nextSteps()),
                toApiHighlightItems(r.observations()),
                toApiLiveTaskItems(r.tasks()),
                new LiveAnalyzeDtos.LiveAnalyzeMetadataDto(
                        md.processingMillis(),
                        md.tokensInput(),
                        md.tokensOutput(),
                        md.piiRedactionsApplied(),
                        md.modelVersion()));
    }

    private List<LiveAnalyzeDtos.LiveHighlightItemDto> toApiHighlightItems(
            List<WorkerDtos.LiveHighlightItem> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream()
                .map(
                        i ->
                                new LiveAnalyzeDtos.LiveHighlightItemDto(
                                        i.text(), i.confidence(), i.sourceQuote()))
                .toList();
    }

    private List<LiveAnalyzeDtos.LiveTaskItemDto> toApiLiveTaskItems(
            List<WorkerDtos.LiveTaskItem> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream()
                .map(
                        i ->
                                new LiveAnalyzeDtos.LiveTaskItemDto(
                                        i.title(), i.assignee(), i.priority(), i.sourceQuote()))
                .toList();
    }

    private static CustomerConfidenceResponse toConfidenceResponse(
            CustomerConfidenceService.ConfidenceView view) {
        CustomerConfidenceAssessment a = view.assessment();
        return new CustomerConfidenceResponse(
                a.score(),
                a.band().name(),
                a.trend() == null ? null : a.trend().name(),
                view.accountName(),
                a.rationale(),
                a.buyingSignals().stream()
                        .map(
                                s ->
                                        new CustomerConfidenceResponse.BuyingSignalResponse(
                                                s.type().name(), s.quote(), s.weight()))
                        .toList(),
                a.objections().stream()
                        .map(
                                o ->
                                        new CustomerConfidenceResponse.ObjectionResponse(
                                                o.type().name(),
                                                o.quote(),
                                                o.severity().name(),
                                                o.competitor()))
                        .toList());
    }

    private MeetingUploadMetadata parseMetadata(String json) {
        try {
            MeetingUploadMetadata parsed =
                    objectMapper.readValue(json, MeetingUploadMetadata.class);
            var violations = validator.validate(parsed);
            if (!violations.isEmpty()) {
                String first = violations.iterator().next().getMessage();
                throw new IllegalArgumentException("metadata invalid: " + first);
            }
            if (!ALLOWED_FORMATS.contains(parsed.transcriptFormat().toUpperCase())) {
                throw new MeetingException.UnsupportedFormat(parsed.transcriptFormat());
            }
            return parsed;
        } catch (IOException ex) {
            throw new IllegalArgumentException("metadata is not valid JSON");
        }
    }

    // Cap defensivo de tamanho do arquivo: 10MB. Aligned com max-file-size do
    // application.yml. Em prod, Spring rejeita antes via MaxUploadSizeExceededException
    // (handler em GlobalExceptionHandler retorna 413).
    private static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
    // Extensoes aceitas alinhadas com TranscriptFormat (TXT, VTT, SRT).
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".txt", ".vtt", ".srt");
    // Content-Types validos para texto puro / legendas.
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of(
                    "text/plain",
                    "text/vtt",
                    "application/x-subrip",
                    "text/srt",
                    "application/octet-stream"); // alguns clientes mandam isso pra .vtt/.srt

    private String readFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new MeetingException.EmptyTranscript();
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("uploaded file exceeds maximum allowed size (10MB)");
        }
        // Filename safety: rejeitar nomes com path traversal ou caracteres
        // perigosos (ex.: "../../etc/passwd", null bytes).
        String filename = file.getOriginalFilename();
        if (filename != null) {
            if (filename.contains("/") || filename.contains("\\") || filename.contains("\0")) {
                throw new IllegalArgumentException("invalid filename");
            }
            String lower = filename.toLowerCase(java.util.Locale.ROOT);
            int dot = lower.lastIndexOf('.');
            String ext = dot >= 0 ? lower.substring(dot) : "";
            if (!ALLOWED_EXTENSIONS.contains(ext)) {
                throw new IllegalArgumentException(
                        "unsupported file extension; allowed: " + ALLOWED_EXTENSIONS);
            }
        }
        // Content-Type check (defesa em profundidade — cliente pode mentir).
        String ct = file.getContentType();
        if (ct != null) {
            String ctLower = ct.toLowerCase(java.util.Locale.ROOT);
            // text/* generico aceito alem da whitelist (clientes variam muito).
            if (!ctLower.startsWith("text/") && !ALLOWED_CONTENT_TYPES.contains(ctLower)) {
                throw new IllegalArgumentException("unsupported content-type: " + ct);
            }
        }
        try {
            byte[] bytes = file.getBytes();
            // Validacao de magic bytes: rejeita binarios disfarcados de .txt.
            // PE (.exe Win): "MZ" (0x4D 0x5A). ELF (.so/.bin Linux): 0x7F 0x45 0x4C 0x46.
            // ZIP/PDF/etc: 0x50 0x4B (PK), 0x25 0x50 0x44 0x46 (PDF).
            if (bytes.length >= 4) {
                int b0 = bytes[0] & 0xFF;
                int b1 = bytes[1] & 0xFF;
                int b2 = bytes[2] & 0xFF;
                int b3 = bytes[3] & 0xFF;
                boolean looksLikeBinary =
                        (b0 == 0x4D && b1 == 0x5A) // MZ (PE)
                                || (b0 == 0x7F && b1 == 0x45 && b2 == 0x4C && b3 == 0x46) // ELF
                                || (b0 == 0x50 && b1 == 0x4B) // PK (ZIP/JAR/DOCX)
                                || (b0 == 0x25 && b1 == 0x50 && b2 == 0x44 && b3 == 0x46) // %PDF
                                || (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47); // PNG
                if (looksLikeBinary) {
                    throw new IllegalArgumentException(
                            "uploaded file appears to be a binary blob, not a text transcript");
                }
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalArgumentException("could not read uploaded file");
        }
    }

    private List<Participant> toDomainParticipants(
            List<MeetingUploadMetadata.ParticipantPayload> input) {
        if (input == null) {
            return List.of();
        }
        return input.stream()
                .map(
                        p ->
                                new Participant(
                                        p.displayName(),
                                        p.email(),
                                        Boolean.TRUE.equals(p.isInternal())))
                .toList();
    }

    private MeetingFilter buildFilter(String search, String status, String from, String to) {
        ProcessingStatus parsedStatus = null;
        if (status != null && !status.isBlank()) {
            try {
                parsedStatus = ProcessingStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("invalid status: " + status);
            }
        }
        OffsetDateTime fromTs = parseInstant(from, "from");
        OffsetDateTime toTs = parseInstant(to, "to");
        return new MeetingFilter(
                (search == null || search.isBlank()) ? null : search.trim(),
                parsedStatus,
                fromTs,
                toTs);
    }

    private OffsetDateTime parseInstant(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("invalid " + field + " timestamp: " + value);
        }
    }
}
