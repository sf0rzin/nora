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
 * - Paleta slate (espelha CorporateDomainCard / o resto do app interno).
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
    <h1 className="mt-4 mb-2 text-xl font-semibold text-slate-900" {...props} />
  ),
  h2: (props: ComponentPropsWithoutRef<"h2">) => (
    <h2 className="mt-3 mb-2 text-lg font-semibold text-slate-900" {...props} />
  ),
  h3: (props: ComponentPropsWithoutRef<"h3">) => (
    <h3 className="mt-2 mb-1 text-base font-semibold text-slate-900" {...props} />
  ),
  p: (props: ComponentPropsWithoutRef<"p">) => (
    <p className="mb-2 leading-relaxed last:mb-0" {...props} />
  ),
  ul: (props: ComponentPropsWithoutRef<"ul">) => (
    <ul className="mb-2 list-disc space-y-1 pl-5 last:mb-0" {...props} />
  ),
  ol: (props: ComponentPropsWithoutRef<"ol">) => (
    <ol className="mb-2 list-decimal space-y-1 pl-5 last:mb-0" {...props} />
  ),
  li: (props: ComponentPropsWithoutRef<"li">) => <li className="leading-relaxed" {...props} />,
  strong: (props: ComponentPropsWithoutRef<"strong">) => (
    <strong className="font-semibold text-slate-900" {...props} />
  ),
  em: (props: ComponentPropsWithoutRef<"em">) => <em className="italic" {...props} />,
  code: (props: ComponentPropsWithoutRef<"code">) => (
    <code className="rounded bg-slate-100 px-1 py-0.5 text-sm font-mono" {...props} />
  ),
  a: (props: ComponentPropsWithoutRef<"a">) => (
    <a
      className="text-blue-600 underline-offset-2 hover:underline"
      target="_blank"
      rel="noopener noreferrer"
      {...props}
    />
  ),
  blockquote: (props: ComponentPropsWithoutRef<"blockquote">) => (
    <blockquote
      className="my-2 border-l-2 border-slate-300 pl-3 italic text-slate-600"
      {...props}
    />
  ),
  hr: (props: ComponentPropsWithoutRef<"hr">) => (
    <hr className="my-4 border-slate-200" {...props} />
  ),
  table: (props: ComponentPropsWithoutRef<"table">) => (
    <div className="my-2 overflow-x-auto">
      <table className="w-full border-collapse text-sm" {...props} />
    </div>
  ),
  th: (props: ComponentPropsWithoutRef<"th">) => (
    <th
      className="border border-slate-200 bg-slate-50 px-2 py-1 text-left font-semibold"
      {...props}
    />
  ),
  td: (props: ComponentPropsWithoutRef<"td">) => (
    <td className="border border-slate-200 px-2 py-1 align-top" {...props} />
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
