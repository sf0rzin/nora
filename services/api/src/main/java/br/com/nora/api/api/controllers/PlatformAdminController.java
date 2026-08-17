package br.com.nora.api.api.controllers;

import br.com.nora.api.api.dto.platform.PlatformDtos.BackfillRequest;
import br.com.nora.api.api.dto.platform.PlatformDtos.BindRequest;
import br.com.nora.api.api.dto.platform.PlatformDtos.BindingResponse;
import br.com.nora.api.api.dto.platform.PlatformDtos.CreateModelRequest;
import br.com.nora.api.api.dto.platform.PlatformDtos.ModelResponse;
import br.com.nora.api.api.security.AuthorizationNotRequired;
import br.com.nora.api.application.embedding.EmbeddingBackfillService;
import br.com.nora.api.application.platform.BusinessTelemetryService;
import br.com.nora.api.application.platform.CostTelemetryService;
import br.com.nora.api.application.platform.FeatureFlagService;
import br.com.nora.api.application.platform.HealthTelemetryService;
import br.com.nora.api.application.platform.ModelCatalogService;
import br.com.nora.api.application.platform.ModelCatalogService.NewModelCommand;
import br.com.nora.api.application.platform.PlatformValidationException;
import br.com.nora.api.domain.platform.BusinessSnapshot;
import br.com.nora.api.domain.platform.CostReport;
import br.com.nora.api.domain.platform.FeatureFlag;
import br.com.nora.api.domain.platform.HealthSnapshot;
import br.com.nora.api.domain.platform.LlmModel;
import br.com.nora.api.domain.platform.Modality;
import br.com.nora.api.domain.platform.ServiceBinding;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator console (platform-control-plane.md §3 contract). Protected by an admin token
 * (chain @Order(2)); the operator's e-mail arrives in X-Operator-Email (forwarded by nora-admin
 * from Easy Auth) and is written to the audit log. Operations that touch the platform database
 * return 503 when degraded/off (via PlatformUnavailableException → PlatformExceptionHandler).
 */
@RestController
@RequestMapping("/admin/platform")
@AuthorizationNotRequired(reason = "Control plane: token chain @Order(2), no IAM principal.")
public class PlatformAdminController {

    static final String OPERATOR_HEADER = "X-Operator-Email";

    private final ModelCatalogService catalog;
    private final CostTelemetryService cost;
    private final HealthTelemetryService health;
    private final BusinessTelemetryService business;
    private final FeatureFlagService flags;
    private final EmbeddingBackfillService embeddingBackfill;

    public PlatformAdminController(
            ModelCatalogService catalog,
            CostTelemetryService cost,
            HealthTelemetryService health,
            BusinessTelemetryService business,
            FeatureFlagService flags,
            EmbeddingBackfillService embeddingBackfill) {
        this.catalog = catalog;
        this.cost = cost;
        this.health = health;
        this.business = business;
        this.flags = flags;
        this.embeddingBackfill = embeddingBackfill;
    }

    // ---------- catalog ----------

    @GetMapping("/models")
    public List<ModelResponse> listModels() {
        return catalog.listModels().stream().map(ModelResponse::from).toList();
    }

    @PostMapping("/models")
    @ResponseStatus(HttpStatus.CREATED)
    public ModelResponse createModel(
            @Valid @RequestBody CreateModelRequest body,
            @RequestHeader(value = OPERATOR_HEADER, required = false) String operator) {
        NewModelCommand cmd =
                new NewModelCommand(
                        body.provider(),
                        body.model(),
                        body.displayName(),
                        body.baseUrl(),
                        Modality.fromWire(body.modality()),
                        Boolean.TRUE.equals(body.supportsStrictJsonSchema()),
                        nz(body.priceInputPerMTok()),
                        nz(body.priceOutputPerMTok()),
                        body.priceCachedInputPerMTok(),
                        body.enabled() == null || body.enabled());
        return ModelResponse.from(catalog.createModel(cmd, operator));
    }

