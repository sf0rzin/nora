# 0049 — Permission boundaries: a cap that never grants, and the four questions a cap raises

- Status: accepted
- Date: 2026-08-17
- Related: ADR 0007 (AWS-style IAM — this extends its model with one concept and is its successor
  record for the boundary; ADR 0007 is accepted and unedited), ADR 0046 §1 (which reactivated US44,
  and did so on the thinnest argument of the seven), ADR 0028 (why the IAM authorization tables are
  exempt from RLS enforce, which V031 follows), ADR 0019 (composite FKs as the tenant floor, the
  shape V015/V027 established and V031 reuses)

## Context

ADR 0046 §1 reactivated US44 and was blunt about why it is the weakest of the seven:

> "The weakest of the seven on its own merits, and it is included with that said. Its note has been
> 'needs an organizational hierarchy and IAM delegation that nothing else asks for', which is true.
> What changed is that a boundary is the one IAM concept a reviewer looks for and does not find."

That is the whole argument and this ADR does not improve it. It does take it seriously in the
design: **the story is worth one concept, not a delegation subsystem.** Everything AWS attaches to
the idea — an organizational hierarchy, service control policies, the `iam:PermissionsBoundary`
condition key that forces a delegated admin to bound the identities they create — is out, and §7
says which of those were considered and rejected rather than merely omitted.

A permission boundary is a policy attached to a principal that **caps** it. An action is allowed
only if the principal's own policies allow it **and** the boundary allows it. A boundary never
grants. That property is what makes handing out policy-attach rights safe: the holder cannot widen
themselves past their own cap, so an admin can delegate without delegating escalation.

NORA had no such concept. `AuthorizationService` collected every statement applicable to a user and
asked `PolicyEvaluator` for a yes or no; every statement in that set could only ever add.

## Decision

**A user may carry at most one permission boundary: a policy of the same tenant, attached through
`iam_permission_boundaries` (migration V031), intersected with the user's own statements inside the
single traversal `PolicyEvaluator.explain` already was.**

### 1. It attaches to a user, and to nothing else

One attachment point. Not groups, not the tenant.

AWS attaches boundaries to identities rather than to groups, and the reason survives translation:
a user in two bounded groups has two boundaries, and both ways of combining them are wrong to
somebody. The **union** widens as you join groups, which is a boundary that grants — the one thing
this feature exists to make impossible. The **intersection** makes joining a group *remove*
permissions, which inverts what every other row in this schema does and would make group membership
unreadable at a glance.

`PRIMARY KEY (user_id)` in V031 is that decision expressed where it cannot be forgotten: the schema
cannot hold a second boundary for a user, so no code has to decide what two would mean.

The cost is named: **there is no way to bound a whole department in one action.** Bounding twelve
people is twelve calls. That is the correct trade for a story whose own reactivating ADR says an
organizational hierarchy is exactly what nothing else here asks for.

### 2. Root is **not** capped, and this is the trap in the story

`AuthorizationService.isAllowed` returns `true` on its first line for `users.isRoot(...)`, before a
single statement is read. A boundary changes nothing about that ordering.

**A boundary that could cap Root would be a lock whose only key is inside it.** The tenant Root is
the one principal guaranteed to be able to administer IAM; cap it out of `iam:*` and nobody can
remove the cap, in a product with **no support desk and no operator override for tenant IAM** — the
control plane (ADR 0022) is telemetry and model catalogue, and deliberately has no path into a
tenant's authorization config. The failure is permanent and self-inflicted, and it is reachable by
one wrong policy id in a form.

AWS makes the same call for the account root, and this is the honest reading of it rather than an
appeal to authority: an unbounded root is a defensible design **as long as it is written down**. So:

- the bypass runs first, unchanged, and the boundary is never consulted for a Root;
- `IamService.setBoundary` **refuses** a boundary aimed at the Root, `409 IAM_BOUNDARY_ON_ROOT`.

The refusal matters as much as the bypass. Without it the table could hold a row that the IAM screen
would render as a control and the evaluator would never read — a lie with a UI. Refusing means the
absence of a cap on Root is visible at the moment somebody tries to create one.

**The consequence, stated plainly:** a compromised Root is unbounded, and permission boundaries do
not reduce that blast radius by one action. What they reduce is the blast radius of everyone else.

### 3. The intersection happens in one place: `PolicyEvaluator.explain`

