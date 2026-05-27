/**
 * Cliente HTTP minimalista para a API do NORA.
 *
 * Por padrao em modo dev usa fixtures (NEXT_PUBLIC_USE_MOCKS=true). Quando false,
 * faz fetch real contra NEXT_PUBLIC_API_BASE_URL.
 *
 * Round 2 / Subfase 1.3 A:
 * - Auth e enviada via cookies httpOnly (`nora_access`, `nora_refresh`)
 *   setados pelo backend no /auth/login. `credentials: include` no fetch
 *   garante que sao enviados. Frontend nao precisa (nem consegue) ler.
 * - Interceptor 401: tenta `POST /auth/refresh` uma vez; se sucesso, repete
 *   a request original; se falhar, redireciona para /auth/login.
 */

import type {
  AcceptInviteRequest,
  ApiError,
  Invite,
  InviteListResponse,
  InviteStatus,
  InviteUserRequest,
  MeetingDetail,
  MeetingGoal,
  MeetingsListResponse,
} from './types';

// Re-export para componentes consumirem direto de @/lib/api/client (parity
// com GroupDto/PolicyDto etc., que sao declarados localmente neste modulo).
export type { AcceptInviteRequest, Invite, InviteListResponse, InviteStatus, InviteUserRequest };
import meetingsListFixture from '@/fixtures/meetings-list-response.json';
import meetingDetailFixture from '@/fixtures/meeting-detail-response.json';
import { handleSessionExpired, scheduleRefresh } from '@/lib/auth';

// Default deve ser 'false' em prod. Antes era 'true' → se a build esquecesse de
// setar NEXT_PUBLIC_USE_MOCKS, prod servia fixtures hardcoded. Agora qualquer
// build sem setar explicitamente vai contra a API real (que falha rápido se
// estiver mal configurada — preferível a servir lixo).
const USE_MOCKS = process.env.NEXT_PUBLIC_USE_MOCKS === 'true';
const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';

/**
 * Em Server Components / Route Handlers, `fetch` NÃO propaga cookies httpOnly
 * do browser automaticamente. Sem isso, qualquer fetch RSC (dashboard,
 * meeting detail) vai sem auth → 401 → notFound() → 404 visível pro user.
 *
 * Este helper detecta SSR (typeof window === 'undefined') e usa `next/headers`
 * dinamicamente para anexar o header `Cookie`. Em client, retorna {} (browser
 * envia automaticamente com `credentials: 'include'`).
 */
async function serverCookieHeader(): Promise<Record<string, string>> {
  if (typeof window !== 'undefined') return {};
  try {
    // Import dinâmico evita carregar next/headers em client bundles.
    const { cookies } = await import('next/headers');
    const all = cookies().getAll();
    if (all.length === 0) return {};
    const cookieStr = all.map((c) => `${c.name}=${c.value}`).join('; ');
    return { Cookie: cookieStr };
  } catch {
    // Fora de contexto de request (build, scripts) — sem cookies.
    return {};
  }
}

export class ApiRequestError extends Error {
  readonly status: number;
  readonly payload?: ApiError;
  constructor(status: number, message: string, payload?: ApiError) {
    super(message);
    this.status = status;
    this.payload = payload;
  }
}

interface RequestOptions extends RequestInit {
  /**
   * Quando `true`, nao tenta refresh+retry em 401 — usado em endpoints
   * publicos onde 401 e um erro semantico (login com senha errada, refresh
   * invalido etc).
   */
  skipAuth?: boolean;
  /** Marcador interno: evita loop quando a propria call e a tentativa de retry. */
  _isRetry?: boolean;
}

/**
 * Promise compartilhada de refresh em andamento. Quando varias requests
 * batem 401 simultaneamente, todas aguardam o mesmo /auth/refresh em vez
 * de disparar N requests redundantes.
 */
let refreshInFlight: Promise<boolean> | null = null;

