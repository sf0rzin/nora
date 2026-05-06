import { apiClient } from "./api-client";
import type { LoginRequest, LoginResponse, SessionUser } from "./types";

function parseJwtPayload(token: string): Record<string, unknown> | null {
  try {
    return JSON.parse(atob(token.split(".")[1]));
  } catch {
    return null;
  }
}

function parseJwtRoles(token: string): string[] {
  const payload = parseJwtPayload(token);
  return (payload?.roles as string[]) || [];
}

function isTokenExpired(token: string): boolean {
  const payload = parseJwtPayload(token);
  if (!payload || typeof payload.exp !== "number") return true;
  return payload.exp * 1000 < Date.now();
}

export async function login(req: LoginRequest): Promise<SessionUser> {
  const response = await apiClient.request<LoginResponse>("/auth/login", {
    method: "POST",
    body: req,
    auth: false,
  });

  const roles = parseJwtRoles(response.accessToken);

  const user: SessionUser = {
    id: response.userId,
    email: response.email,
    displayName: response.displayName,
    tenantId: response.tenantId,
    roles,
  };

  apiClient.setStoredToken(response.accessToken);
  apiClient.setStoredUser(user);

  return user;
}

export function logout(): void {
  apiClient.clearStoredToken();
}

export function getCurrentUser(): SessionUser | null {
  return apiClient.getStoredUser<SessionUser>();
}

export function isAuthenticated(): boolean {
  const token = apiClient.getStoredToken();
  if (!token) return false;
  if (isTokenExpired(token)) {
    apiClient.clearStoredToken();
    return false;
  }
  return true;
}
