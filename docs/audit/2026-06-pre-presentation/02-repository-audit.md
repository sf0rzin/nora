# Repository audit — hygiene and cleanup

> Useless, obsolete or improvable items in the monorepo. Every **removal** was verified
> by searching for references across the whole repository (CI, Makefile, Dockerfiles, imports,
> docs). The highest-confidence removals have already been applied on this branch
> (`chore/auditoria-pre-apresentacao-2026-06`); the rest is a recommendation.

## 1. Removed on this branch (verified safe)

| Path | Why | Verification |
|---|---|---|
| `package.json` (root) | *Stray*: 123 bytes, with no `name`/`private`/`scripts`/`workspaces`. Created by mistake in a desktop commit. Declares `react-markdown` (already correctly in `apps/web` and `apps/desktop`) and `cross-env` (not used anywhere). | No CI/Makefile/Dockerfile runs `npm` at the root — `ci.yml` installs in `apps/web` and `apps/desktop`; the Dockerfiles copy the per-app `package.json` within their own context. |
| `package-lock.json` (root) | 52 KB that lock only the *stray* `package.json` above. `dependabot.yml` registers npm only in `/apps/web` and `/apps/desktop`. | No `npm ci`/`npm install` at the root in CI or in the Makefile. The real lockfiles are in `apps/web/`, `apps/admin/` and `apps/desktop/`. |
| `scripts/.gitkeep` | Redundant placeholder: `scripts/` already has a tracked `dev-stop.sh`. | Zero references to `scripts/.gitkeep` in the repo. |
| `notebooks/.gitkeep` | Redundant placeholder: `notebooks/` already has tracked `.ipynb` + `.py` files. | Zero references to `notebooks/.gitkeep` in the repo. |
| `.kimi/` (entire tree) | **Orphaned and obsolete** AI instruction set: it contains only `skills/nora-workflow/SKILL.md`, which references absolute paths from another machine (`/home/pollo/Dev/nora/`) and instructs reading `docs/PROJECT.md` — a file that `CLAUDE.md` explicitly declares **no longer exists**. | `grep` for `.kimi` across the whole repo (outside the folder itself) = 0 references. Removal pre-authorized by the PO ("`.kimi` if orphaned"). |

> These removals **do not affect** any build, CI, Docker image or development
> flow. The per-app separation (Makefile + `ci.yml`) remains intact.

## 2. Recommended (not applied — requires a decision)

### 2.1 Obsolete remote branches

There are ~28 remote branches besides `main`. **Methodological warning**: this repository uses
*squash-merge*, so `git branch --merged main` reports only `main` — the feature branches
were "flattened" and do not show up as merged, **even though they are already on
`main`**. Do not trust `--merged` here.

**Branches whose work is already on `main` via a merged PR (deletion candidates):**

```
chore/codeql-scanning (#202)   chore/remove-codeql (a84f0a7)   chore/security-headers
feat/admin-console-real-data (#203)   feat/chat-rag (#206)   feat/lgpd-operational (#204)
feat/request-id-tracing   feat/rls-complete-v018   feat/rls-cutover-tooling
feat/rls-enforce-cutover   feat/rls-enforce-flip   fix/ci-deploy-rollout   fix/r001-gexec
fix/swagger-off-prod   test/coverage-gate
```

**Old branches fully superseded:**

```
feat/e3-processamento-ia   docs/iam-estilo-aws   docs/limpeza-incongruencias
feat/pontuacao-produtividade   feat/confianca-cliente   product-dashboard-design
revert-39-fix/desktop-sidecar-lifecycle-and-types
```

**Do not delete without checking for an open PR:** `dependabot/*` (auto-removed when the PR
closes/merges) and `claude/*` (agent branches).

**Recommended safe procedure** (not automated here because deleting a remote branch
is a destructive and external action):

```bash
gh pr list --state merged --limit 100        # confirme cada PR
git branch -r --contains <tip-da-branch>      # ou compare diff vs main
git push origin --delete <branch>             # só após confirmar
```

### 2.2 Quick product/security fixes (high value, low risk)

