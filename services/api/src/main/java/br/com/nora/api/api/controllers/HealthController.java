package br.com.nora.api.api.controllers;

import br.com.nora.api.api.security.AuthorizationNotRequired;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public health endpoint. Kept separate from the actuator to allow a stable response even if the
 * actuator is disabled.
 */
@RestController
public class HealthController {

    @GetMapping("/healthz")
    @AuthorizationNotRequired(reason = "Public: liveness probe, exposes no tenant data.")
    public Map<String, Object> healthz() {
        return Map.of(
                "service", "nora-api",
                "status", "ok",
                "timestamp", Instant.now().toString());
    }
}
