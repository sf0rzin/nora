"use client";

/**
 * NORA Flows — block palette (left column of the editor).
 *
 * v1 catalog blocks grouped by section. CLICK adds to the canvas
 * (the editor decides the position). The trigger is disabled when one already
 * exists on the canvas — the engine requires exactly one per flow.
 */
import { CATALOGO, IconeKind, KIND_META, type BlocoMeta } from "./catalogo";

const SECOES = [
  { rotulo: "Gatilho", kind: "trigger" as const },
  { rotulo: "Condições", kind: "condition" as const },
  { rotulo: "Ações", kind: "action" as const },
];

export function PaletaBlocos({
  temGatilho,
  onAdicionar,
}: {
  temGatilho: boolean;
  onAdicionar: (bloco: BlocoMeta) => void;
}) {
  return (
    <aside className="flows-palette" aria-label="Paleta de blocos">
      {SECOES.map((secao) => (
        <div key={secao.kind} style={{ display: "contents" }}>
          <div className="sec-label">{secao.rotulo}</div>
          {CATALOGO.filter((b) => b.kind === secao.kind).map((bloco) => {
            const bloqueado = bloco.kind === "trigger" && temGatilho;
            return (
              <button
                key={bloco.type}
                type="button"
                className="flows-bloco"
                disabled={bloqueado}
                title={
                  bloqueado
                    ? "O fluxo já tem um gatilho — só pode haver um por fluxo."
                    : "Clique para adicionar ao canvas"
                }
                onClick={() => onAdicionar(bloco)}
              >
                <span className="ic" style={{ color: KIND_META[bloco.kind].cor }}>
                  {bloco.Icone ? <bloco.Icone size={15} /> : <IconeKind kind={bloco.kind} size={15} />}
                </span>
                <span style={{ minWidth: 0 }}>
                  <span className="nome" style={{ display: "block" }}>
                    {bloco.nome}
                  </span>
                  <span className="desc" style={{ display: "block" }}>
                    {bloco.descricao}
                  </span>
                </span>
              </button>
            );
          })}
        </div>
      ))}
    </aside>
  );
}
