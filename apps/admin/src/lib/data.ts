/**
 * Camada de dados do console operador.
 *
 * Reads server-side contra a API Spring (/admin/platform/*), enviando o token de bridge
 * (X-Internal-Token) e — nas mutações — o e-mail do operador (X-Operator-Email, da identidade
 * Cloudflare Access) pra auditoria. Mocks só quando NORA_ADMIN_USE_MOCKS != "false" (dev local).
 *
 * Nota de contrato: o ModelResponse do backend usa `displayName`/`priceInputPerMTok`; o contrato
 * do front usa `label`/`inputCostPer1M`. `toModel` é a camada anticorrupção que reconcilia os dois.
 */
import type {
  BusinessSnapshot,
  CostSummary,
  FeatureFlag,
  HealthSnapshot,
  LlmModel,
  Modality,
  ServiceBinding,
  ServiceKey,
} from "./contracts";
import {
  MOCK_BINDINGS,
  MOCK_BUSINESS,
  MOCK_COST,
  MOCK_FLAGS,
  MOCK_HEALTH,
  MOCK_MODELS,
} from "./mock";

const USE_MOCKS = process.env.NORA_ADMIN_USE_MOCKS !== "false";
const API_BASE_URL = (process.env.PLATFORM_API_BASE_URL ?? "http://localhost:8080").replace(/\/$/, "");
const INTERNAL_TOKEN = process.env.PLATFORM_INTERNAL_TOKEN ?? "";

// Shape cru do ModelResponse do backend (nomes divergem do contrato do front).
interface RawModel {
  id: string;
  provider: string;
  model: string;
  displayName: string;
  baseUrl?: string | null;
  modality: string;
  supportsStrictJsonSchema: boolean;
  priceInputPerMTok: number | string;
  priceOutputPerMTok: number | string;
  priceCachedInputPerMTok?: number | string | null;
  enabled: boolean;
}

interface RawBinding {
  service: string;
  modelId: string;
  enabled: boolean;
}

function toModel(r: RawModel): LlmModel {
  return {
    id: r.id,
    provider: r.provider,
    model: r.model,
    label: r.displayName,
    modality: r.modality === "multimodal" ? "multimodal" : "text",
    inputCostPer1M: Number(r.priceInputPerMTok ?? 0),
    outputCostPer1M: Number(r.priceOutputPerMTok ?? 0),
    cachedInputCostPer1M:
      r.priceCachedInputPerMTok == null ? null : Number(r.priceCachedInputPerMTok),
    supportsStrictJsonSchema: r.supportsStrictJsonSchema,
  };
}

export async function getModels(): Promise<LlmModel[]> {
  if (USE_MOCKS) return MOCK_MODELS;
  const raw = (await platformGet<RawModel[] | null>("/admin/platform/models")) ?? [];
  return raw.map(toModel);
}

export async function getBindings(): Promise<ServiceBinding[]> {
  if (USE_MOCKS) return MOCK_BINDINGS;
  const raw = (await platformGet<RawBinding[] | null>("/admin/platform/config")) ?? [];
  return raw.map((b) => ({ service: b.service as ServiceKey, modelId: b.modelId, enabled: b.enabled }));
}

export async function getFlags(): Promise<FeatureFlag[]> {
  if (USE_MOCKS) return MOCK_FLAGS;
  return (await platformGet<FeatureFlag[] | null>("/admin/platform/flags")) ?? [];
}

export async function getCost(from?: string, to?: string): Promise<CostSummary> {
  if (USE_MOCKS) return MOCK_COST;
  const qs = new URLSearchParams({ groupBy: "service" });
  if (from) qs.set("from", from);
  if (to) qs.set("to", to);
  const raw = await platformGet<Partial<CostSummary> | null>(
    `/admin/platform/telemetry/cost?${qs.toString()}`,
  );
  // Telemetria vazia (plataforma recém-criada) → a API agrega SUM de zero linhas e pode devolver
  // null/ausente. Normaliza pra um CostSummary completo: o console mostra "$0.00 / 0 chamadas" em
  // vez de crashar (toFixed em undefined).
  return {
    from: raw?.from ?? from ?? "",
    to: raw?.to ?? to ?? "",
    totalCostUsd: raw?.totalCostUsd ?? 0,
    totalCalls: raw?.totalCalls ?? 0,
    rows: raw?.rows ?? [],
  };
}

