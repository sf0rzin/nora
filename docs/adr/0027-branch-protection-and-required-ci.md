# 0027 — Branch protection da `main` + CI gate obrigatório

- Status: aceito
- Data: 2026-06-04
- Decisores: Arquiteto (Opus) + Stratfy (PO/dono)
- Relacionado: ADR 0018 (test coverage targets), `.github/workflows/ci.yml`, `.github/CODEOWNERS`, auditoria de fundação 2026-06-03

## Contexto

A auditoria de fundação (2026-06-03) achou um descompasso entre a qualidade do pipeline e a sua imposição: o `ci.yml` já roda testes reais (api `mvn verify`, worker `pytest`, web `lint/typecheck/build`), mas o **ruleset da `main` (id 16147673) só bloqueava `deletion` + `non_fast_forward`**. Não havia exigência de Pull Request, de review, nem de status check verde. Consequências:

- Qualquer colaborador com write podia empurrar direto na `main`.
- PRs podiam ser mergeados com **CI vermelho** e **zero revisão**.
- Código tocando os não-negociáveis (tenant isolation, PII, IAM) chegava à `main` sem nenhum portão automático nem humano.

Restrição de contexto: o time tem **2 humanos** (Anthony/`@sys0xFF` em web+back+infra; Gabriel/`@pollotherunner` em desktop) e o PO frequentemente mergeia o próprio PR. Uma exigência de review **sem escape** geraria deadlock quando um dos dois está indisponível.

Detalhe técnico que força um desenho específico: os jobs do `ci.yml` são **condicionais por path** (`dorny/paths-filter`). Exigir um job condicional (ex.: `api`) diretamente como required status check trava o merge quando o PR não toca aquela área — o GitHub trata um required check que nunca roda como *pending* eterno.

## Decisão

1. **CI gate agregador.** Novo job `ci-gate` no `ci.yml`: sempre roda (`if: always()`), depende de todos os jobs de PR (`changes, api, worker, web, desktop-sidecar, docs-link-check, infra`) e só falha se algum deles **falhou ou foi cancelado** (`skipped` por path-filter = ok). `desktop-bundle` fica de fora (só roda em push na `main`, não é portão de PR). O `ci-gate` é o **único** status check exigido — resolve o problema do path-filter com um único nome estável.

2. **Ruleset da `main` endurecido** (mesmo id 16147673), passa a exigir:
   - **Pull request obrigatório** antes de merge (sem push direto na `main`).
   - **1 approving review**, com *dismiss stale reviews on push* (aprovação cai se novo commit chega).
   - **Status check `ci-gate` verde**, em modo *strict* (branch atualizada com a base).
   - **Histórico linear** (alinhado ao fluxo squash-merge).
   - **Resolução de conversas** antes do merge.
   - Mantém o bloqueio de `deletion` + `non_fast_forward`.

3. **Bypass pragmático para time de 2.** O role de **admin do repositório** (os 2 donos) pode bypassar o gate de PR/review para merge solo ou emergência. É um trade-off consciente: sem ele, um dono sozinho não consegue mergear o próprio PR (não pode auto-aprovar). A exigência de review é o **default**; o bypass é a exceção. **Quando o time crescer (3+), remover o bypass** e tornar o review obrigatório de verdade.

4. **CODEOWNERS** (`.github/CODEOWNERS`) roteia review por área: backend/IAM/worker/web/infra/adr → `@sys0xFF`; desktop → `@pollotherunner`. Hoje serve para *auto-request* de reviewer; quando o time crescer, ligar `require_code_owner_review` no ruleset.

## Consequências

**Positivas:**
- O `ci.yml`, que já roda testes reais, vira um **portão real**: nada toca a `main` com CI vermelho.
- 4-eyes principle como default; rastreabilidade de quem revisou o quê.
- Roteamento automático de review por domínio (CODEOWNERS).
- Base para ligar o gate de cobertura (ADR 0018) como parte do `ci-gate` no futuro.

**Negativas / trade-offs:**
- Não há mais push direto na `main` — todo trabalho passa por PR (custo de processo aceito; já era a norma de fato).
- O bypass de admin torna a exigência de review "soft" para os donos enquanto o time é de 2. Documentado e com gatilho de remoção (3+ pessoas).
- Um force-push legítimo na `main` (ex.: a limpeza de histórico de 2026-06-03) exige desabilitar o ruleset por alguns segundos e reabilitar — procedimento manual conhecido.

## Alternativas Consideradas

1. **Exigir os jobs condicionais diretamente como required checks** — rejeitado: trava o merge com *pending* eterno quando o job é skipped por path-filter. O `ci-gate` agregador é o padrão correto.
2. **Required review sem bypass** — rejeitado para time de 2: gera deadlock quando um dono está sozinho (não pode auto-aprovar).
3. **Só status check, sem exigir review** — menos rigoroso; descarta o 4-eyes principle que é a parte mais valiosa. Rejeitado como default, mantido como o comportamento efetivo para os donos via bypass.
4. **Deixar como estava** (só delete/force-push) — rejeitado: era o gap de governança nº1 da auditoria.

## Histórico

| Data | Decisor | Mudança |
|---|---|---|
| 2026-06-04 | Arquiteto + Stratfy | Criação. CI gate agregador + branch protection (PR + review + ci-gate + linear history) com bypass de admin para time de 2 + CODEOWNERS. Endereça o gap de governança da auditoria 2026-06-03. |
