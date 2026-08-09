package br.com.nora.api.infrastructure.persistence.identity;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenJpaEntity, UUID> {

    /** Pessimistic write because the caller updates {@code last_used_at} or {@code revoked_at}. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshTokenJpaEntity> findByTokenHash(String tokenHash);

    List<RefreshTokenJpaEntity> findAllByUserIdAndRevokedAtIsNull(UUID userId);

    @Modifying
    @Query(
            "update RefreshTokenJpaEntity t set t.revokedAt = :now"
                    + " where t.userId = :userId and t.revokedAt is null")
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying
    @Query(
            "update RefreshTokenJpaEntity t set t.revokedAt = :now"
                    + " where t.familyId = :familyId and t.revokedAt is null")
    int revokeAllForFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);
}
