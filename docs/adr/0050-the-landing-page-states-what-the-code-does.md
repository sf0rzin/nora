# 0050 — The public landing page states what the code does, and marks what it does not

- Status: accepted
- Date: 2026-08-23
- Related: ADR 0038 (DEC-04 froze the page and left the decision open), ADR 0040 (records the same
  freeze as the one consequence it could not resolve), ADR 0046 (closed the story limbo and named
  the landing as the larger honesty gap still standing), ADR 0039 (what transcription actually is),
  ADR 0029 (what retention actually is), ADR 0031 (what the integrations actually are), ADR 0041
  (which direction MCP actually runs in)
- Supersedes: nothing. It resolves a freeze, and a freeze is not a decision about content

## Context

Three accepted ADRs recorded the same unresolved item, in almost the same words. ADR 0038
§Consequences: *"the landing page carries claims that this realignment does not fix. DEC-04 froze
the landing pending a separate decision, and the specific claims are catalogued in issue #456."*
ADR 0040 §Consequences says the page carries the strongest version of the PII claim and that its own
decision makes that worse rather than better. ADR 0046 §Consequences says closing the story limbo
does not close the landing, *"which is a separate and larger honesty gap"*.

The freeze was the right call at the time and it was explicitly temporary. What it produced, left
running, was a repository that had spent three months removing overstatement from every internal
document while the one page a visitor actually reads kept every overstatement it ever had. The
asymmetry is the problem: an audit trail nobody outside reads is not what a portfolio artefact is
judged on.

What the page claimed, measured against the tree rather than remembered:

| Claim on the page | What the code does |
|---|---|
| Six customer logos under "Usado por times em:", in a `fake-logo` CSS class | There are no customers. ADR 0038 §1 declares there will be none |
| "Continuar com Microsoft" / "Cadastrar com Microsoft" buttons; "SSO · SAML" in the footer; SSO SAML 2.0 + Entra ID in the Enterprise block | US05 is **WONT** (ADR 0038 §4). There is no SSO code and none is planned |
| A signed DPA, an enterprise SLA, a contractual guarantee | Closed as scope by ADR 0038 §4. There is no data processing agreement with the transcription subprocessor either (ADR 0040 §3) |
| "30 dias de retenção de áudio (default)", TTL configurable | `RetentionSweeper` purges **meetings** by a global age cutoff and ships **off** (`0` = disabled). Audio never reaches the server at all — the desktop streams it to the provider directly (ADR 0039) |
| "Auditoria imutável — quem acessou qual transcrição, quando, de onde" | `iam_audit_events` records IAM changes and the auth log records login/refresh/logout. Nothing records who read which transcript |
| Push of action items to Linear/Jira/Salesforce/HubSpot "via MCP" | The MCP server is **inbound and read-only** (ADR 0041). The outbound path is OAuth (ADR 0031) and covers nine providers, of which Jira, Salesforce and HubSpot are not three |
| Tenant glossary sold as an Enterprise feature | Not in the domain and not on any screen |
| "Exportação de todos os seus dados a qualquer momento" | US80 is MISSING and is a declared deferral (ADR 0038 §6h). Per-meeting erasure exists (US78) |
| "v1.11 · 21 ADRs aceitos", in two places | There were 49 numbered ADRs when this was measured |
| Seven `href="#"` links in the footer and both Enterprise CTAs | They go nowhere |

## Decision

**The freeze is lifted, and the page is rewritten against the code. A capability that does not
exist may appear on the page only when it is visibly marked as roadmap; everything else goes.**

### 1. Three disposals, and which one applies

- **False → removed or rewritten.** Fabricated social proof, SSO, the DPA and SLA, the retention
  figures, the immutable-access-audit line, the outbound-MCP claim and the version-and-ADR badge.
- **Real but unbuilt → kept and marked.** A visible `RoadmapTag` beside the item, in the page's own
  visual language, on the **Enterprise tier as a whole** and on the **workspace glossary via RAG**.
  Marking beats deleting here because the roadmap is part of an honest product narrative; what it
  must never do is read as available. Where a tag does not fit — inside a FAQ answer — the sentence
  says it in words instead: bulk data export "ainda está no roadmap", beside the per-meeting erasure
  that does exist.
