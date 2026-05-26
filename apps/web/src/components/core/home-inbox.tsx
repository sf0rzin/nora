"use client";

/**
 * HomeInbox — inbox cronológico do Core (porto de `project/home.jsx`). Recebe
 * os grupos JÁ formatados pelo server component (datas resolvidas com um único
 * `nowMs`, sem mismatch de hidratação) e renderiza. Linhas degradam pros campos
 * REAIS de MeetingListItem: sem productivity pill / stack de participantes
 * enquanto o endpoint de lista não os expõe (ver issue de enriquecimento).
 */

import { useEffect, useState } from "react";
import Link from "next/link";
import type { Route } from "next";

import { ShaderOrb } from "@/components/brand/shader-orb";
import { getCurrentUser } from "@/lib/auth";

import { Avatar, Kbd } from "./bits";
import { useCoreShell } from "./core-shell";
import type { InboxGroup, InboxRow } from "./format";

export default function HomeInbox({
  groups,
  todayCount,
  totalItems,
  error,
}: {
  groups: InboxGroup[];
  todayCount: number;
  totalItems: number;
  error: string | null;
}) {
  const { openPalette } = useCoreShell();
  const [hello, setHello] = useState<{ greeting: string; name: string | null }>({
    greeting: "Olá",
    name: null,
  });

  // Hora/nome só no client → evita mismatch (server não tem o cookie nem a tz).
  useEffect(() => {
    const h = new Date().getHours();
    const greeting =
      h < 6 ? "Boa madrugada" : h < 12 ? "Bom dia" : h < 18 ? "Boa tarde" : "Boa noite";
    setHello({ greeting, name: getCurrentUser()?.displayName?.split(/\s+/)[0] ?? null });
  }, []);

  return (
    <div style={{ maxWidth: 920, margin: "0 auto" }}>
      <header
        style={{
          display: "flex",
          alignItems: "flex-end",
          justifyContent: "space-between",
          gap: 24,
          marginBottom: 36,
          flexWrap: "wrap",
        }}
      >
        <div>
          <div style={{ fontSize: 13, color: "var(--muted)", marginBottom: 6 }}>
            {hello.greeting}
            {hello.name ? `, ${hello.name}` : ""}.
          </div>
          <h1
            style={{
              fontFamily: "var(--display)",
              fontSize: 30,
              fontWeight: 500,
              letterSpacing: "-0.025em",
              lineHeight: 1.1,
              color: "var(--ink)",
              margin: 0,
            }}
          >
            {todayCount > 0
              ? `${todayCount} ${todayCount === 1 ? "reunião" : "reuniões"} hoje.`
              : "Sem reuniões hoje."}
          </h1>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <button
            onClick={openPalette}
            style={{
              display: "inline-flex",
              alignItems: "center",
              gap: 8,
              padding: "7px 11px 7px 12px",
              background: "var(--canvas)",
              border: "1px solid var(--border)",
              borderRadius: 8,
              fontFamily: "var(--sans)",
              fontSize: 12.5,
              color: "var(--muted)",
              cursor: "pointer",
            }}
            onMouseEnter={(e) => (e.currentTarget.style.background = "var(--sidebar)")}
            onMouseLeave={(e) => (e.currentTarget.style.background = "var(--canvas)")}
            title="Abrir paleta de comandos"
          >
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="11" cy="11" r="7" />
              <path d="m20 20-3.5-3.5" />
            </svg>
            <span>Buscar</span>
            <Kbd>⌘K</Kbd>
          </button>
          <Link
            href={"/meetings/upload" as Route}
            style={{
              display: "inline-flex",
              alignItems: "center",
              gap: 7,
              padding: "8px 14px",
              background: "var(--ink)",
              color: "var(--canvas)",
              borderRadius: 8,
              textDecoration: "none",
              fontFamily: "var(--sans)",
              fontSize: 13,
              fontWeight: 500,
              letterSpacing: "-0.005em",
            }}
            title="Atalho: N"
          >
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round">
              <path d="M12 5v14M5 12h14" />
            </svg>
            Nova reunião
          </Link>
        </div>
      </header>

      {error && (
        <div
          style={{
            marginBottom: 24,
            borderRadius: 10,
            border: "1px solid #e7c9c2",
            background: "rgba(201,119,102,0.08)",
            padding: "12px 14px",
            fontSize: 13,
            color: "#a04c3e",
          }}
        >
          {error}
        </div>
      )}

      {totalItems === 0 && !error ? (
        <div
          style={{
            display: "flex",
            flexDirection: "column",
            alignItems: "center",
            gap: 22,
            padding: "60px 24px",
            textAlign: "center",
          }}
        >
          <ShaderOrb size={120} speed={1} intensity={0.85} />
          <div style={{ maxWidth: 380 }}>
            <h2
              style={{
                fontFamily: "var(--display)",
                fontSize: 19,
                fontWeight: 500,
                letterSpacing: "-0.018em",
                margin: "0 0 6px",
                color: "var(--ink)",
              }}
            >
              Sua primeira reunião está a um upload de distância.
            </h2>
            <p style={{ fontSize: 13.5, color: "var(--muted)", margin: 0, lineHeight: 1.55 }}>
              A NORA processa a transcrição e devolve resumo, decisões e action items em segundos.
            </p>
          </div>
          <Link
            href={"/meetings/upload" as Route}
            style={{
              display: "inline-flex",
              alignItems: "center",
              gap: 7,
              padding: "10px 18px",
              background: "var(--ink)",
              color: "var(--canvas)",
              borderRadius: 9,
              textDecoration: "none",
              fontFamily: "var(--sans)",
              fontSize: 13,
              fontWeight: 500,
            }}
          >
            Fazer upload
          </Link>
        </div>
      ) : (
        <div style={{ display: "flex", flexDirection: "column", gap: 28 }}>
          {groups.map((g) => (
            <section key={g.key}>
              <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 8, padding: "0 14px" }}>
                <h3
                  style={{
                    fontSize: 11,
                    fontWeight: 500,
                    color: "var(--muted)",
                    letterSpacing: "0.08em",
                    textTransform: "uppercase",
                    margin: 0,
                  }}
                >
                  {g.key}
                </h3>
                <div style={{ flex: 1, height: 1, background: "var(--border)" }} />
                <span style={{ fontSize: 11, color: "var(--muted)" }}>{g.rows.length}</span>
              </div>
              <div style={{ display: "flex", flexDirection: "column", gap: 2 }}>
                {g.rows.map((r) => (
                  <MeetingRow key={r.id} r={r} />
                ))}
              </div>
            </section>
          ))}

          {todayCount === 0 && groups.length > 0 && (
            <div style={{ padding: "14px 14px 4px", fontSize: 12.5, color: "var(--muted)" }}>
              Sem reuniões hoje — mostrando a partir das mais recentes.
            </div>
          )}
        </div>
      )}

      <div
        style={{
          marginTop: 48,
          paddingTop: 18,
          borderTop: "1px solid var(--border)",
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          gap: 12,
          flexWrap: "wrap",
          fontSize: 11.5,
          color: "var(--muted)",
        }}
      >
        <div style={{ display: "flex", gap: 16, flexWrap: "wrap" }}>
          <span>Atalhos:</span>
          <span>
            <Kbd>N</Kbd> nova reunião
          </span>
          <span>
            <Kbd>/</Kbd> buscar
          </span>
          <span>
            <Kbd>⌘K</Kbd> comandos
          </span>
        </div>
        <span>PII Shield ativo</span>
      </div>
    </div>
  );
}