async function performRefresh(): Promise<boolean> {
  if (refreshInFlight) return refreshInFlight;
  refreshInFlight = (async () => {
    try {
      const resp = await fetch(`${API_BASE_URL}/auth/refresh`, {
        method: 'POST',
        credentials: 'include',
        cache: 'no-store',
      });
      if (!resp.ok) return false;
      const data = (await resp.json().catch(() => ({}))) as {
        expiresInSeconds?: number;
      };
      if (typeof data.expiresInSeconds === 'number' && data.expiresInSeconds > 0) {
        scheduleRefresh(data.expiresInSeconds);
      }
      return true;
    } catch {
      return false;
    } finally {
      // Limpa apos completar — proxima chamada cria nova promise se precisar.
      refreshInFlight = null;
    }
  })();
  return refreshInFlight;
}

async function request<T>(path: string, init?: RequestOptions): Promise<T> {
  const cookieHeader = await serverCookieHeader();
  const headers: Record<string, string> = {
    Accept: 'application/json',
    ...cookieHeader,
    ...((init?.headers as Record<string, string>) ?? {}),
  };
  if (init?.body && !(init.body instanceof FormData) && !headers['Content-Type']) {
    headers['Content-Type'] = 'application/json';
  }

  const resp = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers,
    credentials: 'include',
    cache: 'no-store',
  });

  // Interceptor 401: 1 tentativa de refresh + retry. Pulado em endpoints
  // publicos (skipAuth) e em retries (evita loop infinito).
  if (resp.status === 401 && !init?.skipAuth && !init?._isRetry) {
    const refreshed = await performRefresh();
    if (refreshed) {
      return request<T>(path, { ...init, _isRetry: true });
    }
    // Refresh tambem falhou: limpa estado e redireciona. handleSessionExpired
    // chama window.location.href entao a Promise abaixo na pratica nunca
    // resolve antes do unload. Mantemos o throw pra fluxo SSR e testes.
    await handleSessionExpired();
  }

  if (!resp.ok) {
    let payload: ApiError | undefined;
    try {
      payload = (await resp.json()) as ApiError;
    } catch {
      // ignore
    }
    throw new ApiRequestError(
      resp.status,
      payload?.message ?? `Request failed: ${resp.status} ${resp.statusText}`,
      payload,
    );
  }
  if (resp.status === 204) return undefined as T;
  return (await resp.json()) as T;
}

// ---------- Meetings ----------

export interface ListMeetingsParams {
  page?: number;
  size?: number;
  search?: string;
  status?: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
  from?: string; // ISO-8601
  to?: string; // ISO-8601
}

export async function listMeetings(params?: ListMeetingsParams): Promise<MeetingsListResponse> {
  if (USE_MOCKS) return meetingsListFixture as unknown as MeetingsListResponse;
  const qs = new URLSearchParams();
  qs.set('page', String(params?.page ?? 0));
  qs.set('size', String(params?.size ?? 20));
  if (params?.search) qs.set('search', params.search);
  if (params?.status) qs.set('status', params.status);
  if (params?.from) qs.set('from', params.from);
  if (params?.to) qs.set('to', params.to);
  return request<MeetingsListResponse>(`/meetings?${qs.toString()}`);
}

export async function getMeeting(id: string): Promise<MeetingDetail> {
  if (USE_MOCKS) return meetingDetailFixture as unknown as MeetingDetail;
  return request<MeetingDetail>(`/meetings/${encodeURIComponent(id)}`);
}

// Productivity Score opt-in (ADR 0005)
export async function setMeetingGoal(
  meetingId: string,
  goal: MeetingGoal,
): Promise<MeetingGoal> {
  return request<MeetingGoal>(`/meetings/${encodeURIComponent(meetingId)}/goal`, {
    method: 'PUT',
    body: JSON.stringify(goal),
  });
}

export async function deleteMeetingGoal(meetingId: string): Promise<void> {
  return request<void>(`/meetings/${encodeURIComponent(meetingId)}/goal`, {
    method: 'DELETE',
  });
}

