/**
 * Camada de dados do console. Hoje serve mocks (NORA_ADMIN_USE_MOCKS != "false").
 * Quando o backend de plataforma do 4.8 subir, troca-se para fetch server-side
 * contra a API Spring, enviando o token interno (X-Internal-Token) e o e-mail do
 * operador (X-Operator-Email, do Easy Auth) pra auditoria. As assinaturas abaixo
 * já batem com os contratos /admin/platform/* — só o corpo muda.
 */
import type { CostSummary, FeatureFlag, LlmModel, ServiceBinding, ServiceKey } from "./contracts";
import { MOCK_BINDINGS, MOCK_COST, MOCK_FLAGS, MOCK_MODELS } from "./mock";

const USE_MOCKS = process.env.NORA_ADMIN_USE_MOCKS !== "false";

export async function getModels(): Promise<LlmModel[]> {
  if (USE_MOCKS) return MOCK_MODELS;
  return (await platformGet<LlmModel[] | null>("/admin/platform/models")) ?? [];
}

export async function getBindings(): Promise<ServiceBinding[]> {
  if (USE_MOCKS) return MOCK_BINDINGS;
  return (await platformGet<ServiceBinding[] | null>("/admin/platform/config")) ?? [];
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
  // vez de crashar (toFixed em undefined). Idealmente a API COALESCE pra 0 — follow-up no backend.
  return {
    from: raw?.from ?? from ?? "",
    to: raw?.to ?? to ?? "",
    totalCostUsd: raw?.totalCostUsd ?? 0,
    totalCalls: raw?.totalCalls ?? 0,
    rows: raw?.rows ?? [],
  };
}

/** Resolve o modelo de uma binding (helper de UI). */
export function modelOf(models: LlmModel[], modelId: string): LlmModel | undefined {
  return models.find((m) => m.id === modelId);
}

export const ALL_SERVICES: ServiceKey[] = ["chat", "analysis", "multimodal"];

// ---- transporte real (ativado quando NORA_ADMIN_USE_MOCKS=false) ----

const API_BASE_URL = (process.env.PLATFORM_API_BASE_URL ?? "http://localhost:8080").replace(/\/$/, "");
const INTERNAL_TOKEN = process.env.PLATFORM_INTERNAL_TOKEN ?? "";

async function platformGet<T>(path: string): Promise<T> {
  const res = await fetch(`${API_BASE_URL}${path}`, {
    headers: { Accept: "application/json", "X-Internal-Token": INTERNAL_TOKEN },
    cache: "no-store",
  });
  if (!res.ok) throw new Error(`Plataforma respondeu ${res.status} em ${path}`);
  return (await res.json()) as T;
}
