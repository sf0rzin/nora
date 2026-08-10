/**
 * Minimal HTTP client for the NORA API.
 *
 * By default in dev mode it uses fixtures (NEXT_PUBLIC_USE_MOCKS=true). When false,
 * it does a real fetch against NEXT_PUBLIC_API_BASE_URL.
 *
 * Round 2 / Subphase 1.3 A:
 * - Auth is sent via httpOnly cookies (`nora_access`, `nora_refresh`)
 *   set by the backend on /auth/login. `credentials: include` on the fetch
 *   guarantees they are sent. The frontend does not need (nor is able) to read them.
 * - 401 interceptor: tries `POST /auth/refresh` once; on success, repeats
 *   the original request; on failure, redirects to /auth/login.
 */

import type {
  AcceptInviteRequest,
  ApiError,
  ChatMessage,
  ChatSessionDetail,
  ChatSessionSummary,
  IntegrationProvider,
  IntegrationStatus,
  Invite,
  InviteListResponse,
  InviteStatus,
  InviteUserRequest,
  MeetingDetail,
  MeetingGoal,
  MeetingsListResponse,
  MeResponse,
  SplitPreviewResponse,
  TelegramPairingStart,
  TenantInfo,
  WorkflowDefinition,
  WorkflowExecutionResponse,
  WorkflowResponse,
} from './types';

// Re-export so components can consume straight from @/lib/api/client (parity
// with GroupDto/PolicyDto etc., which are declared locally in this module).
export type { AcceptInviteRequest, Invite, InviteListResponse, InviteStatus, InviteUserRequest };
export type { ChatMessage, ChatSessionDetail, ChatSessionSummary };
export type { WorkflowDefinition, WorkflowExecutionResponse, WorkflowResponse };
export type { IntegrationProvider, IntegrationStatus, TelegramPairingStart };
export type { MeResponse, TenantInfo };
import meetingsListFixture from '@/fixtures/meetings-list-response.json';
import meetingDetailFixture from '@/fixtures/meeting-detail-response.json';
import { handleSessionExpired, sharedRefresh } from '@/lib/auth';

// Default must be 'false' in prod. It used to be 'true' → if the build forgot to
// set NEXT_PUBLIC_USE_MOCKS, prod served hardcoded fixtures. Now any build that
// does not set it explicitly goes against the real API (which fails fast if
// misconfigured — preferable to serving garbage).
const USE_MOCKS = process.env.NEXT_PUBLIC_USE_MOCKS === 'true';
const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';

/**
 * In Server Components / Route Handlers, `fetch` does NOT propagate the browser's
 * httpOnly cookies automatically. Without this, any RSC fetch (dashboard,
 * meeting detail) goes without auth → 401 → notFound() → 404 visible to the user.
 *
 * This helper detects SSR (typeof window === 'undefined') and uses `next/headers`
 * dynamically to attach the `Cookie` header. On the client it returns {} (the browser
 * sends them automatically with `credentials: 'include'`).
 */
