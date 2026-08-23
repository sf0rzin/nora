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
import { strings } from "@/lib/strings";

import { KindIcon, KIND_META, blockMeta } from "./catalog";

const copy = strings.flows.node;

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

/**
 * What a screen reader gets out of this node, and what it still does not.
 *
 * The card used to be four unlabelled `<div>`s and two `Handle`s with no accessible name, so the
 * graph reached the accessibility tree as a pile of anonymous text: nothing said which block a
 * line of text belonged to, nor which end of a connection a control was. The `role`/`aria-label`
 * below fix the reading of the graph — every node announces its kind, its block and its
 * parameters as one item.
 *
 * The WRITING of the graph is not fixed and cannot be from here. @xyflow's `Handle` connects by
 * pointer drag only; there is no keyboard equivalent to reach for, and inventing one means
 * reimplementing connection state outside the library. The block palette already adds nodes with
 * real buttons, so a keyboard user can build the nodes and not the edges between them. That is
 * written down rather than left to be rediscovered by whoever audits this next.
 */
export function BlockNode({ data, selected }: NodeProps<RFNode>) {
  const meta = blockMeta(data.blockType);
  const kindMeta = KIND_META[data.kind];
  const summary = meta ? meta.summary(data.params) : null;
  const name = meta?.name ?? data.blockType;
  const label = [kindMeta.label, name, summary].filter(Boolean).join(" — ");

  return (
    <div
      className={`flow-node${selected ? " is-selected" : ""}`}
      role="group"
      aria-label={label}
    >
      {data.kind !== "trigger" && (
        <Handle type="target" position={Position.Left} aria-label={copy.inputHandle(name)} />
      )}

      <div className="kind" style={{ color: kindMeta.color }}>
        {meta?.Icon ? <meta.Icon /> : <KindIcon kind={data.kind} />}
        {kindMeta.label}
      </div>
      <div className="title">{name}</div>
      {summary && <div className="summary">{summary}</div>}

      {data.kind !== "action" && (
        <Handle type="source" position={Position.Right} aria-label={copy.outputHandle(name)} />
      )}
    </div>
  );
}
