/**
 * Web client auth helpers (Round 2 / Subphase 1.3 A).
 *
 * Model:
 * - The real tokens (access JWT, opaque refresh) live in httpOnly cookies
 *   `nora_access` (Path=/, SameSite=Lax) and `nora_refresh` (Path=/auth,
 *   SameSite=Strict), set by the backend via Set-Cookie on /auth/login,
 *   /auth/refresh and cleared on /auth/logout.
 * - JavaScript has no access to those cookies (XSS hardened). The Next
 *   middleware can read them because it runs server-side.
 * - This module only handles the **client-visible state**: name/display of the
 *   logged-in user (readable `nora_user` cookie), scheduling of the proactive
 *   refresh and the logout flow.
 */

const USER_COOKIE = 'nora_user';

/**
 * Lifetime of the user-info cookie: aligned with the nominal refresh TTL
 * (30 days). Logout / expired session clear it explicitly.
 */
const USER_COOKIE_MAX_AGE = 60 * 60 * 24 * 30;

/**
 * Event fired on `window` when the `nora_user` cookie changes outside the login
 * flow (e.g. displayName edited in the settings). Whoever renders session data
 * (sidebar/orb) listens to reflect it right away, without a reload.
 */
export const SESSION_USER_EVENT = 'nora:session-user';

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';

/** Fraction of the TTL at which we fire the proactive refresh (80%). */
const REFRESH_THRESHOLD = 0.8;
/** Safety floor: never schedules less than 15s. */
const MIN_REFRESH_DELAY_MS = 15_000;

export interface SessionUser {
  userId: string;
  tenantId: string;
  email: string;
  displayName: string;
}

let refreshTimerId: ReturnType<typeof setTimeout> | null = null;

function setCookie(name: string, value: string, maxAgeSeconds: number) {
  if (typeof document === 'undefined') return;
  const secure = window.location.protocol === 'https:' ? '; Secure' : '';
  document.cookie = `${name}=${encodeURIComponent(value)}; Path=/; Max-Age=${maxAgeSeconds}; SameSite=Lax${secure}`;
}

function readCookie(name: string): string | null {
  if (typeof document === 'undefined') return null;
  const match = document.cookie.split('; ').find((row) => row.startsWith(`${name}=`));
  if (!match) return null;
  return decodeURIComponent(match.substring(name.length + 1));
}

function clearCookie(name: string) {
  if (typeof document === 'undefined') return;
  document.cookie = `${name}=; Path=/; Max-Age=0; SameSite=Lax`;
}

/**
 * Saves display-only user info in a JS-readable cookie and schedules the
 * proactive refresh. The real tokens were already set as httpOnly cookies
 * by the backend in the Set-Cookie of /auth/login — the frontend does not need
 * (and is not able) to store any of that.
 *
 * The user info cookie uses a Max-Age long enough to survive beyond the
 * access TTL (refresh renews it) — we align with the nominal refresh TTL
 * (30 days). Logout clears it explicitly.
 */
export function setSession(user: SessionUser, expiresInSeconds: number) {
  // On a server-side logout, refresh fails and clearSession clears the local state.
  setCookie(USER_COOKIE, JSON.stringify(user), USER_COOKIE_MAX_AGE);
  scheduleRefresh(expiresInSeconds);
}

/**
 * Updates display-only fields of the session (e.g. displayName after editing the
 * profile in /settings) and notifies the listeners via `SESSION_USER_EVENT` —
 * the sidebar reflects it right away, without waiting for a reload. Returns the
 * new state, or `null` if there is no local session.
 */
export function updateSessionUser(patch: Partial<SessionUser>): SessionUser | null {
  const current = getCurrentUser();
  if (!current) return null;
  const next: SessionUser = { ...current, ...patch };
  setCookie(USER_COOKIE, JSON.stringify(next), USER_COOKIE_MAX_AGE);
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new Event(SESSION_USER_EVENT));
  }
  return next;
}

export function getCurrentUser(): SessionUser | null {
  const raw = readCookie(USER_COOKIE);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as SessionUser;
  } catch {
    return null;
  }
}

/**
 * Clears ONLY the local state (refresh timer + `nora_user` cookie), without
 * calling the backend. For flows where the server has ALREADY revoked the sessions
 * and cleared the httpOnly cookies (logout-all, account deletion) — calling
 * /auth/logout again would be redundant.
 */
export function clearLocalSession(): void {
  cancelScheduledRefresh();
  clearCookie(USER_COOKIE);
}

/**
 * Clears the session on the client + calls the backend to revoke the refresh in DB.
 * Idempotent: if the logout fails (network off, refresh already revoked etc),
 * it still clears the local state — the user has to be able to get out.
 */
