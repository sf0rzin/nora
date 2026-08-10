# PII shield — the measurement, before and after

|                      | before | after  |          |
|----------------------|--------|--------|----------|
| leak rate            | 21.30% | 11.57% | 943 -> 512 of 4427 |
| false-redaction rate |  5.26% |  5.07% | 109 -> 105 of 2071 |

Both numbers come from `python -m tests.pii_corpus.harness` in `services/nlp-worker`. The
before column is `main` at `27dc6cc` with no change to `pii_shield.py`, and it was committed
before the fix so the fix had a number to move rather than an argument to win. The after column
is the same corpus with `_split_on_allow_list` in place; what changed, shape by shape, is at the
bottom of this file.

## Before

```
PII SHIELD CORPUS
4482 cases

leak rate             :  21.30%  (943 / 4427 cases)  of which documented gaps: 3
false-redaction rate  :   5.26%  (109 / 2071 cases)  of which documented gaps: 5

shape                                 leak       false redaction
adv_caps                       1/3     33.3%         0/0      0.0%
adv_company_before             1/2     50.0%         0/2      0.0%
adv_edge                       0/4      0.0%         0/1      0.0%
adv_false_redaction            0/0      0.0%         2/5     40.0%
adv_lowercase                  2/2    100.0%         0/1      0.0%
adv_mixed                      0/2      0.0%         0/2      0.0%
adv_name_is_product            1/2     50.0%         0/1      0.0%
adv_product_after              0/2      0.0%         0/2      0.0%
adv_product_before             4/5     80.0%         0/4      0.0%
adv_product_between            1/2     50.0%         0/2      0.0%
adv_two_people                 1/3     33.3%         0/1      0.0%
allcaps                      100/400   25.0%         0/0      0.0%
allcaps_product_before       400/400  100.0%         0/400    0.0%
at_end                         0/400    0.0%         0/0      0.0%
bare                           0/400    0.0%         0/0      0.0%
company_alone                  0/0      0.0%         3/4     75.0%
company_before                25/400    6.2%       100/400   25.0%
product_after                  0/400    0.0%         0/400    0.0%
product_alone                  0/0      0.0%         0/36     0.0%
product_before               100/400   25.0%         0/400    0.0%
product_between              300/400   75.0%         0/400    0.0%
role_phrase                    0/0      0.0%         4/10    40.0%
sentence                       0/400    0.0%         0/0      0.0%
speaker_label                  7/400    1.8%         0/0      0.0%
two_people_adjacent            0/400    0.0%         0/0      0.0%
```

## After

```
leak rate             :  11.57%  (512 / 4427 cases)  of which documented gaps: 5
false-redaction rate  :   5.07%  (105 / 2071 cases)  of which documented gaps: 5

shape                                 leak       false redaction
allcaps                      100/400   25.0%         0/0      0.0%     unchanged
allcaps_product_before       100/400   25.0%         0/400    0.0%     was 400/400
company_alone                  0/0      0.0%         0/4      0.0%     was 3/4
company_before                 0/400    0.0%       100/400   25.0%     leak was 25/400
product_before                 0/400    0.0%         0/400    0.0%     was 100/400
product_between              300/400   75.0%         0/400    0.0%     unchanged
role_phrase                    0/0      0.0%         3/10    30.0%     was 4/10
speaker_label                  7/400    1.8%         0/0      0.0%     unchanged
```

Every other shape was 0/400 before and is 0/400 after.

## What moved

**`allcaps_product_before`, 400 -> 100.** `_caps_name_spans` splits the run on allow-listed
terms instead of discarding it. The residual 100 is the off-list/off-list quadrant, which is the
same 100 `allcaps` fails without any product in the string: an all-caps run with neither end on
a name list is admitted only as a speaker label. That gap is unchanged and deliberate.

**`product_before`, 100 -> 0**, and **`company_before` leak 25 -> 0.** A stretch beside an
allow-listed term is judged as it would be judged standing alone, so `Jira Sidnei Marchetti` now
matches `Sidnei Marchetti`.

**`company_alone`, 3 -> 0**, and **`role_phrase`, 4 -> 3.** Two false redactions the leak fix
would otherwise have made worse, closed by two tail rules: a run ending in a corporate suffix is
a trading name, and a run opening on a job title and ending in ordinary vocabulary is a role.
`Northwind Software Solutions renovou o contrato` had been returning as a bare placeholder.

