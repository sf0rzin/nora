package br.com.nora.api.infrastructure.persistence.meeting;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MeetingGoalJpaRepository extends JpaRepository<MeetingGoalJpaEntity, UUID> {

    Optional<MeetingGoalJpaEntity> findByMeetingIdAndTenantId(UUID meetingId, UUID tenantId);

    /**
     * Native DELETE triggers Postgres' ON DELETE CASCADE over meeting_goal_expected_outcomes,
     * avoiding the UPDATE SET NULL cycle Hibernate would attempt with orphanRemoval +
     * unidirectional @JoinColumn.
     */
    @Modifying
    @Query(
            value =
                    "DELETE FROM meeting_goals WHERE meeting_id = :meetingId AND tenant_id = :tenantId",
            nativeQuery = true)
    int deleteByMeetingIdAndTenantIdNative(
            @Param("meetingId") UUID meetingId, @Param("tenantId") UUID tenantId);
}
