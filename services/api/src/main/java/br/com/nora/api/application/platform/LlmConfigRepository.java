package br.com.nora.api.application.platform;

import br.com.nora.api.domain.platform.ResolvedLlmConfig;
import br.com.nora.api.domain.platform.ServiceBinding;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Porta de persistência dos bindings serviço→modelo (tabela llm_config, ADR 0024). */
public interface LlmConfigRepository {

    List<ServiceBinding> findAll();

    Optional<ServiceBinding> findByService(String service);

    /** Cria ou atualiza o binding de um serviço. */
    ServiceBinding upsert(String service, UUID modelId, boolean enabled, String updatedBy);

    /**
     * Resolve o modelo ativo de um serviço via join llm_config↔llm_models. {@code enabled} no
     * resultado já combina binding.enabled AND model.enabled (feature flag é aplicada na camada de
     * serviço).
     */
    Optional<ResolvedLlmConfig> resolveActive(String service);
}
