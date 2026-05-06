import { fetch as tauriFetch } from "@tauri-apps/plugin-http";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";

type HttpMethod = "GET" | "POST" | "PUT" | "DELETE";

interface RequestOptions {
  method?: HttpMethod;
  body?: unknown;
  headers?: Record<string, string>;
  auth?: boolean;
}

class ApiClient {
  private baseUrl: string;

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl;
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

    const response = await tauriFetch(`${this.baseUrl}${path}`, {
      method,
      headers: allHeaders,
      body: body ? JSON.stringify(body) : undefined,
    });

    if (!response.ok) {
      const error = await response.json().catch(() => ({
        code: "UNKNOWN",
        message: `HTTP ${response.status}`,
        traceId: "",
        timestamp: new Date().toISOString(),
      }));
      throw error;
    }

    if (response.status === 204) return undefined as T;
    return response.json();
  }

  async uploadMultipart<T>(
    path: string,
    fields: Record<string, string | Blob>,
    fileField: { name: string; blob: Blob; fileName: string },
  ): Promise<T> {
    const formData = new FormData();

    for (const [key, value] of Object.entries(fields)) {
      if (value instanceof Blob) {
        formData.append(key, value);
      } else {
        formData.append(key, value);
      }
    }
    formData.append(fileField.name, fileField.blob, fileField.fileName);

    const token = this.getStoredToken();
    const headers: Record<string, string> = {};
    if (token) {
      headers["Authorization"] = `Bearer ${token}`;
    }

    const response = await tauriFetch(`${this.baseUrl}${path}`, {
      method: "POST",
      headers,
      body: formData,
    });

    if (!response.ok) {
      const error = await response.json().catch(() => ({
        code: "UNKNOWN",
        message: `HTTP ${response.status}`,
        traceId: "",
        timestamp: new Date().toISOString(),
      }));
      throw error;
    }

    return response.json();
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
