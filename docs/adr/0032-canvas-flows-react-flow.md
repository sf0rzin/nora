# ADR 0032 — NORA Flows canvas: React Flow (@xyflow/react) styled with NORA tokens

- **Status:** accepted
- **Date:** 2026-06-11
- **Related:** ADR 0013 (raw Tailwind, no shadcn, OKLCH tokens), ADR 0030 (workflow engine —
  the canvas edits the definition_json that the engine executes)

## Context

The NORA Flows visual builder (route `/fluxos`) needs: a canvas with a grid background, draggable
nodes, edges connectable by handles, selection, keyboard deletion, zoom/pan and
graph serialization. Deadline: pitch 15/06. GOAL.md asks to evaluate React Flow vs. a custom canvas
and to record the decision.

ADR 0013 vetoes COMPONENT libraries (shadcn/Radix) because the NORA editorial design cannot
look like a template. A graph interaction engine is another category: it does not impose an appearance — the nodes
are our own React components.

## Decision

**React Flow v12 (`@xyflow/react`, MIT)** with a 100% NORA appearance:

1. Nodes are our own React components (`nodeTypes`), styled with inline styles + `var(--token)`
   (DM Sans, `--canvas`/`--ink`/`--accent`/`--warn`/`--success`, radius and borders from design v3) —
   no visual component from the lib is used beyond `Background` (dots, color `--border-strong`),
   `Controls` (restyled via CSS) and the edges/handles engine (colors overridden in
   `flows.css`).
2. Our own serialization: RF nodes/edges ⇄ the backend's `definition_json` (`kind`/`type`/`params` in
   `node.data`; canvas position persisted so it reopens identically). The canvas knows only the
   block catalog that the backend validates (trigger + 4 conditions + registered actions).
3. The web's only new dependency. The lib's base CSS (`@xyflow/react/dist/style.css`) is functional
   (positioning), imported in a segmented layout of the route.

## Rejected alternatives

- **Custom canvas (SVG/pointer events):** total control and zero dependency, but
  robust drag-and-drop + handle connection + edge hit-testing + zoom/pan cost days of
  engineering and QA that do not exist before the pitch; the risk of broken UX in the demo is exactly
  what GOAL says to burn down early.
- **Other libs (rete.js, litegraph, jointjs):** less maintained, more visually opinionated or
  with worse licenses/weights than React Flow's lean MIT.

## Consequences

- Lib updates track React/Next (v12 supports React 18/19).
- The React Flow attribution attribute remains visible (the lib's MIT default) — acceptable.
- New node types = an entry in the front end's catalog + an ActionExecutor/condition in the backend; the canvas
  does not need to change.
