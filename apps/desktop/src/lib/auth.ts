import { apiClient } from "./api-client";
import { secrets } from "./secrets";
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

  await secrets.set("access-token", response.accessToken);
  await secrets.set("current-user", JSON.stringify(user));
  apiClient.setCachedUser(user);

  return user;
}

export async function logout(): Promise<void> {
  await secrets.delete("access-token");
  await secrets.delete("current-user");
  apiClient.setCachedUser(null);
}

export async function bootstrapSession(): Promise<SessionUser | null> {
  const token = await secrets.get("access-token");
  if (!token) return null;

  if (isTokenExpired(token)) {
    await secrets.delete("access-token");
    await secrets.delete("current-user");
    apiClient.setCachedUser(null);
    return null;
  }

  const userJson = await secrets.get("current-user");
  if (!userJson) return null;

  let user: SessionUser;
  try {
    user = JSON.parse(userJson) as SessionUser;
  } catch {
    // Corrupted store: clear and force re-login.
    await secrets.delete("access-token");
    await secrets.delete("current-user");
    return null;
  }

  apiClient.setCachedUser(user);
  return user;
}

export async function isAuthenticated(): Promise<boolean> {
  const token = await secrets.get("access-token");
  if (!token) return false;
  if (isTokenExpired(token)) {
    await secrets.delete("access-token");
    await secrets.delete("current-user");
    apiClient.setCachedUser(null);
    return false;
  }
  return true;
}
