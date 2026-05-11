package br.com.nora.api.api.controllers;

import br.com.nora.api.api.dto.analysis.AnalysisResponse;
import br.com.nora.api.api.dto.analysis.AnalysisResponseMapper;
import br.com.nora.api.api.dto.meeting.MeetingDetailResponse;
import br.com.nora.api.api.dto.meeting.MeetingListItem;
import br.com.nora.api.api.dto.meeting.MeetingListResponse;
import br.com.nora.api.api.dto.meeting.MeetingUploadMetadata;
import br.com.nora.api.api.dto.meeting.MeetingUploadResponse;
import br.com.nora.api.api.security.CurrentUser;
import br.com.nora.api.application.analysis.AnalysisService;
import br.com.nora.api.application.iam.AuthorizationService;
import br.com.nora.api.application.meeting.MeetingException;
import br.com.nora.api.application.meeting.MeetingService;
import br.com.nora.api.application.meeting.MeetingService.UploadCommand;
import br.com.nora.api.application.ports.MeetingRepository.MeetingFilter;
import br.com.nora.api.application.ports.MeetingRepository.PagedMeetings;
import br.com.nora.api.domain.analysis.MeetingAnalysis;
import br.com.nora.api.domain.meeting.Meeting;
import br.com.nora.api.domain.meeting.Participant;
import br.com.nora.api.domain.meeting.ProcessingStatus;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final AuthorizationService authz;

    public MeetingsController(
            MeetingService meetings,
            AnalysisService analyses,
            ObjectMapper objectMapper,
            Validator validator,
            AuthorizationService authz) {
        this.meetings = meetings;
        this.analyses = analyses;
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
        authz.require(
                principal.userId(),
                principal.tenantId(),
                "meeting:read",
                meetingResource(principal.tenantId(), null));

        MeetingFilter filter = buildFilter(search, status, from, to);
        PagedMeetings paged = meetings.list(principal.tenantId(), filter, page, size);
        // Filtra item-a-item aplicando conditions com os attributes de cada meeting.
        // NOTA: paginacao real (com filtro at-query) fica para Fase 1 — aqui o totalItems
        // ainda reflete o total nao-filtrado para nao quebrar o cliente.
        List<MeetingListItem> items =
                paged.items().stream()
                        .filter(
                                m ->
                                        authz.isAllowed(
                                                principal.userId(),
                                                principal.tenantId(),
                                                "meeting:read",
                                                meetingResource(principal.tenantId(), m.id()),
                                                m.attributes()))
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
        return new MeetingListResponse(
                items, paged.page(), paged.size(), paged.totalItems(), paged.totalPages());
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
                m.createdAt(),
                m.updatedAt());
    }

    @PostMapping("/{id}/reprocess")
    public ResponseEntity<MeetingUploadResponse> reprocess(@PathVariable("id") UUID id) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        // Resolve para usar attributes no context (authz especifica do recurso).
        Meeting current = meetings.getById(id, principal.tenantId());
        authz.require(
                principal.userId(),
                principal.tenantId(),
                "meeting:reprocess",
                meetingResource(principal.tenantId(), current.id()),
                current.attributes());
        Meeting updated = meetings.reprocess(id, principal.tenantId());
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
