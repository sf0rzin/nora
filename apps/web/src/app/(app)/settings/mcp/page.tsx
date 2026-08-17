"use client";

import { useEffect, useState } from "react";
import {
  ApiRequestError,
  createMcpToken,
  listMcpTokens,
  revokeMcpToken,
  type McpTokenSummary,
} from "@/lib/api/client";
import { formatDateTime } from "@/lib/utils";

/**
 * NORA Core — MCP credentials (US27, ADR 0041 §3).
 *
 * A sibling ROUTE under the settings layout rather than a section of the hub switcher, for the
 * same reason `settings/iam` is one: it carries its own data loading and its own forms.
 *
 * The screen exists because ADR 0041 puts the minting in the hands of a user authenticated in the
 * web application. Without it the MCP server is designed and unusable, so this is deliberately
 * small: a list, a create form, a one-time reveal, and revoke.
 *
 * The one-time reveal is the whole point of the layout. The server stores only a SHA-256 hash and
 * cannot produce the plaintext again, so the panel says so before the user navigates away, and
 * `revealed` is dropped from state the moment they dismiss it — nothing keeps a live credential in
 * a React tree longer than the user is looking at it.
 *
 * UI copy is pt-BR like the rest of the product; identifiers and comments are English.
 */
export default function McpSettingsPage() {
  const [tokens, setTokens] = useState<McpTokenSummary[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [name, setName] = useState("");
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);
  const [revealed, setRevealed] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  const [revoking, setRevoking] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    listMcpTokens()
      .then((items) => {
        if (!cancelled) setTokens(items);
      })
      .catch((err) => {
        if (cancelled) return;
        setLoadError(message(err, "Não foi possível carregar as credenciais."));
      });
    return () => {
      cancelled = true;
    };
  }, []);

  async function reload() {
    try {
      setTokens(await listMcpTokens());
    } catch (err) {
      setLoadError(message(err, "Não foi possível atualizar a lista."));
    }
  }

  async function onCreate(e: React.FormEvent) {
    e.preventDefault();
    const label = name.trim();
    if (!label) {
      setCreateError("Dê um nome para reconhecer esta credencial depois.");
      return;
    }
    setCreating(true);
    setCreateError(null);
    try {
      const created = await createMcpToken({ name: label });
      setRevealed(created.token);
      setCopied(false);
      setName("");
      await reload();
    } catch (err) {
      setCreateError(message(err, "Não foi possível criar a credencial."));
    } finally {
      setCreating(false);
    }
  }

  async function onCopy() {
    if (!revealed) return;
    try {
      await navigator.clipboard.writeText(revealed);
      setCopied(true);
    } catch {
      // Clipboard blocked (insecure context, permission denied): the value is on screen and
      // selectable, so this is a missing convenience, not a dead end.
      setCopied(false);
    }
  }

  async function onRevoke(token: McpTokenSummary) {
    setRevoking(token.id);
    try {
      await revokeMcpToken(token.id);
      await reload();
    } catch (err) {
      setLoadError(message(err, "Não foi possível revogar a credencial."));
    } finally {
      setRevoking(null);
    }
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 28 }}>
      <section className="section">
        <div className="section-head">
          <h2 className="sec-label">Credenciais MCP</h2>
        </div>
        <p className="field-help" style={{ marginBottom: 16 }}>
          Uma credencial MCP deixa um cliente externo — Claude Desktop, uma IDE, um agente — ler
          suas reuniões, tarefas, busca semântica e Customer Confidence. Ela age em seu nome: vê
          exatamente o que você vê no NORA, nunca mais que isso, e é somente leitura.
        </p>

        <form onSubmit={onCreate} style={{ display: "flex", flexDirection: "column", gap: 14 }}>
          <div className="field">
            <label className="field-label" htmlFor="mcp-name">
              Nome da credencial
            </label>
            <input
              className="input"
              id="mcp-name"
              value={name}
              maxLength={80}
              placeholder="Claude Desktop do notebook"
              onChange={(e) => setName(e.target.value)}
              style={{ maxWidth: 360 }}
            />
            <div className="field-help">
              Serve para você reconhecer qual revogar. Não é secreto.
            </div>
          </div>

          {createError && <div className="notice notice--danger">{createError}</div>}

          <div>
            <button className="btn btn-primary btn-sm" type="submit" disabled={creating}>
              {creating ? "Criando…" : "Criar credencial"}
            </button>
          </div>
        </form>
      </section>

      {revealed && (
        <section className="section">
          <div className="section-head">
            <h2 className="sec-label">Copie agora</h2>
          </div>
          <div className="notice notice--accent" style={{ marginBottom: 12 }}>
            Este valor aparece uma única vez. O servidor guarda apenas um hash e não consegue
            mostrá-lo de novo — se você perder, revogue esta credencial e crie outra.
          </div>
          <code
            style={{
              display: "block",
              padding: "12px 14px",
              borderRadius: 8,
              background: "var(--sidebar)",
              fontSize: 12.5,
              wordBreak: "break-all",
              userSelect: "all",
            }}
          >
            {revealed}
          </code>
          <div style={{ display: "flex", alignItems: "center", gap: 12, marginTop: 12 }}>
            <button className="btn btn-primary btn-sm" type="button" onClick={onCopy}>
              {copied ? "Copiado" : "Copiar"}
            </button>
            <button
              className="btn btn-ghost btn-sm"
              type="button"
              onClick={() => {
                setRevealed(null);
                setCopied(false);
              }}
            >
              Já guardei
            </button>
          </div>
        </section>
      )}

      <section className="section">
        <div className="section-head">
          <h2 className="sec-label">Suas credenciais</h2>
        </div>

        {loadError && <div className="notice notice--danger">{loadError}</div>}

        {tokens === null && !loadError && (
          <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
            <div className="skel" style={{ height: 38 }} />
            <div className="skel" style={{ height: 38 }} />
          </div>
        )}

        {tokens !== null && tokens.length === 0 && (
          <p className="field-help">Nenhuma credencial criada ainda.</p>
        )}

        {tokens !== null && tokens.length > 0 && (
          <ul style={{ display: "flex", flexDirection: "column", gap: 10, listStyle: "none" }}>
            {tokens.map((token) => (
              <li
                key={token.id}
                style={{
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "space-between",
                  gap: 12,
                  flexWrap: "wrap",
                  padding: "10px 0",
                  borderBottom: "1px solid var(--border)",
                }}
              >
                <div>
                  <div style={{ fontSize: 13.5 }}>
                    {token.name}{" "}
                    {!token.active && <span className="chip">Revogada</span>}
                  </div>
                  <div style={{ fontSize: 12, color: "var(--muted)", marginTop: 4 }}>
                    Criada em {formatDateTime(token.createdAt)}
                    {token.lastUsedAt
                      ? ` · último uso em ${formatDateTime(token.lastUsedAt)}`
                      : " · nunca usada"}
                  </div>
                </div>
                {token.active && (
                  <button
                    className="btn btn-ghost btn-sm"
                    type="button"
                    disabled={revoking === token.id}
                    onClick={() => onRevoke(token)}
                  >
                    {revoking === token.id ? "Revogando…" : "Revogar"}
                  </button>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

/** API errors carry pt-BR copy already; anything else falls back to the caller's sentence. */
function message(err: unknown, fallback: string): string {
  return err instanceof ApiRequestError ? err.message : fallback;
}
