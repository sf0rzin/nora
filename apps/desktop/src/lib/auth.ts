import { apiClient } from "./api-client";
import { secrets, SECRET_KEYS } from "./secrets";
import type { RefreshResponse } from "./types";

/**
 * What is left of the desktop's own session handling once the local UI was
 * deleted: the two functions `api-client.ts` calls when the proxy returns 401.
 *
 * The login form, the bootstrap-on-start path and the proactive refresh interval
 * went with the pages that were their only callers — the overlay and the dock
 * never started any of them.
 *
 * A consequence worth stating, and one that predates this file shrinking:
 * `http_proxy.rs` signs requests with the `access-token` secret, and the deleted
 * login form was the only thing that ever wrote that secret. The surviving
 * surfaces authenticate through `auth_bridge::web_session_jwt`, which reads the
 * session cookie out of the remote main window instead.
 */

export async function logout(): Promise<void> {
  await secrets.delete(SECRET_KEYS.ACCESS_TOKEN);
  await secrets.delete(SECRET_KEYS.REFRESH_TOKEN);
  await secrets.delete(SECRET_KEYS.CURRENT_USER);
}

// Single mutex for the refresh — several requests can 401 at the same time.
// Without this mutex, each would fire /auth/refresh in parallel with the same
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
