# ADR 0009 — Leave balance grants: event-based `people`↔`timeoff` wiring, and combined-transaction controller methods

**Status:** Accepted
**Date:** 2026-08-14
**Deciders:** Hasan (solo dev)
**Relates to:** ADR 0007 (htmx admin CRUD transaction shape), ADR 0005 (`runAsSystem` correction)

---

## Context

Phase 1.5 (`docs/CURRENT_PHASE.md`) adds `timeoff.BalanceService`, which grants a pro-rated
`leave_balance` row on three triggers (PRD §12.2): a new employee's hire, a leave type's
(re)activation, and the annual Jan 1 job. Two design questions came up that aren't obvious from
the PRD or CLAUDE.md alone.

**1. Balance grants need traffic in both directions between `people` and `timeoff`.** The on-hire
grant needs to react to `people.EmployeeService.create(...)`. The annual job and the
leave-type-activation backfill need to iterate active employees, which only `people` can answer.
A direct call each way would be a package cycle — ArchUnit's `packages_haveNoCycles()` rejects it,
and CLAUDE.md §4 forbids reaching into another module's internals regardless.

**2. Whether `AdminLeaveController`'s mutation endpoints are safe to build like
`AdminEmployeeController`.** ADR 0007 root-caused a real bug in `AdminOrganizationController`'s
first draft: a `@Transactional` write followed by a *separate* `@Transactional` read call, both
from the (non-transactional) controller, intermittently rendered the write as an empty table —
`TenantSessionVariableListener.afterBegin` did not re-fire for the second transaction, so RLS's
`app.tenant_id` was unset and every row was denied. The documented fix was `OrganizationAdminService`:
one `@Service` class doing the whole write-then-read as a single `@Transactional` method.

`AdminEmployeeController` (Phase 1.4) does not follow that shape — its `create`/`update`/`terminate`
handlers call `EmployeeService`'s write method, then separately call `populateContentAttributes(model)`,
which does its own `@Transactional(readOnly = true)` read. That is structurally the same
write-then-separate-read pattern ADR 0007 reproduced as broken. Whether it is actually safe (and
why it might differ from the original bug) was not established — re-investigating it was out of
scope for this phase, and CLAUDE.md §12 requires stopping before touching `TenantSessionVariableListener`
itself to find out. Given no way to test either shape here (no local Testcontainers/Docker
available this session), the conservative choice was made instead of a duplicated one.

## Decision

### A. `people` never imports `timeoff`; `timeoff` depends on `people` only through `PeopleFacade`

- `people.EmployeeService.create(...)` publishes a new `people.EmployeeHiredEvent(UUID employeeId)`
  via `ApplicationEventPublisher`, inside its existing `@Transactional` method. `people` has zero
  knowledge that `timeoff` exists.
