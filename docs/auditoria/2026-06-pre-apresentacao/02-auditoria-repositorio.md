---
title: "Auditoria do repositório — higiene e limpeza"
owner: Arquiteto NORA (Tech Lead)
status: approved
version: 1.0
last_reviewed: 2026-06-06
---

# Auditoria do repositório — higiene e limpeza

> Itens inúteis, obsoletos ou melhoráveis no monorepo. Cada **remoção** foi verificada
> por busca de referências em todo o repositório (CI, Makefile, Dockerfiles, imports,
> docs). As remoções de altíssima confiança já foram aplicadas nesta branch
> (`chore/auditoria-pre-apresentacao-2026-06`); o restante é recomendação.

## 1. Removido nesta branch (verificado seguro)

| Caminho | Por quê | Verificação |
|---|---|---|
| `package.json` (raiz) | *Stray*: 123 bytes, sem `name`/`private`/`scripts`/`workspaces`. Criado por engano num commit do desktop. Declara `react-markdown` (já corretamente em `apps/web` e `apps/desktop`) e `cross-env` (não usado em lugar nenhum). | Nenhum CI/Makefile/Dockerfile roda `npm` na raiz — `ci.yml` instala em `apps/web` e `apps/desktop`; os Dockerfiles copiam os `package.json` por-app no próprio contexto. |
| `package-lock.json` (raiz) | 52 KB que travam apenas o `package.json` *stray* acima. `dependabot.yml` registra npm só em `/apps/web` e `/apps/desktop`. | Nenhum `npm ci`/`npm install` na raiz em CI ou Makefile. Os lockfiles reais são `apps/web/`, `apps/admin/` e `apps/desktop/`. |
| `scripts/.gitkeep` | Placeholder redundante: `scripts/` já tem `dev-stop.sh` rastreado. | Zero referências a `scripts/.gitkeep` no repo. |
| `notebooks/.gitkeep` | Placeholder redundante: `notebooks/` já tem `.ipynb` + `.py` rastreados. | Zero referências a `notebooks/.gitkeep` no repo. |
| `.kimi/` (árvore inteira) | Conjunto de instruções de IA **órfão e obsoleto**: contém só `skills/nora-workflow/SKILL.md`, que referencia caminhos absolutos de outra máquina (`/home/pollo/Dev/nora/`) e manda ler `docs/PROJECT.md` — arquivo que o `CLAUDE.md` declara explicitamente que **não existe mais**. | `grep` por `.kimi` em todo o repo (fora da própria pasta) = 0 referências. Remoção pré-autorizada pela PO ("`.kimi` se órfão"). |

> Estas remoções **não afetam** nenhum build, CI, imagem Docker ou fluxo de
> desenvolvimento. A separação por-app (Makefile + `ci.yml`) permanece intacta.

## 2. Recomendado (não aplicado — exige decisão)

### 2.1 Branches remotas obsoletas

Há ~28 branches remotas além da `main`. **Atenção metodológica**: este repositório usa
*squash-merge*, então `git branch --merged main` reporta apenas a `main` — as branches
de feature foram "achatadas" e não aparecem como mergeadas, **mesmo já estando na
`main`**. Não confie no `--merged` aqui.

**Branches cujo trabalho já está na `main` via PR mergeada (candidatas a deletar):**

```
chore/codeql-scanning (#202)   chore/remove-codeql (a84f0a7)   chore/security-headers
feat/admin-console-real-data (#203)   feat/chat-rag (#206)   feat/lgpd-operational (#204)
feat/request-id-tracing   feat/rls-complete-v018   feat/rls-cutover-tooling
feat/rls-enforce-cutover   feat/rls-enforce-flip   fix/ci-deploy-rollout   fix/r001-gexec
fix/swagger-off-prod   test/coverage-gate
```

**Branches antigas plenamente superadas:**

```
feat/e3-processamento-ia   docs/iam-estilo-aws   docs/limpeza-incongruencias
feat/pontuacao-produtividade   feat/confianca-cliente   product-dashboard-design
revert-39-fix/desktop-sidecar-lifecycle-and-types
```

**Não deletar sem checar PR aberta:** `dependabot/*` (auto-removidas quando a PR
fecha/merge) e `claude/*` (branches de agente).

**Procedimento seguro recomendado** (não automatizado aqui porque deletar branch remota
é uma ação destrutiva e externa):

```bash
gh pr list --state merged --limit 100        # confirme cada PR
git branch -r --contains <tip-da-branch>      # ou compare diff vs main
git push origin --delete <branch>             # só após confirmar
```

