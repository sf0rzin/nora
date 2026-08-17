# 0043 — ADDRESS becomes a redacted type, and the PII corpus gate becomes a decreasing target

- Status: accepted
- Date: 2026-08-17
- Related: ADR 0012 (the PERSON_NAME strategy and the type list this extends), ADR 0033 (the BFF
  mirror this deliberately does not extend), ADR 0040 (which scopes the non-negotiable to text and
  analysis, and whose Consequences list ADDRESS as still-owed debt), ADR 0018 (coverage gates).
  **This ADR supersedes none of them.** ADR 0012's controls are unchanged and one type is added to
  what they cover.

## Context

Two problems, and they are the same problem seen from two sides.

**The type nobody emitted.** `ADDRESS` has been in `PiiType` and in
`packages/shared-contracts/pii-types.json` since the contract was written, described there as
"listed for forward compatibility; the current worker implementation does NOT emit it". A client
reading the contract sees eight types and gets seven. ADR 0040 §Consequences records the debt and
says it is unaffected by that ADR and still owed.

Implementing it is not a matter of adding a regex, and the reason is worth stating because it is
what made the debt survive four rounds of work on this module. The words that open a Brazilian
address — `Rua`, `Avenida`, `Rodovia`, `Alameda`, `Praca`, `Estrada`, `Travessa` — are on the
shield's `_COMMON_PHRASE_HEADS`, whose comment explains that roughly a third of the surname list
doubles as a place name, so recognising the word that *opens* a place name is what keeps
`Bairro SANTA CRUZ` and `Vila Prado` from being redacted as people. Membership on that list means
the word is stripped; the list's only possible effect anywhere in the module is to **suppress** a
redaction. Deleting those entries to make room for ADDRESS reopens exactly the false positive they
were added for.

**The ceiling that could not improve anything.** `services/nlp-worker/tests/test_pii_corpus.py`
gates two rates over a corpus of ~5,900 cases, and it did it with two constants:
`MAX_LEAK_RATE = 533 / 5652` and `MAX_FALSE_REDACTION_RATE = 558 / 5470`. Both were the measured
values, pinned so nothing could get worse.

That is a good regression guard and it is not a target. A ceiling forbids getting worse and is
entirely satisfied by standing still; worse, it banks every improvement as slack, so a change that
halves the leak rate and leaves the constant alone hands the next change permission to undo it. To
anybody who does not open the file, it also reads as a statement that a 9.43% leak rate is
acceptable. It never was — it was what the shield did, written down honestly.

ADR 0040 raises what is at stake. Once ADR 0039's cloud transcription lands, raw audio leaves the
machine before any redaction exists, so the shield stops being one layer among several and becomes
**the only gate before analysis**. A single gate that measures itself at 9.43% and pins that number
as its maximum is a promise the repository's own tests contradict.

## Decision

### 1. ADDRESS is a deterministic recogniser, and it runs before the person-name heuristics

`_ADDRESS_RE` matches a street-type opener, followed by one to four capitalised name parts (pt-BR
particles allowed between them), an optional house number and an optional complement. It lives in
`_BASIC_PATTERNS` alongside EMAIL, CPF, CNPJ, CREDIT_CARD and PHONE, which means it runs in the
deterministic stage, before every person-name heuristic, and claims the whole stretch.

**That order is the reconciliation between the two lists, and it is the decision.** The street-type
words stay on `_COMMON_PHRASE_HEADS` and go on doing their own job for everything the recogniser
declines. A reader who meets the two lists without being told which runs first will conclude they
contradict each other, so the order is stated in the module docstring, in the block above the word
list, and in a test that fails if the entries are removed.

The admission rule is one condition: the token after the street type must be **capitalised**. That
is what separates `Rua das Flores` from `Rua sem saida`, and it is priced by thirteen
`fp_address` cases that open on a street-type word and are not addresses.

### 2. ADDRESS is not NER, and the boundary is published

