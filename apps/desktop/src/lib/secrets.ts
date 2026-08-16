import { invoke } from "@tauri-apps/api/core";

/**
 * Secret keys in the keyring. The VALUES are a contract with ALLOWED_KEYS in
 * secrets.rs (they must match byte-for-byte) — centralized here so loose
 * literals aren't scattered around. Desktop audit #109.
 *
 * The two legacy `azure-*` entries that used to sit here are gone: `ALLOWED_KEYS`
 * on the Rust side stopped accepting them when the Azure Speech path was deleted,
 * so the settings screen's "clean up the old key" migration had already been
 * rejected by the store before that screen itself was deleted.
 */
export const SECRET_KEYS = {
  ACCESS_TOKEN: "access-token",
  REFRESH_TOKEN: "refresh-token",
  CURRENT_USER: "current-user",
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
