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
     * DELETE nativo dispara o ON DELETE CASCADE do Postgres sobre meeting_goal_expected_outcomes,
     * evitando o ciclo UPDATE SET NULL que o Hibernate tentaria com orphanRemoval + @JoinColumn
     * unidirecional.
     */
    @Modifying
    @Query(
            value =
                    "DELETE FROM meeting_goals WHERE meeting_id = :meetingId AND tenant_id = :tenantId",
            nativeQuery = true)
    int deleteByMeetingIdAndTenantIdNative(
            @Param("meetingId") UUID meetingId, @Param("tenantId") UUID tenantId);
}
