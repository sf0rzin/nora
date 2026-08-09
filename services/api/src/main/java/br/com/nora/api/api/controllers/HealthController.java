package br.com.nora.api.api.controllers;

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
    public Map<String, Object> healthz() {
        return Map.of(
                "service", "nora-api",
                "status", "ok",
                "timestamp", Instant.now().toString());
    }
}
