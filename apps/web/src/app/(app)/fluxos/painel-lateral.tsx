"use client";

/**
 * NORA Flows — right panel of the editor (~300px).
 *
 * With a node selected: block parameters form + remove node.
 * Without selection: tabs "Fluxo" (summary + tips) and "Execuções" (real history,
 * with line-by-line log — only on a saved flow).
 */
import Link from "next/link";
import type { Route } from "next";
import { useRef, type ChangeEvent } from "react";

import type {
  WorkflowExecutionResponse,
  WorkflowExecutionStatus,
} from "@/lib/api/types";

import {
  ehRepoGitHub,
  ehWebhookDiscord,
  IconeKind,
  KIND_META,
  metaDoBloco,
  PLACEHOLDERS_EMAIL,
} from "./catalogo";
import type { NoRF } from "./no-bloco";
import { horaLog, tempoRelativo } from "./tempo-relativo";

export type TabPainel = "fluxo" | "execucoes";

const STATUS_EXEC: Record<WorkflowExecutionStatus, { rotulo: string; cor: string }> = {
  SUCCESS: { rotulo: "Sucesso", cor: "var(--success)" },
  FAILED: { rotulo: "Falhou", cor: "var(--danger)" },
  RUNNING: { rotulo: "Executando…", cor: "var(--accent)" },
};

function valorTexto(params: Record<string, unknown>, chave: string): string {
  const v = params[chave];
  return typeof v === "string" ? v : v == null ? "" : String(v);
}

/** Note for OAuth blocks: requires the provider connected in the integrations hub. */
function NotaRequerIntegracao({ provedor }: { provedor: string }) {
  return (
    <div className="notice" style={{ fontSize: 12, lineHeight: 1.5 }}>
      Requer {provedor} conectado em{" "}
      <Link
        href={"/integracoes" as Route}
        style={{ color: "var(--accent-ink)", textDecoration: "underline", textUnderlineOffset: 2 }}
      >
        Integrações
      </Link>
      .
    </div>
  );
}

/** send_email/gmail_send_email/outlook_send_email form: recipient + subject + body + placeholders. */
function FormEmail({
  no,
  mostrarErros,
  onChange,
}: {
  no: NoRF;
  mostrarErros: boolean;
  onChange: (chave: string, valor: unknown) => void;
}) {
  const subjectRef = useRef<HTMLInputElement | null>(null);
  const bodyRef = useRef<HTMLTextAreaElement | null>(null);
  // Last focused text field — destination of the clicked placeholders.
  const ultimoFoco = useRef<"subject" | "body">("body");

  const to = valorTexto(no.data.params, "to");
  const toInvalido = mostrarErros && !to.includes("@");

  function inserirPlaceholder(token: string) {
    const campo = ultimoFoco.current;
    const el = campo === "subject" ? subjectRef.current : bodyRef.current;
    const atual = valorTexto(no.data.params, campo);
    if (el) {
      const ini = el.selectionStart ?? atual.length;
      const fim = el.selectionEnd ?? atual.length;
      onChange(campo, atual.slice(0, ini) + token + atual.slice(fim));
      // gives focus back and puts the cursor after the inserted token
      requestAnimationFrame(() => {
        el.focus();
        const pos = ini + token.length;
        el.setSelectionRange(pos, pos);
      });
    } else {
      onChange(campo, atual + token);
    }
  }

  return (
    <>
      <div className="field">
        <label className="field-label" htmlFor="flows-email-to">
          Para <span className="req">*</span>
        </label>
        <input
          id="flows-email-to"
          className="input"
          type="email"
          placeholder="ana@empresa.com"
          value={to}
          onChange={(e) => onChange("to", e.target.value)}
          style={toInvalido ? { borderColor: "var(--danger)" } : undefined}
        />
        {toInvalido ? (
          <span className="field-help is-err">Informe um e-mail válido (precisa conter @).</span>
        ) : (
          <span className="field-help">Quem recebe o e-mail quando o fluxo executar.</span>
        )}
      </div>

      <div className="field">
        <label className="field-label" htmlFor="flows-email-subject">
          Assunto
        </label>
        <input
          id="flows-email-subject"
          ref={subjectRef}
          className="input"
          type="text"
          placeholder="Resumo: {{meeting.title}}"
          value={valorTexto(no.data.params, "subject")}
          onChange={(e) => onChange("subject", e.target.value)}
          onFocus={() => {
            ultimoFoco.current = "subject";
          }}
        />
      </div>

      <div className="field">
        <label className="field-label" htmlFor="flows-email-body">
          Corpo
        </label>
        <textarea
          id="flows-email-body"
          ref={bodyRef}
          className="textarea"
          rows={5}
          placeholder="Deixe vazio para a Nora enviar o relatório-resumo padrão."
          value={valorTexto(no.data.params, "body")}
          onChange={(e) => onChange("body", e.target.value)}
          onFocus={() => {
            ultimoFoco.current = "body";
          }}
        />
        <span className="field-help">
          Sem assunto e corpo, a Nora envia um relatório-resumo padrão da reunião.
        </span>
      </div>

      <div
        style={{
          border: "1px solid var(--border)",
          borderRadius: 9,
          padding: "9px 11px",
          background: "var(--sidebar)",
        }}
      >
        <div className="sec-label" style={{ marginBottom: 7 }}>
          Placeholders
        </div>
        <p style={{ fontSize: 11.5, color: "var(--muted)", margin: "0 0 8px", lineHeight: 1.5 }}>
          Clique pra inserir no assunto ou no corpo — o valor real entra na hora do envio.
        </p>
        <div style={{ display: "flex", flexWrap: "wrap", gap: 5 }}>
          {PLACEHOLDERS_EMAIL.map((p) => (
            <button
              key={p.token}
              type="button"
              className="flows-ph"
              title={p.dica}
              onClick={() => inserirPlaceholder(p.token)}
            >
              {p.token}
            </button>
          ))}
        </div>
      </div>
    </>
  );
}