US43 collapsed decision and explanation into one traversal — `isAllowed` is literally
`explain(...).allowed()`, and a unit test named `explainAndIsAllowedNeverDisagree` pins it. The
boundary is applied **inside that same method**, in a five-argument overload; the old four-argument
form delegates to it with a null boundary.

Anywhere else would be a second code path, and the two would drift. The specific failure that
avoids is not hypothetical arithmetic: **the simulator would explain a decision the gate did not
make.** A user capped out of an action would get a 403 from the gate and `NO_MATCHING_STATEMENT`
from `POST /iam/simulate`, sending whoever is debugging to read the attached policies — the one
place the answer is not. A simulator that lies is worse than no simulator, so the boundary gets
reasons of its own:

| Reason | Means |
|---|---|
| `BOUNDARY_NOT_PERMITTED` | the user's policies allowed it and the boundary has no Allow covering it. No deciding statement, because a cap denies by *not permitting* |
| `BOUNDARY_EXPLICIT_DENY` | the user's policies allowed it and a Deny **inside the boundary** matched. The statement is reported, and it is a statement of the boundary document |

`PolicyDecision.fromBoundary()` exists so a caller cannot resolve `statementIndex` against the wrong
list: a boundary index points into the boundary document, and walking the attached policies for it
would confidently name whichever policy happened to sit at that offset. `POST /iam/simulate` also
returns `boundaryPolicyName` **whenever the user has one**, decided by it or not — "this user is
bounded" is part of reading any answer about that user.

**Ordering inside the method is the property, not an implementation detail.** The boundary is
consulted only after the user's own statements answered ALLOW. A deny stands on its own and keeps
its own reason, so no arrangement of the code can turn a boundary into a grant. It cannot be
rewritten as "evaluate both and combine".

Three other entry points had to follow, and each for a different reason:

- **`filterAllowed`** (per-item listing) evaluates per row and simply passes the boundary through.
- **`uniformDecision`** is the one that would have been a hole. A present-and-true answer tells the
  caller to **skip** the per-item filter and paginate in SQL, so a cap applied only inside that
  filter would be skipped with it. It now intersects both uniform answers, and any doubt on either
  side returns `empty`, which falls back to item-by-item evaluation — where the cap runs.
- **`hasAnyAllow`** (the list pre-gate) asks the boundary the same weak question. It cannot become
  stricter than the per-row check: a cap that admits no member of the set denies every row anyway.
  What it buys is a clean 403 instead of an empty page.

The cost is one extra query per authorization: `findBoundaryForUser` is a primary-key lookup joined
to `iam_policies`, alongside the two the path already runs (`isRoot`, `collectStatementsForUser`).
It was not folded into the statement collector on purpose — see §6.

### 4. Who may set one, which is the security question of the story

Three actions, deny-by-default like everything else: `iam:boundary:read`, `iam:boundary:set`,
`iam:boundary:delete`. Set and delete are separate so a delegated admin can be given the power to
bound their team without the power to unbound anyone.

`iam:boundary:read` is **not** folded into `iam:policy:read`, on the argument US43 already ran for
`iam:policy:simulate`: which policy caps whom is part of the attachment graph, and no other endpoint
exposes that graph. An existing `iam:policy:read` grant therefore does not silently acquire it,
while the `iam:*` shape admin policies use picks all three up on its own.

**A principal that can edit its own boundary has no boundary**, so `setBoundary` and
`removeBoundary` refuse the caller as subject: `409 IAM_BOUNDARY_SELF`.

That rule is the **second** line of defence and it is worth saying which is the first, because the
first is the reason this feature is delegation-safe at all. **A boundary applies to every action,
`iam:boundary:*` included.** A cap that does not grant those actions stops its holder from touching
*anybody's* boundary — not their own, and not a peer's, which closes the two-bounded-admins
collusion the self-rule alone would leave open. The self-refusal exists for the case where a tenant
legitimately grants a delegated admin `iam:boundary:set` so they can bound their own team: without
it, that admin's first move could be to widen themselves.

### 5. No boundary means unrestricted, never deny-all

A user with no row in `iam_permission_boundaries` is unrestricted, and that is the state of every
user of every tenant the day V031 ships. The migration backfills nothing, because there is nothing
to backfill.

