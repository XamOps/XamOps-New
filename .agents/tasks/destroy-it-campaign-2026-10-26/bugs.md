# Destroy-It Campaign — Bugs & Findings

Workflow: wf_d2ab28b44080e183
Cluster under test: `arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-1` (UAT, account 102227938259)
Image under test: `ghcr.io/xamops/xamops-autoscaler@sha256:5897349fae3d6607c2e019098b736bc0ec44f22887afb29f72f44b1dcfe3897d`
(main HEAD autoscaler build; = commits b8827ad62 / 91d505a7c / eb629c7cb — autoscaler unchanged across them)

---

## BUG-RBAC-001 — agent ClusterRole on the live cluster lacks `persistentvolumes` (and CNPG `clusters`): evictor + consolidation non-functional — **HIGH**

**Impact:** With evictor-live / consolidation-actions gated LIVE (mode=full, DRY_RUN=false), every
automation cycle the agent's fit-snapshot and consolidation collect threw:
```
ERROR: evictor: fit snapshot: list persistentvolumes: persistentvolumes is forbidden:
  User "system:serviceaccount:xamops-system:xamops-automation" cannot list resource
  "persistentvolumes" in API group "" at the cluster scope
WARN: consolidation collect: list persistentvolumes: persistentvolumes is forbidden: ...
```
So evictor and consolidation could not complete ANY action — the stateful/zone protection
(FEAT-005) resolves pod→PVC→PV→zone, and with PVs forbidden the fit simulator aborts. The two
controllers meant to exercise the ng-stateful-1 DB fail-closed protection were effectively dead.

**Forbidden resources observed (exact), BOTH confirmed live this run:**
1. `persistentvolumes` — `cannot list resource "persistentvolumes" in API group "" at the cluster scope`
   (evictor fit-snapshot + consolidation collect).
2. `clusters.postgresql.cnpg.io` — `cannot list resource "clusters" in API group "postgresql.cnpg.io"
   at the cluster scope` (rebalancer + consolidation collect; the CNPG DB-awareness that distinguishes
   Postgres primary vs replica/standby). This surfaced only AFTER the PV grant was added — the collect
   had been aborting at the PV list first.

**Root cause (CORRECTED — this IS a manifest-content bug, not only rollout drift):** the SHIPPED repo
manifest `xamops-autoscaler/manifests/xamops-autoscaler-complete.yaml` was MISSING both rules. The
first read rule (`apiGroups:[""]`) listed `persistentvolumeclaims` but NOT `persistentvolumes`, and
there was NO `postgresql.cnpg.io` rule anywhere in the file. (An earlier correction note in this file
claiming the manifest "already includes both" was WRONG: applying the unmodified shipped manifest to
the live cluster this run actually DROPPED a previously live-patched cnpg grant and re-broke the
collect, proving the manifest lacked it.) The CNPG CRDs are installed on the cluster
(`clusters.postgresql.cnpg.io` present), so the shipped agent lists them every cycle.

**Fix applied in this run (BOTH repo + live, per owner Option A):**
- Repo manifest edited to add `persistentvolumes` to the core read rule and a new read-only rule
  `apiGroups:["postgresql.cnpg.io"] resources:["clusters"] verbs:[get,list,watch]`. Committed so the
  fix is not lost on the next CI deploy. File:lines after edit:
  - `xamops-autoscaler/manifests/xamops-autoscaler-complete.yaml` — `persistentvolumes` in the
    `apiGroups:[""]` read rule, and the new `postgresql.cnpg.io/clusters` rule directly below it.
- Live cluster: `kubectl apply` of just the `ClusterRole/xamops-automation` object (RBAC-only, no
  workload change), context-pinned to xamops-test-1, account asserted 102227938259.

Scope justification: granted EXACTLY the two read resources the shipped code lists (get/list/watch),
nothing broader. No MySQL/Mongo/Redis operator CRDs are installed on this cluster (checked), so no
further DB-operator grants are needed; those DBs in the headline test are plain StatefulSets/Deployments
covered by existing grants + the protectStatefulSets guard.

