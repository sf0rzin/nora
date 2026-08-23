/**
 * `src/lib/access.ts` is the only authentication boundary the operator console has of its own, and
 * until this file existed nothing verified it. What makes the absence expensive is the shape of the
 * failure: every regression this module can suffer is SILENT. A gate that stops enforcing keeps
 * rendering pages; a JWT verified without an audience keeps returning `ok: true`. There is no error
 * screen, no failed request, no log line — the console simply lets everyone in and looks identical.
 *
 * So these tests are written around the decisions, not the lines:
 *
 *   1. mocks are opt-IN. The module read `!== "false"` until 2026-08-16, which made "forgot the
 *      variable" mean "gate off". Reintroducing that inversion is a one-character edit and the only
 *      barrier against it was human reading. Now `false`, empty, misspelled and unset all enforce,
 *      and each of those is a case below.
 *   2. an incomplete configuration BLOCKS. It used to degrade to "the edge still protects us",
 *      which is an argument about the attacker nobody was worried about.
 *   3. the audience is checked. Without it an Access JWT minted for another application in the same
 *      Cloudflare organisation authenticates here.
 *   4. the three denial reasons stay distinguishable, because the 403 screen picks its sentence
 *      from them and telling "never configured" from "no assertion" is the whole payoff.
 *
 * Every constant in the module is read at import time, so each case re-imports it after setting the
 * environment. `vi.resetModules()` before every dynamic import is what makes that work — without it
 * the second case would silently assert against the first case's configuration.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  requestHeaders: new Map<string, string>(),
  jwtVerify: vi.fn(),
  createRemoteJWKSet: vi.fn((_url: URL) => "jwks-handle"),
}));

vi.mock("next/headers", () => ({
  headers: async () => ({
    get: (name: string) => mocks.requestHeaders.get(name.toLowerCase()) ?? null,
  }),
}));

vi.mock("jose", () => ({
  createRemoteJWKSet: mocks.createRemoteJWKSet,
  jwtVerify: mocks.jwtVerify,
}));

const ORIGINAL_ENV = { ...process.env };

const CONFIGURED = {
  CF_ACCESS_TEAM_DOMAIN: "nora.cloudflareaccess.com",
  CF_ACCESS_AUD: "aud-of-this-application",
};

/** Sets the environment and re-imports the module with it. `undefined` means "variable unset". */
async function loadAccess(env: Record<string, string | undefined>) {
  process.env = { ...ORIGINAL_ENV };
  for (const [key, value] of Object.entries(env)) {
    if (value === undefined) delete process.env[key];
    else process.env[key] = value;
  }
  vi.resetModules();
  return import("./access");
}

beforeEach(() => {
  mocks.requestHeaders.clear();
  // The module warns on every denial on purpose (it is how an operator finds the cause in the
  // container log). Silencing it here keeps the suite output readable without removing the calls.
  vi.spyOn(console, "warn").mockImplementation(() => {});
});

afterEach(() => {
  process.env = { ...ORIGINAL_ENV };
  vi.restoreAllMocks();
  mocks.jwtVerify.mockReset();
  mocks.createRemoteJWKSet.mockClear();
  mocks.createRemoteJWKSet.mockReturnValue("jwks-handle");
});

describe("mock mode is opt-in", () => {
  it("stands aside only for the exact string 'true'", async () => {
    const { checkAccess } = await loadAccess({ NORA_ADMIN_USE_MOCKS: "true", ...CONFIGURED });
    await expect(checkAccess()).resolves.toEqual({ enforced: false, ok: true });
    // Nothing was even prepared for verification — the JWKS is not fetched in mock mode.
    expect(mocks.createRemoteJWKSet).not.toHaveBeenCalled();
  });

  // The 2026-08-16 inversion in one table. Each of these used to disable the gate under the old
  // `!== "false"` reading, and every one of them has to enforce now.
  const NOT_MOCK_MODE: Array<[string, string | undefined]> = [
    ["unset", undefined],
    ["empty", ""],
    ["false", "false"],
    ["TRUE (wrong case)", "TRUE"],
    ["1", "1"],
    ["yes", "yes"],
    [" true (padded)", " true"],
  ];

  it.each(NOT_MOCK_MODE)("enforces when NORA_ADMIN_USE_MOCKS is %s", async (_label, value) => {
    const { checkAccess } = await loadAccess({ NORA_ADMIN_USE_MOCKS: value, ...CONFIGURED });
    const result = await checkAccess();
    expect(result.enforced).toBe(true);
    // No assertion on the request, so enforcing must mean denying.
    expect(result.ok).toBe(false);
  });
});

