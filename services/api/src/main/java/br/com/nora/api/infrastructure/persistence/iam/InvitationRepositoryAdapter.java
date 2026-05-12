package br.com.nora.api.infrastructure.persistence.iam;

import br.com.nora.api.application.ports.InvitationRepository;
import br.com.nora.api.domain.iam.IamInvitation;
import br.com.nora.api.domain.iam.InvitationStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adapter JPA/native para a tabela {@code iam_user_invitations} + {@code iam_invitation_groups}
 * (US06, ADR 0011). Persistencia sem entidades JPA dedicadas — SQL nativo via EntityManager,
 * seguindo o padrao do {@code IamRepositoryAdapter}.
 */
@Repository
public class InvitationRepositoryAdapter implements InvitationRepository {

    @PersistenceContext private EntityManager em;

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Optional<IamInvitation> findByToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        List<Object[]> rows =
                em.createNativeQuery(
                                "SELECT id, tenant_id, email, token, status, invited_by,"
                                        + " invited_at, expires_at, accepted_at, accepted_user_id "
                                        + "FROM iam_user_invitations WHERE token = :token")
                        .setParameter("token", token)
                        .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] r = rows.get(0);
        UUID id = (UUID) r[0];
        return Optional.of(toInvitation(r, loadGroupIds(id)));
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Optional<IamInvitation> findById(UUID invitationId, UUID tenantId) {
        List<Object[]> rows =
                em.createNativeQuery(
                                "SELECT id, tenant_id, email, token, status, invited_by,"
                                        + " invited_at, expires_at, accepted_at, accepted_user_id "
                                        + "FROM iam_user_invitations WHERE id = :id AND tenant_id ="
                                        + " :t")
                        .setParameter("id", invitationId)
                        .setParameter("t", tenantId)
                        .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] r = rows.get(0);
        return Optional.of(toInvitation(r, loadGroupIds(invitationId)));
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Optional<IamInvitation> findPendingByEmail(UUID tenantId, String email) {
        List<Object[]> rows =
                em.createNativeQuery(
                                "SELECT id, tenant_id, email, token, status, invited_by,"
                                        + " invited_at, expires_at, accepted_at, accepted_user_id "
                                        + "FROM iam_user_invitations WHERE tenant_id = :t AND email"
                                        + " = :email AND status = 'PENDING' ORDER BY invited_at DESC"
                                        + " LIMIT 1")
                        .setParameter("t", tenantId)
                        .setParameter("email", email)
                        .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] r = rows.get(0);
        UUID id = (UUID) r[0];
        return Optional.of(toInvitation(r, loadGroupIds(id)));
    }

    @Override
    @Transactional
    public IamInvitation save(IamInvitation invitation) {
        // Upsert manual: detecta pelo id se ja existe.
        Object existing =
                em.createNativeQuery("SELECT id FROM iam_user_invitations WHERE id = :id")
                        .setParameter("id", invitation.id())
                        .getResultStream()
                        .findFirst()
                        .orElse(null);

        if (existing == null) {
            em.createNativeQuery(
                            "INSERT INTO iam_user_invitations (id, tenant_id, email, token,"
                                    + " status, invited_by, invited_at, expires_at, accepted_at,"
                                    + " accepted_user_id) VALUES (:id, :t, :email, :token, :status,"
                                    + " :invitedBy, :invitedAt, :expiresAt, :acceptedAt,"
                                    + " :acceptedUserId)")
                    .setParameter("id", invitation.id())
                    .setParameter("t", invitation.tenantId())
                    .setParameter("email", invitation.email())
                    .setParameter("token", invitation.token())
                    .setParameter("status", invitation.status().name())
                    .setParameter("invitedBy", invitation.invitedBy())
                    .setParameter("invitedAt", Timestamp.from(invitation.invitedAt()))
                    .setParameter("expiresAt", Timestamp.from(invitation.expiresAt()))
                    .setParameter(
                            "acceptedAt",
                            invitation.acceptedAt() == null
                                    ? null
                                    : Timestamp.from(invitation.acceptedAt()))
                    .setParameter("acceptedUserId", invitation.acceptedUserId())
                    .executeUpdate();
            for (UUID gid : invitation.groupIds()) {
                em.createNativeQuery(
                                "INSERT INTO iam_invitation_groups (invitation_id, group_id)"
                                        + " VALUES (:i, :g) ON CONFLICT DO NOTHING")
                        .setParameter("i", invitation.id())
                        .setParameter("g", gid)
                        .executeUpdate();
            }
        } else {
            // Update: apenas campos mutaveis (status/acceptedAt/acceptedUserId). Tenant/email/
            // token/invitedBy/invitedAt/expiresAt sao imutaveis no fluxo atual.
            em.createNativeQuery(
                            "UPDATE iam_user_invitations SET status = :status, accepted_at ="
                                    + " :acceptedAt, accepted_user_id = :acceptedUserId WHERE id ="
                                    + " :id")
                    .setParameter("status", invitation.status().name())
                    .setParameter(
                            "acceptedAt",
                            invitation.acceptedAt() == null
                                    ? null
                                    : Timestamp.from(invitation.acceptedAt()))
                    .setParameter("acceptedUserId", invitation.acceptedUserId())
                    .setParameter("id", invitation.id())
                    .executeUpdate();
        }
        return invitation;
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<IamInvitation> listByTenant(UUID tenantId, InvitationStatus status) {
        String sql =
                "SELECT id, tenant_id, email, token, status, invited_by, invited_at,"
                        + " expires_at, accepted_at, accepted_user_id FROM iam_user_invitations "
                        + "WHERE tenant_id = :t "
                        + (status == null ? "" : "AND status = :status ")
                        + "ORDER BY invited_at DESC";
        var query = em.createNativeQuery(sql).setParameter("t", tenantId);
        if (status != null) {
            query.setParameter("status", status.name());
        }
        List<Object[]> rows = query.getResultList();
        List<IamInvitation> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            UUID id = (UUID) r[0];
            out.add(toInvitation(r, loadGroupIds(id)));
        }
        return out;
    }

    // ---------- helpers ----------

    @SuppressWarnings("unchecked")
    private Set<UUID> loadGroupIds(UUID invitationId) {
        List<UUID> ids =
                em.createNativeQuery(
                                "SELECT group_id FROM iam_invitation_groups WHERE invitation_id"
                                        + " = :i")
                        .setParameter("i", invitationId)
                        .getResultList();
        return new LinkedHashSet<>(ids);
    }

    private IamInvitation toInvitation(Object[] r, Set<UUID> groupIds) {
        return new IamInvitation(
                (UUID) r[0],
                (UUID) r[1],
                (String) r[2],
                (String) r[3],
                InvitationStatus.valueOf((String) r[4]),
                (UUID) r[5],
                toInstant(r[6]),
                toInstant(r[7]),
                r[8] == null ? null : toInstant(r[8]),
                (UUID) r[9],
                groupIds);
    }

    private static Instant toInstant(Object col) {
        if (col == null) {
            throw new IllegalStateException("expected non-null timestamp");
        }
        if (col instanceof Instant i) {
            return i;
        }
        if (col instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (col instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        }
        throw new IllegalStateException("unexpected timestamp type: " + col.getClass());
    }
}
