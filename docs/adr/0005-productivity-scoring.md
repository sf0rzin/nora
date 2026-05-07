# 0005 — Productivity Score da reunião (opt-in, baseado em objetivo declarado)

- Status: aceito
- Data: 2026-05-07
- Decisores: Time NORA

## Contexto

Em brainstorming do time surgiu a ideia de medir o quão produtiva foi uma reunião. Avaliar produtividade "no vácuo" (apenas pelo conteúdo da transcrição) gera resultados arbitrários — qualquer reunião pode parecer produtiva ou improdutiva conforme o gosto do leitor. O time refinou a ideia para um modelo **opt-in** em que o usuário declara o objetivo da reunião, transformando a avaliação em algo verificável.

A integração com fontes externas de "estado do projeto" (Jira, Linear, Azure DevOps, GitHub Projects) é um caminho natural, mas exige MCPs e autenticação por tenant — fora do MVP.

## Decisão

Adicionar um recurso opcional de **Productivity Score** com as seguintes propriedades:

1. **Opt-in por reunião.** Sem objetivo declarado, a NORA não tenta avaliar produtividade.
2. **Entrada do usuário** ao subir a reunião (ou ao editar a reunião antes do reprocessamento):
   - `purpose` (texto livre): "Refinement do épico X"
   - `expectedOutcomes` (lista de strings): pontos que precisavam ser tratados/decididos
   - `projectStateSnapshot` (texto opcional): "o que está feito" — manual no MVP; via MCP no pós-MVP
3. **Saída do worker**, dentro do `meeting-analysis-v1.schema.json`:
   ```json
   "productivity": {
     "score": 78,
     "band": "HIGH",
     "coverage": [
       { "expectedOutcome": "Definir critérios de aceite da feature X", "status": "ADDRESSED", "evidence": "..." },
       { "expectedOutcome": "Decidir provider de pagamento", "status": "PARTIAL", "evidence": "..." }
     ],
     "offTopicRatio": 0.18,
     "decisionDensity": 0.6,
     "rationale": "..."
   }
   ```
4. **Fórmula do score (v1):** combinação ponderada de:
   - Cobertura de outcomes esperados (peso dominante): `% ADDRESSED + 0.5 × % PARTIAL`.
   - Densidade de decisões (decisões por minuto, normalizada).
   - Penalidade por `offTopicRatio` alto.
   - Bonus por geração de action items concretos com responsável definido.
5. **Banda derivada** a partir do score: `LOW` (<40), `MEDIUM` (40–69), `HIGH` (≥70). Tunável via configuração do worker, não hardcoded em domain.
6. **Ausência de outcomes** = não emitir `productivity` no payload (o campo é nullable). Não inventar score sem gabarito.

**Persistência:** ver `docs/data-model.md` §2.19–§2.21 (`meeting_goals`, `meeting_productivity_assessments`, `meeting_outcome_coverage`).

**Tier:** Core e Enterprise (não há motivo para limitar ao Enterprise — Core também ganha valor).

## Consequências

**Positivas:**

- Avaliação passa a ser **verificável**: o LLM compara o que aconteceu com o que o usuário pediu para acontecer.
- Cria um hook natural para uma futura integração MCP de project state (US42), com dados estruturados.
- Funciona já no MVP sem dependência de integração externa.

**Negativas:**

- Adiciona campos de input no upload e nova UI de exibição. Pequeno aumento de superfície.
- Score calculado por LLM tem variância — precisa de prompt determinístico (temperatura baixa, exemplos few-shot) e idealmente validação humana periódica nos primeiros tenants.
- Ao processar com `goal` declarado, o prompt fica maior → custo levemente maior por análise.

## Alternativas Consideradas

1. **Score sem objetivo declarado.** Rejeitado: subjetivo, indefensável.
2. **Apenas qualitativo (Low/Medium/High sem número).** Rejeitado: usuários querem comparar reuniões; um número ajuda dashboards e tendências.
3. **Productivity Score sempre ligado.** Rejeitado: força o usuário a declarar objetivo mesmo quando ele só quer um resumo rápido.

## Regras Acompanhantes

- O recurso é estritamente opt-in. UI deve deixar claro que sem `expectedOutcomes` o score não será gerado.
- Nunca usar o score como métrica de avaliação de pessoas — documentar isso na própria UI ("indicador da reunião, não dos participantes").
- O `projectStateSnapshot` segue PII Shield: redação antes de enviar ao LLM.
- A integração MCP (US42) entra como `Won't Have v1` no backlog; um novo ADR cobrirá o desenho do MCP `nora-mcp-projectstate` quando chegar a hora.