- `timeoff.EmployeeHiredEventListener` (mirrors `people.EmployeeInviteAcceptedListener`'s shape) has
  a plain `@EventListener` — not `@TransactionalEventListener(phase = AFTER_COMMIT)` like that
  listener — because the initial grant is an internal DB write with every reason to be atomic with
  employee creation, not a decoupled side effect that should survive the employee record failing to
  commit. It runs synchronously, in the same transaction, via default `@EventListener` semantics.
- `PeopleFacade` gained `listActiveEmployeeHireInfo()` and `requireEmployeeHireInfo(UUID)`, the same
  shape as `OrgFacade`'s existing options list — `timeoff` is simply `PeopleFacade`'s second real
  consumer after `web`.
- This also means the still-open termination-cascades-into-leave-request-cancellation gap (PRD
  §14.4, deferred to Phase 1.6 per `docs/CURRENT_PHASE.md`'s carried-forward notes) has a
  ready-made pattern to close it later the same way — `people` publishing an event, `timeoff`
  listening — without ever needing the reverse dependency. Not built now; noted for whoever picks
  up 1.6.

### B. Every `AdminLeaveController` mutation calls one combined write-then-read `@Transactional` method

`LeaveTypeService`/`PublicHolidayService` each expose `createAndList`/`editAndList`/
`activateAndList`/`deactivateAndList`/`deleteAndList` — thin wrappers that call the existing
single-entity write method and then `listAll()`, both inside one `@Transactional` method — so the
controller never composes a write call and a separate read call itself. This is the same shape as
`org.OrganizationAdminService`, applied without introducing a whole extra class: since Leave Types
and Holidays don't need cross-entity reads the way Division-count-gates-Department-delete did, the
combined methods live directly on the owning service instead.

`AdminEmployeeController`'s narrower shape was deliberately not copied here, given the concern in
Context above. It was left exactly as-is — this ADR does not resolve whether it's actually safe,
only that Phase 1.5 shouldn't add a second instance of the same open question.

## Consequences

**Positive:**
- `packages_haveNoCycles()` passes with no exemption — verified directly (`ArchitectureTest`, 4/4
  green) after wiring both directions through events/facade rather than direct calls.
- The transaction shape for `AdminLeaveController` matches the one ADR 0007 actually reproduced as
  correct, not the one whose correctness is unconfirmed.
- The event pattern is a straight copy of `EmployeeInviteAcceptedListener`/`UserInviteAcceptedEvent`,
  not a new idiom — nothing new to learn for the next module that needs the same kind of wiring.

**Negative / open:**
- Whether `AdminEmployeeController`'s write-then-separate-read shape is actually broken, actually
  safe for some undocumented reason, or merely not yet hit in practice remains unresolved. Worth a
  dedicated investigation (per ADR 0007's own "Negative/open" note) rather than assuming either
  answer.
- `LeaveTypeService`/`PublicHolidayService` now carry both a plain write method (`create`, `edit`,
  ...) and an `...AndList` combined variant. The plain methods exist because `BalanceServiceTest`/
  `LeaveTypeServiceTest`/etc. need a return value that isn't a full list; this is a small amount of
  duplication traded for not having to change already-written tests. If it becomes annoying, the
  plain methods could be made private and only the combined ones kept public — not done now
  (YAGNI: no second caller of the plain methods exists yet).

## Alternatives considered

**1. `people` calls into `timeoff` directly for the on-hire grant, `timeoff` never reads `people`.**
Rejected: the annual job and leave-type-activation backfill both structurally need to iterate
employees, so `timeoff → people` is required regardless. Adding `people → timeoff` on top would
create the exact cycle ArchUnit forbids.

**2. `@TransactionalEventListener(phase = AFTER_COMMIT)` for `EmployeeHiredEvent`, matching
`EmployeeInviteAcceptedListener` exactly.** Considered for consistency, rejected because the two
cases differ: invite-acceptance activation is inherently a separate transaction crossing from
`identity`'s commit into `people`, while the on-hire grant is purely internal to the transaction
`EmployeeService.create` already owns — no reason to add a second transaction (and the `REQUIRES_NEW`
propagation that goes with it) for something that can just be atomic.

**3. A dedicated `LeaveConfigAdminService` orchestrator class, mirroring `OrganizationAdminService`
exactly (one extra class, not extra methods on the two services).** Rejected as more ceremony than
the two entities need — Leave Types and Holidays never need to read each other's data to build a
response, unlike Division/Department. The combined-transaction requirement is satisfied by methods
on the owning services instead.

## References

- PRD §12.2 (grant rules), §14.2 (invite acceptance), §14.4 (termination leave-cancellation gap)
- CLAUDE.md §4 (module boundaries, facades), §7 (transactions on service methods only), §8
  (tenant isolation tests), §12 (ask-first list)
- ADR 0007 (the original write-then-read bug and its fix)
- ADR 0005 (system-scoped tables and the `runAsSystem` correction — same "log-backed, not
  theorized" standard this ADR tries to meet for what *is* confirmed vs. what remains open)
- `people.EmployeeInviteAcceptedListener` / `UserInviteAcceptedEvent` (the pattern this ADR copies)
- `people.EmployeeTerminationJob` (the tenant fan-out pattern `timeoff.AnnualGrantJob` copies)