/**
 * calendar_create_event and mscalendar_create_event form: title +
 * relative scheduling. All params are optional — the backend applies the
 * defaults at execution time (tomorrow at 10h, 30 minutes), so empty fields do
 * not block saving. The provider only changes the integration note (Google/Microsoft).
 */
function FormEvento({
  no,
  provedor,
  onChange,
}: {
  no: NoRF;
  provedor: string;
  onChange: (chave: string, valor: unknown) => void;
}) {
  function numero(chave: string): number | "" {
    const v = no.data.params[chave];
    return typeof v === "number" && Number.isFinite(v) ? v : "";
  }
  function aoMudarNumero(chave: string) {
    return (e: ChangeEvent<HTMLInputElement>) => {
      const n = e.target.value === "" ? undefined : Number(e.target.value);
      onChange(chave, typeof n === "number" && Number.isFinite(n) ? n : undefined);
    };
  }

  return (
    <>
      <NotaRequerIntegracao provedor={provedor} />

      <div className="field">
        <label className="field-label" htmlFor="flows-evento-titulo">
          Título
        </label>
        <input
          id="flows-evento-titulo"
          className="input"
          type="text"
          placeholder="Follow-up: {{meeting.title}}"
          value={valorTexto(no.data.params, "title")}
          onChange={(e) => onChange("title", e.target.value)}
        />
        <span className="field-help">
          Vazio usa o padrão {"“Follow-up: {{meeting.title}}”"} — o placeholder vira o título real
          da reunião na hora.
        </span>
      </div>

      <div className="field">
        <label className="field-label" htmlFor="flows-evento-dias">
          Começa em (dias)
        </label>
        <input
          id="flows-evento-dias"
          className="input"
          type="number"
          min={0}
          step={1}
          placeholder="1"
          value={numero("startInDays")}
          onChange={aoMudarNumero("startInDays")}
        />
        <span className="field-help">Daqui a quantos dias o evento começa (1 = amanhã).</span>
      </div>

      <div className="field">
        <label className="field-label" htmlFor="flows-evento-hora">
          Hora (0–23)
        </label>
        <input
          id="flows-evento-hora"
          className="input"
          type="number"
          min={0}
          max={23}
          step={1}
          placeholder="10"
          value={numero("hour")}
          onChange={aoMudarNumero("hour")}
        />
        <span className="field-help">Horário de São Paulo.</span>
      </div>

      <div className="field">
        <label className="field-label" htmlFor="flows-evento-duracao">
          Duração (minutos)
        </label>
        <input
          id="flows-evento-duracao"
          className="input"
          type="number"
          min={1}
          step={5}
          placeholder="30"
          value={numero("durationMinutes")}
          onChange={aoMudarNumero("durationMinutes")}
        />
        <span className="field-help">Vazio usa 30 minutos.</span>
      </div>
    </>
  );
}

