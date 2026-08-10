# 0017 — License: AGPL-3.0

- Status: accepted
- Date: 2026-05-14

## Context

NORA has been a public repository (`github.com/sys0xFF/nora`) since Sub-phase 1.0. To this day **with no LICENSE declared** at the root.

Implications:

1. **Legally, a public repo without a LICENSE = "all rights reserved" by default.** Nobody can use, modify or distribute it without explicit permission
2. **Stratfy is working with 3 strategic scenarios for NORA after the pitch:**
   - **Plan A** — Institutional partnership/hiring (TOTVS or another company hires after seeing NORA): the LICENSE is **neutral** (code delivery can be done via a parallel commercial dual-licensing)
   - **Plan B** — Commercial SaaS operated by Stratfy itself: the LICENSE is **critical**. Without it, any company can clone it and offer a competing SaaS with no return for the team
   - **Plan C** — Technical portfolio / professional positioning of the members: the LICENSE is **neutral** (the code shows technical capability)
3. **Joint Tech Lead + Design Architect recommendation (pre-Sub-phase 1.10 audit):** AGPL-3.0

## Decision

**LICENSE: GNU Affero General Public License v3.0 (AGPL-3.0)**.

A `LICENSE` file at the repository root with the complete official text (https://www.gnu.org/licenses/agpl-3.0.txt).

## Why AGPL-3.0

### Commercial protection (Plan B)

AGPL is **strong copyleft with a network clause**. Critical difference vs GPL:
- **GPL**: forces making the code available only when the code is **distributed**
- **AGPL**: forces making the code available when the code is **run as a network service** (SaaS)

This means: **any company that clones NORA and runs it as a competing SaaS is obliged to make its own fork available, including modifications**. It kills the risk of TOTVS (or a competitor) cloning it and offering "TOTVS Reuniões Inteligentes" without sharing improvements.

### Compatible with Plan A (TOTVS hires)

AGPL does not prevent Stratfy from:
- Selling parallel commercial rights via dual-licensing
- Accepting an institutional partnership/hiring that involves the product
- Re-licensing future versions if there is consensus among the members (copyright holders)

**Stratfy holds the copyright** (attributed individually to the members according to the git history). Re-licensing decisions require consensus among the listed contributors — it is not unilateral by an individual member.

### Compatible with Plan C (LinkedIn/portfolio)

AGPL is a license that is well recognized and respected by the dev community. It does not affect using NORA as a technical portfolio piece.

### Other Considerations

- **Community**: AGPL is seen as a more aggressive but legitimate copyleft. Famous projects such as MongoDB (until 2018), Mastodon, Nextcloud, Elastic (until 2021) used/use AGPL
- **Corporate adoption**: some companies avoid AGPL in internal products for fear of the viral effect. For NORA this is a **feature, not a bug** (we want a commercial fork to be explicitly declared)
- **Compatibility with Dependencies**: NORA uses Spring Boot (Apache 2.0), Next.js (MIT), Tailwind (MIT), OpenAI SDK (MIT), Tauri (Apache 2.0 / MIT). AGPL is compatible as a "downstream license" (all of NORA is AGPL; the deps keep their original licenses)

## Consequences

**Positive:**
- Plan B protected against clone-and-compete
- Stratfy retains control (copyright distributed among the members, re-licensing decisions by consensus)
- Future dual-licensing possible (commercial for enterprise customers + AGPL for the community)
- A professional signal to technical reviewers

**Negative:**
- Some potential adopters may avoid AGPL (but those would not be viable Plan B customers anyway)
- Stratfy must ensure that all dependencies added in the future are AGPL-compatible (Apache 2.0, MIT, BSD, MPL OK; proprietary ones not)

## Alternatives Considered

1. **MIT** — too permissive. Anyone clones + sells with no return. Rejected because of Plan B
2. **Apache 2.0** — more permissive than MIT (includes a patent grant). Same problem: free clone-and-compete
3. **GPL-3.0** — protects distribution but **not SaaS** (the network clause is absent). Insufficient for Plan B
4. **BUSL (Business Source License)** — protects commercially for X years and then becomes open. Currently hot (Sentry, MariaDB use it). Rejected because it adds legal complexity with no clear benefit for NORA right now; and the community perceives BUSL with more resistance than AGPL
5. **Proprietary** — Stratfy controls 100%. But it loses community benefits (contributions, visibility, Plan C traction). Rejected
6. **No LICENSE (status quo)** — rejected: legal default of "all rights reserved" + an unprofessional signal for Plan A/C

## Application Plan

1. Create `LICENSE` at the root with the official AGPL-3.0 text
2. Add a short header in the root `README.md`: "NORA is licensed under AGPL-3.0. See `LICENSE`"
3. A notice in the footer of the public landing page (optional, coordinated with the Design Architect)
4. Document in `SECURITY.md` that vulnerabilities reported with responsible disclosure keep the copyright of Stratfy's contributors (attributed individually according to the git history)

## History

| Date | Decider | Change |
|---|---|---|
| 2026-05-14 | Stratfy (PO) | AGPL-3.0 LICENSE confirmed after the joint Tech Lead + Design Architect recommendation in the pre-Sub-phase 1.10 audit |