**Repro:**
1. Deploy the agent from the shipped manifest (pre-fix) and enable evictor-live + consolidation-actions,
   mode=full, DRY_RUN=false.
2. Watch agent logs → `persistentvolumes is forbidden` every cycle; after granting PV,
   `clusters.postgresql.cnpg.io is forbidden` every cycle; no drain/consolidation collect completes.

**Verification after fix:** 0 `forbidden` lines in a full poll cycle; consolidation collect now
completes (`Consolidation (look-only): 0 empty, 0 drain, 5 blocked, 4 kept of 9 node(s)`), and all 5
acting gates still read live (rebalancer/evictor/cordon/idle-drain/interruption=live,
consolidation-actions=live). `auth can-i list persistentvolumes` = yes, `auth can-i list
clusters.postgresql.cnpg.io` = yes for the agent SA.

**Secondary follow-up (still valid):** the agent upgrade path should re-apply the ClusterRole alongside
every image roll, so even with a correct manifest an image-only upgrade can't leave RBAC behind.

---

## BUG-GATE-002 — `rightsizer-apply` readiness reads ready but there is no live AGENT path — **MEDIUM**

The `rightsizer-apply` readiness key maps, in the UI/capabilities, to an appliable rightsizer. But the
AGENT's Go rightsizer is **retired**: `K8sAutomationPolicyNormalizer` serves
`workloadRightsizerPolicy.enabled=false` regardless of readiness, and the write endpoint is always
refused (RETIRED_RIGHTSIZER_MESSAGE). The real apply is the backend Java vertical engine, not the
agent. Gate log shows `rightsizer=off / apply=off (workloadRightsizerPolicy.enabled=false)` even at
full-live. **Finding only — not chased this run.** Honest feature-table entry: key ready, no live agent path.

## BUG-GATE-003 — `hpa-apply` readiness ready but HPA manager is analysis-only, no policy write route — **MEDIUM**

