# PII shield — the measurement, before and after

|                      | before  | after  |                      |
|----------------------|---------|--------|----------------------|
| leak rate            | 16.76%  | 9.10%  | 943 -> 512 of 5627   |
| false-redaction rate | 24.83%  | 9.60%  | 1309 -> 506 of 5271  |

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
false-redaction rate  :   9.60%  (506 / 5271 cases)

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

## The false-positive pool, and the first version of it that was worthless

`product_between` above is 300 of 400 for one reason: a product between the halves of a name
leaves two runs of one token each, and a lone token on neither name list is refused by
`_is_a_name_on_its_own`. Closing 5b means loosening that refusal, and the note above already says
it is "the one change in this module that can make the shield materially worse." So the price
list goes in before the fix.

**The first attempt at that price list priced nothing.** It was 81 cases, each a lone Title Case
noun — weekdays, months, business areas — and the claim was that they made a loosening expensive.
The claim was checked the only way it can be, by applying the largest loosening the rule admits:

```
_is_a_name_on_its_own = lambda value: True

cases in the pool                       81
cases whose output changed               0
```

Zero. `_is_a_name_on_its_own` is reachable only through a pattern needing two adjacent
`_TITLE_WORD` tokens, and a lone noun in a sentence never enters that path. Every one of the 81
passed because it could not fail.

The same measurement on the whole corpus is the part worth keeping:

```
                       today          maximally loosened
leak rate              9.0990%   ->   3.7498%    (512 -> 211)
false-redaction rate   9.4918%   ->   9.4918%    (508 -> 508, identical)
```

A change closing 301 leaks registered **no cost at all** across 5,763 cases, while hand-written
realistic strings broke under it. That is the same structural blindness recorded above from the
opposite direction, and `test_the_false_positive_pool_is_not_inert` exists so it cannot recur:
it applies that loosening and fails unless the pool notices.

## The pool as it now stands

| shape | cases | what it is | today |
|---|---|---|---|
| `fp_article` | 10 | `O Brasil`, `A Nota`, `O Protheus`, `A TOTVS`, `O RM` | 0 fail |
| `fp_weekday` | 19 | seven weekdays, bare and in a sentence; `-feira` for the five that take it | 0 fail |
| `fp_month` | 24 | the twelve months | 2 fail |
| `fp_department` | 24 | twelve business areas | 0 fail |
| `fp_accent` | 3 | `Março`, `Terça`, `Sábado` — real accents, so folding is exercised | 1 fails |
| `fp_preposition` | 49 | `Na Sexta`, `Em Janeiro`, … — seven prepositions × seven nouns | **49 fail** |
| `fp_split_flank` | 13 | an allow-listed term between two ordinary words | 0 fail |

Measured on `main` at `964ca22` with `pii_shield.py` untouched:

```
leak rate             :   9.10%  (512 / 5628)   was 9.10% (512 / 5627)
false-redaction rate  :  10.31%  (558 / 5413)   was 9.60% (506 / 5271)
```

**The false-redaction rate rose because the measurement got less wrong, not because the shield
got worse.** `pii_shield.py` is byte-identical. 49 of the 52 new failures are `fp_preposition`,
which fails today and always has.

### `fp_preposition` — a live defect the corpus could not see

`Na Sexta o time fecha o escopo.` comes back `[[PERSON_NAME_1]] o time fecha o escopo.` A
capitalised preposition and a capitalised noun are two `_TITLE_WORD` tokens, which is exactly the
shape the sequence pattern trusts.

Swept at 14 prepositions × 28 nouns: **196 of 392 wrong**, split perfectly by list membership —
`Na No Nas Nos Pela Pelo Em` fail with every noun, `Da Do Das Dos De` pass because they are on
`_NAME_CONNECTIVES` (they occur inside real names), and `A O` pass because one letter is not a
`_TITLE_WORD`. The corpus carries a 7 × 7 sample so one defect does not swamp the rate.

### `fp_split_flank` — the group that actually prices 5b

Ten strings of the form `<Ordinary> <allow-listed> <Ordinary>`, plus the conjunction path and the
`product_between` mirror. All pass today. Under the maximal loosening, five of the ten break:

```
BREAKS   Central Oracle Cloud            `Central Cloud` reads as a name
BREAKS   Licenca Salesforce Enterprise   `Licenca Enterprise`
BREAKS   Servidor Postgres Homologacao
BREAKS   Painel Jira Executivo
BREAKS   Relatorio Datasul Gerencial
holds    Portal SAP Financeiro, Modulo Protheus Fiscal, Base Postgres Producao,
         Ambiente Kubernetes Producao, Integracao Fluig Contabil
```

`Relatorio Datasul Gerencial` held in one sentence frame and breaks in another, so the counts
above are stated for the frame the builder emits and were re-derived rather than carried over.

### `Marco`, and the accented spellings

`_fold` strips accents before every list lookup, so the month folds onto `marco`, which is on
`_BR_TOP_NAMES`. The earlier version of this section asserted that the cedilla spelling behaves
identically — in a corpus that contained no accented character anywhere. `fp_accent` now carries
`Março`, `Terça` and `Sábado` with their real accents, and `Março` does fail, so the equivalence
is measured instead of assumed.

Recorded as a `KNOWN_GAP`. Dropping `marco` from `_BR_TOP_NAMES` buys a month and sells one of the
commonest given names in pt-BR; the signal that separates them is the temporal preposition in
front, which is its own change.
