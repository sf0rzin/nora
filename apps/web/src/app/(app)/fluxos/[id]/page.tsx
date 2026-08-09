"use client";

/**
 * NORA Flows — /fluxos/[id]: edits an existing flow.
 * Next 15: `params` arrives as a Promise in client pages — unwrap it with use().
 */
import { use } from "react";

import { EditorFluxo } from "../editor-fluxo";

export default function PaginaFluxo({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  return <EditorFluxo workflowId={id} />;
}
