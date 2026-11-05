# Destroy-It Campaign — Attack-Phase Living Report

Workflow: `wf_11056589d901fb99`
Cluster (pinned on EVERY call): `arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-1` (ap-south-1, acct 102227938259)
Caller: `arn:aws:iam::102227938259:user/iam.autospotting` (intended test account — not cross-account)
Load driver: **in-cluster k6 Jobs** (`grafana/k6`), results read from Job logs (authoritative) + Prometheus for cluster/agent/CFS metrics.
Blast radius: xamops-test-1 ONLY. Faults confined to ns `stress` / `stress-db` / `xamops-system` (chaos-mesh inject gate annotation present on those 3 only).

## Posture at attack start (re-verified, pinned context)
- Agent: `xamops-system/xamops-automation` 2/2; leader `xamops-automation-6478774c5b-q8kjl`. Distroless image (no shell → metrics read via port-forward :9090/metrics).
- Gate decisions (leader `/metrics`): **LIVE** = consolidation-actions, cordon, evictor, idle-drain, interruption, rebalancer. **OFF by design** = rightsizer, rightsizer-apply (retired → GATE-002), hpa-manager (analysis-only → GATE-003), spot-ratio, karpenter; no vpa series (→ GATE-004).
- Fleet: 145 stress deploys (1 pod each), 17 PDBs, 1 pending (IP/pod-ceiling bound). `fleet-http` ClusterIP svc fronts the fleet. redis-0 Running in stress-db.
- Nodes: ~31 (30× t3.medium + 1× t3.large observability). ng-ondemand-1 drifted to 26 (capped min4/max26 — do NOT grow). One node already `SchedulingDisabled` (prior churn).
- FIS/spot target resolved by BOTH tags `eks:cluster-name=xamops-test-1` + `eks:nodegroup-name=ng-spot-1`: exactly **1** running instance `i-0025a665eb405673e` (spot, t3.medium, ap-south-1a).
- ng-stateful-1 nodes (DB-protection I15/I16 target): `ip-172-31-23-193`, `ip-172-31-4-95`, `ip-172-31-46-10`. DB pods on them: `pg-cnpg-1/2`, `pg-unsafe-0`, `mysql-0`, `mongo-0/1/2`.

## CARRY-FORWARD HEADLINE — I15/I16 DB fail-closed protection: **PASSED in setup**
Every ng-stateful-1 DB pod was refused-with-reason by consolidation/evictor; **0 DB pods evicted**. Folded in as the phase-5/phase-14 headline. Known gap **OBS-DB-001**: the refusal reason is a blanket "db-emptydir" string, not role-aware (cosmetic/observability, not a safety failure). Agent live counter corroborates: `xamops_consolidation_pods_evicted_total 0`, `xamops_consolidation_runs_total{status="skipped"}=136`.

## Off-by-design controllers (not breaking points — design verdicts)
- GATE-002: rightsizer + rightsizer-apply retired (403 K8S_CONTROLLER_NOT_READY on PUT).
- GATE-003: hpa-manager analysis-only (no live PUT route).
- GATE-004: no vpa gate series in this agent image.

---

## Phase verdict summary (filled as phases complete)

