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

// Productivity Score (ADR 0005) — opt-in por reunião
export type ProductivityBand = 'LOW' | 'MEDIUM' | 'HIGH';
export type CoverageStatus = 'ADDRESSED' | 'PARTIAL' | 'MISSED';

export interface ProductivityCoverage {
  expectedOutcome: string;
  status: CoverageStatus;
  evidence: string | null;
}

export interface ProductivityAssessment {
  score: number;
  band: ProductivityBand;
  coverage: ProductivityCoverage[];
  offTopicRatio: number | null;
  decisionDensity: number | null;
  rationale: string;
}

export interface MeetingGoal {
  purpose: string;
  expectedOutcomes: string[];
  projectStateSnapshot: string | null;
}

// Customer Confidence (ADR 0015) — presente só em reuniões externas
// (conversa com cliente/lead); null para reuniões internas.
export type ConfidenceBand = 'LOW' | 'MEDIUM' | 'HIGH';
export type ConfidenceTrend = 'IMPROVING' | 'STABLE' | 'DECLINING';

export interface BuyingSignal {
  type: string;
  quote: string;
  weight?: number | null;
}

export interface Objection {
  type: string;
  quote: string;
  severity: Severity;
  competitor?: string | null;
}

export interface CustomerConfidence {
  score: number;
  band: ConfidenceBand;
  /** Valor autoritativo server-side; null na primeira reunião da conta. */
  trend: ConfidenceTrend | null;
  /** Nome da conta resolvida via get-or-create; pode ser null. */
  accountName: string | null;
  rationale: string;
  buyingSignals: BuyingSignal[];
  objections: Objection[];
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
  goal?: MeetingGoal | null;
  productivity?: ProductivityAssessment | null;
  customerConfidence?: CustomerConfidence | null;
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
