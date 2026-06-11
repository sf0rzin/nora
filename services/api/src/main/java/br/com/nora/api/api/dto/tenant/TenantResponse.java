package br.com.nora.api.api.dto.tenant;

import java.time.Instant;
import java.util.UUID;

/** Workspace do usuario (aba Workspace das configuracoes). */
public record TenantResponse(UUID id, String name, String slug, String plan, Instant createdAt) {}
