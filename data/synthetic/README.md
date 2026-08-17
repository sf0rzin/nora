# Dataset Sintético — NORA

Transcrições **fictícias** geradas para teste, demo, regressão do worker NLP e notebook de Data Science. Nenhuma pessoa, empresa ou negócio descrito é real.

## Tenants de Demo

| Slug | Nome | Setor |
|---|---|---|
| `acme-software` | Acme Software Solutions | B2B SaaS — ERP |
| `northwind-fintech` | Northwind Fintech | Fintech — Pagamentos e Conciliação B2B |
| `solo-launch` | Solo Launch | Software — Startup / Creator Tools |
| `meridian-erp` | Meridian Sistemas de Gestão | B2B SaaS — ERP para logística e transporte |

Os contextos completos estão em `tenants/<slug>.context.json` e seguem o schema esperado pelo `TenantContextRequest` do backend Java (`companyName`, `industry`, `valueProposition`, `idealCustomerProfile`, `products[].keyDifferentiators`, `competitors`, `objectionHandling`).

## Reuniões (17 transcrições)

Todas em `.txt` com cabeçalho descritivo (`Reunião`, `Data`, `Horário`, `Participantes`) e diálogo multi-speaker realista em PT-BR. Tamanho entre 2,7 KB e 6,4 KB (as 01-12 ficam entre 4,4 KB e 6,4 KB; as 13-17, escritas para o seed de demonstração, entre 2,7 KB e 4,1 KB).

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
| 13 | `meetings/13-meridian-discovery-south-region.txt` | meridian-erp | Discovery (conta Central Log Transportes) | Sinais de compra fortes: orçamento medido, decisor identificado, próximo passo agendado |
| 14 | `meetings/14-meridian-objection-south-region.txt` | meridian-erp | Negociação (mesma conta) | Objeções vivas: preço, prazo, lacuna técnica de integração + concorrente TotalSys com proposta menor |
| 15 | `meetings/15-meridian-closing-south-region.txt` | meridian-erp | Fechamento (mesma conta) | Decisão aprovada + contrato + expansão de escopo em aberto |
| 16 | `meetings/16-meridian-renewal-southeast-region.txt` | meridian-erp | Renovação (conta Aurora Distribuidora) | Cliente saudável com irritação registrada + upsell de módulo |
| 17 | `meetings/17-meridian-internal-planning.txt` | meridian-erp | Planejamento interno | Decisões e ações internas, **sem cliente** — o caso em que Customer Confidence deve ser nulo |

### As três reuniões da mesma conta (13, 14, 15)

Existem para que Customer Confidence tenha **histórico**, e não só um cartão isolado. As três
citam `Central Log Transportes` com o mesmo nome do começo ao fim, porque a conta é resolvida por
get-or-create em `LOWER(name)`: um analisador que escrever o nome de dois jeitos cria duas contas
e não há tendência nenhuma para calcular.

A tendência é do **backend**, não do modelo: `CustomerConfidenceService.computeTrend` compara com a
avaliação anterior da mesma conta, com faixa morta de ±5 pontos, e descarta o palpite do worker.
Por isso o seed sobe as três em ordem narrativa e espera cada análise terminar antes de subir a
próxima — a comparação é por `created_at` da avaliação, não pela data da reunião.

**Com `USE_LLM_STUB=true` nada disso acontece.** O stub emite `customerConfidence` com
`accountName` sempre nulo (está escrito no próprio `stub_analyzer.py`), e
`CustomerConfidenceService.persist` é no-op documentado quando `accountName` é nulo ou vazio:
nenhuma conta, nenhuma avaliação, nenhuma tendência. Esse bloco do dataset só produz efeito contra
um provedor real.

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
| Discovery / lead novo | 01, 06, 13 |
| Upsell | 02, 03, 09, 15, 16 |
| Concorrente mencionado | 01, 03, 05, 08, 11, 14 |
| Risco de churn / insatisfação | 04, 05 |
| Objeção comercial / técnica explícita | 02, 04, 05, 08, 14, 16 |
| QBR / review de saúde | 07 |
| Decisões + ações + riscos (Core) | 10, 11, 12, 17 |
| Mesma conta em reuniões sucessivas (tendência) | 13 → 14 → 15 |
| Reunião interna sem cliente | 17 |
| Datas absolutas (sem relativas) | Todas |
| Valores em R$ formatados BR | 01, 02, 03, 04, 05, 06, 09, 11, 12, 13, 14, 15, 16 |

