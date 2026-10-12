#!/usr/bin/env python3
"""Compare two snapshot.py outputs and classify every difference (plan.md D16).

    ./compare_snapshots.py <before.json> <after.json> [--allow PATTERN ...]

Every difference is printed with one tag:
  MUTATION  a change the automation could have caused and the test must explain:
            an object whose managedFields gained an automation manager
            (/xamops|autoscaler|automation/), a node cordon/taint/xamops.io
            annotation change, a seeded Deployment generation/replica/request/limit
            change, a pod UID/containerID/restart change outside xamops-system,
            a census delta, an HPA/VPA/PDB change, a new or changed ledger ConfigMap
  ALLOWED   a MUTATION matched by --allow
  HARNESS   expected harness churn in xamops-system (rollouts by deploy.sh,
            kubectl-managed ConfigMaps) with no automation manager
  HPA       replica/generation/pod changes of a Deployment targeted by an HPA,
            with no automation manager on the Deployment (the HPA did it)

PATTERN is an fnmatch glob over "<kind>:<namespace>/<name>" (nodes: "node:<name>"),
e.g. --allow 'configmap:xamops-system/xamops-automation-state' --allow 'census'.
Exit status 0 when no un-allowed MUTATION remains, 1 otherwise.
"""
import argparse
import fnmatch
import json
import re
import sys

AUTOMATION = re.compile(r"xamops|autoscaler|automation", re.I)
SELF_NS = "xamops-system"
LEDGER = "xamops-automation-state"


def load(p):
    with open(p) as fh:
        return json.load(fh)


def key(row):
    return f"{row.get('namespace', '')}/{row['name']}" if "namespace" in row else row["name"]


def index(rows):
    return {key(r): r for r in rows or []}


def mgr_names(row):
    return {m.split("(")[0] for m in row.get("managers") or []}


def gained_automation(b, a):
    before = mgr_names(b) if b else set()
    return sorted(m for m in mgr_names(a) - before if AUTOMATION.search(m))


class Report:
    def __init__(self, allow):
        self.allow = allow
        self.lines = []
        self.mutations = 0

    def add(self, tag, ident, msg):
        if tag == "MUTATION" and any(fnmatch.fnmatch(ident, p) for p in self.allow):
            tag = "ALLOWED"
        if tag == "MUTATION":
            self.mutations += 1
        self.lines.append(f"{tag:9s} {ident}: {msg}")


def hpa_targets(snap):
    out = set()
    for h in snap.get("hpas") or []:
        t = h.get("target") or {}
        if t.get("kind") == "Deployment":
            out.add(f"{h['namespace']}/{t.get('name')}")
    return out


