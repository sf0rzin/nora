"use client";

/**
 * NORA Flows — /flows/[id]: edits an existing flow.
 * Next 15: `params` arrives as a Promise in client pages — unwrap it with use().
 */
import { use } from "react";

import { FlowEditor } from "../flow-editor";

export default function FlowPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  return <FlowEditor workflowId={id} />;
}
