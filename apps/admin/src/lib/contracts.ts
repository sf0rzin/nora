/**
 * Contratos do control plane — espelham as tabelas/endpoints do módulo de
 * plataforma da API Spring (design note do 4.8: llm_models, llm_config,
 * feature_flags, usage_events). MANTER EM PARIDADE com o backend.
 *
 * Endpoints consumidos (server-side, via token interno) quando o backend subir:
 *   GET  /admin/platform/models
 *   POST /admin/platform/models           DELETE /admin/platform/models/{id}
 *   GET  /admin/platform/config           PUT    /admin/platform/config/{service}
 *   GET  /admin/platform/telemetry/cost?from&to&groupBy={tenant|model|service}
 *   GET  /admin/platform/telemetry/health
 *   GET  /admin/platform/telemetry/business?from&to
 */

export type Modality = "text" | "multimodal";
export type ServiceKey = "chat" | "analysis" | "multimodal";

export interface LlmModel {
  id: string;
  provider: string; // openai | deepseek | google ...
  model: string; // identificador técnico (gpt-4o-mini, deepseek-v4-flash...)
  label: string; // nome amigável
  modality: Modality;
  inputCostPer1M: number; // USD
  outputCostPer1M: number; // USD
  cachedInputCostPer1M?: number | null;
  supportsStrictJsonSchema: boolean;
}

/** Binding por-serviço (qual modelo cada superfície usa). */
export interface ServiceBinding {
  service: ServiceKey;
  modelId: string;
  enabled: boolean;
}

export interface FeatureFlag {
  key: string;
  enabled: boolean;
  description: string;
}

/** Agregado de custo (groupBy = model | service | tenant). */
export interface CostRow {
  key: string;
  label: string;
  calls: number;
  promptTokens: number;
  completionTokens: number;
  costUsd: number;
}

export interface CostSummary {
  from: string;
  to: string;
  totalCostUsd: number;
  totalCalls: number;
  rows: CostRow[];
}

/** Saúde por serviço (Prometheus, janela ~1h). Espelha HealthSnapshot do backend. */
export interface ServiceHealth {
  role: string; // label `job` do Prometheus (nora-api, nora-web, nora-worker...)
  requests: number;
  failed: number;
  failureRate: number; // 0..1
  p95LatencyMs: number | null;
}

export interface HealthSnapshot {
  window: string; // ex.: "1h"
  // Nome do adaptador que respondeu: "prometheus" (ADR 0034; antes "application-insights")
  // ou "unavailable". A UI só ramifica em "unavailable" — o resto é rótulo.
  source: string;
  services: ServiceHealth[];
  degraded: boolean; // alguma failureRate > 5%
  note: string | null;
}

/** Métricas de negócio agregadas do banco primário. Espelha BusinessSnapshot do backend. */
export interface BusinessSnapshot {
  from: string;
  to: string;
  enabled: boolean;
  analyses: number;
  tenantsActive: number;
  productivityAvg: number | null;
  customerConfidenceAvg: number | null;
}

export const SERVICE_LABEL: Record<ServiceKey, string> = {
  chat: "Chat IA (Core)",
  analysis: "Análise de reunião",
  multimodal: "Áudio/vídeo (multimodal)",
};
