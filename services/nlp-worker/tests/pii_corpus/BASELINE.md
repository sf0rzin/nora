# PII shield — the measurement before the fix

Produced by `python -m tests.pii_corpus.harness` from `services/nlp-worker`, against `main` at
`27dc6cc`, with no change to `pii_shield.py`. It is committed before the fix so the fix has a
number to move rather than an argument to win.

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

## What the shapes say

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
