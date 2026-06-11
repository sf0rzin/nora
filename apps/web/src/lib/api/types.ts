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
  /**
   * Metadados do pipeline de análise (espelha AnalysisResponse.Metadata no
   * backend). `piiRedactionsApplied` é a contagem real de redações aplicadas
   * pelo PII Shield (ADR 0012). Opcional: análises antigas podem não ter.
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

// ---------- Chat sessions (persistência tenant + user scoped) ----------

/**
 * Resumo de uma sessão de chat, usado na lista lateral. Sempre escopado ao
 * usuário logado (user_id do principal) dentro do tenant (ADR 0002 + RLS ADR 0028).
 */
export interface ChatSessionSummary {
  id: string;
  title: string;
  /** ISO-8601. Lista vem ordenada por este campo, mais recentes primeiro. */
  updatedAt: string;
  messageCount: number;
  /** Trecho curto da última mensagem, para preview na lista. */
  lastSnippet?: string;
}

/** Uma mensagem dentro de uma sessão de chat. */
export interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  content: string;
  /** ISO-8601. */
  createdAt: string;
}

/** Sessão de chat completa, com o histórico de mensagens. */
export interface ChatSessionDetail {
  id: string;
  title: string;
  /** ISO-8601. */
  createdAt: string;
  /** ISO-8601. */
  updatedAt: string;
  messages: ChatMessage[];
}

// ---------- NORA Flows — workflows de automação (ADR 0030) ----------

/** Papel de um nó dentro do grafo do fluxo. */
export type WorkflowNodeKind = "trigger" | "condition" | "action";

/** Nó da definição persistida no backend (kind + type do catálogo + params). */
export interface WorkflowDefinitionNode {
  id: string;
  kind: WorkflowNodeKind;
  /** Tipo do catálogo (ex.: `meeting.analysis_completed`, `send_email`). */
  type: string;
  params?: Record<string, unknown>;
  /** Posição do nó no canvas do builder (persistida pra reabrir igual). */
  position?: { x: number; y: number };
}

/** Aresta dirigida entre dois nós do fluxo. */
export interface WorkflowDefinitionEdge {
  id: string;
  source: string;
  target: string;
}

/**
 * Definição completa do fluxo (grafo). O backend valida: exatamente 1 gatilho,
 * ao menos 1 ação, sem ciclos e params obrigatórios por tipo de nó.
 */
export interface WorkflowDefinition {
  nodes: WorkflowDefinitionNode[];
  edges: WorkflowDefinitionEdge[];
}

/** Workflow persistido, escopado ao tenant (ADR 0002). */
export interface WorkflowResponse {
  id: string;
  name: string;
  /** Tipo do gatilho (derivado da definição pelo backend). */
  triggerType: string;
  active: boolean;
  definition: WorkflowDefinition;
  /** ISO-8601. */
  createdAt: string;
  /** ISO-8601. */
  updatedAt: string;
}

export type WorkflowExecutionStatus = "RUNNING" | "SUCCESS" | "FAILED";

/** Linha do log de uma execução de fluxo. */
export interface WorkflowExecutionLogEntry {
  /** ISO-8601. */
  at: string;
  /** Nó que produziu a linha; null para mensagens do engine. */
  nodeId: string | null;
  level: "info" | "error";
  message: string;
}

/** Execução de um fluxo (disparo real ou teste manual). */
export interface WorkflowExecutionResponse {
  id: string;
  workflowId: string;
  eventType: string;
  status: WorkflowExecutionStatus;
  log: WorkflowExecutionLogEntry[];
  /** ISO-8601. */
  createdAt: string;
  /** ISO-8601; null enquanto RUNNING. */
  finishedAt: string | null;
}
