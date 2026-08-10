'use client';

/**
 * NORA Core — Integrations (real OAuth connector hub, NORA Flows Phase 2).
 *
 * Lists the REAL status of the connectors (GET /integrations), starts the OAuth
 * (POST /integrations/{provider}/oauth/start → redirect to the consent) and
 * disconnects (DELETE /integrations/{provider}). The backend OAuth callback
 * comes back here with ?connected={provider} or ?error={codigo} — both become
 * a friendly notice and the URL is cleaned right after.
 *
 * Product honesty: a connector without an OAuth credential on the server shows
 * as "Não configurado neste ambiente" WITHOUT a dead button; the roadmap of the
 * other connectors is a text block in the footer, without faking integration.
 */
import Link from 'next/link';
import type { Route } from 'next';
import { useRouter, useSearchParams } from 'next/navigation';
import { Suspense, useEffect, useState, type ReactNode } from 'react';

import {
  ApiRequestError,
  disconnectIntegration,
  listIntegrations,
  saveTrelloToken,
  startIntegrationOAuth,
  startTelegramPairing,
  verifyTelegramPairing,
  type IntegrationProvider,
  type IntegrationStatus,
  type TelegramPairingStart,
} from '@/lib/api/client';

import { relativeTime } from '../flows/relative-time';

/* ── Brand logos (inline SVG, simple-icons style, 36×36 container) ───────── */

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
        <path
          fill="#36C5F0"
          d="M9 3.2a1.7 1.7 0 1 0-1.7 1.7H9V3.2Zm.9 0A1.7 1.7 0 0 1 13.3 3.2v4.2a1.7 1.7 0 0 1-3.4 0V3.2Z"
        />
        <path
          fill="#2EB67D"
          d="M20.8 9a1.7 1.7 0 1 0-1.7-1.7V9h1.7Zm0 .9A1.7 1.7 0 0 1 20.8 13.3h-4.2a1.7 1.7 0 0 1 0-3.4h4.2Z"
        />
        <path
          fill="#ECB22E"
          d="M15 20.8a1.7 1.7 0 1 0 1.7-1.7H15v1.7Zm-.9 0A1.7 1.7 0 0 1 10.7 20.8v-4.2a1.7 1.7 0 0 1 3.4 0v4.2Z"
        />
        <path
          fill="#E01E5A"
          d="M3.2 15a1.7 1.7 0 1 0 1.7 1.7V15H3.2Zm0-.9A1.7 1.7 0 0 1 3.2 10.7h4.2a1.7 1.7 0 0 1 0 3.4H3.2Z"
        />
      </g>
    </svg>
  );
}

function GitHubLogo() {
  return (
    <svg viewBox="0 0 24 24" width={20} height={20} aria-hidden>
      <path
        fill="currentColor"
        d="M12 .3a12 12 0 0 0-3.79 23.39c.6.11.82-.26.82-.58v-2.04c-3.34.73-4.04-1.61-4.04-1.61-.55-1.39-1.33-1.76-1.33-1.76-1.09-.74.08-.73.08-.73 1.2.09 1.84 1.24 1.84 1.24 1.07 1.83 2.81 1.3 3.5 1 .1-.78.42-1.31.76-1.61-2.66-.3-5.47-1.33-5.47-5.93 0-1.31.47-2.38 1.24-3.22-.13-.3-.54-1.52.11-3.18 0 0 1.01-.32 3.3 1.23a11.5 11.5 0 0 1 6.01 0c2.29-1.55 3.29-1.23 3.29-1.23.66 1.66.25 2.88.12 3.18.77.84 1.24 1.91 1.24 3.22 0 4.61-2.81 5.63-5.49 5.92.43.37.81 1.1.81 2.22v3.29c0 .32.22.7.83.58A12 12 0 0 0 12 .3Z"
      />
    </svg>
  );
}