/** Re-dispara a análise NLP da reunião (POST /meetings/{id}/reprocess). Exige meeting:reprocess. */
export async function reprocessMeeting(id: string): Promise<void> {
  return request<void>(`/meetings/${encodeURIComponent(id)}/reprocess`, { method: 'POST' });
}

export interface UploadMeetingInput {
  title: string;
  language: string;
  transcriptFormat: 'TXT' | 'VTT' | 'SRT';
  startedAt?: string;
  endedAt?: string;
  participants?: { displayName: string; email?: string; isInternal?: boolean }[];
  tags?: string[];
  file: File;
}

export async function uploadMeeting(input: UploadMeetingInput) {
  const fd = new FormData();
  const metadata = {
    title: input.title,
    language: input.language,
    transcriptFormat: input.transcriptFormat,
    startedAt: input.startedAt,
    endedAt: input.endedAt,
    participants: input.participants ?? [],
    tags: input.tags ?? [],
  };
  fd.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }));
  fd.append('file', input.file);
  return request<{ id: string; processingStatus: string }>(`/meetings`, {
    method: 'POST',
    body: fd,
  });
}

// ---------- Auth ----------

export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  userId: string;
  tenantId: string;
  email: string;
  displayName: string;
  isRoot: boolean;
}

export async function login(email: string, password: string) {
  return request<LoginResponse>(`/auth/login`, {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  });
}

export async function signup(input: {
  email: string;
  password: string;
  displayName: string;
  companyName: string;
}) {
  return request<{ userId: string; tenantId: string; verificationRequired: boolean }>(
    `/auth/signup`,
    { method: 'POST', body: JSON.stringify(input) },
  );
}

export async function verifyEmail(token: string) {
  return request<{ verified: boolean }>(`/auth/verify-email`, {
    method: 'POST',
    body: JSON.stringify({ token }),
  });
}

export async function requestPasswordReset(email: string) {
  return request<{ requested: boolean }>(`/auth/password/reset/request`, {
    method: 'POST',
    body: JSON.stringify({ email }),
  });
}

export async function confirmPasswordReset(token: string, newPassword: string) {
  return request<{ reset: boolean }>(`/auth/password/reset/confirm`, {
    method: 'POST',
    body: JSON.stringify({ token, newPassword }),
  });
}

// ---------- Current user (GET /auth/me) ----------

export interface MeResponse {
  userId: string;
  tenantId: string;
  email: string;
  displayName: string;
  isRoot: boolean;
  emailVerified: boolean;
  tenantName: string;
  plan: string;
}

/** Identidade autoritativa do usuario logado. Requer sessao (cookies httpOnly). */
export async function getMe(): Promise<MeResponse> {
  return request<MeResponse>(`/auth/me`);
}

// ---------- Tenant Context ----------

export interface TenantContextDto {
  tenantId: string;
  companyName: string;
  industry?: string;
  valueProposition?: string;
  idealCustomerProfile?: string;
  products: { name: string; description?: string; keyDifferentiators: string[] }[];
  competitors: string[];
  objectionHandling: string[];
  updatedAt: string;
}

export async function getTenantContext() {
  return request<TenantContextDto>(`/tenant/context`);
}

export async function upsertTenantContext(
  payload: Omit<TenantContextDto, 'tenantId' | 'updatedAt'>,
) {
  return request<TenantContextDto>(`/tenant/context`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  });
}

// ---------- Tenant Domain (US32) ----------

/** Estado atual do dominio corporativo do tenant (GET /tenant/domain). */
export interface TenantDomain {
  tenantId: string;
  /** Dominio normalizado (ex: "acme.com"). `null` quando nao ha restricao. */
  allowedEmailDomain: string | null;
}

/** Resposta da atualizacao de dominio (PUT /tenant/domain). Inclui auditoria leve. */
export interface TenantDomainUpdateResponse {
  tenantId: string;
  allowedEmailDomain: string | null;
  updatedAt: string;
  updatedBy: string;
}

/** Payload do PUT /tenant/domain. `null` remove a restricao. */
export interface TenantDomainUpdateRequest {
  allowedEmailDomain: string | null;
}

