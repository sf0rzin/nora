import { invoke } from "@tauri-apps/api/core";

/**
 * Chaves de secret no keyring. Os VALORES são contrato com ALLOWED_KEYS em
 * secrets.rs (devem casar byte-a-byte) — centralizado aqui pra não espalhar
 * literais soltos por auth.ts/settings.tsx. Auditoria desktop #109.
 */
export const SECRET_KEYS = {
  ACCESS_TOKEN: "access-token",
  REFRESH_TOKEN: "refresh-token",
  CURRENT_USER: "current-user",
  /** Legacy: usados só pela migração de versões antigas (ver settings.tsx). */
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
