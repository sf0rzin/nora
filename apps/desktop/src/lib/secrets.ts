import { invoke } from "@tauri-apps/api/core";

export const secrets = {
  set: (key: string, value: string) =>
    invoke<void>("secret_set", { key, value }),

  has: (key: string) =>
    invoke<boolean>("secret_has", { key }),

  delete: (key: string) =>
    invoke<void>("secret_delete", { key }),
};
