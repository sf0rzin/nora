package br.com.nora.api.application.platform;

import br.com.nora.api.domain.platform.LlmModel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Porta de persistência do catálogo de modelos (banco de plataforma, ADR 0022). */
public interface LlmModelRepository {

    List<LlmModel> findAll();

    Optional<LlmModel> findById(UUID id);

    Optional<LlmModel> findByProviderAndModel(String provider, String modelId);

    /** Persiste um novo modelo e devolve a linha com id/timestamps preenchidos. */
    LlmModel insert(LlmModel model);

    void deleteById(UUID id);

    /** True se o modelo está bindado em llm_config (impede DELETE — 409). */
    boolean isBound(UUID modelId);
}
