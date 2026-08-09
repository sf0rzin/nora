import { invoke } from "@tauri-apps/api/core";

/**
 * Secret keys in the keyring. The VALUES are a contract with ALLOWED_KEYS in
 * secrets.rs (they must match byte-for-byte) — centralized here so loose
 * literals aren't scattered across auth.ts/settings.tsx. Desktop audit #109.
 */
export const SECRET_KEYS = {
  ACCESS_TOKEN: "access-token",
  REFRESH_TOKEN: "refresh-token",
  CURRENT_USER: "current-user",
  /** Legacy: used only by the migration from old versions (see settings.tsx). */
  LEGACY_AZURE_SPEECH_KEY: "azure-speech-key",
  LEGACY_AZURE_REGION: "azure-region",
} as const;

export type SecretKey = (typeof SECRET_KEYS)[keyof typeof SECRET_KEYS];

export const secrets = {
  set: (key: SecretKey, value: string) =>
    invoke<void>("secret_set", { key, value }),

  get: (key: SecretKey) =>
    invoke<string | null>("secret_get", { key }),

  has: (key: SecretKey) =>
    invoke<boolean>("secret_has", { key }),

  delete: (key: SecretKey) =>
    invoke<void>("secret_delete", { key }),
};
