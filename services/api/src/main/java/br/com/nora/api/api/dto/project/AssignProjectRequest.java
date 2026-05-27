package br.com.nora.api.api.dto.project;

import java.util.UUID;

/**
 * Body de PUT /meetings/{id}/project. {@code projectId} nulo desvincula a meeting de qualquer
 * project (unassign).
 */
public record AssignProjectRequest(UUID projectId) {}
