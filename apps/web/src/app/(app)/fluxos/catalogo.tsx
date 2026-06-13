/**
 * NORA Flows — catálogo de blocos do builder (Fase 1 + ações de integração da Fase 2).
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

/** Seta saindo da caixa — ação "Chamar webhook" (POST pra fora do NORA). */
export function IconeWebhook({ size = 14 }: { size?: number }): ReactNode {
  return (
    <svg {...propsSvg(size)}>
      <path d="M14 4h6v6" />
      <path d="M20 4 11 13" />
      <path d="M20 14v5a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h5" />
    </svg>
  );
}

/** Balão de chat com dois pontos — ação "Avisar no Discord". */
export function IconeDiscord({ size = 14 }: { size?: number }): ReactNode {
  return (
    <svg {...propsSvg(size)}>
      <path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8z" />
      <path d="M9 11.5h.01M15 11.5h.01" />
    </svg>
  );
}

/** Círculo com "+" — ação "Criar issue no GitHub" (estilo issue aberta). */
export function IconeGitHub({ size = 14 }: { size?: number }): ReactNode {
  return (
    <svg {...propsSvg(size)}>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 8.8v6.4M8.8 12h6.4" />
    </svg>
  );
}

/** Documento com linhas — ação "Criar página no Notion". */
export function IconeNotion({ size = 14 }: { size?: number }): ReactNode {
  return (
    <svg {...propsSvg(size)}>
      <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" />
      <path d="M14 3v5h5" />
      <path d="M9 13h6M9 17h6" />
    </svg>
  );
}

/** Quadrado com check — ação "Criar tarefa no Todoist". */
export function IconeTodoist({ size = 14 }: { size?: number }): ReactNode {
  return (
    <svg {...propsSvg(size)}>
      <rect x="4" y="4" width="16" height="16" rx="3.5" />
      <path d="m8.5 12.3 2.4 2.4 4.8-5" />
    </svg>
  );
}

/** Quadro kanban com três colunas — ação "Criar issue no Linear". */
export function IconeLinear({ size = 14 }: { size?: number }): ReactNode {
  return (
    <svg {...propsSvg(size)}>
      <rect x="3.5" y="4.5" width="17" height="15" rx="2.5" />
      <path d="M9.2 4.5v15M14.8 4.5v15" />
    </svg>
  );
}

/** Cerquilha (#) — ação "Postar no Slack" (canal). */
export function IconeSlack({ size = 14 }: { size?: number }): ReactNode {
  return (
    <svg {...propsSvg(size)}>
      <path d="M10 4 8 20M16 4l-2 16" />
      <path d="M5 9.5h15M4 14.5h15" />
    </svg>
  );
}

/** Envelope fechado — ação "Enviar pelo Outlook". */
export function IconeOutlook({ size = 14 }: { size?: number }): ReactNode {
  return (
    <svg {...propsSvg(size)}>
      <rect x="3" y="5" width="18" height="14" rx="2" />
      <path d="m3 7.5 9 6 9-6" />
    </svg>
  );
}

/** Calendário com check — ação "Evento no Outlook Calendar". */
export function IconeCalendarCheck({ size = 14 }: { size?: number }): ReactNode {
  return (
    <svg {...propsSvg(size)}>
      <rect x="3" y="5" width="18" height="16" rx="2.5" />
      <path d="M8 3v4M16 3v4M3 10h18" />
      <path d="m9.3 15.2 1.9 1.9 3.5-3.7" />
    </svg>
  );
}

/** Avião de papel num círculo — ação "Avisar no Telegram". */
export function IconeTelegram({ size = 14 }: { size?: number }): ReactNode {
  return (
    <svg {...propsSvg(size)}>
      <circle cx="12" cy="12" r="8.5" />
      <path d="m16.2 8.2-8.4 3.2 3.2 1.5 1.4 3.3z" />
    </svg>
  );
}

/** Board com duas listas (uma mais curta) — ação "Criar card no Trello". */
export function IconeTrello({ size = 14 }: { size?: number }): ReactNode {
  return (
    <svg {...propsSvg(size)}>
      <rect x="4" y="4" width="16" height="16" rx="3" />
      <path d="M8.5 8v8M15.5 8v4.5" />
    </svg>
  );
}

/** Prefixos válidos de webhook de canal do Discord — mesma regra do backend. */
export const PREFIXOS_WEBHOOK_DISCORD = [
  "https://discord.com/api/webhooks/",
  "https://discordapp.com/api/webhooks/",
];

/** true quando o valor é uma URL de webhook do Discord. */
export function ehWebhookDiscord(valor: unknown): boolean {
  return (
    typeof valor === "string" && PREFIXOS_WEBHOOK_DISCORD.some((p) => valor.trim().startsWith(p))
  );
}