export async function clearSession(): Promise<void> {
  clearLocalSession();
  try {
    await fetch(`${API_BASE_URL}/auth/logout`, {
      method: 'POST',
      credentials: 'include',
      cache: 'no-store',
    });
  } catch {
    // No network / backend down — carry on anyway. The server side state may
    // stay "dirty" for a while, but the refresh expires on the long TTL.
  }
}

/**
 * Schedules a proactive refresh at 80% of the access TTL. Cancels any
 * previous timer before scheduling a new one. In server-side environments
 * (SSR/middleware) it does nothing.
 */
export function scheduleRefresh(expiresInSeconds: number): void {
  cancelScheduledRefresh();
  if (typeof window === 'undefined') return;
  if (!Number.isFinite(expiresInSeconds) || expiresInSeconds <= 0) return;

  const delayMs = Math.max(
    Math.floor(expiresInSeconds * 1000 * REFRESH_THRESHOLD),
    MIN_REFRESH_DELAY_MS,
  );
  refreshTimerId = setTimeout(() => {
    void runProactiveRefresh();
  }, delayMs);
}

export function cancelScheduledRefresh(): void {
  if (refreshTimerId !== null) {
    clearTimeout(refreshTimerId);
    refreshTimerId = null;
  }
}

/** Result of the shared refresh — each consumer decides what to do. */
export interface SharedRefreshResult {
  /** `true` when the backend answered 2xx. */
  ok: boolean;
  /** HTTP status of the response, or `null` on a network error. */
  status: number | null;
  /** TTL of the new access in seconds (when ok and the backend reported it). */
  expiresInSeconds: number | null;
}

/**
 * Shared promise of the in-flight POST /auth/refresh (single-flight).
 *
 * It lives HERE (and not in client.ts) because both consumers — the proactive
 * timer of this module and the 401 interceptor of `@/lib/api/client` — need to
 * await the SAME call, and client.ts already imports auth.ts (importing the
 * other way would create a cycle).
 */
let refreshInFlight: Promise<SharedRefreshResult> | null = null;

/**
 * Single-flight of POST /auth/refresh. The proactive timer and the 401 interceptor
 * share the same promise: two simultaneous refreshes with the same cookie
 * were treated by the backend as reuse of a rotated token (reuse
 * detection), revoking the whole family → spontaneous logout in production.
 *
 * On success, it already reschedules the next proactive refresh (`scheduleRefresh`).
 */
export function sharedRefresh(): Promise<SharedRefreshResult> {
  if (refreshInFlight) return refreshInFlight;
  refreshInFlight = (async (): Promise<SharedRefreshResult> => {
    try {
      const resp = await fetch(`${API_BASE_URL}/auth/refresh`, {
        method: 'POST',
        credentials: 'include',
        cache: 'no-store',
      });
      if (!resp.ok) {
        return { ok: false, status: resp.status, expiresInSeconds: null };
      }
      const data = (await resp.json().catch(() => ({}))) as { expiresInSeconds?: number };
      const expiresInSeconds =
        typeof data.expiresInSeconds === 'number' && data.expiresInSeconds > 0
          ? data.expiresInSeconds
          : null;
      if (expiresInSeconds !== null) scheduleRefresh(expiresInSeconds);
      return { ok: true, status: resp.status, expiresInSeconds };
    } catch {
      return { ok: false, status: null, expiresInSeconds: null };
    } finally {
      // Clear after completing — the next call creates a new promise if needed.
      refreshInFlight = null;
    }
  })();
  return refreshInFlight;
}

/**
 * Proactive refresh (timer). Uses the shared single-flight; if there is already
 * a refresh in flight (e.g. the 401 interceptor), it just awaits its result.
 * On success, the rescheduling was already done by `sharedRefresh`; on 401,
 * it clears the session and redirects to /auth/login keeping the route in `?next=`.
 *
 * Returns the `expiresInSeconds` of the new access on success, or
 * `null` on failure (the caller can decide the behavior).
 */
export async function runProactiveRefresh(): Promise<number | null> {
  if (typeof window === 'undefined') return null;
  const result = await sharedRefresh();
  if (!result.ok) {
    if (result.status === 401) {
      // Invalid refresh: forces a local logout + redirect.
      await handleSessionExpired();
    }
    return null;
  }
  return result.expiresInSeconds;
}

/**
 * Clears the local state and redirects to /auth/login keeping the route
 * the user was trying to see (so the UX does not lose them in the flow).
 */
export async function handleSessionExpired(): Promise<void> {
  cancelScheduledRefresh();
  clearCookie(USER_COOKIE);
  if (typeof window === 'undefined') return;
  const current = window.location.pathname + window.location.search;
  const next = encodeURIComponent(current);
  // Avoids a loop if we are already on /auth/login.
  if (window.location.pathname.startsWith('/auth/login')) return;
  window.location.href = `/auth/login?next=${next}`;
}
