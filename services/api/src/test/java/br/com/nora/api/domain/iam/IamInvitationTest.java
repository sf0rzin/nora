package br.com.nora.api.domain.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Covers state transitions of the {@link IamInvitation} aggregate (US06). */
class IamInvitationTest {

    private static final Instant T0 = Instant.parse("2026-05-11T10:00:00Z");
    private static final Instant T_LATER = T0.plusSeconds(3600);
    private static final Instant EXPIRES = T0.plusSeconds(86400);

    private static IamInvitation pending() {
        return new IamInvitation(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "carlos@acme.com",
                "tok-123",
                InvitationStatus.PENDING,
                UUID.randomUUID(),
                T0,
                EXPIRES,
                null,
                null,
                Set.of(UUID.randomUUID()));
    }

    @Test
    void acceptTransitionsToAccepted_setsUserAndTimestamp_andKeepsGroups() {
        IamInvitation inv = pending();
        UUID userId = UUID.randomUUID();

        IamInvitation accepted = inv.accept(userId, T_LATER);

        assertThat(accepted.status()).isEqualTo(InvitationStatus.ACCEPTED);
        assertThat(accepted.acceptedAt()).isEqualTo(T_LATER);
        assertThat(accepted.acceptedUserId()).isEqualTo(userId);
        assertThat(accepted.groupIds()).isEqualTo(inv.groupIds());
        // Original is immutable.
        assertThat(inv.status()).isEqualTo(InvitationStatus.PENDING);
    }

    @Test
    void revokeTransitionsToRevoked_preservesGroupsAndTimestamps() {
        IamInvitation inv = pending();
        IamInvitation revoked = inv.revoke();
        assertThat(revoked.status()).isEqualTo(InvitationStatus.REVOKED);
        assertThat(revoked.acceptedAt()).isNull();
        assertThat(revoked.acceptedUserId()).isNull();
        assertThat(revoked.groupIds()).isEqualTo(inv.groupIds());
    }

    @Test
    void markExpiredTransitionsToExpired() {
        IamInvitation expired = pending().markExpired();
        assertThat(expired.status()).isEqualTo(InvitationStatus.EXPIRED);
    }

    @Test
    void isExpiredReturnsTrueAtOrAfterExpiry() {
        IamInvitation inv = pending();
        assertThat(inv.isExpired(EXPIRES.minusSeconds(1))).isFalse();
        assertThat(inv.isExpired(EXPIRES)).isTrue();
        assertThat(inv.isExpired(EXPIRES.plusSeconds(1))).isTrue();
    }

    @Test
    void cannotAcceptAfterRevoke() {
        IamInvitation revoked = pending().revoke();
        assertThatThrownBy(() -> revoked.accept(UUID.randomUUID(), T_LATER))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannotRevokeAfterAccept() {
        IamInvitation accepted = pending().accept(UUID.randomUUID(), T_LATER);
        assertThatThrownBy(accepted::revoke).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannotMarkExpiredFromNonPending() {
        IamInvitation accepted = pending().accept(UUID.randomUUID(), T_LATER);
        assertThatThrownBy(accepted::markExpired).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void groupIdsAreCopiedDefensively() {
        UUID g = UUID.randomUUID();
        java.util.Set<UUID> mutable = new java.util.HashSet<>();
        mutable.add(g);
        IamInvitation inv =
                new IamInvitation(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "x@y.com",
                        "t",
                        InvitationStatus.PENDING,
                        UUID.randomUUID(),
                        T0,
                        EXPIRES,
                        null,
                        null,
                        mutable);
        mutable.add(UUID.randomUUID());
        assertThat(inv.groupIds()).hasSize(1).containsExactly(g);
    }

    @Test
    void nullGroupIdsBecomeEmptySet() {
        IamInvitation inv =
                new IamInvitation(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "x@y.com",
                        "t",
                        InvitationStatus.PENDING,
                        UUID.randomUUID(),
                        T0,
                        EXPIRES,
                        null,
                        null,
                        null);
        assertThat(inv.groupIds()).isEmpty();
    }
}
