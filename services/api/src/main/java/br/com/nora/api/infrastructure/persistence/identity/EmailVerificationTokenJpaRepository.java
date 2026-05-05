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

public interface EmailVerificationTokenJpaRepository
        extends JpaRepository<EmailVerificationTokenJpaEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailVerificationTokenJpaEntity> findByTokenHash(String tokenHash);

    List<EmailVerificationTokenJpaEntity> findAllByUserIdAndConsumedAtIsNull(UUID userId);

    @Modifying
    @Query(
            "update EmailVerificationTokenJpaEntity t set t.consumedAt = :now"
                    + " where t.userId = :userId and t.consumedAt is null")
    int markAllConsumed(@Param("userId") UUID userId, @Param("now") Instant now);
}