    @DeleteMapping("/models/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteModel(
            @PathVariable UUID id,
            @RequestHeader(value = OPERATOR_HEADER, required = false) String operator) {
        catalog.deleteModel(id, operator);
    }

    // ---------- bindings ----------

    @GetMapping("/flags")
    public List<FeatureFlag> listFlags() {
        return flags.listFlags();
    }

    @GetMapping("/config")
    public List<BindingResponse> listConfig() {
        Map<UUID, LlmModel> byId =
                catalog.listModels().stream()
                        .collect(Collectors.toMap(LlmModel::id, Function.identity()));
        return catalog.listBindings().stream()
                .map(b -> BindingResponse.from(b, byId.get(b.modelId())))
                .toList();
    }

    @PutMapping("/config/{service}")
    public BindingResponse bind(
            @PathVariable String service,
            @Valid @RequestBody BindRequest body,
            @RequestHeader(value = OPERATOR_HEADER, required = false) String operator) {
        if (body.modelId() == null) {
            throw new PlatformValidationException("modelId is required", false);
        }
        boolean enabled = body.enabled() == null || body.enabled();
        ServiceBinding b = catalog.bindService(service, body.modelId(), enabled, operator);
        LlmModel model =
                catalog.listModels().stream()
                        .filter(m -> m.id().equals(b.modelId()))
                        .findFirst()
                        .orElse(null);
        return BindingResponse.from(b, model);
    }

    // ---------- telemetry ----------

    @GetMapping("/telemetry/cost")
    public CostReport cost(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String groupBy) {
        OffsetDateTime[] w = window(from, to);
        return cost.cost(w[0], w[1], groupBy);
    }

    @GetMapping("/telemetry/health")
    public HealthSnapshot health() {
        return health.health();
    }

    @GetMapping("/telemetry/business")
    public BusinessSnapshot business(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        OffsetDateTime[] w = window(from, to);
        return business.business(w[0], w[1]);
    }

    // ---------- RAG index backfill ----------

    /**
     * What a backfill would do, per tenant, before anything is done. Costs nothing — plain SQL over
     * the primary database, no call to the embedding provider — so it is the safe half of the pair.
     */
    @GetMapping("/embeddings/backfill")
    public EmbeddingBackfillService.IndexStatus embeddingBackfillPreview() {
        return embeddingBackfill.preview();
    }

    /**
     * Runs a bounded backfill for ONE tenant. Every meeting it reaches costs one embedding call, so
     * the tenant is required and the batch is capped — see {@link EmbeddingBackfillService}.
     */
    @PostMapping("/embeddings/backfill")
    public EmbeddingBackfillService.RunOutcome embeddingBackfillRun(
            @RequestBody(required = false) BackfillRequest body,
            @RequestHeader(value = OPERATOR_HEADER, required = false) String operator) {
        BackfillRequest request = body == null ? new BackfillRequest(null, null) : body;
        return embeddingBackfill.run(request.tenantId(), request.limit(), operator);
    }

    // ---------- helpers ----------

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** Window [from, to). Default: last 24h. ISO-8601 (datetime or date). Invalid → 400. */
    private OffsetDateTime[] window(String from, String to) {
        OffsetDateTime resolvedTo =
                parseOrNull(to) != null ? parseOrNull(to) : OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime parsedFrom = parseOrNull(from);
        OffsetDateTime resolvedFrom = parsedFrom != null ? parsedFrom : resolvedTo.minusHours(24);
        return new OffsetDateTime[] {resolvedFrom, resolvedTo};
    }

    private OffsetDateTime parseOrNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String v = s.trim();
        try {
            return OffsetDateTime.parse(v);
        } catch (DateTimeParseException ex) {
            try {
                return LocalDate.parse(v).atStartOfDay().atOffset(ZoneOffset.UTC);
            } catch (DateTimeParseException ex2) {
                throw new PlatformValidationException("invalid date (use ISO-8601): " + s, false);
            }
        }
    }
}