function NotionLogo() {
  return (
    <svg viewBox="0 0 24 24" width={20} height={20} aria-hidden>
      <path
        fill="currentColor"
        d="M4.46 1.28 15.4.47c1.34-.12 1.69-.04 2.53.57l3.48 2.45c.57.42.76.54.76 1v15.62c0 .85-.31 1.35-1.39 1.42l-12.7.77c-.81.04-1.2-.08-1.62-.62L3.89 18.3c-.46-.62-.66-1.08-.66-1.62V2.63c0-.69.31-1.27 1.23-1.35Zm11.21 1.46-10.2.75c-.62.08-.81.39-.57.7l1.46 1.27c.23.23.54.35.96.31l10.31-.62c.54-.08.66-.35.42-.58l-1.61-1.45c-.27-.23-.42-.42-.77-.38Zm-9.85 4.94v12.04c0 .65.32.89.97.85l11.31-.66c.65-.04.73-.43.73-.96V7c0-.53-.2-.81-.65-.77l-11.81.69c-.49.04-.55.29-.55.76Zm10.93.62c.07.35 0 .69-.35.73l-.58.11v8.54c-.5.27-.96.42-1.34.42-.62 0-.77-.19-1.23-.77l-3.78-5.94v5.75l1.2.27s0 .69-.96.69l-2.65.16c-.08-.16 0-.54.27-.62l.69-.19V9.83l-.96-.08c-.08-.34.11-.84.65-.88l2.84-.19 3.92 5.99v-5.3l-1-.11c-.08-.43.23-.74.61-.78l2.67-.18Z"
      />
    </svg>
  );
}

function TodoistLogo() {
  return (
    <svg viewBox="0 0 24 24" width={20} height={20} aria-hidden>
      <path
        fill="#E44332"
        d="M21 0H3C1.35 0 0 1.35 0 3v18c0 1.65 1.35 3 3 3h18c1.65 0 3-1.35 3-3V3c0-1.65-1.35-3-3-3Z"
      />
      <path
        fill="#fff"
        d="m5.1 10.93 6.49-3.73c.18-.1.64-.37.64-.37.16-.09.34-.09.5 0 0 0 .42.24.62.36l1.07.61c.17.1.18.4 0 .5l-7.62 4.38c-.21.12-.46.12-.66 0L3.2 11.04c-.18-.1-.18-.4 0-.5l1.06-.61c.2-.12.62-.36.62-.36.07-.04.15-.04.22.01v1.35Zm0 3.47 6.49-3.73c.18-.1.64-.37.64-.37.16-.09.34-.09.5 0 0 0 .42.24.62.36l1.07.61c.17.1.18.4 0 .5l-7.62 4.38c-.21.12-.46.12-.66 0L3.2 14.51c-.18-.1-.18-.4 0-.5l1.06-.61c.2-.12.62-.36.62-.36.07-.04.15-.04.22.01v1.35Zm0 3.47 6.49-3.73c.18-.1.64-.37.64-.37.16-.09.34-.09.5 0 0 0 .42.24.62.36l1.07.61c.17.1.18.4 0 .5l-7.62 4.38c-.21.12-.46.12-.66 0L3.2 17.98c-.18-.1-.18-.4 0-.5l1.06-.61c.2-.12.62-.36.62-.36.07-.04.15-.04.22.01v1.35Z"
      />
    </svg>
  );
}

function LinearLogo() {
  return (
    <svg viewBox="0 0 24 24" width={20} height={20} aria-hidden>
      <path
        fill="#5E6AD2"
        d="M1.22 14.51a.25.25 0 0 1 .07-.23l8.43 8.43a.25.25 0 0 1-.23.07 11.96 11.96 0 0 1-8.27-8.27ZM.07 10.7a.25.25 0 0 0 .07.21l12.95 12.95c.06.06.14.08.21.07.73-.09 1.44-.25 2.12-.47a.25.25 0 0 0 .1-.41L1.95 8.48a.25.25 0 0 0-.41.1c-.22.68-.38 1.39-.47 2.12Zm2.4-4.92a.25.25 0 0 1-.02-.33A11.97 11.97 0 0 1 12 0c6.63 0 12 5.37 12 12 0 3.7-1.68 7.02-4.31 9.22a.25.25 0 0 1-.33-.02L2.47 5.78Z"
      />
    </svg>
  );
}

