import { invoke } from "@tauri-apps/api/core";
import { secrets } from "./secrets";

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

    if (response.status === 401) {
      console.warn("[api] 401 unauthorized — token expired");
      await secrets.delete("access-token");
      await secrets.delete("current-user");
      this.cachedUser = null;
      this.onUnauthorized?.();
      window.location.hash = "#/login";
      throw { message: "Sessão expirada. Faça login novamente." };
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

export const apiClient = new ApiClient(API_BASE_URL);