/** call_webhook form: just the target URL (HTTPS, internal addresses blocked). */
function FormWebhook({
  no,
  mostrarErros,
  onChange,
}: {
  no: NoRF;
  mostrarErros: boolean;
  onChange: (chave: string, valor: unknown) => void;
}) {
  const url = valorTexto(no.data.params, "url");
  const invalida = mostrarErros && !url.trim().startsWith("https://");

  return (
    <div className="field">
      <label className="field-label" htmlFor="flows-webhook-url">
        URL do webhook <span className="req">*</span>
      </label>
      <input
        id="flows-webhook-url"
        className="input"
        type="url"
        placeholder="https://hooks.exemplo.com/nora"
        value={url}
        onChange={(e) => onChange("url", e.target.value)}
        style={invalida ? { borderColor: "var(--danger)" } : undefined}
      />
      {invalida ? (
        <span className="field-help is-err">Informe uma URL https:// — http:// é bloqueado.</span>
      ) : (
        <span className="field-help">
          Recebe um POST em JSON com evento, reunião, resumo, contagens, scores e action items.
          Apenas HTTPS; endereços internos/privados são bloqueados.
        </span>
      )}
    </div>
  );
}

/** discord_post_message form: channel webhook URL. */
function FormDiscord({
  no,
  mostrarErros,
  onChange,
}: {
  no: NoRF;
  mostrarErros: boolean;
  onChange: (chave: string, valor: unknown) => void;
}) {
  const webhookUrl = valorTexto(no.data.params, "webhookUrl");
  const invalida = mostrarErros && !ehWebhookDiscord(webhookUrl);

  return (
    <div className="field">
      <label className="field-label" htmlFor="flows-discord-webhook">
        Webhook do canal <span className="req">*</span>
      </label>
      <input
        id="flows-discord-webhook"
        className="input"
        type="url"
        placeholder="https://discord.com/api/webhooks/…"
        value={webhookUrl}
        onChange={(e) => onChange("webhookUrl", e.target.value)}
        style={invalida ? { borderColor: "var(--danger)" } : undefined}
      />
      {invalida ? (
        <span className="field-help is-err">
          A URL precisa começar com https://discord.com/api/webhooks/.
        </span>
      ) : (
        <span className="field-help">
          No Discord: Configurações do canal → Integrações → Webhooks → Novo webhook → copiar URL.
          A mensagem sai como “Nora” com o resumo da reunião.
        </span>
      )}
    </div>
  );
}

/** github_create_issue form: target repository (owner/nome). */
function FormGitHub({
  no,
  mostrarErros,
  onChange,
}: {
  no: NoRF;
  mostrarErros: boolean;
  onChange: (chave: string, valor: unknown) => void;
}) {
  const repo = valorTexto(no.data.params, "repo");
  const invalido = mostrarErros && !ehRepoGitHub(repo);

  return (
    <>
      <NotaRequerIntegracao provedor="GitHub" />
      <div className="field">
        <label className="field-label" htmlFor="flows-github-repo">
          Repositório <span className="req">*</span>
        </label>
        <input
          id="flows-github-repo"
          className="input"
          type="text"
          placeholder="stratfy/nora"
          value={repo}
          onChange={(e) => onChange("repo", e.target.value)}
          style={invalido ? { borderColor: "var(--danger)" } : undefined}
        />
        {invalido ? (
          <span className="field-help is-err">Use o formato owner/nome (ex.: stratfy/nora).</span>
        ) : (
          <span className="field-help">
            Uma issue por action item da reunião, com a label “nora”.
          </span>
        )}
      </div>
    </>
  );
}

