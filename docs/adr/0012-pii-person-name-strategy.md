# 0012 — PII PERSON_NAME: BR regional strategy in the MVP, upgrade to multi-language NER when going international

- Status: accepted
- Date: 2026-05-12

## Context

The NLP worker's PII shield has covered EMAIL, CPF, CNPJ, PHONE, CREDIT_CARD since Sub-phase 1.0. Sub-phase 1.1 (TF-IDF baseline) exposed a serious problem: the top-N term ranking was dominated by **proper names** (Lucas, Marina, Rafael, Camila) from the synthetic transcripts — not by business terms. In production this translates into:

- The response's `baselineTerms` degrades (it shows names instead of products/concepts)
- Logs and auditing expose real people's names (LGPD risk if not redacted before the LLM)
- The LLM receives raw names in the prompt — in some cases this biases extraction (the LLM "remembers" the name in a decision/risk)

Sub-phase 1.3 (Slice P) added PERSON_NAME detection via **three heuristic patterns**:

1. Formal prefixes: `Sr\.|Sra\.|Dr\.|Dra\.|Profa?\.` followed by Title Case
2. Title Case sequence: 2-4 capitalized words in sequence (filtered by a negative list)
3. **Hardcoded list of ~270 common BR names** (Lucas, Marina, Camila, Rafael, Carlos, Ana, João, Maria, etc — variants with and without accents)

Plus a **negative list (~80 terms)** that avoids false positives from products, companies and technical acronyms (TOTVS, Protheus, NORA, SAP, Oracle, Salesforce, etc).

The solution covers the **MVP target market well** (Brazil, TOTVS ecosystem), but has **known limitations**:

- **Does not scale internationally**: Anglo-Saxon (John, Sarah), French (Pierre, Marie), Asian (Hiroshi, Wei) and Spanish (Diego, Pablo) names are not covered by the BR list
- **Costly maintenance per region**: each new market would require expanding the list
- **~80% coverage** for Brazilian tenants — uncommon names or rare variants slip through
- **Pattern 2 (Title Case sequence)** catches some foreign people by luck (any Title Case "Word1 Word2" outside the negative list) — but with predictable false positives (place names, capitalized concepts)

## Decision

**Accept the BR regional strategy as the MVP solution**, with the limitation documented and an explicit upgrade plan.

This decision is a **conscious scoping** choice, not negligence:

- The MVP target market is Brazil (FIAP × TOTVS partnership, Lusophone ecosystem)
- Cost of implementing multi-language NER now vs. real gain = not justified
- The current solution is **sufficient** to demonstrate the PII shield in the FIAP/NEXT 2026 pitch and to onboard the first BR customers
- The limited coverage is **explicitly documented** in tests and in this ADR

### Trigger for the upgrade (not automatic)

Migrate to a multi-language NER solution when **at least one** of the conditions below is true:

1. **The first non-Brazilian tenant** signs a contract or enters a formal pilot
2. **>5% of the transcripts processed in production** come in a language ≠ pt-BR (measured via the `language` metadata in `AnalyzeRequest` + worker telemetry)
3. **A customer bug report** with a false negative on a name causing a leak via baselineTerms or the audit log
4. **An LGPD security audit** flags the hardcoded list as inadequate

## Consequences

**Positive:**

- Implementation time in Sub-phase 1.3 was minimal (~30min agentic), with no external dependency
- Zero additional runtime overhead (pure regex, ~ms)
- No heavy dependency (Presidio ~30MB, spaCy ~100MB) in the worker container
- Deterministic tests (a fixed list allows exact assertions)
- The solution is honest about the MVP scope

**Negative / debts:**

- **Does not scale** to internationalization without a refactor
- **Implicit maintenance**: if we discover a common BR name that is not covered, the list needs a manual update
- The negative list grows as new products/companies appear in transcripts
- A PII audit in prod **will need to flag** explicitly that coverage is regional, not universal

## Alternatives Considered

### (a) Expanded global list (~5000+ multicultural names)

Rejected:
- It becomes an impossible list to maintain (top 1000 names for each of N regions)
- Byte bloat in the code without solving the systemic problem (some name will always be missing)
- False positives grow proportionally

### (b) Pure heuristics (Title Case regex without a list)

Rejected:
- It is exactly pattern 2 that we already have — on its own it is not enough
- False positives explode (cities, capitalized concepts, products without a known prefix)
- The pattern becomes indistinguishable from noise

### (c) Microsoft Presidio (ML-based NER)

Deferred — **the main option for a future upgrade** when the trigger conditions are met:
- Robust multi-language (supports EN, PT, ES, FR natively)
- ML-based: detects names by context, not by list
- Cost: heavy dependency ~30MB + model warmup load (~2s at startup) + ~50ms per analysis
- Mature (maintained by Microsoft)
- Can coexist with the BR list (Presidio detects, the BR list confirms with high confidence)

### (d) spaCy with pre-trained NER models

A secondary alternative to Presidio:
- `pt_core_news_lg` (~500MB) or `xx_ent_wiki_sm` (~10MB, multilingual)
- Mature, oss, wide community
- Cost: mid-weight dependency + warmup similar to Presidio
- Less focused on PII specifically (Presidio is PII-first; spaCy is generic NER)

### (e) 2-stage LLM (an extra call dedicated to extracting names)

Rejected for now:
- The cost per analysis doubles (~$0.04 → ~$0.08 with gpt-4o-mini)
- Additional latency (~1-2s per analysis)
- Natively multi-language, but the ROI does not beat the cost right now

## Upgrade Plan

When the trigger condition is met:

1. Evaluate Presidio vs spaCy vs 2-stage LLM with a real benchmark (latency, recall, precision on 50 real transcripts)
2. Create a successor ADR (e.g., `0XXX-pii-person-name-ner-upgrade.md`) with the final technical decision
3. Implement the replacement keeping the BR list as a high-confidence layer (combo: NER detects → list confirms)
4. Strategic migration: feature flag `PII_PERSON_DETECTOR=heuristic|presidio|spacy`, gradual rollout
5. Keep the current deterministic tests as a regression suite

## Accompanying Rules

- The `_BR_TOP_NAMES` and `_PERSON_NAME_NEGATIVE_LIST` lists in `services/nlp-worker/src/nora_nlp/services/pii_shield.py` are **product property** — any addition goes through code review (by someone with BR/PT context) to avoid regional bias
- Pull requests that add names to the BR list must include a justification in the commit message (e.g., "found in a transcript from customer Y, pattern across N=120 other conversations")
- Production logs that detect a name via pattern 2 (Title Case without a list) must emit the metric `pii.person.fallback_heuristic` to monitor the real gap between the list and the heuristic

## History

| Date | Decider | Change |
|---|---|---|
| 2026-05-12 | Stratfy | ADR created and accepted |
