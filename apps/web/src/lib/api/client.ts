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
