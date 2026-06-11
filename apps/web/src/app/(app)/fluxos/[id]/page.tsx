"use client";

/**
 * NORA Flows — /fluxos/[id]: edita um fluxo existente.
 * Next 15: `params` chega como Promise em páginas client — desembrulha com use().
 */
import { use } from "react";

import { EditorFluxo } from "../editor-fluxo";

export default function PaginaFluxo({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  return <EditorFluxo workflowId={id} />;
}
