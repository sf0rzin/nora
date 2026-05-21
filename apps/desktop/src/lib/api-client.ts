import { invoke } from "@tauri-apps/api/core";

import { refreshAccessToken, logout } from "./auth";

type HttpMethod = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

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
   * Sentinel interno: marca requests que já são o retry pós-refresh.
   * Evita a recursão infinita do design anterior (attemptRefreshAndRetry
   * chamava `this.request(...)` que podia bater 401 de novo, reentrava na
   * fila que só drenava no `finally` do refresh original — deadlock).
   */
  _isRefreshRetry?: boolean;
}

const isDev = import.meta.env?.DEV === true;

class ApiClient {
  private onUnauthorized: (() => void) | null = null;
  private cachedUser: unknown = null;
  /**
   * Promise compartilhada do refresh em voo. Múltiplos requests que recebem
   * 401 ao mesmo tempo esperam na MESMA promise — sem fila manual, sem recursão.
   */
  private refreshPromise: Promise<string | null> | null = null;

  on401(callback: () => void) {
    this.onUnauthorized = callback;
  }

  async request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    const {
      method = "GET",
      body,
      headers = {},
      auth = true,
      _isRefreshRetry = false,
    } = options;

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

    if (isDev) {
      console.log("[api] invoking http_proxy:", method, path);
    }

    let response: ProxyResponse;
    try {
      response = await invoke<ProxyResponse>("http_proxy", { req: payload });
    } catch (err) {
      console.error("[api] invoke failed:", err);
      throw err;
    }

    if (isDev) {
      console.log("[api] response status:", response.status);
    }

    // Se 401 e autenticado, tenta refreshar UMA vez. Se o retry também receber
    // 401 (`_isRefreshRetry`), aceitamos e propagamos — sem recursar.
    if (response.status === 401 && auth && !_isRefreshRetry) {
      const newToken = await this.refreshOnce();
      if (newToken) {
        return this.request<T>(path, { ...options, _isRefreshRetry: true });
      }

      console.warn("[api] 401 — refresh failed; logging out");
      await logout();
      this.onUnauthorized?.();
      throw new Error("Sessão expirada. Faça login novamente.");
    }

    if (response.status >= 400) {
      throw response.body || { message: `HTTP ${response.status}` };
    }

    return response.body as T;
  }

  /**
   * Garante que um único refresh está em voo a qualquer momento. Chamadas
   * concorrentes recebem a MESMA promise — quando ela resolve, todas avançam.
   */
  private refreshOnce(): Promise<string | null> {
    if (!this.refreshPromise) {
      this.refreshPromise = refreshAccessToken()
        .catch((err) => {
          console.error("[api] refresh failed:", err);
          return null;
        })
        .finally(() => {
          this.refreshPromise = null;
        }) as Promise<string | null>;
    }
    return this.refreshPromise;
  }

  setCachedUser(user: unknown): void {
    this.cachedUser = user;
  }

  getCachedUser<T>(): T | null {
    return this.cachedUser as T | null;
  }
}

export const apiClient = new ApiClient();