/** notion_create_page form: parent page ID. */
function FormNotion({
  no,
  mostrarErros,
  onChange,
}: {
  no: NoRF;
  mostrarErros: boolean;
  onChange: (chave: string, valor: unknown) => void;
}) {
  const parentPageId = valorTexto(no.data.params, "parentPageId");
  const invalido = mostrarErros && !parentPageId.trim();

  return (
    <>
      <NotaRequerIntegracao provedor="Notion" />
      <div className="field">
        <label className="field-label" htmlFor="flows-notion-pagina">
          ID da página pai <span className="req">*</span>
        </label>
        <input
          id="flows-notion-pagina"
          className="input"
          type="text"
          placeholder="1f2d3c4b5a69708192a3b4c5d6e7f809"
          value={parentPageId}
          onChange={(e) => onChange("parentPageId", e.target.value)}
          style={invalido ? { borderColor: "var(--danger)" } : undefined}
        />
        {invalido ? (
          <span className="field-help is-err">
            Informe o ID da página pai — a página nova nasce dentro dela.
          </span>
        ) : (
          <span className="field-help">
            É o código no fim da URL da página (Compartilhar → Copiar link). A página precisa estar
            compartilhada com a integração Nora.
          </span>
        )}
      </div>
    </>
  );
}

/** todoist_create_task form: no parameters — just the connection note. */
function FormTodoist() {
  return (
    <>
      <NotaRequerIntegracao provedor="Todoist" />
      <p style={{ fontSize: 12.5, color: "var(--muted)", margin: 0, lineHeight: 1.6 }}>
        Sem parâmetros — cria uma tarefa no Inbox da sua conta pra cada action item da reunião.
      </p>
    </>
  );
}

/** linear_create_issue form: team key (optional). */
function FormLinear({
  no,
  onChange,
}: {
  no: NoRF;
  onChange: (chave: string, valor: unknown) => void;
}) {
  return (
    <>
      <NotaRequerIntegracao provedor="Linear" />
      <div className="field">
        <label className="field-label" htmlFor="flows-linear-team">
          Chave do time (opcional)
        </label>
        <input
          id="flows-linear-team"
          className="input"
          type="text"
          placeholder="ENG"
          value={valorTexto(no.data.params, "teamKey")}
          onChange={(e) => onChange("teamKey", e.target.value)}
        />
        <span className="field-help">
          Vazio usa o primeiro time do workspace. Uma issue por action item da reunião.
        </span>
      </div>
    </>
  );
}

