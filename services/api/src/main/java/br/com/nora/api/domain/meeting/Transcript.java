package br.com.nora.api.domain.meeting;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Texto bruto associado a uma reuniao (1-1). Carrega tenant_id para enforcement direto. */
public final class Transcript {

    public static final int MAX_CHAR_COUNT = 1_000_000; // ~1 MB texto, suficiente para reuniao

    private final UUID id;
    private final UUID meetingId;
    private final UUID tenantId;
    private final TranscriptFormat format;
    private final String rawText;
    private final int charCount;
    private final OffsetDateTime createdAt;

    public Transcript(
            UUID id,
            UUID meetingId,
            UUID tenantId,
            TranscriptFormat format,
            String rawText,
            int charCount,
            OffsetDateTime createdAt) {
        if (id == null) {
            throw new IllegalArgumentException("transcript id is required");
        }
        if (meetingId == null) {
            throw new IllegalArgumentException("meetingId is required");
        }
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (format == null) {
            throw new IllegalArgumentException("format is required");
        }
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("transcript content is required");
        }
        if (charCount < 0) {
            throw new IllegalArgumentException("charCount must be non-negative");
        }
        if (rawText.length() > MAX_CHAR_COUNT) {
            throw new IllegalArgumentException(
                    "transcript exceeds max size of " + MAX_CHAR_COUNT + " characters");
        }
        this.id = id;
        this.meetingId = meetingId;
        this.tenantId = tenantId;
        this.format = format;
        this.rawText = rawText;
        this.charCount = charCount;
        this.createdAt = createdAt;
    }

    public static Transcript create(
            UUID meetingId, UUID tenantId, TranscriptFormat format, String rawText) {
        return new Transcript(
                UUID.randomUUID(),
                meetingId,
                tenantId,
                format,
                rawText,
                rawText.length(),
                OffsetDateTime.now());
    }

    public UUID id() {
        return id;
    }

    public UUID meetingId() {
        return meetingId;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public TranscriptFormat format() {
        return format;
    }

    public String rawText() {
        return rawText;
    }

    public int charCount() {
        return charCount;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