def compare(before, after, rep):
    # ── managedFields on every object kind ─────────────────────────────────
    for kind in ("nodes", "pods", "deployments", "replicasets", "hpas", "vpas", "pdbs", "configmaps"):
        bi, ai = index(before.get(kind)), index(after.get(kind))
        for k, a in ai.items():
            g = gained_automation(bi.get(k), a)
            if g:
                rep.add("MUTATION", f"{kind[:-1]}:{k}", f"managedFields gained automation manager(s) {g}")
    # ── nodes ───────────────────────────────────────────────────────────────
    bi, ai = index(before["nodes"]), index(after["nodes"])
    for k in sorted(set(bi) | set(ai)):
        b, a = bi.get(k), ai.get(k)
        if not b or not a:
            rep.add("MUTATION", f"node:{k}", "node added" if a else "node removed")
            continue
        for f in ("unschedulable", "taints", "xamopsAnnotations"):
            if b[f] != a[f]:
                rep.add("MUTATION", f"node:{k}", f"{f} {b[f]!r} -> {a[f]!r}")
    targets = hpa_targets(before) | hpa_targets(after)
    # ── deployments ─────────────────────────────────────────────────────────
    bi, ai = index(before["deployments"]), index(after["deployments"])
    for k in sorted(set(bi) | set(ai)):
        b, a = bi.get(k), ai.get(k)
        ns = k.split("/")[0]
        if not b or not a:
            rep.add("HARNESS" if ns == SELF_NS else "MUTATION", f"deployment:{k}",
                    "deployment added" if a else "deployment removed")
            continue
        diffs = [f"{f} {b[f]!r} -> {a[f]!r}" for f in ("generation", "replicas", "template") if b[f] != a[f]]
        if not diffs:
            continue
        if ns == SELF_NS:
            tag = "HARNESS"
        elif k in targets and b["template"] == a["template"] and not gained_automation(b, a):
            tag = "HPA"
        else:
            tag = "MUTATION"
        rep.add(tag, f"deployment:{k}", "; ".join(diffs))
    # ── replicasets ─────────────────────────────────────────────────────────
    bi, ai = index(before["replicasets"]), index(after["replicasets"])
    for k in sorted(set(bi) | set(ai)):
        b, a = bi.get(k), ai.get(k)
        ns = k.split("/")[0]
        row = a or b
        own = row.get("owner") or {}
        dep = f"{ns}/{own.get('name')}"
        if b and a and b["replicas"] == a["replicas"]:
            continue
        msg = ("replicaset added" if not b else "replicaset removed" if not a
               else f"replicas {b['replicas']} -> {a['replicas']}")
        if ns == SELF_NS:
            tag = "HARNESS"
        elif dep in targets and b and a:
            tag = "HPA"
        else:
            tag = "MUTATION"
        rep.add(tag, f"replicaset:{k}", msg)
    # ── pods ────────────────────────────────────────────────────────────────
    rs_owner = {}
    for snap in (before, after):
        for r in snap.get("replicasets") or []:
            own = r.get("owner") or {}
            rs_owner[f"{r['namespace']}/{r['name']}"] = f"{r['namespace']}/{own.get('name')}"

    def pod_dep(p):
        own = p.get("owner") or {}
        if own.get("kind") == "ReplicaSet":
            return rs_owner.get(f"{p['namespace']}/{own.get('name')}")
        return None

    bi, ai = index(before["pods"]), index(after["pods"])
    for k in sorted(set(bi) | set(ai)):
        b, a = bi.get(k), ai.get(k)
        ns = k.split("/")[0]
        row = a or b
        if b and a:
            diffs = []
            if b["uid"] != a["uid"]:
                diffs.append(f"uid {b['uid']} -> {a['uid']}")
            if b["containerIDs"] != a["containerIDs"]:
                diffs.append(f"containerIDs {b['containerIDs']} -> {a['containerIDs']}")
            if b["restartCount"] != a["restartCount"]:
                diffs.append(f"restartCount {b['restartCount']} -> {a['restartCount']}")
            if b["node"] != a["node"]:
                diffs.append(f"node {b['node']} -> {a['node']}")
            if b["phase"] != a["phase"]:
                diffs.append(f"phase {b['phase']} -> {a['phase']}")
            if b["resources"] != a["resources"]:
                diffs.append(f"resources {b['resources']} -> {a['resources']}")
            if not diffs:
                continue
            msg = "; ".join(diffs)
        else:
            msg = (f"pod added (node={a['node']} phase={a['phase']})" if a
                   else f"pod removed (node={b['node']} phase={b['phase']})")
        if ns == SELF_NS:
            tag = "HARNESS"
        elif pod_dep(row) in targets and not (b and a):
            tag = "HPA"
        else:
            tag = "MUTATION"
        rep.add(tag, f"pod:{k}", msg)
    # ── HPAs / VPAs / PDBs ──────────────────────────────────────────────────
    for kind, fields in (("hpas", ("minReplicas", "maxReplicas", "target", "metrics")),
                         ("vpas", ("updateMode", "target")),
                         ("pdbs", ("minAvailable", "maxUnavailable", "selector"))):
        bi, ai = index(before.get(kind)), index(after.get(kind))
        for k in sorted(set(bi) | set(ai)):
            b, a = bi.get(k), ai.get(k)
            if not b or not a:
                rep.add("MUTATION", f"{kind[:-1]}:{k}", f"{kind[:-1]} added" if a else f"{kind[:-1]} removed")
                continue
            diffs = [f"{f} {b[f]!r} -> {a[f]!r}" for f in fields if b[f] != a[f]]
            if diffs:
                rep.add("MUTATION", f"{kind[:-1]}:{k}", "; ".join(diffs))
    # ── ConfigMaps in xamops-system ─────────────────────────────────────────
    bi, ai = index(before.get("configmaps")), index(after.get("configmaps"))
    for k in sorted(set(bi) | set(ai)):
        b, a = bi.get(k), ai.get(k)
        name = k.split("/")[-1]
        if b and a and b.get("resourceVersion") == a.get("resourceVersion"):
            continue
        msg = ("configmap added" if not b else "configmap removed" if not a
               else f"resourceVersion {b.get('resourceVersion')} -> {a.get('resourceVersion')}")
        if name == LEDGER:
            if a and a.get("data"):
                msg += f"; data={json.dumps(a['data'], sort_keys=True)[:400]}"
            rep.add("MUTATION", f"configmap:{k}", msg)
        else:
            rep.add("HARNESS", f"configmap:{k}", msg + f" managers={(a or b).get('managers')}")
    # ── census ──────────────────────────────────────────────────────────────
    cb, ca = before["census"], after["census"]
    if cb["byPhase"] != ca["byPhase"] or cb["pending"] != ca["pending"]:
        rep.add("MUTATION", "census", f"{cb['byPhase']} pending={cb['pending']} -> {ca['byPhase']} pending={ca['pending']}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("before")
    ap.add_argument("after")
    ap.add_argument("--allow", action="append", default=[])
    a = ap.parse_args()
    before, after = load(a.before), load(a.after)
    rep = Report(a.allow)
    compare(before, after, rep)
    print(f"compare {a.before} ({before['takenAt']}) -> {a.after} ({after['takenAt']})")
    print(f"census before {before['census']['byPhase']} after {after['census']['byPhase']}")
    auto = []
    for kind in ("nodes", "pods", "deployments", "replicasets", "hpas", "vpas", "pdbs", "configmaps"):
        for r in after.get(kind) or []:
            hits = sorted(m for m in mgr_names(r) if AUTOMATION.search(m))
            if hits:
                auto.append(f"{kind[:-1]}:{key(r)} {hits}")
    print(f"objects carrying an automation manager in AFTER: {len(auto)}")
    for line in auto:
        print("  " + line)
    for line in rep.lines:
        print(line)
    if rep.mutations:
        print(f"RESULT: FAIL ({rep.mutations} un-allowed mutation(s))")
        return 1
    print("RESULT: PASS (no un-allowed mutation)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