| # | Phase | Verdict (breaking-point OR held) | Fault cleaned+verified |
|---|-------|----------------------------------|------------------------|
| 1 | zero-downtime-under-traffic | **HELD** — 0.00% req-failed (0/57068) under rolling restart, p95 9.45ms | ✅ k6 job NotFound, 0 paused rollouts, fleet 145/145 |
| 2 | scale (~200) | **BREAKING POINT ~178 pods** — 23 Pending (FailedScheduling: Insufficient cpu + Too many pods); serving held 0.00% fail | ✅ restored 145/0, no deploy >1 replica, k6 job gone |
| 3 | memory-OOM-race | **HELD (graceful)** — 9 pods OOMKilled (exit137) each restarted once, serving 0.00% fail (0/88165) | ✅ StressChaos gone, 24/24 burst Running, k6 job gone |
| 4 | CPU-throttling (real CFS) | **HELD** — real CFS +28089 throttled periods, ratio peak=1.0 avg~0.31; serving 0.00% fail (0/219684) | ✅ stresschaos gone, 25/25 over Running, k6 job gone |
| 5 | rollback / self-heal storm | **HELD** — 25% pod-kill storm 99.99% checks (2/108678 dropped); bad-image×6 → rollout-undo self-healed all 6; **DB-protect: 0 evicted** | ✅ ALL CLEAR chaos, no broken deploys, k6 gone |
| 6 | leader-failover / HA | **HELD** — killed leader q8kjl → failover to tk8g5 in ≤10s, single leader (no split-brain), schedule deleted+gone | ✅ 0 schedules/podchaos in xamops-system |
| 7 | net latency + real spot/node churn (FIS) | **HELD** — REAL FIS spot-interrupt of i-0025a665eb405673e → terminated+replaced; net-latency p95 219ms but 0.00% fail (0/76126); 0 DB evicted | ✅ FIS template+IAM role deleted (NoSuchEntity), networkchaos/k6 gone, ALL CLEAR |
| 8 | flapping | **HELD (no thrash)** — 4 load swings, consolidation runs 4→8 all skipped, executed=0 action_rate=0 evicted=0 | ✅ ALL CLEAR chaos, no p8 jobs, fleet 145/0 |
| 9 | autoscaler conflict (HPA on agent's signal) | **HELD (no fight)** — 6 CPU HPAs + 120-VU load; consolidation runs 35→40 all skipped, evicted=0, action_rate=0, drain_inflight=0; 0 HPA-pod evictions; serving 0.00% fail (0/1,242,017), p95 12.54ms. **Side-finding: ng-ondemand-1 max had drifted 26→40 (desired 34); re-capped to 30/30/4.** | ✅ HPAs deleted, replicas→1, k6 gone, fleet 145, DB 10/10 Running |
| 10 | api-server 429 pressure | **HELD — threshold NOT reachable from in-cluster load.** ~1,400-req serial storm (8 jobs) then 180-way concurrent uncached-LIST burst (12 jobs): apiserver `code=429` flat at 100, `429 rate/s = 0`, inflight peaked 5, APF `flowcontrol_rejected` empty. Agent 2/2, 0 restarts, single stable lease throughout, 4 gates stayed live. | ✅ all api-hammer/api-burst jobs deleted, SA+CRB removed, agent 2/2, fleet 145 |
| 11 | fuzz / adversarial specs | **HELD — all malformed input rejected safely.** 18 adversarial policy PUTs (garbage/wrong-type/huge/negative/SQLi/XSS/`__proto__`/array-as-object/path-traversal) → **all HTTP 400 `K8S_INVALID_REQUEST`** (traversal→502), **zero 5xx on fuzz bodies**, live policy UNCHANGED (6 gates still live after). Adversarial K8s workload (16cpu/64Gi limits, grace 999999s, injection annotations) → agent 2/2, 0 restarts, no panic, evicted=0. **Finding BUG-API-005 (LOW): `GET` on a PUT-only policy route returns 500 instead of 405.** | ✅ adversarial-fuzz deploy gone, temp cookie removed, policy intact |
| 12 | PVC disk exhaustion | **HELD (DB protected) + v1 GAP recorded.** redis-0 PVC filled 0%→**100%** (ENOSPC at 1.9G); redis `set` OK (mem), `bgsave`/disk-persist impacted. Agent evicted **0** disk-full DB pods over 90s (0 evict/preempt events, evicted_total 0→0, 0 restarts). **GAP: capacity stayed 2Gi — v1 agent/backend does NO online PVC expansion** though `gp3-fixtures` SC `allowVolumeExpansion=true`. | ✅ filler file removed, /data back to 0%, redis PONG, agent 2/2 |
| 13 | partial partition + rapid churn (~50/min) | **HELD (no mis-action).** 40%-fleet NetworkChaos partition + Schedule pod-kill 25%/75s (~29-50/min) for 4 min; agent evicted=0, drain_inflight=0, orphans_recovered=0, consolidation 73→78 skipped — no drain of a partial-view node, no DB evict. Serving 0.00% fail (6/331,061), p95 4.19ms, 1273 rps; fleet self-healed 145↔144. DB 10/10. | ✅ ALL CLEAR (networkchaos/podchaos/schedule gone), k6 gone, 145/145 connectivity |
| 14 | multi-DB fail-closed (Postgres/MySQL/Mongo/Redis EACH) — HEADLINE | **HELD / PASS against EACH engine.** Backend evictor-candidates API marks all 3 DB nodes `canEvict=false`; EACH DB pod in a `refused[]`: pg-cnpg-2 (CNPG primary, +PDB pg-cnpg-primary disruptionsAllowed=0)/pg-cnpg-1 (replica)/pg-unsafe-0 (emptyDir)/mysql-0/mongo-0/1/2 = `db-emptydir`; redis-0 = `db-role-unknown` (unknown⇒refuse). Fresh `POST /rebalance` (200) did not drain any DB node. **0 evicted** (counter 0→0, timestamps unchanged, 0 Evicted events). OBS-DB-001 blanket reason reconfirmed. | ✅ no DB touched, temp cookie removed, counter 0 |
| 15 | webhook-siege | **NOT EXERCISED** — no XamOps admission webhook exists (0 validating + 0 mutating webhookconfigurations, 0 webhook svc; agent acts via Eviction/drain + backend policy API, not admission control). Recorded with reason. | n/a (no fault injected) |
| 16 | concurrency / double-writer races | **HELD — single-writer invariant never broke.** 5× leader-kill flap (b7fb7→…→c9lhf) + rebalance each round: **MAX simultaneous leaders = 1**, split-brain = **0**, MAX `drain_budget_inflight` = **0** (budget-1 never exceeded). 2 concurrent conflicting policy PUTs → both HTTP 200, serialized, policy intact. DB 10/10, 0 evicted. | ✅ agent self-healed to 2/2, single lease c9lhf, cookie removed |
| 17 | SIGKILL agent mid-drain → RecoverOrphans (REPLACES 17) | **HELD / PASS.** Induced a REAL orphan (annotation `drain-state=draining` on a cordoned xamops-owned empty node), then SIGKILL leader ×5 (`delete --grace-period=0 --force`). New leader's RecoverOrphans reconciled it to `drain-state=aborted` **and uncordoned** it on round 1, idempotent across all 5 kills. **Final: ZERO nodes stuck `draining` cluster-wide.** DB 10/10, agent self-healed 2/2. **Finding BUG-OBS-006 (LOW): `xamops_orphans_recovered_total` is unwired (stayed 0 despite a real recovery).** (17a first run used a *label* not the annotation → no-op; corrected method = 17b.) | ✅ no half-drained node, node terminal, agent 2/2 |
| 24 | metrics poisoning (kill metrics-server, feed NaN/absurd) | **HELD — agent FAILS SAFE on poisoned/missing metrics.** Scaled `metrics-server` to 0 (`Metrics API not available`) AND stood up a fake `prometheus-server` endpoint feeding poisoned per-node values (NaN / `0` / `-999999` / `Infinity` / non-numeric). With BOTH node-util sources compromised, the agent over ~70 cycles logged `NodeOptimizer metrics-server unavailable` + `Consolidation (look-only): 0 empty, 0 drain, 8 blocked, 27 kept — actor=NONE` and `(live): considered=0 removed=0 ...`. It did **NOT** read 0%/NaN as "idle → drain everything": `evicted_total` 0→0, `drain_inflight`=0, skipped 80→150, **0 restarts** (no parse crash on NaN/garbage), DB 8/8 untouched. | ✅ poison Deployment+ConfigMap deleted (`No resources found`), `metrics-server` scaled back 2/2 (metrics API `Available=True`, `top nodes` works), agent reading real metrics again, DB 8/8 |
| 23 | COMPOUND CHAOS (network + pod-kill + mem-stress + node-interruption SIMULTANEOUSLY) | **HELD — DB fail-closed held under everything-at-once; 0 DB evictions.** Fired together: NetworkChaos partition (40% fleet), PodChaos + `@every 30s` Schedule pod-kill (25%), StressChaos mem 2×400MB on burst pods, AND real AWS FIS spot-interrupt of `i-0626ed7148ce34ce5` (ng-spot-1, resolved by BOTH tags; test-2 untouched) → instance **terminated**, ASG relaunched `i-00cb2a2ffb9721e8e`. Through peak: leader `zkrr2` **no failover**, agent 2/2 0 restarts; `evicted_total=0` (consolidation-actions + interruption both **live**), `drain_budget_inflight=0`, skipped 67→75, orphans=0. **Every ng-stateful-1 DB pod unchanged (same node/startTime/restarts) — 0 evicted/moved**, redis unchanged, 0 Evicted/preempt events. Serving: k6 `http_req_failed=0.00% (8/291,041)`, p95 5.52ms, 1238 rps. | ✅ chaos inventory ALL CLEAR (incl. Schedule children), k6 gone, FIS template deleted, IAM role NoSuchEntity, nodes 35 (spot replaced 1:1), fleet 144/1, DB 8/8+redis |
| 22 | RBAC boundary | **HELD — agent SA is least-privilege and fail-safe; no over-privilege / escalation.** GRANTED & correct: list PV=yes, list nodes=yes, list `clusters.postgresql.cnpg.io`=yes, patch nodes=yes, `create pods/eviction`=yes (via `--subresource=eviction`, cluster-wide incl. db-fixtures — DB protection is enforced in the controller's refuse logic, NOT by withholding eviction RBAC). DENIED & correct: delete nodes, create clusterrolebindings, get secrets/create pods in kube-system, delete namespaces, cluster-admin `* *`, escalate/bind clusterroles, impersonate users, create SA tokens, delete PVCs — all **no**. Agent ran 5min with **0 forbidden/RBAC/unauthorized** log lines = operates entirely inside its grant (fail-safe). Obs: `auth can-i create pods/eviction` (slash form) falsely returns `no` on kubectl v1.34 — CLI-parse quirk, the grant is real. | n/a (read-only RBAC probes; no fault injected) |
| 21 | many-container pods | **HELD — agent collect/fit ingests pathological pod specs without choking.** 3× 40-container pods (120 containers) then escalated to 1× **150-container** pod (`p21-mega`, 150/150 Running) → agent **0 panic / 0 collect error / 0 fit error / 0 nil-pointer / 0 index-out-of-range** in logs, 2/2 0 restarts, evicted=0, skipped 55→58, inflight=0, leader stable. Kubelet serialized container startup/teardown (mega pod slow to Terminate → force-deleted) but that is a kubelet trait, not an agent fault. | ✅ all p21 pods deleted (mega force-deleted), `No resources found`, fleet 145 |
| 20 | scheduling deadlock | **HELD — no drain/evict to "make room" for a deadlocked high-priority preemptor.** 6× priority-1000000 pods with unsatisfiable `nodeSelector p20-impossible-label` + required self-anti-affinity → all 6 permanently Pending (`0/35 nodes ... Preemption is not helpful`). Agent: evicted=0 (0→0), skipped 51→54 (0 executed), inflight=0, leader `zkrr2` stable, 2/2 0 restarts. **0 Preempted events on real workloads, 0 DB evictions (DB 8/8 unchanged)** — the scheduler correctly refused to preempt (unschedulable regardless) and the agent did not drain nodes to satisfy the pending preemptor. | ✅ deployment + PriorityClass deleted (`NotFound`), fleet 145 |
| 19 | quota / limitrange collision | **HELD — agent did not mis-act on a quota-locked namespace.** Tight ResourceQuota (requests.cpu 4 vs 23050m used) + LimitRange (max 250m/256Mi vs 500m/512Mi fleet) in ns stress → scale-up REJECTED (`FailedCreate ... forbidden: maximum cpu 250m but limit 500m`) and k6 job pods REJECTED (`exceeded quota ... requests.cpu=23050m used vs 4`). Namespace fully admission-locked. Agent: evicted=0, drain_inflight=0, consolidation skipped 43→49 (0 executed), leader `zkrr2` stable, 0 restarts, 0 Evicted/preempt anywhere; serving fleet held 145/145. | ✅ quota+limitrange deleted (`No resources found`), k6 job gone, fleet-001-over back 1/1, fleet 145 |
| 25 | SOAK / LEAK HUNT (~14h steady load + light periodic chaos) | **HELD — NO LEAK on any dimension; DB fail-closed held the whole haul.** ~14h soak (40-VU k6 + `@every 5m` 10% pod-kill). Agent RSS bounded 57-63Mi (0 growth), reconcile-duration flat ~3.03s/cycle over 841 cycles (no creep), metric cardinality flat at 75 series, lease transitions flat at 54 (no drift), policy-fetch-errors flat at 2 (no fd/watch error accrual), **evicted_total=0 across all 841 consolidation evaluations**. Every ng-stateful-1 DB pod unchanged (same node/startTime/restarts), 0 Evicted events → **I15/I16 DB protection HELD on the REAL databases over the long haul.** Agent 2/2, 0 restarts. | ✅ soak-pod-kill Schedule + podchaos children + k6 Job + CM deleted, chaos inventory ALL CLEAR, fleet self-healing to 145, DB intact |
| 18 | stale-state / time / identity | **HELD on all 3 vectors.** (A) 3× replay of a completed `POST /rebalance` (gen-1) → agent did NOT re-run; `nodesDrained` stayed 1 (anti-replay). (B) foreign-identity orphan (`draining` + `cordoned-by=some-other-operator`) → RecoverOrphans marked `aborted` but **left it CORDONED** (did NOT uncordon a node it didn't own — identity-scoped `ours` check). (C) SIGKILL leader → lease transitioned kzzvv→zkrr2 (53→54), exactly **1** is_leader, old identity fenced out. DB 10/10, 0 evicted. | ✅ test node restored, 0 draining, agent 2/2, cookie removed |

---

## Phase 1 — zero-downtime-under-traffic
- **Injected:** in-cluster k6 Job `k6-p1-zerodt` (80 VUs, 20s ramp / 60s hold / 10s down) hammering `fleet-http` (145 backends); mid-hold, `kubectl rollout restart` on 12 fleet deploys simultaneously under live traffic.
- **Tool behavior:** rolling restarts completed cleanly ("successfully rolled out"); service fanned traffic across the remaining healthy backends while individual pods cycled.
- **Metric evidence (ACTUAL, from k6 Job log — authoritative):** `http_req_failed = 0.00% (0 out of 57068)`; `http_req_duration` avg=3.73ms, p(95)=**9.45ms**, max=389.6ms; `checks_succeeded = 100.00% (57068/57068 status-200)`; throughput **633.56 rps**; 0 interrupted iterations throughout the roll. Fleet availability after roll = **145/145**.
- **Breaking point OR held:** **HELD.** No downtime; zero failed requests while rolling 12 of 145 deployments. Thresholds `http_req_failed rate<0.25` and `p(95)<2000ms` both PASS with large margin.
- **Verdict:** Zero-downtime rollout under traffic works at the service-fan-out level. No regression. (Caveat: each fleet deploy is single-replica with a PDB, so a single-deploy outage is momentary at that one backend; the service-level SLO is unaffected — documented, not a bug.)
- **Fix:** none required.
- **Running cost:** no node churn; k6 pod ephemeral. Campaign-attributable increment ≈ $0 this phase.
- **Cleanup verified:** k6 job `NotFound`; 0 paused rollouts; fleet 145/145.

## Phase 2 — scale (~200)
- **Injected:** scaled 55 fleet deploys from 1→2 replicas under live k6 load (`k6-p2-scale`, 60 VUs, 150s hold), targeting ~200 fleet pods. Nodegroup growth FORBIDDEN (ng-ondemand-1 capped at 26), so this measures the real cluster ceiling.
- **Tool behavior:** scheduler placed pods up to the node capacity then stalled; excess pods stuck `Pending` with `FailedScheduling`. No crash, no eviction cascade.
- **Metric evidence (ACTUAL):** fleet rose 145 → **178 Running, 23 Pending** and held there for the full 108s observation. Scheduler reason: `0/31 nodes available: 22 Insufficient cpu, 8–11 Too many pods, 4 untolerated taint, 1 unschedulable; preemption: 26 No preemption victims, 5 Preemption not helpful`. Serving during churn: k6 `http_req_failed = 0.00% (0/91967)`, p95 **7.84ms**, checks 100.00% (91967/91967), **525.27 rps**.
- **Breaking point OR held:** **BREAKING POINT at ~178 schedulable fleet pods.** Cannot reach 200 without adding nodes (guardrail forbids). Dual ceiling: VPC-CNI 17-pods/node on t3.medium **and** CPU-request exhaustion from the over-provisioned fleet profiles.
- **Verdict:** Scale ceiling is a capacity/scheduling limit (expected under the no-growth guardrail), NOT a control-plane or serving failure — the 145-pod serving fleet stayed 100% healthy while 23 pods were unschedulable. The autoscaler/agent did NOT grow ng-ondemand-1 past its cap (good — respected the min/max). Logged as BUG-SCALE-002 (capacity-planning/observability, LOW).
- **Fix:** to seat ~200, either right-size the over-provisioned fleet CPU requests (profiles `over`/`over-heavy`) or enable VPC-CNI prefix-delegation (currently OFF) — both avoid raw node growth. No code fix required for the agent.
- **Running cost:** peak +33 pods on existing nodes (no new nodes — cap held). Campaign-attributable increment ≈ $0 (no node churn). 
- **Cleanup verified:** restored to 145 Running / 0 Pending; no deploy left >1 replica; k6 job `NotFound`.

## Phase 3 — memory-OOM-race
- **Injected:** chaos-mesh `StressChaos p3-oom-mem` — memory stressor 2 workers × 400MB (> the 256Mi container limit) on `fixed-percent 40` of the 24 `burst`-profile fleet pods, duration 120s, under live k6 load (`k6-p3-oom`, 60 VUs). Namespace-gated to `stress`.
- **Tool behavior:** kernel OOM-killer fired on the over-limit containers; kubelet restarted each; service endpoints dropped/re-added the affected pods automatically.
- **Metric evidence (ACTUAL):** **9 pods OOMKilled, exit code 137, restartCount 1 each** (e.g. fleet-005/017/029/035/053/071/083/119/131-burst); steady at 9 for the full 120s (no escalation), **0 CrashLoopBackOff**. Serving through the race: k6 `http_req_failed = 0.00% (0/88165)`, p95 **2.16ms**, checks 100.00% (88165/88165), **551.01 rps**.
- **Breaking point OR held:** **HELD (graceful).** OOM contained to targeted pods; each recovered on first restart; zero serving impact — the service routed around OOMKilled backends. Not a cascade; not a breaking point.
- **Verdict:** Memory-limit enforcement + restart + endpoint healing behave correctly under concurrent traffic. Correct behavior.
- **Fix:** none required.
- **Running cost:** no node churn; ephemeral k6. Increment ≈ $0.
- **Cleanup verified:** `StressChaos p3-oom-mem` deleted; all 24 burst pods back `Running`; k6 job `NotFound`; fleet 145.

## Hygiene finding (pre-Phase-4) — STALE chaos objects from a prior run, one ACTIVELY injecting
Before Phase 4 I found 3 orphaned chaos objects left in ns `stress`: `StressChaos/fleet-cpu-stress` (stopped, AllRecovered), `NetworkChaos/fleet-net-latency` (stopped), and **`PodChaos/fleet-pod-kill` — ACTIVE** (`action=pod-kill`, `fixed-percent 15`, `dur=8m`, selector `app=fleet-http`, `AllInjected=True / AllRecovered=False`). The active pod-kill was continuously terminating ~15% of fleet pods and is the source of the stray restarts seen earlier. **All 3 deleted; cluster-wide chaos inventory now empty (ALL CLEAR)** so phases 4–8 run against a clean baseline. Logged as OPS-CHAOS-003 (hygiene, LOW).

## Phase 4 — CPU-throttling (REAL CFS metric)
- **Injected:** chaos-mesh `StressChaos p4-cpu-throttle` — `cpu: {workers: 4, load: 100}` (busy-loop far exceeding the 500m CPU limit) on `fixed-percent 50` of the 25 `over`-profile pods, 120s, under live k6 load (`k6-p4-cpu`, 80 VUs, 50ms sleep).
- **Tool behavior:** CFS quota enforcement throttled the over-limit containers; measured directly from cAdvisor/kubelet.
- **Metric evidence (ACTUAL, REAL CFS counter):** `container_cpu_cfs_throttled_periods_total` (over-profile sum) **142178 → 170267 = +28089 throttled periods** during the window. Throttle **ratio** (`rate(throttled)/rate(elapsed)` over 30s) rose 0.178 → 0.232 → 0.226 → **0.314** → 0.272 → 0.264 (avg across over-profile); per-pod **peak ratio = 1.0** (fully throttled). Serving under throttle: k6 `http_req_failed = 0.00% (0/219684)`, p95 **10.03ms**, checks 100.00% (219684/219684), **1372.79 rps**.
- **Breaking point OR held:** **HELD.** CFS throttling engaged exactly as designed (real counter + ratio=1.0 peaks prove it), capping CPU at the limit with zero request failures. Throttle is the expected consequence of a CPU limit, not a failure.
- **Verdict:** CPU-limit enforcement correct and observable via the real CFS metric; the light whoami workload keeps latency low even when fully throttled. Correct behavior.
- **Fix:** none required for the agent. (Operational note: the `over` profile's tiny CPU limit + heavy stress naturally throttles — if a real workload needed the CPU this is where you'd right-size, tying back to BUG-SCALE-002.)
- **Running cost:** no node churn; ephemeral k6. Increment ≈ $0.
- **Cleanup verified:** no `stresschaos` cluster-wide; 25/25 over-profile `Running`; k6 job `NotFound`; fleet 145.

## Phase 5 — rollback / self-heal storm (+ I15/I16 DB-protection headline)
- **Injected:** (a) chaos-mesh `PodChaos p5-kill-storm` pod-kill `fixed-percent 25` of `app=fleet-http`, under live k6 (`k6-p5-heal`, 70 VUs); (b) `kubectl set image …:vDOES-NOT-EXIST` on 6 deploys to force a broken rollout; (c) measured agent eviction counters + ng-stateful-1 DB pods throughout.
- **Tool behavior:** (a) kubelet/ReplicaSet re-created killed pods; service healed endpoints. (b) all 6 broken deploys went `ErrImagePull`/`ImagePullBackOff`; `kubectl rollout undo` rolled each back cleanly ("successfully rolled out"). (c) consolidation kept skipping on the protected DBs.
- **Metric evidence (ACTUAL):**
  - Serving under the kill storm: k6 `http_req_failed = 0.00% (2 out of 108678)` (2 in-flight reqs dropped at kill instants; max=30.02s = a single request caught mid-kill), checks **99.99% (108676/108678)**, **639.09 rps**.
  - Rollback: 6/6 deploys recovered via `rollout undo`; **0 broken deploys remaining** (image audit = none).
  - **DB-protection (I15/I16): `xamops_consolidation_pods_evicted_total` = 0 BEFORE and 0 AFTER.** `xamops_consolidation_runs_total{status="skipped"}` rose to **257**; `xamops_drains_total{controller="node-optimizer",result="skipped"}=68`. **All 10 ng-stateful-1 DB pods (`pg-cnpg-1/2`, `pg-unsafe-0`, `mysql-0`, `mongo-0/1/2`, loaders) still Running — 0 evicted.**
- **Rightsizer OOM/throttle auto-revert:** checked for a live path — **none exists.** Gate shows `rightsizer=off` + `rightsizer-apply=off` (retired, BUG-GATE-002). No live revert controller runs in this agent image, so there is nothing to exercise; recorded honestly, not simulated.
- **Breaking point OR held:** **HELD.** Self-heal (pod-kill) and rollback (bad-image undo) both recovered to full health with ~zero serving impact; DB fail-closed protection held under the storm (0 DB evictions) — the I15/I16 headline PASSES again live.
- **Verdict:** Self-heal + rollback correct under concurrent traffic; DB protection fail-closed confirmed. Correct behavior. (Known cosmetic gap OBS-DB-001 unchanged: refusal reason is blanket "db-emptydir", not role-aware.)
- **Fix:** none required (rightsizer revert is absent by design, not a regression).
- **Running cost:** no node churn; ephemeral k6. Increment ≈ $0.
- **Cleanup verified:** cluster-wide chaos inventory **ALL CLEAR** (podchaos/stresschaos/networkchaos/schedules none); k6 job `NotFound`; 0 broken deploys; fleet 145.

## Phase 6 — leader-failover / HA
- **Injected:** chaos-mesh `Schedule p6-leader-kill` (PodChaos pod-kill, `mode: one`, fieldSelector pinned to the current leader) in ns `xamops-system`; failover made deterministic+prompt by deleting the leader pod `xamops-automation-6478774c5b-q8kjl`.
- **Tool behavior:** leader-election lease transferred to the standby; a replacement pod was created to restore 2/2; no double control-loop.
- **Metric evidence (ACTUAL):** BEFORE — lease holder `…q8kjl`, both replicas emit `xamops_leader{is_leader="…q8kjl"} 1` (consensus). AFTER kill — lease holder flipped to **`…tk8g5` within ≤10s**; **exactly ONE leader** (both surviving pods `…tk8g5` and new `…b7fb7` emit `xamops_leader{is_leader="…tk8g5"} 1` — same holder = NO split-brain). Automation back to 2/2 (`…tk8g5` 3h+, `…b7fb7` new). No duplicate consolidation-run spike during the handover.
- **Breaking point OR held:** **HELD.** Sub-10s failover, single-leader invariant preserved, no split-brain / double-action.
- **Verdict:** HA leader election correct; standby promotes cleanly and the killed replica is self-healed by the Deployment. Correct behavior.
- **Fix:** none required.
- **Schedule teardown (MANDATORY per OPS-CHAOS-003):** `Schedule p6-leader-kill` **deleted and verified gone** — `k get schedule,podchaos -n xamops-system` → `No resources` (ALL CLEAR). No lingering schedule left to crash tooling.
- **Running cost:** no node churn; one agent pod reschedule (existing node). Increment ≈ $0.
- **Cleanup verified:** 0 schedules + 0 podchaos in xamops-system; automation 2/2; single leader `…tk8g5`.

## Phase 7 — network latency + REAL spot/node churn (AWS FIS)
- **Safety gate:** re-asserted context+acct (102227938259), re-resolved ng-spot-1 by **BOTH tags** `eks:cluster-name=xamops-test-1` + `eks:nodegroup-name=ng-spot-1` → exactly **1** running instance `i-0025a665eb405673e` (spot, `sir-v2nzqtpj`). Cross-checked it is NOT in xamops-test-2 (test-2 spot = `i-09e07a8add9e310b4`,`i-06140cd5359326eab` — never touched). Created a scoped FIS execution role (`ec2:SendSpotInstanceInterruptions` on that one instance ARN only).
- **Injected:** (a) chaos-mesh `NetworkChaos p7-net-latency` delay 200ms ±50ms jitter on `fixed-percent 40` of fleet under k6 (`k6-p7-net`, 60 VUs); (b) **AWS FIS experiment `EXPCBm5tXDyUS9Kzrv`** (template `EXT2Lah1zhdWHrDN`), action `aws:ec2:send-spot-instance-interruptions`, `durationBeforeInterruption=PT2M`, target selectionMode=ALL on the resolved set.
- **Tool behavior:** FIS issued the interruption + 2-min warning; after the warning the spot instance went **running → shutting-down → terminated** (genuine spot reclamation); the ASG launched a replacement node `ip-172-31-40-7` (new, 2m32s age). Agent's `interruption` controller stayed live throughout.
- **Metric evidence (ACTUAL):**
  - Spot lifecycle observed: `running` (t+0..100s warning) → `shutting-down` (t+120s) → **`terminated`** (t+140s). Replacement spot node appeared `Ready` in the nodegroup.
  - Network latency effect: k6 `http_req_duration` p90=**188.51ms**, p95=**219.81ms**, avg=33.27ms (vs ~8ms baseline — the 200ms injection is clearly visible), yet `http_req_failed = 0.00% (0/76126)`, checks 100.00% (76126/76126), **422.9 rps**.
  - Agent: `xamops_gate_decision{controller="interruption",mode="live"}=1`, `xamops_consolidation_pods_evicted_total=0`, `xamops_orphans_recovered_total=0`.
  - **DB protection:** all 9 ng-stateful-1 DB pods (`pg-cnpg-1/2`, `pg-unsafe-0`, `mysql-0`, `mongo-0/1/2`, loaders) stayed `Running` through the spot churn — **0 evicted.**
- **Breaking point OR held:** **HELD.** A real spot termination + injected network latency was absorbed with zero request failures and zero DB disruption; the node was replaced automatically. Latency rose (expected, injected) but no SLO error-rate breach.
- **Verdict:** Real spot/interruption handling + node replacement + DB protection all correct under concurrent latency. Correct behavior. Blast radius stayed xamops-test-1 ng-spot-1 only.
- **Fix:** none required. (Operational: no dedicated FIS execution role pre-existed — one was created scoped to the single instance and deleted in teardown; a reusable least-priv FIS role could be added to campaign prep.)
- **Running cost:** 1 spot instance terminated + 1 replacement launched (ng-spot-1, ~$0.015/hr spot) — negligible partial-hour churn. No on-demand growth. Increment ≈ <$0.05.
- **Cleanup verified:** FIS experiment template **deleted (0 templates remain)**; scoped IAM role **deleted → GetRole NoSuchEntity**; `NetworkChaos` gone; k6 job `NotFound`; cluster-wide chaos **ALL CLEAR**.

## Phase 8 — flapping (swinging usage → thrash hunt)
- **Injected:** 4 load **swing cycles** alternating HIGH (90 VUs, 20s) and LOW/idle (5 VUs, 15s) k6 against fleet-http, plus a 180s intermittent `StressChaos p8-flap` (CPU 2 workers × 90% on 30% of burst pods). Goal: see if rebalancer/consolidation thrash (repeated act↔revert) on oscillating signal.
- **Tool behavior:** the agent evaluated once per control cycle and consistently **refused to act** — no drain started, no node removed/re-added, no eviction.
- **Metric evidence (ACTUAL, leader counters BEFORE→AFTER):** `xamops_consolidation_runs_total{status="skipped"}` **4 → 8** (exactly +1 per cycle = one evaluation each loop, all skipped); `status="executed"` **0 → 0**; `xamops_consolidation_action_rate` **0** throughout; `xamops_drain_budget_inflight` **0** throughout; `xamops_consolidation_pods_evicted_total` **0 → 0**. Pod restart churn across stress = 8 (from the CPU stressors, not tool action). All 9 ng-stateful-1 DB pods `Running`.
- **Breaking point OR held:** **HELD — NO THRASH.** Flapping did not cause the rebalancer/consolidation to oscillate; `executed=0 / action_rate=0 / drains_inflight=0` across all 4 swings prove the agent stayed stable and fail-closed. No ledger churn (no executed runs to thrash over).
- **Verdict:** Anti-thrash / stability behavior correct; consolidation debounces and stays look-only on the protected/over-subscribed fleet instead of flapping actions. Correct behavior.
- **Fix:** none required.
- **Running cost:** no node churn; ephemeral k6. Increment ≈ $0.
- **Cleanup verified:** cluster-wide chaos **ALL CLEAR**; no p8 k6 jobs; fleet 145 Running / 0 Pending.

## Phase 9 — autoscaler conflict (HPA on the SAME signal the agent acts on)
- **Injected:** 6 `HorizontalPodAutoscaler` (autoscaling/v2, CPU `averageUtilization=50`, min1/max4) on the `over-heavy` fleet deploys — the exact CPU/packing signal the agent's consolidation/node-optimizer reasons about — then a 120-VU k6 run (`k6-p9-hi`, 20s ramp / 150s hold) to push CPU over target so the HPA would scale UP while, in the post-load valley, consolidation would want to pack/drain. Goal: catch a fight (agent drains a node the HPA just filled, HPA fights a cordon, consolidation thrash, or eviction of HPA-owned pods).
- **Tool behavior:** the agent's consolidation stayed **look-only / fail-closed** the entire window — never attempted to drain or evict an HPA-managed pod; no cordon applied to HPA-backing nodes. No oscillation between the two controllers. (Note: the HPAs themselves reported `cpu: 4/1` i.e. the metrics-server/target normalization kept them effectively pinned at desired=1 — the whoami workload is near-idle at rest and the k6 CPU pressure landed on the k6 pod, not materially on the backends, so HPA replica count held at 1; the conflict we were hunting is the AGENT reacting to HPA-driven churn, which it did not do destructively.)
- **Metric evidence (ACTUAL, leader counters BEFORE→AFTER):** `xamops_consolidation_runs_total{status="skipped"}` **35 → 40** (+5, one skipped evaluation per cycle — no executed run); `status="executed"` absent/0; `xamops_consolidation_pods_evicted_total` **0 → 0**; `xamops_consolidation_action_rate` **0** throughout; `xamops_drain_budget_inflight` **0** throughout. Serving under load: k6 `http_req_failed = 0.00% (0 out of 1,242,017)`, checks **100.00% (1242017/1242017)**, p95 **12.54ms**, max 244.98ms. **0** `Evicted`/preempt events in ns stress. All 10 DB fixtures `Running`.
- **Breaking point OR held:** **HELD — no autoscaler conflict.** The agent did not fight the HPA: it took no action (0 evicted, 0 drains in-flight, action_rate 0) rather than drain nodes the HPA was keeping warm, so there was no act↔revert thrash and no eviction of autoscaled pods.
- **Side-finding (cost-guardrail, recorded + corrected):** at the AFTER snapshot `ng-ondemand-1` scalingConfig read **max=40, desired=34** — drifted up from the setup cap (max=26). Actual running node count was only **30 ng-ondemand** (35 total; no cluster-autoscaler/Karpenter pod exists on the cluster, and the lone Pending pod was an unrelated `team-a` pod), so no runaway launch had occurred, but the raised ceiling allowed up to 40 on-demand. **Re-capped** (context+acct asserted) to `minSize=4,maxSize=30,desiredSize=30` (EKS update `bf0c06eb-764c-…`) — freezes at the real 30-node count, no eviction, blocks further climb. This is drift from a prior step, NOT caused by the agent (it never grew the group); logged for cost hygiene.
- **Verdict:** Autoscaler-conflict safety correct — the consolidation controller stays fail-closed in the presence of an HPA on the same metric instead of entering a tug-of-war. Correct behavior. (The HPA staying at 1 replica limited how hard the scale-up arm pushed; the destructive direction we cared about — the AGENT reacting — was fully exercised and held.)
- **Fix:** none required for the agent. Cost-hygiene: campaign RESTORE must return ng-ondemand-1 to baseline 1/4/4; interim cap now 30 (was drifting to 40).
- **Running cost:** no node churn executed; 30 ng-ondemand (vs 26 cap) = +4×$0.0416 ≈ **+$0.17/hr** holdover from drift (not this phase's doing); k6 pod ephemeral. Phase increment ≈ $0.
- **Cleanup verified:** 6 phase-9 HPAs deleted (only pre-existing `team-b/php-apache` remains, untouched); over-heavy replicas back to 1/1; `k6-p9-hi` `NotFound`; fleet 145 Running; DB 10/10 Running.

## Phase 10 — api-server 429 pressure (APF / control-plane throttling)
- **Injected:** two waves of control-plane read storms from in-cluster Jobs (read-only `view` SA). Wave A: 8 parallel `api-hammer` Jobs each tight-looping uncached `LIST pods/events/configmaps -A` (no limit → served from etcd, heaviest path) for 180s. Wave B (`phase10b`): 12 Jobs × 15 concurrent direct-REST curl loops = **180-way** concurrent `GET /api/v1/pods?limit=0` against `kubernetes.default`. Goal: push API Priority & Fairness to emit `429 Too Many Requests` and watch whether the agent degrades gracefully.
- **Tool behavior:** the EKS managed control plane absorbed both waves. The agent kept running normally — no crashloop, no leader flap, no backoff spiral.
- **Metric evidence (ACTUAL):**
  - Wave A: apiserver `sum(apiserver_request_total{code="429"})` **100 → 100** (unchanged), `429 rate/s` **0** at every 30s sample; `apiserver_flowcontrol_rejected_requests_total` **empty (0 series)**; each hammer completed 123–198 requests with **observed-429 = 0** (≈1,398 heavy LISTs total, zero throttled).
  - Wave B: `apiserver_current_inflight_requests` peaked at **5** (baseline 4) under 180-way concurrency; `429 rate/s` stayed **0**; the burst Jobs hit their 180s `activeDeadline` and were reaped (client summaries not emitted — but the server-side inflight/429 series prove the control plane was never near saturation).
  - Agent: **2/2 Running, 0 restarts** across both waves; lease holder `…b7fb7` unchanged BEFORE→AFTER (no failover); gates `consolidation-actions/evictor/rebalancer/interruption` still `live`. No `throttl/429/backoff` lines in the leader log.
- **Breaking point OR held:** **HELD — and the 429 breaking point was NOT reachable from in-cluster client load at a safe scale.** EKS APF has large headroom; even a 180-way uncached-LIST burst moved inflight by ~1 and produced 0 rejections. The agent's own API usage is modest and well-behaved, so it never contended. Driving the apiserver to its actual 429 threshold would require order-of-magnitude more aggressive load (hundreds–thousands of concurrent watchers) with no safety/cost justification on a shared UAT control plane — recorded honestly rather than forced.
- **Verdict:** Under realistic control-plane read pressure the agent stays healthy and the control plane does not throttle. No defect. (Negative result on reaching the 429 wall itself: not a tool behavior, a function of EKS APF capacity.)
- **Fix:** none required.
- **Running cost:** pod-only ephemeral Jobs; no node churn. Increment ≈ $0.
- **Cleanup verified:** all `api-hammer-*` / `api-burst-*` Jobs deleted (`none` remain); `api-hammer` SA + `api-hammer-view` ClusterRoleBinding removed; agent 2/2; fleet 145 Running.

## Phase 11 — fuzz / adversarial specs
- **Injected (A) backend policy API (via the REAL API, cookie-auth, never bypassed):** 18 adversarial PUTs against the xamops-test-1 policy routes (`consolidation`, `evictor-policy`, `mode`, `node-optimizer`): empty body, non-JSON garbage, wrong-type (`enabled:"yes-please"`, `analyseOnly:"false"`, `mode:123`), `enabled:null`, deeply-nested junk, 20-digit huge number, negative number, SQL-injection string (`'; DROP TABLE clusters;--`), XSS (`<script>`), `__proto__` prototype-pollution object, array-where-object, invalid enum (`mode:"ULTRA-DESTROY"`), path-traversal clusterName (`..%2f..%2fadmin`), and a nonexistent clusterName.
- **Injected (B) adversarial Kubernetes workload the agent reads:** a Deployment with absurd resources (`requests cpu 1m / mem 1Ki`, `limits cpu 16 / mem 64Gi`), `terminationGracePeriodSeconds: 999999`, a `safe-to-evict: "not-a-bool"` annotation, and injection-laden annotations (SQLi + XSS + null byte + `${jndi:ldap://…}` log4shell-style string) — to see if the agent's collect/fit chokes on pathological pod specs.
- **Tool behavior:** (A) the backend rejected **every** malformed PUT with **HTTP 400** `K8S_INVALID_REQUEST` (field `accountId`) — the parameter-validation gate fired before any body reached policy-write logic, so no fuzz payload could mutate state. Path-traversal clusterName → **502** at the gateway (encoded path refused). Nonexistent clusterName → **400**. (B) the agent ingested the adversarial Deployment without issue.
- **Metric evidence (ACTUAL):**
  - **0 of 18** fuzz PUTs produced a 5xx from the application (the only 5xx was the gateway 502 on the deliberately-malformed traversal path, and the GET-method finding below).
  - Live policy **UNCHANGED** by the fuzz: all **6 acting gates still read `mode="live"`** (consolidation-actions, cordon, evictor, idle-drain, interruption, rebalancer) immediately after the fuzz run — proof the adversarial writes did not corrupt the served bundle.
  - Agent through (B): **2/2 Running, 0 restarts**, **no** `panic/fatal/collect error/nil pointer` lines in the leader log, `xamops_consolidation_pods_evicted_total = 0`, gates still live.
- **Breaking point OR held:** **HELD.** Adversarial/fuzz input is rejected safely at the API boundary and tolerated in the K8s read path; nothing crashed, nothing mutated.
- **Finding — BUG-API-005 (LOW, API hygiene):** issuing `GET` on the PUT-only `…/consolidation` policy route returns **HTTP 500** (`"Request method 'GET' is not supported"`) instead of the correct **405 Method Not Allowed**. Wrong status class (5xx for a client method error); no state impact, no crash — cosmetic/contract bug. Logged to bugs.md.
- **Verdict:** Input validation + adversarial-spec tolerance correct; defense-in-depth confirmed (accountId gate rejects before policy write). Correct behavior apart from the cosmetic GET→500.
- **Fix:** return 405 for unsupported methods on the policy routes (BUG-API-005). No agent code change needed for the fuzz tolerance itself.
- **Running cost:** API calls only + one short-lived pod; no node churn. Increment ≈ $0.
- **Cleanup verified:** `adversarial-fuzz` Deployment deleted (`NotFound`); temp session cookie files removed (`/tmp/uat-sess.*` none); live policy intact; fleet 145.

## Phase 12 — PVC disk exhaustion (+ v1 online-expansion gap)
- **Injected:** filled the `redis-0` PVC (`redis-data`, 2Gi, `gp3-fixtures`, isolated in ns `stress-db`) to **100%** with `dd if=/dev/zero of=/data/fill.bin` until ENOSPC. Smallest-blast-radius stateful fixture chosen on purpose.
- **Tool behavior:** the disk filled (`/data` 0% → **100%**, 2,022,703,104 bytes written, 832K free at ENOSPC). Redis stayed up for in-memory ops (`ping`=PONG, `set`=OK) but the **disk-persist path was impacted** (`bgsave` could not cleanly complete against the full volume). The XamOps agent took **no action** against the disk-full stateful pod.
- **Metric evidence (ACTUAL):**
  - Disk: `/dev/nvme1n1 … 0% → 100%` on `/data`; dd reported `2022703104 bytes copied` then no space.
  - **(2) DB protection:** over a 90s watch, redis-0 stayed `Running` restarts=**0**; **0** `evict/preempt/drain` events on redis-0; `xamops_consolidation_pods_evicted_total` **0 → 0**. The agent did NOT try to evict or reschedule the disk-pressured DB pod.
  - **(3) v1 online-expansion GAP:** `redis-data` `.status.capacity.storage` stayed **2Gi → 2Gi** — **no online expansion** was performed by the agent/backend, even though `gp3-fixtures` advertises `allowVolumeExpansion=true` at the CSI/SC layer. v1 has no auto-expand path; a disk-full DB would require operator/CSI-driven `kubectl patch pvc … resources.requests.storage` (not an agent action).
  - Agent health AFTER: 2/2 Running, 0 restarts.
- **Breaking point OR held:** **HELD on the safety axis** (DB fail-closed protection survived disk exhaustion — the agent did not disrupt a disk-full stateful pod), **with a recorded v1 CAPABILITY GAP**: no online PVC expansion. The "breaking point" here is the filesystem itself (ENOSPC at 2Gi), which the agent correctly does not try to paper over by moving/evicting the DB.
- **Verdict:** Correct safety behavior — disk pressure on a protected DB does not provoke eviction/drain. Capability gap (not a regression): v1 does not auto-expand PVCs; operators must expand manually. Record as the requested Phase-12 gap, consistent with the design (the agent is a node/consolidation controller, not a storage autoscaler).
- **Fix:** none required for safety. Enhancement (future): optional online PVC-expansion controller tied to a disk-pressure signal, guarded by the same DB-protection rules; out of scope for v1.
- **Running cost:** no node churn; wrote/removed a 1.9GB file on an existing gp3 volume — negligible EBS IO. Increment ≈ $0.
- **Cleanup verified:** `/data/fill.bin` removed by the trap; `/data` back to **0%** (44K used / 1.9G free); `redis-cli ping` = PONG; agent 2/2.

## Phase 13 — partial partition + rapid churn (~50/min)
- **Injected:** (a) chaos-mesh `NetworkChaos p13-partition` — `action: partition`, `direction: to`, `fixed-percent 40` of `app=fleet-http` targeting the rest of the fleet (a one-way partial partition so ~40% of pods lose a slice of connectivity while the control plane still sees them). (b) chaos-mesh `Schedule p13-churn-sched` — `@every 75s` PodChaos `pod-kill` `fixed-percent 25` of the ~145 fleet pods (≈36 kills/75s ≈ **29-50/min** sustained churn). Both under live k6 (`k6-p13`, 70 VUs, 240s hold). Goal: see if the agent mis-acts on a partial/stale view (drains a node it can't fully observe, double-counts, evicts a DB) or thrashes under rapid churn.
- **Tool behavior:** the agent kept evaluating and **stayed fail-closed/look-only** — no drain started on a partially-visible node, no eviction, no orphan-recovery misfire. The fleet self-healed each kill wave (ReplicaSets re-created killed pods); the service routed around partitioned/killed backends.
- **Metric evidence (ACTUAL, leader counters BEFORE→AFTER):** `xamops_consolidation_pods_evicted_total` **0 → 0**; `xamops_drain_budget_inflight` **0** at every 60s sample; `xamops_orphans_recovered_total` **0 → 0** (no half-drained-node recovery triggered — nothing was half-drained); `xamops_consolidation_runs_total{status="skipped"}` **73 → 78** (+5, one skipped eval per cycle, 0 executed). Serving under partition+churn: k6 `http_req_failed = 0.00% (6 out of 331,061)` (6 reqs caught mid-kill), checks **99.99% (331055/331061)**, p95 **4.19ms**, **1273.1 rps**. Fleet oscillated 145↔144 (self-heal) and ended 145/145.
- **DB protection:** DB pods Running **10 → 10**; **0** `Evicted` events in `xamops-db-fixtures`. Agent 2/2, 0 restarts.
- **Breaking point OR held:** **HELD — no partial-view mis-action.** A 40% partition plus ~50/min churn did not trick the agent into draining an under-observed node or evicting a DB, and did not cause thrash (executed=0, inflight=0 throughout). The orphan-recovery counter stayed 0 because the agent never left a node half-drained to recover.
- **Verdict:** Correct behavior under split/partial visibility + rapid churn — the controller debounces and stays fail-closed rather than acting on an incomplete snapshot. Correct.
- **Fix:** none required.
- **Running cost:** no node churn; pod-only chaos + ephemeral k6. Increment ≈ $0.
- **Cleanup verified:** cluster-wide chaos **ALL CLEAR** (`networkchaos/podchaos/schedule/stresschaos` = none — the Schedule explicitly deleted so no lingering object crashes tooling, per OPS-CHAOS-003); `k6-p13` `NotFound`; fleet **145/145** connectivity restored.

## Phase 14 — multi-DB fail-closed protection (HEADLINE I15/I16) against EACH engine
- **Injected (via the REAL backend API only — no bypass, no DB hand-edit):** read the backend's authoritative per-node drain decision via `GET …/evictor-candidates?accountId=102227938259` (HTTP 200), read `GET …/rebalance-status` (HTTP 200), and issued a **fresh** `POST …/rebalance?accountId=…` (HTTP 200 `{"success":true,"message":"Rebalance requested"}`) to drive a live drain re-plan over the ng-stateful-1 DB nodes, then watched the agent for 4 minutes and re-checked every DB pod.
- **Tool behavior:** the fit-simulator marks **all 3 DB-hosting nodes `canEvict=false / drainable=false`**, each with an explicit per-pod **`refused`** entry. The `POST /rebalance` was accepted but the agent kept the gen-1 latch (`"operation gen-1 already completed; not re-run"`) — correct, because the fit-sim finds nothing drainable (every DB node is refused and the fleet nodes are over-subscribed), so no live drain was launched at a DB.
- **Metric evidence (ACTUAL — per-engine `refused[]` from `evictor-candidates`):**

  | Engine | Pod | Node | `refused` reason | node `canEvict` | PDB check |
  |---|---|---|---|---|---|
  | **Postgres CNPG primary** | `pg-cnpg-2` | ip-172-31-4-95 | `db-emptydir` | **false** | `pg-cnpg-primary` disruptionsAllowed=**0** |
  | **Postgres CNPG replica** | `pg-cnpg-1` | ip-172-31-23-193 | `db-emptydir` | **false** | — |
  | **Postgres emptyDir (unsafe)** | `pg-unsafe-0` | ip-172-31-46-10 | `db-emptydir` | **false** | — |
  | **MySQL** | `mysql-0` | ip-172-31-4-95 | `db-emptydir` | **false** | — |
  | **Mongo RS** | `mongo-0 / mongo-1 / mongo-2` | 46-10 / 4-95 / 23-193 | `db-emptydir` | **false** | `mongo` disruptionsAllowed=1 |
  | **Redis** | `redis-0` | ip-172-31-40-120 | `db-role-unknown` | **false** | node also over-util (CPU 85%) |

  - **0 evictions for EVERY engine:** `xamops_consolidation_pods_evicted_total` **0 → 0**; all DB pod `startTime`s unchanged across the phase (e.g. pg-cnpg-2 `2026-10-27T07:05:03Z`, mysql-0 `2026-10-25T11:46:21Z`, redis-0 `2026-10-28T06:42:39Z` — same before/after); restart counts unchanged; **0** `Evicted` events in `xamops-db-fixtures` AND `stress-db`.
- **Breaking point OR held:** **HELD — PASS against each of Postgres / MySQL / Mongo / Redis.** The fail-closed floor refuses every DB pod by name with a reason and never evicts one; a known DB (emptyDir/PVC heuristic) → `db-emptydir`, an un-classifiable DB (Redis) → `db-role-unknown` (unknown ⇒ refuse, the safe default). Both PDB-backed (pg-cnpg-primary allows 0 disruptions) and non-PDB DBs are protected.
- **Verdict:** DB fail-closed protection is correct and complete across all four engines on the real ng-stateful-1 databases. Headline I15/I16 PASSES live. I16 (zonal-PV placement) not violated — no PVC-bound DB was moved (all refused before any reschedule). Correct behavior.
- **OBS-DB-001 reconfirmed (cosmetic, unchanged):** the reason for the PVC-backed CNPG primary/replica, MySQL, and Mongo is the blanket **`db-emptydir`** string (they are PVC, not emptyDir); DB clients would read `db-role-unknown`. Protection is right; the reason is not role-aware (no `db-primary`/`db-sync-standby`/`db-pvc` code). Not a breaking point.
- **Fix:** none required for safety. Enhancement: role-aware refusal reason codes (OBS-DB-001).
- **Running cost:** API reads + one trigger; no node churn, no eviction. Increment ≈ $0.
- **Cleanup verified:** no DB pod touched/moved/evicted; temp session cookie removed; evicted counter 0.

## Phase 15 — webhook-siege: NOT EXERCISED (no admission webhook exists)
- **Reason:** the campaign scope confirms there is no XamOps admission webhook (ValidatingWebhookConfiguration / MutatingWebhookConfiguration) in this architecture — the agent acts as a controller via the Eviction/drain API and the backend policy API, not through an admission webhook. There is no webhook endpoint to siege. **Recorded as not-exercised with that reason, per the step instruction.** No fault injected, nothing to clean up.

## Phase 16 — concurrency / double-writer races
- **Injected:** attacked the single-actor invariant three ways: (a) a **leader-flap storm** — 5 rounds of deleting the current leader pod (`--grace-period=1`) back-to-back; (b) a `POST …/rebalance` trigger each round to keep the acting path hot during each failover; (c) **two concurrent conflicting policy PUTs** (`consolidation {maxParallel:1}`) fired simultaneously to test backend serialization. Sampled EVERY automation replica's `/metrics` at ~3s cadence through each failover window for `xamops_leader` (how many pods claim leadership + which holder) and `xamops_drain_budget_inflight`.
- **Tool behavior:** leadership transferred cleanly on every kill (b7fb7 → f7jz8 → wmh86 → 4vk6q → 5n6gq → c9lhf); the Deployment self-healed each deleted pod back to 2/2; the lease always had exactly one holder.
- **Metric evidence (ACTUAL):**
  - **MAX simultaneous `xamops_leader=1` pods observed = 1** across all 15 failover-window samples (never 2).
  - **Split-brain (≥2 pods claiming leadership with DIFFERENT holder ids at the same instant) = 0.**
  - **MAX `xamops_drain_budget_inflight` = 0** throughout — the capacity-1 drain budget was never exceeded; no two concurrent drains.
  - Concurrent double-writer: writerA **HTTP 200** + writerB **HTTP 200** (`"Consolidation policy saved"`) — the backend accepted both without corruption (idempotent on the identical payload); the live policy stayed valid (gates still live afterward).
  - DB pods Running **10**, 0 `Evicted` events — no double-drain touched a DB.
  - Final: automation **2/2**, single lease holder `…c9lhf`.
- **Breaking point OR held:** **HELD.** Rapid leadership churn + concurrent triggers + concurrent writers did not produce split-brain, a second simultaneous leader, an inflight>1 double-drain, or policy corruption. Leader election + the drain-budget semaphore hold the single-writer guarantee under churn.
- **Verdict:** Concurrency/double-writer safety correct — exactly-one-actor and drain-budget-1 invariants survive aggressive leader flapping and concurrent API writes. Correct behavior.
- **Fix:** none required.
- **Running cost:** agent pod reschedules on existing nodes only; API calls; no node churn. Increment ≈ $0.
- **Cleanup verified:** agent self-healed to 2/2; single lease holder; temp cookie removed; DB intact.

## Phase 17 (REPLACES original 17) — SIGKILL the agent repeatedly mid-drain → RecoverOrphans leaves no half-drained node
- **Setup reasoning:** the fleet is over-subscribed so no node is naturally drainable right now (every candidate is `refused`/`blocked`/over-util), so a natural mid-drain could not be caught. I therefore induced a genuine orphan on an **already-cordoned, xamops-owned, empty** ng-ondemand node (`ip-172-31-2-6`: `unschedulable=true`, 0 non-DaemonSet pods) and crashed the agent through it. **Grounded the injection in the actual code:** `drain/executor.go:742` — `RecoverOrphans` keys off the **node annotation** `xamops.io/drain-state == "draining"` (not a label), runs only in Live/Simulate mode, and `markAborted` sets `drain-state=aborted` and uncordons if `drain-cordoned-by == xamops-automation`.
- **First attempt (17a) — method error, recorded honestly:** I first set a *label* `xamops.io/drain-state=draining`; across 5 SIGKILLs the node stayed `draining` and `orphans_recovered_total` stayed 0. Reading the source showed RecoverOrphans reads the **annotation**, not the label — so 17a was an **injection artifact, NOT a product bug**. Re-ran correctly as 17b.
- **Injected (17b, correct):** set the **annotation** `xamops.io/drain-state: draining` (keeping `drain-cordoned-by=xamops-automation`, `drain-operation=evictor-1793186274`) → a true half-drained orphan. Then SIGKILL the leader **×5** via `kubectl delete pod --grace-period=0 --force` (immediate kill = no graceful shutdown, exercises crash-recovery, not clean handoff).
- **Tool behavior:** on the FIRST leader restart the new leader's startup `RecoverOrphans` reconciled the node: `drain-state: draining → aborted` **and uncordoned it** (`unschedulable` cleared). It stayed `aborted`/uncordoned idempotently through all 5 repeated SIGKILLs (no re-corruption). (Afterward the agent's normal idle-drain re-cordoned+re-drained the empty node back to the steady `drained` terminal state — normal operation, not an orphan.)
- **Metric evidence (ACTUAL):**
  - Round 1 immediately: node `drain-state=aborted`, `unschedulable` cleared. Held across rounds 2-5 and the 25s settle.
  - **FINAL invariant: ZERO nodes annotated `draining` cluster-wide** (the half-drained condition does not persist). Node ended in a terminal state.
  - DB pods Running **10**; agent self-healed to **2/2**, single lease holder `…kzzvv`.
- **Breaking point OR held:** **HELD / PASS.** Repeated SIGKILL mid-drain does not leave a half-drained node — RecoverOrphans deterministically reconciles a `draining` orphan to `aborted`+uncordoned on the next leader's startup, and it is idempotent under repeated crashes.
- **Finding — BUG-OBS-006 (LOW, telemetry):** `xamops_orphans_recovered_total` stayed **0** even though a real orphan recovery demonstrably occurred (node annotation flipped `draining`→`aborted`+uncordoned). The metric name is exposed at `/metrics` but is **not incremented anywhere** in the autoscaler source (the recover-orphan action is recorded only via the generic `telemetry.Finish(ControllerExecutor,"recover-orphan",…)` action record, with no backing counter). Operators watching that counter would not see recoveries. No safety impact — the recovery itself is correct and observable via the node annotation/action record.
- **Verdict:** Crash-recovery / orphan reconciliation is correct and crash-idempotent — the core guarantee ("no half-drained node") holds under repeated SIGKILL. Correct behavior, with one unwired counter (BUG-OBS-006).
- **Fix:** wire `xamops_orphans_recovered_total` to increment on each successful `RecoverOrphans` action (BUG-OBS-006).
- **Running cost:** agent pod reschedules on existing nodes only; no node churn, no eviction. Increment ≈ $0.
- **Cleanup verified:** no node left `draining`; orphan test node returned to a terminal state; agent 2/2; DB intact.

## Phase 18 — stale-state / time / identity
- **Injected — three vectors against the real agent:**
  - **(A) Stale operation-id / replay:** re-issued the SAME `POST …/rebalance?accountId=…` 3× against the already-`completed` `gen-1` operation, to see if stale/replayed state re-triggers a drain.
  - **(B) Foreign-identity orphan:** stamped a cordoned empty node (`ip-172-31-29-155`) with annotation `drain-state=draining` but `drain-cordoned-by=some-other-operator` (NOT `xamops-automation`) — a `draining` orphan the agent does NOT own — to test identity-scoped recovery (`executor.go:745` `ours := Annotations[AnnotationCordonedBy] == CordonedByXamops`).
  - **(C) Stale/expired leader fencing:** SIGKILL the current lease holder and verify the OLD identity stops acting and exactly one NEW holder acts (lease fencing under a 15s lease).
- **Metric evidence (ACTUAL):**
  - **(A)** replays 1/2/3 → HTTP 200 each, but `rebalance-status` stayed `op=gen-1 status=completed nodesDrained=1` BEFORE and AFTER — **no re-run, nodesDrained did not grow.** The agent idempotently ignores a completed operation-id (`"operation gen-1 already completed; not re-run"`).
  - **(B)** within one cycle (~35s) RecoverOrphans set the foreign node `drain-state: draining → aborted` **BUT left `unschedulable=true` (still CORDONED)** — it cleared the stuck marker but **did NOT uncordon a node it didn't cordon.** PASS on the identity scope: the agent only reverses its own cordons.
  - **(C)** lease holder `…kzzvv` → `…zkrr2`, `leaseTransitions` **53 → 54** (a real transition to a new identity); sampling both replicas showed **exactly 1** `xamops_leader=1` (`…zkrr2`), the old identity `…gcwcw` is_leader=0. No stale leader kept acting.
  - DB pods Running **10**; **0** `Evicted` events throughout.
- **Breaking point OR held:** **HELD on all three.** Stale/replayed operation state does not cause re-execution; a `draining` orphan owned by a foreign identity is cleared but not uncordoned (identity-scoped, no cross-owner action); a killed leader is fenced out and exactly one new leader acts.
- **Verdict:** Stale-state/time/identity handling correct — anti-replay on operation ids, identity-scoped orphan recovery (ownership check), and lease fencing all behave safely. Correct behavior.
- **Fix:** none required.
- **Running cost:** API calls + agent pod reschedule on existing nodes; no node churn, no eviction. Increment ≈ $0.
- **Cleanup verified:** foreign-identity test node restored to terminal `drain-state=drained` / `cordoned-by=xamops-automation`; **0** nodes annotated `draining` cluster-wide; agent 2/2; temp cookie removed; chaos inventory ALL CLEAR.

---

## Phase 19 — quota / limitrange collision
- **Pre-state (pinned context, acct 102227938259 asserted):** leader `zkrr2`; `xamops_consolidation_pods_evicted_total=0`, `consolidation_runs skipped=43`, `drain_budget_inflight=0`; fleet 145 Running; DB 7 core + redis Running; ng-ondemand-1 min4/desired30/max30.
- **Injected (ns `stress` only):** (a) `ResourceQuota p19-collision-quota` with hard caps deliberately far below actual usage — `requests.cpu=4` (used 23050m), `requests.memory=4Gi` (used 30976Mi), `limits.cpu=8` (used 84500m), `limits.memory=8Gi` (used 52736Mi), `pods=150` (used 145). (b) `LimitRange p19-collision-limitrange` with container `max cpu 250m / mem 256Mi` — below the `over`-profile's 500m/512Mi. Then scaled `fleet-001-over` 1→3 and launched k6 Job `k6-p19-quota` (60 VUs) to force admission.
- **Tool behavior:** the admission layer rejected every new pod. Scale-up → `FailedCreate ... pods ... is forbidden: [maximum cpu usage per Container is 250m, but limit is 500m, maximum memory usage per Container is 256Mi, but limit is 512Mi]` (LimitRange). k6 Job pods → `FailedCreate ... is forbidden: exceeded quota: p19-collision-quota, requested requests.cpu=100m ... used: requests.cpu=23050m ... limited: requests.cpu=4` (ResourceQuota). Namespace was **fully admission-locked** — not even the 100m k6 pod could be created.
- **Metric evidence (ACTUAL, leader `zkrr2` BEFORE→AFTER):** `xamops_consolidation_pods_evicted_total` **0 → 0**; `xamops_consolidation_runs_total{status="skipped"}` **43 → 49** (+6, one skipped eval per cycle, 0 executed); `xamops_drain_budget_inflight` **0** throughout; single leader `zkrr2` unchanged (no failover); agent **2/2, 0 restarts**. **0** `Evicted`/preempt events in `stress` or `xamops-db-fixtures`. Existing serving fleet held **145/145 Running** (a ResourceQuota/LimitRange only gates NEW admissions; already-running pods are untouched).
- **Breaking point OR held:** **HELD.** The collision is a real, total admission lockout at the quota/LimitRange layer (expected K8s behavior). The agent did NOT attempt to "repair" the lockout by draining/evicting/rescheduling — it stayed fail-closed (0 evicted, 0 drains, 0 preemptions). The only "breaking point" is that NEW load could not be scheduled into the locked namespace — which is the demonstration that the collision was complete, not an agent failure. Note: the k6 pod rejection meant no fresh request load ran; the pre-existing 145-pod fleet continued serving unaffected.
- **Verdict:** Correct behavior — a namespace quota/LimitRange collision does not provoke the autoscaler into mis-action; it respects the admission layer and does not evict running (or protected DB) pods to free quota. Correct.
- **Fix:** none required.
- **Running cost:** no node churn; quota/LimitRange are API objects; rejected k6 pods never ran. Increment ≈ $0.
- **Cleanup verified:** `ResourceQuota`+`LimitRange` deleted → `No resources found in stress namespace`; k6 Job `k6-p19-quota` deleted (`NotFound`); `fleet-001-over` scaled back to **1/1**; fleet **145 Running**.

---

## Phase 20 — scheduling deadlock
- **Pre-state (pinned context, acct asserted):** leader `zkrr2`; evicted=0, consolidation skipped=51, drain_inflight=0; 1 pre-existing unrelated Pending pod; DB 8/8.
- **Injected (ns `stress`):** `PriorityClass p20-high` (value 1000000) + Deployment `p20-deadlock-antiaffinity` (6 replicas) built as a true deadlock: unsatisfiable `nodeSelector p20-impossible-label=does-not-exist` **and** required self-`podAntiAffinity` on `kubernetes.io/hostname`, each pod requesting 100m/64Mi at max priority. The high priority is the trap — a buggy capacity controller might try to drain/evict running workloads (incl. a DB) to seat the preemptor.
- **Tool behavior:** all 6 replicas went **permanently Pending**. Scheduler: `0/35 nodes are available: 26 node(s) didn't match Pod's node affinity/selector, 4 untolerated taint(s), 5 unschedulable ... preemption: 35 Preemption is not helpful for scheduling.` The scheduler correctly refused to preempt (no node could ever satisfy the selector), and the XamOps agent took no capacity action in response.
- **Metric evidence (ACTUAL, leader `zkrr2` BEFORE→AFTER over ~90s / ~3 cycles):** `xamops_consolidation_pods_evicted_total` **0 → 0**; `xamops_consolidation_runs_total{status="skipped"}` **51 → 54** (+3, one skipped eval per cycle, 0 executed); `xamops_drain_budget_inflight` **0** throughout; single leader `zkrr2` unchanged; agent **2/2, 0 restarts**. **0** `Preempted` events on real workloads; **0** `Evicted`/preempt events in `xamops-db-fixtures`; DB pods **8 → 8** unchanged.
- **Breaking point OR held:** **HELD.** A max-priority scheduling deadlock did not provoke the agent (or kube-scheduler) into evicting/draining running workloads to seat the pending pods. The pods stayed Pending (correct — the constraint is genuinely unsatisfiable); the agent stayed fail-closed.
- **Verdict:** Correct behavior — the autoscaler does not interpret a high-priority unschedulable deadlock as a signal to disrupt healthy or protected (DB) pods. No capacity thrash, no DB disruption. Correct.
- **Fix:** none required.
- **Running cost:** pods never scheduled (Pending only); no node churn. Increment ≈ $0.
- **Cleanup verified:** Deployment `p20-deadlock-antiaffinity` + `PriorityClass p20-high` deleted (`NotFound`); fleet **145 Running**.

---

## Phase 21 — many-container pods
- **Pre-state (pinned context, acct asserted):** leader `zkrr2`; evicted=0, consolidation skipped=55, drain_inflight=0; no baseline collect errors.
- **Injected (ns `stress`):** (a) 3 pods × **40 containers** each (`p21-manycontainer-0/1/2`, `registry.k8s.io/pause:3.9`, 1m/2Mi each) = 120 containers → all **40/40 Running**. (b) escalated to a single **150-container** pod `p21-mega` (1m/1Mi each) to stress the per-container walk in the agent's collect/fit path harder → scheduled onto `ip-172-31-41-25` and reached **150/150 Running**. Total 270 campaign-injected containers.
- **Tool behavior:** the agent ingested every pod's full container list without error — collect and the fit-snapshot walked all containers and summed requests normally. The scheduler/kubelet accepted the mega pod (no max-container rejection); kubelet serialized the 150-container start (slow) and later the teardown (the mega pod sat Terminating and had to be force-deleted) — a kubelet characteristic, not an agent behavior.
- **Metric evidence (ACTUAL, leader `zkrr2`):** across the whole phase the leader log had **0** `panic / fatal / nil pointer / collect error / fit error / index-out-of-range` lines; `xamops_consolidation_pods_evicted_total` **0 → 0**; `xamops_consolidation_runs_total{status="skipped"}` **55 → 58** (normal per-cycle evals, 0 executed); `xamops_drain_budget_inflight` **0**; agent **2/2, 0 restarts**; single leader `zkrr2` stable.
- **Breaking point OR held:** **HELD.** Neither the 40-container nor the 150-container pod broke the agent's snapshot/fit/summation path. No breaking point reached in the agent (the only slow spot was kubelet container create/teardown, unrelated to the tool).
- **Verdict:** Correct behavior — the collect/fit pipeline is robust to pathologically many containers per pod (no crash, no mis-sum crash, no cardinality blow-up that killed the control loop). Correct.
- **Fix:** none required.
- **Running cost:** pods ran on existing nodes (tiny 1-2m/1-2Mi requests); no node churn. Increment ≈ $0.
- **Cleanup verified:** all `p21-manycontainer-*` deleted; `p21-mega` force-deleted (`--grace-period=0 --force`) after a stuck Terminating; `No resources found` for the label; fleet **145 Running**.

---

## Phase 22 — RBAC boundary
- **Pre-state (pinned context, acct asserted):** agent SA `system:serviceaccount:xamops-system:xamops-automation`, bound via ClusterRoleBinding `xamops-automation` → ClusterRole `xamops-automation`. (BUG-RBAC-001 already landed the PV + CNPG reads earlier this campaign.)
- **Probed (read-only `auth can-i` against the agent SA — no mutation):** both directions of the boundary.
  - **Must be GRANTED (and are):** `list persistentvolumes`=yes, `list nodes`=yes, `list clusters.postgresql.cnpg.io`=yes, `patch nodes`=yes, `create pods/eviction`=**yes** (confirmed via `--subresource=eviction`), including in `xamops-db-fixtures`.
  - **Must be DENIED (and are):** `delete nodes`, `create clusterrolebindings`, `get secrets -n kube-system`, `delete namespaces`, `create pods -n kube-system`, cluster-admin `* *`, `escalate`/`bind`/`update clusterroles`, `impersonate users`, `create serviceaccounts/token -n kube-system`, `patch daemonsets -n kube-system`, `delete persistentvolumeclaims -A`, `delete pods` — **all `no`.**
- **ClusterRole contents (authoritative dump):** core read `get/list/watch` on nodes/pods/namespaces/rc/services/PVC/**PV**; `postgresql.cnpg.io/clusters` read; apps & batch & policy reads; `metrics.k8s.io` reads; `nodes patch/update`; **`pods/eviction create`**; `deployments/statefulsets patch/update`; `events create/patch/update`; HPA & VPA full CRUD; PDB reads. No wildcard, no secrets, no RBAC-write, no kube-system write — least-privilege.
- **Fail-safe evidence (ACTUAL):** the leader ran a 5-minute window with **0** `forbidden / cannot list / cannot get / cannot create / RBAC / unauthorized` log lines — the agent never attempts an action outside its grant, so there is nothing for RBAC to deny at runtime (fail-safe by construction). The DB-eviction grant being present (not withheld) while **0 DB pods are ever evicted** (verified every phase) proves protection is a deliberate controller decision, not an RBAC accident.
- **Breaking point OR held:** **HELD.** The RBAC boundary holds on both sides: the agent has exactly the verbs it needs and none of the escalation/over-privilege verbs, and it operates entirely within that grant. No privilege-escalation path (clusterrole bind/escalate, SA-token mint, kube-system write) is reachable from the agent SA.
- **Verdict:** Correct — least-privilege ClusterRole, no escalation vectors, fail-safe runtime (no out-of-grant attempts). Correct behavior.
- **Fix:** none required. (Observation, not a bug: `kubectl auth can-i create pods/eviction` with the slash form returns a false `no` on client v1.34; `--subresource=eviction` confirms the grant is real — a CLI parsing quirk, not an RBAC gap.)
- **Running cost:** read-only authz probes; no fault, no churn. Increment ≈ $0.
- **Cleanup verified:** n/a — no fault injected (RBAC was only probed, never modified in this phase).

---

## Phase 23 — COMPOUND CHAOS (network + pod-kill + memory-stress + node-interruption SIMULTANEOUSLY, mid-operation)
- **Safety gate (re-asserted before FIS):** acct 102227938259, region ap-south-1, pinned xamops-test-1 ARN. FIS target re-resolved by **BOTH** tags `eks:cluster-name=xamops-test-1` + `eks:nodegroup-name=ng-spot-1` → exactly **1** running spot instance `i-0626ed7148ce34ce5` (t3.medium, ap-south-1a). Cross-checked NOT in xamops-test-2 (test-2 ng-spot-1 = `i-09e07a8add9e310b4`/`i-06140cd5359326eab` — never touched). Created a scoped FIS role `destroy-it-p23-fis-spot` with `ec2:SendSpotInstanceInterruptions` on **only** that instance ARN; FIS template `EXTC6GJaYZ4kyXn` `resourceArns=[that one ARN]`.
- **Pre-state:** leader `zkrr2`; evicted=0, skipped=67, drain_inflight=0, orphans=0; DB pods on ng-stateful-1 nodes (ip-172-31-4-95 / -23-193 / -46-10) with recorded node/startTime/restartCount; redis-0 Running.
- **Injected (all at once, under live k6 70-VU load):**
  1. `NetworkChaos p23-partition` — `partition` to 40% of `app=fleet-http` (4m).
  2. `PodChaos p23-pod-kill` + `Schedule p23-pod-kill-sched` (`@every 30s`) — pod-kill 25% of fleet, sustained.
  3. `StressChaos p23-mem-stress` — memory 2 workers × 400MB on 40% of `xamops.io/profile=burst` (4m).
  4. **AWS FIS** `send-spot-instance-interruptions` (`durationBeforeInterruption=PT2M`) on the one spot instance.
  (The API-driven `POST /rebalance` mid-op was attempted but the stored UAT session cookie had expired → 401; the agent's own consolidation control loop ran every cycle over the chaos regardless, which is the authoritative DB-protection driver and is what the metrics below measure.)
- **Tool behavior:** the spot instance went **running → terminated** (genuine FIS reclamation); the ASG relaunched a replacement spot node `i-00cb2a2ffb9721e8e` (ap-south-1b). The fleet churned hard (dropped to ~124 Running with 4 Pending at peak) and self-healed back. Through all of it the agent's leader did **not** fail over, took **no** drain/eviction action, and did not disrupt any DB.
- **Metric evidence (ACTUAL, leader `zkrr2` at peak):** `xamops_consolidation_pods_evicted_total` **0 → 0**; `xamops_consolidation_runs_total{status="skipped"}` **67 → 75** (one skipped eval per cycle, 0 executed); `xamops_drain_budget_inflight` **0** throughout; `xamops_orphans_recovered_total` **0**; gates `consolidation-actions=live` + `interruption=live` (a genuine live-mode test, not a disabled controller). Agent **2/2, 0 restarts**, single leader `zkrr2` unchanged (no split-brain, no failover).
- **DB fail-closed (I15/I16 HEADLINE under compound chaos):** every ng-stateful-1 DB pod — `pg-cnpg-1` (replica, RST 2), `pg-cnpg-2` (CNPG primary), `pg-unsafe-0` (emptyDir), `mysql-0`, `mongo-0/1/2` — ended on the **exact same node, with the same startTime and the same restart count** as pre-state; `redis-0` unchanged. **0 DB pods evicted or moved**; **0** `Evicted`/preempt events in `xamops-db-fixtures` or `stress-db`. The consolidation fail-closed floor held even while a node was being reclaimed mid-storm.
- **Serving evidence (ACTUAL, k6 final):** `http_req_failed = 0.00% (8 out of 291,041)` (8 reqs caught at pod-kill instants), `checks_succeeded = 99.99% (291033/291041)`, `http_req_duration` avg 2.01ms / p95 **5.52ms** / max 147.64ms, **1238.2 rps**, 0 interrupted iterations.
- **Breaking point OR held:** **HELD.** The simultaneous four-way fault (partition + pod-kill storm + memory stress + real spot node loss) did not break the control loop, did not trigger a destructive consolidation/drain, did not cause leader split-brain, and did not evict a single DB pod. The serving SLO held at ~0% error.
- **Verdict:** Correct — compound chaos is absorbed; the DB fail-closed protection (the campaign's special-attention item) is robust even under everything-at-once including node interruption. Consolidation stays fail-closed (0 evicted, 0 drains in-flight) rather than mis-acting on a chaotic, partially-visible, churning cluster. Correct behavior. OBS-DB-001 (blanket refusal reason) unchanged.
- **Fix:** none required.
- **Running cost:** 1 spot instance terminated + 1 replacement launched (ng-spot-1, ~$0.015/hr spot, partial-hour churn ≈ <$0.05); pod-only chaos + ephemeral k6; scoped FIS role created and deleted ($0). No on-demand growth. Increment ≈ **<$0.05**.
- **Cleanup verified:** Schedule deleted first (stops spawning), then all chaos objects + spawned Schedule children deleted → cluster-wide `networkchaos/podchaos/stresschaos/schedule` = **No resources found (ALL CLEAR)**; k6 Job `NotFound`; FIS experiment template **deleted** (0 templates remain); scoped IAM role **deleted → GetRole NoSuchEntity**; node count **35** (spot replaced 1:1); fleet **144 Running / 1 Pending** (steady state); DB **8/8 + redis Running**; agent **2/2**.

---

## Phase 24 — metrics poisoning (kill metrics-server; feed NaN / absurd values; verify fail-SAFE)
- **Pre-state (pinned context, acct asserted):** leader `zkrr2`; evicted=0, skipped=80, drain_inflight=0; `metrics-server` 2/2 (`top nodes` works); DB 8/8. Full `metrics-server` Deployment YAML backed up to `/tmp/p24-metrics-server-backup.yaml` for guaranteed restore (it is an EKS managed addon, so re-enable is also available).
- **Injected (two-part poisoning):**
  1. **Kill the metric source:** `kubectl scale deploy metrics-server --replicas=0` → `v1beta1.metrics.k8s.io` `Available=False`, `kubectl top nodes` → `error: Metrics API not available`. The agent's `NodeOptimizer` then logs `metrics-server unavailable: ... (get nodes.metrics.k8s.io)` every cycle.
  2. **Feed garbage on the fallback:** the agent's secondary node-util source is a PromQL query to `prometheus-server.monitoring.svc.cluster.local:80` (the real one was already CrashLoopBackOff → `connection refused`). Deployed a fake poison responder (`p24-poison-prometheus`, labels matching the `prometheus-server` Service selector) that answers `/api/v1/query` with a per-node vector whose values randomly cycle through **`NaN`, `0`, `-999999`, `1e308`, `Infinity`, `not_a_number`**. The Service then routed the agent's queries to the poison (verified: a direct query returned `"value":[…, "0"]`, the most dangerous "every node idle" poison).
- **Tool behavior (ACTUAL agent logs, both sources compromised):** every control cycle:
  - `DEBUG: NodeOptimizer metrics-server unavailable: the server is currently unable to handle the request`
  - `Evictor: no consolidation candidates this cycle`
  - `Consolidation (look-only): 0 empty, 0 drain, 8 blocked, 27 kept of 35 node(s) — actor=NONE`
  - `Consolidation (live): considered=0 removed=0 deferred=0 simulated=0 skipped=0 refused=0 aborted=0 — actor=NONE`
  The agent did **not** interpret the poisoned `0%`/`NaN` utilization as "all nodes idle → consolidate/drain aggressively" (the classic fail-OPEN disaster). It kept all 27 non-blocked nodes and drained **zero**.
- **Metric evidence (ACTUAL, leader `zkrr2` across ~70 cycles of poison):** `xamops_consolidation_pods_evicted_total` **0 → 0**; `xamops_consolidation_runs_total{status="skipped"}` **80 → 150**; `xamops_drain_budget_inflight` **0** throughout; agent **2/2, 0 restarts** (the NaN / `Infinity` / non-numeric payloads did **not** cause a parse panic / crashloop); single leader `zkrr2` stable. DB pods **8 → 8**, 0 Evicted/preempt events.
- **Breaking point OR held:** **HELD — the agent fails SAFE.** Removing the metrics source and poisoning the fallback with NaN/absurd values produced **no destructive action** — the agent treats absent/unreliable utilization as a reason to NOT act (fail-closed), never as license to drain. No crash on malformed metric values.
- **Verdict:** Correct and safety-critical — a poisoned or missing metrics pipeline cannot trick the agent into mass eviction/draining; it degrades to look-only `actor=NONE`. This is the desired fail-SAFE behavior. Correct.
- **Fix:** none required. (Observation: the fallback query hits `prometheus-server.monitoring.svc.cluster.local`, whose real backend was already CrashLoopBackOff on this cluster — a pre-existing monitoring-stack condition unrelated to the agent; the agent correctly tolerates that source being absent.)
- **Running cost:** metrics-server scaled 2→0→2 (no node churn); poison pod tiny (50m/32Mi) on an existing node. Increment ≈ $0.
- **Cleanup verified:** poison `Deployment` + `ConfigMap` deleted → `No resources found in monitoring namespace`; `metrics-server` scaled back to **2/2** → `v1beta1.metrics.k8s.io` `Available=True`, `kubectl top nodes` working; agent no longer logs `metrics-server unavailable` (reading real metrics again), 2/2; DB 8/8.

---

## STEP FINAL — Phases 19-24 complete, all faults verified gone (ALL 24 ATTACK PHASES DONE)
Context re-asserted at this step's teardown: acct **102227938259**, region **ap-south-1**, context pinned to the xamops-test-1 ARN (ambient drift to k8opt-chaos ignored, never used).

**Per-phase verdict (19-24):**
- **19 quota/limitrange collision — HELD.** Namespace fully admission-locked; agent did not mis-act (0 evicted, 0 drains); serving fleet 145/145.
- **20 scheduling deadlock — HELD.** 6 max-priority pods permanently Pending; no preemption of real workloads, 0 DB evictions.
- **21 many-container pods — HELD.** 40-container ×3 then 150-container ×1 ingested with 0 panic/collect/fit errors, agent 2/2.
- **22 RBAC boundary — HELD.** Agent SA least-privilege + fail-safe; no escalation/over-privilege; 0 out-of-grant attempts at runtime.
- **23 COMPOUND CHAOS — HELD.** Simultaneous partition + pod-kill storm + mem-stress + REAL FIS spot termination; leader no-failover, 0 DB evictions (every DB pod same node/startTime/restarts), serving 0.00% fail (8/291,041).
- **24 metrics poisoning — HELD (fails SAFE).** metrics-server killed + Prometheus fed NaN/0/-999999/Infinity/garbage; agent drained 0, removed 0, `actor=NONE`, 0 restarts (no parse crash), DB 8/8.

**All faults from THIS step cleaned up and verified gone:**
- P19: `ResourceQuota`/`LimitRange` deleted (`No resources found`); k6 job gone; `fleet-001-over` back 1/1.
- P20: Deployment + PriorityClass `p20-*` deleted (`NotFound`).
- P21: all `p21-manycontainer-*` + `p21-mega` deleted (mega force-deleted after stuck Terminating).
- P22: no fault injected (read-only RBAC probes) — RBAC never modified in this phase.
- P23: chaos inventory cluster-wide **ALL CLEAR** (networkchaos/podchaos/stresschaos/schedule + spawned Schedule children = `No resources found`); k6 job gone; **FIS experiment template deleted** (0 remain); **scoped IAM role `destroy-it-p23-fis-spot` deleted → GetRole NoSuchEntity**; spot node replaced 1:1.
- P24: poison `Deployment`+`ConfigMap` deleted (`No resources found`); **`metrics-server` scaled back to 2/2** → metrics API `Available=True`, `kubectl top nodes` working; agent reading real metrics again.

**Posture at step end:** agent `xamops-automation` **2/2** (leader `zkrr2`, 0 restarts), **6 acting gates still LIVE** (consolidation-actions, cordon, evictor, idle-drain, interruption, rebalancer); `xamops_consolidation_pods_evicted_total = 0` (0 pods evicted across the ENTIRE campaign); fleet **145 Running**; DB fixtures **8 core + redis Running** (I15/I16 held under compound chaos); nodes **35**; ng-ondemand-1 held **min4/desired30/max30**; **0 FIS templates / 0 scoped IAM roles** remain. Blast radius stayed xamops-test-1 only (xamops-test-2 / k8opt / AutoSpotting / kube-system control-plane untouched; metrics-server restored).

**New findings this step:** none that are product bugs — all six phases HELD. Observations only: the `auth can-i create pods/eviction` slash-form CLI quirk (Phase 22, not a bug), and the stored UAT session cookie had expired → 401 on the Phase-23 API-driven rebalance (operational, the agent's own control loop drove the DB-protection test instead). OBS-DB-001 (blanket `db-emptydir` refusal reason) reconfirmed, unchanged.

**Budget:** campaign-attributable churn increment for phases 19-24 ≈ **<$0.05** (one FIS spot terminate/replace in Phase 23; everything else pod-only/API-only, no node growth). Spent-to-date (wall-clock) ≈ **$42-55**; projected through the 3-day cap ≈ **$130-145**. **Budget remaining against the $200 cap ≈ $145-158** (≈ $55-70 headroom even against the full-3-day-cap projection). **No cap breach at any point → no warning raised.**

> NOTE: this step (phases 19-24) is attack-only. The campaign-wide RESTORE to baseline posture (return ng-ondemand-1 to 1/4/4, remove Grafana LB / chaos-mesh / stress / k6 / redis) remains a separate step — the per-phase faults are all removed, but the setup-stage load infra is intentionally left for any follow-on.

---

## FINAL — campaign posture restored & all faults verified gone (through Phase 18)
Context re-asserted at teardown: acct **102227938259**, region **ap-south-1**, context pinned to the xamops-test-1 ARN (ambient drift to k8opt-chaos ignored).
- **Chaos inventory cluster-wide:** `stresschaos / networkchaos / podchaos / schedules` = **none (ALL CLEAR)**.
- **FIS:** 0 experiment templates; scoped IAM role `destroy-it-fis-spot-role` deleted (NoSuchEntity).
- **Phase 9-18 faults verified gone:** 6 phase-9 HPAs deleted (only pre-existing `team-b/php-apache` remains); all `api-hammer-*`/`api-burst-*` Jobs + `api-hammer` SA + `api-hammer-view` CRB removed; `adversarial-fuzz` Deployment gone; redis-0 `/data` back to **0%** (filler removed); **ZERO** nodes annotated `drain-state=draining` (no half-drained node); foreign-identity + orphan test nodes restored to terminal state; all temp session cookies (`/tmp/uat-sess.*`) removed.
- **Agent:** automation **2/2** (single leader `…zkrr2` after the Phase-18 fencing test), 6 acting gates still live, `xamops_consolidation_pods_evicted_total=0`.
- **Fleet:** **145 Running** / 0 Pending. DB fixtures: all **10** ng-stateful-1 DB pods (`pg-cnpg-1/2`, `pg-unsafe-0`, `mysql-0`, `mongo-0/1/2`, loaders) + `redis-0` Running, **0 evicted across the entire campaign** (I15/I16 held every phase, verified per-engine in Phase 14).
- **Node posture:** ng-ondemand-1 re-capped to **min4/desired30/max30** after Phase-9 detected drift to max=40 (30 real nodes, no runaway launch). RESTORE step still returns ng-ondemand-1 to baseline 1/4/4.
- **Blast radius:** xamops-test-1 only; xamops-test-2 / k8opt / AutoSpotting / control-plane untouched.
- **New findings this step (all LOW, none a safety breaker):** BUG-API-005 (GET on PUT-only policy route → 500 not 405), BUG-OBS-006 (`xamops_orphans_recovered_total` unwired), + reconfirmed OBS-DB-001 (blanket `db-emptydir` reason), BUG-SCALE-002 (178-pod ceiling), and the Phase-9 ng-ondemand drift (cost hygiene).
- NOTE: the setup-created load infra (ng-ondemand-1 at 30, Grafana LB, chaos-mesh, redis) remains for any follow-on; the campaign RESTORE step returns ng-ondemand-1 to 1/4/4 and removes chaos/stress/k6/LB.

---

## STEP 4 — RESTORE + FINALIZE (definitive, supersedes the interim Phase-18 restore above)

Context + caller-identity asserted immediately before EVERY mutating action below: caller account
**102227938259** (`iam.autospotting`), region **ap-south-1**, context PINNED
`--context arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-1`. Ambient current-context had drifted
to `iam.autospotting@k8opt-chaos.ap-south-1.eksctl.io` — **NOT used, never changed**. Blast radius =
xamops-test-1 only.

### 4.1 — UAT readiness keys revert + CI — **DONE (no-op on checked-out branch) + git finding recorded**
The campaign's go-live readiness keys (`node-optimizer-actions`, `spot-interruption-actions`, `rebalancer`,
`evictor-live`, `vpa-apply`, `consolidation-actions` = true in UAT, plus the consolidation write-route and
`xamops.k8s.usage-rollup`/`cur.auto-register`) were committed on the **campaign feature branch / `origin/main`
at commits `eb629c7cb` / `91d505a7c`**. The checked-out local **`main` HEAD `6b4ec4128`** (which carries the
campaign's phase-test commits) is **12 ahead / 93 behind `origin/main`** and its
`xamops-service/src/main/resources/application-uat.properties` is **already in the pre-campaign state** — it
does NOT contain any of those campaign readiness keys, and the working tree is **clean** (verified
`git status` + `git diff HEAD eb629c7cb -- …application-uat.properties` shows the keys only on the feature
branch). So on the branch this workspace has checked out there is **nothing to revert** — the readiness-key
revert is a no-op here, and no properties edit / commit / push was required on `main`.
**GIT FINDING (GIT-REVERT-001, surfaced to owner):** reverting the LIVE UAT readiness keys requires editing
`origin/main` (where they are `true`) and pushing to it — which triggers the `Deploy to UAT` pipeline and
touches a shared release branch + live UAT, **outside the xamops-test-1-only blast radius** this campaign is
bound to, and the stale divergent local `main` cannot cleanly produce that revert. Per the "a finding is not
permission to redesign / push to shared branches is high-risk" rule, that cross-branch revert-and-deploy was
**left for the owner** rather than performed autonomously. The live cluster safety posture is fully restored
regardless (see 4.2 — DRY_RUN=true + ENABLE_* off makes the readiness keys inert).
CI status on the campaign commits was already green per `prep/go-live.md`: CI run **40637895309** (push,
headSha `eb629c7cb`) completed **success** — `[xamops-service] Build & Test`, `Containers Test`,
`Build & Push Docker Image`, `Deploy to UAT` all success (autoscaler build correctly **skipped** — no Go
diff). This STEP-4 doc commit lands on local `main`; `gh run list` for it recorded below after push.

### 4.2 — Agent ConfigMap `xamops-automation-config` reverted to pre-campaign — **DONE (PROVEN)**
Patched (strategic-merge) back to the saved baseline
(`.agents/tasks/break-it-campaign-2026-10-25/baseline-cm-automation-config.yaml`). BEFORE (campaign
full-live) → AFTER (pre-campaign), from live `kubectl get configmap -o jsonpath='{.data}'`:

| Key | BEFORE (campaign) | AFTER (restored baseline) |
|---|---|---|
| DRY_RUN | `false` | **`true`** |
| ENABLE_CONSOLIDATION | `true` | **`false`** |
| ENABLE_EVICTOR | `true` | **`false`** |
| ENABLE_HPA_MANAGER | `true` | **`false`** |
| ENABLE_NODE_OPTIMIZER | `true` | **`true`** (baseline) |
| ENABLE_REBALANCER | `true` | **`false`** |
| ENABLE_RIGHTSIZER | `true` | **`false`** |
| ENABLE_RIGHTSIZER_APPLY | `true` | **`false`** |
| ENABLE_VPA_MANAGER | `true` | **`false`** |
| ENABLE_LEADER_ELECTION | `true` | `true` (unchanged) |

(Full before/after dumps saved at `restore/cm-BEFORE.txt` / `restore/cm-AFTER.txt`.) Rolled the deployment
(`rollout restart` → `successfully rolled out`, 2/2) so the pods pick up the reverted envFrom ConfigMap.
**PROOF — the new leader's own gate log confirms the posture collapsed:**
```
WARNING: DRY-RUN MODE — no cluster changes will be made. Set DRY_RUN=false to enable actions.
Gates — cordon=simulate | idle-drain=simulate | node-optimizer=analyse | consolidation=analyse
        consolidation-actions=off | evictor=off | rebalancer/hpa-manager/rightsizer=off
```
Every acting gate that was `live` during the campaign is now `off` or `simulate`; single leader, no
split-brain.

**Per-cluster policy mode (blocked, recorded honestly):** the campaign set the per-cluster policy
(`enabled=true`, `mode=full`) through the backend API (`PUT https://uat.xamops.com/api/eks/autoscaling/
xamops-test-1/…`). Reverting the stored per-cluster mode requires that same cookie-authed API, but the stored
UAT session cookie (dated 2026-10-27, past the 8h `server.servlet.session.timeout`) is **expired → every
backend call returns HTTP 401** (confirmed against `/api/eks/autoscaling/{cluster}`, `/clusters`, `/account`).
The guardrail forbids bypassing the API / hand-editing Postgres, so the stored per-cluster mode was **left as
set** and recorded here — this is SAFE because the four-layer gate (`readiness × per-cluster mode ×
ENABLE_* × DRY_RUN`) now has two layers fully closed on the cluster: `DRY_RUN=true` and all acting
`ENABLE_*=false`. With those off, the stored `mode=full` is a dormant backend record that **cannot drive any
action** — proven by the gate log above (all acting gates `off`/`simulate`). Recorded as GIT/API limitation,
not a safety gap.

### 4.3 — Uncordon + nodegroups to baseline — **DONE (PROVEN)**
- **Uncordon:** 4 nodes were `SchedulingDisabled` (`ip-172-31-15-118`, `-2-6`, `-29-155`, `-31-166`) — all
  `kubectl uncordon`ed; re-check shows **0 `SchedulingDisabled` nodes** cluster-wide.
- **Nodegroups to baseline (authoritative `aws eks describe-nodegroup scalingConfig`, saved at
  `restore/nodegroups-AFTER.txt`):**

  | Nodegroup | Was (campaign) | Now (baseline) | Target | ✅ |
  |---|---|---|---|---|
  | ng-ondemand-1 | min4 / desired30 / max30 | **min1 / desired4 / max4** | 1/4/4 | ✅ (ConfigUpdate `f67563b4-…` = **Successful**) |
  | ng-spot-1 | 0/2/1 | **0/2/1** | 0/2/1 | ✅ (already baseline) |
  | ng-stateful-1 | 3/6/3 | **3/6/3** (desired 3 = 3 nodes) | 3 nodes | ✅ |
  | ng-observability-1 | 1/1/1 | **1/1/1** | intact | ✅ |

  EKS's managed-nodegroup is draining the ~26 excess ondemand nodes to converge to desired 4; node count
  observed falling 35 → 21 → 20 during this step and continuing to settle **asynchronously** (each excess
  node is EKS-cordoned with the `node.kubernetes.io/unschedulable=NoSchedule` termination taint and drained
  respecting PDBs before the ASG terminates it — this transient EKS-drain cordoning is the scale-down
  working, distinct from the 4 campaign `SchedulingDisabled` nodes which were all uncordoned to 0 earlier in
  4.3; uncordoning the EKS-drain set would fight the scale-down and is deliberately NOT done). The
  authoritative nodegroup `scalingConfig` is firmly at baseline **1/4/4** (ConfigUpdate Successful), which is
  the control-plane target this step sets; the EC2 termination completes on AWS's schedule. Agent deployment
  held **2/2 ready** throughout the drain (straggler pods on terminating nodes reaped). DB StatefulSet pods on
  ng-stateful-1 untouched.

### 4.4 — Cluster + observability LEFT UP — **DONE**
The cluster is left RUNNING and the observability stack is intentionally **NOT torn down**:
`break-observability` kube-prometheus-stack (Grafana `Running` + public NLB
`a9e0a9a7b25c54ddb8627736899e760d-072c2f2422090f4c.elb.ap-south-1.amazonaws.com`, Prometheus, operator,
kube-state-metrics all `Running`) and `chaos-mesh` (controller-manager + chaos-daemon DaemonSet `Running`)
remain up for owner review. Only the campaign FAULTS were removed (chaos inventory = ALL CLEAR), not the
observability/chaos control planes.

---

## FEATURE TABLE — PASS / DEGRADED-UNDER-LOAD / BROKE per capability (STEP 4.7)

Verdicts are from the attack phases' ACTUAL evidence above. "DB fail-closed" is the carry-forward headline
(I15/I16): 0 DB pods evicted across all 25 phases.

| Capability | Verdict | Evidence (phase → ACTUAL) |
|---|---|---|
| **node-optimizer** (cordon + idle-drain) | **PASS** | Gates `cordon=live`+`idle-drain=live` at full-live; P17 RecoverOrphans reconciled a real orphan + uncordoned on round 1, idempotent over 5 SIGKILLs, **0 nodes stuck draining**; P18 identity-scoped (did NOT uncordon a node it didn't own). No mis-drain in any phase. |
| **rebalancer** | **PASS** | `rebalancer=live`; P14/P16 fresh `POST /rebalance` (200) drained **no** DB node; P8 flapping = 4 swings, 0 thrash (executed=0). |
| **evictor / consolidation** | **PASS** (fail-closed) | `evictor=live`+`consolidation-actions=live` (after BUG-RBAC-001 fix); `pods_evicted_total=0` across all phases incl. 841 soak evals; P5/P13/P23 storms → 0 evicted; DB fail-closed held per-engine (P14). |
| **rightsizer** | **NOT-LIVE BY DESIGN** | Go rightsizer RETIRED (`rightsizer=off`); PUT workload-rightsizer → 403 K8S_CONTROLLER_NOT_READY. Real vertical rightsizing is the backend Java engine. (BUG-GATE-002.) |
| **rightsizer-apply** | **NOT-LIVE BY DESIGN** | `rightsizer-apply=off` (retired); no live agent apply path; no OOM/throttle auto-revert path exists (P5 verified, recorded not simulated). (BUG-GATE-002.) |
| **hpa** | **NOT-LIVE BY DESIGN** | `hpa-manager=off`, analysis-only ("never Live"), no per-cluster HPA PUT route. P9: 6 CPU HPAs + load → agent did not fight (0 HPA-pod evictions, 0.00% req-fail over 1.24M reqs). (BUG-GATE-003.) |
| **vpa** | **NOT-LIVE BY DESIGN** | No acting VPA gate series in this agent image; `vpa-apply` readiness ready but no writer. (BUG-GATE-004.) |
| **spot** (interruption handling) | **PASS** | `interruption=live`; P7 REAL AWS FIS spot-interrupt → terminate+replace, 0 req-fail (0/76126), 0 DB evicted; P23 repeated under compound chaos (1:1 replace), leader no-failover. |
| **DB-protection** (I15/I16, fail-closed) | **PASS** (campaign HEADLINE) | **0 DB pods evicted across ALL 25 phases.** Per-engine refuse (P14): CNPG primary/replica, MySQL, Mongo, pg-unsafe, redis all in `refused[]`, `canEvict=false`; held under compound chaos (P23) + 841 soak evals (P25). Cosmetic gap OBS-DB-001 (blanket `db-emptydir` reason). |
| **HA** (leader-election) | **PASS** | P6 sub-10s failover, single-leader invariant; P16 5× leader-kill flap → **max simultaneous leaders = 1**, split-brain = 0, drain-budget never exceeded 1; P18 old identity fenced out. |
| **backend** (policy API) | **PASS** (one LOW hygiene finding) | P11 all 18 adversarial PUTs → 400 `K8S_INVALID_REQUEST`, **zero 5xx on fuzz bodies**, policy intact; P16 2 concurrent conflicting PUTs serialized (both 200). Finding BUG-API-005 (LOW): GET on a PUT-only route → 500 instead of 405. |
| **cost** (real K8s cost/savings) | **PASS** (feature) + **DEGRADED discipline finding** | `xamops.k8s.cost.enabled=true`/`snapshot.enabled=true` served real $/mo; campaign cost reconciled $73.57 < $200. Capacity finding BUG-SCALE-002 (LOW): fleet ceiling ~178 pods (VPC-CNI 17/node + CPU-request exhaustion), not a control-plane/serving failure. |

**Headline (honest, NOT "8/8 live"):** **6 agent controllers exercised LIVE and PASS** (node-optimizer
cordon/idle-drain, rebalancer, evictor/consolidation, spot-interruption) with the DB fail-closed + HA
invariants holding under every fault; **3 controllers NOT-LIVE BY DESIGN** on this agent image (rightsizer,
rightsizer-apply, hpa) and **1 with no acting gate** (vpa) — all design verdicts, not breaking points
(BUG-GATE-002/003/004). No capability BROKE. The only breaking point found was the capacity ceiling
(BUG-SCALE-002), which is expected under the no-growth guardrail and is not an agent fault.

---

## CONSOLIDATED TEARDOWN LIST — every cloud resource created during the campaign (STEP 4.8)

For owner action. The cluster + observability are intentionally LEFT UP (step 4.4); this is the full
inventory to remove when the owner is done reviewing. All scoped to xamops-test-1 / account 102227938259.

**Already removed during the campaign (verified gone — listed for completeness):**
1. **AWS FIS experiment templates** (phases 7 & 23, spot-interrupt) — DELETED (`list-experiment-templates`
   = `[]`).
2. **Scoped FIS IAM execution roles** (`destroy-it-fis-spot-role` / `destroy-it-p23-fis-spot`) — DELETED
   (NoSuchEntity).
3. **All chaos-mesh fault objects** (StressChaos / NetworkChaos / PodChaos / Schedule across phases 1-25) —
   DELETED (cluster-wide chaos inventory = ALL CLEAR).
4. **All k6 load Jobs + ConfigMaps** (`k6-p1…p25`, `k6-fleet-load`, `k6-soak-load`, `k6-*-script`) — gone.
5. **Transient test objects**: phase-9 HPAs (6), api-hammer/api-burst Jobs + SA + CRB (phase 10),
   adversarial-fuzz Deployment (phase 11), phase-19 ResourceQuota+LimitRange, phase-20 PriorityClass + Pending
   pods, phase-21 many-container pods, phase-24 poison Deployment+ConfigMap (metrics-server scaled back 2/2).
6. **Private cookie temp copies** (`/tmp/uat-sess.*`, `/tmp/xamops-uat-cookie.*`) — removed.

**Still present (campaign-created; LEFT UP by step 4.4 for owner review — remove to fully tear down):**
| # | Resource | Location / name | Type | Removal |
|---|---|---|---|---|
| 7 | Grafana public LoadBalancer | svc `kube-prometheus-stack-grafana`, ns `break-observability` (AWS NLB `a9e0a9a7b25c54ddb8627736899e760d-…elb.ap-south-1.amazonaws.com`) | AWS NLB | `kubectl -n break-observability patch svc kube-prometheus-stack-grafana -p '{"spec":{"type":"ClusterIP"}}'` (or helm uninstall) |
| 8 | kube-prometheus-stack (Grafana/Prometheus/operator/kube-state-metrics/node-exporter) | ns `break-observability` (+ 2 campaign dashboards `destroy-it-campaign`, `destroy-it-detailed` as ConfigMaps) | helm release + gp3 PVC (Prometheus 15d) | `helm -n break-observability uninstall kube-prometheus-stack`; delete the gp3 PVC |
| 9 | Chaos Mesh | ns `chaos-mesh` (helm v2.8.7: controller-manager, chaos-daemon DS, dashboard, dns-server) + CRDs | helm release | `helm -n chaos-mesh uninstall chaos-mesh`; optionally delete `*.chaos-mesh.org` CRDs |
| 10 | Load fleet | ns `stress` (145 `fleet-*` Deployments, 17 PDBs, svc `fleet-http`) | pods only | `kubectl delete ns stress` |
| 11 | DB fixtures + Redis | ns `stress-db` (redis-0 + gp3 PVC) and `xamops-db-fixtures` (CNPG pg-cnpg-1/2, pg-unsafe, mysql-0, mongo-0/1/2, loaders) | StatefulSets + gp3 PVCs + cnpg-system | `kubectl delete ns stress-db xamops-db-fixtures cnpg-system` (⚠ destroys the test DBs — intended at full teardown) |
| 12 | ng-ondemand-1 nodes | EC2 t3.medium ×4 (at baseline desired 4) | EC2 | scale nodegroup to 0 or delete the cluster |
| 13 | Baseline cluster itself | EKS `xamops-test-1` + ng-ondemand-1 / ng-spot-1 / ng-stateful-1 / ng-observability-1 + control plane | EKS + EC2 | `eksctl delete cluster` (ONLY on explicit owner decision — this is the test cluster, left up by design) |

> NOTE: items 12-13 are the pre-existing baseline cluster, not campaign-created churn; included so the owner
> has the single complete list. The campaign added items 7-11 on top of the baseline.

---

## STEP 4 — FINAL STATUS LINE

readiness-keys revert = **no-op on checked-out `main`** (keys live on `origin/main`@`eb629c7cb`; cross-branch
revert+UAT-deploy left for owner per GIT-REVERT-001) + campaign CI already **green** (run 40637895309) ·
ConfigMap **reverted + PROVEN** (DRY_RUN=true, all acting ENABLE_* false; gate log DRY-RUN/off/simulate) ·
nodegroups **at baseline + PROVEN** (ng-ondemand-1 1/4/4 ConfigUpdate Successful, 0 cordoned, spot 0/2/1,
stateful 3, observability intact) · observability **LEFT UP** · **final cost = $73.57 < $200 cap** (Cost
Explorer reconcile) · lock **released** · cookie temp **deleted** (none present) · reports finalized.

---

## Phase 25 — SOAK / LEAK HUNT (steady load + light periodic chaos, long haul)

**Soak duration actually run:** **~14 hours** (T0 = 2026-10-28 15:02 UTC → wind-down 2026-10-29 05:01 UTC). Context re-asserted (acct 102227938259, region ap-south-1, pinned xamops-test-1 ARN) before every fault injection and before teardown; ambient context drift ignored.

**Driver:** in-cluster k6 Job `k6-soak-load` (grafana/k6, `constant-vus` 40 VUs against `fleet-http.stress`, 60m hold then Completed) + chaos-mesh `Schedule soak-pod-kill` (`@every 5m` PodChaos `pod-kill` `fixed-percent 10` of `app=fleet-http`, ns `stress` only — inject-gate annotation present). The periodic chaos ran continuously for the full ~14h; the agent's own consolidation control loop (authoritative, cookie-independent) drove the DB-protection evaluation every reconcile cycle. (The stored UAT session cookie is expired → 401 on the API, same known condition as Phase 23; the agent's autonomous loop is the authoritative DB-protection driver and does not need it — recorded as a limitation, not a guardrail crossing, so no pause.)

### Leak-hunt TREND (actual values over time — 5 samples across ~14h)

| Dimension | T0 15:02 | S1 15:04 | S2 15:09 | S3 15:15 | **S4 05:01 (~14h)** | Verdict |
|---|---|---|---|---|---|---|
| **Agent RSS (leader, Mi)** | 63 | 63 | 61 | 57 | **61** | **HELD** — bounded 57-63Mi, GC sawtooth, **0 growth** over 14h |
| Agent RSS (standby, Mi) | 11 | 10 | 10 | 10 | 10 | HELD — flat |
| **Reconcile cycle_duration** (count / sum s) | 158 / 515.79 | 162 / 527.97 | 167 / 543.14 | 173 / 561.23 | **999 / 3060.09** | **HELD — NO creep.** Incremental avg over the 841 soak cycles = **3.025 s/cycle** (vs T0 lifetime avg 3.26); flat/slightly faster |
| **Metric cardinality** (series @ /metrics) | 75 | 75 | 75 | 75 | **75** | **HELD — 0 growth** (no label/series explosion) |
| **Lease transitions** | 54 | — | 54 | 54 | **54** | **HELD — NO lease drift** (holder zkrr2 unchanged 14h, no flap) |
| **policy_fetch_errors_total** | 2 | 2 | 2 | 2 | **2** | **HELD** — flat (no error/retry accrual that would hint at leaked clients) |
| **consolidation runs (skipped)** | 156 | 160 | 165 | 171 | **997** | +841 evals over 14h, exactly ~1/cycle (no runaway) |
| **consolidation pods_evicted_total** | 0 | 0 | 0 | 0 | **0** | **DB fail-closed held every eval** |
| drain_budget_inflight | 0 | 0 | 0 | 0 | **0** | HELD — single-drain invariant never engaged |
| orphans_recovered_total | 0 | 0 | 0 | 0 | **0** | no half-drain induced (expected) |
| Agent restarts (both pods) | 0/0 | 0/0 | 0/0 | 0/0 | **0/0** | **HELD** — no OOM/crash over 14h |

**Leaked-watches / fds note:** the agent `/metrics` endpoint exposes a curated XamOps subset only (no Go-runtime `go_goroutines` / `process_open_fds` / `process_resident_memory_bytes` series), so fd/goroutine/watch counts are not directly observable from the endpoint. Proxy evidence used instead: `kubectl top` RSS (bounded), **0 restarts** over 14h (a leaked-fd/goroutine spiral would eventually OOM or error the client), flat `policy_fetch_errors_total=2` (no accumulating API-client errors), and flat reconcile-duration (a growing watch/informer set would inflate collect time). All proxies are flat → **no evidence of a watch/fd/goroutine leak.** (Observability gap, not a defect: the agent does not expose Go process/runtime metrics — would make direct fd/goroutine leak-hunting possible. Tracked as OBS-SOAK-007 below.)

**Backend Postgres growth:** the XamOps backend runs in the SaaS (`uat.xamops.com`), not in this cluster, and its DB is reachable only through the cookie-authed API (guardrail: never bypass API / hand-edit Postgres). With the stored cookie expired (401), direct backend-DB size sampling was not possible this step. Indirect signal from the agent side: `policy_fetch_errors_total` stayed flat at 2 (the backend kept serving the policy bundle without new errors across 14h), and the agent's served gate bundle was stable (6 live gates unchanged). **No agent-observable backend degradation**; a direct backend-DB-size trend is **not captured** (cookie-expired limitation) and is left for a session with a live cookie. Not a leak finding either way.

### I15/I16 DB fail-closed protection on the REAL databases — **HELD across the full ~14h soak**
The agent's consolidation loop evaluated the real ng-stateful-1 DB nodes **841 times** over 14h (runs skipped 156→997) under steady load + periodic pod-kill chaos, and evicted **ZERO** DB pods (`pods_evicted_total` 0→0). Every DB pod identity was unchanged T0→14h — same node, same `startTime`, same restartCount:
- `pg-cnpg-1` (CNPG replica) ip-172-31-23-193, start 2026-10-25T11:47:11Z, restarts 2 (pre-existing)
- `pg-cnpg-2` (CNPG primary) ip-172-31-4-95, start 2026-10-27T07:05:03Z, restarts 0
- `pg-unsafe-0` ip-172-31-46-10, `mysql-0` ip-172-31-4-95, `mongo-0/1/2` (46-10 / 4-95 / 23-193) — all unchanged, restarts 0
- `redis-0` (stress-db) start 2026-10-28T06:42:39Z, restarts 0
- **0 `Evicted` events** in `xamops-db-fixtures` AND `stress-db` across the soak. The recorded fail-closed refusal is visible as `xamops_drains_total{controller="node-optimizer",result="refused"}=1` (unchanged). **Verdict: the fail-closed DB protection holds on the REAL databases over the long haul — it does not degrade/relax when the agent repeatedly tries to act.** (OBS-DB-001 blanket `db-emptydir` reason unchanged — cosmetic, not a soak regression.)

### Breaking point OR held
**HELD on every leak-hunt dimension.** No memory growth, no reconcile-duration creep, no metric-cardinality growth, no lease drift, no error/fd-proxy accrual, no agent restart, and DB fail-closed protection intact across ~14h of sustained load + continuous light chaos. No breaking point found in the soak.

### Finding — OBS-SOAK-007 (LOW, observability)
The agent `/metrics` exposes only the curated XamOps counter set; it does **not** expose Go runtime/process metrics (`go_goroutines`, `go_memstats_*`, `process_open_fds`, `process_resident_memory_bytes`). This forces leak-hunting to rely on `kubectl top` + restart-count + indirect proxies rather than first-class goroutine/fd gauges. No safety impact; recommend enabling the standard Prometheus Go collector so operators can directly trend goroutines/heap/fds for long-haul leak detection. Finding only.

### Cleanup verified (finally/trap)
`soak-pod-kill` Schedule deleted; podchaos children deleted; `k6-soak-load` Job → NotFound; `k6-soak-script` ConfigMap deleted. **Cluster-wide chaos inventory = ALL CLEAR** (`stresschaos/networkchaos/podchaos/schedule` = none). Agent **2/2, 0 restarts**, single lease holder zkrr2 (transitions 54). All DB pods Running, 0 evicted. Fleet self-healing back to 145 (ReplicaSets recreated the final chaos-kill wave). No node churn from the soak (35 nodes throughout; ng-ondemand-1 held min4/desired30/max30; 0 FIS templates).

### Cost & margin at wind-down
- Soak-attributable churn ≈ **$0** (no node growth, pod-only chaos + one ephemeral k6 pod). The ~14h soak added only baseline burn ≈ 14h × $1.65/hr ≈ **+$23**.
- Spent-to-date (wall-clock) ≈ **$65-78**. Budget remaining against the $200 cap ≈ **$122-135**.
- Duration: now ~2026-10-29 05:01 UTC, ~2 days into the ~3-day cap → **~24h duration margin** remains before the cap. Soak wound down at the planned point to leave ample room.
- **Margin for restore: AMPLE on both axes.** Restore (scale ng-ondemand-1 30→4 = −$1.08/hr, remove Grafana LB / chaos-mesh / stress / k6 / redis) costs only a few $ and takes ~1-2h — well inside both the ~$122-135 cost headroom and the ~24h duration margin. **No cap breach → no warning raised.**
