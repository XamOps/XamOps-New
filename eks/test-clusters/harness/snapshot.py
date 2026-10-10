#!/usr/bin/env python3
"""Read-only cluster snapshot for the live re-test evidence (plan.md D16).

    ./snapshot.py <out.json> [--cluster xamops-test-1]

Captures, with `kubectl get -o json` only (no writes):
  nodes        unschedulable, taints, xamops.io/* annotations, managedFields managers
  pods         ns/name/uid/node/phase/owner/containerIDs/restartCount/requests/limits/qos/managers
  deployments  generation, replicas, template requests/limits, managedFields managers
  replicasets  ns/name/owner/desired/managers
  hpas         min/max/metrics/managers
  vpas         updateMode/managers
  pdbs         minAvailable/maxUnavailable/selector/disruptionsAllowed/managers
  configmaps   xamops-system: names + managedFields managers (+ data of the ledger ConfigMap)
  census       pod count per phase, pending pods by name

A manager entry is "<manager>(<operation>[/<subresource>])". The output is
sorted so two snapshots diff cleanly; compare_snapshots.py reads it.
"""
import argparse
import json
import subprocess
import sys
import time

CLUSTERS = ("xamops-test-1", "xamops-test-2")
LEDGER = "xamops-automation-state"


def kubectl(ctx, *args):
    # --show-managed-fields: kubectl strips managedFields from -o json by default.
    out = subprocess.run(["kubectl", "--context", ctx, *args, "--show-managed-fields"], check=True,
                         capture_output=True, text=True).stdout
    return json.loads(out)


def managers(meta):
    seen = set()
    for mf in meta.get("managedFields") or []:
        sub = mf.get("subresource")
        op = mf.get("operation", "")
        seen.add(f"{mf.get('manager', '?')}({op}{'/' + sub if sub else ''})")
    return sorted(seen)


def owner(meta):
    refs = meta.get("ownerReferences") or []
    ctl = [r for r in refs if r.get("controller")] or refs
    if not ctl:
        return None
    r = ctl[0]
    return {"kind": r.get("kind"), "name": r.get("name"), "uid": r.get("uid")}


def resources(containers):
    out = {}
    for c in containers or []:
        res = c.get("resources") or {}
        out[c["name"]] = {"requests": res.get("requests") or {}, "limits": res.get("limits") or {}}
    return out


def snap_nodes(ctx):
    rows = []
    for n in kubectl(ctx, "get", "nodes", "-o", "json")["items"]:
        m, s = n["metadata"], n.get("spec") or {}
        ready = next((c.get("status") for c in (n.get("status") or {}).get("conditions") or []
                      if c.get("type") == "Ready"), None)
        rows.append({
            "name": m["name"],
            "uid": m.get("uid"),
            "unschedulable": bool(s.get("unschedulable")),
            "taints": sorted((s.get("taints") or []), key=lambda t: (t.get("key", ""), t.get("effect", ""))),
            "xamopsAnnotations": {k: v for k, v in sorted((m.get("annotations") or {}).items())
                                  if k.startswith("xamops.io/")},
            "managers": managers(m),
            "ready": ready,
            "allocatable": (n.get("status") or {}).get("allocatable") or {},
        })
    return sorted(rows, key=lambda r: r["name"])


def snap_pods(ctx):
    rows = []
    for p in kubectl(ctx, "get", "pods", "-A", "-o", "json")["items"]:
        m, s, st = p["metadata"], p.get("spec") or {}, p.get("status") or {}
        cs = st.get("containerStatuses") or []
        rows.append({
            "namespace": m["namespace"],
            "name": m["name"],
            "uid": m.get("uid"),
            "node": s.get("nodeName"),
            "phase": st.get("phase"),
            "deleting": bool(m.get("deletionTimestamp")),
            "owner": owner(m),
            "containerIDs": {c["name"]: c.get("containerID") for c in cs},
            "restartCount": {c["name"]: c.get("restartCount", 0) for c in cs},
            "resources": resources(s.get("containers")),
            "qos": st.get("qosClass"),
            "managers": managers(m),
        })
    return sorted(rows, key=lambda r: (r["namespace"], r["name"]))


def snap_deployments(ctx):
    rows = []
    for d in kubectl(ctx, "get", "deployments", "-A", "-o", "json")["items"]:
        m, s, st = d["metadata"], d.get("spec") or {}, d.get("status") or {}
        rows.append({
            "namespace": m["namespace"],
            "name": m["name"],
            "uid": m.get("uid"),
            "generation": m.get("generation"),
            "observedGeneration": st.get("observedGeneration"),
            "replicas": s.get("replicas"),
            "readyReplicas": st.get("readyReplicas", 0),
            "template": resources(((s.get("template") or {}).get("spec") or {}).get("containers")),
            "managers": managers(m),
        })
    return sorted(rows, key=lambda r: (r["namespace"], r["name"]))


