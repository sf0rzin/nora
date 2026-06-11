"use client";

/**
 * NORA Flows — painel direito do editor (~300px).
 *
 * Com nó selecionado: formulário de parâmetros do bloco + remover nó.
 * Sem seleção: tabs "Fluxo" (resumo + dicas) e "Execuções" (histórico real,
 * com log linha a linha — só em fluxo salvo).
 */
import { useRef } from "react";

import type {
  WorkflowExecutionResponse,
  WorkflowExecutionStatus,
} from "@/lib/api/types";

import { IconeKind, KIND_META, metaDoBloco, PLACEHOLDERS_EMAIL } from "./catalogo";
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

/** Formulário do send_email: destinatário + assunto + corpo + placeholders. */
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
  // Último campo de texto focado — destino dos placeholders clicados.
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
      // devolve o foco e posiciona o cursor depois do token inserido
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
          placeholder="Deixe vazio para a NORA enviar o relatório-resumo padrão."
          value={valorTexto(no.data.params, "body")}
          onChange={(e) => onChange("body", e.target.value)}
          onFocus={() => {
            ultimoFoco.current = "body";
          }}
        />
        <span className="field-help">
          Sem assunto e corpo, a NORA envia um relatório-resumo padrão da reunião.
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

/** Formulário de parâmetros por tipo de bloco. */
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

  return (
    <p style={{ fontSize: 12.5, color: "var(--muted)", margin: 0 }}>
      Bloco sem parâmetros configuráveis.
    </p>
  );
}

/** Tab "Fluxo": resumo do grafo + dicas de uso do builder. */
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

/** Tab "Execuções": histórico real com log expandível. */
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
  // Nó selecionado → parâmetros do bloco.
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
            <IconeKind kind={no.data.kind} />
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

  // Sem seleção → tabs Fluxo / Execuções.
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
