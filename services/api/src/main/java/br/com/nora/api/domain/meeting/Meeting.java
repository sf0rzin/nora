package br.com.nora.api.domain.meeting;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reuniao no dominio. Carrega tenant_id obrigatorio, owner, formato da transcricao e estado de
 * processamento. A transcricao em si vive em {@link Transcript} (referencia 1-1) para evitar
 * carregar texto longo desnecessariamente nas listagens.
 */
public final class Meeting {

    private static final int TITLE_MAX = 200;
    private static final int SUMMARY_SNIPPET_MAX = 500;

    private final UUID id;
    private final UUID tenantId;
    private final UUID ownerUserId;
    private final String title;
    private final OffsetDateTime startedAt;
    private final OffsetDateTime endedAt;
    private final String language;
    private final TranscriptFormat transcriptFormat;
    private final ProcessingStatus processingStatus;
    private final String summarySnippet;
    private final List<Participant> participants;
    private final List<String> tags;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;

    public Meeting(
            UUID id,
            UUID tenantId,
            UUID ownerUserId,
            String title,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            String language,
            TranscriptFormat transcriptFormat,
            ProcessingStatus processingStatus,
            String summarySnippet,
            List<Participant> participants,
            List<String> tags,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
        if (id == null) {
            throw new IllegalArgumentException("meeting id is required");
        }
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (ownerUserId == null) {
            throw new IllegalArgumentException("ownerUserId is required");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        String trimmedTitle = title.trim();
        if (trimmedTitle.length() > TITLE_MAX) {
            throw new IllegalArgumentException("title must be at most " + TITLE_MAX + " chars");
        }
        if (transcriptFormat == null) {
            throw new IllegalArgumentException("transcriptFormat is required");
        }
        if (processingStatus == null) {
            throw new IllegalArgumentException("processingStatus is required");
        }
        if (startedAt != null && endedAt != null && endedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("endedAt must be after startedAt");
        }
        if (summarySnippet != null && summarySnippet.length() > SUMMARY_SNIPPET_MAX) {
            throw new IllegalArgumentException(
                    "summarySnippet must be at most " + SUMMARY_SNIPPET_MAX + " chars");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.ownerUserId = ownerUserId;
        this.title = trimmedTitle;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.language = (language == null || language.isBlank()) ? "pt-BR" : language.trim();
        this.transcriptFormat = transcriptFormat;
        this.processingStatus = processingStatus;
        this.summarySnippet = summarySnippet;
        this.participants =
                participants == null
                        ? List.of()
                        : Collections.unmodifiableList(new ArrayList<>(participants));
        this.tags =
                tags == null
                        ? List.of()
                        : Collections.unmodifiableList(
                                new ArrayList<>(
                                        new java.util.LinkedHashSet<>(
                                                tags.stream()
                                                        .filter(t -> t != null && !t.isBlank())
                                                        .map(t -> t.trim().toLowerCase())
                                                        .toList())));
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Cria uma nova reuniao em estado PENDING (logo apos upload). */
    public static Meeting newPending(
            UUID tenantId,
            UUID ownerUserId,
            String title,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            String language,
            TranscriptFormat format,
            List<Participant> participants,
            List<String> tags) {
        OffsetDateTime now = OffsetDateTime.now();
        return new Meeting(
                UUID.randomUUID(),
                tenantId,
                ownerUserId,
                title,
                startedAt,
                endedAt,
                language,
                format,
                ProcessingStatus.PENDING,
                null,
                participants,
                tags,
                now,
                now);
    }

    public Long durationSeconds() {
        if (startedAt == null || endedAt == null) {
            return null;
        }
        return java.time.Duration.between(startedAt, endedAt).toSeconds();
    }

    /** Devolve copia com novo status (e updatedAt = now). */
    public Meeting withStatus(ProcessingStatus newStatus) {
        if (newStatus == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (newStatus == this.processingStatus) {
            return this;
        }
        return new Meeting(
                id,
                tenantId,
                ownerUserId,
                title,
                startedAt,
                endedAt,
                language,
                transcriptFormat,
                newStatus,
                summarySnippet,
                participants,
                tags,
                createdAt,
                OffsetDateTime.now());
    }

    /** Devolve copia com novo snippet (e updatedAt = now). Trunca em SUMMARY_SNIPPET_MAX. */
    public Meeting withSummarySnippet(String snippet) {
        String trimmed =
                snippet == null
                        ? null
                        : (snippet.length() > SUMMARY_SNIPPET_MAX
                                ? snippet.substring(0, SUMMARY_SNIPPET_MAX)
                                : snippet);
        return new Meeting(
                id,
                tenantId,
                ownerUserId,
                title,
                startedAt,
                endedAt,
                language,
                transcriptFormat,
                processingStatus,
                trimmed,
                participants,
                tags,
                createdAt,
                OffsetDateTime.now());
    }

    /** Apenas leitura — getters. */
    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID ownerUserId() {
        return ownerUserId;
    }

    public String title() {
        return title;
    }

    public OffsetDateTime startedAt() {
        return startedAt;
    }

    public OffsetDateTime endedAt() {
        return endedAt;
    }

    public String language() {
        return language;
    }

    public TranscriptFormat transcriptFormat() {
        return transcriptFormat;
    }

    public ProcessingStatus processingStatus() {
        return processingStatus;
    }

    public String summarySnippet() {
        return summarySnippet;
    }

    public List<Participant> participants() {
        return participants;
    }

    public List<String> tags() {
        return tags;
    }

    public Set<String> tagSet() {
        return Set.copyOf(tags);
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }
}
