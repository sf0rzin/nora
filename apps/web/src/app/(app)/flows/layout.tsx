/**
 * NORA Flows — layout of the /flows route.
 *
 * Centralizes the builder's CSS imports: the React Flow base stylesheet
 * (@xyflow/react) and the overrides with the NORA tokens (flows.css). This way
 * the list and the editor share the same style bundle.
 */
import "@xyflow/react/dist/style.css";
import "./flows.css";

export default function FlowsLayout({ children }: { children: React.ReactNode }) {
  return <>{children}</>;
}
