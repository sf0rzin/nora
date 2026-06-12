"use client";

/**
 * NORA Flows — editor visual de fluxos (canvas estilo n8n).
 *
 * Mesmo componente pra /fluxos/novo (workflowId null) e /fluxos/[id].
 * Layout: topbar (nome à esquerda; Ativo + Testar/Salvar/Excluir agrupados à
 * direita) sobre 3 colunas — paleta de blocos | canvas React Flow | painel
 * de parâmetros/execuções.
 *
 * Persistência 100% real: POST/PUT /workflows com a definição serializada
 * (nós kind/type/params + posição do canvas) e POST /workflows/{id}/test
 * pro botão Testar (execução síncrona com log).
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

import { CATALOGO, metaDoBloco, type BlocoMeta } from "./catalogo";
import { NoBloco, type NoRF } from "./no-bloco";
import { PainelLateral, type TabPainel } from "./painel-lateral";
import { PaletaBlocos } from "./paleta-blocos";
import { horaCurta } from "./tempo-relativo";

// nodeTypes precisa de referência estável (fora do componente) — senão o
// React Flow re-registra os tipos a cada render.
const NODE_TYPES = { bloco: NoBloco };

const GATILHO_PADRAO = CATALOGO.find((b) => b.kind === "trigger")!;

/**
 * Cria um nó RF novo a partir de um bloco do catálogo. O id é injetável:
 * nós adicionados pelo usuário usam crypto.randomUUID(), mas o gatilho
 * inicial de /fluxos/novo usa id fixo — o estado inicial roda também no
 * SSR e um id aleatório divergiria entre servidor e hidratação.
 */
function novoNo(
  bloco: BlocoMeta,
  position: { x: number; y: number },
  id: string,
  selecionado = false,
): NoRF {
  return {
    id,
    type: "bloco",
    position,
    selected: selecionado,
    data: { kind: bloco.kind, blockType: bloco.type, params: { ...bloco.paramsPadrao } },
  };
}

/** definition do backend → nós/arestas do React Flow. */
function paraCanvas(def: WorkflowDefinition): { nos: NoRF[]; arestas: Edge[] } {
  const nos: NoRF[] = def.nodes.map((n, i) => ({
    id: n.id,
    type: "bloco",
    // fallback de layout pra definições antigas sem posição salva
    position: n.position ?? { x: 60 + i * 260, y: 140 },
    data: { kind: n.kind, blockType: n.type, params: { ...(n.params ?? {}) } },
  }));
  const arestas: Edge[] = def.edges.map((e) => ({ id: e.id, source: e.source, target: e.target }));
  return { nos, arestas };
}

/** Remove params vazios antes de persistir (ex.: subject/body em branco). */
function limparParams(params: Record<string, unknown>): Record<string, unknown> | undefined {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null) continue;
    if (typeof v === "string" && v.trim() === "") continue;
    out[k] = v;
  }
  return Object.keys(out).length > 0 ? out : undefined;
}

