import type {
  BusinessSnapshot,
  CostSummary,
  FeatureFlag,
  HealthSnapshot,
  LlmModel,
  ServiceBinding,
} from "./contracts";

/** Seeds — they mirror the platform V001 proposed by 4.8. Used while the
 *  backend is not plugged in (NORA_ADMIN_USE_MOCKS != "false"). */

export const MOCK_MODELS: LlmModel[] = [
  {
    id: "deepseek-v4-flash",
    provider: "deepseek",
    model: "deepseek-v4-flash",
    label: "DeepSeek V4 Flash",
    modality: "text",
    inputCostPer1M: 0.14,
    outputCostPer1M: 0.28,
    cachedInputCostPer1M: 0.014,
    supportsStrictJsonSchema: false,
  },
  {
    id: "gpt-4o-mini",
    provider: "openai",
    model: "gpt-4o-mini",
    label: "GPT-4o mini",
    modality: "text",
    inputCostPer1M: 0.15,
    outputCostPer1M: 0.6,
    cachedInputCostPer1M: 0.075,
    supportsStrictJsonSchema: true,
  },
  {
    id: "gemini-3.5-flash",
    provider: "google",
    model: "gemini-3.5-flash",
    label: "Gemini 3.5 Flash",
    modality: "multimodal",
    inputCostPer1M: 1.5,
    outputCostPer1M: 9.0,
    cachedInputCostPer1M: 0.15,
    supportsStrictJsonSchema: true,
  },
];

export const MOCK_BINDINGS: ServiceBinding[] = [
  { service: "chat", modelId: "deepseek-v4-flash", enabled: true },
  { service: "analysis", modelId: "gpt-4o-mini", enabled: true },
  { service: "multimodal", modelId: "gemini-3.5-flash", enabled: false },
];

export const MOCK_FLAGS: FeatureFlag[] = [
  { key: "chat", enabled: true, description: "Chat IA do Core" },
  { key: "analysis", enabled: true, description: "Análise automática de reuniões" },
  { key: "multimodal", enabled: false, description: "Ingestão de áudio/vídeo (pós-MVP)" },
  { key: "search-embeddings", enabled: false, description: "Busca semântica por embeddings" },
];

export const MOCK_COST: CostSummary = {
  from: "2026-05-01",
  to: "2026-05-28",
  totalCostUsd: 1.8423,
  totalCalls: 412,
  rows: [
    { key: "analysis", label: "Análise (gpt-4o-mini)", calls: 96, promptTokens: 980_000, completionTokens: 210_000, costUsd: 0.273 },
    { key: "chat", label: "Chat (deepseek-v4-flash)", calls: 305, promptTokens: 920_000, completionTokens: 340_000, costUsd: 0.224 },
    { key: "multimodal", label: "Multimodal (gemini-3.5-flash)", calls: 11, promptTokens: 140_000, completionTokens: 96_000, costUsd: 1.074 },
  ],
};

export const MOCK_HEALTH: HealthSnapshot = {
  window: "1h",
  source: "prometheus",
  degraded: false,
  note: null,
  services: [
    { role: "nora-api", requests: 1840, failed: 12, failureRate: 0.0065, p95LatencyMs: 412 },
    { role: "nora-web", requests: 3210, failed: 4, failureRate: 0.0012, p95LatencyMs: 188 },
    { role: "nora-worker", requests: 96, failed: 1, failureRate: 0.0104, p95LatencyMs: 8450 },
  ],
};

export const MOCK_BUSINESS: BusinessSnapshot = {
  from: "2026-06-10",
  to: "2026-06-11",
  enabled: true,
  analyses: 23,
  tenantsActive: 6,
  productivityAvg: 71.4,
  customerConfidenceAvg: 63.8,
};
