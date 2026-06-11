# ADR 0032 — Canvas do NORA Flows: React Flow (@xyflow/react) estilizado com tokens NORA

- **Status:** aceito
- **Data:** 2026-06-11
- **Decisores:** Arquiteto NORA (run do pitch) + Stratfy (PO, via GOAL.md)
- **Relacionados:** ADR 0013 (Tailwind cru, sem shadcn, tokens OKLCH), ADR 0030 (workflow engine —
  o canvas edita o definition_json que o engine executa)

## Contexto

O builder visual do NORA Flows (rota `/fluxos`) precisa de: canvas com fundo de grid, nós
arrastáveis, arestas conectáveis por handles, seleção, deleção por teclado, zoom/pan e
serialização do grafo. Deadline: pitch 15/06. O GOAL.md pede avaliar React Flow vs. canvas custom
e registrar a decisão.

ADR 0013 veta bibliotecas de COMPONENTES (shadcn/Radix) porque o design editorial NORA não pode
parecer template. Um motor de interação de grafo é outra categoria: não impõe aparência — os nós
são componentes React nossos.

## Decisão

**React Flow v12 (`@xyflow/react`, MIT)** com aparência 100% NORA:

1. Nós são componentes React próprios (`nodeTypes`), estilizados com inline styles + `var(--token)`
   (DM Sans, `--canvas`/`--ink`/`--accent`/`--warn`/`--success`, radius e bordas do design v3) —
   nenhum componente visual da lib é usado além de `Background` (dots, cor `--border-strong`),
   `Controls` (reestilizado via CSS) e o motor de arestas/handles (cores sobrescritas em
   `flows.css`).
2. Serialização própria: nós/arestas RF ⇄ `definition_json` do backend (`kind`/`type`/`params` em
   `node.data`; posição do canvas persistida para reabrir igual). O canvas conhece apenas o
   catálogo de blocos que o backend valida (gatilho + 4 condições + ações registradas).
3. Única dependência nova do web. CSS base da lib (`@xyflow/react/dist/style.css`) é funcional
   (posicionamento), importado num layout segmentado da rota.

## Alternativas rejeitadas

- **Canvas custom (SVG/pointer events):** controle total e zero dependência, mas
  drag-and-drop + conexão de handles + hit-testing de arestas + zoom/pan robustos custam dias de
  engenharia e QA que não existem antes do pitch; o risco de UX quebrada na demo é exatamente o
  que o GOAL manda queimar cedo.
- **Outras libs (rete.js, litegraph, jointjs):** menos mantidas, mais opinativas visualmente ou
  com licenças/pesos piores que o MIT enxuto do React Flow.

## Consequências

- Atualizações da lib acompanham React/Next (v12 suporta React 18/19).
- O atributo de atribuição do React Flow permanece visível (padrão MIT da lib) — aceitável.
- Novos tipos de nó = entrada no catálogo do front + ActionExecutor/condição no backend; o canvas
  não precisa mudar.
