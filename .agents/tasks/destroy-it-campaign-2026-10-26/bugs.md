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