function MicrosoftLogo() {
  return (
    <svg viewBox="0 0 24 24" width={20} height={20} aria-hidden>
      <path fill="#F25022" d="M1 1h10.5v10.5H1z" />
      <path fill="#7FBA00" d="M12.5 1H23v10.5H12.5z" />
      <path fill="#00A4EF" d="M1 12.5h10.5V23H1z" />
      <path fill="#FFB900" d="M12.5 12.5H23V23H12.5z" />
    </svg>
  );
}

function TelegramLogo() {
  return (
    <svg viewBox="0 0 24 24" width={20} height={20} aria-hidden>
      <path
        fill="#26A5E4"
        d="M12 0C5.37 0 0 5.37 0 12s5.37 12 12 12 12-5.37 12-12S18.63 0 12 0Zm5.57 8.16-1.97 9.3c-.15.66-.54.82-1.09.51l-3-2.21-1.45 1.39c-.16.16-.3.3-.6.3l.21-3.05 5.56-5.02c.24-.21-.05-.33-.37-.12l-6.87 4.33-2.96-.93c-.64-.2-.66-.64.14-.95l11.57-4.46c.54-.2 1.01.12.83.91Z"
      />
    </svg>
  );
}

function TrelloLogo() {
  return (
    <svg viewBox="0 0 24 24" width={20} height={20} aria-hidden>
      <path
        fill="#0052CC"
        d="M21 0H3C1.35 0 0 1.35 0 3v18c0 1.65 1.35 3 3 3h18c1.65 0 3-1.35 3-3V3c0-1.65-1.35-3-3-3Z"
      />
      <path
        fill="#fff"
        d="M10.44 3.12H4.56c-.8 0-1.44.65-1.44 1.44v15.12c0 .8.65 1.44 1.44 1.44h5.88c.8 0 1.44-.65 1.44-1.44V4.56c0-.8-.65-1.44-1.44-1.44Zm9 0h-5.88c-.8 0-1.44.65-1.44 1.44v8.4c0 .8.65 1.44 1.44 1.44h5.88c.8 0 1.44-.65 1.44-1.44v-8.4c0-.8-.65-1.44-1.44-1.44Z"
      />
    </svg>
  );
}

/* ── Display metadata per provider ─────────────────────────────────────── */

const PROVEDORES: Record<IntegrationProvider, { name: string; desc: string; logo: ReactNode }> = {
  google: {
    name: 'Google (Gmail + Calendar)',
    desc: 'Envia e-mails pela sua conta e cria follow-ups no seu Calendar direto dos fluxos.',
    logo: <GoogleLogo />,
  },
  slack: {
    name: 'Slack',
    desc: 'Manda resumos e alertas das reuniões nos canais do seu workspace.',
    logo: <SlackLogo />,
  },
  github: {
    name: 'GitHub',
    desc: 'Cria issues no seu repositório a partir dos action items das reuniões.',
    logo: <GitHubLogo />,
  },
  notion: {
    name: 'Notion',
    desc: 'Cria páginas no seu workspace com resumo e action items de cada reunião.',
    logo: <NotionLogo />,
  },
  todoist: {
    name: 'Todoist',
    desc: 'Transforma os action items das reuniões em tarefas na sua conta.',
    logo: <TodoistLogo />,
  },
  linear: {
    name: 'Linear',
    desc: 'Cria issues no seu time a partir dos action items das reuniões.',
    logo: <LinearLogo />,
  },
  microsoft: {
    name: 'Microsoft (Outlook + Calendar)',
    desc: 'Envia e-mails pela sua conta Outlook e cria follow-ups no seu calendário direto dos fluxos.',
    logo: <MicrosoftLogo />,
  },
  telegram: {
    name: 'Telegram',
    desc: 'Manda o resumo das reuniões direto no seu chat — pareamento rápido com o bot do Nora.',
    logo: <TelegramLogo />,
  },
  trello: {
    name: 'Trello',
    desc: 'Transforma os action items das reuniões em cards na lista que você escolher.',
    logo: <TrelloLogo />,
  },
};