- **Real → stated at its real size.** The integrations list names the nine OAuth providers that
  exist and says Jira, Salesforce and Pipedrive are roadmap without a date. The MCP paragraph says
  it is the inbound direction, read-only, five tools.

### 2. A disabled control is still a promise

The two "Continuar com Microsoft" buttons were removed rather than disabled, and this is the part
most likely to be re-litigated. A greyed-out button with a "soon" tooltip communicates *this is
coming*, and US05 is not coming — ADR 0038 §4 closed it formally. Disabling it would have replaced
a false claim with a slower false claim. The `SSO_NOTICE` copy, the `MicrosoftIcon` component and
the dividers around them went with the buttons, because a leftover divider is how a removed feature
grows back.

### 3. The rule that outlives the pass

This ADR is not a list of eleven fixes. **The claim surface of the product is subject to the same
standard as its documentation: an assertion in the present tense must be true of the tree on the day
it is written, and the reader must be able to tell an assertion from an intention without knowing
the codebase.** That is why the roadmap tag is a visible component rather than a footnote, and why
the FAQ answers now name mechanisms (`tenant_id` on every query, an outbound tunnel, per-meeting
erasure) instead of adjectives.

### 4. What is explicitly not decided here

Nothing about scope. No story moves, no feature is promised, and no deferral is reopened. If the
page is wrong about something in the future, the fix is the page — not a new build to make the page
retroactively true, which is the failure mode running the other way.

## Consequences

**Positive.**

- Issue #456 closes, and with it the one item three accepted ADRs each recorded as unresolved.
- The page becomes usable as portfolio evidence rather than a liability in front of it: what it
  claims can be checked against a public repository, which is the whole argument of the artefact.
- The honesty standard now has a single formulation that covers documents and product copy alike,
  so the next surface that acquires a claim has a rule to be measured against.

**Negative, and named rather than discovered.**

- **The page is less persuasive.** Fabricated logos and an enterprise SLA are persuasive; that is
  what they are for. The replacement — a context strip naming the FIAP × TOTVS challenge — says less
  and is the true thing.
- **Roadmap tags are a maintenance surface.** A tag that is never removed after the feature ships is
  the same defect in a new place. The workspace glossary has no story of its own, which is the one
  marked item nothing in the backlog will remind anybody about — if it is ever built, this ADR is
  where to look for what the page promised.
- **This ADR ages the same way everything else does.** It records claims measured on 2026-08-23. It
  is not a standing certificate that the page is accurate.

## Alternatives Considered

1. **Keep the freeze.** Rejected. The freeze was justified by the pitch and the pitch was held on
   2026-06-15; DEC-04 has outlived its own reason by two months, and three ADRs recording the same
   open item is the repository telling itself the deferral had stopped being a decision.
2. **Delete the landing page and serve the login screen.** Rejected. It removes the false claims and
   the product narrative together, and the narrative is a deliverable — the FIAP submission is
   partly judged on it.
3. **Build the missing features instead.** Rejected on the merits, not on effort: US05 is closed
   scope (ADR 0038 §4), a DPA needs a counterparty, and audio retention cannot be offered for audio
   that never reaches the server. Making the page true by building would mean reversing accepted
   decisions to satisfy marketing copy, which inverts the direction of authority in this repository.
4. **Mark everything as roadmap rather than removing anything.** Rejected. A page where most items
   carry a roadmap tag communicates nothing, and it would have applied the tag to items that are not
   on any roadmap — SSO and the DPA are closed, not scheduled.

## History

| Date | Change |
|---|---|
| 2026-08-23 | Created and accepted. Executes option 2 of issue #456 across the landing page, the marketing home and the auth screen, and lifts the DEC-04 freeze recorded in ADR 0038 §Consequences, ADR 0040 §Consequences and ADR 0046 §Consequences |