export async function getTenantDomain(): Promise<TenantDomain> {
  return request<TenantDomain>(`/tenant/domain`);
}

export async function updateTenantDomain(
  req: TenantDomainUpdateRequest,
): Promise<TenantDomainUpdateResponse> {
  return request<TenantDomainUpdateResponse>(`/tenant/domain`, {
    method: 'PUT',
    body: JSON.stringify(req),
  });
}

// ---------- Tenant Metrics (US33) ----------

/** Visao rapida de atividade do tenant (GET /tenant/metrics). Sempre escopado ao tenant atual. */
export interface TenantMetrics {
  totalMeetings: number;
  meetingsThisMonth: number;
  completed: number;
  processing: number;
  pending: number;
  failed: number;
  totalActionItems: number;
  openActionItems: number;
}

export async function getTenantMetrics(): Promise<TenantMetrics> {
  return request<TenantMetrics>('/tenant/metrics');
}

// ---------- Tasks ----------

export type TaskStatus = 'OPEN' | 'IN_PROGRESS' | 'DONE';
export type TaskPriority = 'LOW' | 'MEDIUM' | 'HIGH';

export interface TaskListItemDto {
  id: string;
  title: string;
  assignee?: string;
  dueDate?: string;
  priority: TaskPriority;
  status: TaskStatus;
  meetingId: string;
  meetingTitle: string;
  updatedAt: string;
}

export interface TaskListResponse {
  items: TaskListItemDto[];
}

export async function listTasks(status?: TaskStatus): Promise<TaskListResponse> {
  const qs = new URLSearchParams();
  if (status) qs.set('status', status);
  const path = qs.toString().length > 0 ? `/tasks?${qs.toString()}` : `/tasks`;
  return request<TaskListResponse>(path);
}

