"use client";

/**
 * NORA Core — Integrações (hub real de conectores OAuth, NORA Flows Fase 2).
 *
 * Lista o status REAL dos conectores (GET /integrations), inicia o OAuth
 * (POST /integrations/{provider}/oauth/start → redirect pro consent) e
 * desconecta (DELETE /integrations/{provider}). O callback OAuth do backend
 * volta pra cá com ?connected={provider} ou ?error={codigo} — ambos viram
 * notice amigável e a URL é limpa em seguida.
 *
 * Honestidade de produto: conector sem credencial OAuth no servidor aparece
 * como "Não configurado neste ambiente" SEM botão morto; o roadmap dos demais
 * conectores é um bloco textual no rodapé, sem fingir integração.
 */
import Link from "next/link";
import type { Route } from "next";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState, type ReactNode } from "react";

import {
  ApiRequestError,
  disconnectIntegration,
  listIntegrations,
  startIntegrationOAuth,
  type IntegrationProvider,
  type IntegrationStatus,
} from "@/lib/api/client";

import { tempoRelativo } from "../fluxos/tempo-relativo";

/* ── Logos das marcas (SVG inline, estilo simple-icons, container 36×36) ─── */

function GoogleLogo() {
  return (
    <svg viewBox="0 0 24 24" width={20} height={20} aria-hidden>
      <path
        fill="#4285F4"
        d="M23.52 12.27c0-.85-.08-1.66-.22-2.45H12v4.64h6.46a5.53 5.53 0 0 1-2.4 3.62v3.01h3.88c2.27-2.09 3.58-5.17 3.58-8.82Z"
      />
      <path
        fill="#34A853"
        d="M12 24c3.24 0 5.96-1.07 7.94-2.91l-3.88-3.01c-1.08.72-2.45 1.15-4.06 1.15-3.13 0-5.78-2.11-6.72-4.95H1.27v3.11A12 12 0 0 0 12 24Z"
      />
      <path
        fill="#FBBC05"
        d="M5.28 14.28A7.2 7.2 0 0 1 4.9 12c0-.79.14-1.56.38-2.28V6.61H1.27a12 12 0 0 0 0 10.78l4.01-3.11Z"
      />
      <path
        fill="#EA4335"
        d="M12 4.77c1.76 0 3.34.61 4.59 1.8l3.44-3.44A11.97 11.97 0 0 0 12 0 12 12 0 0 0 1.27 6.61l4.01 3.11C6.22 6.88 8.87 4.77 12 4.77Z"
      />
    </svg>
  );
}

function SlackLogo() {
  return (
    <svg viewBox="0 0 24 24" width={20} height={20} aria-hidden>
      <g strokeWidth="0">
        <path fill="#36C5F0" d="M9 3.2a1.7 1.7 0 1 0-1.7 1.7H9V3.2Zm.9 0A1.7 1.7 0 0 1 13.3 3.2v4.2a1.7 1.7 0 0 1-3.4 0V3.2Z" />
        <path fill="#2EB67D" d="M20.8 9a1.7 1.7 0 1 0-1.7-1.7V9h1.7Zm0 .9A1.7 1.7 0 0 1 20.8 13.3h-4.2a1.7 1.7 0 0 1 0-3.4h4.2Z" />
        <path fill="#ECB22E" d="M15 20.8a1.7 1.7 0 1 0 1.7-1.7H15v1.7Zm-.9 0A1.7 1.7 0 0 1 10.7 20.8v-4.2a1.7 1.7 0 0 1 3.4 0v4.2Z" />
        <path fill="#E01E5A" d="M3.2 15a1.7 1.7 0 1 0 1.7 1.7V15H3.2Zm0-.9A1.7 1.7 0 0 1 3.2 10.7h4.2a1.7 1.7 0 0 1 0 3.4H3.2Z" />
      </g>
    </svg>
  );
}

/* ── Metadados de exibição por provedor ────────────────────────────────── */

const PROVEDORES: Record<IntegrationProvider, { nome: string; desc: string; logo: ReactNode }> = {
  google: {
    nome: "Google (Gmail + Calendar)",
    desc: "Envia e-mails pela sua conta e cria follow-ups no seu Calendar direto dos fluxos.",
    logo: <GoogleLogo />,
  },
  slack: {
    nome: "Slack",
    desc: "Manda resumos e alertas das reuniões nos canais do seu workspace.",
    logo: <SlackLogo />,
  },
};

