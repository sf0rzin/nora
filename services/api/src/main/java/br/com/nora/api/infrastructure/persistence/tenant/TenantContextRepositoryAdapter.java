package br.com.nora.api.infrastructure.persistence.tenant;

import br.com.nora.api.application.ports.TenantContextRepository;
import br.com.nora.api.domain.tenant.TenantContext;
import br.com.nora.api.domain.tenant.TenantContextVersion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence of the company context and of its immutable history (US31, migration V028).
 *
 * <p>The history table has a composite primary key {@code (context_id, version)} and no surrogate
 * id, so it is reached with native SQL rather than a second JPA entity — the same choice {@code
 * IamRepositoryAdapter} makes for {@code iam_policy_versions}, whose shape it copies.
 */
@Repository
public class TenantContextRepositoryAdapter implements TenantContextRepository {

    /** Metadata columns of a history row, shared by the listing and the single-version read. */
    private static final String VERSION_COLUMNS =
            "v.version, v.created_by, u.display_name, v.created_at";

    /**
     * Joins the history to the LIVE context of the tenant.
     *
     * <p>{@code deleted_at IS NULL} is what makes a soft-deleted context's history unreachable
     * through the API, mirroring the {@code @SQLRestriction} that hides the document itself. The
     * {@code tenant_id} predicate is application-level defense in depth: under RLS enforce the
     * policy created in V028 already filters the same rows, but RLS is off in the repository's
     * default configuration and this filter is then the only control.
     *
     * <p>The author is resolved by a LEFT JOIN, so a version whose author was removed still lists
     * (with a null name) instead of vanishing from the trail. {@code users.deleted_at} is
     * deliberately NOT filtered: a soft-deleted user's name is still the truthful answer to "who
     * made this change".
     */
    private static final String VERSION_FROM =
            "FROM tenant_context_versions v"
                    + " JOIN tenant_contexts c ON c.id = v.context_id AND c.deleted_at IS NULL"
                    + " LEFT JOIN users u ON u.id = v.created_by AND u.tenant_id = v.tenant_id"
                    + " WHERE v.tenant_id = :t";

    private static final String LIST_VERSIONS_SQL =
            "SELECT " + VERSION_COLUMNS + " " + VERSION_FROM + " ORDER BY v.version DESC";

    private static final String FIND_VERSION_SQL =
            "SELECT "
                    + VERSION_COLUMNS
                    + ", v.document::text, v.context_id "
                    + VERSION_FROM
                    + " AND v.version = :v";

    @PersistenceContext private EntityManager em;

    private final TenantContextJpaRepository jpa;
    private final ObjectMapper objectMapper;

