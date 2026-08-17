"use client";

/**
 * NORA Flows — visual flow editor (n8n-style canvas).
 *
 * Same component for /flows/new (workflowId null) and /flows/[id].
 * Layout: topbar (name on the left; Active + Test/Save/Delete grouped on the
 * right) over 3 columns — block palette | React Flow canvas | parameters/
 * executions panel.
 *
 * 100% real persistence: POST/PUT /workflows with the serialized definition
 * (kind/type/params nodes + canvas position) and POST /workflows/{id}/test
 * for the Test button (synchronous execution with log).
 */
import Link from "next/link";
import type { Route } from "next";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useState } from "react";

import {
  addEdge,
  Background,
  BackgroundVariant,
  Controls,
  ReactFlow,
  ReactFlowProvider,
  useEdgesState,
  useNodesState,
  type Connection,
  type Edge,
  type EdgeChange,
  type NodeChange,
  type OnSelectionChangeParams,
} from "@xyflow/react";

import {
  ApiRequestError,
  createWorkflow,
  deleteWorkflow,
  getWorkflow,
  listWorkflowExecutions,
  testWorkflow,
  updateWorkflow,
  type WorkflowDefinition,
  type WorkflowExecutionResponse,
} from "@/lib/api/client";

import {
  CATALOG,
  isGitHubRepo,
  isDiscordWebhook,
  isValidSchedule,
  blockMeta,
  type BlockMeta,
} from "./catalog";
import { BlockNode, type RFNode } from "./block-node";
import { SidePanel, type PanelTab } from "./side-panel";
import { BlockPalette } from "./block-palette";
import { shortTime } from "./relative-time";

// nodeTypes needs a stable reference (outside the component) — otherwise
// React Flow re-registers the types on every render.
const NODE_TYPES = { bloco: BlockNode };

const GATILHO_PADRAO = CATALOG.find((b) => b.kind === "trigger")!;

/**
 * Creates a new RF node from a catalog block. The id is injectable:
 * nodes added by the user use crypto.randomUUID(), but the initial
 * trigger of /flows/new uses a fixed id — the initial state also runs in
 * SSR and a random id would diverge between server and hydration.
 */
function novoNo(
  bloco: BlockMeta,
  position: { x: number; y: number },
  id: string,
  selecionado = false,
): RFNode {
  return {
    id,
    type: "bloco",
    position,
    selected: selecionado,
    data: { kind: bloco.kind, blockType: bloco.type, params: { ...bloco.defaultParams } },
  };
}

/** backend definition → React Flow nodes/edges. */
function paraCanvas(def: WorkflowDefinition): { nos: RFNode[]; arestas: Edge[] } {
  const nos: RFNode[] = def.nodes.map((n, i) => ({
    id: n.id,
    type: "bloco",
    // layout fallback for old definitions without a saved position
    position: n.position ?? { x: 60 + i * 260, y: 140 },
    data: { kind: n.kind, blockType: n.type, params: { ...(n.params ?? {}) } },
  }));
  const arestas: Edge[] = def.edges.map((e) => ({ id: e.id, source: e.source, target: e.target }));
  return { nos, arestas };
}

/** Removes empty params before persisting (e.g. blank subject/body). */
function limparParams(params: Record<string, unknown>): Record<string, unknown> | undefined {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null) continue;
    if (typeof v === "string" && v.trim() === "") continue;
    out[k] = v;
  }
  return Object.keys(out).length > 0 ? out : undefined;
}

/** React Flow nodes/edges → definition the engine understands. */
function paraDefinicao(nos: RFNode[], arestas: Edge[]): WorkflowDefinition {
  return {
    nodes: nos.map((n) => ({
      id: n.id,
      kind: n.data.kind,
      type: n.data.blockType,
      params: limparParams(n.data.params),
      position: { x: Math.round(n.position.x), y: Math.round(n.position.y) },
    })),
    edges: arestas.map((e) => ({ id: e.id, source: e.source, target: e.target })),
  };
}

type Aviso = { tipo: "erro" | "ok"; msg: string };

