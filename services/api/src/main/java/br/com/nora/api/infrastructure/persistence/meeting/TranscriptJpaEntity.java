package br.com.nora.api.infrastructure.persistence.meeting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "transcripts")
public class TranscriptJpaEntity {

    @Id private UUID id;

    @Column(name = "meeting_id", nullable = false, unique = true)
    private UUID meetingId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String format;

    @Column(name = "raw_text", nullable = false, columnDefinition = "TEXT")
    private String rawText;

    @Column(name = "char_count", nullable = false)
    private int charCount;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getMeetingId() {
        return meetingId;
    }

    public void setMeetingId(UUID meetingId) {
        this.meetingId = meetingId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getRawText() {
        return rawText;
    }

    public void setRawText(String rawText) {
        this.rawText = rawText;
    }

    public int getCharCount() {
        return charCount;
    }

    public void setCharCount(int charCount) {
        this.charCount = charCount;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
