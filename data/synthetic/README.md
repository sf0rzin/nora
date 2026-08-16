# Dataset Sintético — NORA

Transcrições **fictícias** geradas para teste, demo, regressão do worker NLP e notebook de Data Science. Nenhuma pessoa, empresa ou negócio descrito é real.

## Tenants de Demo

| Slug | Nome | Setor |
|---|---|---|
| `acme-software` | Acme Software Solutions | B2B SaaS — ERP |
| `northwind-fintech` | Northwind Fintech | Fintech — Pagamentos e Conciliação B2B |
| `solo-launch` | Solo Launch | Software — Startup / Creator Tools |

Os contextos completos estão em `tenants/<slug>.context.json` e seguem o schema esperado pelo `TenantContextRequest` do backend Java (`companyName`, `industry`, `valueProposition`, `idealCustomerProfile`, `products[].keyDifferentiators`, `competitors`, `objectionHandling`).

## Reuniões (12 transcrições)

Todas em `.txt` com cabeçalho descritivo (`Reunião`, `Data`, `Horário`, `Participantes`) e diálogo multi-speaker realista em PT-BR. Tamanho médio entre 4,4 KB e 6,3 KB.

| # | Arquivo | Tenant | Tipo | Sinais Esperados |
|---|---|---|---|---|
| 01 | `meetings/01-acme-discovery-lead-novo.txt` | acme-software | Discovery (lead novo) | Oportunidade nova + concorrentes mencionados (TotalSys, OmniBusiness) + objeção de prazo |
| 02 | `meetings/02-acme-followup-financeiro-pro.txt` | acme-software | Follow-up comercial | Decisão de proposta + cláusula de upgrade + cronograma de implantação |
| 03 | `meetings/03-acme-upsell-manufatura-concorrente.txt` | acme-software | Upsell + concorrente | Upsell Manufatura para nova planta + concorrente TotalSys + renovação |
| 04 | `meetings/04-acme-churn-suporte-performance.txt` | acme-software | Risco de churn | Insatisfação com performance + falha de SLA de suporte + conversa formal com OmniBusiness |
| 05 | `meetings/05-northwind-renewal-churn-risco.txt` | northwind-fintech | Renovação + risco churn | Pressão de preço (-20%) + objeção de prazo de contrato + ameaça do PayMatch |
| 06 | `meetings/06-northwind-prospect-poc.txt` | northwind-fintech | Prospect + PoC | ICP forte + oportunidade Reconcile + interesse em Payments futuro |
| 07 | `meetings/07-northwind-qbr-cliente-saudavel.txt` | northwind-fintech | QBR trimestral | Cliente saudável + expansão regional + interesse em Risk + renovação preventiva |
| 08 | `meetings/08-northwind-objecao-integracao-tecnica.txt` | northwind-fintech | Objeção técnica explícita | HMAC, retry, sandbox, migração de gateway — objeção viva |
| 09 | `meetings/09-northwind-upsell-payments-risk.txt` | northwind-fintech | Upsell forte | Expansão para Payments + Risk com shadow mode + ROI claro |
| 10 | `meetings/10-solo-standup-bug-agendamento.txt` | solo-launch | Stand-up engenharia | Hotfix de bug + decisão de stack (Stripe) + marco de produto |
| 11 | `meetings/11-solo-roadmap-concorrente.txt` | solo-launch | Roadmap | Priorização Q3 + concorrentes CreatorHub/PostMate/Buffer + ROI quantificado |
| 12 | `meetings/12-solo-sprint-planning.txt` | solo-launch | Sprint planning | Capacidade + estimativa + riscos + decisão de infraestrutura |

## Formatos Alternativos

Para validar o suporte a múltiplos formatos do upload (US07: `.txt`, `.vtt`, `.srt`), alguns cenários estão disponíveis em formatos alternativos com o mesmo conteúdo semântico.

### WebVTT (`.vtt`)

| Arquivo | Cenário |
|---|---|
| `meetings/03-acme-upsell-manufatura-concorrente.vtt` | Upsell + concorrente |
| `meetings/07-northwind-qbr-cliente-saudavel.vtt` | QBR trimestral |
| `meetings/10-solo-standup-bug-agendamento.vtt` | Stand-up interno |

### SubRip (`.srt`)

| Arquivo | Cenário |
|---|---|
| `meetings/05-northwind-renewal-churn-risco.srt` | Renovação com risco de churn |
| `meetings/08-northwind-objecao-integracao-tecnica.srt` | Objeção técnica de integração |

## Cobertura de Cenários

| Cenário | Reuniões |
|---|---|
| Discovery / lead novo | 01, 06 |
| Upsell | 02, 03, 09 |
| Concorrente mencionado | 01, 03, 05, 08, 11 |
| Risco de churn / insatisfação | 04, 05 |
| Objeção comercial / técnica explícita | 02, 04, 05, 08 |
| QBR / review de saúde | 07 |
| Decisões + ações + riscos (Core) | 10, 11, 12 |
| Datas absolutas (sem relativas) | Todas |
| Valores em R$ formatados BR | 01, 02, 03, 04, 05, 06, 09, 11, 12 |

## Como Usar

1. Worker NLP roda contra estes arquivos em testes de regressão e no notebook DS.
2. Esperado: o pipeline gera `meeting-analysis-v1` válido para todos os 12, com extração de tarefas, decisões, riscos, oportunidades e menções a concorrentes.
3. Para testes unitários menores e smoke tests, ver `data/samples/`.

## Política de PII

Todos os dados aqui são fictícios. Nomes, empresas, CNPJs e valores são ilustrativos. Mesmo assim, o pipeline deve passar pelo PII Shield para validar que o redactor não quebra a análise. Se algum dado pessoal real for inserido por engano, o redactor deve mascarar antes de qualquer chamada de LLM (ver `AGENTS.md` — não-negociáveis).

## Política de Hardcode

Nenhum tenant referencia a TOTVS ou outros parceiros reais. NORA é horizontal por design (ver `docs/product/vision.md` — Tenant Context System). TOTVS aparece como caso de uso futuro no roadmap, não como tenant de demo.