    public TenantContextRepositoryAdapter(
            TenantContextJpaRepository jpa, ObjectMapper objectMapper) {
        this.jpa = jpa;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public TenantContext save(TenantContext context) {
        TenantContextJpaEntity entity =
                jpa.findByTenantId(context.tenantId()).orElseGet(TenantContextJpaEntity::new);
        boolean isNew = entity.getId() == null;
        String document = serialize(context);

        // Does this edit actually change anything? Compared as parsed JSON, never as text: the
        // column is JSONB, so Postgres hands back its own normalized rendering (whitespace dropped,
        // keys reordered) and a string comparison against freshly serialized Jackson output would
        // report "changed" on every single save.
        boolean documentChanged = isNew || !sameJson(document, entity.getDocument());

        if (isNew) {
            entity.setId(context.id());
            entity.setCreatedAt(context.createdAt());
            entity.setCurrentVersion(1);
        } else if (documentChanged) {
            entity.setCurrentVersion(entity.getCurrentVersion() + 1);
        }
        entity.setTenantId(context.tenantId());
        entity.setDocument(document);
        entity.setUpdatedBy(context.updatedBy());
        entity.setUpdatedAt(context.updatedAt());

        // saveAndFlush, not save: the INSERT below is native SQL against the history table, whose
        // foreign key points at the row this line writes. Letting Hibernate decide when to flush
        // would make the ordering implicit.
        TenantContextJpaEntity saved = jpa.saveAndFlush(entity);

        // A save that changes nothing writes no version. The alternative — one row per press of
        // the save button — fills the table with entries that differ in nothing but their
        // timestamp and buries the edits that matter. `updated_at` still moves, so "when was this
        // last confirmed" is not lost either.
        if (documentChanged) {
            em.createNativeQuery(
                            "INSERT INTO tenant_context_versions"
                                    + " (context_id, version, tenant_id, document, created_by)"
                                    + " VALUES (:id, :v, :t, CAST(:doc AS jsonb), :by)")
                    .setParameter("id", saved.getId())
                    .setParameter("v", saved.getCurrentVersion())
                    .setParameter("t", saved.getTenantId())
                    .setParameter("doc", document)
                    .setParameter("by", saved.getUpdatedBy())
                    .executeUpdate();
        }
        return toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TenantContext> findByTenantId(UUID tenantId) {
        return jpa.findByTenantId(tenantId).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<TenantContextVersion> listVersions(UUID tenantId) {
        var query = em.createNativeQuery(LIST_VERSIONS_SQL).setParameter("t", tenantId);
        List<Object[]> rows = query.getResultList();
        List<TenantContextVersion> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(toVersion(r));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Optional<TenantContextVersion.Detail> findVersion(UUID tenantId, int version) {
        List<Object[]> rows =
                em.createNativeQuery(FIND_VERSION_SQL)
                        .setParameter("t", tenantId)
                        .setParameter("v", version)
                        .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] r = rows.get(0);
        TenantContextVersion metadata = toVersion(r);
        // The frozen document is rehydrated into the live aggregate so both endpoints share one
        // mapping. updatedBy/updatedAt carry the VERSION's author and timestamp, not the current
        // row's — see TenantContextVersion.Detail.
        TenantContext document =
                fromDocument(
                        (UUID) r[5],
                        tenantId,
                        (String) r[4],
                        metadata.createdBy(),
                        metadata.createdAt(),
                        metadata.createdAt());
        return Optional.of(new TenantContextVersion.Detail(metadata, document));
    }

    private TenantContextVersion toVersion(Object[] r) {
        return new TenantContextVersion(
                ((Number) r[0]).intValue(), (UUID) r[1], (String) r[2], toOdt(r[3]));
    }

    /** Two documents are the same edit when they parse to the same JSON, whatever the encoding. */
    private boolean sameJson(String left, String right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        try {
            JsonNode a = objectMapper.readTree(left);
            JsonNode b = objectMapper.readTree(right);
            return a.equals(b);
        } catch (JsonProcessingException ex) {
            // Unparseable stored document: treat the edit as a change so the history keeps moving
            // rather than silently swallowing it.
            return false;
        }
    }

    private String serialize(TenantContext c) {
        try {
            ContextDocument doc =
                    new ContextDocument(
                            c.companyName(),
                            c.industry(),
                            c.valueProposition(),
                            c.idealCustomerProfile(),
                            c.products().stream()
                                    .map(
                                            p ->
                                                    new ProductDoc(
                                                            p.name(),
                                                            p.description(),
                                                            p.keyDifferentiators()))
                                    .toList(),
                            c.competitors(),
                            c.objectionHandling());
            return objectMapper.writeValueAsString(doc);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize tenant context", ex);
        }
    }

    private TenantContext toDomain(TenantContextJpaEntity e) {
        return fromDocument(
                e.getId(),
                e.getTenantId(),
                e.getDocument(),
                e.getUpdatedBy(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }

    private TenantContext fromDocument(
            UUID id,
            UUID tenantId,
            String documentJson,
            UUID updatedBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
        ContextDocument doc;
        try {
            doc = objectMapper.readValue(documentJson, ContextDocument.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to deserialize tenant context", ex);
        }
        List<TenantContext.Product> products =
                doc.products() == null
                        ? List.of()
                        : doc.products().stream()
                                .map(
                                        p ->
                                                new TenantContext.Product(
                                                        p.name(),
                                                        p.description(),
                                                        p.keyDifferentiators()))
                                .toList();
        return new TenantContext(
                id,
                tenantId,
                doc.companyName(),
                doc.industry(),
                doc.valueProposition(),
                doc.idealCustomerProfile(),
                products,
                doc.competitors(),
                doc.objectionHandling(),
                updatedBy,
                createdAt,
                updatedAt);
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

    /** JSON format serialized in tenant_contexts.document. */
    public record ContextDocument(
            String companyName,
            String industry,
            String valueProposition,
            String idealCustomerProfile,
            List<ProductDoc> products,
            List<String> competitors,
            List<String> objectionHandling) {}

    public record ProductDoc(String name, String description, List<String> keyDifferentiators) {}
}
