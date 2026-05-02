/**
 * Cliente HTTP minimalista para a API do NORA.
 *
 * Por padrao, em modo MVP/dev, retorna fixtures estaticos (USE_MOCKS=true).
 * Quando NEXT_PUBLIC_USE_MOCKS=false, faz fetch real contra NEXT_PUBLIC_API_BASE_URL.
 */

import type { MeetingDetail, MeetingsListResponse, ApiError } from "./types";
import meetingsListFixture from "@/fixtures/meetings-list-response.json";
import meetingDetailFixture from "@/fixtures/meeting-detail-response.json";

const USE_MOCKS = (process.env.NEXT_PUBLIC_USE_MOCKS ?? "true") !== "false";
const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const resp = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(init?.headers ?? {}),
    },
    cache: "no-store",
  });
  if (!resp.ok) {
    let payload: ApiError | undefined;
    try {
      payload = (await resp.json()) as ApiError;
    } catch {
      // ignore
    }
    throw new Error(
      payload?.message ?? `Request failed: ${resp.status} ${resp.statusText}`,
    );
  }
  return (await resp.json()) as T;
}

export async function listMeetings(): Promise<MeetingsListResponse> {
  if (USE_MOCKS) {
    return meetingsListFixture as unknown as MeetingsListResponse;
  }
  return request<MeetingsListResponse>("/meetings?page=1&pageSize=20");
}

export async function getMeeting(id: string): Promise<MeetingDetail> {
  if (USE_MOCKS) {
    return meetingDetailFixture as unknown as MeetingDetail;
  }
  return request<MeetingDetail>(`/meetings/${encodeURIComponent(id)}`);
}
