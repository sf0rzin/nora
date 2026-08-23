/**
 * `src/lib/data.ts` is the console's whole conversation with the Spring API, and the reason this
 * file exists is a defect it would have caught on the first run: the "add model" form built a
 * payload without `baseUrl`, the backend annotates that field `@NotBlank`, and every attempt to
 * create a model against a real API answered 400. It survived a story marked DONE because
 * `createModel` returns before the request under `NORA_ADMIN_USE_MOCKS=true` — the one mode anybody
 * ran the console in was the one mode that never exercised the call.
 *
 * Hence the mirror test below. It READS the Java rather than restating it: a test that hardcodes
 * the required field list on both sides passes forever after the backend adds a fifth one, which is
 * the exact failure this file is here to catch.
 */
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { CostGroupBy } from "./contracts";
import type { NewModelInput } from "./data";
import { createModel, getCost, getModels, REQUIRED_MODEL_FIELDS } from "./data";

const PLATFORM_DTOS = new URL(
  "../../../../services/api/src/main/java/br/com/nora/api/api/dto/platform/PlatformDtos.java",
  import.meta.url,
);

/**
 * Failing loudly when the backend source is missing is the point: a mirror test that skips itself
 * when it cannot find the other half of the mirror reports green while providing nothing. The whole
 * repository is checked out both in CI and on a workstation, so absence means the file moved — a
 * finding, not a reason to stay quiet.
 */
function readPlatformDtos(): string {
  const path = fileURLToPath(PLATFORM_DTOS);
  try {
    return readFileSync(path, "utf8");
  } catch (cause) {
    throw new Error(
      `Cannot read the backend DTO this test mirrors: ${path}. ` +
        "If the file moved, update this test; do not delete the assertion.",
      { cause },
    );
  }
}

/** Pulls the component list out of `public record <name>(...) {}` in a Java source. */
function javaRecordComponents(source: string, record: string): string[] {
  const match = new RegExp(`record ${record}\\(([\\s\\S]*?)\\)\\s*\\{`).exec(source);
  if (!match) throw new Error(`Record ${record} not found in PlatformDtos.java.`);
  return match[1]
    .split(",")
    .map((c) => c.trim())
    .filter((c) => c !== "");
}

/** The identifier of a Java record component — the last token of `@NotBlank String baseUrl`. */
function componentName(component: string): string {
  const parts = component.split(/\s+/);
  return parts[parts.length - 1];
}

const COMPLETE_INPUT: NewModelInput = {
  provider: "openai",
  model: "gpt-4o-mini",
  displayName: "GPT-4o mini",
  baseUrl: "https://api.openai.com/v1",
  modality: "text",
  supportsStrictJsonSchema: true,
  priceInputPerMTok: 0.15,
  priceOutputPerMTok: 0.6,
  priceCachedInputPerMTok: 0.075,
};

