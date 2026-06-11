/**
 * NORA Flows — catálogo de blocos do builder (Fase 1 + ações Google da Fase 2).
 *
 * Fonte única de verdade no front pros blocos que o engine aceita (ADR 0030).
 * O backend rejeita tipos desconhecidos, então NUNCA adicione um bloco aqui
 * sem o executor correspondente no engine (services/api).
 */
import type { ReactNode } from "react";

import type { WorkflowNodeKind } from "@/lib/api/types";

/** Metadados visuais de cada papel de nó (gatilho/condição/ação). */
export const KIND_META: Record<
  WorkflowNodeKind,
  { rotulo: string; rotuloPlural: string; cor: string }
> = {
  trigger: { rotulo: "Gatilho", rotuloPlural: "Gatilhos", cor: "var(--accent)" },
  condition: { rotulo: "Condição", rotuloPlural: "Condições", cor: "var(--warn)" },
  action: { rotulo: "Ação", rotuloPlural: "Ações", cor: "var(--success)" },
};

/** Ícone do papel do nó — SVG inline 14px no padrão do app (stroke 1.7). */
export function IconeKind({ kind, size = 14 }: { kind: WorkflowNodeKind; size?: number }): ReactNode {
  const comum = {
    width: size,
    height: size,
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    strokeWidth: 1.7,
    strokeLinecap: "round" as const,
    strokeLinejoin: "round" as const,
  };
  switch (kind) {
    case "trigger":
      // Raio — evento que dispara o fluxo.
      return (
        <svg {...comum}>
          <path d="M13 2 4.5 13.5h6L11 22l8.5-11.5h-6z" />
        </svg>
      );
    case "condition":
      // Funil — filtra o caminho da execução.
      return (
        <svg {...comum}>
          <path d="M3 5h18l-7 8v6l-4-2v-4z" />
        </svg>
      );
    case "action":
      // Avião de papel — executa algo no mundo real.
      return (
        <svg {...comum}>
          <path d="m22 2-11 11" />
          <path d="M22 2 15 22l-4-9-9-4z" />
        </svg>
      );
  }
}

/** Props comuns dos ícones por bloco — mesmo traço do IconeKind (stroke 1.7). */
function propsSvg(size: number) {
  return {
    width: size,
    height: size,
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    strokeWidth: 1.7,
    strokeLinecap: "round" as const,
    strokeLinejoin: "round" as const,
  };
}

/** Envelope com seta de envio — ação "Enviar via Gmail". */
export function IconeGmail({ size = 14 }: { size?: number }): ReactNode {
  return (
    <svg {...propsSvg(size)}>
      <path d="M21 12V7a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h8" />
      <path d="m3 7 9 6 9-6" />
      <path d="M15.5 19H22" />
      <path d="m19.5 16.5 2.5 2.5-2.5 2.5" />
    </svg>
  );
}

/** Calendário com "+" — ação "Criar evento no Calendar". */
export function IconeCalendarMais({ size = 14 }: { size?: number }): ReactNode {
  return (
    <svg {...propsSvg(size)}>
      <rect x="3" y="5" width="18" height="16" rx="2.5" />
      <path d="M8 3v4M16 3v4M3 10h18" />
      <path d="M12 13.2v4.6M9.7 15.5h4.6" />
    </svg>
  );
}

const PRIORIDADE_ROTULO: Record<string, string> = {
  HIGH: "Alta",
  MEDIUM: "Média",
  LOW: "Baixa",
};

export interface BlocoMeta {
  kind: WorkflowNodeKind;
  /** Tipo aceito pelo engine (NÃO renomear — contrato com o backend). */
  type: string;
  nome: string;
  descricao: string;
  /** Params iniciais quando o bloco entra no canvas. */
  paramsPadrao: Record<string, unknown>;
  /** Linha de resumo dos params exibida dentro do nó (null = sem resumo). */
  resumo: (params: Record<string, unknown>) => string | null;
  /** Ícone próprio do bloco; quando ausente, usa o ícone do papel (IconeKind). */
  Icone?: (props: { size?: number }) => ReactNode;
}

