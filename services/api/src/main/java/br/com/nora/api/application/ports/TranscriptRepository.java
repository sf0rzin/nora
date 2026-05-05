package br.com.nora.api.application.ports;

import br.com.nora.api.domain.meeting.Transcript;
import java.util.Optional;
import java.util.UUID;

public interface TranscriptRepository {

    Transcript save(Transcript transcript);

    Optional<Transcript> findByMeetingAndTenant(UUID meetingId, UUID tenantId);
}
