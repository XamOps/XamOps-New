#!/usr/bin/env python3
"""Check the autoscaler's pushed telemetry against plan.md section 4 / D2.

    ./check_telemetry.py <capture-dir> [--expect-dry-run] [--max-simulated-drains 1]

<capture-dir> is a pull_captures.py destination (reads <dir>/ingest/*.json).
Only autoscaler pushes are checked (agentVersion containing "autoscaler"); one
ingest = one autoscaler cycle. Checks:
  - every autoscaling-actions record has boolean dryRun and verified
  - every autoscaling-events entry uses only the 8 AutoscalingEventDto keys and
    has a paired action record (same id) in the same push
  - an event whose action is not (succeeded AND verified AND not dryRun) never has
    severity "success" and its message never contains completed|drained|consolidated|applied;
    simulated events start "DRY-RUN (simulated)"
  - autoscaling-rightsizing rows use only the 14 WorkloadRecommendation keys
  - rebalance-status / node-drain-progress / node-optimizer-report /
    hpa-recommendations / evictor-candidates rows carry a boolean dryRun
  - node-optimizer estimatedSavingsPct and every spotCandidates savingsPct /
    interruptible are null
  - simulated drains per cycle (action "drain", status simulated, any controller)
    <= --max-simulated-drains
  --expect-dry-run additionally requires dryRun=true everywhere, no succeeded
  action, no applied=true rightsizing/HPA row, no drained/completed status.
Exit 0 on PASS, 1 on FAIL.
"""
import argparse
import glob
import json
import os
import re
import sys

EVENT_KEYS = {"id", "timestamp", "type", "severity", "message", "details", "nodeCount", "savingsDelta"}
RIGHTSIZING_KEYS = {"namespace", "workloadName", "workloadKind", "containerName", "currentCpuRequest",
                    "currentMemRequest", "recommendedCpu", "recommendedMem", "p80Cpu", "p100Mem",
                    "estimatedSavingsPercent", "applied", "appliedAt", "reason"}
STATUSES = {"simulated", "succeeded", "failed", "refused", "skipped", "aborted"}
OUTCOME = re.compile(r"completed|drained|consolidated|applied", re.I)
DRYRUN_COLLECTORS = ("autoscaling-rebalance-status", "node-drain-progress", "node-optimizer-report",
                     "hpa-recommendations", "autoscaling-evictor-candidates")


