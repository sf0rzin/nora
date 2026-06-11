package br.com.nora.api.infrastructure.persistence.workflow;

import br.com.nora.api.application.ports.WorkflowRepository;
import br.com.nora.api.domain.workflow.TriggerType;
import br.com.nora.api.domain.workflow.Workflow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adapter JDBC (via {@link EntityManager} + SQL nativo) dos workflows do NORA Flows (V023). Mesmo
 * estilo do {@code ChatSessionRepositoryAdapter}: sempre escopado por tenant_id (RLS, ADR 0028).
 * {@code definition_json} entra com CAST explícito para JSONB.
 */
@Repository
public class WorkflowRepositoryAdapter implements WorkflowRepository {

    private static final String SELECT_COLUMNS =
            "SELECT w.id, w.tenant_id, w.name, w.trigger_type, w.definition_json::text,"
                    + " w.active, w.created_at, w.updated_at FROM workflows w ";

    @PersistenceContext private EntityManager em;

    @Override
    @Transactional
    public void create(Workflow workflow) {
        em.createNativeQuery(
                        "INSERT INTO workflows (id, tenant_id, name, trigger_type,"
                                + " definition_json, active, created_at, updated_at) VALUES (:id,"
                                + " :tenantId, :name, :triggerType, CAST(:definition AS jsonb),"
                                + " :active, :createdAt, :updatedAt)")
                .setParameter("id", workflow.id())
                .setParameter("tenantId", workflow.tenantId())
                .setParameter("name", workflow.name())
                .setParameter("triggerType", workflow.triggerType().wire())
                .setParameter("definition", workflow.definitionJson())
                .setParameter("active", workflow.active())
                .setParameter("createdAt", toTimestamp(workflow.createdAt()))
                .setParameter("updatedAt", toTimestamp(workflow.updatedAt()))
                .executeUpdate();
    }

    @Override
    @Transactional
    public void update(Workflow workflow) {
        em.createNativeQuery(
                        "UPDATE workflows SET name = :name, trigger_type = :triggerType,"
                                + " definition_json = CAST(:definition AS jsonb), active = :active,"
                                + " updated_at = :updatedAt WHERE id = :id AND tenant_id ="
                                + " :tenantId")
                .setParameter("name", workflow.name())
                .setParameter("triggerType", workflow.triggerType().wire())
                .setParameter("definition", workflow.definitionJson())
                .setParameter("active", workflow.active())
                .setParameter("updatedAt", toTimestamp(workflow.updatedAt()))
                .setParameter("id", workflow.id())
                .setParameter("tenantId", workflow.tenantId())
                .executeUpdate();
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Optional<Workflow> findByIdAndTenant(UUID id, UUID tenantId) {
        var query =
                em.createNativeQuery(
                        SELECT_COLUMNS + "WHERE w.id = :id AND w.tenant_id = :tenantId");
        query.setParameter("id", id);
        query.setParameter("tenantId", tenantId);
        List<Object[]> rows = (List<Object[]>) query.getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.of(toWorkflow(rows.get(0)));
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Workflow> listByTenant(UUID tenantId) {
        var query =
                em.createNativeQuery(
                        SELECT_COLUMNS
                                + "WHERE w.tenant_id = :tenantId ORDER BY w.created_at DESC");
        query.setParameter("tenantId", tenantId);
        List<Object[]> rows = (List<Object[]>) query.getResultList();
        List<Workflow> result = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            result.add(toWorkflow(r));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Workflow> findActiveByTenantAndTrigger(UUID tenantId, TriggerType triggerType) {
        var query =
                em.createNativeQuery(
                        SELECT_COLUMNS
                                + "WHERE w.tenant_id = :tenantId AND w.trigger_type = :triggerType"
                                + " AND w.active ORDER BY w.created_at ASC");
        query.setParameter("tenantId", tenantId);
        query.setParameter("triggerType", triggerType.wire());
        List<Object[]> rows = (List<Object[]>) query.getResultList();
        List<Workflow> result = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            result.add(toWorkflow(r));
        }
        return result;
    }

    @Override
    @Transactional
    public void delete(UUID id, UUID tenantId) {
        em.createNativeQuery("DELETE FROM workflows WHERE id = :id AND tenant_id = :tenantId")
                .setParameter("id", id)
                .setParameter("tenantId", tenantId)
                .executeUpdate();
    }

    private Workflow toWorkflow(Object[] r) {
        return new Workflow(
                (UUID) r[0],
                (UUID) r[1],
                (String) r[2],
                TriggerType.fromWire((String) r[3]),
                (String) r[4],
                (Boolean) r[5],
                toOffset(r[6]),
                toOffset(r[7]));
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

    private static Timestamp toTimestamp(OffsetDateTime value) {
        return value == null ? null : Timestamp.from(value.toInstant());
    }
}
