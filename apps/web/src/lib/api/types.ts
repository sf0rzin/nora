/**
 * Tipos TypeScript que espelham docs/api/openapi.yaml e os exemplos em
 * docs/api/examples/. Mudancas aqui devem manter paridade com o backend.
 */

export type Sentiment = "POSITIVE" | "NEUTRAL" | "NEGATIVE" | "MIXED";
export type Severity = "LOW" | "MEDIUM" | "HIGH";
export type Priority = "LOW" | "MEDIUM" | "HIGH";

export type ProcessingStatus = "PENDING" | "PROCESSING" | "COMPLETED" | "FAILED";

export interface MeetingListItem {
  id: string;
  title: string;
  startedAt: string;
  durationSeconds?: number;
  ownerName: string;
  processingStatus: ProcessingStatus;
  summarySnippet?: string;
  actionItemCount: number;
  riskCount: number;
  opportunityCount: number;
  tags: string[];
}

export interface MeetingsListResponse {
  items: MeetingListItem[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface Decision {
  id?: string;
  text: string;
  confidence: number;
}

export interface ActionItem {
  id?: string;
  title: string;
  assignee?: string | null;
  dueDate?: string | null;
  priority: Priority;
  sourceQuote: string;
  status?: "OPEN" | "IN_PROGRESS" | "DONE";
}

export interface Risk {
  id?: string;
  text: string;
  severity: Severity;
  category: string;
  sourceQuote: string;
}

export interface Opportunity {
  id?: string;
  text: string;
  estimatedValue: Severity;
  category: string;
  sourceQuote: string;
}

export interface MeetingAnalysis {
  id?: string;
  summary: string;
  decisions: Decision[];
  actionItems: ActionItem[];
  risks: Risk[];
  opportunities: Opportunity[];
  sentimentOverall: Sentiment;
  topics: string[];
  modelVersion?: string;
  promptVersion?: string;
  generatedAt?: string;
}

export interface UserRef {
  id: string;
  displayName: string;
}

export interface Participant {
  displayName: string;
  email?: string;
  isInternal?: boolean;
}

export interface MeetingDetail {
  id: string;
  tenantId: string;
  title: string;
  startedAt: string;
  endedAt?: string;
  durationSeconds?: number;
  language?: string;
  owner: UserRef;
  participants: Participant[];
  processingStatus: ProcessingStatus;
  analysis?: MeetingAnalysis;
  createdAt: string;
  updatedAt: string;
}

export interface ApiError {
  code: string;
  message: string;
  traceId?: string;
  timestamp?: string;
  details?: { field: string; issue: string }[];
}

// ---------- IAM Invitations (US06, ADR 0011) ----------

export type InviteStatus = "PENDING" | "ACCEPTED" | "EXPIRED" | "REVOKED";

export interface Invite {
  id: string;
  tenantId: string;
  email: string;
  status: InviteStatus;
  invitedBy: string;
  invitedAt: string;
  expiresAt: string;
  groupIds: string[];
  acceptedAt: string | null;
  acceptedUserId: string | null;
}

export interface InviteUserRequest {
  email: string;
  groupIds: string[];
  expiresInDays?: number;
}

export interface AcceptInviteRequest {
  /** Opcional — backend usa local-part do e-mail se omitido. Max 120 chars. */
  displayName?: string;
  password: string;
}

export interface InviteListResponse {
  items: Invite[];
  total: number;
  page: number;
  size: number;
}