/** Saúde do sistema (App Insights via backend). `source: "unavailable"` quando sem credenciais. */
export async function getHealth(): Promise<HealthSnapshot> {
  if (USE_MOCKS) return MOCK_HEALTH;
  const raw = await platformGet<Partial<HealthSnapshot> | null>(
    "/admin/platform/telemetry/health",
  );
  return {
    window: raw?.window ?? "1h",
    source: raw?.source ?? "unavailable",
    services: raw?.services ?? [],
    degraded: raw?.degraded ?? false,
    note: raw?.note ?? null,
  };
}

/** Métricas de negócio do banco primário. `enabled: false` quando desligado por flag. */
export async function getBusiness(from?: string, to?: string): Promise<BusinessSnapshot> {
  if (USE_MOCKS) return MOCK_BUSINESS;
  const qs = new URLSearchParams();
  if (from) qs.set("from", from);
  if (to) qs.set("to", to);
  const query = qs.toString();
  const suffix = query ? `?${query}` : "";
  const raw = await platformGet<Partial<BusinessSnapshot> | null>(
    `/admin/platform/telemetry/business${suffix}`,
  );
  return {
    from: raw?.from ?? from ?? "",
    to: raw?.to ?? to ?? "",
    enabled: raw?.enabled ?? false,
    analyses: raw?.analyses ?? 0,
    tenantsActive: raw?.tenantsActive ?? 0,
    productivityAvg: raw?.productivityAvg ?? null,
    customerConfidenceAvg: raw?.customerConfidenceAvg ?? null,
  };
}

/** Resolve o modelo de uma binding (helper de UI). */
export function modelOf(models: LlmModel[], modelId: string): LlmModel | undefined {
  return models.find((m) => m.id === modelId);
}

export const ALL_SERVICES: ServiceKey[] = ["chat", "analysis", "multimodal"];

// --------------------------------------------------------------------------- //
// Mutações (server-side; exigem o e-mail do operador pra auditoria no backend)
// --------------------------------------------------------------------------- //

export interface NewModelInput {
  provider: string;
  model: string;
  displayName: string;
  baseUrl?: string;
  modality: Modality;
  supportsStrictJsonSchema: boolean;
  priceInputPerMTok: number;
  priceOutputPerMTok: number;
  priceCachedInputPerMTok?: number | null;
}

/** Troca o modelo (e enabled) de um serviço em runtime. PUT /admin/platform/config/{service}. */
export async function bindService(
  service: ServiceKey,
  modelId: string,
  enabled: boolean,
  operator: string,
): Promise<void> {
  if (USE_MOCKS) return;
  await platformSend("PUT", `/admin/platform/config/${service}`, operator, { modelId, enabled });
}

/** Remove um modelo do catálogo. DELETE /admin/platform/models/{id}. */
export async function removeModel(id: string, operator: string): Promise<void> {
  if (USE_MOCKS) return;
  await platformSend("DELETE", `/admin/platform/models/${encodeURIComponent(id)}`, operator);
}

/** Cria um modelo no catálogo. POST /admin/platform/models. */
export async function createModel(input: NewModelInput, operator: string): Promise<void> {
  if (USE_MOCKS) return;
  await platformSend("POST", "/admin/platform/models", operator, { ...input, enabled: true });
}

// ---- transporte real (ativado quando NORA_ADMIN_USE_MOCKS=false) ----

async function platformGet<T>(path: string): Promise<T> {
  const res = await fetch(`${API_BASE_URL}${path}`, {
    headers: { Accept: "application/json", "X-Internal-Token": INTERNAL_TOKEN },
    cache: "no-store",
  });
  if (!res.ok) throw new Error(`Plataforma respondeu ${res.status} em ${path}`);
  return (await res.json()) as T;
}

async function platformSend<T>(
  method: string,
  path: string,
  operator: string,
  body?: unknown,
): Promise<T | null> {
  const res = await fetch(`${API_BASE_URL}${path}`, {
    method,
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      "X-Internal-Token": INTERNAL_TOKEN,
      "X-Operator-Email": operator,
    },
    body: body == null ? undefined : JSON.stringify(body),
    cache: "no-store",
  });
  if (!res.ok) throw new Error(`Plataforma respondeu ${res.status} em ${method} ${path}`);
  if (res.status === 204) return null;
  return (await res.json()) as T;
}