### 2.2 Correções rápidas de produto/segurança (alto valor, baixo risco)

| Item | Onde | Ação |
|---|---|---|
| E-mail de contato de segurança/DPO **errado** | `SECURITY.md:9,71` usa `axonogenesis@gmail.com`; o canal real é `axonogenesis@proton.me` | Corrigir o e-mail (canal de reporte de vulnerabilidade apontando para conta errada é risco real). |
| Tooling de segurança descrito não condiz com repo privado | `SECURITY.md:78-80` cita Secret Scanning/Push Protection "default em repos públicos", mas o repo é privado (o commit `a84f0a7` removeu o CodeQL por exigir GitHub Advanced Security pago em repo privado) | Ajustar a descrição ao que está de fato habilitado. |
| Homóglifo cirílico em texto latino | `docs/operations/production-readiness-gaps.md:7` ("deploy**а**" com `а` cirílico) | Corrigir o caractere; recomendar linter de homóglifos no CI. |

### 2.3 *Smells* de menor prioridade

- **`notebooks/totvs_transcricoes_eda.py`**: o nome com `totvs` colide com o princípio
  *"Sem TOTVS hardcoded"* (o princípio mira código de produto, e isto é script de
  análise — tecnicamente fora de escopo, mas o nome é um *smell* dado o posicionamento
  tenant-agnóstico). Sugestão: renomear removendo a marca e/ou converter para `.ipynb`
  (convenção da pasta).
- **Assimetria em `scripts/`**: `make dev-stop` delega a `scripts/dev-stop.sh`, mas a
  lógica de *start* está embutida no `Makefile`. Opcional: extrair um
  `scripts/dev-start.sh` por simetria.
- **Três fontes de instrução de IA**: `CLAUDE.md` (fonte de verdade) +
  `.github/copilot-instructions.md` (atual e consistente — aceitável) + `.kimi` (removido
  nesta branch). Manter `CLAUDE.md` e `copilot-instructions.md` sincronizados.

## 3. Verificado e **descartado** (não são problema)

Para evitar retrabalho em auditorias futuras, registramos o que parecia suspeito mas foi
confirmado como **legítimo**:

| Suspeita | Veredito |
|---|---|
| Pasta `mcp/` vazia | **Não existe** no repo (já foi removida). O `backlog`/`roadmap`/`vision` ainda a citam — isso é *drift* de doc (ver documento 03), não lixo no repo. |
| `docs/engineering/data-model-oracle.md` duplica `data-model.md` | **Não duplica**: é entrega acadêmica FIAP intencional (schema espelhado em Oracle 19c). Ambos são cross-referenciados. Manter. |
| `apps/admin/src/lib/mock.ts` é dead code | **Está ativo**: é a fonte de dados local do admin (`data.ts` usa como fallback quando `NORA_ADMIN_USE_MOCKS≠false`). |
| Workflows Cloudflare (`setup` + `tunnel`) redundantes | **Não**: são duas "raias" intencionais do ADR 0025 (setup = Access App/Policy/IdP; tunnel = Tunnel + CNAME). Ambos `workflow_dispatch`. |
| `data/samples` vs `data/synthetic` | Ambos populados, distintos e documentados (samples = fixtures curtos; synthetic = corpus completo). |
| Artefatos de build commitados | **Nenhum**: `git ls-files` não mostra `node_modules`/`.next`/`target`/`dist`/`coverage`/`.env`. O `.gitignore` é efetivo. |
| `packages/shared-contracts` só tem `.gitkeep` | **Falso**: tem 4 arquivos reais (`error-codes.md`, `pii-types.json`, `processing-status.json`, `README.md`). Os docs que dizem "só `.gitkeep`" estão desatualizados (ver documento 03). |

## 4. Workflows de CI — situação

Nenhum workflow órfão/morto encontrado. Para o leitor entender a divisão de
responsabilidades (alguns só rodam manualmente):

| Workflow | Disparo | Papel |
|---|---|---|
| `ci.yml` | PR | Gate de lint/test/build por pacote alterado |
| `build-images.yml` | push `main` (path-filtered) | Build das imagens + rollout dos Container Apps |
| `deploy-infra.yml` | mudanças em `infra/bicep/**` | Bicep/infra via OIDC |
| `cloudflare-setup.yml` | manual | Access App/Policy/IdP (ADR 0025) |
| `cloudflare-tunnel.yml` | manual | Tunnel + CNAME `admin.nora.systems` (ADR 0025) |
| `rls-cutover.yml` | manual | Provisionamento idempotente do cutover RLS (ADR 0028) |