The opposite reading would turn deploying this migration into a tenant-wide outage, so the code is
written so it cannot be read the other way: the port returns `Optional<PermissionBoundary>`,
`AuthorizationService` maps empty to `null` in exactly one private method, and `null` is what the
evaluator documents as "no boundary". An **empty** statement list is deliberately a different thing
— a cap that permits nothing, which denies. It is unreachable through the API (the document parser
refuses a policy with zero statements) and is defined that way because, between two unreachable
readings, the one that costs access rather than granting it is the safe one. Both are unit-tested,
so neither is an accident of control flow.

### 6. A table, not a flag, and a policy that cannot be deleted out from under a cap

**Why not `iam_user_policies.is_boundary`?** One column instead of one table, and rejected for a
structural reason rather than an aesthetic one. `collectAttachedPoliciesForUser` reads that table,
unions the group attachments, and hands the result to the evaluator **as grants**. With a flag, the
boundary document would sit inside that query's range, and a single forgotten `AND NOT is_boundary`
would turn the cap into a grant of everything it was written to forbid. A separate table means the
grant query cannot see it: there is nothing to remember. The same argument keeps `PermissionBoundary`
a distinct type from `AttachedPolicy` — passing one where the other belongs does not compile.

**Why `ON DELETE NO ACTION` on the policy FK.** Under `CASCADE`, `DELETE /iam/policies/{id}` would
silently remove somebody's cap: a privilege escalation reachable by anyone who can delete a policy,
whose audit entry would read only "policy deleted". The delete is refused —
`409 IAM_POLICY_IN_USE_AS_BOUNDARY` — so removing a cap must be an explicit act against the
boundary, which is audited as one. `NO ACTION` rather than `RESTRICT` because it is deferred to the
end of the statement: deleting a tenant cascades into both tables, and only the deferred check is
guaranteed to see the boundary row already gone.

**The tenant floor is the schema's, not the service's.** Two composite FKs — `(tenant_id, user_id)`
into `users` and `(tenant_id, policy_id)` into `iam_policies` — in the shape V015 and V027
established, with the `UNIQUE (tenant_id, id)` on `iam_policies` that the second one needs. The
service resolves the subject inside the caller's tenant first and answers `404` for anyone else, so
a boundary in tenant A cannot be read or written from tenant B; the constraints are the floor that
does not depend on that check being remembered by the next person.

**RLS follows the family.** V020 (ADR 0028) disabled RLS on the IAM authorization tables because
onboarding flows without a JWT write to them, leaving the `tenant_isolation` policies defined and
inert. V031 does exactly that — policy created, RLS not enabled — so this table matches the end
state of its family instead of being the one member that behaves differently.

**The migration is out of order, and that costs one configuration flag.** V031 was reserved by US41,
released when policy templates shipped as a code catalogue, and V032 took the next number while it
sat empty. This story filled the gap instead of renumbering, because renumbering V032 would change
the checksum of a migration that may already have run. The consequence is that a database holding
V032 sees a *lower* pending version, which Flyway refuses by default: `validate` fails on boot with
"Detected resolved migration not applied to database: 031" and the API does not start. So
`spring.flyway.out-of-order` becomes `true`. Nothing already applied is re-run and no checksum
moves. The named cost is that a genuinely accidental out-of-order file would now boot silently too
— the check for that is the per-migration inventory in `data-model.md` §5, not the runtime.

### 7. What is deliberately not built

- **The `iam:PermissionsBoundary` condition key.** In AWS this is what makes delegation *complete*:
  it lets a policy say "you may create users only if you attach this boundary to them". NORA has
  five condition operators, all of them string or date comparisons over a request context; that key
  is a different mechanism, not a sixth operator. **Named consequence: a delegated admin holding
  `iam:attachment:create` can still create an unbounded user.** What a boundary gives here is that
  the admin cannot escalate *themselves* — which is the property the story asked for, and the one a
  reviewer looks for.
- **Boundaries on groups, service control policies, an organizational hierarchy.** §1, and ADR 0046
  §1 saying in writing that nothing else asks for it.
- **A boundary written as an inline document.** `PUT` takes a policy id. An inline document would be
  a policy no screen lists, that `iam_policy_versions` does not version and that the simulator could
  not name — three properties every other policy in the tenant has, lost for the one document that
  decides what the others cannot exceed.

## Consequences

**Positive**

- IAM gains the concept a reviewer looks for, and it is a real cap rather than a label: the two
  tests that matter are that an action allowed by the policies and not by the boundary is denied,
  **and** that an action allowed by the boundary and not by the policies is denied too.
