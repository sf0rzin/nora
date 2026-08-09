package br.com.nora.api.application.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.nora.api.application.platform.ModelCatalogService.NewModelCommand;
import br.com.nora.api.domain.platform.LlmModel;
import br.com.nora.api.domain.platform.Modality;
import br.com.nora.api.domain.platform.ServiceBinding;
import br.com.nora.api.infrastructure.platform.PlatformAvailability;
import br.com.nora.api.infrastructure.platform.PlatformProperties;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;

/** Catalog CRUD + the ADR 0024/0003 guards (strict on analysis, modality on multimodal). */
class ModelCatalogServiceTest {

    private final LlmModelRepository models = mock(LlmModelRepository.class);
    private final LlmConfigRepository configs = mock(LlmConfigRepository.class);
    private final PlatformAuditRepository audit = mock(PlatformAuditRepository.class);
    private final LlmConfigResolver resolver = mock(LlmConfigResolver.class);

    @Test
    void createModel_duplicado_409() {
        when(models.findByProviderAndModel("openai", "gpt-4o-mini"))
                .thenReturn(Optional.of(model(true, Modality.TEXT)));
        assertThatThrownBy(() -> svc(true).createModel(cmd(Modality.TEXT, true), "op"))
                .isInstanceOf(PlatformConflictException.class);
    }

    @Test
    void createModel_ok_persisteEAudita() {
        when(models.findByProviderAndModel("openai", "gpt-4o-mini")).thenReturn(Optional.empty());
        LlmModel saved = model(true, Modality.TEXT);
        when(models.insert(any())).thenReturn(saved);

        LlmModel out = svc(true).createModel(cmd(Modality.TEXT, true), "op@nora");

        assertThat(out).isEqualTo(saved);
        verify(audit).record(eq("op@nora"), eq("model.create"), eq("llm_model"), any(), any());
    }

    @Test
    void deleteModel_bindado_409() {
        UUID id = UUID.randomUUID();
        when(models.findById(id)).thenReturn(Optional.of(model(true, Modality.TEXT)));
        when(models.isBound(id)).thenReturn(true);
        assertThatThrownBy(() -> svc(true).deleteModel(id, "op"))
                .isInstanceOf(PlatformConflictException.class);
    }

    @Test
    void bindAnalysis_modeloSemStrict_422() {
        UUID id = UUID.randomUUID();
        when(models.findById(id)).thenReturn(Optional.of(model(false, Modality.TEXT)));
        assertThatThrownBy(() -> svc(true).bindService("analysis", id, true, "op"))
                .isInstanceOf(PlatformValidationException.class);
    }

    @Test
    void bindAnalysis_modeloStrict_ok_invalidaCache() {
        UUID id = UUID.randomUUID();
        when(models.findById(id)).thenReturn(Optional.of(model(true, Modality.TEXT)));
        when(configs.upsert("analysis", id, true, "op"))
                .thenReturn(new ServiceBinding("analysis", id, true, "op", OffsetDateTime.now()));

        svc(true).bindService("analysis", id, true, "op");

        verify(resolver).invalidate("analysis");
    }

    @Test
    void bindMultimodal_modeloTexto_422() {
        UUID id = UUID.randomUUID();
        when(models.findById(id)).thenReturn(Optional.of(model(true, Modality.TEXT)));
        assertThatThrownBy(() -> svc(true).bindService("multimodal", id, true, "op"))
                .isInstanceOf(PlatformValidationException.class);
    }

    @Test
    void bindServicoInvalido_400() {
        assertThatThrownBy(() -> svc(true).bindService("bogus", UUID.randomUUID(), true, "op"))
                .isInstanceOf(PlatformValidationException.class);
    }

    @Test
    void plataformaNaoUsavel_503() {
        assertThatThrownBy(() -> svc(false).listModels())
                .isInstanceOf(PlatformUnavailableException.class);
    }

    @Test
    void quedaDeRuntime_dataAccess_503() {
        when(models.findAll()).thenThrow(new DataAccessResourceFailureException("down"));
        assertThatThrownBy(() -> svc(true).listModels())
                .isInstanceOf(PlatformUnavailableException.class);
    }

    // ---- helpers ----

    private ModelCatalogService svc(boolean usable) {
        PlatformProperties p = new PlatformProperties();
        p.setEnabled(true);
        PlatformAvailability av = new PlatformAvailability(p);
        if (usable) {
            av.markHealthy();
        }
        return new ModelCatalogService(
                provider(models), provider(configs), provider(audit), av, resolver);
    }

    private static NewModelCommand cmd(Modality modality, boolean strict) {
        return new NewModelCommand(
                "openai",
                "gpt-4o-mini",
                "GPT-4o mini",
                "https://api.openai.com/v1",
                modality,
                strict,
                new BigDecimal("0.15"),
                new BigDecimal("0.60"),
                null,
                true);
    }

    private static LlmModel model(boolean strict, Modality modality) {
        return new LlmModel(
                UUID.randomUUID(),
                "openai",
                "gpt-4o-mini",
                "GPT-4o mini",
                "https://api.openai.com/v1",
                modality,
                strict,
                new BigDecimal("0.15"),
                new BigDecimal("0.60"),
                null,
                true,
                OffsetDateTime.now(),
                OffsetDateTime.now());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getObject()).thenReturn(value);
        return p;
    }
}
