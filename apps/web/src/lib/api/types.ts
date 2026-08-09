/**
 * TypeScript types mirroring docs/api/openapi.yaml and the examples in
 * docs/api/examples/. Changes here must keep parity with the backend.
 */

export type Sentiment = 'POSITIVE' | 'NEUTRAL' | 'NEGATIVE' | 'MIXED';
export type Severity = 'LOW' | 'MEDIUM' | 'HIGH';
export type Priority = 'LOW' | 'MEDIUM' | 'HIGH';

export type ProcessingStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';

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
  /** Productivity band when assessed; absent/null otherwise. */
  productivityBand?: 'LOW' | 'MEDIUM' | 'HIGH' | null;
  /** Score 0–100 when assessed. */
  productivityScore?: number | null;
  /** Participant names for the avatar stack. */
  participants?: string[];
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
  status?: 'OPEN' | 'IN_PROGRESS' | 'DONE';
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
  /**
   * Analysis pipeline metadata (mirrors AnalysisResponse.Metadata in the
   * backend). `piiRedactionsApplied` is the real count of redactions applied
   * by the PII Shield (ADR 0012). Optional: old analyses may not have it.
   */
  metadata?: { piiRedactionsApplied?: number; [k: string]: unknown };
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

// Productivity Score (ADR 0005) — opt-in per meeting
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

// Customer Confidence (ADR 0015) — present only in external meetings
// (conversation with customer/lead); null for internal meetings.
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
  /** Authoritative server-side value; null on the account's first meeting. */
  trend: ConfidenceTrend | null;
  /** Name of the account resolved via get-or-create; can be null. */
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
  /** Tags given on the meeting upload (may be empty). */
  tags?: string[];
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

// ---------- Transcript split (.txt file with several meetings) ----------

/**
 * A segment detected by `POST /meetings/split-preview`. `startLine` and
 * `endLine` are 1-based and INCLUSIVE over the ORIGINAL file — the real
 * slicing is done client-side after the user confirms. `preview` already comes
 * redacted by the worker's PII Shield (never raw PII).
 */
export interface SplitSegment {
  index: number;
  title: string;
  startLine: number;
  endLine: number;
  /** 0..1 — below 0.7 the UI suggests checking the split. */
  confidence: number;
  preview: string;
}

export interface SplitPreviewMetadata {
  modelVersion: string;
  promptVersion: string;
  tokensInput: number;
  tokensOutput: number;
  processingMillis: number;
  piiRedactionsApplied: number;
}

export interface SplitPreviewResponse {
  segments: SplitSegment[];
  totalLines: number;
  metadata: SplitPreviewMetadata;
}

// ---------- Account & Workspace (settings — Account/Security/Workspace) ----------

/** Authenticated user (GET /auth/me; PATCH /users/me returns the same shape). */
export interface MeResponse {
  userId: string;
  tenantId: string;
  email: string;
  displayName: string;
  /** Whether the account e-mail has already been verified (link sent on signup). */
  emailVerified: boolean;
  /** ISO-8601. */
  createdAt: string;
}

/** Current workspace (tenant) (GET /tenant; PUT /tenant/name returns the same shape). */
export interface TenantInfo {
  id: string;
  name: string;
  /** Immutable workspace identifier, defined at signup. */
  slug: string;
  plan: string;
  /** ISO-8601. */
  createdAt: string;
}

// ---------- IAM Invitations (US06, ADR 0011) ----------

export type InviteStatus = 'PENDING' | 'ACCEPTED' | 'EXPIRED' | 'REVOKED';

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
  /** Optional — backend uses the e-mail local-part if omitted. Max 120 chars. */
  displayName?: string;
  password: string;
}

export interface InviteListResponse {
  items: Invite[];
  total: number;
  page: number;
  size: number;
}

// ---------- Chat sessions (tenant + user scoped persistence) ----------

/**
 * Summary of a chat session, used in the side list. Always scoped to the
 * logged-in user (the principal's user_id) inside the tenant (ADR 0002 + RLS ADR 0028).
 */
