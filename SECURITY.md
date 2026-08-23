# Security Policy

How to report a vulnerability in NORA, what is in scope, and what to expect after you do.

## Reporting a vulnerability

**Please do not open a public issue on GitHub.** Use the channels below:

- **Secure email:** axonogenesis@proton.me (with the `[SECURITY-NORA]` prefix in the subject)
- **Expected response:** within **3 business days** with an initial acknowledgement
- **Disclosure timeline:** typically **90 days** between report and public disclosure, with the fix merged beforehand

PGP key available on request via the email address above.

## Scope

### In scope

- `services/api/**` — Spring backend (authentication, IAM, persistence, REST contracts)
- `services/nlp-worker/**` — Python NLP worker (PII Shield, LLM calls, contracts)
- `apps/web/**` — Next.js frontend (auth flow, XSS, CSRF)
- `apps/desktop/**` — Tauri app (local secret store, on-device transcription)
- `infra/host/**` — self-hosted stack (compose, ingress, secret management)
- `.github/workflows/**` — CI/CD (secret leakage, supply chain)
- `packages/**` — shared packages

### Out of scope

- **Vulnerabilities in upstream dependencies** (Spring Boot, Next.js, Tauri, OpenAI SDK, etc.) — report them upstream and let us know as a courtesy
- **User-environment-specific configuration** that does not derive from standard NORA code
- **Self-XSS** (requires active social engineering to exploit)
- **UX/usability issues with no security component**

## High-priority vulnerabilities

NORA processes sensitive data (meeting transcripts, possible personal data via PII Shield) under Brazil's LGPD. **High-priority** categories:

1. **Tenant isolation bypass** — any path that lets a user of tenant A access data from tenant B (`tenant_id` violated in a query, frontend-only filtering with no backend gate, JWT manipulated to swap `tenantId`)
2. **PII Shield bypass** — a path that delivers raw PII to the LLM (including via prompt injection)
3. **IAM privilege escalation** — a user manages to execute an action that the applicable policy Denies or does not Allow (Policy Evaluator bypass, Resource string injection, condition operator misuse)
4. **JWT/refresh token forgery** — forged signature, replay attack, or cookie hijack without detection
5. **Secrets leakage** — Key Vault secrets exposed in logs, error responses, or via debug endpoints
6. **SQL injection / NoSQL injection** — in any dynamically built query

## Lower-priority categories

- Rate limiting bypass (impact: spam, LLM/Speech cost)
- Non-sensitive information disclosure (library versions, internal paths)
- DoS via malformed input (impact: degraded service)
- CSRF where NORA already has mitigation (SameSite cookies, etc.)

## Responsible disclosure

NORA is licensed under **AGPL-3.0** (see `LICENSE`). Researchers who discover vulnerabilities:

- **Keep copyright** over their research work
- **Are credited** in the release notes and in the post-mortem (if they agree)
- **Are not pursued** legally for research within the scope declared in this document (good faith security research)
- **Must not test in production** without prior coordination (use a local clone + your own environment)

## DPO / LGPD Data Protection Officer

**Data Protection Officer** (LGPD requires a natural person to be designated):

- **Name:** Anthony Sforzin (`@sf0rzin`), maintainer of the repository
- **Contact:** axonogenesis@proton.me (with the `[LGPD-NORA]` prefix)

The same person maintains and operates NORA. At GA with >10 tenants, a formal DPO would have to be hired or designated.

## Active security tooling

- **Dependabot** enabled via `.github/dependabot.yml` — weekly updates grouped by ecosystem (Maven, pip, npm, Cargo, GitHub Actions). Alerts via the Security tab.
- **Coverage gates in CI** — narrow, and declared in the build files rather than here. `mvn verify` runs three JaCoCo rules (`PolicyEvaluator`, the `domain.iam` package, and the whole bundle — `services/api/pom.xml`); the worker job runs `pytest --cov=nora_nlp.services.pii_shield --cov-fail-under=90`; the two Next.js apps apply per-module thresholds from their `vitest.config.mts`. Until 2026-08-23 the backend gate was the single class alone, so a coverage regression anywhere else passed the build in silence. ADR 0018's ">85% across IAM, Auth and PII" is a target, not something any gate enforces.
- **Dependency and image scanning** — weekly, not on the merge gate, and deliberately: `npm audit` over the three Node packages, `pip-audit` over the worker environment, `cargo-deny` over the Rust crate and `trivy` over the four published images (`.github/workflows/supply-chain-drift.yml`). Before 2026-08-23 only the Rust crate was scanned. It is weekly because a newly published advisory is not a reason to block an unrelated pull request — the same argument the repository already wrote for `cargo-deny`.
- **Build provenance and SBOM** — published with the images (`.github/workflows/build-images.yml`). **Nothing signs them and the host verifies no signature**, which is written into that workflow rather than left to be assumed: a compromised publishing account can push an image the host will pull. Attestation proves where an image was built, not that it should be trusted.
- **Authorization coverage** — `AuthorizationCoverageIntegrationTest` asserts that every HTTP handler declares `@RequiresPermission` or `@AuthorizationNotRequired`, deny-by-default. Since 2026-08-23 the endpoint list it checks is **derived from Spring's `RequestMappingHandlerMapping`** instead of maintained by hand, so a new endpoint cannot be missed by forgetting to add it to the test.
- **GitHub Secret Scanning / Push Protection** — the NORA repository is public, so Secret Scanning and Push Protection are available for free and should be confirmed as enabled in the repository's Security settings.
- **PII Shield** in the worker as the last gate before the LLM (ADR 0012), measured against an adversarial corpus with a leak rate and a false-redaction rate that each carry a ceiling, a ratchet and a dated goal (ADR 0043). The corpus is the control: a change that lowers one rate by raising the other fails the test rather than being argued about.

## History

| Date | Change |
|---|---|
| 2026-05-14 | Document created during Sub-phase 1.10 (Docs Refresh) |
| 2026-06-06 | Doc x code reconciliation + standardization |
| 2026-08-23 | **"Active security tooling" reconciled against the tree**, which had moved past it in both directions. The coverage-gate bullet said "two, both narrow" and there are more; it now points at the build files instead of counting, for the same reason the coverage figures elsewhere stopped being restated. Three controls that existed and were unlisted were added — dependency and image scanning (weekly, and the argument for weekly rather than gating), build provenance and SBOM, and the authorization-coverage test, whose endpoint list is now derived from Spring rather than maintained by hand. The provenance bullet states what attestation does **not** prove: nothing signs the images and the host verifies no signature, so a compromised publishing account can push an image the host will pull. That limitation is recorded here and in `build-images.yml` rather than being implied away by the word "provenance". |
