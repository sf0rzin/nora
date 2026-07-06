# ADR 0033 — Estratégia de PII no caminho do chat (redação estruturada no BFF + PERSON_NAME via worker)

- **Status:** aceito
- **Data:** 2026-07-06
- **Decisores:** sys0xFF (PO) + Claude Fable 5 (auditoria pós-pitch)
- **Relacionados:** ADR 0012 (estratégia de PERSON_NAME no worker — **complementa**, não substitui),
  ADR 0004 (provider-agnóstico), ADR 0003 (JSON Schema strict). Não altera o pipeline de análise.

## Contexto

O não-negociável do ADR 0012 é "PII nunca crua na LLM": o `PIIShield` do worker é o último gate
antes de qualquer chamada ao provedor no **pipeline de análise**. O chat Core (chat-first, RAG),
porém, tem um caminho de LLM **separado** que não passa pelo worker:

`apps/web/src/app/api/chat/route.ts` (BFF) monta contexto de workspace + histórico e chama o
provedor de chat direto. A redação nesse caminho é feita por um port em JS
(`apps/web/src/lib/pii/redact.ts`) que cobre só PII **estruturada** (e-mail, telefone, CPF, CNPJ,
cartão — com DV/Luhn), **de propósito**, para não over-redigir Title Case legítimo no chat.

A auditoria pós-pitch (2026-07-06) encontrou dois problemas nesse caminho:

1. **Vazamento estruturado para embeddings.** A última mensagem do usuário virava a query da busca
   semântica (`GET /meetings/search?q=`) e ia **crua** para o provedor de **embeddings** (Gemini
   por default — um provider externo distinto do de chat) **antes** do `redactPii`. Uma pergunta
   como "qual reunião falou do CPF 111.444.777-35?" exfiltrava o CPF.
2. **PERSON_NAME nunca redigido no chat.** O `redactPii` não cobre nome de pessoa, então nomes em
   mensagens do usuário e em títulos de reunião (que vêm crus do upload) chegam ao provedor de
   chat. Isso é PII coberta pelo ADR 0012 no worker, mas com cobertura zero no caminho do chat — e
   a decisão vivia só num comentário de código, sem ADR.

## Decisão

**A redação de PII estruturada permanece no BFF (rápida, sem hop de rede) e passa a cobrir também
a query de RAG; a cobertura de PERSON_NAME no chat será entregue roteando os textos do chat pelo
`PIIShield` do worker — não por duplicar a lista de nomes em JS.**

1. **Query de RAG redigida antes do embedding** (entregue neste ADR): `route.ts` aplica
   `redactPii` na última mensagem do usuário antes de passá-la a `buildWorkspaceContext` →
   `/meetings/search`. Placeholder `[[CPF_1]]` degrada pouco a similaridade semântica (não se busca
   por número de CPF). Defesa em profundidade recomendada (follow-up): redigir também `q` no
   `MeetingsController.search`/`EmbeddingService` do lado Java.
2. **PERSON_NAME no chat = roteamento pelo worker** (follow-up declarado): em vez de portar a lista
   `_BR_TOP_NAMES` (~270 nomes) para o JS — exatamente o espelhamento manual que já causou bypass
   estruturado (fix órfão `b0bad1d`) —, o BFF passará a chamar um endpoint interno do worker
   (`POST /redact`, autenticado por `X-Internal-Token`) que reusa o `PIIShield` completo (com o
   accent-fold restaurado e o backstop NER quando o PR #289 entrar). Fallback SOFT para o
   `redactPii` local em qualquer falha, para nunca derrubar o chat.

## Resíduo aceito (até o follow-up)

Enquanto o roteamento pelo worker não entra, **PERSON_NAME em mensagens do chat e em títulos de
reunião ainda chega ao provedor de chat**. É um risco residual **explícito e documentado** (não um
comentário escondido). Mitigadores atuais: a PII estruturada de maior risco LGPD (documentos,
contato, cartão) está coberta nos dois provedores (chat e embeddings); o system prompt instrui o
modelo a não expor PII. Este ADR aciona o trigger nº 3 do ADR 0012 (bug report de falso negativo
de nome causando vazamento) — o backstop NER (spaCy) já provado em #289 é o alvo do resgate.

## Alternativas Consideradas

- **Portar `_BR_TOP_NAMES` + prefixos para `redact.ts`:** rejeitado como solução definitiva —
  duplica a fonte de verdade e reintroduz o anti-padrão de espelhamento manual (o próprio bypass
  estruturado nasceu de um port desalinhado). Aceitável só como paliativo se o worker-routing
  atrasar.
- **Roteador de redação como microserviço à parte:** overkill; o worker já tem o shield e o token
  interno.
- **Não redigir a query de RAG (aceitar o vazamento estruturado):** rejeitado — viola o ADR 0012 e
  dobra a superfície externa (provider de embeddings ≠ provider de chat).

## Consequências

- **Positivas:** fecha o vazamento estruturado para o provider de embeddings (P0); dá ao chat uma
  fonte única de redação de nome (worker) em vez de espelhamento frágil; o risco residual passa a
  ser rastreável (ADR + follow-up), não implícito.
- **Negativas / dívidas:** o roteamento pelo worker adiciona um hop de rede por turno de chat
  (mitigado por batch + fallback SOFT + cache); enquanto não entra, PERSON_NAME no chat é resíduo
  aceito. O endpoint `/redact` do worker precisa de auth interna e de teste de contrato.

## Histórico

| Data | Decisor | Mudança |
|---|---|---|
| 2026-07-06 | sys0xFF + Claude | ADR criado e aceito. Fix da query de RAG entregue; PERSON_NAME no chat via worker declarado como follow-up. |
