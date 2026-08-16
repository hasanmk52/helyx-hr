# Current Sub-Phase

**Working on:** Phase 1.6 — Leave Requests + Approvals
**Branch:** `phase-1.6-leave-approvals`
**Goal:** An Employee books time off against the balances Phase 1.5 granted; their Manager (or an Admin, if no manager is set) approves or rejects it; an approved request debits `leave_balance.used`; a cancellation credits it back. This is the phase that finally makes the "Book Time Off" cards on Home (and the still-placeholder "For Action" nav item) do something.

## Read these before doing anything

1. `docs/Helyx_Implementation_Plan.md` — the "1.6 Leave Requests + Approvals" section under Phase 1 — MVP
2. `docs/Helyx_PRD.md` — §12.3 (duration algorithm — the pseudocode is the spec, implement it exactly, exhaustively unit-tested per CLAUDE.md §8), §12.4 (approval flow state machine), §21 (`leave_request` schema), §26 (permissions matrix: booking is self-service for all three roles, approval is Manager-of-reports or Admin-any, no self-approval ever)
3. `CLAUDE.md` — §5 (tenancy — `leave_request` is a fourth `timeoff` table, same RLS/`@TenantId` template as 1.5's three), §6a (durable outbox — approval/rejection/cancellation notify by email; that's an external side effect and **must** go through `email_outbox`, not an inline `mailSender.send()`), §8 ("critical logic, TDD first" explicitly names the leave duration algorithm), §10 ("Handling a state transition" recipe — sealed interface/enum + `<Entity>StateMachine` + audit + optimistic locking)
4. `docs/UI_Guidelines.md` — §6 for the Book Time Off modal and the For Action list's layout conventions
5. `docs/adr/0009` (this phase's direct predecessor: the `people`↔`timeoff` event-based wiring and the `PeopleFacade`/combined-transaction conventions — both apply again here) and `docs/adr/0007` (htmx admin-CRUD transaction pattern, needed again for the approval actions)

## Already in place — do not redo

- **Everything from 1.1–1.5**: full tenancy/auth/org/people stack; `leave_type`, `public_holiday`, `leave_balance` tables with RLS + `@TenantId`; `timeoff.BalanceService` (grant logic — do not touch its grant paths, this phase only *reads* `leave_balance` and increments `used`); `timeoff.LeaveTypeService`/`PublicHolidayService` admin CRUD; Home dashboard "Book Time Off" balance cards (currently display-only — this phase is what makes them actionable); `people.EmployeeHiredEvent` + `timeoff.EmployeeHiredEventListener` (the event pattern to copy again, see below).
- **`PeopleFacade`** — already exposes `listActiveEmployeeHireInfo()`/`requireEmployeeHireInfo()`. This phase will likely need employee→manager lookups for approver resolution; check whether `people.Employee.manager()` is reachable through the existing facade surface or needs a new method before assuming a gap.
- **`timeoff` package and its cross-module wiring pattern (ADR 0009)** — `people` must continue to never import `timeoff`. If this phase needs `people.EmployeeService.applyTermination` to cancel future leave requests (PRD §14.4 — the carried-forward gap from 1.4/1.5), do it the same way `EmployeeHiredEvent` did: `people` publishes an event, `timeoff` listens. Do not add a direct `people → timeoff` call — that reopens the exact package cycle ADR 0009 avoided.
- **`AnnualGrantJob` fan-out-over-tenants pattern** (`timeoff.AnnualGrantJob`, itself copied from `people.EmployeeTerminationJob`) — not directly reused here (approvals are per-request, not a scheduled job), but the `TenantContext.set/clear` per-tenant-iteration shape is the reference if this phase ends up needing any cross-tenant scheduled job (e.g., auto-expiring stale pending requests, if the PRD calls for it — check before assuming it does).
- **`email_outbox` + `EmailDispatcher`** (`notifications.system`) — the durable-outbox pattern this phase's approval/rejection/cancellation emails must use. `EmailOutbox`/`EmailDispatcher`/the retry-backoff shape are all already built; this phase adds new email templates/trigger points, not new outbox infrastructure.
- **htmx + Alpine, offcanvas/modal patterns** — `admin/leave-types.html`, `admin/holidays.html`, `people/profile.html`'s tab-swap pattern are the freshest reference implementations. The Book Time Off modal and the For Action approve/reject modal are new UI shapes (not another list+offcanvas CRUD screen) — check `docs/UI_Guidelines.md` §6 for whether a modal-based flow has its own established pattern yet, or whether this phase is establishing it.
- **Combined write-then-read `@Transactional` service methods (ADR 0007, reaffirmed by ADR 0009)** — every new controller mutation (book, approve, reject, cancel) needs this shape. Do not let the controller compose a write call and a separate read call itself.

## Remaining Phase 1.6 work

### Schema + entities

- Flyway migration for `leave_request` per PRD §21 (reproduced above in this file's git history / the PRD itself). Tenant-scoped: RLS template + `@TenantId`, `rls_probe` grant added to the test isolation-probe migration (following `V202608141000`'s pattern from 1.5 — one more `GRANT SELECT` line).
- `LeaveRequest` entity: `employeeId` as a plain `UUID` (not a JPA relation), matching `LeaveBalance.employeeId`'s established convention (ADR-adjacent reasoning: `timeoff` doesn't hold a `people.Employee` JPA reference); `leaveType` as a real `@ManyToOne` (same-package, like `LeaveBalance.leaveType`); `deciderId`/`cancelledBy` as plain `UUID` referencing `app_user` (cross-module, `identity` package — same plain-id convention).
- Status as a `sealed interface` or `enum` state machine (`PENDING`/`APPROVED`/`REJECTED`/`CANCELLED`) per CLAUDE.md §10's recipe — a `LeaveRequestStateMachine` class, unit-tested exhaustively for every legal/illegal transition.
- Consider `@Version` optimistic locking on `LeaveBalance` now (CURRENT_PHASE.md's 1.5 predecessor deferred it exactly to this phase, since concurrent approve/cancel racing on `used` is the first real case that needs it — decide and record if this ships or if the risk is accepted for MVP scale).

### Backend

- `LeaveDurationCalculator` — PRD §12.3's pseudocode, implemented exactly, unit-tested exhaustively (weekend/holiday skipping, half-day edge cases, same-day-both-halves rejection, tenant-specific weekend days from `TenantConfig`/`Tenant.weekendDays`).
- `LeaveRequestService.book(...)` — balance check (sufficient `remaining()` on the current year's `LeaveBalance`), compute duration, create `PENDING`, resolve approver (`employee.manager()` → fallback to any tenant Admin — check PRD §12.4 step 2 for the exact fallback rule), enqueue approver notification via `email_outbox`, log for the interim audit trail (real `audit_entry` is still Phase 1.11).
- `LeaveRequestService.approve/reject/cancel(...)` — state machine transitions + `leave_balance.used` adjustment (approved: `+= duration`; rejected: no change; cancelled: `-= duration` if it was approved). No self-approval, ever (PRD BR-6) — enforce in the service, not just the UI.
- Approver resolution needs a manager→approver read from `people` — go through `PeopleFacade`, adding a method if the current surface doesn't cover it (see "Already in place" above).

### Frontend

- Book Time Off modal (top bar CTA + Home dashboard cards) — type selector scoped to active leave types with remaining balance, date range, half-day toggles, live duration preview, note, submit.
- Profile → Time Off tab — now real: budget cards (can likely reuse `HomeController.BalanceCard`'s shape) + request history table. This is the tab `people/profile.html`'s comment has been marking as omitted since Phase 1.3.
- For Action page — pending requests scoped to the signed-in user's approval authority (Manager: direct + indirect reports per PRD §26; Admin: any). Approve/reject modal with an optional note.
- Sidebar "For Action" nav item currently renders `disabled` — this is the phase that makes it real (see `fragments/sidebar.html`).

### Tests

- Exhaustive unit tests on `LeaveDurationCalculator` (CLAUDE.md §8 names this explicitly): DST transitions, year boundaries, tenant-specific weekend variants, every half-day combination from PRD §12.3's pseudocode including the invalid-combination rejection.
- Integration: book → approve → balance debited → email queued (assert the outbox row, not a real send). Book → reject → balance unchanged. Approve → cancel → balance credited back.
- Tenant isolation test for `leave_request`, mirroring `TimeoffTenantIsolationTest`'s 1.5 shape.
- RBAC: booking is self-service for all three roles; approval is Manager-of-reports-only or Admin-any; a Manager attempting to approve a non-report's request must be denied; self-approval must be denied even for an Admin who is also the requester.
- E2E: the PRD's own worked example (or equivalent) — Employee books N days, Manager approves, Employee sees APPROVED in history — mirroring `EmployeeLifecycleE2ETest`/`LeaveConfigE2ETest`'s helper-reuse convention.

## Definition of Done for Phase 1.6

- Full happy path works end-to-end through the browser (book → approve → balance updates → email queued).
- Insufficient balance blocks booking with a clear error, not a generic 500/validation dump.
- Cancelling an approved request returns the balance.
- No self-approval possible, by construction, proven by test — not by review.
- `leave_request`: RLS + `@TenantId` + a passing tenant-isolation test.
- `./mvnw verify` green, PMD at zero violations, ArchUnit green with no new exemptions (the `people`↔`timeoff` acyclic boundary from ADR 0009 must still hold if this phase closes the termination-cascade gap).

## Not in scope for Phase 1.6 — do not start any of this

- Real persisted `audit_entry` — still Phase 1.11. Continue the interim SLF4J-logging convention.
- Auto-expiring/auto-escalating stale pending requests, unless the PRD explicitly calls for it — check before building it as a "seems obviously needed" addition (CLAUDE.md §11: no speculative features).
- Team/org-wide leave calendar view (PRD §26 lists "View team calendar" as in scope for all roles, but check whether that's this phase's job or a later reporting-phase concern before building it here).
- Employee custom fields, grid/tree People views — still Phase 2, unrelated to this phase anyway.

## Carried forward — open items

These were accepted deviations, not oversights. Do not silently "fix" them; they have owners.

- **Lockout is keyed on the user, not (email + IP)** — blocked on `login_audit`, Phase 1.11. ADR 0006 decision B.
- **Password-reset enumeration safety is response-shape only**, not constant-time. ADR 0006 decision E.
- **No common-password blocklist.** ADR 0006 decision F.
- **Tenant primary colour not yet injected into `--bs-primary`.** Phase 1.10 owns tenant branding.
- **Peer-to-peer profile viewing (PRD §26 "View peer profile 🔒 basic") is not implemented.** Deferred since Phase 1.4; still not this phase's job.
- **Termination's "cancel future leave requests" (PRD §14.4) is a no-op.** This is finally this phase's job — `leave_request` now exists. Close it via the event pattern described above under "Already in place," not a direct `people → timeoff` call.
- **`EmployeeTerminationJob`/`AnnualGrantJob` process tenants serially, not in parallel.** Still fine at current scale (CLAUDE.md §11) — revisit only with a benchmark showing a problem.
- **Manual leave-balance adjustment (`BalanceService.adjustManually`, added in 1.5) has no dedicated admin screen**, by design — it's a backend capability tested via RBAC only. Revisit if a real need for an adjustment UI surfaces; not assumed needed by this phase.
- **`AdminEmployeeController`'s write-then-separate-read transaction shape has an open correctness question** (ADR 0009's Context/Consequences) — not investigated or fixed in 1.5. If this phase adds any new mutation to `AdminEmployeeController` itself, follow `AdminLeaveController`'s combined-transaction shape instead of extending the unconfirmed pattern further.

## When you finish

1. Confirm every DoD item above with a specific test or command result — do not claim done from vibes.
2. Update this file to whatever sub-phase comes next (this file's 1.5 → 1.6 update is the template).
3. Commit `phase-1.6-leave-approvals` and open a PR against `main`.
4. Do not start the next phase in the same session.