async function serverCookieHeader(): Promise<Record<string, string>> {
  if (typeof window !== 'undefined') return {};
  try {
    // Dynamic import avoids loading next/headers into client bundles.
    const { cookies } = await import('next/headers');
    const all = (await cookies()).getAll();
    if (all.length === 0) return {};
    const cookieStr = all.map((c) => `${c.name}=${c.value}`).join('; ');
    return { Cookie: cookieStr };
  } catch {
    // Outside a request context (build, scripts) — no cookies.
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
   * When `true`, does not try refresh+retry on 401 — used on public
   * endpoints where 401 is a semantic error (login with the wrong password,
   * invalid refresh etc).
   */
  skipAuth?: boolean;
  /** Internal marker: avoids a loop when the call itself is the retry attempt. */
  _isRetry?: boolean;
}

/**
 * Single-flight refresh: the shared promise lives in `@/lib/auth`
 * (`sharedRefresh`) and is the SAME one awaited by the proactive refresh timer.
 * When several requests hit 401 simultaneously — or the timer fires at the
 * same time — they all await the same POST /auth/refresh instead of firing N
 * redundant requests (a duplicated refresh with the same cookie tripped the
 * backend's reuse detection and dropped the whole session).
 */
async function performRefresh(): Promise<boolean> {
  const result = await sharedRefresh();
  return result.ok;
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

  // 401 interceptor: 1 refresh attempt + retry. Skipped on public
  // endpoints (skipAuth) and on retries (avoids an infinite loop).
  if (resp.status === 401 && !init?.skipAuth && !init?._isRetry) {
    const refreshed = await performRefresh();
    if (refreshed) {
      return request<T>(path, { ...init, _isRetry: true });
    }
    // Refresh also failed: clear state and redirect. handleSessionExpired
    // calls window.location.href so in practice the Promise below never
    // resolves before the unload. We keep the throw for the SSR flow and tests.
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
  // 2xx with no body (e.g. 202 from /auth/verify-email/resend) must not break on parse.
  const text = await resp.text();
  if (!text) return undefined as T;
  return JSON.parse(text) as T;
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
export async function setMeetingGoal(meetingId: string, goal: MeetingGoal): Promise<MeetingGoal> {
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


/**
 * Parses a .txt file and detects segments of multiple meetings.
 * Returns the segments with a suggested title, lines and preview — the real
 * slicing is done client-side after the user confirms.
 *
 * Expected errors:
 * - 400: file is not .txt (PT-BR message from the API, show it directly)
 * - 413: file too large
 */
export async function splitPreview(file: File, language?: string): Promise<SplitPreviewResponse> {
  const fd = new FormData();
  fd.append('file', file);
  if (language) fd.append('language', language);
  return request<SplitPreviewResponse>(`/meetings/split-preview`, {
    method: 'POST',
    body: fd,
  });
}
/**
 * Re-fires the analysis pipeline of an existing meeting (POST 202).
 * Useful to recover from a `FAILED` or to re-analyse a `COMPLETED`. The backend
 * puts `processingStatus` back to `PENDING`/`PROCESSING`; the caller must redo
 * the polling/refresh. Desktop already consumes the same endpoint.
 */
export async function reprocessMeeting(
  meetingId: string,
): Promise<{ id: string; processingStatus: string }> {
  return request<{ id: string; processingStatus: string }>(
    `/meetings/${encodeURIComponent(meetingId)}/reprocess`,
    { method: 'POST' },
  );
}

// ---------- Privacy / LGPD (ADR 0029) ----------

/**
 * Right to be forgotten (LGPD Art. 18): PERMANENTLY deletes the meeting and all
 * the PII in cascade (raw transcript, participants, analyses). Irreversible.
 * Returns 204; 404 when it does not exist in the tenant (does not leak cross-tenant existence).
 */
export async function deleteMeeting(meetingId: string): Promise<void> {
  return request<void>(`/privacy/meetings/${encodeURIComponent(meetingId)}`, {
    method: 'DELETE',
  });
}

// ---------- Auth ----------

export interface LoginResponse {
  /**
   * Only present for native clients. Web sends `X-NORA-Client: web` and receives the
   * session only via httpOnly cookies — the token does NOT come in the body (XSS defense).
   */
  accessToken?: string;
  tokenType: string;
  expiresInSeconds: number;
  userId: string;
  tenantId: string;
  email: string;
  displayName: string;
}

export async function login(email: string, password: string) {
  return request<LoginResponse>(`/auth/login`, {
    method: 'POST',
    // Declares itself a web client: backend returns the session only via httpOnly cookie (token out of the body).
    headers: { 'X-NORA-Client': 'web' },
    body: JSON.stringify({ email, password }),
  });
}

export async function signup(input: {
  email: string;
  password: string;
  displayName: string;
  companyName: string;
  /** Usage intent collected during onboarding (telemetry #156). */
  role: string;
}) {
  return request<{
    userId: string;
    tenantId: string;
    message?: string;
    emailVerificationDevToken?: string;
  }>(`/auth/signup`, { method: 'POST', body: JSON.stringify(input) });
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

// ---------- Account & session (settings — Account/Security tabs) ----------

/** Authenticated user data — source of truth for the profile (GET /auth/me). */
export async function getMe(): Promise<MeResponse> {
  return request<MeResponse>(`/auth/me`);
}

/** Updates the logged-in user's profile. 400 VALIDATION_FAILED when blank. */
export async function updateMe(input: { displayName: string }): Promise<MeResponse> {
  return request<MeResponse>(`/users/me`, {
    method: 'PATCH',
    body: JSON.stringify(input),
  });
}

/**
 * Changes the logged-in user's password (204). The backend revokes ALL sessions and
 * reissues new cookies for the current device — the user STAYS logged in here.
 * 401 INVALID_CREDENTIALS = wrong current password; 400 = new password outside the policy
 * (the `message` explains the rule).
 */
export async function changePassword(input: {
  currentPassword: string;
  newPassword: string;
}): Promise<void> {
  return request<void>(`/auth/password/change`, {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

/**
 * Ends ALL sessions, including the current one (204 + cookies cleared by the
 * backend). The caller must clear the local state (`clearLocalSession`) and
 * send the user to the login.
 */
export async function logoutAllSessions(): Promise<void> {
  return request<void>(`/auth/logout-all`, { method: 'POST' });
}

/**
 * Resends the verification e-mail. **Public endpoint**, always answers 202
 * (anti-enumeration) — the UI must show the same message in every case.
 */
export async function resendVerificationEmail(email: string): Promise<void> {
  return request<void>(`/auth/verify-email/resend`, {
    method: 'POST',
    body: JSON.stringify({ email }),
    skipAuth: true,
  });
}

/**
 * PERMANENTLY deletes the account + workspace + all data (LGPD, right to be
 * forgotten). 204 and cookies cleared by the backend. 401 = wrong password;
 * 409 ACCOUNT_TENANT_SHARED = shared workspace (show the `message`).
 */
export async function deleteAccount(input: { password: string }): Promise<void> {
  return request<void>(`/users/me`, {
    method: 'DELETE',
    body: JSON.stringify(input),
  });
}

// ---------- Workspace / Tenant (settings — Workspace tab) ----------

/** Current workspace (GET /tenant). The slug is immutable — just meta info in the UI. */
export async function getTenant(): Promise<TenantInfo> {
  return request<TenantInfo>(`/tenant`);
}

/** Renames the workspace (PUT /tenant/name). Slug does not change. */
export async function renameTenant(input: { name: string }): Promise<TenantInfo> {
  return request<TenantInfo>(`/tenant/name`, {
    method: 'PUT',
    body: JSON.stringify(input),
  });
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

/** Current state of the tenant's corporate domain (GET /tenant/domain). */
export interface TenantDomain {
  tenantId: string;
  /** Normalized domain (e.g. "acme.com"). `null` when there is no restriction. */
  allowedEmailDomain: string | null;
}

/** Domain update response (PUT /tenant/domain). Includes light auditing. */
export interface TenantDomainUpdateResponse {
  tenantId: string;
  allowedEmailDomain: string | null;
  updatedAt: string;
  updatedBy: string;
}

/** Payload of PUT /tenant/domain. `null` removes the restriction. */
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

// ---------- IAM Invitations (US06, ADR 0011) ----------

/** Creates an invite. Requires IAM `iam:user:invite`. */
export async function inviteUser(req: InviteUserRequest): Promise<Invite> {
  return request<Invite>(`/iam/users/invite`, {
    method: 'POST',
    body: JSON.stringify(req),
  });
}

/** Lists invites of the current tenant; filters by status when given. */
export async function listInvites(status?: InviteStatus): Promise<InviteListResponse> {
  const qs = status ? `?status=${encodeURIComponent(status)}` : '';
  return request<InviteListResponse>(`/iam/invites${qs}`);
}

/**
 * Accepts an invite. **Public endpoint**: the `token` in the URL is the credential,
 * so we do not send a Bearer JWT (even if the browser has a session from another
 * tenant). The backend creates the user, persists the password and returns `LoginResponse`.
 */
export async function acceptInvite(
  token: string,
  req: AcceptInviteRequest,
): Promise<LoginResponse> {
  return request<LoginResponse>(`/iam/invites/${encodeURIComponent(token)}/accept`, {
    method: 'POST',
    headers: { 'X-NORA-Client': 'web' },
    body: JSON.stringify(req),
    skipAuth: true,
  });
}

/** Revokes a PENDING invite. Requires IAM `iam:invite:revoke`. */
export async function revokeInvite(id: string): Promise<void> {
  return request<void>(`/iam/invites/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

// ---------- Chat sessions (tenant + user scoped persistence) ----------
//
// All sessions are scoped to the logged-in user (the principal's user_id) inside
// the current tenant (ADR 0002 + RLS ADR 0028). The user only sees their own
// sessions; the backend resolves user_id/tenant_id from the authenticated context
// (httpOnly cookies via `credentials: 'include'`, already applied in the `request` helper).

/** Lists the logged-in user's sessions, most recent first. */
export async function listChatSessions(): Promise<ChatSessionSummary[]> {
  return request<ChatSessionSummary[]>(`/chat/sessions`);
}

/** Creates a session. `title` is optional; the backend can derive it after the 1st message. */
export async function createChatSession(title?: string): Promise<ChatSessionSummary> {
  return request<ChatSessionSummary>(`/chat/sessions`, {
    method: 'POST',
    body: JSON.stringify(title !== undefined ? { title } : {}),
  });
}

/** Loads a session with the full message history. */
export async function getChatSession(id: string): Promise<ChatSessionDetail> {
  return request<ChatSessionDetail>(`/chat/sessions/${encodeURIComponent(id)}`);
}

/**
 * Appends a message to the session. Bumps `updatedAt`; if the session has no title,
 * the backend derives the title from the user's 1st message (~48 chars).
 */
export async function appendChatMessage(
  id: string,
  msg: { role: 'user' | 'assistant'; content: string },
): Promise<ChatMessage> {
  return request<ChatMessage>(`/chat/sessions/${encodeURIComponent(id)}/messages`, {
    method: 'POST',
    body: JSON.stringify(msg),
  });
}

/** Renames a session. */
export async function renameChatSession(id: string, title: string): Promise<ChatSessionSummary> {
  return request<ChatSessionSummary>(`/chat/sessions/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    body: JSON.stringify({ title }),
  });
}

/** Permanently deletes a session (204). */
export async function deleteChatSession(id: string): Promise<void> {
  return request<void>(`/chat/sessions/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

// ---------- Semantic meeting search (RAG) ----------

/**
 * Searches meetings relevant to a query by semantic similarity.
 * `k` controls how many results to return (backend default). Scoped to the tenant.
 */
export async function searchMeetings(
  q: string,
  k?: number,
): Promise<{
  items: Array<{ id: string; title: string; summarySnippet?: string; startedAt?: string }>;
}> {
  const qs = new URLSearchParams();
  qs.set('q', q);
  if (typeof k === 'number') qs.set('k', String(k));
  return request<{
    items: Array<{ id: string; title: string; summarySnippet?: string; startedAt?: string }>;
  }>(`/meetings/search?${qs.toString()}`);
}

// ---------- NORA Flows — automation workflows (ADR 0030) ----------
//
// CRUD + manual test + execution history. Everything scoped to the tenant by the
// backend (ADR 0002); the engine validates the definition on POST/PUT and returns 422
// `WORKFLOW_INVALID_DEFINITION` with an actionable PT-BR message when the graph
// is invalid (no trigger, no action, with a cycle, missing params etc.).

/** Lists the flows of the current tenant. */
export async function listWorkflows(): Promise<WorkflowResponse[]> {
  return request<WorkflowResponse[]>(`/workflows`);
}

/** Loads a flow with the full definition (nodes + edges + positions). */
export async function getWorkflow(id: string): Promise<WorkflowResponse> {
  return request<WorkflowResponse>(`/workflows/${encodeURIComponent(id)}`);
}

/** Creates a flow. `active` defaults to true in the backend when omitted. */
export async function createWorkflow(input: {
  name: string;
  active?: boolean;
  definition: WorkflowDefinition;
}): Promise<WorkflowResponse> {
  return request<WorkflowResponse>(`/workflows`, {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

/** Updates name, active state and definition of an existing flow. */
export async function updateWorkflow(
  id: string,
  input: { name: string; active: boolean; definition: WorkflowDefinition },
): Promise<WorkflowResponse> {
  return request<WorkflowResponse>(`/workflows/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(input),
  });
}

/** Permanently deletes a flow (204). */
export async function deleteWorkflow(id: string): Promise<void> {
  return request<void>(`/workflows/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

/**
 * Runs the flow now, synchronously, against the tenant's last analysed meeting
 * (or sample data when there is no meeting). Returns the full execution
 * with the line-by-line log — including REAL e-mail sending.
 */
export async function testWorkflow(id: string): Promise<WorkflowExecutionResponse> {
  return request<WorkflowExecutionResponse>(`/workflows/${encodeURIComponent(id)}/test`, {
    method: 'POST',
  });
}

/** Execution history of the flow (max. 50, most recent first). */
export async function listWorkflowExecutions(id: string): Promise<WorkflowExecutionResponse[]> {
  return request<WorkflowExecutionResponse[]>(`/workflows/${encodeURIComponent(id)}/executions`);
}

// ---------- OAuth integrations (Google / Slack — NORA Flows Phase 2) ----------
//
// Per-user connectors: the backend stores the OAuth tokens and exposes only the status.
// The connection flow is a full redirect: `authorizeUrl` → consent at the
// provider → callback in the backend → back to /integrations?connected={provider}
// or /integrations?error={codigo}.

/** Status of every supported connector for the logged-in user. */
export async function listIntegrations(): Promise<IntegrationStatus[]> {
  return request<IntegrationStatus[]>(`/integrations`);
}

/**
 * Starts the provider's OAuth; the caller must redirect the browser to
 * `authorizeUrl`. Errors: 422 `INTEGRATION_NOT_CONFIGURED` (server without
 * OAuth credentials — the `message` comes in PT-BR) and 404 unknown provider.
 */
export async function startIntegrationOAuth(
  provider: IntegrationProvider,
): Promise<{ authorizeUrl: string }> {
  return request<{ authorizeUrl: string }>(
    `/integrations/${encodeURIComponent(provider)}/oauth/start`,
    { method: 'POST' },
  );
}

/** Disconnects the provider account and revokes the stored tokens (204). */
export async function disconnectIntegration(provider: IntegrationProvider): Promise<void> {
  return request<void>(`/integrations/${encodeURIComponent(provider)}`, { method: 'DELETE' });
}

/**
 * Telegram (no OAuth): generates the tenant's pairing code and returns the
 * bot's deep link. The user opens the link, sends /start and then calls
 * `verifyTelegramPairing`. Errors: 422 without NORA_TELEGRAM_BOT_TOKEN on the server.
 */
export async function startTelegramPairing(): Promise<TelegramPairingStart> {
  return request<TelegramPairingStart>(`/integrations/telegram/pairing/start`, {
    method: 'POST',
  });
}

/**
 * Telegram: checks whether the /start arrived and completes the connection. Errors: 409
 * `INTEGRATION_PAIRING_PENDING` (/start not seen yet — the message tells you to
 * try again) and 502 expired code/pairing not started.
 */
export async function verifyTelegramPairing(): Promise<IntegrationStatus> {
  return request<IntegrationStatus>(`/integrations/telegram/pairing/verify`, {
    method: 'POST',
  });
}

/**
 * Trello (no server-side OAuth): sends the token the user pasted; the
 * backend validates it against Trello before saving. Errors: 502 token refused
 * (message in PT-BR) and 422 without TRELLO_API_KEY on the server.
 */
export async function saveTrelloToken(token: string): Promise<IntegrationStatus> {
  return request<IntegrationStatus>(`/integrations/trello/token`, {
    method: 'POST',
    body: JSON.stringify({ token }),
  });
}