export async function updateTask(
  id: string,
  patch: { status?: TaskStatus; title?: string },
): Promise<TaskListItemDto> {
  return request<TaskListItemDto>(`/tasks/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    body: JSON.stringify(patch),
  });
}

// ---------- IAM (AWS-style) ----------

export interface GroupDto {
  id: string;
  name: string;
  description?: string | null;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface PolicyDto {
  id: string;
  name: string;
  description?: string | null;
  document: unknown;
  currentVersion: number;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface AuditEventDto {
  id: string;
  actorUserId: string;
  action: string;
  targetType: string;
  targetId: string;
  payload: Record<string, unknown>;
  createdAt: string;
}

export async function listGroups(): Promise<GroupDto[]> {
  return request<GroupDto[]>(`/iam/groups`);
}

export async function createGroup(name: string, description?: string): Promise<GroupDto> {
  return request<GroupDto>(`/iam/groups`, {
    method: 'POST',
    body: JSON.stringify({ name, description: description ?? null }),
  });
}

export async function deleteGroup(id: string): Promise<void> {
  return request<void>(`/iam/groups/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

export async function listGroupMembers(id: string): Promise<string[]> {
  return request<string[]>(`/iam/groups/${encodeURIComponent(id)}/members`);
}

export async function addGroupMember(groupId: string, userId: string): Promise<void> {
  return request<void>(
    `/iam/groups/${encodeURIComponent(groupId)}/members/${encodeURIComponent(userId)}`,
    { method: 'POST' },
  );
}

export async function removeGroupMember(groupId: string, userId: string): Promise<void> {
  return request<void>(
    `/iam/groups/${encodeURIComponent(groupId)}/members/${encodeURIComponent(userId)}`,
    { method: 'DELETE' },
  );
}

export async function listPolicies(): Promise<PolicyDto[]> {
  return request<PolicyDto[]>(`/iam/policies`);
}

export async function createPolicy(
  name: string,
  document: unknown,
  description?: string,
): Promise<PolicyDto> {
  return request<PolicyDto>(`/iam/policies`, {
    method: 'POST',
    body: JSON.stringify({ name, description: description ?? null, document }),
  });
}

export async function updatePolicyDocument(id: string, document: unknown): Promise<PolicyDto> {
  return request<PolicyDto>(`/iam/policies/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify({ document }),
  });
}

export async function deletePolicy(id: string): Promise<void> {
  return request<void>(`/iam/policies/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

export async function attachPolicyToGroup(policyId: string, groupId: string): Promise<void> {
  return request<void>(
    `/iam/groups/${encodeURIComponent(groupId)}/policies/${encodeURIComponent(policyId)}`,
    { method: 'POST' },
  );
}

export async function detachPolicyFromGroup(policyId: string, groupId: string): Promise<void> {
  return request<void>(
    `/iam/groups/${encodeURIComponent(groupId)}/policies/${encodeURIComponent(policyId)}`,
    { method: 'DELETE' },
  );
}

export async function attachPolicyToUser(policyId: string, userId: string): Promise<void> {
  return request<void>(
    `/iam/users/${encodeURIComponent(userId)}/policies/${encodeURIComponent(policyId)}`,
    { method: 'POST' },
  );
}

export async function detachPolicyFromUser(policyId: string, userId: string): Promise<void> {
  return request<void>(
    `/iam/users/${encodeURIComponent(userId)}/policies/${encodeURIComponent(policyId)}`,
    { method: 'DELETE' },
  );
}

export async function listAuditEvents(limit = 50): Promise<AuditEventDto[]> {
  return request<AuditEventDto[]>(`/iam/audit?limit=${limit}`);
}

export interface TenantUserDto {
  id: string;
  email: string;
  displayName: string;
  isRoot: boolean;
  status: string;
  createdAt: string;
}

/** Lista usuarios do tenant (GET /iam/users). Exige iam:user:read; Root bypassa. */
export async function listIamUsers(): Promise<TenantUserDto[]> {
  return request<TenantUserDto[]>(`/iam/users`);
}

// ---------- IAM Policy Simulator (US43) ----------

export interface SimulateResult {
  userId: string;
  action: string;
  resource: string;
  allowed: boolean;
}

/**
 * Avalia "o usuario X pode executar action Y no resource Z?" sem tentativa-e-erro
 * (POST /iam/policies/simulate). Exige iam:policy:read; sempre dentro do tenant atual.
 */
export async function simulatePolicy(req: {
  userId: string;
  action: string;
  resource: string;
}): Promise<SimulateResult> {
  return request<SimulateResult>('/iam/policies/simulate', {
    method: 'POST',
    body: JSON.stringify(req),
  });
}

// ---------- IAM Invitations (US06, ADR 0011) ----------

/** Cria um convite. Exige IAM `iam:user:invite`. */
export async function inviteUser(req: InviteUserRequest): Promise<Invite> {
  return request<Invite>(`/iam/users/invite`, {
    method: 'POST',
    body: JSON.stringify(req),
  });
}

/** Lista convites do tenant atual; filtra por status quando informado. */
export async function listInvites(status?: InviteStatus): Promise<InviteListResponse> {
  const qs = status ? `?status=${encodeURIComponent(status)}` : '';
  return request<InviteListResponse>(`/iam/invites${qs}`);
}

/**
 * Aceita um convite. **Endpoint publico**: o `token` na URL e a credencial,
 * portanto nao enviamos Bearer JWT (mesmo se o navegador tiver sessao de outro
 * tenant). Backend cria user, persiste senha e devolve `LoginResponse`.
 */
export async function acceptInvite(
  token: string,
  req: AcceptInviteRequest,
): Promise<LoginResponse> {
  return request<LoginResponse>(`/iam/invites/${encodeURIComponent(token)}/accept`, {
    method: 'POST',
    body: JSON.stringify(req),
    skipAuth: true,
  });
}

/** Revoga um convite PENDING. Exige IAM `iam:invite:revoke`. */
export async function revokeInvite(id: string): Promise<void> {
  return request<void>(`/iam/invites/${encodeURIComponent(id)}`, { method: 'DELETE' });
}