/** Catálogo v1 — exatamente os tipos que o engine executa hoje. */
export const CATALOGO: BlocoMeta[] = [
  {
    kind: "trigger",
    type: "meeting.analysis_completed",
    nome: "Reunião analisada",
    descricao: "Dispara quando a análise de uma reunião termina",
    paramsPadrao: {},
    resumo: () => null,
  },
  {
    kind: "condition",
    type: "productivity_score_below",
    nome: "Productivity Score abaixo de…",
    descricao: "Segue só se o score ficar abaixo do limite",
    paramsPadrao: { value: 70 },
    resumo: (p) => (typeof p.value === "number" ? `score < ${p.value}` : "defina o limite"),
  },
  {
    kind: "condition",
    type: "customer_confidence_below",
    nome: "Customer Confidence abaixo de…",
    descricao: "Segue só se a confiança ficar abaixo do limite",
    paramsPadrao: { value: 60 },
    resumo: (p) => (typeof p.value === "number" ? `confiança < ${p.value}` : "defina o limite"),
  },
  {
    kind: "condition",
    type: "tag_equals",
    nome: "Reunião tem a tag…",
    descricao: "Segue só se a reunião tiver a tag exata",
    paramsPadrao: { value: "" },
    resumo: (p) =>
      typeof p.value === "string" && p.value.trim() ? `tag: ${p.value.trim()}` : "defina a tag",
  },
  {
    kind: "condition",
    type: "priority_equals",
    nome: "Há action item com prioridade…",
    descricao: "Segue só se houver action item nessa prioridade",
    paramsPadrao: { value: "HIGH" },
    resumo: (p) =>
      typeof p.value === "string" && PRIORIDADE_ROTULO[p.value]
        ? `prioridade: ${PRIORIDADE_ROTULO[p.value]}`
        : "defina a prioridade",
  },
  {
    kind: "action",
    type: "send_email",
    nome: "Enviar e-mail",
    descricao: "Envia e-mail real com o resumo da reunião",
    paramsPadrao: { to: "" },
    resumo: (p) =>
      typeof p.to === "string" && p.to.trim() ? `para: ${p.to.trim()}` : "defina o destinatário",
  },
  {
    kind: "action",
    type: "gmail_send_email",
    nome: "Enviar via Gmail",
    descricao: "Envia pela SUA conta Google conectada (remetente = você)",
    paramsPadrao: { to: "" },
    resumo: (p) =>
      typeof p.to === "string" && p.to.trim() ? `para: ${p.to.trim()}` : "defina o destinatário",
    Icone: IconeGmail,
  },
  {
    kind: "action",
    type: "calendar_create_event",
    nome: "Criar evento no Calendar",
    descricao: "Cria um follow-up no seu Google Calendar",
    paramsPadrao: { title: "", startInDays: 1, hour: 10, durationMinutes: 30 },
    resumo: (p) => resumoEvento(p),
    Icone: IconeCalendarMais,
  },
];

/** Resumo do calendar_create_event — ex.: "amanhã às 10h, 30min". */
function resumoEvento(p: Record<string, unknown>): string {
  const dias = typeof p.startInDays === "number" ? p.startInDays : 1;
  const hora = typeof p.hour === "number" ? p.hour : 10;
  const dur = typeof p.durationMinutes === "number" ? p.durationMinutes : 30;
  const quando = dias === 0 ? "hoje" : dias === 1 ? "amanhã" : `em ${dias} dias`;
  return `${quando} às ${hora}h, ${dur}min`;
}

/** Busca a meta de um bloco pelo tipo. Tipos fora do catálogo retornam undefined. */
export function metaDoBloco(type: string): BlocoMeta | undefined {
  return CATALOGO.find((b) => b.type === type);
}

/**
 * Placeholders suportados pelo backend em subject/body do send_email e do
 * gmail_send_email (e no title do calendar_create_event).
 * Renderizados como chips clicáveis no painel de parâmetros.
 */
export const PLACEHOLDERS_EMAIL: { token: string; dica: string }[] = [
  { token: "{{meeting.title}}", dica: "título da reunião" },
  { token: "{{meeting.summary}}", dica: "resumo gerado" },
  { token: "{{meeting.url}}", dica: "link da reunião" },
  { token: "{{meeting.tags}}", dica: "tags da reunião" },
  { token: "{{productivity.score}}", dica: "Productivity Score" },
  { token: "{{confidence.score}}", dica: "Customer Confidence" },
];
