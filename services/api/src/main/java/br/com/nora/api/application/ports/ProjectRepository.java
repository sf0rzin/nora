package br.com.nora.api.application.ports;

import br.com.nora.api.domain.project.Project;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Porta de persistencia de projects. Toda consulta exige tenantId para enforcement (ADR 0002). */
public interface ProjectRepository {

    Project save(Project project);

    Optional<Project> findByIdAndTenant(UUID id, UUID tenantId);

    /** Lista todos os projects (nao-deletados) do tenant ordenados por created_at desc. */
    List<Project> listByTenant(UUID tenantId);

    /** Soft-delete (deleted_at = NOW()). Idempotente; no-op se ja deletado/inexistente. */
    void softDelete(UUID id, UUID tenantId);
}
