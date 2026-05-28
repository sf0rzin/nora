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
   * Quantas vezes essa request já tentou refresh+retry depois de um 401.
   * Capado em 1 (1 refresh por request) pra impedir loops infinitos caso
   * o token novo também volte 401 (token revogado, IAM rule estranha etc).
   * Não use diretamente — o api-client maneja.
   */
  _retryDepth?: number;
}

class ApiClient {
  private onUnauthorized: (() => void) | null = null;
  private cachedUser: unknown = null;

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

    console.log("[api] invoking http_proxy:", method, path);

    let response: ProxyResponse;
    try {
      response = await invoke<ProxyResponse>("http_proxy", { req: payload });
    } catch (err) {
      console.error("[api] invoke failed:", err);
      throw err;
    }

    console.log("[api] response:", response.status, response.body);

    if (response.status === 401 && auth && _retryDepth === 0) {
      // Uma única tentativa de refresh+retry. refreshAccessToken() é idempotente
      // (mutex interno), então múltiplas requests 401-ando em paralelo coalescem
      // num único refresh.
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
      // Já tentamos uma vez. Não entra em loop — derruba sessão.
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

  setCachedUser(user: unknown): void {
    this.cachedUser = user;
  }

  getCachedUser<T>(): T | null {
    return this.cachedUser as T | null;
  }
}

export const apiClient = new ApiClient();
