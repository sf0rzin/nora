package br.com.nora.api.infrastructure.persistence.tenant;

import br.com.nora.api.domain.tenant.Tenant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenants")
public class TenantJpaEntity {

    @Id private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Tenant.Status status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Tenant.Plan plan;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TenantJpaEntity() {}

    public TenantJpaEntity(
            UUID id,
            String name,
            String slug,
            Tenant.Status status,
            Tenant.Plan plan,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.slug = slug;
        this.status = status;
        this.plan = plan;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public Tenant.Status getStatus() {
        return status;
    }

    public Tenant.Plan getPlan() {
        return plan;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
