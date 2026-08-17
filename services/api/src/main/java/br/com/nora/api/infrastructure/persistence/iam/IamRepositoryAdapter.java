package br.com.nora.api.infrastructure.persistence.iam;

import br.com.nora.api.application.ports.IamRepository;
import br.com.nora.api.domain.iam.AttachedPolicy;
import br.com.nora.api.domain.iam.Effect;
import br.com.nora.api.domain.iam.IamAuditEvent;
import br.com.nora.api.domain.iam.IamGroup;
import br.com.nora.api.domain.iam.IamPolicy;
import br.com.nora.api.domain.iam.PermissionBoundary;
import br.com.nora.api.domain.iam.PolicyDocument;
import br.com.nora.api.domain.iam.PolicyStatement;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single adapter for the IAM subdomain. Native SQL via EntityManager — avoids creating 7 JPA
 * entities just for join tables. Policies are stored as JSONB in iam_policies.document.
 */
@Repository
public class IamRepositoryAdapter implements IamRepository {

    @PersistenceContext private EntityManager em;

    private final ObjectMapper json;

    public IamRepositoryAdapter(ObjectMapper json) {
        this.json = json;
    }

    // ===================== groups =====================

    @Override
    @Transactional
    public IamGroup createGroup(UUID tenantId, String name, String description, UUID createdBy) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery(
                        "INSERT INTO iam_groups (id, tenant_id, name, description, created_by) "
                                + "VALUES (:id, :tenantId, :name, :description, :createdBy)")
                .setParameter("id", id)
                .setParameter("tenantId", tenantId)
                .setParameter("name", name)
                .setParameter("description", description)
                .setParameter("createdBy", createdBy)
                .executeUpdate();
        return findGroup(id, tenantId).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Optional<IamGroup> findGroup(UUID groupId, UUID tenantId) {
        List<Object[]> rows =
                em.createNativeQuery(
                                "SELECT id, tenant_id, name, description, created_by, created_at, updated_at "
                                        + "FROM iam_groups WHERE id = :id AND tenant_id = :tenantId")
                        .setParameter("id", groupId)
                        .setParameter("tenantId", tenantId)
                        .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toGroup(rows.get(0)));
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<IamGroup> listGroups(UUID tenantId) {
        List<Object[]> rows =
                em.createNativeQuery(
                                "SELECT id, tenant_id, name, description, created_by, created_at, updated_at "
                                        + "FROM iam_groups WHERE tenant_id = :tenantId ORDER BY name")
                        .setParameter("tenantId", tenantId)
                        .getResultList();
        List<IamGroup> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(toGroup(r));
        }
        return out;
    }

    @Override
    @Transactional
    public void deleteGroup(UUID groupId, UUID tenantId) {
        em.createNativeQuery("DELETE FROM iam_groups WHERE id = :id AND tenant_id = :tenantId")
                .setParameter("id", groupId)
                .setParameter("tenantId", tenantId)
                .executeUpdate();
    }

    // ===================== membership =====================

    @Override
    @Transactional
    public void addUserToGroup(UUID userId, UUID groupId, UUID tenantId, UUID attachedBy) {
        em.createNativeQuery(
                        "INSERT INTO iam_user_groups (user_id, group_id, tenant_id, attached_by) "
                                + "VALUES (:u, :g, :t, :by) ON CONFLICT DO NOTHING")
                .setParameter("u", userId)
                .setParameter("g", groupId)
                .setParameter("t", tenantId)
                .setParameter("by", attachedBy)
                .executeUpdate();
    }

    @Override
    @Transactional
    public void removeUserFromGroup(UUID userId, UUID groupId, UUID tenantId) {
        em.createNativeQuery(
                        "DELETE FROM iam_user_groups WHERE user_id = :u AND group_id = :g AND tenant_id = :t")
                .setParameter("u", userId)
                .setParameter("g", groupId)
                .setParameter("t", tenantId)
                .executeUpdate();
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<UUID> listGroupMembers(UUID groupId, UUID tenantId) {
        return (List<UUID>)
                em.createNativeQuery(
                                "SELECT user_id FROM iam_user_groups "
                                        + "WHERE group_id = :g AND tenant_id = :t ORDER BY attached_at")
                        .setParameter("g", groupId)
                        .setParameter("t", tenantId)
                        .getResultList();
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<UUID> listUserGroups(UUID userId, UUID tenantId) {
        return (List<UUID>)
                em.createNativeQuery(
                                "SELECT group_id FROM iam_user_groups "
                                        + "WHERE user_id = :u AND tenant_id = :t")
                        .setParameter("u", userId)
                        .setParameter("t", tenantId)
                        .getResultList();
    }

    // ===================== policies =====================

    @Override
    @Transactional
    public IamPolicy createPolicy(
            UUID tenantId, String name, String description, String documentJson, UUID createdBy) {
        // Validates the JSON before persisting.
        parseDocument(documentJson);

        UUID id = UUID.randomUUID();
        var insert =
                em.createNativeQuery(
                        "INSERT INTO iam_policies (id, tenant_id, name, description, document,"
                                + " current_version, created_by) VALUES (:id, :t, :name, :desc,"
                                + " CAST(:doc AS jsonb), 1, :by)");
        insert.setParameter("id", id)
                .setParameter("t", tenantId)
                .setParameter("name", name)
                .setParameter("desc", description)
                .setParameter("doc", documentJson)
                .setParameter("by", createdBy)
                .executeUpdate();

        em.createNativeQuery(
                        "INSERT INTO iam_policy_versions (policy_id, version, tenant_id, document, created_by) "
                                + "VALUES (:id, 1, :t, CAST(:doc AS jsonb), :by)")
                .setParameter("id", id)
                .setParameter("t", tenantId)
                .setParameter("doc", documentJson)
                .setParameter("by", createdBy)
                .executeUpdate();

        return findPolicy(id, tenantId).orElseThrow();
    }

    @Override
    @Transactional
    public IamPolicy updatePolicyDocument(
            UUID policyId, UUID tenantId, String documentJson, UUID updatedBy) {
        IamPolicy current = findPolicy(policyId, tenantId).orElseThrow();
        parseDocument(documentJson);
        int newVersion = current.currentVersion() + 1;

        em.createNativeQuery(
                        "UPDATE iam_policies SET document = CAST(:doc AS jsonb), "
                                + "current_version = :v, updated_at = NOW() "
                                + "WHERE id = :id AND tenant_id = :t")
                .setParameter("doc", documentJson)
                .setParameter("v", newVersion)
                .setParameter("id", policyId)
                .setParameter("t", tenantId)
                .executeUpdate();

        em.createNativeQuery(
                        "INSERT INTO iam_policy_versions (policy_id, version, tenant_id, document, created_by) "
                                + "VALUES (:id, :v, :t, CAST(:doc AS jsonb), :by)")
                .setParameter("id", policyId)
                .setParameter("v", newVersion)
                .setParameter("t", tenantId)
                .setParameter("doc", documentJson)
                .setParameter("by", updatedBy)
                .executeUpdate();

        return findPolicy(policyId, tenantId).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Optional<IamPolicy> findPolicy(UUID policyId, UUID tenantId) {
        var query =
                (NativeQuery<Object[]>)
                        em.createNativeQuery(
                                "SELECT id, tenant_id, name, description, document::text, "
                                        + "current_version, created_by, created_at, updated_at "
                                        + "FROM iam_policies WHERE id = :id AND tenant_id = :t");
        query.setParameter("id", policyId).setParameter("t", tenantId);
        List<Object[]> rows = query.getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toPolicy(rows.get(0)));
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<IamPolicy> listPolicies(UUID tenantId) {
        var query =
                (NativeQuery<Object[]>)
                        em.createNativeQuery(
                                "SELECT id, tenant_id, name, description, document::text, "
                                        + "current_version, created_by, created_at, updated_at "
                                        + "FROM iam_policies WHERE tenant_id = :t ORDER BY name");
        query.setParameter("t", tenantId);
        List<Object[]> rows = query.getResultList();
        List<IamPolicy> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(toPolicy(r));
        }
        return out;
    }

    @Override
    @Transactional
    public void deletePolicy(UUID policyId, UUID tenantId) {
        em.createNativeQuery("DELETE FROM iam_policies WHERE id = :id AND tenant_id = :t")
                .setParameter("id", policyId)
                .setParameter("t", tenantId)
                .executeUpdate();
    }

    // ===================== attachments =====================

    @Override
    @Transactional
    public void attachPolicyToGroup(UUID policyId, UUID groupId, UUID tenantId, UUID attachedBy) {
        em.createNativeQuery(
                        "INSERT INTO iam_group_policies (group_id, policy_id, tenant_id, attached_by) "
                                + "VALUES (:g, :p, :t, :by) ON CONFLICT DO NOTHING")
                .setParameter("g", groupId)
                .setParameter("p", policyId)
                .setParameter("t", tenantId)
                .setParameter("by", attachedBy)
                .executeUpdate();
    }

    @Override
    @Transactional
    public void detachPolicyFromGroup(UUID policyId, UUID groupId, UUID tenantId) {
        em.createNativeQuery(
                        "DELETE FROM iam_group_policies WHERE group_id = :g AND policy_id = :p AND tenant_id = :t")
                .setParameter("g", groupId)
                .setParameter("p", policyId)
                .setParameter("t", tenantId)
                .executeUpdate();
    }

    @Override
    @Transactional
    public void attachPolicyToUser(UUID policyId, UUID userId, UUID tenantId, UUID attachedBy) {
        em.createNativeQuery(
                        "INSERT INTO iam_user_policies (user_id, policy_id, tenant_id, attached_by) "
                                + "VALUES (:u, :p, :t, :by) ON CONFLICT DO NOTHING")
                .setParameter("u", userId)
                .setParameter("p", policyId)
                .setParameter("t", tenantId)
                .setParameter("by", attachedBy)
                .executeUpdate();
    }

    @Override
    @Transactional
    public void detachPolicyFromUser(UUID policyId, UUID userId, UUID tenantId) {
        em.createNativeQuery(
                        "DELETE FROM iam_user_policies WHERE user_id = :u AND policy_id = :p AND tenant_id = :t")
                .setParameter("u", userId)
                .setParameter("p", policyId)
                .setParameter("t", tenantId)
                .executeUpdate();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PolicyStatement> collectStatementsForUser(UUID userId, UUID tenantId) {
        List<PolicyStatement> out = new ArrayList<>();
        for (AttachedPolicy p : collectAttachedPoliciesForUser(userId, tenantId)) {
            out.addAll(p.statements());
        }
        return out;
    }

    /**
     * The single query behind both collectors — the flat one above is this one flattened, so the
     * set of policies a decision is taken from and the set a simulation explains it from cannot
     * diverge.
     *
     * <p>Ordered by name only to make the answer deterministic; statement order does not change a
     * decision, since a Deny wins wherever it sits.
     */
    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<AttachedPolicy> collectAttachedPoliciesForUser(UUID userId, UUID tenantId) {
        String sql =
                "SELECT p.id, p.name, p.document::text FROM iam_policies p "
                        + "WHERE p.tenant_id = :t AND p.id IN ("
                        + "  SELECT policy_id FROM iam_user_policies WHERE user_id = :u AND tenant_id = :t "
                        + "  UNION "
                        + "  SELECT gp.policy_id FROM iam_group_policies gp "
                        + "    JOIN iam_user_groups ug ON ug.group_id = gp.group_id "
                        + "    WHERE ug.user_id = :u AND ug.tenant_id = :t) "
                        + "ORDER BY p.name";
        List<Object[]> rows =
                em.createNativeQuery(sql)
                        .setParameter("t", tenantId)
                        .setParameter("u", userId)
                        .getResultList();
        List<AttachedPolicy> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            PolicyDocument doc = parseDocument((String) r[2]);
            out.add(new AttachedPolicy((UUID) r[0], (String) r[1], doc.statements()));
        }
        return out;
    }

    // ===================== permission boundary (US44) =====================

    /**
     * The user's cap, joined to the policy that expresses it. {@code empty} is the answer for the
     * overwhelming majority of users and means unrestricted, which is why the join is an inner one
     * and no row is synthesised: there is nothing to represent.
     */
    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Optional<PermissionBoundary> findBoundaryForUser(UUID userId, UUID tenantId) {
        String sql =
                "SELECT b.policy_id, p.name, p.document::text, b.attached_by, b.attached_at "
                        + "FROM iam_permission_boundaries b "
                        + "JOIN iam_policies p ON p.id = b.policy_id AND p.tenant_id = b.tenant_id "
                        + "WHERE b.user_id = :u AND b.tenant_id = :t";
        List<Object[]> rows =
                em.createNativeQuery(sql)
                        .setParameter("u", userId)
                        .setParameter("t", tenantId)
                        .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] r = rows.get(0);
        PolicyDocument doc = parseDocument((String) r[2]);
        return Optional.of(
                new PermissionBoundary(
                        userId,
                        (UUID) r[0],
                        (String) r[1],
                        doc.statements(),
                        (UUID) r[3],
                        toOdt(r[4])));
    }

    /**
     * Upsert on the primary key: replacing a boundary is one statement, so there is no instant at
     * which the user is momentarily uncapped.
     */
    @Override
    @Transactional
    public void setBoundaryForUser(UUID userId, UUID policyId, UUID tenantId, UUID attachedBy) {
        em.createNativeQuery(
                        "INSERT INTO iam_permission_boundaries"
                                + " (user_id, tenant_id, policy_id, attached_by) VALUES (:u, :t,"
                                + " :p, :by) ON CONFLICT (user_id) DO UPDATE SET policy_id ="
                                + " EXCLUDED.policy_id, attached_by = EXCLUDED.attached_by,"
                                + " updated_at = NOW()")
                .setParameter("u", userId)
                .setParameter("t", tenantId)
                .setParameter("p", policyId)
                .setParameter("by", attachedBy)
                .executeUpdate();
    }

    @Override
    @Transactional
    public void removeBoundaryForUser(UUID userId, UUID tenantId) {
        em.createNativeQuery(
                        "DELETE FROM iam_permission_boundaries WHERE user_id = :u"
                                + " AND tenant_id = :t")
                .setParameter("u", userId)
                .setParameter("t", tenantId)
                .executeUpdate();
    }

    // ===================== audit =====================

    @Override
    @Transactional
    public void recordAudit(
            UUID tenantId,
            UUID actorUserId,
            String action,
            String targetType,
            UUID targetId,
            Map<String, Object> payload) {
        String payloadJson;
        try {
            payloadJson = payload == null ? "{}" : json.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            payloadJson = "{}";
        }
        em.createNativeQuery(
                        "INSERT INTO iam_audit_events (id, tenant_id, actor_user_id, action,"
                                + " target_type, target_id, payload) VALUES (gen_random_uuid(),"
                                + " :t, :actor, :action, :type, :target, CAST(:payload AS jsonb))")
                .setParameter("t", tenantId)
                .setParameter("actor", actorUserId)
                .setParameter("action", action)
                .setParameter("type", targetType)
                .setParameter("target", targetId)
                .setParameter("payload", payloadJson)
                .executeUpdate();
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<IamAuditEvent> listAudit(UUID tenantId, OffsetDateTime since, int limit) {
        String sql =
                "SELECT id, tenant_id, actor_user_id, action, target_type, target_id, payload::text, created_at "
                        + "FROM iam_audit_events WHERE tenant_id = :t "
                        + "AND (CAST(:since AS timestamptz) IS NULL OR created_at >= :since) "
                        + "ORDER BY created_at DESC LIMIT :limit";
        List<Object[]> rows =
                em.createNativeQuery(sql)
                        .setParameter("t", tenantId)
                        .setParameter("since", since)
                        .setParameter("limit", limit)
                        .getResultList();
        List<IamAuditEvent> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            Map<String, Object> payload = parsePayload((String) r[6]);
            out.add(
                    new IamAuditEvent(
                            (UUID) r[0],
                            (UUID) r[1],
                            (UUID) r[2],
                            (String) r[3],
                            (String) r[4],
                            (UUID) r[5],
                            payload,
                            toOdt(r[7])));
        }
        return out;
    }

    // ===================== helpers =====================

    private IamGroup toGroup(Object[] r) {
        return new IamGroup(
                (UUID) r[0],
                (UUID) r[1],
                (String) r[2],
                (String) r[3],
                (UUID) r[4],
                toOdt(r[5]),
                toOdt(r[6]));
    }

    private IamPolicy toPolicy(Object[] r) {
        PolicyDocument doc = parseDocument((String) r[4]);
        return new IamPolicy(
                (UUID) r[0],
                (UUID) r[1],
                (String) r[2],
                (String) r[3],
                doc,
                ((Number) r[5]).intValue(),
                (UUID) r[6],
                toOdt(r[7]),
                toOdt(r[8]));
    }

    private PolicyDocument parseDocument(String docJson) {
        try {
            Map<String, Object> raw =
                    json.readValue(docJson, new TypeReference<Map<String, Object>>() {});
            String version = (String) raw.getOrDefault("version", "2026-05-07");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> stmts = (List<Map<String, Object>>) raw.get("statements");
            if (stmts == null || stmts.isEmpty()) {
                throw new IllegalArgumentException(
                        "policy document must contain at least one statement");
            }
            List<PolicyStatement> parsed = new ArrayList<>(stmts.size());
            for (Map<String, Object> s : stmts) {
                String effectStr = ((String) s.getOrDefault("effect", "Allow")).toUpperCase();
                Effect effect = Effect.valueOf(effectStr);
                List<String> actions = asStringList(s.get("action"));
                List<String> resources = asStringList(s.get("resource"));
                @SuppressWarnings("unchecked")
                Map<String, Object> condition = (Map<String, Object>) s.get("condition");
                parsed.add(new PolicyStatement(effect, actions, resources, condition));
            }
            return new PolicyDocument(version, parsed);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("invalid policy document: " + ex.getMessage());
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("policy document is not valid JSON");
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> asStringList(Object o) {
        if (o == null) {
            return List.of();
        }
        if (o instanceof String s) {
            return List.of(s);
        }
        if (o instanceof List<?> l) {
            List<String> out = new ArrayList<>(l.size());
            for (Object item : l) {
                out.add(String.valueOf(item));
            }
            return out;
        }
        throw new IllegalArgumentException("expected string or array of strings");
    }

    private Map<String, Object> parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return new HashMap<>();
        }
        try {
            return json.readValue(payloadJson, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException ex) {
            return new HashMap<>();
        }
    }

    private static OffsetDateTime toOdt(Object col) {
        if (col == null) {
            throw new IllegalStateException("expected non-null timestamp column");
        }
        if (col instanceof OffsetDateTime odt) {
            return odt;
        }
        if (col instanceof Instant i) {
            return i.atOffset(ZoneOffset.UTC);
        }
        if (col instanceof Timestamp ts) {
            return ts.toInstant().atOffset(ZoneOffset.UTC);
        }
        throw new IllegalStateException("unexpected timestamp type: " + col.getClass());
    }
}
