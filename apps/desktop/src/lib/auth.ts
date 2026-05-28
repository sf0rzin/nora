import { apiClient } from "./api-client";
import { secrets } from "./secrets";
import type {
  LoginRequest,
  LoginResponse,
  RefreshResponse,
  SessionUser,
} from "./types";

const JWT_REFRESH_MARGIN_MS = 10 * 60 * 1000; // 10 minutos
const JWT_REFRESH_INTERVAL_MS = 5 * 60 * 1000; // 5 minutos

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
  return (payload?.roles as string[]) || [];
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

  const roles = parseJwtRoles(response.accessToken);

  const user: SessionUser = {
    id: response.userId,
    email: response.email,
    displayName: response.displayName,
    tenantId: response.tenantId,
    roles,
  };

  await secrets.set("access-token", response.accessToken);
  await secrets.set("refresh-token", response.refreshToken);
  await secrets.set("current-user", JSON.stringify(user));
  apiClient.setCachedUser(user);

  startTokenRefreshLoop();

  return user;
}

export async function logout(): Promise<void> {
  stopTokenRefreshLoop();
  await secrets.delete("access-token");
  await secrets.delete("refresh-token");
  await secrets.delete("current-user");
  apiClient.setCachedUser(null);
}

// Mutex único pro refresh — tanto o loop proativo (checkAndRefresh a cada 5min)
// quanto o caminho reativo (api-client em 401) chamam refreshAccessToken().
// Sem este mutex, ambos podem disparar /auth/refresh em paralelo com o mesmo
// refresh token plain → backend detecta reuse e revoga a sessão.
let inFlightRefresh: Promise<string | null> | null = null;

export function refreshAccessToken(): Promise<string | null> {
  if (inFlightRefresh) return inFlightRefresh;
  inFlightRefresh = (async (): Promise<string | null> => {
    const refresh = await secrets.get("refresh-token");
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

      // Defesa: se backend responder no formato antigo (sem tokens no body),
      // aborta antes de gravar `undefined` no keyring.
      if (!response?.accessToken) {
        console.error(
          "[auth] refresh response missing accessToken — backend likely on older RefreshResponse shape",
        );
        return null;
      }

      await secrets.set("access-token", response.accessToken);
      if (response.refreshToken) {
        await secrets.set("refresh-token", response.refreshToken);
      }

      console.log("[auth] token refreshed successfully");
      return response.accessToken;
    } catch (err) {
      console.error("[auth] failed to refresh token:", err);
      return null;
    }
  })();
  // Limpa o slot SEMPRE — sucesso, falha ou exceção — pra evitar trava perpétua.
  inFlightRefresh.finally(() => {
    inFlightRefresh = null;
  });
  return inFlightRefresh;
}

export function startTokenRefreshLoop(): void {
  stopTokenRefreshLoop();

  // Verificação imediata
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
  const token = await secrets.get("access-token");
  if (!token) return;

  if (shouldRefresh(token)) {
    const newToken = await refreshAccessToken();
    if (!newToken) {
      // Refresh falhou — token expirado ou revogado
      await handleAuthExpired();
    }
  }
}

async function handleAuthExpired(): Promise<void> {
  stopTokenRefreshLoop();
  await secrets.delete("access-token");
  await secrets.delete("refresh-token");
  await secrets.delete("current-user");
  apiClient.setCachedUser(null);
  window.dispatchEvent(new CustomEvent("auth-expired"));
}

export async function bootstrapSession(): Promise<SessionUser | null> {
  const token = await secrets.get("access-token");
  if (!token) return null;

  if (isTokenExpired(token)) {
    // Tenta refresh antes de desistir
    const newToken = await refreshAccessToken();
    if (!newToken) {
      await logout();
      return null;
    }
  }

  const userJson = await secrets.get("current-user");
  if (!userJson) return null;

  let user: SessionUser;
  try {
    user = JSON.parse(userJson) as SessionUser;
  } catch {
    await logout();
    return null;
  }

  apiClient.setCachedUser(user);
  startTokenRefreshLoop();
  return user;
}

export async function isAuthenticated(): Promise<boolean> {
  const token = await secrets.get("access-token");
  if (!token) return false;
  if (isTokenExpired(token)) {
    const newToken = await refreshAccessToken();
    if (!newToken) {
      await logout();
      return false;
    }
  }
  return true;
}