const NOME_CURTO: Record<IntegrationProvider, string> = {
  google: 'Google',
  slack: 'Slack',
  github: 'GitHub',
  notion: 'Notion',
  todoist: 'Todoist',
  linear: 'Linear',
  microsoft: 'Microsoft',
  telegram: 'Telegram',
  trello: 'Trello',
};

/** Fixed display order of the connectors in the hub. */
const ORDEM: IntegrationProvider[] = [
  'google',
  'microsoft',
  'slack',
  'telegram',
  'github',
  'notion',
  'todoist',
  'linear',
  'trello',
];

type Aviso = { tipo: 'ok' | 'erro'; msg: string };

/** Friendly PT-BR translation of the OAuth callback error codes. */
function mensagemErroOAuth(codigo: string): string {
  switch (codigo) {
    case 'access_denied':
      return 'Você cancelou a autorização no provedor. Nada foi conectado.';
    case 'integration_invalid_state':
      return 'O link de autorização expirou — tente conectar de novo.';
    case 'integration_unknown_provider':
      return 'O servidor não conhece esse conector — recarregue a página e tente de novo.';
    case 'integration_not_configured':
      return 'Este conector não está configurado neste ambiente — faltam credenciais OAuth no servidor.';
    default:
      return `Não foi possível concluir a conexão (código: ${codigo}). Tente de novo.`;
  }
}

/** "desde {quando}" out of relativeTime ("há 3 dias" → "3 dias"). */
function desdeQuando(iso: string): string {
  const rel = relativeTime(iso);
  if (rel === 'agora') return 'agora há pouco';
  return rel.startsWith('há ') ? rel.slice(3) : rel;
}

/* ── Connector card ────────────────────────────────────────────────────── */

