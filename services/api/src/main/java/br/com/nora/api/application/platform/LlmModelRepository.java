package br.com.nora.api.application.platform;

import br.com.nora.api.domain.platform.LlmModel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for the model catalog (platform database, ADR 0022). */
public interface LlmModelRepository {

    List<LlmModel> findAll();

    Optional<LlmModel> findById(UUID id);

    Optional<LlmModel> findByProviderAndModel(String provider, String modelId);

    /** Persists a new model and returns the row with id/timestamps filled in. */
    LlmModel insert(LlmModel model);

    void deleteById(UUID id);

    /** True if the model is bound in llm_config (blocks DELETE — 409). */
    boolean isBound(UUID modelId);
}
