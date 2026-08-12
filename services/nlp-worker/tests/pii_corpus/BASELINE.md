# PII shield — the measurement, before and after

|                      | before  | after  |                      |
|----------------------|---------|--------|----------------------|
| leak rate            | 16.76%  | 9.10%  | 943 -> 512 of 5627   |
| false-redaction rate | 24.83%  | 9.58%  | 1309 -> 505 of 5271  |

Both numbers come from `python -m tests.pii_corpus.harness` in `services/nlp-worker`. The before
column is `main` at `27dc6cc` with no change to `pii_shield.py`; the after column is the same
corpus with `_split_on_allow_list` in place. Both were re-measured on the corpus as it stands,
so the two columns are comparable — see "The corpus grew once" at the bottom for why that
mattered.

## Before, by shape

```
leak rate             :  16.76%  (943 / 5627 cases)
false-redaction rate  :  24.83%  (1309 / 5271 cases)

shape                                 leak       false redaction
allcaps                      100/400   25.0%         0/400    0.0%
allcaps_product_before       400/400  100.0%         0/400    0.0%
at_end                         0/400    0.0%         0/400    0.0%
bare                           0/400    0.0%         0/0      0.0%
company_alone                  0/0      0.0%         3/4     75.0%
company_before                25/400    6.2%       100/400   25.0%
name_then_company_suffix       0/400    0.0%       400/400  100.0%
product_after                  0/400    0.0%         0/400    0.0%
product_alone                  0/0      0.0%         0/36     0.0%
product_before               100/400   25.0%         0/400    0.0%
product_between              300/400   75.0%         0/400    0.0%
role_phrase                    0/0      0.0%         4/10    40.0%
sentence                       0/400    0.0%         0/400    0.0%
signature_block                0/400    0.0%       400/400  100.0%
speaker_label                  7/400    1.8%         0/400    0.0%
title_then_name_then_label     0/400    0.0%       400/400  100.0%
```

## After, by shape

```
leak rate             :   9.10%  (512 / 5627 cases)
false-redaction rate  :   9.58%  (505 / 5271 cases)

allcaps                      100/400   25.0%         0/400    0.0%    unchanged
allcaps_product_before       100/400   25.0%         0/400    0.0%    was 400/400
company_alone                  0/0      0.0%         0/4      0.0%    was 3/4
company_before                 0/400    0.0%       100/400   25.0%    leak was 25/400
name_then_company_suffix       0/400    0.0%         0/400    0.0%    FR was 400/400
product_before                 0/400    0.0%         0/400    0.0%    was 100/400
product_between              300/400   75.0%         0/400    0.0%    unchanged
role_phrase                    0/0      0.0%         3/10    30.0%    was 4/10
signature_block                0/400    0.0%         0/400    0.0%    FR was 400/400
speaker_label                  7/400    1.8%         0/400    0.0%    unchanged
title_then_name_then_label     0/400    0.0%       400/400  100.0%    unchanged
```

Every other shape was 0 before and is 0 after.

## What moved

**`allcaps_product_before`, 400 -> 100.** `_caps_name_spans` splits the run on allow-listed terms
instead of discarding it. The residual 100 is the off-list/off-list quadrant, which is the same
100 `allcaps` fails with no product in the string at all: an all-caps run with neither end on a
name list is admitted only as a speaker label. That gap is unchanged and deliberate.

**`product_before` 100 -> 0**, and **`company_before` leak 25 -> 0.** A stretch beside an
allow-listed term is judged as it would be standing alone, so `Jira Sidnei Marchetti` now matches
`Sidnei Marchetti`.

**`name_then_company_suffix` and `signature_block`, 400 -> 0 false redactions each.** These are
the shapes where `main` swallowed a company word into the person's placeholder:
`Wanderleia Kranz Sistemas confirmou o prazo` came back with `Sistemas` gone. Subtracting the
corporate suffix from the run — rather than refusing the run — keeps the word and redacts the
person.

**`company_alone` 3 -> 0**, and **`role_phrase` 4 -> 3**, from the same two tail rules.

## What did not move, and why

**`product_between`, 300 of 400.** A product between the halves of a name leaves two runs of one
token each, and a lone token on neither name list is refused by `_is_a_name_on_its_own` — the
backstop that keeps `O Brasil` and `A Nota` from becoming people. The given name redacts, the
surname does not. This is finding 5b's single-token limitation reached by a different route, and
closing it means changing what a lone Title Case token is worth. That is the one change in this
module that can make the shield materially worse, and it is not this PR.

**`company_before` false redaction, 100 of 400.** `Northwind Andre Teixeira confirmou a
renovacao` comes back as `[[PERSON_NAME_1]] confirmou a renovacao` — the company swallowed into
the person's placeholder. `Northwind` is on no list, and three Title Case tokens are the shape of
a full name; there is no lexical signal separating them. `TotalSys` and `OmniBusiness` escape
only because an inner capital means they never match `_TITLE_WORD`.

