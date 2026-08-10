/**
 * Single locale module for the NORA web app.
 *
 * The product UI is pt-BR and there is exactly one locale, so this is a plain
 * nested object instead of an i18n framework: no runtime lookup, no key misses,
 * full type inference. Entries that interpolate a value are small functions so
 * the whole sentence stays here rather than being reassembled in a component.
 *
 * Coverage is deliberately partial. The files listed below import from here; the
 * remaining routes still hold their copy inline and are migrated in later passes.
 * Keep this list accurate — `scripts/check-language.sh` allowlists exactly the
 * files that still carry inline pt-BR.
 *
 * Migrated:
 *   app/(app)/flows/page.tsx
 *   app/(app)/flows/catalog.tsx
 *   app/(app)/flows/block-node.tsx
 *   app/(app)/flows/block-palette.tsx
 *   app/(app)/flows/relative-time.ts
 *   app/(app)/projects/page.tsx
 */

/** BCP 47 tag used by every `toLocaleDateString` / `toLocaleTimeString` call. */
export const LOCALE = "pt-BR";

export const strings = {
  flows: {
    /** Visual role of a node on the canvas (trigger / condition / action). */
    kinds: {
      trigger: { label: "Gatilho", labelPlural: "Gatilhos" },
      condition: { label: "Condição", labelPlural: "Condições" },
      action: { label: "Ação", labelPlural: "Ações" },
    },

    list: {
      eyebrow: "Nora Flows",
      title: "Fluxos",
      lede: "Automações que reagem às suas reuniões — gatilhos, condições e ações reais.",
      newFlow: "Novo fluxo",
      active: "Ativo",
      paused: "Pausado",
      conditionCount: (n: number) => (n === 1 ? "condição" : "condições"),
      actionCount: (n: number) => (n === 1 ? "ação" : "ações"),
      updatedPrefix: "Atualizado",
      loadFailed: "Falha ao carregar os fluxos.",
      loadErrorPrefix: "Não consegui carregar os fluxos agora",
      retry: "Tentar de novo",
      emptyTitle: "Nenhum fluxo ainda",
      emptyBody:
        "Um fluxo conecta um gatilho a ações — por exemplo, enviar um e-mail sempre que uma reunião terminar com o score baixo.",
      emptyCta: "Criar primeiro fluxo",
    },

    palette: {
      ariaLabel: "Paleta de blocos",
      sections: {
        trigger: "Gatilho",
        condition: "Condições",
        action: "Ações",
      },
      triggerAlreadyOnCanvas: "O fluxo já tem um gatilho — só pode haver um por fluxo.",
      clickToAdd: "Clique para adicionar ao canvas",
    },

    /** Action-item priority as shown in the `priority_equals` condition summary. */
    priority: {
      HIGH: "Alta",
      MEDIUM: "Média",
      LOW: "Baixa",
    } as Record<string, string>,

    /** Block catalog copy, keyed by the engine `type` (the wire contract). */
    blocks: {
      "meeting.analysis_completed": {
        name: "Reunião analisada",
        description: "Dispara quando a análise de uma reunião termina",
      },
      productivity_score_below: {
        name: "Productivity Score abaixo de…",
        description: "Segue só se o score ficar abaixo do limite",
      },
      customer_confidence_below: {
        name: "Customer Confidence abaixo de…",
        description: "Segue só se a confiança ficar abaixo do limite",
      },
      tag_equals: {
        name: "Reunião tem a tag…",
        description: "Segue só se a reunião tiver a tag exata",
      },
      priority_equals: {
        name: "Há action item com prioridade…",
        description: "Segue só se houver action item nessa prioridade",
      },
      send_email: {
        name: "Enviar e-mail",
        description: "Envia e-mail real com o resumo da reunião",
      },
      gmail_send_email: {
        name: "Enviar via Gmail",
        description: "Envia pela SUA conta Google conectada (remetente = você)",
      },
      calendar_create_event: {
        name: "Criar evento no Calendar",
        description: "Cria um follow-up no seu Google Calendar",
      },
      call_webhook: {
        name: "Chamar webhook",
        description: "POST com os dados da reunião em JSON pra uma URL sua",
      },
      discord_post_message: {
        name: "Avisar no Discord",
        description: "Posta o resumo da reunião num canal via webhook",
      },
      github_create_issue: {
        name: "Criar issue no GitHub",
        description: "Abre issues no repositório — uma por action item",
      },
      notion_create_page: {
        name: "Criar página no Notion",
        description: "Cria uma página com resumo e action items da reunião",
      },
      todoist_create_task: {
        name: "Criar tarefa no Todoist",
        description: "Uma tarefa no Inbox pra cada action item",
      },
      linear_create_issue: {
        name: "Criar issue no Linear",
        description: "Uma issue por action item no time escolhido",
      },
      slack_post_message: {
        name: "Postar no Slack",
        description: "Resumo da reunião num canal da workspace conectada",
      },
      outlook_send_email: {
        name: "Enviar pelo Outlook",
        description: "Envia pela SUA conta Microsoft conectada (remetente = você)",
      },
      mscalendar_create_event: {
        name: "Evento no Outlook Calendar",
        description: "Cria um follow-up no calendário da conta Microsoft",
      },
      telegram_send_message: {
        name: "Avisar no Telegram",
        description: "Resumo da reunião no chat pareado com o bot",
      },
      trello_create_card: {
        name: "Criar card no Trello",
        description: "Um card por action item na lista escolhida",
      },
    } as Record<string, { name: string; description: string }>,

    /** One-line params summary rendered inside each canvas node. */
    blockSummary: {
      scoreBelow: (value: number) => `score < ${value}`,
      confidenceBelow: (value: number) => `confiança < ${value}`,
      setThreshold: "defina o limite",
      tag: (value: string) => `tag: ${value}`,
      setTag: "defina a tag",
      priority: (label: string) => `prioridade: ${label}`,
      setPriority: "defina a prioridade",
      recipient: (value: string) => `para: ${value}`,
      setRecipient: "defina o destinatário",
      setUrl: "defina a URL",
      invalidUrl: "URL inválida",
      webhookConfigured: "webhook configurado",
      webhookInvalid: "webhook inválido",
      setWebhook: "defina o webhook",
      repo: (value: string) => `repo: ${value}`,
      repoFormat: "use o formato owner/nome",
      setRepo: "defina o repositório",
      page: (value: string) => `página: ${value}`,
      setParentPage: "defina a página pai",
      oneTaskPerActionItem: "uma tarefa por action item",
      team: (value: string) => `time: ${value}`,
      firstWorkspaceTeam: "primeiro time do workspace",
      channel: (value: string) => `canal: ${value}`,
      setChannel: "defina o canal",
      list: (value: string) => `lista: ${value}`,
      setList: "defina a lista",
      summaryInPairedChat: "resumo no chat pareado",
      /** calendar_create_event / mscalendar_create_event — e.g. "amanhã às 10h, 30min". */
      event: (days: number, hour: number, durationMinutes: number) => {
        const when = days === 0 ? "hoje" : days === 1 ? "amanhã" : `em ${days} dias`;
        return `${when} às ${hour}h, ${durationMinutes}min`;
      },
    },

    /** Hints for the placeholder chips of the e-mail / calendar actions. */
    placeholderHints: {
      "{{meeting.title}}": "título da reunião",
      "{{meeting.summary}}": "resumo gerado",
      "{{meeting.url}}": "link da reunião",
      "{{meeting.tags}}": "tags da reunião",
      "{{productivity.score}}": "Productivity Score",
      "{{confidence.score}}": "Customer Confidence",
    } as Record<string, string>,
  },

  /** Relative dates in pt-BR — no external library, app standard. */
  relativeTime: {
    now: "agora",
    minutesAgo: (n: number) => `há ${n} min`,
    hoursAgo: (n: number) => `há ${n} h`,
    yesterday: "ontem",
    daysAgo: (n: number) => `há ${n} dias`,
  },

  projects: {
    title: "Projetos",
    lede: "A Nora agrupa reuniões e action items por tag — cada frente de trabalho num só lugar, sem preencher nada.",
    meetingCount: (n: number) => (n === 1 ? "reunião" : "reuniões"),
    riskCount: (n: number) => (n === 1 ? "risco" : "riscos"),
    openActionItems: "abertos",
    actionItems: "action items",
    risks: "riscos",
    lastActivityPrefix: "Última atividade",
    meetingsSection: "Reuniões",
    tagsFootnote:
      "Projetos vêm das tags das suas reuniões. Adicione tags no upload — ou edite depois no detalhe da reunião.",
    emptyTitle: "Seus projetos aparecem aqui conforme você usa a Nora.",
    emptyBody: "Envie reuniões com tags — a Nora conecta os pontos e organiza tudo por projeto.",
    emptyUploadCta: "Enviar reunião",
    emptyChatCta: "Conversar com a Nora",
    loadMeetingsFailed: "Falha ao carregar reuniões.",
    loadErrorPrefix: "Não consegui carregar os projetos agora",
    loadErrorSuffix: "Verifique a conexão com a API.",
    /** Tags whose prettified form needs the pt-BR accent back. */
    prettyTagFixes: {
      saude: "Saúde",
      logistica: "Logística",
      cafe: "Café",
      estrategia: "Estratégia",
      producao: "Produção",
    } as Record<string, string>,
    status: {
      PENDING: "Na fila",
      PROCESSING: "Analisando…",
      COMPLETED: "Analisada",
      FAILED: "Falhou",
    },
  },
} as const;

