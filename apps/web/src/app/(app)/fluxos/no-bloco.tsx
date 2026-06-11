"use client";

/**
 * NORA Flows — nó custom do canvas (React Flow nodeType "bloco").
 *
 * Card ~220px com header do papel (GATILHO/CONDIÇÃO/AÇÃO colorido), título do
 * bloco e linha de resumo dos params. Fluxo horizontal estilo n8n: handle de
 * entrada à ESQUERDA, saída à DIREITA. Gatilho não tem entrada; ação não tem
 * saída (é folha do grafo).
 */
import { Handle, Position, type Node, type NodeProps } from "@xyflow/react";

import type { WorkflowNodeKind } from "@/lib/api/types";

import { IconeKind, KIND_META, metaDoBloco } from "./catalogo";

/**
 * Dados do nó no React Flow. O `type` do RF é a chave do nodeType ("bloco");
 * o tipo do catálogo do engine vive em `blockType` pra não colidir.
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
