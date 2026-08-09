/**
 * Control plane contracts — they mirror the tables/endpoints of the platform
 * module of the Spring API (design note from 4.8: llm_models, llm_config,
 * feature_flags, usage_events). KEEP IN PARITY with the backend.
 *
 * Endpoints consumed (server-side, via internal token) once the backend is up:
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
  model: string; // technical identifier (gpt-4o-mini, deepseek-v4-flash...)
  label: string; // friendly name
  modality: Modality;
  inputCostPer1M: number; // USD
  outputCostPer1M: number; // USD
  cachedInputCostPer1M?: number | null;
  supportsStrictJsonSchema: boolean;
}

/** Per-service binding (which model each surface uses). */
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

/** Cost aggregate (groupBy = model | service | tenant). */
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

/** Per-service health (Prometheus, ~1h window). Mirrors the backend's HealthSnapshot. */
export interface ServiceHealth {
  role: string; // Prometheus `job` label (nora-api, nora-web, nora-worker...)
  requests: number;
  failed: number;
  failureRate: number; // 0..1
  p95LatencyMs: number | null;
}

export interface HealthSnapshot {
  window: string; // e.g.: "1h"
  // Name of the adapter that answered: "prometheus" (ADR 0034; formerly "application-insights")
  // or "unavailable". The UI only branches on "unavailable" — the rest is a label.
  source: string;
  services: ServiceHealth[];
  degraded: boolean; // some failureRate > 5%
  note: string | null;
}

/** Business metrics aggregated from the primary database. Mirrors the backend's BusinessSnapshot. */
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
