package br.com.nora.api.infrastructure.persistence.meeting;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TranscriptJpaRepository extends JpaRepository<TranscriptJpaEntity, UUID> {

    Optional<TranscriptJpaEntity> findByMeetingIdAndTenantId(UUID meetingId, UUID tenantId);
}
