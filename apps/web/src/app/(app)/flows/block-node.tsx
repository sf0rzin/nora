"use client";

/**
 * NORA Flows — custom canvas node (React Flow nodeType "bloco").
 *
 * ~220px card with the role header (colored trigger/condition/action), block
 * title and a params summary line. Horizontal n8n-style flow: input handle on
 * the LEFT, output on the RIGHT. A trigger has no input; an action has no
 * output (it is a graph leaf).
 */
import { Handle, Position, type Node, type NodeProps } from "@xyflow/react";

import type { WorkflowNodeKind } from "@/lib/api/types";

import { KindIcon, KIND_META, blockMeta } from "./catalog";

/**
 * Node data in React Flow. The RF `type` is the nodeType key ("bloco");
 * the engine catalog type lives in `blockType` so they do not collide.
 */
export type NodeData = {
  kind: WorkflowNodeKind;
  blockType: string;
  params: Record<string, unknown>;
};

export type RFNode = Node<NodeData, "bloco">;

export function BlockNode({ data, selected }: NodeProps<RFNode>) {
  const meta = blockMeta(data.blockType);
  const kindMeta = KIND_META[data.kind];
  const summary = meta ? meta.summary(data.params) : null;

  return (
    <div className={`flow-node${selected ? " is-selected" : ""}`}>
      {data.kind !== "trigger" && <Handle type="target" position={Position.Left} />}

      <div className="kind" style={{ color: kindMeta.color }}>
        {meta?.Icon ? <meta.Icon /> : <KindIcon kind={data.kind} />}
        {kindMeta.label}
      </div>
      <div className="title">{meta?.name ?? data.blockType}</div>
      {summary && <div className="summary">{summary}</div>}

      {data.kind !== "action" && <Handle type="source" position={Position.Right} />}
    </div>
  );
}
