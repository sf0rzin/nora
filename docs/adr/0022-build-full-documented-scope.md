# 0022 — Construir o escopo documentado completo (reverte o defer do ADR 0014)

- Status: aceito
- Data: 2026-05-27
- Decisores: Stratfy (Anthony, PO)
- Substitui: ADR 0014 (defer post-MVP commercial gate)

## Contexto

O ADR 0014 (2026-05-14) fechou a v1 do backlog e adiou 14 US (SSO, upload de áudio,
busca semântica, MCP, Account Health agregado, export, métricas, policy
templates/simulator, permission boundaries, histórico de contexto) pra liberar foco na
janela pré-pitch.

Durante a varredura de solidez de 2026-05-27, o PO reavaliou a direção: em vez de
**remover** da UI o que não está implementado (ex.: botão SSO morto, MCP "vendido" na
landing), o produto deve **implementar** o que os documentos dizem que ele pode ou deve
ter — desde que viável numa conta **Azure for Students**. Motivação: entregar um produto
coerente e completo (não "100 promessas inacabadas"), aproveitando o orçamento e o tempo
agentic disponíveis.

## Decisão

**Reverter o defer do ADR 0014.** As US adiadas voltam a ser escopo ativo e devem ser
implementadas de verdade, em fatias sólidas (cada fatia compila, testa e entra
commitada), priorizadas por valor de demo × viabilidade × dependências.

Restrição de viabilidade: **tem que caber num Azure for Students**. Substituições
conscientes pra respeitar o orçamento:

- **Busca semântica (US15):** `pgvector` no Postgres existente + embeddings OpenAI, em
  vez de Azure AI Search (evita ~R$400/mês). Atrás da flag `enableSearch`.
- **Upload de áudio (US08):** reusar o recurso **Azure Speech** já provisionado
  (transcrição batch/fast).
- **SSO (US05):** Microsoft Entra ID free tier (app registration). Botão só aparece
  quando configurado.
- **MCP (US27-29, US47):** código puro (servidores MCP), sem dependência de Azure.

Recursos que exigem provisionamento manual pelo PO entram atrás de flag e ficam
documentados em `infra/AZURE-PROVISIONING.md`.

## Consequências

**Positivas:** produto coerente ponta-a-ponta; a landing deixa de prometer o que não
existe (passa a existir); demo mais forte pro pitch FIAP/TOTVS; rastreabilidade mantida
(o histórico de US do 0014 continua válido como contexto).

**Negativas / riscos:** superfície muito maior pra manter e testar; risco de
meia-implementação se fatias não forem fechadas — mitigado pela regra "fatia = compila +
testa + commit". Custo Azure sobe se features pagas forem ligadas — mitigado pelas flags,
pelas substituições acima e pelo provisionamento sob controle do PO.

## Alternativas Consideradas

1. **Manter o defer (ADR 0014)** — rejeitado pelo PO: a janela e o orçamento agora
   permitem entregar o escopo completo, e "remover o inacabado" deixa o produto raso.
2. **Implementar só o subconjunto de maior valor de demo** — rejeitado: o PO pediu
   explicitamente "absolutamente todas as funções que os documentos dizem que podem ou
   devem existir".

## Histórico

| Data | Decisor | Mudança |
|---|---|---|
| 2026-05-27 | Stratfy (Anthony, PO) | ADR criado; reverte o defer do ADR 0014 sob a restrição "viável no Azure for Students". |