/**
 * pt-BR copy for the API's error codes.
 *
 * The API's `code` is its contract; the `message` beside it is a developer-facing
 * detail that happens to be English since the backend was normalised. The client
 * used to render that message straight into the UI, which put English error text
 * in a Portuguese product the moment the backend stopped speaking Portuguese.
 *
 * Anything absent here falls back to the message, so an unmapped code degrades to
 * something readable rather than to nothing.
 */
export const errorCopy: Record<string, string> = {
  INVALID_CREDENTIALS: "E-mail ou senha incorretos.",
  EMAIL_NOT_VERIFIED: "Confirme seu e-mail antes de entrar.",
  EMAIL_ALREADY_TAKEN: "Esse e-mail já está em uso.",
  EMAIL_DOMAIN_NOT_ALLOWED: "Esse domínio de e-mail não é aceito neste workspace.",
  USER_DISABLED: "Esta conta está desativada.",
  ACCOUNT_TENANT_SHARED: "Esta conta pertence a um workspace compartilhado.",
  TOKEN_INVALID: "Esse link expirou ou já foi usado.",
  REFRESH_TOKEN_INVALID: "Sua sessão expirou. Entre novamente.",
  RATE_LIMITED: "Tentativas demais. Aguarde um momento e tente de novo.",
  VALIDATION_ERROR: "Confira os campos e tente de novo.",
  FORBIDDEN: "Você não tem permissão para isso.",
  IAM_FORBIDDEN: "Você não tem permissão para isso.",
  IAM_AUTHORIZATION_NOT_DECLARED: "Você não tem permissão para isso.",
  IAM_USER_NOT_IN_TENANT: "Esse usuário não pertence a este workspace.",
  IAM_NAME_TAKEN: "Já existe algo com esse nome.",
  IAM_GROUP_NOT_FOUND: "Grupo não encontrado.",
  IAM_POLICY_NOT_FOUND: "Política não encontrada.",
  IAM_INVALID_DOCUMENT: "O documento da política é inválido.",
  MEETING_NOT_FOUND: "Reunião não encontrada.",
  CHAT_SESSION_NOT_FOUND: "Conversa não encontrada.",
  CANNOT_REPROCESS: "Esta reunião não pode ser reanalisada agora.",
  EMPTY_TRANSCRIPT: "A transcrição está vazia.",
  FILE_TOO_LARGE: "O arquivo é grande demais.",
  TRANSCRIPT_TOO_LARGE: "A transcrição é grande demais.",
  UNSUPPORTED_TRANSCRIPT_FORMAT: "Formato de transcrição não suportado.",
  SPLIT_UNSUPPORTED_FORMAT: "A divisão automática só funciona com .txt por enquanto.",
  ANALYSIS_WORKER_UNAVAILABLE: "A análise está indisponível no momento. Tente de novo em instantes.",
  ANALYSIS_INVALID_RESPONSE: "A análise voltou incompleta. Tente de novo.",
  ANALYSIS_TRANSCRIPT_MISSING: "A transcrição desta reunião não foi encontrada.",
  ANALYSIS_MEETING_NOT_FOUND: "Reunião não encontrada.",
  INTEGRATION_NOT_CONFIGURED: "Essa integração não está configurada.",
  INTEGRATION_INVALID_STATE: "O retorno da integração não confere. Tente conectar de novo.",
  CONFLICT: "Esse recurso já existe.",
};
