# ADR 0033 — PII strategy on the chat path (structured redaction in the BFF + PERSON_NAME via the worker)

- **Status:** accepted
- **Date:** 2026-07-06
- **Deciders:** sys0xFF (PO) + Claude Fable 5 (post-pitch audit)
- **Related:** ADR 0012 (PERSON_NAME strategy in the worker — **complements**, does not replace it),
  ADR 0004 (provider-agnostic), ADR 0003 (strict JSON Schema). Does not change the analysis pipeline.

## Context

The non-negotiable of ADR 0012 is "PII never raw in the LLM": the worker's `PIIShield` is the last gate
before any call to the provider in the **analysis pipeline**. The Core chat (chat-first, RAG),
however, has a **separate** LLM path that does not go through the worker:

`apps/web/src/app/api/chat/route.ts` (BFF) assembles workspace context + history and calls the
chat provider directly. Redaction on that path is done by a JS port
(`apps/web/src/lib/pii/redact.ts`) that covers only **structured** PII (email, phone, CPF, CNPJ,
card — with check digit/Luhn), **on purpose**, so as not to over-redact legitimate Title Case in the chat.

The post-pitch audit (2026-07-06) found two problems on that path:

1. **Structured leak to embeddings.** The user's last message became the semantic search query
   (`GET /meetings/search?q=`) and went **raw** to the **embeddings** provider (Gemini
   by default — an external provider distinct from the chat one) **before** `redactPii`. A question
   like "qual reunião falou do CPF 111.444.777-35?" exfiltrated the CPF.
2. **PERSON_NAME never redacted in the chat.** `redactPii` does not cover person names, so names in
   user messages and in meeting titles (which come raw from the upload) reach the chat
   provider. This is PII covered by ADR 0012 in the worker, but with zero coverage on the chat path — and
   the decision lived only in a code comment, with no ADR.

## Decision

**Structured PII redaction stays in the BFF (fast, no network hop) and now also covers
the RAG query; PERSON_NAME coverage in the chat will be delivered by routing the chat texts through the
worker's `PIIShield` — not by duplicating the name list in JS.**

1. **RAG query redacted before the embedding** (delivered in this ADR): `route.ts` applies
   `redactPii` to the user's last message before passing it to `buildWorkspaceContext` →
   `/meetings/search`. The `[[CPF_1]]` placeholder degrades semantic similarity only slightly (one does not search
   by CPF number). Recommended defense in depth (follow-up): also redact `q` in the
   `MeetingsController.search`/`EmbeddingService` on the Java side.
2. **PERSON_NAME in the chat = routing through the worker** (declared follow-up): instead of porting the
   `_BR_TOP_NAMES` list (~270 names) to JS — exactly the manual mirroring that already caused the structured
   bypass (orphan fix `b0bad1d`) —, the BFF will call an internal worker endpoint
   (`POST /redact`, authenticated by `X-Internal-Token`) that reuses the complete `PIIShield` (with the
   accent-fold restored and the NER backstop once PR #289 lands). SOFT fallback to the
   local `redactPii` on any failure, so the chat is never taken down.

## Accepted residue (until the follow-up)

Until routing through the worker lands, **PERSON_NAME in chat messages and in meeting
titles still reaches the chat provider**. This is an **explicit and documented** residual risk (not a
hidden comment). Current mitigators: the structured PII with the highest LGPD risk (documents,
contact, card) is covered in both providers (chat and embeddings); the system prompt instructs the
model not to expose PII. This ADR triggers trigger #3 of ADR 0012 (a bug report of a false negative
on a name causing a leak) — the NER backstop (spaCy) already proven in #289 is the rescue target.

## Alternatives Considered

- **Porting `_BR_TOP_NAMES` + prefixes to `redact.ts`:** rejected as a definitive solution —
  it duplicates the source of truth and reintroduces the manual mirroring anti-pattern (the structured bypass
  itself was born from a misaligned port). Acceptable only as a stopgap if the worker routing
  is delayed.
- **A redaction router as a separate microservice:** overkill; the worker already has the shield and the
  internal token.
- **Not redacting the RAG query (accepting the structured leak):** rejected — it violates ADR 0012 and
  doubles the external surface (the embeddings provider ≠ the chat provider).

## Consequences

- **Positive:** it closes the structured leak to the embeddings provider (P0); it gives the chat a
  single source of name redaction (the worker) instead of fragile mirroring; the residual risk becomes
  traceable (ADR + follow-up), not implicit.
- **Negative / debts:** routing through the worker adds a network hop per chat turn
  (mitigated by batching + SOFT fallback + cache); until it lands, PERSON_NAME in the chat is accepted
  residue. The worker's `/redact` endpoint needs internal auth and a contract test.

## History

| Date | Decider | Change |
|---|---|---|
| 2026-07-06 | sys0xFF + Claude | ADR created and accepted. RAG query fix delivered; PERSON_NAME in the chat via the worker declared as a follow-up. |
