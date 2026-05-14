# 0017 — License: AGPL-3.0

- Status: aceito
- Data: 2026-05-14
- Decisores: Anthony Sforzin (PO — único dono de propriedade intelectual)

## Contexto

NORA é repositório público (`github.com/sys0xFF/nora`) desde a Sub-fase 1.0. Até hoje **sem LICENSE declarado** na raiz.

Implicações:

1. **Legalmente, repo público sem LICENSE = "todos os direitos reservados" por default.** Ninguém pode usar, modificar, distribuir sem permissão explícita
2. **Anthony tem 3 planos de carreira** (memory `user_career.md`):
   - **Plano A** — TOTVS contrata vendo NORA: LICENSE é **neutra** (Anthony entrega código diretamente como funcionário)
   - **Plano B** — SaaS comercial: LICENSE é **crítica**. Sem ela, qualquer empresa (incluindo TOTVS) pode clonar e oferecer SaaS competitivo sem pagar Anthony
   - **Plano C** — LinkedIn/portfolio: LICENSE é **neutra** (código mostra capacidade técnica)
3. **Recomendação Joint Tech Lead + Arquiteto Design (audit pré-Sub-fase 1.10):** AGPL-3.0

## Decisão

**LICENSE: GNU Affero General Public License v3.0 (AGPL-3.0)**.

Arquivo `LICENSE` na raiz do repositório com texto oficial completo (https://www.gnu.org/licenses/agpl-3.0.txt).

## Por que AGPL-3.0

### Proteção comercial (Plano B)

AGPL é **copyleft forte com cláusula network**. Diferença crítica vs GPL:
- **GPL**: força disponibilizar código apenas quando código é **distribuído**
- **AGPL**: força disponibilizar código quando código é **rodado como serviço de rede** (SaaS)

Isto significa: **qualquer empresa que clone NORA e rode como SaaS competitor obrigatoriamente disponibiliza o próprio fork incluindo modificações**. Mata o risco de TOTVS (ou competidor) clonar e oferecer "TOTVS Reuniões Inteligentes" sem partilhar melhorias.

### Compatível com Plano A (TOTVS contrata)

AGPL não impede Anthony de:
- Vender direitos comerciais paralelos a TOTVS (dual-licensing)
- Aceitar oferta de emprego que envolva o produto
- Re-licenciar versões futuras como copyright holder único

Como Anthony é **dono único de copyright** (todo código contribuído por ele ou por Claude operando sob sua direção), ele mantém poder de mudar a license unilateralmente no futuro.

### Compatível com Plano C (LinkedIn/portfolio)

AGPL é license bem reconhecida e respeitada pela comunidade dev. Não afeta uso de NORA como peça de portfolio técnico.

### Outras Considerações

- **Comunidade**: AGPL é vista como copyleft mais agressiva mas legítima. Projetos famosos como MongoDB (até 2018), Mastodon, Nextcloud, Elastic (até 2021) usaram/usam AGPL
- **Adoção corporativa**: algumas empresas evitam AGPL em produtos internos por medo de viral effect. Pra NORA isto é **feature, não bug** (queremos que fork comercial seja explicitamente declarado)
- **Compatibility com Dependencies**: NORA usa Spring Boot (Apache 2.0), Next.js (MIT), Tailwind (MIT), OpenAI SDK (MIT), Tauri (Apache 2.0 / MIT). AGPL é compatível como "downstream license" (NORA inteira é AGPL; deps continuam com suas licenses originais)

## Consequências

**Positivas:**
- Plano B protegido contra clone-and-compete
- Anthony mantém controle (dono único de copyright)
- Dual-licensing futuro possível (comercial pra clientes enterprise + AGPL pra comunidade)
- Sinal profissional pra reviewers técnicos

**Negativas:**
- Alguns potential adopters podem evitar AGPL (mas estes não seriam clientes Plano B viáveis de qualquer forma)
- Anthony precisa garantir que todas as dependências adicionadas no futuro sejam AGPL-compatible (Apache 2.0, MIT, BSD, MPL OK; proprietárias não)

## Alternativas Consideradas

1. **MIT** — permissivo demais. Qualquer um clona + vende sem retorno. Rejeitado pelo Plano B
2. **Apache 2.0** — mais permissiva que MIT (inclui patente grant). Mesmo problema: clone-and-compete livre
3. **GPL-3.0** — protege distribuição mas **não SaaS** (network clause ausente). Insuficiente pra Plano B
4. **BUSL (Business Source License)** — protege comercialmente por X anos depois vira open. Hot atualmente (Sentry, MariaDB usam). Rejeitado porque adiciona complexidade legal sem benefit claro pra NORA agora; e community percebe BUSL com mais resistência que AGPL
5. **Proprietário** — Anthony controla 100%. Mas perde benefícios de comunidade (contribuições, visibilidade, Plano C tração). Rejeitado
6. **Sem LICENSE (status quo)** — rejeitado: legal default "all rights reserved" + sinal não-profissional pra Plano A/C

## Plano de Aplicação

1. Criar `LICENSE` na raiz com texto AGPL-3.0 oficial
2. Adicionar header curto em `README.md` raiz: "NORA é licenciado sob AGPL-3.0. Ver `LICENSE`"
3. Notice no rodapé da landing pública (opcional, em coordenação com Arquiteto Design)
4. Documentar em `SECURITY.md` que vulnerabilidades reportadas com responsible disclosure mantêm copyright de Anthony

## Histórico

| Data | Decisor | Mudança |
|---|---|---|
| 2026-05-14 | Anthony Sforzin (PO) | LICENSE AGPL-3.0 confirmada após recomendação joint Tech Lead + Arquiteto Design no audit pré-Sub-fase 1.10 |
