import { describe, expect, it } from "vitest";

import { keyVariableFor, resolveProviderKey } from "./provider-key";

const OPENAI = { defaultProvider: "openai", defaultKey: "sk-openai-account-key" };

describe("resolveProviderKey", () => {
  it("prefers the explicit per-provider key", () => {
    const key = resolveProviderKey("deepseek", {
      ...OPENAI,
      env: { LLM_KEY_DEEPSEEK: "sk-deepseek-key" },
    });
    expect(key).toBe("sk-deepseek-key");
  });

  it("uses the legacy single key for the provider it belongs to", () => {
    expect(resolveProviderKey("openai", { ...OPENAI, env: {} })).toBe("sk-openai-account-key");
    // and case is not a reason to fail to find it
    expect(resolveProviderKey("OpenAI", { ...OPENAI, env: {} })).toBe("sk-openai-account-key");
  });

  it("NEVER hands one provider's key to another", () => {
    // The regression. The control plane bound `chat` to deepseek while this deployment held
    // only an OpenAI key; the unconditional fallback shipped that key to api.deepseek.com on
    // every message. A rejected credential is still a disclosed credential.
    for (const other of ["deepseek", "google", "anthropic", "DEEPSEEK"]) {
      expect(resolveProviderKey(other, { ...OPENAI, env: {} })).toBe("");
    }
  });

  it("treats a blank per-provider key as absent, not as a credential", () => {
    expect(resolveProviderKey("deepseek", { ...OPENAI, env: { LLM_KEY_DEEPSEEK: "   " } })).toBe("");
  });

  it("trims a key that arrived with whitespace from a secret store", () => {
    expect(
      resolveProviderKey("deepseek", { ...OPENAI, env: { LLM_KEY_DEEPSEEK: " sk-ds \n" } }),
    ).toBe("sk-ds");
  });

  it("names the variable an operator has to set", () => {
    expect(keyVariableFor("deepseek")).toBe("LLM_KEY_DEEPSEEK");
    expect(keyVariableFor("openai")).toBe("LLM_KEY_OPENAI");
  });
});