export interface ChatSessionSummary {
  id: string;
  title: string;
  /** ISO-8601. The list comes ordered by this field, most recent first. */
  updatedAt: string;
  messageCount: number;
  /** Short excerpt of the last message, for the preview in the list. */
  lastSnippet?: string;
}

/** A message inside a chat session. */
export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  /** ISO-8601. */
  createdAt: string;
}

/** Full chat session, with the message history. */
export interface ChatSessionDetail {
  id: string;
  title: string;
  /** ISO-8601. */
  createdAt: string;
  /** ISO-8601. */
  updatedAt: string;
  messages: ChatMessage[];
}

// ---------- NORA Flows — automation workflows (ADR 0030) ----------

/** Role of a node inside the flow graph. */
export type WorkflowNodeKind = 'trigger' | 'condition' | 'action';

/** Node of the definition persisted in the backend (kind + catalog type + params). */
export interface WorkflowDefinitionNode {
  id: string;
  kind: WorkflowNodeKind;
  /** Catalog type (e.g. `meeting.analysis_completed`, `send_email`). */
  type: string;
  params?: Record<string, unknown>;
  /** Node position on the builder canvas (persisted so it reopens the same). */
  position?: { x: number; y: number };
}

/** Directed edge between two nodes of the flow. */
export interface WorkflowDefinitionEdge {
  id: string;
  source: string;
  target: string;
}

/**
 * Full flow definition (graph). The backend validates: exactly 1 trigger,
 * at least 1 action, no cycles and the params required per node type.
 */
export interface WorkflowDefinition {
  nodes: WorkflowDefinitionNode[];
  edges: WorkflowDefinitionEdge[];
}

/** Persisted workflow, scoped to the tenant (ADR 0002). */
export interface WorkflowResponse {
  id: string;
  name: string;
  /** Trigger type (derived from the definition by the backend). */
  triggerType: string;
  active: boolean;
  definition: WorkflowDefinition;
  /** ISO-8601. */
  createdAt: string;
  /** ISO-8601. */
  updatedAt: string;
}

export type WorkflowExecutionStatus = 'RUNNING' | 'SUCCESS' | 'FAILED';

/** Log line of a flow execution. */
export interface WorkflowExecutionLogEntry {
  /** ISO-8601. */
  at: string;
  /** Node that produced the line; null for engine messages. */
  nodeId: string | null;
  level: 'info' | 'error';
  message: string;
}

/** Execution of a flow (real trigger or manual test). */
export interface WorkflowExecutionResponse {
  id: string;
  workflowId: string;
  eventType: string;
  status: WorkflowExecutionStatus;
  log: WorkflowExecutionLogEntry[];
  /** ISO-8601. */
  createdAt: string;
  /** ISO-8601; null while RUNNING. */
  finishedAt: string | null;
}

// ---------- OAuth integrations (NORA Flows Phase 2) ----------

/**
 * Integration provider supported by the backend. Almost all are OAuth;
 * exceptions from wave 2: `telegram` (code pairing via bot) and
 * `trello` (token generated by the user and pasted into the hub).
 */
export type IntegrationProvider =
  | 'google'
  | 'slack'
  | 'github'
  | 'notion'
  | 'todoist'
  | 'linear'
  | 'microsoft'
  | 'telegram'
  | 'trello';

/** Response of the Telegram pairing start (POST /integrations/telegram/pairing/start). */
export interface TelegramPairingStart {
  /** Deep link t.me/<bot>?start=<code> for the user to open and send /start. */
  deepLink: string;
  /** Code shown in the hub (same payload as the deep link). */
  code: string;
}

/**
 * State of a connector for the logged-in user (GET /integrations).
 * `configured` = the server has the provider's OAuth credentials (environment);
 * `connected` = the user authorized their own account via OAuth.
 */
export interface IntegrationStatus {
  provider: IntegrationProvider;
  configured: boolean;
  connected: boolean;
  /** Connected external account (e.g. Google e-mail); null when disconnected. */
  externalAccount: string | null;
  /** ISO-8601; null when disconnected. */
  connectedAt: string | null;
}
