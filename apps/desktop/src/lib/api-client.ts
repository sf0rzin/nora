import { invoke } from "@tauri-apps/api/core";

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

    if (auth) {
      const token = this.getStoredToken();
      if (token) {
        allHeaders["Authorization"] = `Bearer ${token}`;
      }
    }

    const payload = {
      url: `${this.baseUrl}${path}`,
      method,
      headers: allHeaders,
      body: body ?? null,
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
      this.clearStoredToken();
      this.onUnauthorized?.();
      window.location.hash = "#/login";
      throw { message: "Sessão expirada. Faça login novamente." };
    }

    if (response.status >= 400) {
      throw response.body || { message: `HTTP ${response.status}` };
    }

    return response.body as T;
  }

  getStoredToken(): string | null {
    return localStorage.getItem("nora_access_token");
  }

  setStoredToken(token: string): void {
    localStorage.setItem("nora_access_token", token);
  }

  clearStoredToken(): void {
    localStorage.removeItem("nora_access_token");
    localStorage.removeItem("nora_user");
  }

  setStoredUser(user: unknown): void {
    localStorage.setItem("nora_user", JSON.stringify(user));
  }

  getStoredUser<T>(): T | null {
    const raw = localStorage.getItem("nora_user");
    if (!raw) return null;
    try {
      return JSON.parse(raw) as T;
    } catch {
      return null;
    }
  }
}

export const apiClient = new ApiClient(API_BASE_URL);
