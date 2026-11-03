# Runbook: AutoSpotting switches and FREEZE

Operator runbook for pausing and resuming AutoSpotting activity at runtime, without a deploy
(design-ux-ops.md §6.3, §10.5; design-core.md §6.3, §6.6). Covers the engage/release of the global,
per-cloud, conversions and FREEZE switches through the operator API.

Audience: XamOps platform operators (super admin or XamOps admin). Login-As (impersonated) sessions
can read but never write these switches (PO-14, design-core.md §6.6).

## 1. Trigger

Engage a switch when you need to stop some or all AutoSpotting activity immediately:

- a provider incident (AWS or GCP) is causing failed conversions or fallbacks;
- a bad deploy or a suspected bug is driving groups into DEGRADED/FAILED;
- an operator wants to hold all change (FREEZE) during an investigation or a maintenance window;
- conversions must be stopped for one cloud while reads and health keep running.

Release the switch once the cause is resolved and you have confirmed the system is healthy.

## 2. Switch model (what each scope does)

The effective state of a switch is `deployed property AND NOT db-engaged` (design-core.md §6.1). The
operator API only toggles the DB half; it can tighten but never loosen past the deployed property.

| Scope key | Effect when engaged | Error code callers see |
|---|---|---|
| `GLOBAL` | All AutoSpotting mutations blocked for every cloud and account | `AS_DISABLED` ("GLOBAL") |
| `CLOUD:aws` / `CLOUD:gcp` | All mutations for that cloud blocked | `AS_DISABLED` ("CLOUD") |
| `CONVERSIONS:aws` / `CONVERSIONS:gcp` | New conversions for that cloud blocked; health, fallback and revert keep running | `AS_CONVERSIONS_PAUSED` ("CONVERSIONS") |
| `ACCOUNT:{cloudAccountId}` | All mutations for one account blocked | `AS_ACCOUNT_PAUSED` ("ACCOUNT") |
| `FREEZE` | Every in-flight and new mutation paused, SAFETY ops included; the hardest stop | `AS_FROZEN` ("FREEZE") |

FREEZE and an unreadable switch store pause even SAFETY operations (compensation, fallback); the
other scopes let SAFETY work finish (design-core.md §6.3, `ActionGate`).

## 3. Diagnosis

Before changing a switch, read the current state and the in-flight work.

- **Read all switches and their versions:**
  ```
  GET /api/xamops/autospotting-admin/switches
  ```
  Each row shows the deployed `property`, the DB `engaged` flag, the effective value, the `version`
  (needed for the write), and the last operator `reason`. Note the `version` of the scope you intend
  to change.

- **See what is currently running or waiting:**
  ```
  GET /api/xamops/autospotting-admin/operations
  GET /api/xamops/autospotting-admin/operations?provider=aws
  ```

- **Logs:** filter the AutoSpotting JSON logs by the switch name or `operationId`. The
  `AutoSpottingMdc` keys (`operationId`, `groupId`, `provider`, `cloudAccountId`, `requestId`, `job`)
  are on every AutoSpotting log line (design-ux-ops.md §8).

- **Metrics:** `autospotting.job.skipped{reason=switch}` rises while a scope is engaged; the alarms in
  `cloudformation/xamops-autospotting-monitoring.yaml` fire on FAILED groups, stuck ops and lease
  store errors (design-ux-ops.md §6.4).

## 4. Actions

Every write needs an `Idempotency-Key` header and a 1-to-500-character `reason`. The `expectedVersion`
in the body must match the `version` you just read (optimistic concurrency; a mismatch is 409
`SWITCH_VERSION_MISMATCH`).

- **Pause conversions for one cloud (AWS):**
  ```
  PUT /api/xamops/autospotting-admin/switches/CONVERSIONS:aws
  Idempotency-Key: <uuid>
  { "engaged": true, "expectedVersion": <v>, "reason": "AWS capacity incident; pausing new conversions" }
  ```
  Health, fallback and revert keep running; only new ENABLE/ADOPT/CONFIG_APPLY conversions are blocked.

- **FREEZE everything:**
  ```
  PUT /api/xamops/autospotting-admin/switches/FREEZE
  Idempotency-Key: <uuid>
  { "engaged": true, "expectedVersion": <v>, "reason": "Investigating drift spike; freezing all change" }
  ```

- **Release a switch** (e.g. lift the conversions pause or FREEZE): the same call with
  `"engaged": false` and the current `expectedVersion`.

Each change writes an `OPERATOR` audit row with the before and after, in the same transaction
(design-core.md §6.6).

## 5. Verification

- Re-read `GET /switches` and confirm the scope's `engaged` flag and `version` changed as expected.
- For an engage: confirm new work for the scope is refused with the right code (§2) — e.g. a preview
  for a paused cloud returns `AS_CONVERSIONS_PAUSED`.
- For a release: confirm a new preview/apply for the scope is accepted again, and that
  `autospotting.job.skipped{reason=switch}` stops rising.
- Confirm the audit row is present for your change (operator id and reason).

## 6. Rollback

Switches are themselves the rollback control. To undo an engage, release the same scope with the
current `expectedVersion`. If a release is wrong, re-engage. No deploy is needed. If the switch store
cannot be read at all, callers fail closed (every mutation is paused as if FREEZE were on); fix the
database connectivity rather than forcing a switch.

## 7. Escalation

- Lease store errors (`autospotting.lease.failures{reason=store_error}`) or an unreadable switch store
  page on-call (design-ux-ops.md §6.4). Escalate to the on-call engineer on the
  `xamops-autospotting-oncall-{env}` topic.
- A `SWITCH_VERSION_MISMATCH` that keeps recurring means two operators are racing; coordinate before
  retrying.

## 8. UAT walk-through (recorded for the exit report)

Engage and release `CONVERSIONS:aws`, then engage and release `FREEZE`, through the operator API in
UAT, reading `GET /switches` before and after each change and confirming the audit rows. The
walk-through result is recorded in the Phase 10 exit report (plan-phase-10.md 10.5, 10.6).
