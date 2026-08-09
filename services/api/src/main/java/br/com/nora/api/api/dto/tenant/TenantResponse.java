package br.com.nora.api.api.dto.tenant;

import java.time.Instant;
import java.util.UUID;

/** The user's workspace (Workspace tab in settings). */
public record TenantResponse(UUID id, String name, String slug, String plan, Instant createdAt) {}
