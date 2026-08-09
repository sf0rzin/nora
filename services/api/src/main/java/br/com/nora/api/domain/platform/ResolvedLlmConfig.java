package br.com.nora.api.domain.platform;

/**
 * Resolved LLM config for a service (response of GET /internal/platform/llm-config). No API key —
 * the key is resolved by the consumer via provider→secret (ADR 0024, decision #C).
 */
public record ResolvedLlmConfig(String provider, String model, String baseUrl, boolean enabled) {}