/** true quando o valor segue o formato owner/nome — mesma regra do backend. */
export function ehRepoGitHub(valor: unknown): boolean {
  return typeof valor === "string" && valor.trim().length > 0 && valor.trim().includes("/");
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
  {
    kind: "action",
    type: "call_webhook",
    nome: "Chamar webhook",
    descricao: "POST com os dados da reunião em JSON pra uma URL sua",
    paramsPadrao: { url: "" },
    resumo: (p) =>
      typeof p.url === "string" && p.url.trim() ? hostDaUrl(p.url) : "defina a URL",
    Icone: IconeWebhook,
  },
  {
    kind: "action",
    type: "discord_post_message",
    nome: "Avisar no Discord",
    descricao: "Posta o resumo da reunião num canal via webhook",
    paramsPadrao: { webhookUrl: "" },
    resumo: (p) =>
      ehWebhookDiscord(p.webhookUrl)
        ? "webhook configurado"
        : typeof p.webhookUrl === "string" && p.webhookUrl.trim()
          ? "webhook inválido"
          : "defina o webhook",
    Icone: IconeDiscord,
  },
  {
    kind: "action",
    type: "github_create_issue",
    nome: "Criar issue no GitHub",
    descricao: "Abre issues no repositório — uma por action item",
    paramsPadrao: { repo: "" },
    resumo: (p) =>
      ehRepoGitHub(p.repo)
        ? `repo: ${String(p.repo).trim()}`
        : typeof p.repo === "string" && p.repo.trim()
          ? "use o formato owner/nome"
          : "defina o repositório",
    Icone: IconeGitHub,
  },
  {
    kind: "action",
    type: "notion_create_page",
    nome: "Criar página no Notion",
    descricao: "Cria uma página com resumo e action items da reunião",
    paramsPadrao: { parentPageId: "" },
    resumo: (p) =>
      typeof p.parentPageId === "string" && p.parentPageId.trim()
        ? `página: ${truncar(p.parentPageId.trim(), 12)}`
        : "defina a página pai",
    Icone: IconeNotion,
  },
  {
    kind: "action",
    type: "todoist_create_task",
    nome: "Criar tarefa no Todoist",
    descricao: "Uma tarefa no Inbox pra cada action item",
    paramsPadrao: {},
    resumo: () => "uma tarefa por action item",
    Icone: IconeTodoist,
  },
  {
    kind: "action",
    type: "linear_create_issue",
    nome: "Criar issue no Linear",
    descricao: "Uma issue por action item no time escolhido",
    paramsPadrao: { teamKey: "" },
    resumo: (p) =>
      typeof p.teamKey === "string" && p.teamKey.trim()
        ? `time: ${p.teamKey.trim()}`
        : "primeiro time do workspace",
    Icone: IconeLinear,
  },
  {
    kind: "action",
    type: "slack_post_message",
    nome: "Postar no Slack",
    descricao: "Resumo da reunião num canal da workspace conectada",
    paramsPadrao: { channel: "" },
    resumo: (p) =>
      typeof p.channel === "string" && p.channel.trim()
        ? `canal: ${p.channel.trim()}`
        : "defina o canal",
    Icone: IconeSlack,
  },
  {
    kind: "action",
    type: "outlook_send_email",
    nome: "Enviar pelo Outlook",
    descricao: "Envia pela SUA conta Microsoft conectada (remetente = você)",
    paramsPadrao: { to: "" },
    resumo: (p) =>
      typeof p.to === "string" && p.to.trim() ? `para: ${p.to.trim()}` : "defina o destinatário",
    Icone: IconeOutlook,
  },
  {
    kind: "action",
    type: "mscalendar_create_event",
    nome: "Evento no Outlook Calendar",
    descricao: "Cria um follow-up no calendário da conta Microsoft",
    paramsPadrao: { title: "", startInDays: 1, hour: 10, durationMinutes: 30 },
    resumo: (p) => resumoEvento(p),
    Icone: IconeCalendarCheck,
  },
  {
    kind: "action",
    type: "telegram_send_message",
    nome: "Avisar no Telegram",
    descricao: "Resumo da reunião no chat pareado com o bot",
    paramsPadrao: {},
    resumo: () => "resumo no chat pareado",
    Icone: IconeTelegram,
  },
  {
    kind: "action",
    type: "trello_create_card",
    nome: "Criar card no Trello",
    descricao: "Um card por action item na lista escolhida",
    paramsPadrao: { listId: "" },
    resumo: (p) =>
      typeof p.listId === "string" && p.listId.trim()
        ? `lista: ${truncar(p.listId.trim(), 12)}`
        : "defina a lista",
    Icone: IconeTrello,
  },
];

/** Trunca valores longos pro resumo do nó (ex.: ID de página do Notion). */
function truncar(valor: string, max: number): string {
  return valor.length > max ? `${valor.slice(0, max)}…` : valor;
}

/** Host da URL pro resumo do nó (ex.: "hooks.exemplo.com"). */
function hostDaUrl(url: string): string {
  try {
    return new URL(url.trim()).host || "URL inválida";
  } catch {
    return "URL inválida";
  }
}

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
 * Placeholders suportados pelo backend em subject/body das ações de e-mail
 * (send_email, gmail_send_email, outlook_send_email) e no title dos eventos
 * de calendário (calendar_create_event, mscalendar_create_event).
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
