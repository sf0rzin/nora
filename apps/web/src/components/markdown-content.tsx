"use client";

/**
 * MarkdownContent (Subfase 1.3 M)
 * -------------------------------------------------------------------------
 * Renderiza markdown produzido pelo LLM (resumos, descricoes longas) com
 * sanitizacao built-in do `react-markdown`. NAO usa `rehype-raw` — HTML
 * inline nao e permitido, o que mantem a superficie de XSS minima.
 *
 * Onde usar:
 * - Campos de texto livre vindos do worker (ex: `MeetingAnalysis.summary`).
 * - Qualquer descricao longa controlada pelo tenant que aceite formatacao
 *   leve (listas, negrito, headers).
 *
 * Onde NAO usar:
 * - Items curtos de listas estruturadas (decision.text, risk.text, etc.).
 *   Markdown ali quebraria o layout das pills/badges e nao agrega valor.
 *
 * Estilos:
 * - Paleta v3 (tokens OKLCH: --ink / --muted / --accent-ink / --border / --chip).
 * - Espacamentos compactos suficientes para inline em sections.
 */

import type { ComponentPropsWithoutRef } from "react";
import ReactMarkdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";

type Props = {
  children: string;
  className?: string;
};

const components: Components = {
  h1: (props: ComponentPropsWithoutRef<"h1">) => (
    <h1 style={{ marginTop: 16, marginBottom: 8, fontSize: 20, fontWeight: 600, color: "var(--ink)" }} {...props} />
  ),
  h2: (props: ComponentPropsWithoutRef<"h2">) => (
    <h2 style={{ marginTop: 12, marginBottom: 8, fontSize: 18, fontWeight: 600, color: "var(--ink)" }} {...props} />
  ),
  h3: (props: ComponentPropsWithoutRef<"h3">) => (
    <h3 style={{ marginTop: 8, marginBottom: 4, fontSize: 16, fontWeight: 600, color: "var(--ink)" }} {...props} />
  ),
  p: (props: ComponentPropsWithoutRef<"p">) => (
    <p className="last:mb-0" style={{ marginBottom: 8, lineHeight: 1.65 }} {...props} />
  ),
  ul: (props: ComponentPropsWithoutRef<"ul">) => (
    <ul className="last:mb-0" style={{ marginBottom: 8, paddingLeft: 20, listStyleType: "disc" }} {...props} />
  ),
  ol: (props: ComponentPropsWithoutRef<"ol">) => (
    <ol className="last:mb-0" style={{ marginBottom: 8, paddingLeft: 20, listStyleType: "decimal" }} {...props} />
  ),
  li: (props: ComponentPropsWithoutRef<"li">) => <li style={{ lineHeight: 1.65, marginBottom: 2 }} {...props} />,
  strong: (props: ComponentPropsWithoutRef<"strong">) => (
    <strong style={{ fontWeight: 600, color: "var(--ink)" }} {...props} />
  ),
  em: (props: ComponentPropsWithoutRef<"em">) => <em style={{ fontStyle: "italic" }} {...props} />,
  code: (props: ComponentPropsWithoutRef<"code">) => (
    <code style={{ borderRadius: 4, background: "var(--chip)", padding: "1px 4px", fontSize: 13 }} {...props} />
  ),
  a: (props: ComponentPropsWithoutRef<"a">) => (
    <a
      style={{ color: "var(--accent-ink)", textUnderlineOffset: 2 }}
      target="_blank"
      rel="noopener noreferrer"
      {...props}
    />
  ),
  blockquote: (props: ComponentPropsWithoutRef<"blockquote">) => (
    <blockquote
      style={{ margin: "8px 0", borderLeft: "2px solid var(--border-strong)", paddingLeft: 12, fontStyle: "italic", color: "var(--muted)" }}
      {...props}
    />
  ),
  hr: (props: ComponentPropsWithoutRef<"hr">) => (
    <hr style={{ margin: "16px 0", border: "none", borderTop: "1px solid var(--border)" }} {...props} />
  ),
  table: (props: ComponentPropsWithoutRef<"table">) => (
    <div style={{ margin: "8px 0", overflowX: "auto" }}>
      <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 13 }} {...props} />
    </div>
  ),
  th: (props: ComponentPropsWithoutRef<"th">) => (
    <th
      style={{ border: "1px solid var(--border)", background: "var(--sidebar)", padding: "4px 8px", textAlign: "left", fontWeight: 600 }}
      {...props}
    />
  ),
  td: (props: ComponentPropsWithoutRef<"td">) => (
    <td style={{ border: "1px solid var(--border)", padding: "4px 8px", verticalAlign: "top" }} {...props} />
  ),
};

export function MarkdownContent({ children, className }: Props) {
  return (
    <div className={className}>
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>
        {children}
      </ReactMarkdown>
    </div>
  );
}
