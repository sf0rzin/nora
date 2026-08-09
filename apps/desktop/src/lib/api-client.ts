import { invoke } from "@tauri-apps/api/core";

import { refreshAccessToken, logout } from "./auth";

type HttpMethod = "GET" | "POST" | "PUT" | "DELETE";

interface ProxyResponse {
  status: number;
  body: unknown;
}

interface RequestOptions {
  method?: HttpMethod;
  body?: unknown;
  headers?: Record<string, string>;
  auth?: boolean;
  /**
   * How many times this request already tried refresh+retry after a 401.
   * Capped at 1 (1 refresh per request) to prevent infinite loops if
   * the new token also comes back 401 (revoked token, odd IAM rule etc).
   * Don't use directly — the api-client handles it.
   */
  _retryDepth?: number;
}

class ApiClient {
  private onUnauthorized: (() => void) | null = null;

  on401(callback: () => void) {
    this.onUnauthorized = callback;
  }

  async request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    const { method = "GET", body, headers = {}, auth = true, _retryDepth = 0 } = options;

    const allHeaders: Record<string, string> = {
      "Content-Type": "application/json",
      ...headers,
    };

    const payload = {
      path,
      method,
      headers: allHeaders,
      body: body ?? null,
      auth,
    };

    if (import.meta.env.DEV) console.log("[api] invoking http_proxy:", method, path);

    let response: ProxyResponse;
    try {
      response = await invoke<ProxyResponse>("http_proxy", { req: payload });
    } catch (err) {
      console.error("[api] invoke failed:", err);
      throw err;
    }

    // body only in dev (Vite strips it in prod) — no token leak in the bundle. #98
    if (import.meta.env.DEV) console.log("[api] response:", response.status, response.body);

    if (response.status === 401 && auth && _retryDepth === 0) {
      // A single refresh+retry attempt. refreshAccessToken() is idempotent
      // (internal mutex), so multiple requests 401-ing in parallel coalesce
      // into a single refresh.
      const newToken = await refreshAccessToken();
      if (newToken) {
        return this.request<T>(path, { ...options, _retryDepth: 1 });
      }

      console.warn("[api] 401 — refresh falhou ou indisponível, encerrando sessão");
      await logout();
      this.onUnauthorized?.();
      throw new Error("Sessão expirada. Faça login novamente.");
    }

    if (response.status === 401 && auth && _retryDepth > 0) {
      // We already tried once. Don't loop — kill the session.
      console.warn("[api] 401 após refresh — token novo também recusado");
      await logout();
      this.onUnauthorized?.();
      throw new Error("Sessão expirada. Faça login novamente.");
    }

    if (response.status >= 400) {
      throw response.body || { message: `HTTP ${response.status}` };
    }

    return response.body as T;
  }
}

export const apiClient = new ApiClient();