## Como Usar

1. Worker NLP roda contra estes arquivos em testes de regressão e no notebook DS.
2. Esperado: o pipeline gera `meeting-analysis-v1` válido para todas as 17, com extração de tarefas, decisões, riscos, oportunidades e menções a concorrentes.
3. Para testes unitários menores e smoke tests, ver `data/samples/`.
4. Para povoar um ambiente local com a narrativa de demonstração, use `scripts/seed-demo.sh`
   (alvo `make seed-demo`). Ele sobe estes arquivos pela API HTTP — `POST /meetings` multipart,
   `PUT /tenant/context`, `POST /iam/policies` —, nunca por SQL direto. O roteiro que consome
   esse ambiente é `docs/challenge/demo-script.md`.

## Política de PII

Todos os dados aqui são fictícios. Nomes, empresas, CNPJs e valores são ilustrativos. Mesmo assim, o pipeline deve passar pelo PII Shield para validar que o redactor não quebra a análise. Se algum dado pessoal real for inserido por engano, o redactor deve mascarar antes de qualquer chamada de LLM (ver `AGENTS.md` — não-negociáveis).

## Política de Hardcode — e por que um seed com vocabulário de fornecedor não a viola

O não-negociável do repositório é **"nenhuma regra de TOTVS hardcoded no código de produto"**
(`AGENTS.md`). Vale a pena separar as duas coisas que essa frase junta, porque a sub-fase 1.11
especificava um seed com vocabulário TOTVS e isso pareceu, por um tempo, contradizer a regra.

**Não contradiz.** O que a regra proíbe é o *produto* conhecer um fornecedor: um `if` no
analisador, um termo fixo no prompt, um default no backend, uma coluna com nome de módulo de
terceiro. Nada disso tem a ver com o conteúdo de um `context.json` de demonstração. Um tenant cujo
contexto fala dos módulos de um ERP passa exatamente pelo mesmo `PUT /tenant/context` que qualquer
cliente usaria, e é justamente a prova de que o contexto é configuração e não código — o mesmo
binário serve um ERP de logística e uma fintech.

**Ainda assim este dataset usa fornecedor fictício, e por dois motivos concretos:**

1. A primeira linha deste arquivo declara que nenhuma pessoa, empresa ou negócio aqui é real.
   Colocar uma empresa real como tenant quebraria essa declaração para todo o resto do dataset.
2. As transcrições são diálogos com nomes, preços, risco de churn e ameaça de concorrente. Fora do
   repositório, um arquivo desses atribuído a uma empresa real é indistinguível de material real
   sobre ela. O repositório é público (AGPL-3.0, ADR 0017).

O que prova configurabilidade é o **caminho** — vocabulário de fornecedor entrando pela API
pública, sem uma linha de código de produto mudar — e o caminho é idêntico com qualquer marca no
payload. `meridian-erp` é um ERP fictício de logística, com módulos, diferenciais, concorrentes e
tratativas de objeção próprios; trocar esse JSON por outro é a única coisa necessária para o NORA
falar de outro mercado.

**Uma nuance que já existe e não deve ser ampliada:** a lista negativa do PII Shield contém
`TOTVS` e `Protheus` entre os ~80 termos que ele **não** deve redigir como nome de pessoa
(ADR 0012, `services/nlp-worker/src/nora_nlp/services/pii_shield.py`). Isso é código de produto,
é anterior a este seed e existe para evitar falso positivo do detector de nomes — não é regra de
negócio de fornecedor. Não amplie essa lista por causa de dado de demonstração.
