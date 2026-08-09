/**
 * NORA Flows — /fluxos/novo: creates a flow from scratch.
 * The canvas is born with the "Reunião analisada" trigger already positioned.
 */
import { EditorFluxo } from "../editor-fluxo";

export default function PaginaNovoFluxo() {
  return <EditorFluxo workflowId={null} />;
}
