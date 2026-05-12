/**
 * Helpers de auth do client web (Round 2 / Subfase 1.3 A).
 *
 * Modelo:
 * - Tokens reais (access JWT, refresh opaque) ficam em cookies httpOnly
 *   `nora_access` (Path=/, SameSite=Lax) e `nora_refresh` (Path=/auth,
 *   SameSite=Strict), setados pelo backend via Set-Cookie no /auth/login,
 *   /auth/refresh e limpos no /auth/logout.
 * - JavaScript nao tem acesso a esses cookies (XSS hardened). O middleware
 *   do Next consegue le-los porque roda server-side.
 * - Este modulo so cuida do **estado client-visivel**: nome/display do
 *   usuario logado (cookie `nora_user` legivel), agendamento do refresh
 *   proativo e fluxo de logout.
 */

const USER_COOKIE = 'nora_user';

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';

/** Fracao do TTL onde disparamos o refresh proativo (80%). */
const REFRESH_THRESHOLD = 0.8;
/** Piso de seguranca: nunca agenda menos que 15s. */
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
 * Salva info display-only do usuario num cookie legivel pelo JS e agenda o
 * refresh proativo. Os tokens reais ja foram setados como cookies httpOnly
 * pelo backend no Set-Cookie do /auth/login — frontend nao precisa
 * (e nao consegue) armazenar nada disso.
 *
 * O cookie de user info usa Max-Age longo o suficiente pra sobreviver alem
 * do TTL do access (refresh renova) — alinhamos com o refresh TTL nominal
 * (30 dias). Logout limpa explicitamente.
 */
export function setSession(user: SessionUser, expiresInSeconds: number) {
  // Sobrevida do user-info: alinhada com refresh TTL (ate 30 dias). Em caso
  // de logout server-side, refresh falha e clearSession limpa local.
  const userInfoMaxAge = 60 * 60 * 24 * 30;
  setCookie(USER_COOKIE, JSON.stringify(user), userInfoMaxAge);
  scheduleRefresh(expiresInSeconds);
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
 * Limpa sessao no client + chama backend pra revogar o refresh em DB.
 * Idempotente: se o logout falhar (rede off, refresh ja revogado etc),
 * ainda limpa o estado local — usuario tem que conseguir sair.
 */
export async function clearSession(): Promise<void> {
  cancelScheduledRefresh();
  clearCookie(USER_COOKIE);
  try {
    await fetch(`${API_BASE_URL}/auth/logout`, {
      method: 'POST',
      credentials: 'include',
      cache: 'no-store',
    });
  } catch {
    // Sem rede / backend down — segue mesmo assim. O server side state pode
    // ficar "sujo" temporariamente, mas o refresh expira no TTL longo.
  }
}

/**
 * Agenda um refresh proativo a 80% do TTL do access. Cancela qualquer
 * timer anterior antes de agendar novo. Em ambientes server-side
 * (SSR/middleware) nao faz nada.
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

/**
 * Chama POST /auth/refresh. Se sucesso, reagenda; se 401, limpa sessao e
 * redireciona para /auth/login preservando rota atual em `?next=`.
 *
 * Retorna o `expiresInSeconds` do novo access em caso de sucesso, ou
 * `null` em caso de falha (caller pode decidir comportamento).
 */
export async function runProactiveRefresh(): Promise<number | null> {
  if (typeof window === 'undefined') return null;
  try {
    const resp = await fetch(`${API_BASE_URL}/auth/refresh`, {
      method: 'POST',
      credentials: 'include',
      cache: 'no-store',
    });
    if (!resp.ok) {
      if (resp.status === 401) {
        // Refresh invalido: forca logout local + redirect.
        await handleSessionExpired();
      }
      return null;
    }
    const data = (await resp.json()) as { expiresInSeconds?: number };
    const next = typeof data.expiresInSeconds === 'number' ? data.expiresInSeconds : 0;
    if (next > 0) scheduleRefresh(next);
    return next || null;
  } catch {
    return null;
  }
}

/**
 * Limpa o estado local e redireciona para /auth/login preservando a rota
 * que o usuario estava tentando ver (para a UX nao perde-lo no fluxo).
 */
export async function handleSessionExpired(): Promise<void> {
  cancelScheduledRefresh();
  clearCookie(USER_COOKIE);
  if (typeof window === 'undefined') return;
  const current = window.location.pathname + window.location.search;
  const next = encodeURIComponent(current);
  // Evita loop se ja estamos em /auth/login.
  if (window.location.pathname.startsWith('/auth/login')) return;
  window.location.href = `/auth/login?next=${next}`;
}