describe("incomplete Cloudflare Access configuration", () => {
  // Half a configuration validates nothing useful: an issuer with no audience accepts a JWT minted
  // for a different application in the same organisation, which is why "either both or neither".
  const HALF_CONFIGURED: Array<[string, Record<string, string | undefined>]> = [
    ["neither variable", { CF_ACCESS_TEAM_DOMAIN: undefined, CF_ACCESS_AUD: undefined }],
    ["only the team domain", { ...CONFIGURED, CF_ACCESS_AUD: undefined }],
    ["only the audience", { ...CONFIGURED, CF_ACCESS_TEAM_DOMAIN: undefined }],
    ["empty audience", { ...CONFIGURED, CF_ACCESS_AUD: "" }],
  ];

  it.each(HALF_CONFIGURED)("blocks with reason 'unconfigured' given %s", async (_label, env) => {
    const { checkAccess } = await loadAccess({ NORA_ADMIN_USE_MOCKS: "false", ...env });
    await expect(checkAccess()).resolves.toEqual({
      enforced: true,
      ok: false,
      reason: "unconfigured",
    });
    expect(mocks.createRemoteJWKSet).not.toHaveBeenCalled();
  });

  it("blocks even when the request carries an assertion", async () => {
    mocks.requestHeaders.set("cf-access-jwt-assertion", "a.b.c");
    const { checkAccess } = await loadAccess({
      NORA_ADMIN_USE_MOCKS: "false",
      CF_ACCESS_TEAM_DOMAIN: undefined,
      CF_ACCESS_AUD: undefined,
    });
    const result = await checkAccess();
    expect(result.ok).toBe(false);
    // The assertion is never even looked at: there are no keys to check it against.
    expect(mocks.jwtVerify).not.toHaveBeenCalled();
  });
});

describe("JWT validation", () => {
  it("denies with 'no-assertion' when the header is absent or empty", async () => {
    const { checkAccess } = await loadAccess({ NORA_ADMIN_USE_MOCKS: "false", ...CONFIGURED });
    await expect(checkAccess()).resolves.toEqual({
      enforced: true,
      ok: false,
      reason: "no-assertion",
    });

    mocks.requestHeaders.set("cf-access-jwt-assertion", "");
    const empty = await checkAccess();
    expect(empty.reason).toBe("no-assertion");
    expect(mocks.jwtVerify).not.toHaveBeenCalled();
  });

  it("fetches the keys from the team's Access certificate endpoint", async () => {
    await loadAccess({ NORA_ADMIN_USE_MOCKS: "false", ...CONFIGURED });
    expect(mocks.createRemoteJWKSet).toHaveBeenCalledTimes(1);
    const url = mocks.createRemoteJWKSet.mock.calls[0][0];
    expect(url.toString()).toBe("https://nora.cloudflareaccess.com/cdn-cgi/access/certs");
  });

  it("verifies against BOTH the issuer and the audience", async () => {
    mocks.requestHeaders.set("cf-access-jwt-assertion", "a.b.c");
    mocks.jwtVerify.mockResolvedValue({ payload: { email: "operador@nora.systems" } });
    const { checkAccess } = await loadAccess({ NORA_ADMIN_USE_MOCKS: "false", ...CONFIGURED });

    await expect(checkAccess()).resolves.toEqual({
      enforced: true,
      ok: true,
      email: "operador@nora.systems",
    });
    // The audience is the assertion that matters: dropping it accepts a token minted for any other
    // application in the same Cloudflare organisation.
    expect(mocks.jwtVerify).toHaveBeenCalledWith("a.b.c", "jwks-handle", {
      issuer: "https://nora.cloudflareaccess.com",
      audience: "aud-of-this-application",
    });
  });

  it("denies with 'invalid-assertion' when verification throws", async () => {
    mocks.requestHeaders.set("cf-access-jwt-assertion", "a.b.c");
    mocks.jwtVerify.mockRejectedValue(new Error('unexpected "aud" claim value'));
    const { checkAccess } = await loadAccess({ NORA_ADMIN_USE_MOCKS: "false", ...CONFIGURED });

    await expect(checkAccess()).resolves.toEqual({
      enforced: true,
      ok: false,
      reason: "invalid-assertion",
    });
  });

  it("admits a verified token with no usable e-mail claim, without inventing one", async () => {
    // A valid assertion authorises the render; the missing claim only costs the audit identity, and
    // the caller falls back to the display operator. Fabricating an e-mail here would put a made-up
    // name on somebody's audit trail.
    mocks.requestHeaders.set("cf-access-jwt-assertion", "a.b.c");
    mocks.jwtVerify.mockResolvedValue({ payload: { email: 12345 } });
    const { checkAccess } = await loadAccess({ NORA_ADMIN_USE_MOCKS: "false", ...CONFIGURED });

    await expect(checkAccess()).resolves.toEqual({ enforced: true, ok: true, email: undefined });
  });
});

