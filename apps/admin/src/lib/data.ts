/**
 * Data layer of the operator console.
 *
 * Server-side reads against the Spring API (/admin/platform/*), sending the bridge token
 * (X-Internal-Token) and — on mutations — the operator's e-mail (X-Operator-Email, from the
 * Cloudflare Access identity) for auditing. Mocks only when NORA_ADMIN_USE_MOCKS == "true", which
 * has to be asked for: anything else — including forgetting the variable — reads the real API.
 *
 * Contract note: the backend's ModelResponse uses `displayName`/`priceInputPerMTok`; the front-end
 * contract uses `label`/`inputCostPer1M`. `toModel` is the anticorruption layer that reconciles the two.
 */
import type {
  BusinessSnapshot,
  CostGroupBy,
  CostSummary,
  FeatureFlag,
  HealthSnapshot,
  LlmModel,
  Modality,
  ServiceBinding,
  ServiceKey,
} from "./contracts";
import { COST_GROUP_BY } from "./contracts";
import {
  MOCK_BINDINGS,
  MOCK_BUSINESS,
  MOCK_COST,
  MOCK_FLAGS,
  MOCK_HEALTH,
  MOCK_MODELS,
} from "./mock";

// Opt-IN, and it has to agree with the identical constant in ./access.ts — the two together are
// what makes "forgot the variable" mean "real data behind a real gate" instead of "fabricated data
// with no gate at all". See the long note on that constant.
const USE_MOCKS = process.env.NORA_ADMIN_USE_MOCKS === "true";
const API_BASE_URL = (process.env.PLATFORM_API_BASE_URL ?? "http://localhost:8080").replace(/\/$/, "");
const INTERNAL_TOKEN = process.env.PLATFORM_INTERNAL_TOKEN ?? "";

/**
 * Every call to the Spring API is bounded. Without this the console inherits the runtime's default,
 * which for Node's fetch is "wait as long as the socket stays open": an API that accepts the
 * connection and never answers (a stuck pool, a half-open tunnel) hangs the render instead of
 * failing, and a hung render is the one failure mode no error boundary can catch. Overridable
 * because the acceptable ceiling belongs to the deployment, not to the source.
 */
const TIMEOUT_MS = Number(process.env.PLATFORM_API_TIMEOUT_MS ?? "8000") || 8000;

// Raw shape of the backend's ModelResponse (names diverge from the front-end contract).
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

export async function getCost(
  from?: string,
  to?: string,
  groupBy: CostGroupBy = "service",
): Promise<CostSummary> {
  if (USE_MOCKS) return MOCK_COST;
  // Narrowed here rather than trusted from the caller: the value travels from a query string the
  // operator can type, and an unknown dimension is a 400 from the backend — a blank screen where a
  // default would have been the honest answer.
  const dimension = COST_GROUP_BY.includes(groupBy) ? groupBy : "service";
  const qs = new URLSearchParams({ groupBy: dimension });
  if (from) qs.set("from", from);
  if (to) qs.set("to", to);
  const raw = await platformGet<Partial<CostSummary> | null>(
    `/admin/platform/telemetry/cost?${qs.toString()}`,
  );
  // Empty telemetry (freshly created platform) → the API aggregates a SUM over zero rows and may return
  // null/absent. Normalizes to a complete CostSummary: the console shows "$0.00 / 0 chamadas" instead
  // of crashing (toFixed on undefined).
  return {
    from: raw?.from ?? from ?? "",
    to: raw?.to ?? to ?? "",
    totalCostUsd: raw?.totalCostUsd ?? 0,
    totalCalls: raw?.totalCalls ?? 0,
    rows: raw?.rows ?? [],
  };
}

/** System health (App Insights via backend). `source: "unavailable"` when there are no credentials. */
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

/** Business metrics from the primary database. `enabled: false` when turned off by flag. */
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

/** Resolves the model of a binding (UI helper). */
export function modelOf(models: LlmModel[], modelId: string): LlmModel | undefined {
  return models.find((m) => m.id === modelId);
}

export const ALL_SERVICES: ServiceKey[] = ["chat", "analysis", "multimodal"];

// --------------------------------------------------------------------------- //
// Mutations (server-side; require the operator's e-mail for auditing in the backend)
// --------------------------------------------------------------------------- //