## What did not move, and why

**`product_between`, 300 of 400.** A product between the halves of a name leaves two runs of one
token each, and a lone token on neither name list is refused by `_is_a_name_on_its_own` -- the
backstop that keeps `O Brasil` and `A Nota` from becoming people. The given name redacts, the
surname does not. This is finding 5b's single-token limitation reached by a different route, not
finding 5a, and closing it means changing what a lone Title Case token is worth. That is the one
change in this module that can make the shield materially worse, and it is not this PR.

**`company_before` false redaction, 100 of 400.** `Northwind Andre Teixeira confirmou a
renovacao` comes back as `[[PERSON_NAME_1]] confirmou a renovacao` -- the company swallowed into
the person's placeholder. Present before this change and unaffected by it: `Northwind` is on no
list, and three Title Case tokens are the shape of a full name. `TotalSys` and `OmniBusiness`
escape only because an inner capital means they never match `_TITLE_WORD` at all.

**`speaker_label`, 7 of 400.** `DIRCEU PANIZZON: fechamos o escopo.` is not claimed, because
`_VERB_TAIL_RE` reads the `-eu` ending of `DIRCEU` as a third-person preterite and Pattern 6
refuses any label containing what looks like a verb. Seven of the sixty off-list given names in
the corpus end that way.

## What the shapes say

The reading below is of the *before* column, which is what the fix was designed against.

**`allcaps_product_before` — 400 of 400.** `SAP ANA MARTINS aprovou o escopo.` emits both name
tokens. `ANA MARTINS aprovou o escopo.` redacts them. The all-caps path discards the entire run
when any token is on the negative list, so an allow-listed term three characters long suppresses
the redaction of everything beside it. This is finding 5a, mechanism A, and it is total: every
product, every name, on-list and off-list alike.

**`product_before` — 100 of 400, all in the off-list/off-list quadrant.** `Jira Sidnei Marchetti
apresentou o plano.` leaks; `Sidnei Marchetti apresentou o plano.` does not. One negative token
anywhere in a candidate demotes *every* run in it off the trusted path and onto one that demands
a listed given name or a listed surname. A name on neither list -- which is most names, the lists
holding 271 and 101 entries -- then falls through. Mechanism B.

**`company_before` — 25 leaks and 100 false redactions, and they are different companies.** The
25 are `Acme`, which is on the negative list, suppressing the name after it (mechanism B again).
The 100 are `Northwind`, which is not on any list and is swallowed *into* the person's
placeholder: `Northwind Andre Teixeira confirmou a renovacao.` comes back as
`[[PERSON_NAME_1]] confirmou a renovacao.` and the customer's name is gone from the summary.
`TotalSys` and `OmniBusiness` escape both fates for an accidental reason -- an inner capital
means they do not match `_TITLE_WORD` at all.

**`product_between` — 300 of 400.** A product between the halves of a name leaves two runs of one
token each, and a lone token off both lists is refused. The given name redacts, the surname does
not. Same single-token limitation as finding 5b, reached by a different route.

**`allcaps` — 100 of 400, the off-list/off-list quadrant.** An all-caps run with neither end on a
name list is admitted only as a speaker label; in running prose it is not distinguishable from an
acronym string. Deliberate, and unchanged by this work.

**`role_phrase` and `adv_false_redaction`.** `Customer Success`, `Machine Learning` and `Pull
Request` come back as people, and `Segunda-feira` is spliced into `[[PERSON_NAME_1]]-feira`.
Cosmetic next to a leak, and recorded here so that a change that fixes them by widening the
negative list can be measured against the leaks that widening causes.

## How the two rates are counted

- **leak** — a case declaring `must_vanish` where any of those tokens is still present in the
  output, with the placeholders removed first so a placeholder's own words cannot be mistaken for
  the text they replaced. Denominator: every case carrying a person's name.
- **false redaction** — a case declaring `must_survive` where any of those tokens is missing,
  *or* a case with nobody in it that produced a `[[PERSON_NAME_n]]` anyway. Denominator: every
  case carrying something that has to survive.

A case can count towards both, and most of the interesting ones do: `SAP CARLOS SILVA` has to
lose `CARLOS SILVA` and keep `SAP`. That is the point. Driving the leak rate to zero by redacting
every capitalised token drives the second number through the roof, and the pair is what makes
that visible.
