/**
 * Helpers de auth para o cliente web. Token e armazenado em cookie nao-httpOnly
 * (`nora_token`) para que tanto o middleware quanto o browser possam le-lo.
 *
 * Esta abordagem e suficiente para o MVP/dev. Em producao seria substituida por
 * sessao httpOnly emitida por route handler do Next (proximo incremento).
 */

const COOKIE_NAME = "nora_token";
const USER_COOKIE = "nora_user";

export interface SessionUser {
  userId: string;
  tenantId: string;
  email: string;
  displayName: string;
}

function setCookie(name: string, value: string, maxAgeSeconds: number) {
  if (typeof document === "undefined") return;
  const secure = window.location.protocol === "https:" ? "; Secure" : "";
  document.cookie = `${name}=${encodeURIComponent(value)}; Path=/; Max-Age=${maxAgeSeconds}; SameSite=Lax${secure}`;
}

function readCookie(name: string): string | null {
  if (typeof document === "undefined") return null;
  const match = document.cookie
    .split("; ")
    .find((row) => row.startsWith(`${name}=`));
  if (!match) return null;
  return decodeURIComponent(match.substring(name.length + 1));
}

function clearCookie(name: string) {
  if (typeof document === "undefined") return;
  document.cookie = `${name}=; Path=/; Max-Age=0; SameSite=Lax`;
}

export function setSession(token: string, user: SessionUser, expiresInSeconds: number) {
  setCookie(COOKIE_NAME, token, expiresInSeconds);
  setCookie(USER_COOKIE, JSON.stringify(user), expiresInSeconds);
}

export function getToken(): string | null {
  return readCookie(COOKIE_NAME);
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

export function clearSession() {
  clearCookie(COOKIE_NAME);
  clearCookie(USER_COOKIE);
}