def verified_live(a):
    return a.get("status") == "succeeded" and a.get("verified") is True and a.get("dryRun") is False


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("capture_dir")
    ap.add_argument("--expect-dry-run", action="store_true")
    ap.add_argument("--max-simulated-drains", type=int, default=1)
    a = ap.parse_args()

    fails, notes = [], []
    cycles = []
    totals = {}
    for path in sorted(glob.glob(os.path.join(a.capture_dir, "ingest", "*.json"))):
        doc = json.load(open(path))
        p = doc.get("payload") or {}
        if "autoscaler" not in str(p.get("agentVersion", "")):
            continue
        name = os.path.basename(path)
        batches = {}
        for b in p.get("batches") or []:
            batches.setdefault(b.get("collector"), []).extend(b.get("items") or [])
        for c, items in batches.items():
            totals[c] = totals.get(c, 0) + len(items)
        actions = batches.get("autoscaling-actions", [])
        by_id = {}
        for rec in actions:
            by_id[rec.get("id")] = rec
            where = f"{name} action {rec.get('id')} {rec.get('controller')}/{rec.get('action')} {rec.get('target')}"
            if not isinstance(rec.get("dryRun"), bool):
                fails.append(f"{where}: dryRun is not a boolean ({rec.get('dryRun')!r})")
            if not isinstance(rec.get("verified"), bool):
                fails.append(f"{where}: verified is not a boolean ({rec.get('verified')!r})")
            if rec.get("status") not in STATUSES:
                fails.append(f"{where}: unknown status {rec.get('status')!r}")
            if a.expect_dry_run:
                if rec.get("dryRun") is not True:
                    fails.append(f"{where}: dryRun={rec.get('dryRun')!r} under DRY_RUN")
                if rec.get("status") == "succeeded":
                    fails.append(f"{where}: status succeeded under DRY_RUN")
        for ev in batches.get("autoscaling-events", []):
            where = f"{name} event {ev.get('id')}"
            extra = set(ev) - EVENT_KEYS
            if extra:
                fails.append(f"{where}: keys outside AutoscalingEventDto {sorted(extra)}")
            rec = by_id.get(ev.get("id"))
            if rec is None:
                fails.append(f"{where}: no paired autoscaling-actions record")
                continue
            if not verified_live(rec):
                if ev.get("severity") == "success":
                    fails.append(f"{where}: severity success for a {rec.get('status')} action (verified={rec.get('verified')}, dryRun={rec.get('dryRun')})")
                if OUTCOME.search(ev.get("message", "")):
                    fails.append(f"{where}: outcome word in non-verified message {ev.get('message')!r}")
                if rec.get("status") == "simulated" and not ev.get("message", "").startswith("DRY-RUN (simulated)"):
                    fails.append(f"{where}: simulated event without the DRY-RUN prefix {ev.get('message')!r}")
        for row in batches.get("autoscaling-rightsizing", []):
            extra = set(row) - RIGHTSIZING_KEYS
            if extra:
                fails.append(f"{name} rightsizing {row.get('namespace')}/{row.get('workloadName')}: extra keys {sorted(extra)}")
            if a.expect_dry_run and row.get("applied"):
                fails.append(f"{name} rightsizing {row.get('namespace')}/{row.get('workloadName')}: applied=true under DRY_RUN")
        for c in DRYRUN_COLLECTORS:
            for row in batches.get(c, []):
                ident = row.get("nodeName") or row.get("workloadName") or row.get("operationId") or "-"
                if not isinstance(row.get("dryRun"), bool):
                    fails.append(f"{name} {c} {ident}: dryRun missing or not boolean")
                elif a.expect_dry_run and row.get("dryRun") is not True:
                    fails.append(f"{name} {c} {ident}: dryRun=false under DRY_RUN")
                if a.expect_dry_run and row.get("status") in ("drained", "completed"):
                    fails.append(f"{name} {c} {ident}: status {row.get('status')} under DRY_RUN")
                if a.expect_dry_run and row.get("applied"):
                    fails.append(f"{name} {c} {ident}: applied=true under DRY_RUN")
        for rep in batches.get("node-optimizer-report", []):
            if rep.get("estimatedSavingsPct") is not None:
                fails.append(f"{name} node-optimizer-report: estimatedSavingsPct={rep.get('estimatedSavingsPct')!r}")
            for sc in rep.get("spotCandidates") or []:
                for f in ("savingsPct", "interruptible"):
                    if sc.get(f) is not None:
                        fails.append(f"{name} spotCandidate {sc.get('nodeName')}: {f}={sc.get(f)!r} (must be null)")
        sim = [r for r in actions if r.get("action") == "drain" and r.get("status") == "simulated"]
        refused = [r for r in actions if r.get("action") in ("drain", "cordon") and r.get("status") == "refused"]
        skipped = [r for r in actions if r.get("status") == "skipped"]
        if len(sim) > a.max_simulated_drains:
            fails.append(f"{name}: {len(sim)} simulated drains in one cycle (max {a.max_simulated_drains}): "
                         + ", ".join(f"{r.get('controller')}:{r.get('target')}" for r in sim))
        cycles.append((name, doc.get("receivedAt"), len(actions), len(batches.get("autoscaling-events", [])),
                       [f"{r.get('controller')}:{r.get('target')}" for r in sim],
                       [f"{r.get('controller')}/{r.get('action')}:{r.get('target')}" for r in refused],
                       [f"{r.get('controller')}/{r.get('action')}:{r.get('target')}" for r in skipped],
                       sorted(batches)))

    print(f"check_telemetry {a.capture_dir} expect_dry_run={a.expect_dry_run} cycles={len(cycles)}")
    for c, n in sorted(totals.items()):
        print(f"  total {c}: {n} item(s)")
    for name, ts, na, ne, sim, ref, skp, cols in cycles:
        print(f"cycle {ts} {name[:5]} actions={na} events={ne} simulatedDrains={len(sim)} {sim} "
              f"refusedNodeActions={len(ref)} {ref} skipped={len(skp)} {skp}")
        print(f"      collectors={cols}")
    if not cycles:
        fails.append("no autoscaler ingest in the capture directory")
    for n in notes:
        print("NOTE " + n)
    for f in fails:
        print("FAIL " + f)
    print("RESULT: " + ("FAIL" if fails else "PASS") + f" ({len(fails)} failure(s))")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
