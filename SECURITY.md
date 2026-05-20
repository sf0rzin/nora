# Security Policy

NORA leva segurança a sério. Este documento descreve **como reportar vulnerabilidades, escopo, contato seguro, e expectativa de timeline**.

## Reportar vulnerabilidade

**Por favor não abra issue público no GitHub.** Use os canais abaixo:

- **E-mail seguro:** axonogenesis@gmail.com (com prefixo `[SECURITY-NORA]` no assunto)
- **Resposta esperada:** em até **3 dias úteis** com acknowledgement inicial
- **Disclosure timeline:** typicamente **90 dias** entre report e disclosure pública, com correção mergeada antes

PGP key disponível mediante request inicial pelo e-mail acima.

## Escopo

### Em escopo

- `services/api/**` — backend Spring (autenticação, IAM, persistência, contratos REST)
- `services/nlp-worker/**` — worker Python NLP (PII Shield, LLM calls, contratos)
- `apps/web/**` — frontend Next.js (auth flow, XSS, CSRF)
- `apps/desktop/**` — app Tauri + sidecar Python (token broker, secret store local)
- `infra/bicep/**` — infraestrutura como código Azure (configurações, secret management)
- `.github/workflows/**` — CI/CD (secret leakage, supply chain)
- `packages/**` — pacotes compartilhados

### Fora de escopo

- **Vulnerabilidades em dependências upstream** (Spring Boot, Next.js, Tauri, OpenAI SDK, etc.) — reporte upstream e nos avise por cortesia
- **Configurações específicas do ambiente do usuário** que não derivam do código padrão NORA
- **Self-XSS** (requer engenharia social ativa pra explorar)
- **Issues de UX/usabilidade sem componente de segurança**

## Vulnerabilidades de alta prioridade

NORA processa dados sensíveis (transcripts de reuniões, possíveis dados pessoais via PII Shield) sob LGPD brasileiro. Categorias de **alta prioridade**:

1. **Bypass de tenant isolation** — qualquer caminho que permita usuário do tenant A acessar dados do tenant B (`tenant_id` violado em query, frontend filtering only sem backend gate, JWT manipulado pra trocar `tenantId`)
2. **Bypass do PII Shield** — caminho que entrega PII bruto pro LLM (incluindo via prompt injection)
3. **IAM privilege escalation** — usuário consegue executar action que policy aplicável Deny ou não Allow (Policy Evaluator bypass, Resource string injection, condition operator misuse)
4. **JWT/refresh token forgery** — assinatura forjada, replay attack, ou cookie hijack sem detection
5. **Secrets leakage** — secrets do Key Vault expostos em logs, error responses, ou via debug endpoints
6. **SQL injection / NoSQL injection** — em qualquer query construída dinâmicamente
7. **Speech token broker abuse** — caminho que permite atacante obter token Azure Speech via NORA sem autorização

## Categorias de menor prioridade

- Rate limiting bypass (impacto: spam, custo LLM/Speech)
- Information disclosure não-sensível (versões de libs, paths internos)
- DoS via input mal-formado (impacto: serviço degradado)
- CSRF onde NORA já tem mitigação (SameSite cookies, etc.)

## Responsible disclosure

NORA é licenciado sob **AGPL-3.0** (ver `LICENSE`). Pesquisadores que descobrem vulnerabilidades:

- **Mantém copyright** sobre seu trabalho de pesquisa
- **São creditados** no `CHANGELOG.md` e em post-mortem (se concordar)
- **Não são processados** legalmente por research em escopo declarado neste documento (good faith security research)
- **Não devem testar em produção** sem coordenação prévia (use clone local + ambiente próprio)

## Reconhecimentos passados

(em construção — primeiros responsible disclosures serão listados aqui)

## DPO / Encarregado LGPD

**Encarregado de Proteção de Dados** (LGPD exige indicação de pessoa física):

- **Nome:** Anthony Sforzin (membro da equipe Stratfy designado como Encarregado)
- **Contato:** axonogenesis@gmail.com (com prefixo `[LGPD-NORA]`)

NORA é operado pela equipe Stratfy durante MVP/Pilot. Em GA com >10 tenants, DPO formal contratado ou role designado.

## Tooling de segurança ativo

- **Dependabot** habilitado via `.github/dependabot.yml` — updates semanais agrupados por ecosystem (Maven, pip, npm, Cargo, GitHub Actions). Alerts via Security tab.
- **JaCoCo + áreas IAM/Auth/PII** — meta ADR 0018 de >85% coverage. Hoje rodada manualmente; gate de CI bloqueando regressão está na Sub-fase 1.12 (ADR 0016 — production readiness).
- **GitHub Secret Scanning** habilitado (default em repos públicos)
- **GitHub Push Protection** — bloqueia commits com chaves detectadas (default em repos públicos com Secret Scanning).
- **PII Shield** no worker como último gate antes de LLM (ADR 0012)

## Histórico

| Data | Mudança |
|---|---|
| 2026-05-14 | Documento criado durante Sub-fase 1.10 (Docs Refresh) |
