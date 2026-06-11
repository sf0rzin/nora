/**
 * NORA Flows — /fluxos/novo: cria um fluxo do zero.
 * O canvas já nasce com o gatilho "Reunião analisada" posicionado.
 */
import { EditorFluxo } from "../editor-fluxo";

export default function PaginaNovoFluxo() {
  return <EditorFluxo workflowId={null} />;
}