/** slack_post_message form: target channel (e.g. #vendas). */
function FormSlack({
  no,
  mostrarErros,
  onChange,
}: {
  no: NoRF;
  mostrarErros: boolean;
  onChange: (chave: string, valor: unknown) => void;
}) {
  const channel = valorTexto(no.data.params, "channel");
  const invalido = mostrarErros && !channel.trim();

  return (
    <>
      <NotaRequerIntegracao provedor="Slack" />
      <div className="field">
        <label className="field-label" htmlFor="flows-slack-canal">
          Canal <span className="req">*</span>
        </label>
        <input
          id="flows-slack-canal"
          className="input"
          type="text"
          placeholder="#vendas"
          value={channel}
          onChange={(e) => onChange("channel", e.target.value)}
          style={invalido ? { borderColor: "var(--danger)" } : undefined}
        />
        {invalido ? (
          <span className="field-help is-err">Informe o canal de destino (ex.: #vendas).</span>
        ) : (
          <span className="field-help">
            A mensagem sai com título, resumo e link da reunião. Em canal privado, convide o bot
            antes (/invite).
          </span>
        )}
      </div>
    </>
  );
}

/** telegram_send_message form: no parameters — just the connection note. */
function FormTelegram() {
  return (
    <>
      <NotaRequerIntegracao provedor="Telegram" />
      <p style={{ fontSize: 12.5, color: "var(--muted)", margin: 0, lineHeight: 1.6 }}>
        Sem parâmetros — envia o resumo da reunião (com próximos passos e link) no chat pareado com
        o bot da Nora.
      </p>
    </>
  );
}

/** trello_create_card form: target list ID. */
function FormTrello({
  no,
  mostrarErros,
  onChange,
}: {
  no: NoRF;
  mostrarErros: boolean;
  onChange: (chave: string, valor: unknown) => void;
}) {
  const listId = valorTexto(no.data.params, "listId");
  const invalido = mostrarErros && !listId.trim();

  return (
    <>
      <NotaRequerIntegracao provedor="Trello" />
      <div className="field">
        <label className="field-label" htmlFor="flows-trello-lista">
          ID da lista <span className="req">*</span>
        </label>
        <input
          id="flows-trello-lista"
          className="input"
          type="text"
          placeholder="5f2d3c4b5a69708192a3b4c5"
          value={listId}
          onChange={(e) => onChange("listId", e.target.value)}
          style={invalido ? { borderColor: "var(--danger)" } : undefined}
        />
        {invalido ? (
          <span className="field-help is-err">
            Informe o ID da lista — os cards nascem dentro dela.
          </span>
        ) : (
          <span className="field-help">
            Pra achar o ID: abra o board e acrescente .json no fim da URL — cada lista aparece com
            seu id. Um card por action item da reunião.
          </span>
        )}
      </div>
    </>
  );
}

/** Parameters form per block type. */
function FormParams({
  no,
  mostrarErros,
  onChange,
}: {
  no: NoRF;
  mostrarErros: boolean;
  onChange: (chave: string, valor: unknown) => void;
}) {
  const t = no.data.blockType;

  if (t === "meeting.analysis_completed") {
    return (
      <p style={{ fontSize: 12.5, color: "var(--muted)", margin: 0, lineHeight: 1.6 }}>
        Este gatilho dispara automaticamente sempre que a análise de uma reunião do seu workspace
        termina. Não tem parâmetros — conecte condições e ações à direita dele.
      </p>
    );
  }

  if (t === "productivity_score_below" || t === "customer_confidence_below") {
    const rotulo = t === "productivity_score_below" ? "Productivity Score" : "Customer Confidence";
    const v = no.data.params.value;
    return (
      <div className="field">
        <label className="field-label" htmlFor="flows-param-value">
          Limite (0–100) <span className="req">*</span>
        </label>
        <input
          id="flows-param-value"
          className="input"
          type="number"
          min={0}
          max={100}
          step={1}
          value={typeof v === "number" ? v : ""}
          onChange={(e) => {
            const n = e.target.value === "" ? undefined : Number(e.target.value);
            onChange("value", typeof n === "number" && Number.isFinite(n) ? n : undefined);
          }}
        />
        <span className="field-help">
          O fluxo segue por aqui só quando o {rotulo} da reunião ficar <strong>abaixo</strong> desse
          valor.
        </span>
      </div>
    );
  }

  if (t === "tag_equals") {
    return (
      <div className="field">
        <label className="field-label" htmlFor="flows-param-tag">
          Tag <span className="req">*</span>
        </label>
        <input
          id="flows-param-tag"
          className="input"
          type="text"
          placeholder="renovação"
          value={valorTexto(no.data.params, "value")}
          onChange={(e) => onChange("value", e.target.value)}
        />
        <span className="field-help">Compara com as tags da reunião (igualdade exata).</span>
      </div>
    );
  }

  if (t === "priority_equals") {
    return (
      <div className="field">
        <label className="field-label" htmlFor="flows-param-prio">
          Prioridade <span className="req">*</span>
        </label>
        <select
          id="flows-param-prio"
          className="select"
          value={valorTexto(no.data.params, "value") || "HIGH"}
          onChange={(e) => onChange("value", e.target.value)}
        >
          <option value="HIGH">Alta</option>
          <option value="MEDIUM">Média</option>
          <option value="LOW">Baixa</option>
        </select>
        <span className="field-help">
          Segue quando a reunião tiver ao menos um action item com essa prioridade.
        </span>
      </div>
    );
  }

  if (t === "send_email") {
    return <FormEmail no={no} mostrarErros={mostrarErros} onChange={onChange} />;
  }

  if (t === "gmail_send_email") {
    return (
      <>
        <NotaRequerIntegracao provedor="Google" />
        <FormEmail no={no} mostrarErros={mostrarErros} onChange={onChange} />
      </>
    );
  }

  if (t === "outlook_send_email") {
    return (
      <>
        <NotaRequerIntegracao provedor="Microsoft" />
        <FormEmail no={no} mostrarErros={mostrarErros} onChange={onChange} />
      </>
    );
  }

  if (t === "calendar_create_event") {
    return <FormEvento no={no} provedor="Google" onChange={onChange} />;
  }

  if (t === "mscalendar_create_event") {
    return <FormEvento no={no} provedor="Microsoft" onChange={onChange} />;
  }

  if (t === "call_webhook") {
    return <FormWebhook no={no} mostrarErros={mostrarErros} onChange={onChange} />;
  }

  if (t === "discord_post_message") {
    return <FormDiscord no={no} mostrarErros={mostrarErros} onChange={onChange} />;
  }

  if (t === "github_create_issue") {
    return <FormGitHub no={no} mostrarErros={mostrarErros} onChange={onChange} />;
  }

  if (t === "notion_create_page") {
    return <FormNotion no={no} mostrarErros={mostrarErros} onChange={onChange} />;
  }

  if (t === "todoist_create_task") {
    return <FormTodoist />;
  }

  if (t === "linear_create_issue") {
    return <FormLinear no={no} onChange={onChange} />;
  }

  if (t === "slack_post_message") {
    return <FormSlack no={no} mostrarErros={mostrarErros} onChange={onChange} />;
  }

  if (t === "telegram_send_message") {
    return <FormTelegram />;
  }

  if (t === "trello_create_card") {
    return <FormTrello no={no} mostrarErros={mostrarErros} onChange={onChange} />;
  }

  return (
    <p style={{ fontSize: 12.5, color: "var(--muted)", margin: 0 }}>
      Bloco sem parâmetros configuráveis.
    </p>
  );
}

/** Tab "Fluxo": graph summary + builder usage tips. */
function TabFluxo({ nos }: { nos: NoRF[] }) {
  const gatilho = nos.find((n) => n.data.kind === "trigger");
  const nomeGatilho = gatilho ? metaDoBloco(gatilho.data.blockType)?.nome ?? "—" : null;
  const nCond = nos.filter((n) => n.data.kind === "condition").length;
  const nAcoes = nos.filter((n) => n.data.kind === "action").length;

  return (
    <div style={{ padding: 14, display: "flex", flexDirection: "column", gap: 16 }}>
      <div>
        <div className="sec-label" style={{ marginBottom: 8 }}>
          Resumo
        </div>
        <div style={{ display: "flex", flexDirection: "column", gap: 7, fontSize: 12.5 }}>
          <div style={{ display: "flex", justifyContent: "space-between", gap: 10 }}>
            <span style={{ color: "var(--muted)" }}>Gatilho</span>
            <span style={{ color: nomeGatilho ? "var(--ink)" : "var(--danger)", textAlign: "right" }}>
              {nomeGatilho ?? "nenhum — adicione pela paleta"}
            </span>
          </div>
          <div style={{ display: "flex", justifyContent: "space-between", gap: 10 }}>
            <span style={{ color: "var(--muted)" }}>Condições</span>
            <span>{nCond}</span>
          </div>
          <div style={{ display: "flex", justifyContent: "space-between", gap: 10 }}>
            <span style={{ color: "var(--muted)" }}>Ações</span>
            <span style={{ color: nAcoes > 0 ? "var(--ink)" : "var(--danger)" }}>
              {nAcoes > 0 ? nAcoes : "nenhuma — obrigatória"}
            </span>
          </div>
        </div>
      </div>

      <div>
        <div className="sec-label" style={{ marginBottom: 8 }}>
          Como usar
        </div>
        <ul
          style={{
            margin: 0,
            paddingLeft: 16,
            display: "flex",
            flexDirection: "column",
            gap: 6,
            fontSize: 12,
            color: "var(--muted)",
            lineHeight: 1.55,
          }}
        >
          <li>Clique num bloco da paleta pra adicionar ao canvas.</li>
          <li>Arraste da bolinha direita de um nó até a esquerda do próximo pra conectar.</li>
          <li>Clique num nó pra editar os parâmetros aqui no painel.</li>
          <li>
            <kbd className="kbd">Backspace</kbd> remove o nó ou a conexão selecionada.
          </li>
          <li>Condições são opcionais — gatilho direto na ação também vale.</li>
        </ul>
      </div>

      <div className="notice" style={{ fontSize: 12 }}>
        “Testar” executa o fluxo de verdade contra a sua última reunião analisada — inclusive o
        envio de e-mail.
      </div>
    </div>
  );
}

/** Tab "Execuções": real history with expandable log. */
function TabExecucoes({
  execucoes,
  carregando,
  erro,
  onRecarregar,
  expandida,
  onExpandir,
}: {
  execucoes: WorkflowExecutionResponse[] | null;
  carregando: boolean;
  erro: string | null;
  onRecarregar: () => void;
  expandida: string | null;
  onExpandir: (id: string | null) => void;
}) {
  return (
    <div style={{ padding: 14, display: "flex", flexDirection: "column", gap: 10 }}>
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
        <div className="sec-label">Últimas execuções</div>
        <button
          type="button"
          className="btn btn-ghost btn-sm"
          onClick={onRecarregar}
          disabled={carregando}
        >
          {carregando ? "Atualizando…" : "Atualizar"}
        </button>
      </div>

      {erro && (
        <div className="notice notice--danger" style={{ fontSize: 12 }}>
          {erro}
        </div>
      )}

      {carregando && execucoes === null ? (
        <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
          {[0, 1, 2].map((i) => (
            <div key={i} className="skel" style={{ height: 38, borderRadius: 10 }} />
          ))}
        </div>
      ) : (execucoes ?? []).length === 0 && !erro ? (
        <p style={{ fontSize: 12.5, color: "var(--muted)", margin: 0, lineHeight: 1.55 }}>
          Nenhuma execução ainda. Clique em “Testar” pra rodar o fluxo agora, ou aguarde a próxima
          reunião analisada.
        </p>
      ) : (
        (execucoes ?? []).map((ex) => {
          const meta = STATUS_EXEC[ex.status] ?? STATUS_EXEC.RUNNING;
          const aberta = expandida === ex.id;
          return (
            <div key={ex.id} className="flows-exec">
              <button
                type="button"
                className="flows-exec-head"
                onClick={() => onExpandir(aberta ? null : ex.id)}
                aria-expanded={aberta}
              >
                {ex.status === "RUNNING" ? (
                  <span className="status-dot status-dot--processing" />
                ) : (
                  <span className="status-dot" style={{ background: meta.cor }} />
                )}
                <span style={{ flex: 1, minWidth: 0 }}>
                  <span style={{ display: "block", fontSize: 12.5, color: "var(--ink)" }}>
                    {meta.rotulo}
                  </span>
                  <span style={{ display: "block", fontSize: 11, color: "var(--muted)" }}>
                    {metaDoBloco(ex.eventType)?.nome ?? ex.eventType} · {tempoRelativo(ex.createdAt)}
                  </span>
                </span>
                <svg
                  width="12"
                  height="12"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="var(--muted)"
                  strokeWidth="2"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  style={{ transform: aberta ? "rotate(180deg)" : "none", transition: "transform 140ms ease", flexShrink: 0 }}
                >
                  <path d="m6 9 6 6 6-6" />
                </svg>
              </button>
              {aberta && (
                <div className="flows-exec-log">
                  {ex.log.length === 0 ? (
                    <span style={{ fontSize: 12, color: "var(--muted)" }}>Sem linhas de log.</span>
                  ) : (
                    ex.log.map((linha, i) => (
                      <div key={i} className={`flows-exec-linha${linha.level === "error" ? " is-err" : ""}`}>
                        <span className="hora">{horaLog(linha.at)}</span>
                        <span>{linha.message}</span>
                      </div>
                    ))
                  )}
                </div>
              )}
            </div>
          );
        })
      )}
    </div>
  );
}

export function PainelLateral({
  no,
  nos,
  fluxoSalvo,
  tab,
  onTab,
  tentouSalvar,
  onAtualizarParam,
  onRemoverNo,
  execucoes,
  carregandoExec,
  erroExec,
  onRecarregarExec,
  expandida,
  onExpandir,
}: {
  no: NoRF | null;
  nos: NoRF[];
  fluxoSalvo: boolean;
  tab: TabPainel;
  onTab: (t: TabPainel) => void;
  tentouSalvar: boolean;
  onAtualizarParam: (id: string, chave: string, valor: unknown) => void;
  onRemoverNo: (id: string) => void;
  execucoes: WorkflowExecutionResponse[] | null;
  carregandoExec: boolean;
  erroExec: string | null;
  onRecarregarExec: () => void;
  expandida: string | null;
  onExpandir: (id: string | null) => void;
}) {
  // Selected node → block parameters.
  if (no) {
    const meta = metaDoBloco(no.data.blockType);
    const kindMeta = KIND_META[no.data.kind];
    return (
      <aside className="flows-panel" aria-label="Parâmetros do nó">
        <div style={{ padding: "14px 14px 0" }}>
          <div
            style={{
              display: "flex",
              alignItems: "center",
              gap: 6,
              fontSize: 10.5,
              fontWeight: 600,
              letterSpacing: "0.08em",
              textTransform: "uppercase",
              color: kindMeta.cor,
              marginBottom: 4,
            }}
          >
            {meta?.Icone ? <meta.Icone /> : <IconeKind kind={no.data.kind} />}
            {kindMeta.rotulo}
          </div>
          <h2
            style={{
              fontFamily: "var(--display)",
              fontSize: 15.5,
              fontWeight: 500,
              letterSpacing: "-0.012em",
              margin: 0,
              color: "var(--ink)",
            }}
          >
            {meta?.nome ?? no.data.blockType}
          </h2>
          {meta && (
            <p style={{ fontSize: 12, color: "var(--muted)", margin: "4px 0 0", lineHeight: 1.5 }}>
              {meta.descricao}.
            </p>
          )}
        </div>

        <div style={{ padding: 14, display: "flex", flexDirection: "column", gap: 14, flex: 1 }}>
          <FormParams
            no={no}
            mostrarErros={tentouSalvar}
            onChange={(chave, valor) => onAtualizarParam(no.id, chave, valor)}
          />
        </div>

        <div style={{ padding: 14, borderTop: "1px solid var(--border)" }}>
          <button
            type="button"
            className="btn btn-ghost"
            style={{ width: "100%", color: "var(--danger)" }}
            onClick={() => onRemoverNo(no.id)}
          >
            Remover nó
          </button>
        </div>
      </aside>
    );
  }

  // No selection → Fluxo / Execuções tabs.
  return (
    <aside className="flows-panel" aria-label="Painel do fluxo">
      <div className="flows-tabs" role="tablist">
        <button
          type="button"
          role="tab"
          aria-selected={tab === "fluxo"}
          className={`flows-tab${tab === "fluxo" ? " is-active" : ""}`}
          onClick={() => onTab("fluxo")}
        >
          Fluxo
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === "execucoes"}
          className={`flows-tab${tab === "execucoes" ? " is-active" : ""}`}
          disabled={!fluxoSalvo}
          title={fluxoSalvo ? undefined : "Salve o fluxo para ver execuções"}
          onClick={() => onTab("execucoes")}
        >
          Execuções
        </button>
      </div>

      {tab === "fluxo" || !fluxoSalvo ? (
        <TabFluxo nos={nos} />
      ) : (
        <TabExecucoes
          execucoes={execucoes}
          carregando={carregandoExec}
          erro={erroExec}
          onRecarregar={onRecarregarExec}
          expandida={expandida}
          onExpandir={onExpandir}
        />
      )}
    </aside>
  );
}