The agent `HPAManager` gate is analysis-only by design (returns Analyse, "the HPA manager never
mutates"), and there is NO `PUT …/hpa` policy route to set `hpaManagerPolicy.enabled=true`, so the
served bundle keeps it false and the gate reads `hpa=off`. Backend HPA apply is a separate path.
**Finding only.** Honest entry: key ready, no live agent path on this build.

## BUG-GATE-004 — `vpa-apply` readiness ready but no `vpaManagerPolicy` writer — **MEDIUM**

`ENABLE_VPA_MANAGER=true` and `vpa-apply` ready, but the VPA manager needs
`vpaManagerPolicy.enabled=true` and there is no customer-reachable write route to set it, so VPA never
acts. No named VPA gate appears in the agent's gate-decision map. **Finding only.** Honest entry: key
ready, no live agent path on this build.

---

## Accurate posture headline

**6 agent gates LIVE** (rebalancer, evictor, cordon, idle-drain, interruption, consolidation-actions)
**+ 3 readiness keys READY-but-no-live-agent-path** (rightsizer-apply, hpa-apply, vpa-apply, per
BUG-GATE-002/003/004). NOT "8/8 agent-live". All 8 readiness keys ARE ready at the backend and
DRY_RUN=false; the three gaps are design/missing-writer issues recorded above.

---
## OBS-DB-001 — DB-safety refusal reason-code is a blanket `db-emptydir` (not role-aware) — **LOW / observation**
HEADLINE DB-safety test (I15) during the attack phase **PASSED**: when rebalancer/node-optimizer tried to
disrupt the ng-stateful-1 nodes hosting the fixtures, **every** DB pod was **refused with a recorded reason**
and **none was evicted** (zero `Evicted`/preempt events; DB pod ages unchanged). Evidence:
`setup/attack/I15-I16-headline.md` + `setup/attack/drain-refusals-raw.log`.

Observation (does NOT change the PASS): the recorded reason is a blanket **`db-emptydir`** even for the
PVC-backed CNPG **primary** (pg-cnpg-2), CNPG replica (pg-cnpg-1), MySQL (mysql-0) and Mongo RS — which are
NOT emptyDir — and DB *clients* get `db-role-unknown` (correct: unknown ⇒ refuse). Protection is correct;
the reason is not role-aware (no `db-primary` / `db-sync-standby` / `db-pvc` code). Matches the known gaps
**G2/G6** in `.agents/tasks/stateful-db-fixtures-2026-10-24/report.md` ("refusal reasons are generic";
"primary and sync standby protected only by the blanket PVC refusal"). Recommend a role-aware reason code
so operators can see *why* a DB pod was protected. **Finding/observation only — not a headline-breaker.**

---

## BUG-SCALE-002 — fleet cannot reach ~200 pods; hard ceiling ~178 (VPC-CNI 17-pod/node + CPU-request exhaustion) — **LOW** (capacity/observability)

**Repro (Phase 2):** context-pinned xamops-test-1; scale 55 `fleet-*` deploys 1→2 replicas under k6 load.
Fleet rises 145 → **178 Running, 23 Pending** and stalls. Scheduler:
```
0/31 nodes are available: 22 Insufficient cpu, 8-11 Too many pods,
4 node(s) had untolerated taint(s), 1 node(s) were unschedulable;
preemption: 26 No preemption victims found, 5 Preemption is not helpful.
```
**Impact:** Target of ~200 schedulable workloads is INFEASIBLE under the campaign guardrail
(ng-ondemand-1 capped at 26, no node growth). Dual bind: t3.medium VPC-CNI 17-pod IP ceiling
(prefix-delegation OFF) AND over-provisioned CPU requests on the `over`/`over-heavy` profiles.
NOT a control-plane or serving failure — the 145-pod serving fleet held 0.00% req-failed, p95 7.84ms
through the churn, and the agent correctly did NOT grow the capped nodegroup.
**File/where:** cluster capacity + fleet profile requests (fixtures), not agent code. VPC-CNI
`ENABLE_PREFIX_DELEGATION` off; fleet deploy CPU requests on profiles 001/002/007/008/014-style (`over`).
**Priority:** LOW — expected capacity limit given the guardrail; fix is right-sizing requests or
enabling prefix-delegation, not an agent bug.

---

## OPS-CHAOS-003 — orphaned chaos objects from a prior run (one ACTIVELY injecting) — **LOW** (hygiene)

**Repro (pre-Phase-4):** `kubectl get stresschaos,networkchaos,podchaos -A` on xamops-test-1 showed 3
leftovers in ns stress: `StressChaos/fleet-cpu-stress` (Stop/AllRecovered), `NetworkChaos/fleet-net-latency`
(Stop), and **`PodChaos/fleet-pod-kill` ACTIVE** (`pod-kill`, fixed-percent 15, dur 8m, selector
app=fleet-http; AllInjected=True/AllRecovered=False) — continuously killing ~15% of fleet pods.
**Impact:** stray pod restarts that could be misattributed to the tool; an active experiment left running
across runs. **Fix/where:** campaign teardown must delete-and-verify-gone every chaos object + Schedule
(a lingering Schedule also crashes downstream tooling). All 3 deleted; inventory now ALL CLEAR.
**Priority:** LOW (operational hygiene, not a product bug), but verify-gone is now mandatory per phase.

## GATE-002 / GATE-003 / GATE-004 — rightsizer / hpa / vpa not live BY DESIGN (confirmed, not bugs)

**GATE-002 rightsizer + rightsizer-apply:** gate reads `off`; Go rightsizer retired; PUT workload-rightsizer
→ 403 K8S_CONTROLLER_NOT_READY. **No live OOM/throttle auto-revert path exists** (verified in Phase 5 —
nothing to exercise, recorded honestly rather than simulated). **GATE-003 hpa-manager:** analysis-only,
no live PUT route. **GATE-004 vpa:** no acting gate series in this agent image. All three are design
verdicts, not breaking points — forcing live would require hand-editing Postgres / bypassing the API
(forbidden). Priority: N/A (documented design).

## BUG-API-005 — `GET` on a PUT-only policy route returns 500 instead of 405 — **LOW** (API hygiene)

**Repro (Phase 11):** `GET https://uat.xamops.com/api/eks/autoscaling/xamops-test-1/consolidation` →
**HTTP 500** `{"message":"An unexpected error occurred: Request method 'GET' is not supported", ...}`.
The route is PUT-only; an unsupported HTTP method should return **405 Method Not Allowed** (a 4xx client
error), not a 500 (which signals a server fault and trips 5xx alerting/SLO budgets).

**Impact:** cosmetic/contract only — no state change, no crash, no data exposure. But a 500 on a plain
method mismatch pollutes error-rate metrics and can mask real server faults. Spring's default
`HttpRequestMethodNotSupportedException` maps to 405; here it is being caught and re-wrapped as a generic
500 "unexpected error".

**Fix:** let `HttpRequestMethodNotSupportedException` surface as 405 (or add an explicit handler) on the
`/api/eks/autoscaling/{clusterName}/*` policy controllers. **Finding only — not a breaking point.**

**NOTE — fuzz tolerance itself PASSED:** 18 adversarial policy PUTs (SQLi/XSS/`__proto__`/huge/negative/
wrong-type/array-as-object/path-traversal) were ALL rejected with HTTP 400 `K8S_INVALID_REQUEST` (gateway
502 on the traversal path); zero 5xx on fuzz bodies; the live policy was unchanged (all 6 gates still live
afterward). The adversarial K8s workload (16cpu/64Gi limits, grace 999999s, injection annotations) left the
agent 2/2 with 0 restarts and no collect panic. Only the GET-method status class is wrong.

---

## BUG-OBS-006 — `xamops_orphans_recovered_total` is unwired (never increments) — **LOW** (observability)

**Repro (Phase 17b):** induced a real half-drained orphan (node annotation `xamops.io/drain-state=draining`
on a cordoned xamops-owned node), SIGKILLed the leader repeatedly. The new leader's `RecoverOrphans`
correctly reconciled the node (`drain-state: draining → aborted`, uncordoned — verified on the node), yet
the exposed metric `xamops_orphans_recovered_total` stayed **0** before and after.

**Root cause:** the metric name appears at `/metrics` but is **not incremented anywhere** in the
autoscaler source. `RecoverOrphans` (`internal/drain/executor.go:731-766`) records each recovery only
through the generic action-record path `telemetry.Finish(ControllerExecutor, "recover-orphan", …)`; there
is no `.Inc()` on a backing counter named `orphans_recovered_total`. `grep -rn orphans_recovered` across
`xamops-autoscaler/` finds no definition/registration/increment.

**Impact:** operators (and dashboards/alerts) watching `xamops_orphans_recovered_total` will see 0 even
when the agent is actively recovering orphaned drains after crashes — a blind spot for a safety-relevant
self-heal path. **No safety impact**: the recovery itself works and is observable via the node annotation
flip and the action record.

**Fix:** register a counter and `.Inc()` it on each successful `RecoverOrphans` action (and ideally a
`…_failed_total` on the failure branch). **Finding only — the orphan-recovery behavior PASSED.**

---

## NOTE-PHASE15 — webhook-siege NOT EXERCISED (no admission webhook exists)

Verified live on xamops-test-1: `kubectl get validatingwebhookconfigurations` and
`mutatingwebhookconfigurations` show **no XamOps webhook** (only chaos-mesh/cnpg), and there is no XamOps
webhook Service. The agent acts as a controller via the Eviction/drain API and the backend policy API —
there is no admission webhook to siege. Phase 15 recorded as **not-exercised** with this reason, per the
step instruction. Not a bug.

---

## FIS-OK — real spot interruption + node replacement + DB protection all held (Phase 7, NOT a bug)

Recorded for completeness: FIS `send-spot-instance-interruptions` on the single ng-spot-1 instance caused a
genuine terminate→replace with 0 request failures (k6 0/76126) and 0 DB evictions; `interruption` gate live.
No defect found. The only operational note: no reusable least-priv FIS execution role shipped in prep — one
was created scoped to the exact instance ARN and deleted in teardown.
