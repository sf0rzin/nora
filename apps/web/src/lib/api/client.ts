/**
 * Cliente HTTP minimalista para a API do NORA.
 *
 * Por padrao em modo dev usa fixtures (NEXT_PUBLIC_USE_MOCKS=true). Quando false,
 * faz fetch real contra NEXT_PUBLIC_API_BASE_URL e injeta o JWT do cookie.
 */

import type { MeetingDetail, MeetingsListResponse, ApiError } from "./types";
import meetingsListFixture from "@/fixtures/meetings-list-response.json";
import meetingDetailFixture from "@/fixtures/meeting-detail-response.json";
import { getToken } from "@/lib/auth";

const USE_MOCKS = (process.env.NEXT_PUBLIC_USE_MOCKS ?? "true") !== "false";
const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

export class ApiRequestError extends Error {
  readonly status: number;
  readonly payload?: ApiError;
  constructor(status: number, message: string, payload?: ApiError) {
    super(message);
    this.status = status;
    this.payload = payload;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const token = getToken();
  const headers: Record<string, string> = {
    Accept: "application/json",
    ...((init?.headers as Record<string, string>) ?? {}),
  };
  if (init?.body && !(init.body instanceof FormData) && !headers["Content-Type"]) {
    headers["Content-Type"] = "application/json";
  }
  if (token) headers["Authorization"] = `Bearer ${token}`;

  const resp = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers,
    cache: "no-store",
  });
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
  status?: "PENDING" | "PROCESSING" | "COMPLETED" | "FAILED";
  from?: string; // ISO-8601
  to?: string; // ISO-8601
}

export async function listMeetings(params?: ListMeetingsParams): Promise<MeetingsListResponse> {
  if (USE_MOCKS) return meetingsListFixture as unknown as MeetingsListResponse;
  const qs = new URLSearchParams();
  qs.set("page", String(params?.page ?? 0));
  qs.set("size", String(params?.size ?? 20));
  if (params?.search) qs.set("search", params.search);
  if (params?.status) qs.set("status", params.status);
  if (params?.from) qs.set("from", params.from);
  if (params?.to) qs.set("to", params.to);
  return request<MeetingsListResponse>(`/meetings?${qs.toString()}`);
}

export async function getMeeting(id: string): Promise<MeetingDetail> {
  if (USE_MOCKS) return meetingDetailFixture as unknown as MeetingDetail;
  return request<MeetingDetail>(`/meetings/${encodeURIComponent(id)}`);
}

export interface UploadMeetingInput {
  title: string;
  language: string;
  transcriptFormat: "TXT" | "VTT" | "SRT";
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
  fd.append("metadata", new Blob([JSON.stringify(metadata)], { type: "application/json" }));
  fd.append("file", input.file);
  return request<{ id: string; processingStatus: string }>(`/meetings`, {
    method: "POST",
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
}

export async function login(email: string, password: string) {
  return request<LoginResponse>(`/auth/login`, {
    method: "POST",
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
    { method: "POST", body: JSON.stringify(input) },
  );
}

export async function verifyEmail(token: string) {
  return request<{ verified: boolean }>(`/auth/verify-email`, {
    method: "POST",
    body: JSON.stringify({ token }),
  });
}

export async function requestPasswordReset(email: string) {
  return request<{ requested: boolean }>(`/auth/password/reset/request`, {
    method: "POST",
    body: JSON.stringify({ email }),
  });
}

export async function confirmPasswordReset(token: string, newPassword: string) {
  return request<{ reset: boolean }>(`/auth/password/reset/confirm`, {
    method: "POST",
    body: JSON.stringify({ token, newPassword }),
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

export async function upsertTenantContext(payload: Omit<TenantContextDto, "tenantId" | "updatedAt">) {
  return request<TenantContextDto>(`/tenant/context`, {
    method: "PUT",
    body: JSON.stringify(payload),
  });
}

// ---------- Tasks ----------

export type TaskStatus = "OPEN" | "IN_PROGRESS" | "DONE";
export type TaskPriority = "LOW" | "MEDIUM" | "HIGH";

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
  if (status) qs.set("status", status);
  const path = qs.toString().length > 0 ? `/tasks?${qs.toString()}` : `/tasks`;
  return request<TaskListResponse>(path);
}

export async function updateTask(
  id: string,
  patch: { status?: TaskStatus; title?: string },
): Promise<TaskListItemDto> {
  return request<TaskListItemDto>(`/tasks/${encodeURIComponent(id)}`, {
    method: "PATCH",
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
    method: "POST",
    body: JSON.stringify({ name, description: description ?? null }),
  });
}

export async function deleteGroup(id: string): Promise<void> {
  return request<void>(`/iam/groups/${encodeURIComponent(id)}`, { method: "DELETE" });
}

export async function listGroupMembers(id: string): Promise<string[]> {
  return request<string[]>(`/iam/groups/${encodeURIComponent(id)}/members`);
}

export async function addGroupMember(groupId: string, userId: string): Promise<void> {
  return request<void>(
    `/iam/groups/${encodeURIComponent(groupId)}/members/${encodeURIComponent(userId)}`,
    { method: "POST" },
  );
}

export async function removeGroupMember(groupId: string, userId: string): Promise<void> {
  return request<void>(
    `/iam/groups/${encodeURIComponent(groupId)}/members/${encodeURIComponent(userId)}`,
    { method: "DELETE" },
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
    method: "POST",
    body: JSON.stringify({ name, description: description ?? null, document }),
  });
}

export async function updatePolicyDocument(id: string, document: unknown): Promise<PolicyDto> {
  return request<PolicyDto>(`/iam/policies/${encodeURIComponent(id)}`, {
    method: "PUT",
    body: JSON.stringify({ document }),
  });
}

export async function deletePolicy(id: string): Promise<void> {
  return request<void>(`/iam/policies/${encodeURIComponent(id)}`, { method: "DELETE" });
}

export async function attachPolicyToGroup(policyId: string, groupId: string): Promise<void> {
  return request<void>(
    `/iam/groups/${encodeURIComponent(groupId)}/policies/${encodeURIComponent(policyId)}`,
    { method: "POST" },
  );
}

export async function detachPolicyFromGroup(policyId: string, groupId: string): Promise<void> {
  return request<void>(
    `/iam/groups/${encodeURIComponent(groupId)}/policies/${encodeURIComponent(policyId)}`,
    { method: "DELETE" },
  );
}

export async function attachPolicyToUser(policyId: string, userId: string): Promise<void> {
  return request<void>(
    `/iam/users/${encodeURIComponent(userId)}/policies/${encodeURIComponent(policyId)}`,
    { method: "POST" },
  );
}

export async function detachPolicyFromUser(policyId: string, userId: string): Promise<void> {
  return request<void>(
    `/iam/users/${encodeURIComponent(userId)}/policies/${encodeURIComponent(policyId)}`,
    { method: "DELETE" },
  );
}

export async function listAuditEvents(limit = 50): Promise<AuditEventDto[]> {
  return request<AuditEventDto[]>(`/iam/audit?limit=${limit}`);
}