**`title_then_name_then_label`, 400 of 400 false redactions.** `Coordenador <name>` followed on
the next line by `Relatorio` takes the label into the placeholder with the name. Identical on
`main`; measured here for the first time.

**`speaker_label`, 7 of 400.** `DIRCEU PANIZZON: fechamos o escopo.` is not claimed, because
`_VERB_TAIL_RE` reads the `-eu` ending of `DIRCEU` as a third-person preterite and Pattern 6
refuses any label containing what looks like a verb. Seven of the sixty off-list given names in
the corpus end that way.

## The corpus grew once, after review

It started at 4,482 cases and the first measurement was 21.30% / 5.26% against 11.57% / 5.07%.
Those numbers were real but the corpus was structurally blind in one direction: all eleven
generated shapes put the product or company *before*, *after* or *between* the halves of a name,
and none put an ordinary or corporate word at the **tail of a run opening on a person**.

That is exactly where a rule which *refuses* to redact does its damage, and the first version of
this change added two such rules. The corpus reported zero regressions on all 4,482 cases while
2,300 hand-built cases leaked — a full name in an attendee block, which is most of a set of
minutes. `name_then_company_suffix`, `signature_block` and `title_then_name_then_label` exist
because of that, and both columns above were re-measured with them in place.

The lesson generalises past this PR: **a rule that refuses to redact needs corpus cases that make
the refusal expensive.** Adding one without adding those is how a leak gets measured as an
improvement.

## How the two rates are counted

- **leak** — a case declaring `must_vanish` where any of those tokens is still present in the
  output, with placeholders removed first so a placeholder's own words cannot be mistaken for the
  text they replaced. Denominator: every case carrying a person's name.
- **false redaction** — a case declaring `must_survive` where any of those tokens is missing,
  *or* a case with nobody in it that produced a `[[PERSON_NAME_n]]` anyway. Denominator: every
  case carrying something that has to survive.

A case can count towards both, and most of the interesting ones do: `SAP CARLOS SILVA` has to
lose `CARLOS SILVA` and keep `SAP`. Driving the leak rate to zero by redacting every capitalised
token drives the second number through the roof, and the pair is what makes that visible.

## The single-token false positives, added before finding 5b

`product_between` above is 300 of 400 for one reason: a product between the halves of a name
leaves two runs of one token each, and a lone token on neither name list is refused by
`_is_a_name_on_its_own`. Closing 5b means loosening that refusal, and the note above already says
it is "the one change in this module that can make the shield materially worse."

So the price list was committed first — 81 cases, every one a lone Title Case token that is not a
person, each appearing bare and inside a realistic sentence:

| shape | cases | what it is |
|---|---|---|
| `fp_article` | 12 | `O Brasil`, `A Nota`, `O Protheus`, `A TOTVS`, `O RM`, `O Financeiro` |
| `fp_weekday` | 21 | the seven weekdays, bare, in a sentence, and in the `-feira` form |
| `fp_month` | 24 | the twelve months |
| `fp_department` | 24 | twelve business areas |

Measured on `main` at `964ca22`, with no change to `pii_shield.py`:

```
leak rate             :   9.10%  (512 / 5627)   unchanged — none of these carries a name
false-redaction rate  :   9.49%  (508 / 5352)   was 9.60% (506 / 5271)

fp_article       0/12    0.0%
fp_department    0/24    0.0%
fp_month         2/24    8.3%   <- both are `Marco`
fp_weekday       0/21    0.0%
```

The rate moved *down* because the denominator grew by 81 while the failures grew by 2. That is
not an improvement and is not claimed as one; the ceiling in `test_pii_corpus.py` is rewritten to
the new measured fraction so the gate keeps meaning the same thing.

### What the fixtures found on the way in

**`Marco`, both forms, 2 of 24.** `_fold` strips accents before every list lookup, so the month
folds onto `marco`, which is on `_BR_TOP_NAMES`. Checked on the cedilla form too — `Marco` also
comes back `[[PERSON_NAME_1]]`, so this is not an artefact of the corpus writing months without
accents. Lowercase `marco` survives, which is the deliberate lowercase gap.

Recorded as a `KNOWN_GAP` rather than fixed. The obvious fix — dropping `marco` from
`_BR_TOP_NAMES` — buys a month and sells one of the commonest given names in pt-BR. The signal
that actually separates them is the temporal preposition in front (`em`, `para`, `ate`, `desde`),
which is a different mechanism from anything 5b touches.

**Everything else passes today**, including the `-feira` form, which the older
`adv/false/weekday` gap says fails. Both are true: that case is `Na Segunda-feira o time...`,
sentence-initial, and this one is `A entrega foi remarcada para Segunda-feira.` The position is
what differs, and the pair is more useful than either alone.