describe("requireAccess (the gate for server actions)", () => {
  it("throws AccessDeniedError, so the mutation never runs", async () => {
    const { requireAccess, AccessDeniedError } = await loadAccess({
      NORA_ADMIN_USE_MOCKS: "false",
      ...CONFIGURED,
    });
    await expect(requireAccess()).rejects.toBeInstanceOf(AccessDeniedError);
  });

  it("carries a different sentence for a missing configuration than for a missing assertion", async () => {
    const { requireAccess } = await loadAccess({
      NORA_ADMIN_USE_MOCKS: "false",
      CF_ACCESS_TEAM_DOMAIN: undefined,
      CF_ACCESS_AUD: undefined,
    });
    await expect(requireAccess()).rejects.toThrow(/CF_ACCESS_TEAM_DOMAIN/);

    const configured = await loadAccess({ NORA_ADMIN_USE_MOCKS: "false", ...CONFIGURED });
    await expect(configured.requireAccess()).rejects.toThrow(/sem asserção válida/);
  });

  it("returns the verified e-mail, not the unsigned header", async () => {
    // The header is what `getOperator()` reads for display and is forgeable by whoever reaches the
    // origin; only the JWT claim may stamp an audit record.
    mocks.requestHeaders.set("cf-access-jwt-assertion", "a.b.c");
    mocks.requestHeaders.set("cf-access-authenticated-user-email", "forjado@invasor.test");
    mocks.jwtVerify.mockResolvedValue({ payload: { email: "operador@nora.systems" } });
    const { requireAccess } = await loadAccess({ NORA_ADMIN_USE_MOCKS: "false", ...CONFIGURED });

    await expect(requireAccess()).resolves.toBe("operador@nora.systems");
  });

  it("resolves to undefined in mock mode instead of blocking local work", async () => {
    const { requireAccess } = await loadAccess({ NORA_ADMIN_USE_MOCKS: "true", ...CONFIGURED });
    await expect(requireAccess()).resolves.toBeUndefined();
  });
});

describe("guardPage (the gate for pages)", () => {
  it("returns the denial instead of throwing, so the page can render the readable 403", async () => {
    // This is the whole reason the function exists: a thrown message is redacted by Next before it
    // reaches the browser in production, and the reason is exactly what the 403 screen branches on.
    const { guardPage } = await loadAccess({
      NORA_ADMIN_USE_MOCKS: "false",
      CF_ACCESS_TEAM_DOMAIN: undefined,
      CF_ACCESS_AUD: undefined,
    });
    await expect(guardPage()).resolves.toEqual({ ok: false, reason: "unconfigured" });
  });

  it("distinguishes a missing assertion from an invalid one", async () => {
    const denied = await loadAccess({ NORA_ADMIN_USE_MOCKS: "false", ...CONFIGURED });
    await expect(denied.guardPage()).resolves.toEqual({ ok: false, reason: "no-assertion" });

    mocks.requestHeaders.set("cf-access-jwt-assertion", "a.b.c");
    mocks.jwtVerify.mockRejectedValue(new Error("expired"));
    const invalid = await loadAccess({ NORA_ADMIN_USE_MOCKS: "false", ...CONFIGURED });
    await expect(invalid.guardPage()).resolves.toEqual({ ok: false, reason: "invalid-assertion" });
  });

  it("lets a verified request through with its e-mail", async () => {
    mocks.requestHeaders.set("cf-access-jwt-assertion", "a.b.c");
    mocks.jwtVerify.mockResolvedValue({ payload: { email: "operador@nora.systems" } });
    const { guardPage } = await loadAccess({ NORA_ADMIN_USE_MOCKS: "false", ...CONFIGURED });
    await expect(guardPage()).resolves.toEqual({ ok: true, email: "operador@nora.systems" });
  });

  it("never denies in mock mode", async () => {
    const { guardPage } = await loadAccess({ NORA_ADMIN_USE_MOCKS: "true", ...CONFIGURED });
    await expect(guardPage()).resolves.toEqual({ ok: true, email: undefined });
  });
});
