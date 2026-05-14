# 0013 — Estratégia de CSS frontend (Tailwind cru, sem shadcn, tokens OKLCH)

- Status: Proposto (esboço pelo Tech Lead; aguarda refino pelo Arquiteto Design)
- Data: 2026-05-14
- Decisores: Arquiteto Design (proprietário do escopo frontend)

> **NOTA**: este ADR é esboço do Tech Lead durante Sub-fase 1.10 (Docs Refresh). O Arquiteto Design refina/aceita formalmente. Sub-seções marcadas `[DESIGN refinar]` aguardam input dele.

## Contexto

NORA web (`apps/web/`) é Next.js 14 + TypeScript + Tailwind CSS. **Não usa shadcn/ui nem nenhuma lib de componentes.**

Histórico:
- Subfase 1.0-1.1: scaffolding original previu shadcn (rastros em `docs/development-standards.md` antigo)
- Subfase 1.2: redesign visual editorial v1 → v2 (PRs #56, #58) trocou paleta HSL legacy shadcn por OKLCH editorial customizada
- PR #66 detectou colisão `tokens.css` (OKLCH) × `globals.css` (HSL legacy resíduos)

Componentes UI atuais são **construídos em Tailwind cru** com utilitários + variants manuais. Paleta editorial customizada:
- Tipografia: Inter (sans), Instrument Serif (display), JetBrains Mono (mono) — via `next/font`
- Cores: tokens OKLCH semânticos (`--paper`, `--ink`, `--brand`, etc.) — `apps/web/src/styles/tokens.css`
- Sem dependência de @radix-ui, headless-ui, ou similar

Drift detectado no audit pré-Sub-fase 1.10:
- `docs/development-standards.md` antigo listava shadcn/ui + Zod obrigatório (linhas 286-290) — **divergente da realidade**

A revisão do Arquiteto Design no audit explicitou:

> "Colisão tokens.css × globals.css (HSL legacy shadcn) descoberta em PR #66 mostrou que a decisão 'sem shadcn' precisa estar ESCRITA. Senão alguém amanhã reintroduz shadcn pq 'é padrão indústria' e dá ruim."

## Decisão

**Não adotar shadcn/ui (nem outra lib de componentes UI). Manter Tailwind cru + tokens OKLCH editoriais customizados.**

### Padrões declarados [DESIGN refinar]

1. **Tokens semânticos em `tokens.css`**: OKLCH puros, naming editorial (`--paper`, `--ink`, `--brand-primary`, etc.) `[DESIGN refinar a lista completa de tokens com nomes definitivos]`

2. **Classes utilitárias em `landing.module.css` (CSS Modules)** quando classe Tailwind ficaria gigante ou semanticamente repetida — wraps com `:global()` quando necessário pra escopar a seção

3. **styled-jsx em componentes complexos** por seção — `<style jsx global>` quando há sub-componente que precisa receber styling. Padrão do Next.js, zero deps adicionais

4. **HSL legacy renomeado** em `globals.css` pra `--tw-*` (não colidir com OKLCH); manter apenas o necessário pra Tailwind funcionar (`--background`, `--foreground` etc. mapeados pra tokens OKLCH)

5. **Componentes UI ficam em `components/`** (sem `components/ui/` separado tipo shadcn). Brand assets em `components/brand/`. Landing-specific em `components/landing/`

## Por que não shadcn

### Trade-off explícito

- **shadcn ganha em**: velocidade inicial, acessibilidade out-of-the-box (Radix), padronização visual entre páginas
- **NORA perde se adotar**: paleta editorial customizada (Instrument Serif + OKLCH) **não é vendida com shadcn**; cada componente shadcn precisaria override pesado → mais código, menos consistência

Pra design editorial diferenciado (NORA pitch é "produto distinto, não SaaS genérico"), shadcn vira **anti-pattern**.

### Acessibilidade sem Radix

Componentes do NORA precisam ARIA correto, keyboard navigation, focus states. Sem Radix, isso é trabalho manual mas viável:
- `<button>` semântico (não `<div onClick>`)
- `aria-label` em ícones-only
- `:focus-visible` no Tailwind
- `tabindex` correto

[DESIGN refinar: checklist de acessibilidade obrigatório por tipo de componente — modal, dropdown, form, etc.]

## Consequências

**Positivas:**
- Controle total da paleta editorial (Instrument Serif + Inter + OKLCH não vende com shadcn)
- Zero deps de UI lib (Radix, HeadlessUI, etc.) — bundle menor
- Padrão "styled-jsx + CSS module pra utilities + Tailwind cru" estabelecido
- Equipe pequena (Arquiteto Design solo + Tech Lead suporta) sem precisar aprender abstrações de uma lib externa

**Negativas:**
- Acessibilidade exige disciplina manual (sem Radix grátis)
- Velocidade inicial menor que adopt shadcn
- Não usar lib popular significa que dev novo precisa aprender padrão do projeto

## Alternativas Consideradas

1. **Adotar shadcn/ui** — rejeitado pelo trade-off paleta editorial vs setup default
2. **CSS-in-JS lib (styled-components, emotion)** — rejeitado. Next.js `styled-jsx` cobre o caso sem dep extra
3. **Migrar tudo pra CSS modules** — rejeitado. Seria refactor desnecessário grande pra ganho marginal
4. **Adotar HeadlessUI ou Radix sem shadcn** — `[DESIGN refinar: avaliar se HeadlessUI/Radix isolados, sem styling shadcn, são úteis pra acessibilidade]`

## Plano de Aplicação

1. Documentar tokens editoriais em `docs/engineering/design-tokens.md` `[DESIGN escreve na Sub-fase 1.12 ou quando achar tempo]`
2. PRs futuros que mexam em `apps/web/src/components/` ou `apps/web/src/styles/` referenciam este ADR no commit/PR description
3. `docs/engineering/standards.md` atualizado pela Sub-fase 1.10 já remove menções a shadcn/Zod obrigatório

## Histórico

| Data | Decisor | Mudança |
|---|---|---|
| 2026-05-14 | Tech Lead | Esboço criado durante Sub-fase 1.10. Aguarda Arquiteto Design refinar seções marcadas `[DESIGN refinar]` |
