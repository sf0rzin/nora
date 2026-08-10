# 0007 — AWS-style IAM (Root + Users + Groups + Policies)

- Status: accepted
- Date: 2026-05-07

## Context

The initial version of the documents envisaged a hybrid **RBAC + ABAC** IAM with five fixed roles (`ROOT`, `ADMIN`, `MANAGER`, `ANALYST`, `VIEWER`) and an `access_scopes` table to limit visibility by `team` or `region`. That model grew organically across `docs/PROJECT.md`, `docs/visao-do-produto.md`, `docs/data-model.md` and `docs/backlog-mvp.md`, producing inconsistencies:

- `visao-do-produto.md` listed 4 levels ("Root, Admin, Manager, Analyst/Viewer") while `PROJECT.md` listed 5.
- `access_scopes` used `OWN_MEETINGS / TEAMS / REGIONS`, but the narrative talked about `department / project / client_account`.
- `access_scopes.user_id` was the PK, preventing more than one scope axis from being combined per user.
- The "region" concept was orphaned (no table of its own, no story requiring it).
- Fixed roles contradicted the repeated comparison with "AWS IAM" — which is precisely the opposite.

In addition, the team agreed that Enterprise tenants need **real freedom** to model their own access structure (departments, projects, accounts, regions, any axis), without depending on the platform adding new roles.

## Decision

Adopt an **AWS IAM-style** model from the MVP onwards:

```
Tenant
├── Root user           — owner do tenant; criado no provisionamento; bypass total
├── Users               — convidados pelo Root ou por quem tiver permissão de IAM
├── Groups              — coleções nomeadas de usuários, criadas livremente pelo tenant
├── Policies            — documentos JSON: Effect / Action / Resource [/ Condition]
├── Users ⇄ Groups       (N:N)
├── Groups ⇄ Policies    (N:N)
└── Users ⇄ Policies     (N:N — anexação direta, opcional)
```

**Vocabulary:**

- **Actions** follow the `service:operation` pattern: `meeting:read`, `meeting:upload`, `analysis:export`, `tenant:context:write`, `iam:user:invite`, `iam:group:create`, `iam:policy:create`, `audit:read`, etc. Wildcards allowed (`meeting:*`, `*`).
- **Resources** are ARN-like: `nora:tenant/{tenantId}:meeting/{meetingId}`, `nora:tenant/{tenantId}:meeting/*`, with wildcard.
- **Conditions** in AWS style (`stringEquals`, `stringIn`, `dateGreaterThan`, etc.) operating over attributes defined by the tenant itself: `nora:Department`, `nora:Project`, `nora:Account`. NORA does not impose a taxonomy.

**Authorization evaluation (Policy Evaluator):**

1. If the user is the tenant's Root → **Allow**.
2. Collect all policies attached to the user directly and via groups.
3. Evaluate **Deny first**: any applicable Deny wins.
4. Otherwise, require at least one **Allow** matching `Action` + `Resource` + `Condition`.
5. Default: **Deny**.

**Data model:** see `docs/data-model.md` (tables `users.is_root`, `iam_groups`, `iam_user_groups`, `iam_policies`, `iam_policy_versions`, `iam_group_policies`, `iam_user_policies`). The old tables `roles`, `user_roles`, `teams`, `user_teams` and `access_scopes` are removed.

**MVP scope (explicit decision):**

The **complete** functionality goes into the MVP — no slicing. This includes:

- CRUD of Users, Groups, Policies, attachments.
- Policies in JSON with Effect/Action/Resource **and** Condition.
- Wildcards in Action/Resource.
- Immutable versioning of Policies.
- Auditing of IAM changes.
- Optional templates ("ReadOnlyAccess", "MeetingAnalystAccess") to speed up onboarding.
- Minimal Web UI for management.

A form-based visual editor, a policy simulator and permission boundaries enter the backlog as **Should/Could** (US42–US44), but are not blockers for the first release.

## Consequences

**Positive:**

- Tenants have full freedom to model access according to their organizational structure, without waiting for NORA's roadmap.
- A familiar model for Enterprise IT teams (AWS vocabulary).
- Auditing becomes natural: every policy has a version, every attachment has a timestamp and an author.
- Account Health Score, Customer Confidence and Product Context can use conditions to filter data without inventing new concepts.

**Negative / costs:**

- Implementing a robust Policy Evaluator (Deny-first, wildcards, conditions) is significant work — estimated at 3–4 weeks of 1 senior dev.
- The IAM UI requires care so as not to intimidate less technical users (templates and, in the future, a visual editor mitigate this).
- Getting a policy wrong can block legitimate access. Mitigation: Root always has bypass; auditing records changes; safe templates as a starting point.

## Alternatives Considered

1. **Keep fixed roles (classic RBAC).** Rejected: it does not address the heterogeneity of Enterprise tenants; it was already in conflict with the product's own narrative, which compares itself to AWS IAM.
2. **Reduced hybrid model (Root + Groups + predefined permissions, without JSON policies).** Rejected: it would still require a NORA roadmap for each new filtering capability; it would lose the main differentiator.
3. **Defer AWS-style IAM to post-MVP, keep fixed roles in the MVP.** Rejected by the team: granular IAM is part of the Enterprise promise and of the FIAP/NEXT 2026 pitch; delivering the MVP without it weakens the demonstration.

## Accompanying Rules

- Every new feature defines which Actions and Resources it exposes; document this in `docs/development-standards.md` (section to be created) or in a specific ADR.
- The backend must have a single interceptor (`@RequiresPermission("meeting:read")` or equivalent) that triggers the Policy Evaluator. Never evaluate permissions manually in a controller.
- IAM changes (create/edit/attach a policy or group, add/remove a member) **always** produce a record in `audit_events`.
- The tenant's Root is unique per tenant and cannot be removed or demoted via the UI; changing the Root is a separate administrative process (post-MVP).
- Default for any new resource: **Deny** if no policy covers it.
