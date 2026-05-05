package br.com.nora.api.infrastructure.persistence.meeting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "meeting_participants")
public class MeetingParticipantJpaEntity {

    @Id private UUID id;

    @ManyToOne
    @JoinColumn(name = "meeting_id", nullable = false)
    private MeetingJpaEntity meeting;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column private String email;

    @Column(name = "is_internal", nullable = false)
    private boolean isInternal;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public MeetingJpaEntity getMeeting() {
        return meeting;
    }

    public void setMeeting(MeetingJpaEntity meeting) {
        this.meeting = meeting;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public boolean isInternal() {
        return isInternal;
    }

    public void setInternal(boolean internal) {
        isInternal = internal;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
