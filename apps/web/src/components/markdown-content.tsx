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
 * - Paleta editorial via tokens (--ink / --muted / --accent-ink / --chip),
 *   coerente com o kit de UID do Core. Layout/espaçamentos seguem Tailwind.
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
    <h1 className="mt-4 mb-2 text-xl font-semibold" style={{ color: "var(--ink)" }} {...props} />
  ),
  h2: (props: ComponentPropsWithoutRef<"h2">) => (
    <h2 className="mt-3 mb-2 text-lg font-semibold" style={{ color: "var(--ink)" }} {...props} />
  ),
  h3: (props: ComponentPropsWithoutRef<"h3">) => (
    <h3 className="mt-2 mb-1 text-base font-semibold" style={{ color: "var(--ink)" }} {...props} />
  ),
  p: (props: ComponentPropsWithoutRef<"p">) => (
    <p className="mb-2 leading-relaxed last:mb-0" style={{ color: "var(--ink)" }} {...props} />
  ),
  ul: (props: ComponentPropsWithoutRef<"ul">) => (
    <ul className="mb-2 list-disc space-y-1 pl-5 last:mb-0" style={{ color: "var(--ink)" }} {...props} />
  ),
  ol: (props: ComponentPropsWithoutRef<"ol">) => (
    <ol className="mb-2 list-decimal space-y-1 pl-5 last:mb-0" style={{ color: "var(--ink)" }} {...props} />
  ),
  li: (props: ComponentPropsWithoutRef<"li">) => <li className="leading-relaxed" {...props} />,
  strong: (props: ComponentPropsWithoutRef<"strong">) => (
    <strong className="font-semibold" style={{ color: "var(--ink)" }} {...props} />
  ),
  em: (props: ComponentPropsWithoutRef<"em">) => <em className="italic" {...props} />,
  code: (props: ComponentPropsWithoutRef<"code">) => (
    <code
      className="rounded px-1 py-0.5 text-sm font-mono"
      style={{ background: "var(--chip)", color: "var(--ink)" }}
      {...props}
    />
  ),
  a: (props: ComponentPropsWithoutRef<"a">) => (
    <a
      className="underline-offset-2 hover:underline"
      style={{ color: "var(--accent-ink)" }}
      target="_blank"
      rel="noopener noreferrer"
      {...props}
    />
  ),
  blockquote: (props: ComponentPropsWithoutRef<"blockquote">) => (
    <blockquote
      className="my-2 border-l-2 pl-3 italic"
      style={{ borderColor: "var(--border-strong)", color: "var(--muted)" }}
      {...props}
    />
  ),
  hr: (props: ComponentPropsWithoutRef<"hr">) => (
    <hr className="my-4" style={{ borderColor: "var(--border)" }} {...props} />
  ),
  table: (props: ComponentPropsWithoutRef<"table">) => (
    <div className="my-2 overflow-x-auto">
      <table className="w-full border-collapse text-sm" {...props} />
    </div>
  ),
  th: (props: ComponentPropsWithoutRef<"th">) => (
    <th
      className="px-2 py-1 text-left font-semibold"
      style={{ border: "1px solid var(--border)", background: "var(--chip)", color: "var(--ink)" }}
      {...props}
    />
  ),
  td: (props: ComponentPropsWithoutRef<"td">) => (
    <td
      className="px-2 py-1 align-top"
      style={{ border: "1px solid var(--border)", color: "var(--ink)" }}
      {...props}
    />
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
