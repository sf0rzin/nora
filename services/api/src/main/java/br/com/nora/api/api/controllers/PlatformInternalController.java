package br.com.nora.api.api.controllers;

import br.com.nora.api.api.dto.platform.PlatformDtos.UsageRequest;
import br.com.nora.api.application.platform.LlmConfigResolver;
import br.com.nora.api.application.platform.PlatformValidationException;
import br.com.nora.api.application.platform.UsageRecorder;
import br.com.nora.api.domain.platform.ResolvedLlmConfig;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints serviço-a-serviço do control plane (contrato platform-control-plane.md §2). Protegidos
 * por token interno (chain @Order(1) em PlatformSecurityConfig). Hot-path: nunca 5xx — o resolver faz
 * fallback SOFT e o usage é fire-and-forget.
 */
@RestController
@RequestMapping("/internal/platform")
public class PlatformInternalController {

    private final LlmConfigResolver resolver;
    private final UsageRecorder usage;

    public PlatformInternalController(LlmConfigResolver resolver, UsageRecorder usage) {
        this.resolver = resolver;
        this.usage = usage;
    }

    /** GET /internal/platform/llm-config?service={chat|analysis|multimodal} */
    @GetMapping("/llm-config")
    public ResponseEntity<ResolvedLlmConfig> llmConfig(@RequestParam String service) {
        if (!resolver.isValidService(service)) {
            throw new PlatformValidationException(
                    "service inválido: " + service + " (use chat|analysis|multimodal)", false);
        }
        return ResponseEntity.ok(resolver.resolve(service));
    }

    /** POST /internal/platform/usage — fire-and-forget, sempre 202. */
    @PostMapping("/usage")
    public ResponseEntity<Void> usage(@Valid @RequestBody UsageRequest body) {
        usage.recordExternal(
                body.service(),
                body.provider(),
                body.model(),
                parseUuid(body.tenantId()),
                nz(body.promptTokens()),
                nz(body.completionTokens()),
                body.costUsd(),
                body.latencyMs(),
                body.status());
        return ResponseEntity.accepted().build();
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException ex) {
            return null; // tenantId é dimensão best-effort — id malformado vira "sem tenant"
        }
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