function FlowEditorInterno({ workflowId }: { workflowId: string | null }) {
  const router = useRouter();
  const fluxoSalvo = workflowId !== null;

  // ── Document state ──
  const [nome, setNome] = useState("");
  const [ativo, setAtivo] = useState(true);
  const [nodes, setNodes, onNodesChange] = useNodesState<RFNode>(
    fluxoSalvo ? [] : [novoNo(GATILHO_PADRAO, { x: 60, y: 140 }, "no-gatilho-inicial")],
  );
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
  const [sujo, setSujo] = useState(false);

  // ── Loading state (existing flow) ──
  const [carregando, setCarregando] = useState(fluxoSalvo);
  const [erroCarga, setErroCarga] = useState<string | null>(null);

  // ── UI state ──
  const [selId, setSelId] = useState<string | null>(null);
  const [tab, setTab] = useState<PanelTab>("flow");
  const [aviso, setAviso] = useState<Aviso | null>(null);
  const [salvoAs, setSalvoAs] = useState<string | null>(null);
  const [salvando, setSalvando] = useState(false);
  const [testando, setTestando] = useState(false);
  const [tentouSalvar, setTentouSalvar] = useState(false);
  const [confirmaExcluir, setConfirmaExcluir] = useState(false);
  const [excluindo, setExcluindo] = useState(false);

  // ── Executions ──
  const [execucoes, setExecucoes] = useState<WorkflowExecutionResponse[] | null>(null);
  const [carregandoExec, setCarregandoExec] = useState(false);
  const [erroExec, setErroExec] = useState<string | null>(null);
  const [expandida, setExpandida] = useState<string | null>(null);

  // Loads the existing flow (name, state and graph) from the backend.
  useEffect(() => {
    if (!workflowId) return;
    let vivo = true;
    getWorkflow(workflowId)
      .then((w) => {
        if (!vivo) return;
        setNome(w.name);
        setAtivo(w.active);
        const { nos, arestas } = paraCanvas(w.definition);
        setNodes(nos);
        setEdges(arestas);
        setCarregando(false);
      })
      .catch((e) => {
        if (!vivo) return;
        if (e instanceof ApiRequestError && e.status === 404) {
          setErroCarga("Fluxo não encontrado — pode ter sido excluído.");
        } else {
          setErroCarga(e instanceof ApiRequestError ? e.message : "Falha ao carregar o fluxo.");
        }
        setCarregando(false);
      });
    return () => {
      vivo = false;
    };
  }, [workflowId, setNodes, setEdges]);

  // Wrappers that mark the document dirty on real graph changes
  // (selection and dimension measuring do not count as an edit).
  const aoMudarNos = useCallback(
    (changes: NodeChange<RFNode>[]) => {
      if (changes.some((c) => c.type !== "select" && c.type !== "dimensions")) setSujo(true);
      onNodesChange(changes);
    },
    [onNodesChange],
  );
  const aoMudarArestas = useCallback(
    (changes: EdgeChange<Edge>[]) => {
      if (changes.some((c) => c.type !== "select")) setSujo(true);
      onEdgesChange(changes);
    },
    [onEdgesChange],
  );

  const aoConectar = useCallback(
    (conexao: Connection) => {
      if (conexao.source === conexao.target) return; // no self-loop
      setEdges((eds) => addEdge({ ...conexao, id: crypto.randomUUID() }, eds));
      setSujo(true);
    },
    [setEdges],
  );

  const aoSelecionar = useCallback(({ nodes: sel }: OnSelectionChangeParams) => {
    setSelId(sel.length === 1 ? sel[0].id : null);
  }, []);

  const noSelecionado = useMemo(
    () => (selId ? (nodes.find((n) => n.id === selId) ?? null) : null),
    [selId, nodes],
  );
  const hasTrigger = nodes.some((n) => n.data.kind === "trigger");

  /** CLICK on the palette: adds the block near the rightmost node, already selected. */
  function adicionarBloco(bloco: BlockMeta) {
    // id created OUTSIDE the updater: in StrictMode the updater runs 2x and an id
    // generated in there would diverge from what we recorded in the selection.
    const id = crypto.randomUUID();
    setNodes((ns) => {
      let pos = { x: 60, y: 140 };
      if (ns.length > 0) {
        const ref = ns.reduce((a, b) => (b.position.x > a.position.x ? b : a));
        pos = { x: ref.position.x + 260, y: ref.position.y + ((ns.length % 3) - 1) * 36 };
      }
      return [
        ...ns.map((n) => (n.selected ? { ...n, selected: false } : n)),
        novoNo(bloco, pos, id, true),
      ];
    });
    setSelId(id);
    setSujo(true);
  }

  function atualizarParam(id: string, chave: string, valor: unknown) {
    setNodes((ns) =>
      ns.map((n) =>
        n.id === id
          ? ({ ...n, data: { ...n.data, params: { ...n.data.params, [chave]: valor } } } as RFNode)
          : n,
      ),
    );
    setSujo(true);
  }

  function removerNo(id: string) {
    setNodes((ns) => ns.filter((n) => n.id !== id));
    setEdges((eds) => eds.filter((e) => e.source !== id && e.target !== id));
    setSelId(null);
    setSujo(true);
  }

  /** Selects a node programmatically (used to point at a validation error). */
  function focarNo(id: string) {
    setNodes((ns) => ns.map((n) => ({ ...n, selected: n.id === id })));
    setSelId(id);
  }

  /** Client-side validation before hitting the API. Returns the 1st pending item. */
  function validar(): string | null {
    if (!nome.trim()) return "Dê um nome ao fluxo antes de salvar.";
    const gatilhos = nodes.filter((n) => n.data.kind === "trigger");
    if (gatilhos.length === 0)
      return "O fluxo precisa de um gatilho — adicione “Reunião analisada” pela paleta.";
    if (gatilhos.length > 1) return "O fluxo só pode ter um gatilho — remova os extras.";
    // The schedule trigger is the only one with params, and the backend refuses an incomplete one
    // (a schedule it cannot honour is rejected, never accepted and quietly not run).
    const agendaIncompleta = gatilhos.find(
      (n) => n.data.blockType === "schedule.cron" && !isValidSchedule(n.data.params),
    );
    if (agendaIncompleta) {
      focarNo(agendaIncompleta.id);
      return "O gatilho “Em um horário” precisa da frequência e do horário completos.";
    }
    if (!nodes.some((n) => n.data.kind === "action"))
      return "Adicione ao menos uma ação (ex.: “Enviar e-mail”).";
    const tiposEmail = ["send_email", "gmail_send_email", "outlook_send_email"];
    const emailSemDestino = nodes.find((n) => {
      if (!tiposEmail.includes(n.data.blockType)) {
        return false;
      }
      const to = n.data.params.to;
      return typeof to !== "string" || !to.includes("@");
    });
    if (emailSemDestino) {
      focarNo(emailSemDestino.id);
      const nomeBloco = blockMeta(emailSemDestino.data.blockType)?.name ?? "Enviar e-mail";
      return `A ação “${nomeBloco}” precisa de um destinatário válido no campo Para.`;
    }
    const webhookSemUrl = nodes.find((n) => {
      if (n.data.blockType !== "call_webhook") return false;
      const url = n.data.params.url;
      return typeof url !== "string" || !url.trim().startsWith("https://");
    });
    if (webhookSemUrl) {
      focarNo(webhookSemUrl.id);
      return "A ação “Chamar webhook” precisa de uma URL https:// no campo URL do webhook.";
    }
    const discordSemWebhook = nodes.find(
      (n) => n.data.blockType === "discord_post_message" && !isDiscordWebhook(n.data.params.webhookUrl),
    );
    if (discordSemWebhook) {
      focarNo(discordSemWebhook.id);
      return "A ação “Avisar no Discord” precisa da URL do webhook do canal (começa com https://discord.com/api/webhooks/).";
    }
    const githubSemRepo = nodes.find(
      (n) => n.data.blockType === "github_create_issue" && !isGitHubRepo(n.data.params.repo),
    );
    if (githubSemRepo) {
      focarNo(githubSemRepo.id);
      return "A ação “Criar issue no GitHub” precisa do repositório no formato owner/nome (ex.: stratfy/nora).";
    }
    const notionSemPagina = nodes.find((n) => {
      if (n.data.blockType !== "notion_create_page") return false;
      const parentPageId = n.data.params.parentPageId;
      return typeof parentPageId !== "string" || !parentPageId.trim();
    });
    if (notionSemPagina) {
      focarNo(notionSemPagina.id);
      return "A ação “Criar página no Notion” precisa do ID da página pai (código no fim da URL da página).";
    }
    const slackSemCanal = nodes.find((n) => {
      if (n.data.blockType !== "slack_post_message") return false;
      const channel = n.data.params.channel;
      return typeof channel !== "string" || !channel.trim();
    });
    if (slackSemCanal) {
      focarNo(slackSemCanal.id);
      return "A ação “Postar no Slack” precisa do canal de destino no campo Canal (ex.: #vendas).";
    }
    const trelloSemLista = nodes.find((n) => {
      if (n.data.blockType !== "trello_create_card") return false;
      const listId = n.data.params.listId;
      return typeof listId !== "string" || !listId.trim();
    });
    if (trelloSemLista) {
      focarNo(trelloSemLista.id);
      return "A ação “Criar card no Trello” precisa do ID da lista do board (abra o board com .json na URL pra ver os ids).";
    }
    return null;
  }

  async function salvar() {
    setTentouSalvar(true);
    const pendencia = validar();
    if (pendencia) {
      setAviso({ tipo: "erro", msg: pendencia });
      return;
    }
    setAviso(null);
    setSalvando(true);
    const definition = paraDefinicao(nodes, edges);
    try {
      if (workflowId) {
        const w = await updateWorkflow(workflowId, { name: nome.trim(), active: ativo, definition });
        setSalvoAs(shortTime(w.updatedAt));
        setSujo(false);
        setTentouSalvar(false);
      } else {
        const w = await createWorkflow({ name: nome.trim(), active: ativo, definition });
        // switches to the canonical URL of the just-created flow (the editor reloads saved)
        router.replace(`/flows/${w.id}` as Route);
      }
    } catch (e) {
      // 422 WORKFLOW_INVALID_DEFINITION carries an actionable PT-BR message from the engine
      setAviso({
        tipo: "erro",
        msg: e instanceof ApiRequestError ? e.message : "Falha ao salvar o fluxo. Tente de novo.",
      });
    } finally {
      setSalvando(false);
    }
  }

  const recarregarExecucoes = useCallback(() => {
    if (!workflowId) return;
    setCarregandoExec(true);
    setErroExec(null);
    listWorkflowExecutions(workflowId)
      .then((r) => setExecucoes(r))
      .catch((e) =>
        setErroExec(e instanceof ApiRequestError ? e.message : "Falha ao carregar execuções."),
      )
      .finally(() => setCarregandoExec(false));
  }, [workflowId]);

  // First time the Executions tab is opened → loads the history.
  useEffect(() => {
    if (tab === "executions" && execucoes === null && fluxoSalvo) recarregarExecucoes();
  }, [tab, execucoes, fluxoSalvo, recarregarExecucoes]);

  // Success notice disappears on its own; error stays until the user closes it.
  useEffect(() => {
    if (aviso?.tipo !== "ok") return;
    const t = setTimeout(() => setAviso(null), 6000);
    return () => clearTimeout(t);
  }, [aviso]);

  async function testar() {
    if (!workflowId) return;
    setTestando(true);
    setAviso(null);
    try {
      const ex = await testWorkflow(workflowId);
      setExecucoes((curr) => [ex, ...(curr ?? []).filter((e) => e.id !== ex.id)]);
      setExpandida(ex.id);
      setTab("executions");
      // guarantees the full history (if the tab was never opened, only the
      // new execution would be in the list) — background refetch
      recarregarExecucoes();
      // clears the selection so the right panel shows the executions tab
      setNodes((ns) => ns.map((n) => (n.selected ? { ...n, selected: false } : n)));
      setSelId(null);
      setAviso(
        ex.status === "FAILED"
          ? { tipo: "erro", msg: "A execução de teste falhou — confira o log no painel." }
          : { tipo: "ok", msg: "Execução de teste concluída — log no painel à direita." },
      );
    } catch (e) {
      setAviso({
        tipo: "erro",
        msg: e instanceof ApiRequestError ? e.message : "Falha ao executar o teste.",
      });
    } finally {
      setTestando(false);
    }
  }

  async function excluir() {
    if (!workflowId) return;
    setExcluindo(true);
    try {
      await deleteWorkflow(workflowId);
      router.push("/flows" as Route);
    } catch (e) {
      setExcluindo(false);
      setConfirmaExcluir(false);
      setAviso({
        tipo: "erro",
        msg: e instanceof ApiRequestError ? e.message : "Falha ao excluir o fluxo.",
      });
    }
  }

  // ── Page loading/error states ──
  if (erroCarga) {
    return (
      <div className="page">
        <div className="notice notice--danger" style={{ marginBottom: 16 }}>
          {erroCarga}
        </div>
        <Link href={"/flows" as Route} className="btn btn-secondary">
          Voltar pros fluxos
        </Link>
      </div>
    );
  }

  if (carregando) {
    return (
      <div className="flows-editor" aria-busy>
        <div className="flows-topbar">
          <div className="skel" style={{ width: 240, height: 30 }} />
          <span style={{ flex: 1 }} />
          <div className="skel" style={{ width: 84, height: 32 }} />
          <div className="skel" style={{ width: 84, height: 32 }} />
        </div>
        <div className="flows-body">
          <div className="flows-palette">
            {[0, 1, 2, 3].map((i) => (
              <div key={i} className="skel" style={{ height: 52, borderRadius: 9 }} />
            ))}
          </div>
          <div className="flows-canvas" style={{ background: "var(--canvas)" }} />
          <div className="flows-panel" style={{ padding: 14 }}>
            <div className="skel" style={{ height: 120, borderRadius: 10 }} />
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="flows-editor">
      {/* ── Topbar ── */}
      <div className="flows-topbar">
        <Link
          href={"/flows" as Route}
          className="icon-btn"
          aria-label="Voltar pra lista de fluxos"
          title="Fluxos"
          style={{ width: 32, height: 32, flexShrink: 0 }}
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
            <path d="M19 12H5M12 19l-7-7 7-7" />
          </svg>
        </Link>

        <input
          className={`flows-name-input${tentouSalvar && !nome.trim() ? " is-err" : ""}`}
          value={nome}
          onChange={(e) => {
            setNome(e.target.value);
            setSujo(true);
          }}
          placeholder="Nome do fluxo"
          aria-label="Nome do fluxo"
        />

        <span style={{ flex: 1 }} />

        {salvoAs && !sujo && (
          <span style={{ fontSize: 11.5, color: "var(--muted)" }}>Salvo às {salvoAs}</span>
        )}
        {sujo && fluxoSalvo && (
          <span style={{ fontSize: 11.5, color: "var(--muted)" }}>Alterações não salvas</span>
        )}

        {fluxoSalvo &&
          (confirmaExcluir ? (
            <span style={{ display: "inline-flex", alignItems: "center", gap: 8 }}>
              <span style={{ fontSize: 12.5, color: "var(--danger)" }}>Excluir fluxo?</span>
              <button type="button" className="btn btn-danger btn-sm" onClick={excluir} disabled={excluindo}>
                {excluindo ? "Excluindo…" : "Excluir"}
              </button>
              <button
                type="button"
                className="btn btn-ghost btn-sm"
                onClick={() => setConfirmaExcluir(false)}
                disabled={excluindo}
              >
                Cancelar
              </button>
            </span>
          ) : (
            <button
              type="button"
              className="btn btn-ghost"
              style={{ color: "var(--danger)" }}
              onClick={() => setConfirmaExcluir(true)}
            >
              Excluir
            </button>
          ))}

        {/* switch grouped with the primary actions (Test/Save) — it used to
            float next to the name, "in the middle of nowhere" (PO feedback) */}
        <label className="flows-switch" title={ativo ? "Fluxo ativo — roda nos gatilhos" : "Fluxo pausado"}>
          <input
            type="checkbox"
            checked={ativo}
            onChange={(e) => {
              setAtivo(e.target.checked);
              setSujo(true);
            }}
          />
          <span className="track" aria-hidden />
          <span className="estado">{ativo ? "Ativo" : "Pausado"}</span>
        </label>

        <span aria-hidden style={{ width: 1, height: 20, background: "var(--border)", flexShrink: 0 }} />

        <button
          type="button"
          className="btn btn-secondary"
          onClick={testar}
          disabled={!fluxoSalvo || testando}
          title={
            !fluxoSalvo
              ? "Salve o fluxo para testar"
              : sujo
                ? "Testa a última versão salva — salve pra incluir as mudanças recentes"
                : "Executa o fluxo agora contra a sua última reunião analisada"
          }
        >
          {testando ? "Testando…" : "Testar"}
        </button>
        <button type="button" className="btn btn-primary" onClick={salvar} disabled={salvando}>
          {salvando ? "Salvando…" : "Salvar"}
        </button>
      </div>

      {/* ── Body: palette | canvas | panel ── */}
      <div className="flows-body">
        <BlockPalette hasTrigger={hasTrigger} onAdd={adicionarBloco} />

        <div className="flows-canvas">
          {aviso && (
            <div
              className={`notice ${aviso.tipo === "erro" ? "notice--danger" : "notice--accent"}`}
              role={aviso.tipo === "erro" ? "alert" : "status"}
              style={{
                position: "absolute",
                top: 12,
                left: "50%",
                transform: "translateX(-50%)",
                zIndex: 10,
                maxWidth: "min(540px, calc(100% - 32px))",
                display: "flex",
                alignItems: "center",
                gap: 10,
                boxShadow: "0 4px 16px rgba(15, 23, 42, 0.08)",
              }}
            >
              <span style={{ flex: 1 }}>{aviso.msg}</span>
              <button
                type="button"
                onClick={() => setAviso(null)}
                aria-label="Fechar aviso"
                style={{
                  background: "transparent",
                  border: "none",
                  cursor: "pointer",
                  color: "inherit",
                  display: "grid",
                  placeItems: "center",
                  padding: 2,
                  flexShrink: 0,
                }}
              >
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                  <path d="M6 6l12 12M18 6L6 18" />
                </svg>
              </button>
            </div>
          )}

          <ReactFlow
            nodes={nodes}
            edges={edges}
            onNodesChange={aoMudarNos}
            onEdgesChange={aoMudarArestas}
            onConnect={aoConectar}
            onSelectionChange={aoSelecionar}
            nodeTypes={NODE_TYPES}
            fitView
            fitViewOptions={{ padding: 0.3, maxZoom: 1.1 }}
            deleteKeyCode={["Backspace", "Delete"]}
            style={{ background: "var(--canvas)" }}
          >
            <Background
              variant={BackgroundVariant.Dots}
              gap={18}
              size={1.5}
              color="var(--border-strong)"
            />
            <Controls showInteractive={false} position="bottom-left" />
          </ReactFlow>
        </div>

        <SidePanel
          no={noSelecionado}
          nos={nodes}
          fluxoSalvo={fluxoSalvo}
          tab={tab}
          onTab={setTab}
          tentouSalvar={tentouSalvar}
          onAtualizarParam={atualizarParam}
          onRemoverNo={removerNo}
          execucoes={execucoes}
          carregandoExec={carregandoExec}
          erroExec={erroExec}
          onRecarregarExec={recarregarExecucoes}
          expandida={expandida}
          onExpandir={setExpandida}
        />
      </div>
    </div>
  );
}

export function FlowEditor({ workflowId }: { workflowId: string | null }) {
  return (
    <ReactFlowProvider>
      <FlowEditorInterno workflowId={workflowId} />
    </ReactFlowProvider>
  );
}
