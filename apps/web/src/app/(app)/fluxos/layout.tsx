/**
 * NORA Flows — layout da rota /fluxos.
 *
 * Centraliza os imports de CSS do builder: o stylesheet base do React Flow
 * (@xyflow/react) e os overrides com os tokens NORA (flows.css). Assim a
 * lista e o editor compartilham o mesmo bundle de estilos.
 */
import "@xyflow/react/dist/style.css";
import "./flows.css";

export default function FluxosLayout({ children }: { children: React.ReactNode }) {
  return <>{children}</>;
}
