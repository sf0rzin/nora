import { apiClient } from "./api-client";
import type { LoginRequest, LoginResponse, SessionUser } from "./types";

function parseJwtRoles(token: string): string[] {
  try {
    const payload = JSON.parse(atob(token.split(".")[1]));
    return payload.roles || [];
  } catch {
    return [];
  }
}

export async function login(req: LoginRequest): Promise<LoginResponse> {
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

  return response;
}

export function logout(): void {
  apiClient.clearStoredToken();
}

export function getCurrentUser(): SessionUser | null {
  return apiClient.getStoredUser<SessionUser>();
}

export function isAuthenticated(): boolean {
  return !!apiClient.getStoredToken();
}
