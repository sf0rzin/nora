# 0006 — Customer Confidence (por reunião) e Account Health (agregado por conta)

- Status: aceito
- Data: 2026-05-07
- Decisores: Time NORA

## Contexto

A versão inicial do PROJECT.md já mencionava um **Account Health Score temporal** como output do plano Enterprise, mas sem definir como esse score é calculado. Em brainstorming, surgiu a ideia de um **"nível de confiança da empresa"** — refinado pelo time como **confiança do CLIENTE/lead na nossa empresa** (sentimento + sinais de compra/objeções da call), avaliada **por reunião**.

A pergunta era: Customer Confidence substitui o Account Health, ou complementa? O time alinhou que **complementa**: Confidence é o sinal por reunião; Health é o agregado da conta no tempo, alimentado por Confidence + riscos + oportunidades + recência.

## Decisão

Adotar dois indicadores distintos e complementares para o plano **Enterprise**:

### 1. Customer Confidence Assessment (por reunião)

- Gerado pelo worker NLP a cada análise de reunião com cliente/lead.
- Saída integrada ao `meeting-analysis-v1.schema.json`:
  ```json
  "customerConfidence": {
    "score": 62,
    "band": "MEDIUM",
    "trend": "DECLINING",
    "buyingSignals": [
      { "type": "BUDGET_DISCUSSED", "quote": "...", "weight": 0.7 }
    ],
    "objections": [
      { "type": "COMPETITOR_MENTION", "quote": "...", "severity": "HIGH", "competitor": "Senior" }
    ],
    "rationale": "..."
  }
  ```
- **Score (0–100)** combina:
  - Tom geral da fala do cliente (positivo/negativo, derivado do `sentimentOverall` ponderado pelas falas atribuídas ao cliente).
  - Quantidade e peso de **buying signals** (`BUDGET_DISCUSSED`, `STAKEHOLDER_INVOLVED`, `NEXT_STEP_REQUESTED`, etc.).
  - Penalidade por **objeções** (peso por `severity`; menção a concorrente com severity HIGH penaliza forte).
- **Banda** derivada: `LOW` (<40), `MEDIUM` (40–69), `HIGH` (≥70).
- **Trend** comparado à última análise da mesma `customer_account`. `null` se for a primeira.
- **Nullable**: reuniões internas (sem `customer_account` vinculada) não geram Confidence.

### 2. Account Health Score (agregado por conta no tempo)

- Snapshot recalculado sempre que uma nova análise gerar Customer Confidence para a conta.
- Persistido em `account_health_snapshots` (ver data-model §2.24).
- **Score (0–100)** combina:
  - Média móvel ponderada (recência) das últimas N avaliações de Customer Confidence da conta.
  - Riscos abertos da conta (penalidade por `severity`).
  - Oportunidades abertas (bonus por `estimatedValue`).
  - Recência da última interação (penalidade por silêncio prolongado).
- **Banda**: `AT_RISK` (<35), `WATCH` (35–59), `HEALTHY` (60–84), `STRONG` (≥85).
- **Trigger de alerta** na mudança de banda para pior (US43).

### Modelo de dados

Novas tabelas em `docs/data-model.md` §2.19–§2.24:

- `customer_accounts` (lead/cliente do tenant)
- `meeting_account_links` (N:N reunião↔conta)
- `customer_confidence_assessments` (1:N por análise; geralmente 1:1)
- `customer_buying_signals`, `customer_objections` (filhas)
- `account_health_snapshots` (séries temporais)

### Escopo MVP

- Customer Confidence: **MVP (Must)**.
- Account Health agregado: **MVP (Should)** — fórmula simples baseada em média móvel e contagens; refinamentos numéricos podem evoluir sem breaking change.
- Alertas de mudança de banda: **MVP (Should)** — push in-app/email; webhook fica para post-MVP.

## Consequências

**Positivas:**

- Define com clareza o output Enterprise mais valioso: detectar erosão de confiança antes do churn.
- Confidence por reunião é facilmente revisável pelo AE (sinais e objeções vêm com citações textuais).
- Health agregado dá ao gerente comercial visão consolidada sem depender da memória do AE.

**Negativas:**

- Aumento de superfície do schema do worker — testes de validação precisam ser atualizados.
- Score calculado por LLM tem variância. Mitigação: temperatura baixa, prompt com critérios explícitos, validação humana periódica.
- Account Health depende de `customer_accounts` modelada — exige UI/import mínimo no MVP (planejar como story de E7 ou linkar com integração CRM futura).

## Alternativas Consideradas

1. **Apenas Account Health, sem Customer Confidence por reunião.** Rejeitado: perderia rastreabilidade fina; o AE não saberia o que mudou em qual reunião.
2. **Apenas Customer Confidence, sem agregação.** Rejeitado: o gerente comercial precisa de visão por conta; calcular no frontend a cada query é caro e instável.
3. **Confiança da NOSSA empresa em fechar o deal (forecast/probabilidade).** Rejeitado: foi pedido explicitamente — o time quer a confiança do CLIENTE em nós, não a nossa probabilidade interna. Forecast pode entrar como indicador separado no futuro.

## Regras Acompanhantes

- Confidence é calculado **somente** quando a reunião está vinculada a uma `customer_account`; caso contrário o campo vem `null` no schema.
- Citações textuais (`quote`) são obrigatórias em todo `buyingSignal` e `objection` — sem citação, o LLM não emite o sinal.
- Account Health snapshot é **append-only**: histórico nunca é editado; correções viram novo snapshot.
- Nunca usar Customer Confidence isolado para decidir comissão/avaliação do AE — documentar isso na UI.
- PII Shield aplica antes do prompt: nomes de stakeholders sensíveis não vão como texto cru.
