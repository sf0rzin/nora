"use client";

/**
 * NORA Flows — custom canvas node (React Flow nodeType "bloco").
 *
 * ~220px card with the role header (colored GATILHO/CONDIÇÃO/AÇÃO), block
 * title and a params summary line. Horizontal n8n-style flow: input handle on
 * the LEFT, output on the RIGHT. A trigger has no input; an action has no
 * output (it is a graph leaf).
 */
import { Handle, Position, type Node, type NodeProps } from "@xyflow/react";

import type { WorkflowNodeKind } from "@/lib/api/types";

import { IconeKind, KIND_META, metaDoBloco } from "./catalogo";

/**
 * Node data in React Flow. The RF `type` is the nodeType key ("bloco");
 * the engine catalog type lives in `blockType` so they do not collide.
 */
export type DadosNo = {
  kind: WorkflowNodeKind;
  blockType: string;
  params: Record<string, unknown>;
};

export type NoRF = Node<DadosNo, "bloco">;

export function NoBloco({ data, selected }: NodeProps<NoRF>) {
  const meta = metaDoBloco(data.blockType);
  const kindMeta = KIND_META[data.kind];
  const resumo = meta ? meta.resumo(data.params) : null;

  return (
    <div className={`flow-node${selected ? " is-selected" : ""}`}>
      {data.kind !== "trigger" && <Handle type="target" position={Position.Left} />}

      <div className="kind" style={{ color: kindMeta.cor }}>
        {meta?.Icone ? <meta.Icone /> : <IconeKind kind={data.kind} />}
        {kindMeta.rotulo}
      </div>
      <div className="titulo">{meta?.nome ?? data.blockType}</div>
      {resumo && <div className="resumo">{resumo}</div>}

      {data.kind !== "action" && <Handle type="source" position={Position.Right} />}
    </div>
  );
}