/**
 * Payload of POST /admin/platform/models. The four required fields are required HERE because they
 * are `@NotBlank` in the backend's `CreateModelRequest` — `baseUrl` was optional in this type until
 * 2026-08-23, so the console built a payload the API rejects with 400 and the whole "create" half of
 * the catalog was unreachable from the UI. Keep this in parity with
 * `services/api/.../dto/platform/PlatformDtos.java`; `src/lib/data.test.ts` asserts the parity.
 */
export interface NewModelInput {
  provider: string;
  model: string;
  displayName: string;
  baseUrl: string;
  modality: Modality;
  supportsStrictJsonSchema: boolean;
  priceInputPerMTok: number;
  priceOutputPerMTok: number;
  priceCachedInputPerMTok?: number | null;
}

/** The `@NotBlank` fields of the backend's `CreateModelRequest`, in the order the form shows them. */
export const REQUIRED_MODEL_FIELDS = [
  "provider",
  "model",
  "displayName",
  "baseUrl",
] as const satisfies readonly (keyof NewModelInput)[];

/** Switches a service's model (and enabled) at runtime. PUT /admin/platform/config/{service}. */
export async function bindService(
  service: ServiceKey,
  modelId: string,
  enabled: boolean,
  operator: string,
): Promise<void> {
  if (USE_MOCKS) return;
  await platformSend("PUT", `/admin/platform/config/${service}`, operator, { modelId, enabled });
}

/** Removes a model from the catalog. DELETE /admin/platform/models/{id}. */
export async function removeModel(id: string, operator: string): Promise<void> {
  if (USE_MOCKS) return;
  await platformSend("DELETE", `/admin/platform/models/${encodeURIComponent(id)}`, operator);
}

/**
 * Creates a model in the catalog. POST /admin/platform/models.
 *
 * The blank check is the console's, not the API's, on purpose: reaching the backend to be told
 * "baseUrl must not be blank" costs a round trip and returns a message in English to a pt-BR
 * console. `USE_MOCKS` returns before the call, so this validation runs in the mock mode too —
 * which is exactly the mode in which the missing field went unnoticed for a whole story.
 */
export async function createModel(input: NewModelInput, operator: string): Promise<void> {
  const missing = REQUIRED_MODEL_FIELDS.filter((f) => (input[f] ?? "").trim() === "");
  if (missing.length > 0) {
    throw new Error(`Campos obrigatórios em branco: ${missing.join(", ")}.`);
  }
  if (USE_MOCKS) return;
  await platformSend("POST", "/admin/platform/models", operator, { ...input, enabled: true });
}

// ---- real transport (the default; mocks require NORA_ADMIN_USE_MOCKS=true) ----

/**
 * Turns the runtime's abort into a sentence an operator can act on. `AbortSignal.timeout` rejects
 * with a bare `TimeoutError`, which surfaces in the console as "The operation was aborted" — true
 * and useless. Naming the endpoint and the ceiling is the difference between "the console is
 * broken" and "the platform API is not answering".
 */
function asPlatformFailure(err: unknown, what: string): Error {
  if (err instanceof DOMException && err.name === "TimeoutError") {
    return new Error(`Plataforma não respondeu em ${TIMEOUT_MS} ms em ${what}`);
  }
  if (err instanceof Error) return new Error(`Falha ao falar com a plataforma em ${what}: ${err.message}`);
  return new Error(`Falha ao falar com a plataforma em ${what}`);
}

async function platformGet<T>(path: string): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`${API_BASE_URL}${path}`, {
      headers: { Accept: "application/json", "X-Internal-Token": INTERNAL_TOKEN },
      cache: "no-store",
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch (err) {
    throw asPlatformFailure(err, path);
  }
  if (!res.ok) throw new Error(`Plataforma respondeu ${res.status} em ${path}`);
  return (await res.json()) as T;
}

async function platformSend<T>(
  method: string,
  path: string,
  operator: string,
  body?: unknown,
): Promise<T | null> {
  let res: Response;
  try {
    res = await fetch(`${API_BASE_URL}${path}`, {
      method,
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
        "X-Internal-Token": INTERNAL_TOKEN,
        "X-Operator-Email": operator,
      },
      body: body == null ? undefined : JSON.stringify(body),
      cache: "no-store",
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch (err) {
    // Naming the method and the path matters more here than on a read: a mutation that times out
    // may still have been applied on the other side, and the operator needs to know which one.
    throw asPlatformFailure(err, `${method} ${path}`);
  }
  if (!res.ok) throw new Error(`Plataforma respondeu ${res.status} em ${method} ${path}`);
  if (res.status === 204) return null;
  return (await res.json()) as T;
}