const NOME_CURTO: Record<IntegrationProvider, string> = { google: "Google", slack: "Slack" };

/** Ordem fixa de exibição dos conectores no hub. */
const ORDEM: IntegrationProvider[] = ["google", "slack"];

type Aviso = { tipo: "ok" | "erro"; msg: string };

/** Tradução PT-BR amigável dos códigos de erro do callback OAuth. */
function mensagemErroOAuth(codigo: string): string {
  switch (codigo) {
    case "access_denied":
      return "Você cancelou a autorização no Google. Nada foi conectado.";
    case "integration_invalid_state":
      return "O link de autorização expirou — tente conectar de novo.";
    case "integration_not_configured":
      return "Este conector não está configurado neste ambiente — faltam credenciais OAuth no servidor.";
    default:
      return `Não foi possível concluir a conexão (código: ${codigo}). Tente de novo.`;
  }
}

/** "desde {quando}" a partir do tempoRelativo ("há 3 dias" → "3 dias"). */
function desdeQuando(iso: string): string {
  const rel = tempoRelativo(iso);
  if (rel === "agora") return "agora há pouco";
  return rel.startsWith("há ") ? rel.slice(3) : rel;
}

/* ── Card de um conector ───────────────────────────────────────────────── */

function CardConector({
  status,
  conectando,
  onConectar,
  onDesconectar,
}: {
  status: IntegrationStatus;
  conectando: boolean;
  onConectar: () => void;
  /** Resolve true quando a desconexão deu certo (o card sai do modo confirmação). */
  onDesconectar: () => Promise<boolean>;
}) {
  const [confirmando, setConfirmando] = useState(false);
  const [desconectando, setDesconectando] = useState(false);
  const meta = PROVEDORES[status.provider];

  async function confirmarDesconexao() {
    setDesconectando(true);
    const ok = await onDesconectar();
    setDesconectando(false);
    if (ok) setConfirmando(false);
  }

  return (
    <section
      className="card card--pad"
      aria-label={meta.nome}
      style={{ display: "flex", flexDirection: "column", gap: 0 }}
    >
      <div style={{ display: "flex", alignItems: "flex-start", gap: 12 }}>
        <div
          style={{
            width: 36,
            height: 36,
            borderRadius: 9,
            background: "var(--sidebar)",
            border: "1px solid var(--border)",
            display: "grid",
            placeItems: "center",
            flexShrink: 0,
          }}
        >
          {meta.logo}
        </div>

        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap" }}>
            <span style={{ fontSize: 14.5, fontWeight: 500, color: "var(--ink)", letterSpacing: "-0.01em" }}>
              {meta.nome}
            </span>
            {status.connected ? (
              <span className="chip" style={{ color: "var(--success)" }}>
                <span className="status-dot" style={{ background: "var(--success)", width: 6, height: 6 }} />
                Conectado
              </span>
            ) : !status.configured ? (
              <span className="chip" style={{ color: "var(--muted)" }}>
                Não configurado neste ambiente
              </span>
            ) : null}
          </div>
          <p style={{ fontSize: 12.5, color: "var(--muted)", margin: "3px 0 0", lineHeight: 1.5 }}>
            {meta.desc}
          </p>
        </div>

        <div style={{ flexShrink: 0, paddingTop: 2 }}>
          {status.connected ? (
            confirmando ? (
              <span style={{ display: "inline-flex", alignItems: "center", gap: 8 }}>
                <span style={{ fontSize: 12, color: "var(--danger)" }}>Desconectar?</span>
                <button
                  type="button"
                  className="btn btn-danger btn-sm"
                  onClick={confirmarDesconexao}
                  disabled={desconectando}
                >
                  {desconectando ? "Desconectando…" : "Sim, desconectar"}
                </button>
                <button
                  type="button"
                  className="btn btn-ghost btn-sm"
                  onClick={() => setConfirmando(false)}
                  disabled={desconectando}
                >
                  Cancelar
                </button>
              </span>
            ) : (
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => setConfirmando(true)}>
                Desconectar
              </button>
            )
          ) : status.configured ? (
            <button type="button" className="btn btn-primary" onClick={onConectar} disabled={conectando}>
              {conectando ? "Redirecionando…" : "Conectar"}
            </button>
          ) : null}
        </div>
      </div>

      {status.connected && (
        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: 8,
            flexWrap: "wrap",
            fontSize: 12.5,
            color: "var(--muted)",
            marginTop: 14,
            paddingTop: 12,
            borderTop: "1px solid var(--border)",
          }}
        >
          <span style={{ color: "var(--ink)" }}>{status.externalAccount ?? "conta conectada"}</span>
          {status.connectedAt && <span>· desde {desdeQuando(status.connectedAt)}</span>}
        </div>
      )}

      {!status.connected && !status.configured && (
        <p
          style={{
            fontSize: 12,
            color: "var(--muted)",
            margin: "14px 0 0",
            paddingTop: 12,
            borderTop: "1px solid var(--border)",
            lineHeight: 1.55,
          }}
        >
          Faltam as credenciais OAuth deste provedor no servidor — sem elas, não há o que conectar.
          Quando forem configuradas no ambiente, o botão “Conectar” aparece aqui.
        </p>
      )}

      {status.provider === "google" && status.connected && (
        <Link
          href={"/fluxos" as Route}
          style={{
            display: "block",
            fontSize: 12.5,
            color: "var(--accent-ink)",
            marginTop: 10,
            lineHeight: 1.5,
          }}
        >
          As ações “Enviar via Gmail” e “Criar evento no Calendar” já podem ser usadas nos seus
          fluxos →
        </Link>
      )}
    </section>
  );
}