/** nós/arestas do React Flow → definition que o engine entende. */
function paraDefinicao(nos: NoRF[], arestas: Edge[]): WorkflowDefinition {
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

function EditorFluxoInterno({ workflowId }: { workflowId: string | null }) {
  const router = useRouter();
  const fluxoSalvo = workflowId !== null;

  // ── Estado do documento ──
  const [nome, setNome] = useState("");
  const [ativo, setAtivo] = useState(true);
  const [nodes, setNodes, onNodesChange] = useNodesState<NoRF>(
    fluxoSalvo ? [] : [novoNo(GATILHO_PADRAO, { x: 60, y: 140 }, "no-gatilho-inicial")],
  );
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
  const [sujo, setSujo] = useState(false);

  // ── Estado de carga (fluxo existente) ──
  const [carregando, setCarregando] = useState(fluxoSalvo);
  const [erroCarga, setErroCarga] = useState<string | null>(null);

  // ── Estado de UI ──
  const [selId, setSelId] = useState<string | null>(null);
  const [tab, setTab] = useState<TabPainel>("fluxo");
  const [aviso, setAviso] = useState<Aviso | null>(null);
  const [salvoAs, setSalvoAs] = useState<string | null>(null);
  const [salvando, setSalvando] = useState(false);
  const [testando, setTestando] = useState(false);
  const [tentouSalvar, setTentouSalvar] = useState(false);
  const [confirmaExcluir, setConfirmaExcluir] = useState(false);
  const [excluindo, setExcluindo] = useState(false);

  // ── Execuções ──
  const [execucoes, setExecucoes] = useState<WorkflowExecutionResponse[] | null>(null);
  const [carregandoExec, setCarregandoExec] = useState(false);
  const [erroExec, setErroExec] = useState<string | null>(null);
  const [expandida, setExpandida] = useState<string | null>(null);

  // Carrega o fluxo existente (nome, estado e grafo) do backend.
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

  // Wrappers que marcam o documento como sujo em mudanças reais do grafo
  // (seleção e medição de dimensões não contam como edição).
  const aoMudarNos = useCallback(
    (changes: NodeChange<NoRF>[]) => {
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
      if (conexao.source === conexao.target) return; // sem self-loop
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
  const temGatilho = nodes.some((n) => n.data.kind === "trigger");

  /** CLICK na paleta: adiciona o bloco perto do nó mais à direita, já selecionado. */
  function adicionarBloco(bloco: BlocoMeta) {
    // id criado FORA do updater: em StrictMode o updater roda 2x e um id
    // gerado lá dentro divergiria do que registramos na seleção.
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
          ? ({ ...n, data: { ...n.data, params: { ...n.data.params, [chave]: valor } } } as NoRF)
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

  /** Seleciona um nó programaticamente (usado pra apontar erro de validação). */
  function focarNo(id: string) {
    setNodes((ns) => ns.map((n) => ({ ...n, selected: n.id === id })));
    setSelId(id);
  }

  /** Validação client-side antes de bater na API. Retorna a 1ª pendência. */
  function validar(): string | null {
    if (!nome.trim()) return "Dê um nome ao fluxo antes de salvar.";
    const gatilhos = nodes.filter((n) => n.data.kind === "trigger");
    if (gatilhos.length === 0)
      return "O fluxo precisa de um gatilho — adicione “Reunião analisada” pela paleta.";
    if (gatilhos.length > 1) return "O fluxo só pode ter um gatilho — remova os extras.";
    if (!nodes.some((n) => n.data.kind === "action"))
      return "Adicione ao menos uma ação (ex.: “Enviar e-mail”).";
    const emailSemDestino = nodes.find((n) => {
      if (n.data.blockType !== "send_email" && n.data.blockType !== "gmail_send_email") {
        return false;
      }
      const to = n.data.params.to;
      return typeof to !== "string" || !to.includes("@");
    });
    if (emailSemDestino) {
      focarNo(emailSemDestino.id);
      const nomeBloco = metaDoBloco(emailSemDestino.data.blockType)?.nome ?? "Enviar e-mail";
      return `A ação “${nomeBloco}” precisa de um destinatário válido no campo Para.`;
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
        setSalvoAs(horaCurta(w.updatedAt));
        setSujo(false);
        setTentouSalvar(false);
      } else {
        const w = await createWorkflow({ name: nome.trim(), active: ativo, definition });
        // vira a URL canônica do fluxo recém-criado (o editor recarrega salvo)
        router.replace(`/fluxos/${w.id}` as Route);
      }
    } catch (e) {
      // 422 WORKFLOW_INVALID_DEFINITION traz mensagem PT-BR acionável do engine
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

  // Primeira abertura da tab Execuções → carrega o histórico.
  useEffect(() => {
    if (tab === "execucoes" && execucoes === null && fluxoSalvo) recarregarExecucoes();
  }, [tab, execucoes, fluxoSalvo, recarregarExecucoes]);

  // Aviso de sucesso some sozinho; erro fica até o usuário fechar.
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
      setTab("execucoes");
      // garante o histórico completo (se a tab nunca foi aberta, só a
      // execução nova estaria na lista) — refetch em segundo plano
      recarregarExecucoes();
      // limpa a seleção pro painel direito mostrar a tab de execuções
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
      router.push("/fluxos" as Route);
    } catch (e) {
      setExcluindo(false);
      setConfirmaExcluir(false);
      setAviso({
        tipo: "erro",
        msg: e instanceof ApiRequestError ? e.message : "Falha ao excluir o fluxo.",
      });
    }
  }

  // ── Estados de carga/erro de página ──
  if (erroCarga) {
    return (
      <div className="page">
        <div className="notice notice--danger" style={{ marginBottom: 16 }}>
          {erroCarga}
        </div>
        <Link href={"/fluxos" as Route} className="btn btn-secondary">
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
          href={"/fluxos" as Route}
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

        {/* switch agrupado com as ações primárias (Testar/Salvar) — antes
            flutuava ao lado do nome, "no meio do nada" (feedback do PO) */}
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

      {/* ── Corpo: paleta | canvas | painel ── */}
      <div className="flows-body">
        <PaletaBlocos temGatilho={temGatilho} onAdicionar={adicionarBloco} />

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

        <PainelLateral
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

export function EditorFluxo({ workflowId }: { workflowId: string | null }) {
  return (
    <ReactFlowProvider>
      <EditorFluxoInterno workflowId={workflowId} />
    </ReactFlowProvider>
  );
}