An address whose name is lower-cased (`rua sem saida`), purely numeric (`Rua 25`), or written
without a street-type opener is **not covered**. `Bairro`, `Vila`, `Jardim`, `Parque` and `Centro`
are deliberately not street types: they open a neighbourhood, and claiming them would be the same
over-redaction the head list exists to prevent, wearing a different type.

This limit goes in `x-notes.ADDRESS` in the shared contract rather than in an ADR nobody reads
from a client. A type that is emitted "sometimes" without saying when is worse than one that is
never emitted, because the first invites reliance.

### 3. The BFF mirror does not gain ADDRESS

`apps/web/src/lib/pii/redact.ts` keeps the structured patterns only. Every pattern there is
validated (check digit, Luhn) or structurally unmistakable and its false-positive rate is near
zero; the ADDRESS recogniser is a heuristic with a measured cost, and the chat path has no corpus
to price it on. Over-redacting a user's own sentence back at them in a chat window is a worse
outcome than in an analysis they never see.

The consequence is declared rather than left to be discovered: **a street address typed into the
chat reaches the chat and embedding providers in the clear.** It is residue of the same kind ADR
0033 declares for PERSON_NAME on that path, and it closes the same way — by routing the chat path
through the worker's shield, not by copying a heuristic into a second implementation that will
drift. The header of `redact.ts` says so at the point where somebody would otherwise add it.

### 4. Each rate carries a ceiling, a ratchet and a dated goal

| Constant | What it asserts | Failure message |
|---|---|---|
| `MAX_LEAK_RATE`, `MAX_FALSE_REDACTION_RATE` | nothing got worse | `REGRESSION` |
| `RATCHET_SLACK_CASES` | the ceiling sits at most five cases above the measurement | `CEILING NOT RE-TIGHTENED` |
| `LEAK_RATE_GOAL`, `FALSE_REDACTION_RATE_GOAL`, `RATE_GOALS_DUE` | the number being worked towards, and when it is owed | `TARGET NOT MET BY ITS DEADLINE` |

The **ratchet** is what makes the ceiling decrease. Improve the shield and leave the constant
alone and the build fails, telling you to lower it and to record whether the rate moved because
the code improved or because the corpus grew — a distinction this file already spends several
paragraphs on and previously enforced by nothing.

The **goal** is dated on purpose, and it can turn CI red on a day nobody pushed. That is the
mechanism working: a goal with no deadline is a wish. The two legitimate responses are to do the
named work or to move the date in a commit that says what changed about the judgement.

Committed to: **a leak rate of 1.0% and a false-redaction rate of 4.0% by 2027-06-30.** Each names
exactly one shape, so the goal is a piece of work rather than an aspiration:

- Leak — `product_between` in the off-list/off-list quadrant. A product name between the halves of
  a name where neither half is on a list, 100 cases and about 1.8 points.
- False redaction — `title_then_name_then_label`. A job title, a name, and an ordinary word on the
  next line swallowed together because `_NAME_SEQUENCE_RE`'s `\s+` matches a newline. 400 cases and
  7.3 points on its own.

### 5. The rates are published, and both or neither

Unchanged in substance and restated because it is the property everything above rests on: the leak
rate is never stated without the false-redaction rate beside it. Driving the first to zero by
redacting every capitalised token drives the second through the roof, and a single number cannot
see the difference between a fix and that.

## Consequences

**Positive**

- The published contract stops overstating itself by one type, and the type it now delivers comes
  with its own boundary written down.
- Measured, on a corpus held byte-identical across both measurements so the difference is
  attributable to the shield alone: leak 9.60% → 2.12% (544 → 120 of 5,664), false redaction
  10.13% → 10.08% (558 → 555 of 5,507). **Neither rate rose.** The tables are in
  `services/nlp-worker/tests/pii_corpus/BASELINE.md`.
- Three shapes the corpus catalogued as broken are closed: the genitive and phrase-head leak
  (2,912 of 2,916 measured combinations before), a product name between the halves of a name (300
  of 400), and an all-caps pair in running prose (200 of 400 plus seven speaker labels).
- The gate can now express improvement. Before this, the only thing a better shield changed was a
  number nobody was obliged to update.

