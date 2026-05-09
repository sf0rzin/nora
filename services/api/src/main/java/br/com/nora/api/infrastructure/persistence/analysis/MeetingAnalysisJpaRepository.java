package br.com.nora.api.infrastructure.persistence.analysis;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeetingAnalysisJpaRepository
        extends JpaRepository<MeetingAnalysisJpaEntity, UUID> {

    Optional<MeetingAnalysisJpaEntity> findByMeetingIdAndTenantId(UUID meetingId, UUID tenantId);

    void deleteByMeetingIdAndTenantId(UUID meetingId, UUID tenantId);
}
