# Runbook: AutoSpotting stuck operation

Operator runbook for a stuck AutoSpotting operation — an ENABLE, REVERT, FALLBACK or other op that
has not reached a terminal state within its expected window (design-ux-ops.md §4.4, §6.4, §10.5;
design-core.md §5.3, §6.6).

Audience: XamOps platform operators (super admin or XamOps admin). Login-As sessions are read-only.

## 1. Trigger

- The **Stuck operation** alarm fires: `autospotting.ops.stuck` Max > 0 for 5 minutes (Page,
  `cloudformation/xamops-autospotting-monitoring.yaml`).
- A group has sat in APPLYING or REVERTING longer than the stuck threshold
  (`autospotting.reconciler.stuck-threshold`, default 2 h) or past its operation `deadline_at`
  (design-core.md §5.3, AC-SPN-06).
- A customer or support reports a conversion that "never finishes".

## 2. Diagnosis

- **List stuck operations:**
  ```
  GET /api/xamops/autospotting-admin/operations?waitReason=STUCK
  GET /api/xamops/autospotting-admin/operations?waitReason=STUCK&provider=aws
  ```
  Each row carries the `operationId`, `groupId`, `kind`, `status`, `waitReason` and timing. Note the
  `operationId` and `groupId`.

- **Read the operation (customer-scoped async view) for its current step and progress:**
  ```
  GET /api/autospotting/v2/accounts/{accountId}/operations/{operationId}
  ```

- **Logs:** filter the AutoSpotting JSON logs by `operationId` (and `groupId`). The `AutoSpottingMdc`
  keys are on every line (design-ux-ops.md §8), so one `operationId` filter shows the whole op,
  including the cloud request id logged next to it on each cloud call.

- **Metrics:** `autospotting.ops.stuck` (per provider), `autospotting.reconcile.staleness_seconds`
  and `autospotting.operation.duration` tell you whether the reconciler is sweeping and how long ops
  are taking. If `autospotting.reconcile.last_success_age` is high, the reconciler itself is behind —
  work that one first (see below).

- **Common causes:** a cloud call waiting on capacity (op parked `WAITING(CLOUD_PROGRESS)`); a lease
  that was lost and not re-acquired (check `autospotting.lease.failures`); the switch store unreadable
  (everything fails closed); a provider incident (look at `autospotting.cloud.errors`).

## 3. Actions

- **If the reconciler is behind** (`reconcile.last_success_age` high, "Reconciler not running"
  alarm): the stuck op may just need the sweep to catch up. Confirm the scheduler is running and the
  job lease is not stuck on a dead instance; once sweeps resume, the op is re-driven or marked.

- **If a specific group is wedged and must stop changing**, pause it so no further automatic action is
  taken while you investigate:
  ```
  POST /api/xamops/autospotting-admin/groups/{groupId}/pause
  Idempotency-Key: <uuid>
  { "reason": "Investigating stuck ENABLE op <operationId>" }
  ```

- **If all change must stop** (provider-wide incident), engage `CONVERSIONS:{cloud}` or `FREEZE` per
  the switches runbook (`docs/runbooks/autospotting/switches-and-freeze.md`).

- **Resume the group** once the cause is resolved:
  ```
  POST /api/xamops/autospotting-admin/groups/{groupId}/resume
  Idempotency-Key: <uuid>
  { "reason": "Cause resolved; resuming group" }
  ```
  Every non-GET needs an `Idempotency-Key` and a 1-to-500-character `reason`, and writes an `OPERATOR`
  audit row (design-core.md §6.6).

Do not hand-edit the `spot_operation` or `spot_group` rows in the database. The operator API and the
reconciler are the only safe writers (fenced on the group lease). A direct DB edit can break the
fence invariant and strand the group.

## 4. Verification

- Re-read `GET /operations?waitReason=STUCK` and confirm the op is no longer listed (it reached a
  terminal state, or is now progressing with a normal `waitReason`).
- `autospotting.ops.stuck` returns to 0 and the Stuck-operation alarm clears.
- The group's state is the expected one (ACTIVE, FALLBACK, REVERTED, or a deliberate PAUSED while you
  investigate), read via the v2 operation/config endpoints.
- The audit trail for the `operationId`/`groupId` shows your pause/resume and the terminal transition.

## 5. Rollback

- A `pause` is undone by `resume` with a new `Idempotency-Key`; a `resume` that was premature is
  undone by `pause`. No deploy is needed.
- If a switch was engaged as part of the response, release it per the switches runbook once the
  incident is over.

## 6. Escalation

- A stuck op that will not clear after the reconciler catches up and the group is paused/resumed, or
  repeated lease store errors, pages on-call (`xamops-autospotting-oncall-{env}`).
- A group in FAILED needs a human by definition (the "Group needs a human" Page alarm); follow the
  compensation/recovery guidance with the on-call engineer. Keep the `operationId` and `groupId` for
  the escalation.

## 7. UAT walk-through (recorded for the exit report)

Read a stuck operation through `GET /api/xamops/autospotting-admin/operations?waitReason=STUCK` in
UAT and confirm the fields needed to diagnose it. The walk-through result is recorded in the Phase 10
exit report (plan-phase-10.md 10.5, 10.6).
