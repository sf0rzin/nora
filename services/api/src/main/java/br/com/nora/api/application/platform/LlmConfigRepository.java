package br.com.nora.api.application.platform;

import br.com.nora.api.domain.platform.ResolvedLlmConfig;
import br.com.nora.api.domain.platform.ServiceBinding;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for the service→model bindings (llm_config table, ADR 0024). */
public interface LlmConfigRepository {

    List<ServiceBinding> findAll();

    Optional<ServiceBinding> findByService(String service);

    /** Creates or updates a service's binding. */
    ServiceBinding upsert(String service, UUID modelId, boolean enabled, String updatedBy);

    /**
     * Resolves a service's active model via the llm_config↔llm_models join. {@code enabled} in the
     * result already combines binding.enabled AND model.enabled (the feature flag is applied in the
     * service layer).
     */
    Optional<ResolvedLlmConfig> resolveActive(String service);
}
