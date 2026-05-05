package br.com.nora.api.infrastructure.persistence.meeting;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "meeting_tags")
public class MeetingTagJpaEntity {

    @EmbeddedId private MeetingTagId id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    public MeetingTagId getId() {
        return id;
    }

    public void setId(MeetingTagId id) {
        this.id = id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    @Embeddable
    public static class MeetingTagId implements Serializable {

        @Column(name = "meeting_id", nullable = false)
        private UUID meetingId;

        @Column(nullable = false)
        private String tag;

        public MeetingTagId() {}

        public MeetingTagId(UUID meetingId, String tag) {
            this.meetingId = meetingId;
            this.tag = tag;
        }

        public UUID getMeetingId() {
            return meetingId;
        }

        public void setMeetingId(UUID meetingId) {
            this.meetingId = meetingId;
        }

        public String getTag() {
            return tag;
        }

        public void setTag(String tag) {
            this.tag = tag;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof MeetingTagId other)) {
                return false;
            }
            return Objects.equals(meetingId, other.meetingId) && Objects.equals(tag, other.tag);
        }

        @Override
        public int hashCode() {
            return Objects.hash(meetingId, tag);
        }
    }
}