function CardConector({
  status,
  conectando,
  onConectar,
  onDesconectar,
  onStatus,
  onAviso,
}: {
  status: IntegrationStatus;
  conectando: boolean;
  onConectar: () => void;
  /** Resolves true when the disconnect worked (the card leaves confirmation mode). */
  onDesconectar: () => Promise<boolean>;
  /** New connector status coming from a flow finished inside the card (Telegram/Trello). */
  onStatus: (status: IntegrationStatus) => void;
  onAviso: (aviso: Aviso | null) => void;
}) {
  const [confirmando, setConfirmando] = useState(false);
  const [desconectando, setDesconectando] = useState(false);

  // Telegram: pairing by code (deep link + "Verificar conexão").
  const [pareamento, setPareamento] = useState<TelegramPairingStart | null>(null);
  const [gerandoCodigo, setGerandoCodigo] = useState(false);
  const [verificando, setVerificando] = useState(false);
  const [pendencia, setPendencia] = useState<string | null>(null);

  // Trello: the user generates the token in a new tab and pastes it here.
  const [coletandoToken, setColetandoToken] = useState(false);
  const [tokenColado, setTokenColado] = useState('');
  const [salvandoToken, setSalvandoToken] = useState(false);

  const meta = PROVEDORES[status.provider];

  async function confirmarDesconexao() {
    setDesconectando(true);
    const ok = await onDesconectar();
    setDesconectando(false);
    if (ok) setConfirmando(false);
  }

  async function iniciarPareamentoTelegram() {
    setGerandoCodigo(true);
    setPendencia(null);
    onAviso(null);
    try {
      setPareamento(await startTelegramPairing());
    } catch (e) {
      onAviso({
        tipo: 'erro',
        msg: e instanceof ApiRequestError ? e.message : 'Falha ao gerar o código. Tente de novo.',
      });
    } finally {
      setGerandoCodigo(false);
    }
  }

  async function verificarPareamentoTelegram() {
    setVerificando(true);
    setPendencia(null);
    try {
      const atualizado = await verifyTelegramPairing();
      setPareamento(null);
      onStatus(atualizado);
      onAviso({
        tipo: 'ok',
        msg: 'Telegram conectado! O bot do Nora já pode mandar resumos no seu chat.',
      });
    } catch (e) {
      if (e instanceof ApiRequestError && e.status === 409) {
        // INTEGRATION_PAIRING_PENDING — the /start has not arrived yet; inline message, no fuss.
        setPendencia(e.message);
      } else {
        // Expired code / lost pairing: start over from the Conectar button.
        setPareamento(null);
        onAviso({
          tipo: 'erro',
          msg: e instanceof ApiRequestError ? e.message : 'Falha ao verificar. Tente de novo.',
        });
      }
    } finally {
      setVerificando(false);
    }
  }

  async function abrirAutorizacaoTrello() {
    setGerandoCodigo(true);
    onAviso(null);
    try {
      const { authorizeUrl } = await startIntegrationOAuth('trello');
      window.open(authorizeUrl, '_blank', 'noopener');
      setColetandoToken(true);
    } catch (e) {
      onAviso({
        tipo: 'erro',
        msg: e instanceof ApiRequestError ? e.message : 'Falha ao abrir a autorização do Trello.',
      });
    } finally {
      setGerandoCodigo(false);
    }
  }

  async function salvarTokenTrello() {
    setSalvandoToken(true);
    onAviso(null);
    try {
      const atualizado = await saveTrelloToken(tokenColado.trim());
      setColetandoToken(false);
      setTokenColado('');
      onStatus(atualizado);
      onAviso({
        tipo: 'ok',
        msg: 'Trello conectado! A ação de criar cards já está liberada nos seus fluxos.',
      });
    } catch (e) {
      onAviso({
        tipo: 'erro',
        msg: e instanceof ApiRequestError ? e.message : 'Falha ao salvar o token. Tente de novo.',
      });
    } finally {
      setSalvandoToken(false);
    }
  }

  return (
    <section
      className="card card--pad"
      aria-label={meta.name}
      style={{ display: 'flex', flexDirection: 'column', gap: 0 }}
    >
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12 }}>
        <div
          style={{
            width: 36,
            height: 36,
            borderRadius: 9,
            background: 'var(--sidebar)',
            border: '1px solid var(--border)',
            display: 'grid',
            placeItems: 'center',
            flexShrink: 0,
          }}
        >
          {meta.logo}
        </div>

        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
            <span
              style={{
                fontSize: 14.5,
                fontWeight: 500,
                color: 'var(--ink)',
                letterSpacing: '-0.01em',
              }}
            >
              {meta.name}
            </span>
            {status.connected ? (
              <span className="chip" style={{ color: 'var(--success)' }}>
                <span
                  className="status-dot"
                  style={{ background: 'var(--success)', width: 6, height: 6 }}
                />
                Conectado
              </span>
            ) : !status.configured ? (
              <span className="chip" style={{ color: 'var(--muted)' }}>
                Não configurado neste ambiente
              </span>
            ) : null}
          </div>
          <p style={{ fontSize: 12.5, color: 'var(--muted)', margin: '3px 0 0', lineHeight: 1.5 }}>
            {meta.desc}
          </p>
        </div>

        <div style={{ flexShrink: 0, paddingTop: 2 }}>
          {status.connected ? (
            confirmando ? (
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
                <span style={{ fontSize: 12, color: 'var(--danger)' }}>Desconectar?</span>
                <button
                  type="button"
                  className="btn btn-danger btn-sm"
                  onClick={confirmarDesconexao}
                  disabled={desconectando}
                >
                  {desconectando ? 'Desconectando…' : 'Sim, desconectar'}
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
              <button
                type="button"
                className="btn btn-ghost btn-sm"
                onClick={() => setConfirmando(true)}
              >
                Desconectar
              </button>
            )
          ) : status.configured ? (
            status.provider === 'telegram' ? (
              !pareamento && (
                <button
                  type="button"
                  className="btn btn-primary"
                  onClick={iniciarPareamentoTelegram}
                  disabled={gerandoCodigo}
                >
                  {gerandoCodigo ? 'Gerando código…' : 'Conectar'}
                </button>
              )
            ) : status.provider === 'trello' ? (
              !coletandoToken && (
                <button
                  type="button"
                  className="btn btn-primary"
                  onClick={abrirAutorizacaoTrello}
                  disabled={gerandoCodigo}
                >
                  {gerandoCodigo ? 'Abrindo…' : 'Conectar'}
                </button>
              )
            ) : (
              <button
                type="button"
                className="btn btn-primary"
                onClick={onConectar}
                disabled={conectando}
              >
                {conectando ? 'Redirecionando…' : 'Conectar'}
              </button>
            )
          ) : null}
        </div>
      </div>

      {status.connected && (
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            flexWrap: 'wrap',
            fontSize: 12.5,
            color: 'var(--muted)',
            marginTop: 14,
            paddingTop: 12,
            borderTop: '1px solid var(--border)',
          }}
        >
          <span style={{ color: 'var(--ink)' }}>{status.externalAccount ?? 'conta conectada'}</span>
          {status.connectedAt && <span>· desde {desdeQuando(status.connectedAt)}</span>}
        </div>
      )}

      {!status.connected && !status.configured && (
        <p
          style={{
            fontSize: 12,
            color: 'var(--muted)',
            margin: '14px 0 0',
            paddingTop: 12,
            borderTop: '1px solid var(--border)',
            lineHeight: 1.55,
          }}
        >
          Faltam as credenciais OAuth deste provedor no servidor — sem elas, não há o que conectar.
          Quando forem configuradas no ambiente, o botão “Conectar” aparece aqui.
        </p>
      )}

      {status.provider === 'telegram' && !status.connected && pareamento && (
        <div
          style={{
            marginTop: 14,
            paddingTop: 12,
            borderTop: '1px solid var(--border)',
            display: 'flex',
            flexDirection: 'column',
            gap: 10,
          }}
        >
          <p style={{ fontSize: 12.5, color: 'var(--muted)', margin: 0, lineHeight: 1.55 }}>
            1. Abra o link abaixo — ele manda <code>/start {pareamento.code}</code> pro bot do Nora.
            <br />
            2. Depois volte aqui e clique em “Verificar conexão”. O código vale por 10 minutos.
          </p>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
            <a
              href={pareamento.deepLink}
              target="_blank"
              rel="noopener noreferrer"
              className="btn btn-ghost btn-sm"
            >
              Abrir no Telegram ↗
            </a>
            <button
              type="button"
              className="btn btn-primary btn-sm"
              onClick={verificarPareamentoTelegram}
              disabled={verificando}
            >
              {verificando ? 'Verificando…' : 'Verificar conexão'}
            </button>
            <button
              type="button"
              className="btn btn-ghost btn-sm"
              onClick={() => {
                setPareamento(null);
                setPendencia(null);
              }}
            >
              Cancelar
            </button>
          </div>
          {pendencia && (
            <p style={{ fontSize: 12.5, color: 'var(--danger)', margin: 0 }}>{pendencia}</p>
          )}
        </div>
      )}

      {status.provider === 'trello' && !status.connected && coletandoToken && (
        <div
          style={{
            marginTop: 14,
            paddingTop: 12,
            borderTop: '1px solid var(--border)',
            display: 'flex',
            flexDirection: 'column',
            gap: 10,
          }}
        >
          <p style={{ fontSize: 12.5, color: 'var(--muted)', margin: 0, lineHeight: 1.55 }}>
            O Trello abriu numa nova aba. Clique em “Permitir”, copie o token exibido e cole aqui.
          </p>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
            <input
              type="text"
              value={tokenColado}
              onChange={(e) => setTokenColado(e.target.value)}
              placeholder="Cole o token do Trello"
              aria-label="Token do Trello"
              style={{
                flex: 1,
                minWidth: 220,
                fontSize: 12.5,
                padding: '7px 10px',
                border: '1px solid var(--border)',
                borderRadius: 8,
                background: 'transparent',
                color: 'var(--ink)',
              }}
            />
            <button
              type="button"
              className="btn btn-primary btn-sm"
              onClick={salvarTokenTrello}
              disabled={salvandoToken || tokenColado.trim().length === 0}
            >
              {salvandoToken ? 'Validando…' : 'Salvar token'}
            </button>
            <button
              type="button"
              className="btn btn-ghost btn-sm"
              onClick={() => {
                setColetandoToken(false);
                setTokenColado('');
              }}
              disabled={salvandoToken}
            >
              Cancelar
            </button>
          </div>
        </div>
      )}

      {status.provider === 'microsoft' && status.connected && (
        <Link
          href={'/flows' as Route}
          style={{
            display: 'block',
            fontSize: 12.5,
            color: 'var(--accent-ink)',
            marginTop: 10,
            lineHeight: 1.5,
          }}
        >
          As ações “Enviar via Outlook” e “Criar evento no Outlook Calendar” já podem ser usadas nos
          seus fluxos →
        </Link>
      )}

      {status.provider === 'google' && status.connected && (
        <Link
          href={'/flows' as Route}
          style={{
            display: 'block',
            fontSize: 12.5,
            color: 'var(--accent-ink)',
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

/* ── Page ──────────────────────────────────────────────────────────────── */

function HubIntegracoes() {
  const router = useRouter();
  const searchParams = useSearchParams();

  const [integracoes, setIntegracoes] = useState<IntegrationStatus[] | null>(null);
  const [erroCarga, setErroCarga] = useState<string | null>(null);
  const [tentativa, setTentativa] = useState(0);
  const [aviso, setAviso] = useState<Aviso | null>(null);
  const [conectando, setConectando] = useState<IntegrationProvider | null>(null);

  // Result of the OAuth callback (?connected= / ?error=) → notice + clears the URL,
  // so a refresh does not repeat the message.
  useEffect(() => {
    const conectado = searchParams.get('connected');
    const erro = searchParams.get('error');
    if (!conectado && !erro) return;
    if (conectado) {
      const nome = NOME_CURTO[conectado as IntegrationProvider] ?? conectado;
      setAviso({
        tipo: 'ok',
        msg:
          conectado === 'google'
            ? 'Conta Google conectada! As ações “Enviar via Gmail” e “Criar evento no Calendar” já estão liberadas nos seus fluxos.'
            : conectado === 'microsoft'
              ? 'Conta Microsoft conectada! As ações “Enviar via Outlook” e “Criar evento no Outlook Calendar” já estão liberadas nos seus fluxos.'
              : `Conta ${nome} conectada!`,
      });
    } else if (erro) {
      setAviso({ tipo: 'erro', msg: mensagemErroOAuth(erro) });
    }
    router.replace('/integrations' as Route, { scroll: false });
  }, [searchParams, router]);

  // Real status of the connectors in the backend.
  useEffect(() => {
    let vivo = true;
    setErroCarga(null);
    listIntegrations()
      .then((r) => {
        if (vivo) setIntegracoes(r);
      })
      .catch((e) => {
        if (!vivo) return;
        setErroCarga(
          e instanceof ApiRequestError ? e.message : 'Falha ao carregar as integrações.',
        );
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
      // Full redirect to the provider consent; the "Redirecionando…" state
      // stays until the browser navigates.
      window.location.assign(authorizeUrl);
    } catch (e) {
      setConectando(null);
      if (e instanceof ApiRequestError && e.status === 422) {
        // INTEGRATION_NOT_CONFIGURED — the backend message already comes actionable.
        setAviso({ tipo: 'erro', msg: e.message });
      } else if (e instanceof ApiRequestError && e.status === 404) {
        setAviso({
          tipo: 'erro',
          msg: 'O servidor não conhece esse conector — recarregue a página e tente de novo.',
        });
      } else {
        setAviso({
          tipo: 'erro',
          msg:
            e instanceof ApiRequestError ? e.message : 'Falha ao iniciar a conexão. Tente de novo.',
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
      setAviso({ tipo: 'ok', msg: `Conta ${NOME_CURTO[provider] ?? provider} desconectada.` });
      return true;
    } catch (e) {
      setAviso({
        tipo: 'erro',
        msg: e instanceof ApiRequestError ? e.message : 'Falha ao desconectar. Tente de novo.',
      });
      return false;
    }
  }

  // Sorts by the hub's fixed order (google first), keeping extras at the end.
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
          Conecte suas contas para o Nora Flows agir nelas — e-mail, agenda e mensagens reais.
        </p>
      </header>

      {aviso && (
        <div
          className={`notice ${aviso.tipo === 'erro' ? 'notice--danger' : 'notice--accent'}`}
          role={aviso.tipo === 'erro' ? 'alert' : 'status'}
          style={{ margin: '0 0 18px', display: 'flex', alignItems: 'center', gap: 10 }}
        >
          <span style={{ flex: 1 }}>{aviso.msg}</span>
          <button
            type="button"
            onClick={() => setAviso(null)}
            aria-label="Fechar aviso"
            style={{
              background: 'transparent',
              border: 'none',
              cursor: 'pointer',
              color: 'inherit',
              display: 'grid',
              placeItems: 'center',
              padding: 2,
              flexShrink: 0,
            }}
          >
            <svg
              width="13"
              height="13"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
            >
              <path d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        </div>
      )}

      {erroCarga && (
        <div
          className="notice notice--danger"
          style={{
            marginBottom: 16,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 12,
          }}
        >
          <span>Não consegui carregar as integrações agora ({erroCarga}).</span>
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            onClick={() => setTentativa((t) => t + 1)}
          >
            Tentar de novo
          </button>
        </div>
      )}

      {ordenadas === null && !erroCarga ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }} aria-busy>
          {[0, 1].map((i) => (
            <div key={i} className="skel" style={{ height: 104, borderRadius: 12 }} />
          ))}
        </div>
      ) : ordenadas !== null ? (
        ordenadas.length === 0 ? (
          <p style={{ fontSize: 13, color: 'var(--muted)', margin: 0 }}>
            O servidor não expôs nenhum conector neste ambiente.
          </p>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            {ordenadas.map((st) => (
              <CardConector
                key={st.provider}
                status={st}
                conectando={conectando === st.provider}
                onConectar={() => conectar(st.provider)}
                onDesconectar={() => desconectar(st.provider)}
                onStatus={(novo) =>
                  setIntegracoes((curr) =>
                    (curr ?? []).map((i) => (i.provider === novo.provider ? novo : i)),
                  )
                }
                onAviso={setAviso}
              />
            ))}
          </div>
        )
      ) : null}

      <div style={{ marginTop: 40, paddingTop: 18, borderTop: '1px solid var(--border)' }}>
        <div className="sec-label" style={{ marginBottom: 6 }}>
          No radar
        </div>
        <p
          style={{ fontSize: 12, color: 'var(--muted)', margin: 0, lineHeight: 1.6, maxWidth: 560 }}
        >
          Jira, Salesforce e Pipedrive estão no roadmap de conectores — ainda sem data. Quando um
          deles estiver pronto de verdade, aparece nesta página com o botão “Conectar”.
        </p>
      </div>
    </div>
  );
}

/** Suspense fallback (useSearchParams requires a boundary in the app router). */
function PaginaSkeleton() {
  return (
    <div className="page" aria-busy>
      <div className="skel" style={{ width: 90, height: 13, marginBottom: 10 }} />
      <div className="skel" style={{ width: 220, height: 30, marginBottom: 28 }} />
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
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