/** Minimal `fetch` double: records the call and answers with the given status and body. */
function stubFetch(body: unknown = {}, options: { status?: number } = {}) {
  const status = options.status ?? 200;
  const fetchMock = vi.fn(async (_url: string, _init: RequestInit) => ({
    ok: status < 400,
    status,
    json: async () => body,
  }));
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function lastRequest(fetchMock: ReturnType<typeof stubFetch>) {
  const call = fetchMock.mock.calls.at(-1);
  if (!call) throw new Error("fetch was never called");
  return { url: call[0], init: call[1] };
}

beforeEach(() => {
  vi.spyOn(console, "warn").mockImplementation(() => {});
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("POST /admin/platform/models stays in parity with CreateModelRequest", () => {
  it("declares exactly the backend's @NotBlank fields as required", () => {
    const components = javaRecordComponents(readPlatformDtos(), "CreateModelRequest");
    const notBlank = components.filter((c) => c.includes("@NotBlank")).map(componentName);

    expect(notBlank).toEqual([...REQUIRED_MODEL_FIELDS]);
  });

  it("sends every field the backend record declares", async () => {
    const fetchMock = stubFetch({ id: "new-model" });
    await createModel(COMPLETE_INPUT, "operador@nora.systems");

    const { url, init } = lastRequest(fetchMock);
    expect(url).toBe("http://platform.test.invalid/admin/platform/models");
    expect(init.method).toBe("POST");
    const payload = JSON.parse(String(init.body)) as Record<string, unknown>;

    const declared = javaRecordComponents(readPlatformDtos(), "CreateModelRequest").map(
      componentName,
    );
    // `enabled` is filled in by the data layer, not by the form — the console only ever creates
    // models that are on. Everything else has to come from the operator's input.
    for (const field of declared) {
      expect(payload, `missing field "${field}" in the create-model payload`).toHaveProperty(field);
    }
    expect(payload.baseUrl).toBe("https://api.openai.com/v1");
    expect(payload.priceCachedInputPerMTok).toBe(0.075);
    expect(payload.enabled).toBe(true);
  });

  it("stamps the operator's e-mail and the bridge token on the request", async () => {
    const fetchMock = stubFetch({ id: "new-model" });
    await createModel(COMPLETE_INPUT, "operador@nora.systems");

    const headers = lastRequest(fetchMock).init.headers as Record<string, string>;
    expect(headers["X-Operator-Email"]).toBe("operador@nora.systems");
    expect(headers).toHaveProperty("X-Internal-Token");
  });

  it.each(REQUIRED_MODEL_FIELDS)("refuses to send a blank %s", async (field) => {
    const fetchMock = stubFetch();
    const blank: NewModelInput = { ...COMPLETE_INPUT };
    blank[field] = "   ";
    await expect(createModel(blank, "operador@nora.systems")).rejects.toThrow(new RegExp(field));
    // The point of validating in the console is not politeness — it is not spending a round trip to
    // be told, in English, something the form already knew.
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("accepts a model with no cached-input price, sending null rather than zero", async () => {
    const fetchMock = stubFetch({ id: "new-model" });
    await createModel(
      { ...COMPLETE_INPUT, priceCachedInputPerMTok: null },
      "operador@nora.systems",
    );
    const payload = JSON.parse(String(lastRequest(fetchMock).init.body)) as Record<string, unknown>;
    expect(payload.priceCachedInputPerMTok).toBeNull();
  });
});

describe("cost telemetry query", () => {
  const EMPTY_COST = { from: "", to: "", totalCostUsd: 0, totalCalls: 0, rows: [] };

  it("defaults to grouping by service and sends no window", async () => {
    const fetchMock = stubFetch(EMPTY_COST);
    await getCost();
    const url = new URL(lastRequest(fetchMock).url);
    expect(url.searchParams.get("groupBy")).toBe("service");
    expect(url.searchParams.has("from")).toBe(false);
    expect(url.searchParams.has("to")).toBe(false);
  });

  // US83's own title is "per service and per tenant", and the query string hardcoded `service`
  // until 2026-08-23 — the tenant half of the story was reachable only by curl.
  it.each(["tenant", "model", "service"] as const)("passes groupBy=%s through", async (group) => {
    const fetchMock = stubFetch(EMPTY_COST);
    await getCost("2026-08-01", "2026-08-23", group);
    const url = new URL(lastRequest(fetchMock).url);
    expect(url.searchParams.get("groupBy")).toBe(group);
    expect(url.searchParams.get("from")).toBe("2026-08-01");
    expect(url.searchParams.get("to")).toBe("2026-08-23");
  });

  it("falls back to service for a dimension the backend would reject", async () => {
    const fetchMock = stubFetch(EMPTY_COST);
    // The value reaches this function from a query string an operator can edit, and an unknown
    // dimension is a 400 — a blank screen where a default was the honest answer.
    await getCost(undefined, undefined, "usuario" as unknown as CostGroupBy);
    expect(new URL(lastRequest(fetchMock).url).searchParams.get("groupBy")).toBe("service");
  });

  it("normalises an empty aggregate instead of rendering NaN", async () => {
    stubFetch(null);
    await expect(getCost("2026-08-01", "2026-08-23")).resolves.toEqual({
      from: "2026-08-01",
      to: "2026-08-23",
      totalCostUsd: 0,
      totalCalls: 0,
      rows: [],
    });
  });
});

describe("transport", () => {
  it("turns a non-2xx into an error that names the status and the path", async () => {
    stubFetch({}, { status: 503 });
    await expect(getModels()).rejects.toThrow("Plataforma respondeu 503 em /admin/platform/models");
  });

  it("bounds every call with a timeout signal", async () => {
    const fetchMock = stubFetch([]);
    await getModels();
    expect(lastRequest(fetchMock).init.signal).toBeInstanceOf(AbortSignal);
  });

  it("reports a timeout as a timeout, not as 'the operation was aborted'", async () => {
    // Node's abort rejects with a bare TimeoutError, which surfaces as "The operation was aborted":
    // true, and useless to whoever has to decide whether the API or the console is down.
    const fetchMock = vi.fn(async () => {
      throw new DOMException("The operation was aborted due to timeout", "TimeoutError");
    });
    vi.stubGlobal("fetch", fetchMock);
    await expect(getModels()).rejects.toThrow(
      /Plataforma não respondeu em \d+ ms em \/admin\/platform\/models/,
    );
  });

  it("maps the backend's field names onto the front-end contract", async () => {
    stubFetch([
      {
        id: "1",
        provider: "openai",
        model: "gpt-4o-mini",
        displayName: "GPT-4o mini",
        modality: "text",
        supportsStrictJsonSchema: true,
        priceInputPerMTok: "0.15",
        priceOutputPerMTok: "0.60",
        priceCachedInputPerMTok: null,
        enabled: true,
      },
    ]);
    await expect(getModels()).resolves.toEqual([
      {
        id: "1",
        provider: "openai",
        model: "gpt-4o-mini",
        label: "GPT-4o mini",
        modality: "text",
        inputCostPer1M: 0.15,
        outputCostPer1M: 0.6,
        // Absent is not zero: the catalog only shows the "cache $x" chip when a price exists.
        cachedInputCostPer1M: null,
        supportsStrictJsonSchema: true,
      },
    ]);
  });
});
