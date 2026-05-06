import { apiClient } from "./api-client";
import type { LoginRequest, LoginResponse, SessionUser } from "./types";

export async function login(req: LoginRequest): Promise<LoginResponse> {
  const response = await apiClient.request<LoginResponse>("/auth/login", {
    method: "POST",
    body: req,
    auth: false,
  });

  apiClient.setStoredToken(response.accessToken);
  apiClient.setStoredUser(response.user);

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
