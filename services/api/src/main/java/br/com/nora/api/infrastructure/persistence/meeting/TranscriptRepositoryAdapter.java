package br.com.nora.api.infrastructure.persistence.meeting;

import br.com.nora.api.application.ports.TranscriptRepository;
import br.com.nora.api.domain.meeting.Transcript;
import br.com.nora.api.domain.meeting.TranscriptFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class TranscriptRepositoryAdapter implements TranscriptRepository {

    private final TranscriptJpaRepository jpa;

    public TranscriptRepositoryAdapter(TranscriptJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Transcript save(Transcript t) {
        TranscriptJpaEntity e = new TranscriptJpaEntity();
        e.setId(t.id());
        e.setMeetingId(t.meetingId());
        e.setTenantId(t.tenantId());
        e.setFormat(t.format().name());
        e.setRawText(t.rawText());
        e.setCharCount(t.charCount());
        e.setCreatedAt(t.createdAt());
        TranscriptJpaEntity saved = jpa.save(e);
        return toDomain(saved);
    }

    @Override
    public Optional<Transcript> findByMeetingAndTenant(UUID meetingId, UUID tenantId) {
        return jpa.findByMeetingIdAndTenantId(meetingId, tenantId).map(this::toDomain);
    }

    private Transcript toDomain(TranscriptJpaEntity e) {
        return new Transcript(
                e.getId(),
                e.getMeetingId(),
                e.getTenantId(),
                TranscriptFormat.valueOf(e.getFormat()),
                e.getRawText(),
                e.getCharCount(),
                e.getCreatedAt());
    }
}