| Item | Where | Action |
|---|---|---|
| **Wrong** security/DPO contact e-mail | `SECURITY.md:9,71` uses `axonogenesis@gmail.com`; the real channel is `axonogenesis@proton.me` | Fix the e-mail (a vulnerability reporting channel pointing at the wrong account is a real risk). |
| Described security tooling does not match a private repo | `SECURITY.md:78-80` cites Secret Scanning/Push Protection as "default in public repos", but the repo is private (commit `a84f0a7` removed CodeQL because it requires paid GitHub Advanced Security on a private repo) | Adjust the description to what is actually enabled. |
| Cyrillic homoglyph in Latin text | `docs/operations/production-readiness-gaps.md:7` ("deploy**а**" with a Cyrillic `а`) | Fix the character; recommend a homoglyph linter in CI. |

### 2.3 Lower-priority *smells*

- **`notebooks/totvs_transcricoes_eda.py`**: the name containing `totvs` collides with the
  *"No hardcoded TOTVS"* principle (the principle targets product code, and this is an analysis
  script — technically out of scope, but the name is a *smell* given the
  tenant-agnostic positioning). Suggestion: rename it removing the brand and/or convert it to `.ipynb`
  (the folder's convention).
- **Asymmetry in `scripts/`**: `make dev-stop` delegates to `scripts/dev-stop.sh`, but the
  *start* logic is embedded in the `Makefile`. Optional: extract a
  `scripts/dev-start.sh` for symmetry.
- **Three sources of AI instructions**: `CLAUDE.md` (source of truth) +
  `.github/copilot-instructions.md` (current and consistent — acceptable) + `.kimi` (removed
  on this branch). Keep `CLAUDE.md` and `copilot-instructions.md` in sync.

## 3. Verified and **discarded** (not a problem)

To avoid rework in future audits, we record what looked suspicious but was
confirmed as **legitimate**:

| Suspicion | Verdict |
|---|---|
| Empty `mcp/` folder | **Does not exist** in the repo (it has already been removed). The `backlog`/`roadmap`/`vision` still cite it — that is doc *drift* (see document 03), not junk in the repo. |
| `docs/engineering/data-model-oracle.md` duplicates `data-model.md` | **It does not duplicate**: it is an intentional FIAP academic deliverable (schema mirrored in Oracle 19c). Both are cross-referenced. Keep. |
| `apps/admin/src/lib/mock.ts` is dead code | **It is active**: it is the admin's local data source (`data.ts` uses it as a fallback when `NORA_ADMIN_USE_MOCKS≠false`). |
| Cloudflare workflows (`setup` + `tunnel`) are redundant | **No**: they are two intentional "lanes" of ADR 0025 (setup = Access App/Policy/IdP; tunnel = Tunnel + CNAME). Both are `workflow_dispatch`. |
| `data/samples` vs `data/synthetic` | Both are populated, distinct and documented (samples = short fixtures; synthetic = full corpus). |
| Committed build artifacts | **None**: `git ls-files` does not show `node_modules`/`.next`/`target`/`dist`/`coverage`/`.env`. The `.gitignore` is effective. |
| `packages/shared-contracts` only has a `.gitkeep` | **False**: it has 4 real files (`error-codes.md`, `pii-types.json`, `processing-status.json`, `README.md`). The docs that say "only `.gitkeep`" are outdated (see document 03). |

## 4. CI workflows — status

No orphaned/dead workflow found. So the reader understands the split of
responsibilities (some run only manually):

| Workflow | Trigger | Role |
|---|---|---|
| `ci.yml` | PR | Lint/test/build gate per changed package |
| `build-images.yml` | push to `main` (path-filtered) | Image build + Container Apps rollout |
| `deploy-infra.yml` | changes in `infra/bicep/**` | Bicep/infra via OIDC |
| `cloudflare-setup.yml` | manual | Access App/Policy/IdP (ADR 0025) |
| `cloudflare-tunnel.yml` | manual | Tunnel + CNAME `admin.nora.systems` (ADR 0025) |
| `rls-cutover.yml` | manual | Idempotent provisioning of the RLS cutover (ADR 0028) |
