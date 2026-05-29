package br.com.nora.api.domain.platform;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Binding serviço → modelo (tabela llm_config). Serviços válidos: {@code chat}, {@code analysis},
 * {@code multimodal} (ADR 0024).
 */
public record ServiceBinding(
        String service, UUID modelId, boolean enabled, String updatedBy, OffsetDateTime updatedAt) {}
