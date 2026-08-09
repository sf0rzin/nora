# 0013 — Frontend CSS strategy (raw Tailwind, no shadcn, OKLCH tokens)

- Status: Proposed (drafted by the Tech Lead; awaiting refinement by the Design Architect)
- Date: 2026-05-14
- Deciders: Design Architect (owner of the frontend scope)

> **NOTE**: this ADR is a Tech Lead draft written during Sub-phase 1.10 (Docs Refresh). The Design Architect refines/formally accepts it. Subsections marked `[DESIGN refinar]` are awaiting his input.

## Context

NORA web (`apps/web/`) is Next.js 14 + TypeScript + Tailwind CSS. **It does not use shadcn/ui or any component library.**

History:
- Sub-phase 1.0-1.1: the original scaffolding assumed shadcn (traces in the old `docs/development-standards.md`)
- Sub-phase 1.2: editorial visual redesign v1 → v2 (PRs #56, #58) swapped the legacy shadcn HSL palette for a custom editorial OKLCH one
- PR #66 detected a collision between `tokens.css` (OKLCH) and `globals.css` (legacy HSL residue)

The current UI components are **built in raw Tailwind** with utilities + manual variants. Custom editorial palette:
- Typography: Inter (sans), Instrument Serif (display), JetBrains Mono (mono) — via `next/font`
- Colors: semantic OKLCH tokens (`--paper`, `--ink`, `--brand`, etc.) — `apps/web/src/styles/tokens.css`
- No dependency on @radix-ui, headless-ui, or similar

Drift detected in the pre-Sub-phase 1.10 audit:
- The old `docs/development-standards.md` listed shadcn/ui + mandatory Zod (lines 286-290) — **divergent from reality**

The Design Architect's review in the audit made it explicit:

> "The tokens.css × globals.css collision (legacy shadcn HSL) discovered in PR #66 showed that the 'no shadcn' decision needs to be WRITTEN DOWN. Otherwise someone reintroduces shadcn tomorrow because 'it's the industry standard' and it goes badly."

## Decision

**Do not adopt shadcn/ui (or any other UI component library). Keep raw Tailwind + custom editorial OKLCH tokens.**

### Declared standards [DESIGN refinar]

1. **Semantic tokens in `tokens.css`**: pure OKLCH, editorial naming (`--paper`, `--ink`, `--brand-primary`, etc.) `[DESIGN refinar a lista completa de tokens com nomes definitivos]`

2. **Utility classes in `landing.module.css` (CSS Modules)** when a Tailwind class would get huge or semantically repeated — wraps with `:global()` when needed to scope the section

3. **styled-jsx in complex components** per section — `<style jsx global>` when there is a sub-component that needs to receive styling. A Next.js standard, zero additional deps

4. **Legacy HSL renamed** in `globals.css` to `--tw-*` (so as not to collide with OKLCH); keep only what is needed for Tailwind to work (`--background`, `--foreground`, etc. mapped to OKLCH tokens)

5. **UI components live in `components/`** (no separate `components/ui/` in the shadcn style). Brand assets in `components/brand/`. Landing-specific ones in `components/landing/`

## Why not shadcn

### Explicit trade-off

- **shadcn wins on**: initial speed, out-of-the-box accessibility (Radix), visual standardization across pages
- **NORA loses if it adopts it**: the custom editorial palette (Instrument Serif + OKLCH) **does not come bundled with shadcn**; each shadcn component would need heavy overriding → more code, less consistency

For a differentiated editorial design (the NORA pitch is "a distinct product, not generic SaaS"), shadcn becomes an **anti-pattern**.

### Accessibility without Radix

NORA components need correct ARIA, keyboard navigation, focus states. Without Radix, this is manual work but feasible:
- Semantic `<button>` (not `<div onClick>`)
- `aria-label` on icon-only elements
- `:focus-visible` in Tailwind
- Correct `tabindex`

[DESIGN refinar: checklist de acessibilidade obrigatório por tipo de componente — modal, dropdown, form, etc.]

## Consequences

**Positive:**
- Full control of the editorial palette (Instrument Serif + Inter + OKLCH does not come with shadcn)
- Zero UI lib deps (Radix, HeadlessUI, etc.) — smaller bundle
- The "styled-jsx + CSS module for utilities + raw Tailwind" standard is established
- A small team (Design Architect solo + Tech Lead supporting) does not need to learn an external library's abstractions

**Negative:**
- Accessibility requires manual discipline (no free Radix)
- Lower initial speed than adopting shadcn
- Not using a popular library means a new dev has to learn the project's own pattern

## Alternatives Considered

1. **Adopt shadcn/ui** — rejected because of the editorial palette vs default setup trade-off
2. **CSS-in-JS lib (styled-components, emotion)** — rejected. Next.js `styled-jsx` covers the case without an extra dep
3. **Migrate everything to CSS modules** — rejected. It would be a large unnecessary refactor for marginal gain
4. **Adopt HeadlessUI or Radix without shadcn** — `[DESIGN refinar: avaliar se HeadlessUI/Radix isolados, sem styling shadcn, são úteis pra acessibilidade]`

## Application Plan

1. Document the editorial tokens in `docs/engineering/design-tokens.md` `[DESIGN escreve na Sub-fase 1.12 ou quando achar tempo]`
2. Future PRs that touch `apps/web/src/components/` or `apps/web/src/styles/` reference this ADR in the commit/PR description
3. `docs/engineering/standards.md`, updated by Sub-phase 1.10, already removes mentions of shadcn/mandatory Zod

## History

| Date | Decider | Change |
|---|---|---|
| 2026-05-14 | Tech Lead | Draft created during Sub-phase 1.10. Awaiting the Design Architect to refine the sections marked `[DESIGN refinar]` |