- Delegation becomes expressible: a bounded admin can be handed attach rights without being handed
  escalation.
- The simulator explains the cap, so the feature is debuggable on the day it ships rather than being
  a 403 with no story.
- The single-traversal property US43 established survives, and is now pinned under a boundary as
  well.

**Negative / debts**

- **One more query on every authorization decision.** Small and indexed, but it is on the hot path,
  and folding it into the statement collector was rejected in §6 for a reason that costs this.
- **An unbounded Root**, §2. Written down rather than fixed, because the fix is worse.
- **No group-level bounding**, §1. Bounding a department is one call per person.
- **A delegated admin can still create unbounded users**, §7. The AWS mechanism that closes it is
  not in this codebase and inventing it here would be the "build it because AWS has it" trap ADR
  0046 §1 warned against.
- **`spring.flyway.out-of-order` is now `true`** for the whole application, to let V031 land after
  V032 (§6). It is a repository-wide loosening bought for one migration, and from here on an
  accidental out-of-order file boots instead of failing loudly.
- **A pre-existing defect was found while adding that flag and is left in place, named.** The three
  Flyway keys in `application.yml` — `enabled`, `baseline-on-migrate`, `locations` — are indented
  under `spring.jpa` rather than `spring.flyway`, so Spring binds them into `JpaProperties`, which
  ignores unknown fields. **Flyway has been running on its own defaults for the entire life of the
  file.** Two of the three defaults coincide with the intent; `baseline-on-migrate` does not — its
  real value is `false`. Fixing the indentation would be the first time that key ever took effect,
  against databases nobody has re-tested, which is a decision of its own and not a reindent inside
  an unrelated story. The new `spring.flyway` block therefore carries exactly one key, with the
  defect written beside the old ones.
- The IAM screen takes a user id and a policy id in text fields, like the rest of that page: no
  endpoint of this API lists a tenant's users, which US43's row already records as a limit.

## Alternatives Considered

1. **Do not build it; move US44 to WONT.** The most consistent with ADR 0038's subtraction, and
   ADR 0046 §1 considered exactly this and decided otherwise, in writing, on an argument it labelled
   the thinnest of seven. Rejected because reversing an accepted decision needs a new reason, and
   nothing changed between that ADR and this one.
2. **Model the boundary as an ordinary attached policy with a Deny-everything-else statement.** No
   schema, no new concept: express the cap as explicit Denies inside a normal policy. Rejected
   because it is not a cap. It denies what the author *thought of*; a boundary denies what nobody
   thought of, which is the entire difference, and every new action shipped would silently escape
   every such "boundary" until somebody edited it.
3. **Apply the boundary in `AuthorizationService` after calling the evaluator.** Simpler diff — one
   `if` after the decision, no evaluator change. Rejected: it is precisely the second code path §3
   is about, and `explain` would keep answering as though the cap did not exist.
4. **Let the boundary cap Root, with a "you are locking yourself out" confirmation in the UI.**
   Rejected in §2: a confirmation dialog is not a recovery path, and there is no support desk behind
   this product to be the recovery path.
5. **Boundaries on groups as well as users.** More expressive and it is what a tenant with an org
   chart would want. Rejected in §1: the combination rule for two boundaries is wrong in both
   directions, and the story that would justify the hierarchy is the story ADR 0046 §1 says nothing
   asks for.
6. **`is_boundary` flag on `iam_user_policies`.** Rejected in §6 — it puts the cap inside the range
   of the query that collects grants.

## History

| Date | Decider | Change |
|---|---|---|
| 2026-08-17 | sys0xFF | Created and accepted. Adds the permission boundary to the IAM of ADR 0007 — one policy per user, migration V031 — and decides the five questions that would otherwise have stayed implicit in code: it attaches to users only and never to groups; the tenant Root is **not** capped and a boundary aimed at it is refused rather than stored; the intersection happens inside `PolicyEvaluator.explain`, the same traversal the gate uses, with two reasons of its own so the simulator reports the cap instead of a misleading "no statement matched"; setting or removing one's own boundary is refused, on top of the cap applying to `iam:boundary:*` like any other action; and an absent boundary means unrestricted, never deny-all. Records what is deliberately not built, including the `iam:PermissionsBoundary` condition key and the consequence of its absence. Successor record to ADR 0007, which is accepted and unedited |
