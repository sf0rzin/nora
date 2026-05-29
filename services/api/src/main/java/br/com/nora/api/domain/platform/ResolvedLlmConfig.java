package br.com.nora.api.domain.platform;

/**
 * Config resolvida de LLM para um serviço (resposta de GET /internal/platform/llm-config). Sem chave
 * de API — a chave é resolvida pelo consumidor via provider→secret (ADR 0024, decisão #C).
 */
public record ResolvedLlmConfig(String provider, String model, String baseUrl, boolean enabled) {}
