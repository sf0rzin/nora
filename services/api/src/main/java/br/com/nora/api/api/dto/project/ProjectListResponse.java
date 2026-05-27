package br.com.nora.api.api.dto.project;

import java.util.List;

/** Resposta de GET /projects. */
public record ProjectListResponse(List<ProjectResponse> items) {}
