import { invoke } from "@tauri-apps/api/core";

import { refreshAccessToken, logout } from "./auth";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";

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
}

class ApiClient {
  private baseUrl: string;
  private onUnauthorized: (() => void) | null = null;
  private cachedUser: unknown = null;
  private refreshing = false;
  private refreshQueue: Array<{
    resolve: (value: unknown) => void;
    reject: (reason?: unknown) => void;
    path: string;
    options: RequestOptions;
  }> = [];

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl;
  }

  on401(callback: () => void) {
    this.onUnauthorized = callback;
  }

  async request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    const { method = "GET", body, headers = {}, auth = true } = options;

    const allHeaders: Record<string, string> = {
      "Content-Type": "application/json",
      ...headers,
    };

    const payload = {
      url: `${this.baseUrl}${path}`,
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

    // Se 401 e autenticado, tenta refreshar e retry
    if (response.status === 401 && auth) {
      const retry = await this.attemptRefreshAndRetry<T>(path, options);
      if (retry) {
        return retry;
      }

      // Refresh falhou — desloga
      console.warn("[api] 401 unauthorized — token expired and refresh failed");
      await logout();
      this.onUnauthorized?.();
      throw new Error("Sessão expirada. Faça login novamente.");
    }

    if (response.status >= 400) {
      throw response.body || { message: `HTTP ${response.status}` };
    }

    return response.body as T;
  }

  private async attemptRefreshAndRetry<T>(
    path: string,
    options: RequestOptions
  ): Promise<T | null> {
    // Se já estamos refreshando, espera na fila
    if (this.refreshing) {
      return new Promise<T>((resolve, reject) => {
        this.refreshQueue.push({
          resolve: resolve as (value: unknown) => void,
          reject,
          path,
          options,
        });
      });
    }

    this.refreshing = true;
    try {
      const newToken = await refreshAccessToken();
      if (newToken) {
        // Retry a requisição original com novo token
        const retry = await this.request<T>(path, options);
        // Processa fila com sucesso
        this.drainQueue(true);
        return retry;
      }
    } catch (err) {
      console.error("[api] refresh failed during retry:", err);
    } finally {
      this.refreshing = false;
    }
    // Refresh falhou — rejeita fila
    this.drainQueue(false);
    return null;
  }

  private drainQueue(success: boolean) {
    const queue = this.refreshQueue;
    this.refreshQueue = [];
    for (const item of queue) {
      if (success) {
        this.request(item.path, item.options).then(item.resolve).catch(item.reject);
      } else {
        item.reject(new Error("Sessão expirada. Faça login novamente."));
      }
    }
  }

  setCachedUser(user: unknown): void {
    this.cachedUser = user;
  }

  getCachedUser<T>(): T | null {
    return this.cachedUser as T | null;
  }
}

export const apiClient = new ApiClient(API_BASE_URL);
