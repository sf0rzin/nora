export interface LoginRequest {
  email: string;
  password: string;
}

export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  userId: string;
  tenantId: string;
  email: string;
  displayName: string;
}

export interface SessionUser {
  id: string;
  email: string;
  displayName: string;
  tenantId: string;
  roles: string[];
}

export interface MeetingSummary {
  id: string;
  tenantId: string;
  title: string;
  startedAt: string;
  endedAt?: string;
  durationSeconds?: number;
  ownerName: string;
  ownerId: string;
  processingStatus: "PENDING" | "PROCESSING" | "COMPLETED" | "FAILED";
  summarySnippet?: string;
  actionItemCount: number;
  riskCount: number;
  opportunityCount: number;
  tags: string[];
  createdAt: string;
}

export interface MeetingsPage {
  items: MeetingSummary[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface MeetingDetail extends MeetingSummary {
  language: string;
  participants: Participant[];
  owner: {
    id: string;
    displayName: string;
  };
  analysis?: MeetingAnalysis;
  updatedAt: string;
}

export interface Participant {
  displayName: string;
  email?: string;
  isInternal?: boolean;
}

export interface MeetingAnalysis {
  id: string;
  summary: string;
  decisions: Decision[];
  actionItems: ActionItem[];
  risks: Risk[];
  opportunities: Opportunity[];
  sentimentOverall: "POSITIVE" | "NEUTRAL" | "NEGATIVE" | "MIXED";
  topics: string[];
  modelVersion: string;
  promptVersion: string;
  generatedAt: string;
}

export interface Decision {
  id: string;
  text: string;
  confidence: number;
}

export interface ActionItem {
  id: string;
  title: string;
  assignee?: string;
  dueDate?: string;
  priority: "LOW" | "MEDIUM" | "HIGH";
  status: "OPEN" | "IN_PROGRESS" | "DONE" | "CANCELLED";
  sourceQuote?: string;
}

export interface Risk {
  id: string;
  text: string;
  severity: "LOW" | "MEDIUM" | "HIGH";
  category: string;
  sourceQuote?: string;
}

export interface Opportunity {
  id: string;
  text: string;
  estimatedValue?: string;
  category: string;
  sourceQuote?: string;
}

export interface ApiError {
  code: string;
  message: string;
  traceId: string;
  timestamp: string;
}