function MeetingRow({ r }: { r: InboxRow }) {
  const [hover, setHover] = useState(false);
  const [copied, setCopied] = useState(false);

  const inner = (
    <>
      <div style={{ display: "grid", placeItems: "center", width: 16 }}>
        {r.statusKind === "orb" ? (
          <div
            style={{
              width: 16,
              height: 16,
              borderRadius: "50%",
              overflow: "hidden",
              flexShrink: 0,
              animation: "noraOrbPulse 1.6s ease-in-out infinite",
            }}
            title={r.statusLabel}
          >
            <ShaderOrb size={16} speed={1.8} intensity={1} />
          </div>
        ) : (
          <div
            title={r.statusLabel}
            style={{ width: 8, height: 8, borderRadius: "50%", background: r.statusColor, flexShrink: 0 }}
          />
        )}
      </div>

      <div style={{ fontSize: 12, color: "var(--muted)", fontVariantNumeric: "tabular-nums", whiteSpace: "nowrap" }}>
        {r.timeLabel}
      </div>

      <div style={{ minWidth: 0, display: "flex", flexDirection: "column", gap: 3 }}>
        <span
          style={{
            fontSize: 14.5,
            fontWeight: 500,
            color: "var(--ink)",
            letterSpacing: "-0.012em",
            whiteSpace: "nowrap",
            overflow: "hidden",
            textOverflow: "ellipsis",
          }}
        >
          {r.title}
        </span>
        <div style={{ display: "flex", alignItems: "center", gap: 10, fontSize: 12, color: "var(--muted)", flexWrap: "wrap" }}>
          {r.tag && (
            <span style={{ padding: "1px 8px", borderRadius: 999, background: "var(--chip)", color: "var(--ink)", fontSize: 11 }}>
              {r.tag}
            </span>
          )}
          {r.statusKind === "orb" ? (
            <span style={{ color: "var(--accent-ink)" }}>Analisando…</span>
          ) : (
            <>
              {r.durationLabel && <span>{r.durationLabel}</span>}
              {r.actionItemCount > 0 && (
                <span style={{ fontVariantNumeric: "tabular-nums" }}>
                  {r.actionItemCount} {r.actionItemCount === 1 ? "action" : "actions"}
                </span>
              )}
              {r.riskCount > 0 && <span style={{ fontVariantNumeric: "tabular-nums" }}>{r.riskCount} riscos</span>}
              {r.status === "FAILED" && <span style={{ color: "#a04c3e" }}>Falha na análise</span>}
            </>
          )}
        </div>
      </div>

      <div style={{ display: "flex", justifyContent: "flex-end" }}>
        <Avatar name={r.ownerName} />
      </div>

      <div
        style={{
          display: "flex",
          alignItems: "center",
          gap: 4,
          opacity: hover && r.clickable ? 1 : 0,
          transition: "opacity 140ms ease",
          pointerEvents: hover && r.clickable ? "auto" : "none",
        }}
      >
        <span
          role="button"
          title={copied ? "Link copiado" : "Copiar link"}
          onClick={(e) => {
            e.preventDefault();
            e.stopPropagation();
            void navigator.clipboard
              ?.writeText(`${window.location.origin}/meetings/${r.id}`)
              .then(() => {
                setCopied(true);
                setTimeout(() => setCopied(false), 1200);
              })
              .catch(() => {});
          }}
          style={{
            width: 26,
            height: 26,
            display: "grid",
            placeItems: "center",
            borderRadius: 6,
            color: copied ? "#3f8a5e" : "var(--muted)",
            cursor: "pointer",
          }}
          onMouseEnter={(e) => {
            e.currentTarget.style.background = "var(--chip)";
          }}
          onMouseLeave={(e) => {
            e.currentTarget.style.background = "transparent";
          }}
        >
          {copied ? (
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <polyline points="20 6 9 17 4 12" />
            </svg>
          ) : (
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
              <path d="M10 13a5 5 0 0 0 7.07 0l3-3a5 5 0 0 0-7.07-7.07L11 5" />
              <path d="M14 11a5 5 0 0 0-7.07 0l-3 3a5 5 0 0 0 7.07 7.07L13 19" />
            </svg>
          )}
        </span>
      </div>
    </>
  );

  const gridStyle: React.CSSProperties = {
    width: "100%",
    display: "grid",
    gridTemplateColumns: "16px 56px 1fr auto 30px",
    alignItems: "center",
    gap: 14,
    padding: "12px 14px",
    borderRadius: 10,
    background: hover && r.clickable ? "var(--sidebar)" : "transparent",
    textAlign: "left",
    color: "var(--ink)",
    transition: "background 120ms ease",
    textDecoration: "none",
  };

  if (!r.clickable) {
    return (
      <div style={{ ...gridStyle, cursor: "default" }}>{inner}</div>
    );
  }

  return (
    <Link
      href={`/meetings/${r.id}` as Route}
      style={{ ...gridStyle, cursor: "pointer" }}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
    >
      {inner}
    </Link>
  );
}