def snap_replicasets(ctx):
    rows = []
    for r in kubectl(ctx, "get", "replicasets", "-A", "-o", "json")["items"]:
        m, s = r["metadata"], r.get("spec") or {}
        rows.append({"namespace": m["namespace"], "name": m["name"], "uid": m.get("uid"),
                     "owner": owner(m), "replicas": s.get("replicas"),
                     "managers": managers(m)})
    return sorted(rows, key=lambda r: (r["namespace"], r["name"]))


def snap_hpas(ctx):
    rows = []
    for h in kubectl(ctx, "get", "hpa", "-A", "-o", "json")["items"]:
        m, s = h["metadata"], h.get("spec") or {}
        rows.append({"namespace": m["namespace"], "name": m["name"], "uid": m.get("uid"),
                     "minReplicas": s.get("minReplicas"), "maxReplicas": s.get("maxReplicas"),
                     "target": s.get("scaleTargetRef"), "metrics": s.get("metrics"),
                     "currentReplicas": (h.get("status") or {}).get("currentReplicas"),
                     "managers": managers(m)})
    return sorted(rows, key=lambda r: (r["namespace"], r["name"]))


def snap_vpas(ctx):
    try:
        items = kubectl(ctx, "get", "verticalpodautoscalers.autoscaling.k8s.io", "-A", "-o", "json")["items"]
    except subprocess.CalledProcessError:
        return None
    rows = []
    for v in items:
        m, s = v["metadata"], v.get("spec") or {}
        rows.append({"namespace": m["namespace"], "name": m["name"], "uid": m.get("uid"),
                     "updateMode": (s.get("updatePolicy") or {}).get("updateMode"),
                     "target": s.get("targetRef"), "managers": managers(m)})
    return sorted(rows, key=lambda r: (r["namespace"], r["name"]))


def snap_pdbs(ctx):
    rows = []
    for p in kubectl(ctx, "get", "pdb", "-A", "-o", "json")["items"]:
        m, s, st = p["metadata"], p.get("spec") or {}, p.get("status") or {}
        rows.append({"namespace": m["namespace"], "name": m["name"], "uid": m.get("uid"),
                     "minAvailable": s.get("minAvailable"), "maxUnavailable": s.get("maxUnavailable"),
                     "selector": s.get("selector"), "disruptionsAllowed": st.get("disruptionsAllowed"),
                     "labels": m.get("labels") or {}, "managers": managers(m)})
    return sorted(rows, key=lambda r: (r["namespace"], r["name"]))


def snap_configmaps(ctx):
    rows = []
    for c in kubectl(ctx, "-n", "xamops-system", "get", "configmaps", "-o", "json")["items"]:
        m = c["metadata"]
        row = {"namespace": m["namespace"], "name": m["name"], "uid": m.get("uid"),
               "resourceVersion": m.get("resourceVersion"), "managers": managers(m)}
        if m["name"] == LEDGER:
            row["data"] = c.get("data") or {}
        if m["name"] == "xamops-automation-config":
            row["data"] = c.get("data") or {}
        rows.append(row)
    return sorted(rows, key=lambda r: r["name"])


def census(pods):
    by_phase = {}
    for p in pods:
        by_phase[p["phase"]] = by_phase.get(p["phase"], 0) + 1
    pending = sorted(f"{p['namespace']}/{p['name']}" for p in pods if p["phase"] == "Pending")
    return {"byPhase": dict(sorted(by_phase.items())), "total": len(pods), "pending": pending}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("out")
    ap.add_argument("--cluster", default="xamops-test-1", choices=CLUSTERS)
    a = ap.parse_args()
    ctx = f"arn:aws:eks:ap-south-1:102227938259:cluster/{a.cluster}"
    pods = snap_pods(ctx)
    snap = {
        "takenAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "context": ctx,
        "nodes": snap_nodes(ctx),
        "pods": pods,
        "deployments": snap_deployments(ctx),
        "replicasets": snap_replicasets(ctx),
        "hpas": snap_hpas(ctx),
        "vpas": snap_vpas(ctx),
        "pdbs": snap_pdbs(ctx),
        "configmaps": snap_configmaps(ctx),
        "census": census(pods),
    }
    with open(a.out, "w") as fh:
        json.dump(snap, fh, indent=1, sort_keys=True)
    c = snap["census"]
    print(f"snapshot {a.out} at {snap['takenAt']}: nodes={len(snap['nodes'])} pods={c['total']} "
          f"{c['byPhase']} deployments={len(snap['deployments'])} pdbs={len(snap['pdbs'])} "
          f"vpas={len(snap['vpas'] or [])} configmaps(xamops-system)={len(snap['configmaps'])}")


if __name__ == "__main__":
    sys.exit(main())