/* ── Página ────────────────────────────────────────────────────────────── */

function HubIntegracoes() {
  const router = useRouter();
  const searchParams = useSearchParams();

  const [integracoes, setIntegracoes] = useState<IntegrationStatus[] | null>(null);
  const [erroCarga, setErroCarga] = useState<string | null>(null);
  const [tentativa, setTentativa] = useState(0);
  const [aviso, setAviso] = useState<Aviso | null>(null);
  const [conectando, setConectando] = useState<IntegrationProvider | null>(null);

  // Resultado do callback OAuth (?connected= / ?error=) → notice + limpa a URL,
  // pra um refresh não repetir a mensagem.
  useEffect(() => {
    const conectado = searchParams.get("connected");
    const erro = searchParams.get("error");
    if (!conectado && !erro) return;
    if (conectado) {
      const nome = NOME_CURTO[conectado as IntegrationProvider] ?? conectado;
      setAviso({
        tipo: "ok",
        msg:
          conectado === "google"
            ? "Conta Google conectada! As ações “Enviar via Gmail” e “Criar evento no Calendar” já estão liberadas nos seus fluxos."
            : `Conta ${nome} conectada!`,
      });
    } else if (erro) {
      setAviso({ tipo: "erro", msg: mensagemErroOAuth(erro) });
    }
    router.replace("/integracoes" as Route, { scroll: false });
  }, [searchParams, router]);

  // Status real dos conectores no backend.
  useEffect(() => {
    let vivo = true;
    setErroCarga(null);
    listIntegrations()
      .then((r) => {
        if (vivo) setIntegracoes(r);
      })
      .catch((e) => {
        if (!vivo) return;
        setErroCarga(e instanceof ApiRequestError ? e.message : "Falha ao carregar as integrações.");
      });
    return () => {
      vivo = false;
    };
  }, [tentativa]);

  async function conectar(provider: IntegrationProvider) {
    setConectando(provider);
    setAviso(null);
    try {
      const { authorizeUrl } = await startIntegrationOAuth(provider);
      // Redirect completo pro consent do provedor; o estado "Redirecionando…"
      // permanece até o browser navegar.
      window.location.assign(authorizeUrl);
    } catch (e) {
      setConectando(null);
      if (e instanceof ApiRequestError && e.status === 422) {
        // INTEGRATION_NOT_CONFIGURED — a message do backend já vem acionável.
        setAviso({ tipo: "erro", msg: e.message });
      } else if (e instanceof ApiRequestError && e.status === 404) {
        setAviso({
          tipo: "erro",
          msg: "O servidor não conhece esse conector — recarregue a página e tente de novo.",
        });
      } else {
        setAviso({
          tipo: "erro",
          msg: e instanceof ApiRequestError ? e.message : "Falha ao iniciar a conexão. Tente de novo.",
        });
      }
    }
  }

  async function desconectar(provider: IntegrationProvider): Promise<boolean> {
    try {
      await disconnectIntegration(provider);
      setIntegracoes((curr) =>
        (curr ?? []).map((i) =>
          i.provider === provider
            ? { ...i, connected: false, externalAccount: null, connectedAt: null }
            : i,
        ),
      );
      setAviso({ tipo: "ok", msg: `Conta ${NOME_CURTO[provider] ?? provider} desconectada.` });
      return true;
    } catch (e) {
      setAviso({
        tipo: "erro",
        msg: e instanceof ApiRequestError ? e.message : "Falha ao desconectar. Tente de novo.",
      });
      return false;
    }
  }

  // Ordena pela ordem fixa do hub (google primeiro), preservando extras no fim.
  const ordenadas = integracoes
    ? [...integracoes].sort(
        (a, b) =>
          (ORDEM.indexOf(a.provider) + 1 || ORDEM.length + 1) -
          (ORDEM.indexOf(b.provider) + 1 || ORDEM.length + 1),
      )
    : null;

  return (
    <div className="page">
      <header style={{ marginBottom: 24 }}>
        <div className="eyebrow">Conectores</div>
        <h1 className="h1">Integrações</h1>
        <p className="lede" style={{ marginTop: 8, maxWidth: 620 }}>
          Conecte suas contas para o NORA Flows agir nelas — e-mail, agenda e mensagens reais.
        </p>
      </header>

      {aviso && (
        <div
          className={`notice ${aviso.tipo === "erro" ? "notice--danger" : "notice--accent"}`}
          role={aviso.tipo === "erro" ? "alert" : "status"}
          style={{ margin: "0 0 18px", display: "flex", alignItems: "center", gap: 10 }}
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

      {erroCarga && (
        <div
          className="notice notice--danger"
          style={{ marginBottom: 16, display: "flex", alignItems: "center", justifyContent: "space-between", gap: 12 }}
        >
          <span>Não consegui carregar as integrações agora ({erroCarga}).</span>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => setTentativa((t) => t + 1)}>
            Tentar de novo
          </button>
        </div>
      )}

      {ordenadas === null && !erroCarga ? (
        <div style={{ display: "flex", flexDirection: "column", gap: 14 }} aria-busy>
          {[0, 1].map((i) => (
            <div key={i} className="skel" style={{ height: 104, borderRadius: 12 }} />
          ))}
        </div>
      ) : ordenadas !== null ? (
        ordenadas.length === 0 ? (
          <p style={{ fontSize: 13, color: "var(--muted)", margin: 0 }}>
            O servidor não expôs nenhum conector neste ambiente.
          </p>
        ) : (
          <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
            {ordenadas.map((st) => (
              <CardConector
                key={st.provider}
                status={st}
                conectando={conectando === st.provider}
                onConectar={() => conectar(st.provider)}
                onDesconectar={() => desconectar(st.provider)}
              />
            ))}
          </div>
        )
      ) : null}

      <div style={{ marginTop: 40, paddingTop: 18, borderTop: "1px solid var(--border)" }}>
        <div className="sec-label" style={{ marginBottom: 6 }}>
          No radar
        </div>
        <p style={{ fontSize: 12, color: "var(--muted)", margin: 0, lineHeight: 1.6, maxWidth: 560 }}>
          Outlook, Linear, Jira, GitHub, Salesforce e Pipedrive estão no roadmap de conectores —
          ainda sem data. Quando um deles estiver pronto de verdade, aparece nesta página com o
          botão “Conectar”.
        </p>
      </div>
    </div>
  );
}

/** Fallback do Suspense (useSearchParams exige boundary no app router). */
function PaginaSkeleton() {
  return (
    <div className="page" aria-busy>
      <div className="skel" style={{ width: 90, height: 13, marginBottom: 10 }} />
      <div className="skel" style={{ width: 220, height: 30, marginBottom: 28 }} />
      <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
        {[0, 1].map((i) => (
          <div key={i} className="skel" style={{ height: 104, borderRadius: 12 }} />
        ))}
      </div>
    </div>
  );
}

export default function IntegracoesPage() {
  return (
    <Suspense fallback={<PaginaSkeleton />}>
      <HubIntegracoes />
    </Suspense>
  );
}
