---
name: nora-workflow
description: Workflow obrigatório para todo trabalho no projeto NORA. Use sempre que o usuário mencionar "nora", "projeto nora", ou quando for atuar no repositório /home/pollo/Dev/nora. Carrega contexto inicial, regras de git e template de PR.
---

# NORA Workflow

## 1. Início do Chat — Leitura Obrigatória

Se esta for a **primeira mensagem do chat**, execute **imediatamente** antes de qualquer ação:

1. Leia `/home/pollo/Dev/nora/CLAUDE.md`
2. Siga **todas** as instruções dele. Se o CLAUDE.md mandar ler outros arquivos, leia-os na ordem indicada.
3. O CLAUDE.md tipicamente exige ler em sequência:
   - `docs/PROJECT.md`
   - `docs/development-standards.md`
   - `docs/backlog-mvp.md`
   - `docs/visao-do-produto.md`
   - `docs/plano-de-execucao.md`

> Nunca ignore uma instrução de leitura vinda do CLAUDE.md.

## 2. Git — Proibições e Autorização

- **NUNCA** execute `git push`, `git push origin`, `gh pr create`, `gh release create`, ou qualquer comando que escreva no repositório remoto sem **autorização explícita do usuário**.
- Branches locais, commits locais, rebase local, merge local — permitidos.
- Sempre que for necessário enviar algo para o remoto, **pergunte ao usuário** antes.

## 3. Validação Local Pré-PR (CI Local)

Antes de solicitar autorização para push/PR, **rode os checks locais equivalentes ao CI do GitHub Actions** para os escopos que foram modificados no branch. Isso evita PRs quebrados e economiza tempo de review.

### 3.1 Detectar escopos modificados

Use o comando abaixo para listar arquivos alterados no branch atual (comparado com `main`):

```bash
cd /home/pollo/Dev/nora
git diff --name-only main...HEAD
```

Com base nos paths modificados, determine quais jobs do CI seriam disparados.

### 3.2 Checks locais por escopo

| Escopo | Paths que disparam | Checks locais (comandos) |
|---|---|---|
| **API** | `services/api/**`, `docs/api/**` | `cd services/api && mvn -B spotless:check && mvn -B verify` |
| **Worker** | `services/nlp-worker/**`, `packages/nlp-baseline/**`, `docs/api/llm-schemas/**`, `data/synthetic/**` | `cd services/nlp-worker && ruff check . && ruff format --check . && pytest` |
| **Web** | `apps/web/**`, `packages/**`, `docs/api/**` | `cd apps/web && pnpm install --no-frozen-lockfile && pnpm lint && pnpm typecheck && pnpm build` |
| **Desktop** | `apps/desktop/**` | `cd apps/desktop && npx tsc --noEmit && cd src-tauri && cargo check && cargo test` |
| **Docs** | `docs/**`, `**/*.md` | `python -c "import json, pathlib, sys; ok=all(json.loads(p.read_text()) or True for p in pathlib.Path('docs/api').rglob('*.json')); sys.exit(0 if ok else 1)"` |
| **Infra** | `infra/bicep/**` | `cd infra/bicep && az bicep build --file main.bicep` + loop em `modules/*.bicep` |

> **Regra de ouro:** se o PR toca em múltiplos escopos (ex: API + Web), rode os checks de **todos** os escopos afetados. Se o PR é cross-stack, rode tudo.

### 3.3 Comportamento obrigatório

1. **Após implementar** e **antes de pedir autorização para PR**, pergunte ao usuário: *"Quer que eu rode os checks locais do CI antes de abrir o PR?"* — ou, se o usuário já pediu explicitamente para abrir PR, **rode automaticamente**.
2. Se algum check falhar, **não peça autorização para push** até corrigir. Mostre o erro ao usuário e proponha o fix.
3. Se algum check local não puder ser rodado (ex: `az` CLI não instalado, falta Docker), anote no PR em "Notas para Revisor" e peça que o revisor preste atenção nesse job específico no CI remoto.

## 4. Template de PR

Quando o usuário autorizar a criação de um PR, use o template abaixo. O repositório possui `.github/PULL_REQUEST_TEMPLATE.md`; siga-o como base mínima, mas os PRs mais bem-sucedidos do NORA expandem com as seções documentadas abaixo.

### Estrutura obrigatória

```markdown
# PR — NORA

## Resumo

> O que esse PR entrega? (1–3 linhas)

Issue / Story: `#`

## Mudanças Principais

- [ ]
- [ ]

## Como Testar

```bash
# comandos
```

## Checklist

- [ ] Segue padrões em `docs/development-standards.md`
- [ ] Multi-tenancy preservada (filtro `tenant_id` ou RLS) — quando aplicável
- [ ] Sem segredos no diff
- [ ] Testes adicionados ou atualizados
- [ ] Contratos atualizados (`docs/api/openapi.yaml` / `docs/api/examples/*.json`) se houve mudança de API
- [ ] Schema LLM versionado (`docs/api/llm-schemas/*-vN.schema.json`) se houve mudança de saída do worker
- [ ] CI local verde (Spotless/Maven, ruff/pytest, tsc/cargo, etc.) — quando aplicável
- [ ] CI verde

## Notas para Revisor

>
```

### Extensões recomendadas (quando aplicável)

Para PRs cross-stack ou complexos, adicione **antes** de "Notas para Revisor":

- **Contexto** — por que este PR existe? Problema que resolve ou story que implementa.
- **Seções por stack** — `## Backend Spring`, `## Worker NLP`, `## Web Next.js`, etc. Detalhe mudanças em cada camada.
- **Test plan** — checkboxes com comandos ou URLs a verificar.
- **Próximos passos (fora deste PR)** — o que vem depois, para não perder o fio da meada.
- **Pre-condições / Cleanup** — ações manuais já feitas fora do código (ex: registros Azure, deleções, visibilidade GHCR).

Use o `gh` CLI para abrir o PR:

```bash
cd /home/pollo/Dev/nora
gh pr create \
  --title "tipo(escopo): descrição curta" \
  --body-file - <<'EOF'
...conteúdo do PR...
EOF
```

> Só execute o comando acima após autorização explícita do usuário (ver seção 2).
