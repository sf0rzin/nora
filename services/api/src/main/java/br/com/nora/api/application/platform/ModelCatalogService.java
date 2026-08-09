package br.com.nora.api.application.platform;

import br.com.nora.api.domain.platform.LlmModel;
import br.com.nora.api.domain.platform.Modality;
import br.com.nora.api.domain.platform.ServiceBinding;
import br.com.nora.api.infrastructure.platform.PlatformAvailability;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * CRUD of the model catalog and the service→model bindings (ADR 0024). Requires the platform
 * database to be usable (admin → 503 when degraded/off at boot AND when the database goes down at
 * runtime — see {@link #guarded}). Every mutation is audited with the operator's e-mail
 * (X-Operator-Email). Applies the router/strict validations of ADR 0024/0003 to the binding.
 */
@Service
public class ModelCatalogService {

    private final ObjectProvider<LlmModelRepository> modelsProvider;
    private final ObjectProvider<LlmConfigRepository> configProvider;
    private final ObjectProvider<PlatformAuditRepository> auditProvider;
    private final PlatformAvailability availability;
    private final LlmConfigResolver resolver;

    public ModelCatalogService(
            ObjectProvider<LlmModelRepository> modelsProvider,
            ObjectProvider<LlmConfigRepository> configProvider,
            ObjectProvider<PlatformAuditRepository> auditProvider,
            PlatformAvailability availability,
            LlmConfigResolver resolver) {
        this.modelsProvider = modelsProvider;
        this.configProvider = configProvider;
        this.auditProvider = auditProvider;
        this.availability = availability;
        this.resolver = resolver;
    }

    public List<LlmModel> listModels() {
        return guarded(() -> models().findAll());
    }

    public LlmModel createModel(NewModelCommand c, String operator) {
        return guarded(
                () -> {
                    models().findByProviderAndModel(c.provider(), c.modelId())
                            .ifPresent(
                                    m -> {
                                        throw new PlatformConflictException(
                                                "modelo já existe: "
                                                        + c.provider()
                                                        + "/"
                                                        + c.modelId());
                                    });
                    LlmModel toInsert =
                            new LlmModel(
                                    null,
                                    c.provider(),
                                    c.modelId(),
                                    c.displayName(),
                                    c.baseUrl(),
                                    c.modality(),
                                    c.supportsStrictJsonSchema(),
                                    c.priceInputPerMTok(),
                                    c.priceOutputPerMTok(),
                                    c.priceCachedInputPerMTok(),
                                    c.enabled(),
                                    null,
                                    null);
                    LlmModel saved = models().insert(toInsert);
                    audit(
                            operator,
                            "model.create",
                            "llm_model",
                            saved.id().toString(),
                            Map.of("provider", c.provider(), "model", c.modelId()));
                    return saved;
                });
    }

    public void deleteModel(UUID id, String operator) {
        guardedRun(
                () -> {
                    LlmModel m =
                            models().findById(id)
                                    .orElseThrow(
                                            () ->
                                                    new PlatformNotFoundException(
                                                            "modelo não encontrado: " + id));
                    if (models().isBound(id)) {
                        throw new PlatformConflictException(
                                "modelo está bindado em llm_config — desbinde do serviço antes de"
                                        + " remover");
                    }
                    models().deleteById(id);
                    audit(
                            operator,
                            "model.delete",
                            "llm_model",
                            id.toString(),
                            Map.of("provider", m.provider(), "model", m.modelId()));
                });
    }

    public List<ServiceBinding> listBindings() {
        return guarded(() -> configs().findAll());
    }

    public ServiceBinding bindService(
            String service, UUID modelId, boolean enabled, String operator) {
        if (!LlmConfigResolver.SERVICES.contains(service)) {
            throw new PlatformValidationException("serviço inválido: " + service, false);
        }
        return guarded(
                () -> {
                    LlmModel m =
                            models().findById(modelId)
                                    .orElseThrow(
                                            () ->
                                                    new PlatformNotFoundException(
                                                            "modelo não encontrado: " + modelId));
                    if ("analysis".equals(service) && !m.supportsStrictJsonSchema()) {
                        throw new PlatformValidationException(
                                "serviço 'analysis' exige modelo com supportsStrictJsonSchema=true"
                                        + " (ADR 0003)",
                                true);
                    }
                    if ("multimodal".equals(service) && m.modality() != Modality.MULTIMODAL) {
                        throw new PlatformValidationException(
                                "serviço 'multimodal' exige modelo modality=multimodal (ADR 0024)",
                                true);
                    }
                    ServiceBinding b = configs().upsert(service, modelId, enabled, operator);
                    resolver.invalidate(service);
                    audit(
                            operator,
                            "config.bind",
                            "service",
                            service,
                            Map.of("modelId", modelId.toString(), "enabled", enabled));
                    return b;
                });
    }

    // ---- helpers ----

    private LlmModelRepository models() {
        requireUsable();
        return modelsProvider.getObject();
    }

    private LlmConfigRepository configs() {
        requireUsable();
        return configProvider.getObject();
    }

    private void requireUsable() {
        if (!availability.isUsable()) {
            throw new PlatformUnavailableException(
                    "control plane indisponível (banco de plataforma)");
        }
    }

    /**
     * Runs an operation that touches the platform database, translating a runtime failure (database
     * went down post-boot) into {@link PlatformUnavailableException} → 503. Per-call (does not
     * degrade the state permanently): when the database comes back, the next call works. Domain
     * exceptions (Not Found/Conflict/Validation/Unavailable) propagate intact to the correct
     * statuses.
     */
    private <T> T guarded(Supplier<T> action) {
        try {
            return action.get();
        } catch (DataAccessException ex) {
            throw new PlatformUnavailableException("banco de plataforma indisponível em runtime");
        }
    }

    private void guardedRun(Runnable action) {
        guarded(
                () -> {
                    action.run();
                    return null;
                });
    }

    private void audit(
            String operator,
            String action,
            String type,
            String target,
            Map<String, Object> detail) {
        try {
            auditProvider.getObject().record(operator, action, type, target, detail);
        } catch (RuntimeException ex) {
            // Auditing never brings the operation down.
        }
    }

    /** Model creation command (coming from the controller, already bean-validated). */
    public record NewModelCommand(
            String provider,
            String modelId,
            String displayName,
            String baseUrl,
            Modality modality,
            boolean supportsStrictJsonSchema,
            BigDecimal priceInputPerMTok,
            BigDecimal priceOutputPerMTok,
            BigDecimal priceCachedInputPerMTok,
            boolean enabled) {}
}
