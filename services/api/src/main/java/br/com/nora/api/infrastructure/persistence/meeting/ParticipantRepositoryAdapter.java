package br.com.nora.api.infrastructure.persistence.meeting;

import br.com.nora.api.application.ports.ParticipantRepository;
import br.com.nora.api.application.ports.TrendsRepository.Scope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * SQL behind participant identity (US13, ADR 0048). One native query, because the projection joins
 * {@code meeting_participants} to its meeting and no entity models that shape.
 *
 * <p><b>Soft-deleted meetings are excluded explicitly.</b> Native SQL bypasses the soft-delete
 * restriction the entity declares, so without {@code m.deleted_at IS NULL} a meeting that has been
 * deleted but not yet purged would keep contributing a person to the identity listing. Same
 * predicate, and the same reason, as {@code TrendsRepositoryAdapter}.
 *
 * <p><b>The meeting restriction is an array, not an expanded IN list</b> — also as in {@code
 * TrendsRepositoryAdapter}. On the per-item authorization path the id list is the size of the
 * tenant, and an expanded {@code IN} would put one JDBC parameter per meeting and break past 32767
 * of them. The empty array matches nothing, which is the correct reading of "this caller may open
 * no meeting".
 */
@Repository
public class ParticipantRepositoryAdapter implements ParticipantRepository {

    @PersistenceContext private EntityManager em;

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<ParticipantOccurrence> listOccurrences(Scope scope) {
        if (scope.restricted() && scope.meetingIds().isEmpty()) {
            return List.of();
        }
        String sql =
                "SELECT p.meeting_id, m.title, COALESCE(m.started_at, m.created_at) AS started_at, "
                        + "       p.display_name, p.email, p.is_internal "
                        + "FROM meeting_participants p "
                        + "JOIN meetings m ON m.id = p.meeting_id AND m.deleted_at IS NULL "
                        + "WHERE p.tenant_id = :tenantId"
                        + (scope.restricted() ? " AND m.id = ANY(CAST(:meetingIds AS uuid[]))" : "")
                        + " ORDER BY started_at DESC, m.id, p.display_name, p.id";
        Query query = em.createNativeQuery(sql);
        query.setParameter("tenantId", scope.tenantId());
        if (scope.restricted()) {
            query.setParameter("meetingIds", uuidArrayLiteral(scope.meetingIds()));
        }
        List<Object[]> rows = (List<Object[]>) query.getResultList();
        List<ParticipantOccurrence> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(
                    new ParticipantOccurrence(
                            (UUID) r[0],
                            (String) r[1],
                            toOffset(r[2]),
                            (String) r[3],
                            (String) r[4],
                            Boolean.TRUE.equals(r[5])));
        }
        return out;
    }

    /**
     * Postgres array literal. A UUID's text form has no character an array literal would have to
     * quote, so no escaping is needed and none is invented here.
     */
    private static String uuidArrayLiteral(List<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
    }

    private static OffsetDateTime toOffset(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime odt) {
            return odt;
        }
        if (value instanceof java.time.Instant instant) {
            return instant.atOffset(ZoneOffset.UTC);
        }
        return ((Timestamp) value).toInstant().atOffset(ZoneOffset.UTC);
    }
}
