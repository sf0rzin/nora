package br.com.nora.api.api.controllers;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint publico de saude. Mantido separado do actuator para permitir uma resposta estavel mesmo
 * se o actuator estiver desabilitado.
 */
@RestController
public class HealthController {

    @GetMapping("/healthz")
    public Map<String, Object> healthz() {
        return Map.of(
                "service", "nora-api",
                "status", "ok",
                "timestamp", Instant.now().toString());
    }
}
