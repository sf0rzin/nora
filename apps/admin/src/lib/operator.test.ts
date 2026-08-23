/**
 * `src/lib/operator.ts` decides which e-mail the console shows in the sidebar and, when the gate is
 * off, which e-mail gets stamped on the platform's audit records. Two things are worth pinning:
 *
 *   - Cloudflare Access wins. It is the live path (ADR 0025); the Easy Auth branch below it is dead
 *     weight kept for robustness, and an ordering swap would let a forged `x-ms-client-principal`
 *     outrank the header the edge actually sets.
 *   - a malformed principal degrades to the development operator instead of throwing. This runs
 *     inside the root layout, so an exception here is a blank console rather than a wrong name.
 */
import { afterEach, describe, expect, it, vi } from "vitest";

import { getOperator } from "./operator";

const requestHeaders = vi.hoisted(() => new Map<string, string>());

// Hoisted above the import above by Vitest's transform, which is why `getOperator` sees the double.
vi.mock("next/headers", () => ({
  headers: async () => ({
    get: (name: string) => requestHeaders.get(name.toLowerCase()) ?? null,
  }),
}));

function easyAuthPrincipal(payload: unknown): string {
  return Buffer.from(JSON.stringify(payload), "utf-8").toString("base64");
}

afterEach(() => {
  requestHeaders.clear();
});

describe("getOperator", () => {
  it("falls back to the development operator when no identity header is present", async () => {
    await expect(getOperator()).resolves.toEqual({
      email: "operador-dev@nora.local",
      name: "Operador (dev)",
      authenticated: false,
    });
  });

  it("uses the Cloudflare Access e-mail header", async () => {
    requestHeaders.set("cf-access-authenticated-user-email", "operador@nora.systems");
    await expect(getOperator()).resolves.toEqual({
      email: "operador@nora.systems",
      name: "operador@nora.systems",
      authenticated: true,
    });
  });

  it("prefers Cloudflare Access over the legacy Easy Auth principal", async () => {
    requestHeaders.set("cf-access-authenticated-user-email", "operador@nora.systems");
    requestHeaders.set(
      "x-ms-client-principal",
      easyAuthPrincipal({ claims: [{ typ: "preferred_username", val: "outro@nora.systems" }] }),
    );
    await expect(getOperator()).resolves.toMatchObject({ email: "operador@nora.systems" });
  });

  it("reads name and e-mail out of an Easy Auth principal", async () => {
    requestHeaders.set(
      "x-ms-client-principal",
      easyAuthPrincipal({
        claims: [
          { typ: "preferred_username", val: "legado@nora.systems" },
          { typ: "name", val: "Operador Legado" },
        ],
      }),
    );
    await expect(getOperator()).resolves.toEqual({
      email: "legado@nora.systems",
      name: "Operador Legado",
      authenticated: true,
    });
  });

  it("degrades to the development operator when the principal cannot be decoded", async () => {
    requestHeaders.set("x-ms-client-principal", "not-base64-json");
    await expect(getOperator()).resolves.toMatchObject({ authenticated: false });
  });
});
