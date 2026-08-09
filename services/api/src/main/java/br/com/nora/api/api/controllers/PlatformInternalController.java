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
 * Service-to-service control plane endpoints (platform-control-plane.md §2 contract). Protected by
 * an internal token (chain @Order(1) in PlatformSecurityConfig). Hot path: never 5xx — the resolver
 * does a SOFT fallback and the usage is fire-and-forget.
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

    /** POST /internal/platform/usage — fire-and-forget, always 202. */
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
            return null; // tenantId is a best-effort dimension — malformed id becomes "no tenant"
        }
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
