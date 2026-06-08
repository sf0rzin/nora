---
title: "Guia de estilo e templates de documentação"
owner: Arquiteto NORA (Tech Lead)
status: approved
version: 1.0
last_reviewed: 2026-06-06
aplica_se_a: "Todos os .md do repositório (docs/, README, CLAUDE.md, SECURITY.md)"
---

# Guia de estilo e templates de documentação — NORA

> Padrão único para a documentação do NORA parecer (e ser) um produto real. Este
> documento é a referência citada pelo diagnóstico [03](03-diagnostico-documentacao.md).
> Foi escrito já no próprio padrão que descreve.

## 1. Front-matter obrigatório

Todo documento começa com YAML front-matter. Mínimo:

```yaml
---
title: "Título legível do documento"
owner: <papel responsável>          # ex.: Arquiteto NORA (Tech Lead)
status: draft | review | approved | superseded
version: <semver simples, ex.: 1.0>
last_reviewed: AAAA-MM-DD
---
```

Campos opcionais quando aplicável: `supersedes` / `superseded_by` (ADRs e contratos),
`aplica_se_a`, `relacionado`.

## 2. Fonte única de verdade (regra de ouro)

Cada fato vive em **um só lugar**; os demais documentos **linkam**, nunca recopiam.

| Fato | Fonte única |
|---|---|
| Índice e contagem de ADRs | `docs/adr/README.md` |
| Intervalo de migrations | `docs/engineering/data-model.md` |
| Status por user story | `docs/product/backlog.md` |
| Termos canônicos | `docs/product/glossary.md` |

> Exemplo do que **não** fazer: escrever "21 ADRs" em cinco documentos. Quando o número
> muda, quatro ficam errados. Escreva "ver [índice de ADRs](docs/adr/README.md)".

## 3. Política de idioma

- **PT-BR** é o idioma de prosa e de **todos os cabeçalhos**.
- Termos técnicos em inglês são permitidos *inline* quando são o termo de mercado
  (ex.: *deploy*, *endpoint*, *commit*, *pull request*), em itálico na primeira ocorrência.
- Cabeçalhos não misturam idioma: use "Como trabalhamos", não "How To Work"; "Não
  negociáveis", não "Non-Negotiables".
- O glossário define a forma canônica de cada termo (ex.: "armadilhas" em vez de "pegadinhas").

> **Transição futura para inglês (bilingual front door):** se/quando o repositório for
> internacionalizado, o padrão-alvo é README em inglês (porta de entrada) + docs
> profundos em PT-BR, migrados progressivamente. Não fazer antes da apresentação.

## 4. Tom e registro

**Permitido:** voz impessoal/3ª pessoa, frases diretas, exemplos concretos ancorados em
`caminho:linha`.

**Proibido:**
- Gírias: "shipou", "nukar", "gambiarra", "pegadinhas", "tô mexendo", "pra/pro" (use
  "para"/"pelo").
- **Emoji como status semântico** (✅ ⬜ ⚠️ 🚨). Use uma coluna textual de status
  (`Pendente` / `Em andamento` / `Concluído`) ou um rótulo `Atenção:` em texto.
- Caracteres homóglifos (ex.: "а" cirílico em texto latino). Recomenda-se linter no CI.

## 5. Estrutura recomendada por tipo de documento

- **Documento de produto/engenharia**: front-matter → resumo de 1 parágrafo (*blockquote*)
  → seções numeradas → "Histórico do documento" no fim.
- **ADR**: ver template em §6.
- **Runbook**: ver template em §7.

Todo documento termina com uma tabela de histórico:

```markdown
## Histórico do documento

| Versão | Data | Autor | Mudança |
|---|---|---|---|
| 1.0 | AAAA-MM-DD | <papel> | Criação |
```

## 6. Template de ADR (MADR)

> Referência exemplar já no repositório: `docs/adr/0029-lgpd-operational.md`.

```markdown
# NNNN — Título da decisão

- Status: proposto | aceito | substituído por XXXX | obsoleto
- Data: AAAA-MM-DD
- Decisores: <papéis ou nomes>
- Relacionado: <ADRs, PRs, migrations relacionados> (opcional)

## Contexto
Qual problema/força motivou a decisão. Factual, sem solução ainda.

## Decisão
O que foi decidido, no presente do indicativo. Específico e verificável.

## Consequências
O que muda. **Incluir trade-offs negativos explícitos** (subseção "Negativas").

## Alternativas consideradas
Pelo menos uma alternativa real e por que foi rejeitada.
```

Regras: ADR aceito é **imutável** (decisão obsoleta → ADR sucessor com `Status:
substituído por NNNN` no original). Numeração sequencial de 4 dígitos, kebab-case.

## 7. Template de runbook

```markdown
---
title: "Runbook — <operação>"
owner: <papel>
status: approved
version: 1.0
last_reviewed: AAAA-MM-DD
---

# Runbook — <operação>

> Objetivo em uma frase.

## Pré-requisitos
- Acessos, variáveis, ferramentas necessárias.

## Sequência (estado)

| Passo | Ação | Resultado esperado | Status |
|---|---|---|---|
| 1 | ... | ... | Pendente |

## Passos
1. Passo idempotente, com o comando exato.
   Atenção: avisos em texto, nunca em ALL-CAPS + emoji.

## Verificação (smoke)
Como confirmar que deu certo.

## Rollback
Como desfazer com segurança.
```

## 8. Referências cruzadas

- Links **relativos** com âncora de seção: `[ADR 0029 — LGPD operacional](../adr/0029-lgpd-operational.md)`.
- Ao citar ADR/migration, **verifique que o número existe** (linter de docs no CI).
- Evite "ver acima/abaixo"; linke a seção.

## 9. Linter de docs no CI (proposta)

Job que falha o PR quando:
- Falta front-matter ou `last_reviewed` está acima de N dias.
- Há link relativo quebrado ou ADR/migration citado que não existe.
- Há gíria da lista-negra ou emoji em posição de status.
- Há caractere homóglifo em texto latino.

## 10. Checklist de reconciliação (para o PULL_REQUEST_TEMPLATE)

```markdown
- [ ] Docs vivos reconciliados (backlog/roadmap/vision/standards/data-model) se esta PR
      mudou status de US, migration ou ADR.
- [ ] Nenhum número de fato canônico (contagem de ADRs/migrations) foi recopiado —
      apenas linkado à fonte única.
- [ ] Front-matter atualizado (last_reviewed).
```

## Histórico do documento

| Versão | Data | Autor | Mudança |
|---|---|---|---|
| 1.0 | 2026-06-06 | Arquiteto NORA (Tech Lead) | Criação como parte da auditoria pré-apresentação |
