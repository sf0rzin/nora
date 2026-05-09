package br.com.nora.api.infrastructure.persistence.tenant;

import br.com.nora.api.application.ports.TenantContextRepository;
import br.com.nora.api.domain.tenant.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class TenantContextRepositoryAdapter implements TenantContextRepository {

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
        if (entity.getId() == null) {
            entity.setId(context.id());
            entity.setCreatedAt(context.createdAt());
        }
        entity.setTenantId(context.tenantId());
        entity.setDocument(serialize(context));
        entity.setUpdatedBy(context.updatedBy());
        entity.setUpdatedAt(context.updatedAt());
        TenantContextJpaEntity saved = jpa.save(entity);
        return toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TenantContext> findByTenantId(UUID tenantId) {
        return jpa.findByTenantId(tenantId).map(this::toDomain);
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
        ContextDocument doc;
        try {
            doc = objectMapper.readValue(e.getDocument(), ContextDocument.class);
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
                e.getId(),
                e.getTenantId(),
                doc.companyName(),
                doc.industry(),
                doc.valueProposition(),
                doc.idealCustomerProfile(),
                products,
                doc.competitors(),
                doc.objectionHandling(),
                e.getUpdatedBy(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }

    /** Formato JSON serializado em tenant_contexts.document. */
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
