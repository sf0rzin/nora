package br.com.nora.api.api.controllers;

import br.com.nora.api.api.dto.analysis.AnalysisResponse;
import br.com.nora.api.api.dto.analysis.AnalysisResponseMapper;
import br.com.nora.api.api.dto.meeting.LiveAnalyzeDtos;
import br.com.nora.api.api.dto.meeting.MeetingDetailResponse;
import br.com.nora.api.api.dto.meeting.MeetingGoalRequest;
import br.com.nora.api.api.dto.meeting.MeetingGoalResponse;
import br.com.nora.api.api.dto.meeting.MeetingGoalResponseMapper;
import br.com.nora.api.api.dto.meeting.MeetingListItem;
import br.com.nora.api.api.dto.meeting.MeetingListResponse;
import br.com.nora.api.api.dto.meeting.MeetingUploadMetadata;
import br.com.nora.api.api.dto.meeting.MeetingUploadResponse;
import br.com.nora.api.api.dto.meeting.ProductivityAssessmentResponse;
import br.com.nora.api.api.security.CurrentUser;
import br.com.nora.api.application.analysis.AnalysisException;
import br.com.nora.api.application.analysis.AnalysisService;
import br.com.nora.api.application.analysis.LiveAnalysisService;
import br.com.nora.api.application.iam.AuthorizationService;
import br.com.nora.api.application.meeting.MeetingException;
import br.com.nora.api.application.meeting.MeetingGoalService;
import br.com.nora.api.application.meeting.MeetingService;
import br.com.nora.api.application.meeting.MeetingService.UploadCommand;
import br.com.nora.api.application.ports.MeetingRepository.MeetingFilter;
import br.com.nora.api.domain.analysis.MeetingAnalysis;
import br.com.nora.api.domain.meeting.Meeting;
import br.com.nora.api.domain.meeting.Participant;
import br.com.nora.api.domain.meeting.ProcessingStatus;
import br.com.nora.api.infrastructure.nlp.WorkerDtos;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
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
    private static final int AUTH_FILTER_HARD_CAP = 500;

    private final MeetingService meetings;
    private final AnalysisService analyses;
    private final MeetingGoalService meetingGoals;
    private final LiveAnalysisService liveAnalysis;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final AuthorizationService authz;

    public MeetingsController(
            MeetingService meetings,
            AnalysisService analyses,
            MeetingGoalService meetingGoals,
            LiveAnalysisService liveAnalysis,
            ObjectMapper objectMapper,
            Validator validator,
            AuthorizationService authz) {
        this.meetings = meetings;
        this.analyses = analyses;
        this.meetingGoals = meetingGoals;
        this.liveAnalysis = liveAnalysis;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.authz = authz;
    }

    private static String meetingResource(UUID tenantId, UUID meetingId) {
        return "nora:tenant/" + tenantId + ":meeting/" + (meetingId == null ? "*" : meetingId);
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
        // Carrega ate AUTH_FILTER_HARD_CAP meetings, filtra por IAM e pagina apos.
        // Necessario para que totalItems reflita o conjunto que o usuario pode realmente ver
        // (caso contrario paginas podem chegar vazias com totalItems > 0). Fase 1: empurrar o
        // predicado de attributes para o SQL.
        List<Meeting> candidates =
                meetings.listForAuthFilter(principal.tenantId(), filter, AUTH_FILTER_HARD_CAP);
        List<Meeting> visible =
                candidates.stream()
                        .filter(
                                m ->
                                        authz.isAllowed(
                                                principal.userId(),
                                                principal.tenantId(),
                                                "meeting:read",
                                                meetingResource(principal.tenantId(), m.id()),
                                                m.attributes()))
                        .toList();

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

    private String readFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new MeetingException.EmptyTranscript();
        }
        try {
            byte[] bytes = file.getBytes();
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
