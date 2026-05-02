# Dataset Sintético — NORA

Transcrições **fictícias** geradas para teste, demo e regressão do worker NLP. Nenhuma pessoa, empresa ou negócio descrito é real.

## Tenants de Demo

| Slug | Nome | Setor |
|---|---|---|
| `acme-software` | Acme Software | B2B SaaS — ERP |
| `northwind-fintech` | Northwind Fintech | Pagamentos B2B |
| `solo-launch` | Solo Launch | Startup Core (uso individual) |

Os contextos completos estão em `tenants/<slug>.context.json`.

## Reuniões

| Arquivo | Tenant | Tipo | Sinal Esperado |
|---|---|---|---|
| `meetings/01-acme-discovery.txt` | acme-software | Comercial — Discovery | Upsell + sinal de concorrente |
| `meetings/02-acme-followup.txt` | acme-software | Comercial — Follow-up | Decisão + risco de prazo |
| `meetings/03-northwind-renewal.txt` | northwind-fintech | Comercial — Renovação | Risco de churn + objeção de preço |
| `meetings/04-northwind-prospect.txt` | northwind-fintech | Comercial — Prospect | Oportunidade + ICP correto |
| `meetings/05-solo-engineering.txt` | solo-launch | Core — Engenharia | Decisões técnicas + tarefas |
| `meetings/06-solo-product.txt` | solo-launch | Core — Produto | Roadmap + bloqueio |

## Como Usar

1. Worker NLP roda contra estes arquivos em testes de regressão.
2. Esperado: o pipeline gera `meeting-analysis-v1` válido para todos os 6.
3. As asserções mínimas estão em `expected/<arquivo>.assertions.yaml` (a ser criado pela trilha do colega).

## Política de PII

Os dados aqui são fictícios. Mesmo assim, o pipeline deve passar pelo PII Shield para validar que o redactor não quebra a análise.
