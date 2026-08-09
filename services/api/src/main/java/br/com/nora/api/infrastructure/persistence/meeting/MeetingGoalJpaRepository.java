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
    /**
     * {@code flushAutomatically} so nothing pending is silently discarded, and {@code
     * clearAutomatically} because a native DELETE is invisible to the persistence context: the
     * entity loaded by {@code findByMeetingIdAndTenantId} moments earlier stays MANAGED under an id
     * whose row no longer exists. The upsert in the adapter then calls {@code save} with a fresh
     * instance carrying that same assigned id, which — no {@code @Version}, no {@code Persistable}
     * — takes the {@code merge} branch and collides with the managed copy still sitting on that
     * {@code EntityKey}. Evicting everything right after the DELETE is what makes the second PUT of
     * the idempotent upsert behave like the first.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value =
                    "DELETE FROM meeting_goals WHERE meeting_id = :meetingId AND tenant_id = :tenantId",
            nativeQuery = true)
    int deleteByMeetingIdAndTenantIdNative(
            @Param("meetingId") UUID meetingId, @Param("tenantId") UUID tenantId);
}