**Negative / debts**

- **CI can fail on a date.** This is deliberate and it is still a cost: a maintainer who has not
  read this ADR will meet a red build with no diff behind it. The failure message names the shape
  and both responses, which is the mitigation available.
- **The residual of the all-caps rule is a new class of false redaction.** A two-word all-caps
  phrase whose both words are on no list is claimed as a person —
  `SALES ENABLEMENT assumiu a conta.` It is the same residual the Title Case path has always had
  (`role_phrase` recorded `Customer Success` as exactly this), and those three phrases are on the
  negative list now, which closes the commonest instance and not the class. Recorded as a corpus
  case rather than as a sentence here, so it is counted in the published rate.
- **`product_between`'s off-list/off-list quadrant stays open**, 100 cases, deliberately. The same
  string with ordinary words in it (`A Central Oracle Cloud entrou na pauta`) is a server, and no
  lexical signal separates them; the maximum loosening buys those 100 leaks for 34 false
  redactions. That trade is what §4's goal dates rather than takes blind.
- **`_PERSON_NAME_NEGATIVE_LIST` grew by six English function words.** Any entry on that list can
  only suppress a redaction, so the list is a standing liability; these six were added because the
  reason previously recorded for refusing them (finding 5a was live) no longer holds, and because
  the all-caps rule made their absence newly expensive.
- **ADR 0040 §Consequences now carries a statement that has been overtaken** — "ADDRESS ... debt is
  unaffected by this ADR and still owed". It was true when written and accepted ADRs are immutable,
  so it stays. This is the successor that pays it.

## Alternatives Considered

1. **Take the street-type words off `_COMMON_PHRASE_HEADS` so an ADDRESS regex can have them.**
   The obvious implementation and the one that trades a defect for a defect: those entries are what
   keeps `Bairro SANTA CRUZ` and `Vila Prado` from being redacted as people, and the list's only
   possible effect is to suppress a redaction. Rejected in favour of ordering the two rules, which
   costs a paragraph of explanation and no behaviour.
2. **Implement ADDRESS as NER, with a model.** It would cover the shapes §2 excludes. Rejected on
   the same grounds ADR 0012 rejected it for PERSON_NAME: a model in the redaction path is a
   dependency the last gate before an external provider cannot afford, it is not deterministic
   across retries, and the repository has one maintainer. The deterministic recogniser covers the
   form a transcript actually carries and says plainly what it does not.
3. **Mirror ADDRESS in the BFF for symmetry.** Rejected in §3. The symmetry is superficial: the two
   files differ in what they may cost, not only in what they cover.
4. **Keep the two ceilings and simply lower them.** This is what a normal fix would do, and it
   leaves the structural problem exactly where it was — the next improvement has no obligation to
   move them either. Rejected, and it is the alternative this ADR exists to reject.
5. **A goal with no deadline** ("we aim for 1%"). Rejected: the repository's history is a long
   argument that an unenforced intention decays into a false claim, and this file would have been
   the fifth example.
6. **Make the goal an `xfail(strict=True)` until it is met.** Considered because it is the
   idiomatic pytest shape. Rejected: a strict xfail turns "not there yet" into a passing build with
   no visible countdown, which is a ceiling with a longer name. The dated assertion is the version
   that eventually insists.
7. **Close `product_between`'s last quadrant by loosening the single-token rule outright.** It
   reaches roughly 0.4% leak in one change. Rejected here because the corpus prices it at 34 false
   redactions and §5's rule is that the two numbers are judged together; it is exactly the shape of
   trade this gate exists to make visible rather than to make quietly.

## History

| Date | Decider | Change |
|---|---|---|
| 2026-08-17 | sys0xFF | Created and accepted. Implements ADDRESS as a deterministic recogniser ordered before the person-name heuristics, keeps the street-type words on the ordinary-vocabulary list, declines to mirror ADDRESS in the BFF and declares the resulting residue, and replaces the corpus gate's two ceilings with a ceiling, a ratchet and a dated goal per rate |
