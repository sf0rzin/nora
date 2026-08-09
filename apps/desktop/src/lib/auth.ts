import { apiClient } from "./api-client";
import { secrets, SECRET_KEYS } from "./secrets";
import type {
  LoginRequest,
  LoginResponse,
  RefreshResponse,
  SessionUser,
} from "./types";

const JWT_REFRESH_MARGIN_MS = 10 * 60 * 1000; // 10 minutes
const JWT_REFRESH_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes

let refreshIntervalId: ReturnType<typeof setInterval> | null = null;

function parseJwtPayload(token: string): Record<string, unknown> | null {
  try {
    return JSON.parse(atob(token.split(".")[1]));
  } catch {
    return null;
  }
}

function parseJwtRoles(token: string): string[] {
  const payload = parseJwtPayload(token);
  const roles = payload?.roles;
  return Array.isArray(roles) ? roles.filter((r): r is string => typeof r === "string") : [];
}

function getTokenExpirationMs(token: string): number | null {
  const payload = parseJwtPayload(token);
  if (!payload || typeof payload.exp !== "number") return null;
  return payload.exp * 1000;
}

function isTokenExpired(token: string): boolean {
  const exp = getTokenExpirationMs(token);
  if (!exp) return true;
  return exp < Date.now();
}

function shouldRefresh(token: string): boolean {
  const exp = getTokenExpirationMs(token);
  if (!exp) return true;
  return exp - Date.now() < JWT_REFRESH_MARGIN_MS;
}

export async function login(req: LoginRequest): Promise<SessionUser> {
  const response = await apiClient.request<LoginResponse>("/auth/login", {
    method: "POST",
    body: req,
    auth: false,
  });

  // Defense: backend down or unexpected response (2xx with no JSON body) made
  // login blow up with "null is not an object". Clear message instead of a crash.
  if (!response?.accessToken) {
    throw new Error(
      "Resposta de login inválida do servidor. Verifique se o backend está no ar.",
    );
  }

  const roles = parseJwtRoles(response.accessToken);

  const user: SessionUser = {
    id: response.userId,
    email: response.email,
    displayName: response.displayName,
    tenantId: response.tenantId,
    roles,
  };

  await secrets.set(SECRET_KEYS.ACCESS_TOKEN, response.accessToken);
  await secrets.set(SECRET_KEYS.REFRESH_TOKEN, response.refreshToken);
  await secrets.set(SECRET_KEYS.CURRENT_USER, JSON.stringify(user));

  startTokenRefreshLoop();

  return user;
}

export async function logout(): Promise<void> {
  stopTokenRefreshLoop();
  await secrets.delete(SECRET_KEYS.ACCESS_TOKEN);
  await secrets.delete(SECRET_KEYS.REFRESH_TOKEN);
  await secrets.delete(SECRET_KEYS.CURRENT_USER);
}

// Single mutex for the refresh — both the proactive loop (checkAndRefresh every 5min)
// and the reactive path (api-client on 401) call refreshAccessToken().
// Without this mutex, both can fire /auth/refresh in parallel with the same
// plain refresh token → backend detects reuse and revokes the session.
let inFlightRefresh: Promise<string | null> | null = null;

export function refreshAccessToken(): Promise<string | null> {
  if (inFlightRefresh) return inFlightRefresh;
  inFlightRefresh = (async (): Promise<string | null> => {
    const refresh = await secrets.get(SECRET_KEYS.REFRESH_TOKEN);
    if (!refresh) {
      console.warn("[auth] no refresh token available");
      return null;
    }

    try {
      const response = await apiClient.request<RefreshResponse>("/auth/refresh", {
        method: "POST",
        auth: false,
        headers: {
          Authorization: `Bearer ${refresh}`,
        },
      });

      // Defense: if the backend responds in the old format (no tokens in the body),
      // abort before writing `undefined` into the keyring.
      if (!response?.accessToken) {
        console.error(
          "[auth] refresh response missing accessToken — backend likely on older RefreshResponse shape",
        );
        return null;
      }

      await secrets.set(SECRET_KEYS.ACCESS_TOKEN, response.accessToken);
      if (response.refreshToken) {
        await secrets.set(SECRET_KEYS.REFRESH_TOKEN, response.refreshToken);
      }

      console.log("[auth] token refreshed successfully");
      return response.accessToken;
    } catch (err) {
      console.error("[auth] failed to refresh token:", err);
      return null;
    }
  })();
  // ALWAYS clear the slot — success, failure or exception — to avoid a perpetual lock.
  inFlightRefresh.finally(() => {
    inFlightRefresh = null;
  });
  return inFlightRefresh;
}

export function startTokenRefreshLoop(): void {
  stopTokenRefreshLoop();

  // Immediate check
  void checkAndRefresh();

  refreshIntervalId = setInterval(() => {
    void checkAndRefresh();
  }, JWT_REFRESH_INTERVAL_MS);
}

export function stopTokenRefreshLoop(): void {
  if (refreshIntervalId) {
    clearInterval(refreshIntervalId);
    refreshIntervalId = null;
  }
}

async function checkAndRefresh(): Promise<void> {
  const token = await secrets.get(SECRET_KEYS.ACCESS_TOKEN);
  if (!token) return;

  if (shouldRefresh(token)) {
    const newToken = await refreshAccessToken();
    if (!newToken) {
      // Refresh failed — token expired or revoked
      await handleAuthExpired();
    }
  }
}

async function handleAuthExpired(): Promise<void> {
  stopTokenRefreshLoop();
  await secrets.delete(SECRET_KEYS.ACCESS_TOKEN);
  await secrets.delete(SECRET_KEYS.REFRESH_TOKEN);
  await secrets.delete(SECRET_KEYS.CURRENT_USER);
  window.dispatchEvent(new CustomEvent("auth-expired"));
}

export async function bootstrapSession(): Promise<SessionUser | null> {
  const token = await secrets.get(SECRET_KEYS.ACCESS_TOKEN);
  if (!token) return null;

  if (isTokenExpired(token)) {
    // Try a refresh before giving up
    const newToken = await refreshAccessToken();
    if (!newToken) {
      await logout();
      return null;
    }
  }

  const userJson = await secrets.get(SECRET_KEYS.CURRENT_USER);
  if (!userJson) return null;

  let user: SessionUser;
  try {
    user = JSON.parse(userJson) as SessionUser;
  } catch {
    await logout();
    return null;
  }

  startTokenRefreshLoop();
  return user;
}
