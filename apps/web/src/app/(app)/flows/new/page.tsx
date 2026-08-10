/**
 * NORA Flows — /flows/new: creates a flow from scratch.
 * The canvas is born with the "meeting analysed" trigger already positioned.
 */
import { FlowEditor } from "../flow-editor";

export default function NewFlowPage() {
  return <FlowEditor workflowId={null} />;
}
