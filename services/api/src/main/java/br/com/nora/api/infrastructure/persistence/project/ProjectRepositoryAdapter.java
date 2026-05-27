package br.com.nora.api.infrastructure.persistence.project;

import br.com.nora.api.application.ports.ProjectRepository;
import br.com.nora.api.domain.project.Project;
import br.com.nora.api.domain.project.ProjectStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ProjectRepositoryAdapter implements ProjectRepository {

    private final ProjectJpaRepository jpa;

    public ProjectRepositoryAdapter(ProjectJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional
    public Project save(Project project) {
        ProjectJpaEntity saved = jpa.save(toEntity(project));
        return toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Project> findByIdAndTenant(UUID id, UUID tenantId) {
        return jpa.findByIdAndTenantId(id, tenantId).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Project> listByTenant(UUID tenantId) {
        return jpa.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public void softDelete(UUID id, UUID tenantId) {
        // Resolve dentro do tenant (o @SQLRestriction filtra deletados) e remove via @SQLDelete,
        // que faz UPDATE deleted_at = NOW() em vez de DELETE fisico (V019/V013). No-op se ja
        // deletado ou inexistente no tenant.
        jpa.findByIdAndTenantId(id, tenantId).ifPresent(jpa::delete);
    }

    private ProjectJpaEntity toEntity(Project p) {
        ProjectJpaEntity e = new ProjectJpaEntity();
        e.setId(p.id());
        e.setTenantId(p.tenantId());
        e.setName(p.name());
        e.setDescription(p.description());
        e.setStatus(p.status().name());
        e.setCreatedAt(p.createdAt());
        e.setUpdatedAt(p.updatedAt());
        e.setDeletedAt(p.deletedAt());
        return e;
    }

    private Project toDomain(ProjectJpaEntity e) {
        return new Project(
                e.getId(),
                e.getTenantId(),
                e.getName(),
                e.getDescription(),
                ProjectStatus.valueOf(e.getStatus()),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.getDeletedAt());
    }
}
