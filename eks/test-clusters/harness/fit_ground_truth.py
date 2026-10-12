#!/usr/bin/env python3
"""Independent ground truth for the autoscaler's drain-fit simulator (stdlib only).

Implements plan.md section 3 "Fit semantics v1" from the spec, NOT from the Go code
(xamops-autoscaler/internal/fitsim). Both must agree on every node.

Input: the JSON of
    kubectl get nodes,pods,pdb,rs,sts -A -o json
(a v1 List; a file path or "-" for stdin). Read-only: this script never talks to a
cluster.

    ./fit_ground_truth.py snapshot.json --critical kube-system,monitoring \\
        --self-namespace xamops-system [--json] [--set node-a,node-b]
    ./fit_ground_truth.py snapshot.json --policy policies/41-evictor-positive.json

Guards default to the DefaultBundle (all protections on); --policy reads
safetyGuards from a policy fixture; --no-protect-* switch single guards off.
Without --set every node is evaluated as a single-node drain set.

Reason codes (joined with "; " in this order):
  refused: pod-affinity, pod-anti-affinity, topology-spread, pvc-volume,
           ephemeral-volume, host-port, extended-resource:<names>
  blocked: critical-namespace, self-namespace, statefulset,
           single-replica | replicas-unknown,
           no-controller | job-owner | unknown-owner-kind:<Kind>,
           safe-to-evict-false, multiple-pdbs:<pdbs>,
           pdb-no-disruptions:<pdb> (each), pdb-stale:<pdb> (each)
"""
import argparse
import json
import math
import re
import sys
from fractions import Fraction

MIRROR = "kubernetes.io/config.mirror"
SAFE_TO_EVICT = "cluster-autoscaler.kubernetes.io/safe-to-evict"
DEFAULT_CRITICAL = ["kube-system", "monitoring", "ingress-nginx", "xamops-system"]

# ── Kubernetes quantities ────────────────────────────────────────────────────

_BIN = {"Ki": 2 ** 10, "Mi": 2 ** 20, "Gi": 2 ** 30, "Ti": 2 ** 40, "Pi": 2 ** 50, "Ei": 2 ** 60}
_DEC = {"n": Fraction(1, 10 ** 9), "u": Fraction(1, 10 ** 6), "m": Fraction(1, 1000), "": Fraction(1),
        "k": Fraction(10 ** 3), "M": Fraction(10 ** 6), "G": Fraction(10 ** 9), "T": Fraction(10 ** 12),
        "P": Fraction(10 ** 15), "E": Fraction(10 ** 18)}
_QTY = re.compile(r"^([+-]?)(\d+(?:\.\d*)?|\.\d+)(.*)$")


def quantity(s):
    """Parse a Kubernetes resource quantity into an exact Fraction."""
    if isinstance(s, (int, float)):
        return Fraction(s)
    m = _QTY.match(str(s).strip())
    if not m:
        raise ValueError(f"bad quantity {s!r}")
    sign, num, suffix = m.groups()
    value = Fraction(num)
    if suffix in _BIN:
        value *= _BIN[suffix]
    elif suffix in _DEC:
        value *= _DEC[suffix]
    elif re.fullmatch(r"[eE][+-]?\d+", suffix):
        value *= Fraction(10) ** int(suffix[1:])
    else:
        raise ValueError(f"bad quantity suffix {s!r}")
    return -value if sign == "-" else value


def millicores(s):
    return math.ceil(quantity(s) * 1000) if s is not None else 0


def whole(s):
    return math.ceil(quantity(s)) if s is not None else 0


# ── helpers over raw objects ─────────────────────────────────────────────────

def meta(o):
    return o.get("metadata") or {}


def key(o):
    return f"{meta(o).get('namespace', '')}/{meta(o)['name']}"


def is_bound(pod):
    return bool((pod.get("spec") or {}).get("nodeName")) and \
        (pod.get("status") or {}).get("phase") not in ("Succeeded", "Failed")


def controller_ref(pod):
    for ref in meta(pod).get("ownerReferences") or []:
        if ref.get("controller") is True:
            return ref
    return None


def container_need(container, status_by_name):
    """(cpu millicores, memory bytes) = max(spec request, allocatedResources)."""
    req = (container.get("resources") or {}).get("requests") or {}
    alloc = (status_by_name.get(container.get("name")) or {}).get("allocatedResources") or {}
    cpu = max(millicores(req.get("cpu")), millicores(alloc.get("cpu")))
    mem = max(whole(req.get("memory")), whole(alloc.get("memory")))
    return cpu, mem


def effective(pod):
    spec, status = pod.get("spec") or {}, pod.get("status") or {}
    regular_status = {s.get("name"): s for s in status.get("containerStatuses") or []}
    init_status = {s.get("name"): s for s in status.get("initContainerStatuses") or []}
    app = [container_need(c, regular_status) for c in spec.get("containers") or []]
    app_cpu, app_mem = sum(c for c, _ in app), sum(m for _, m in app)
    side_cpu = side_mem = 0
    init_peak_cpu = init_peak_mem = 0
    for c in spec.get("initContainers") or []:
        cpu, mem = container_need(c, init_status)
        if c.get("restartPolicy") == "Always":      # native sidecar: runs alongside the app
            side_cpu, side_mem = side_cpu + cpu, side_mem + mem
        else:                                        # runs with the sidecars declared before it
            init_peak_cpu = max(init_peak_cpu, cpu + side_cpu)
            init_peak_mem = max(init_peak_mem, mem + side_mem)
    overhead = spec.get("overhead") or {}
    cpu = max(app_cpu + side_cpu, init_peak_cpu) + millicores(overhead.get("cpu"))
    mem = max(app_mem + side_mem, init_peak_mem) + whole(overhead.get("memory"))
    return cpu, mem


def refusals(pod):
    spec = pod.get("spec") or {}
    out = []
    affinity = spec.get("affinity") or {}
    if (affinity.get("podAffinity") or {}).get("requiredDuringSchedulingIgnoredDuringExecution"):
        out.append("pod-affinity")
    if (affinity.get("podAntiAffinity") or {}).get("requiredDuringSchedulingIgnoredDuringExecution"):
        out.append("pod-anti-affinity")
    if any(t.get("whenUnsatisfiable") == "DoNotSchedule" for t in spec.get("topologySpreadConstraints") or []):
        out.append("topology-spread")
    volumes = spec.get("volumes") or []
    if any(v.get("persistentVolumeClaim") is not None for v in volumes):
        out.append("pvc-volume")
    if any(v.get("ephemeral") is not None for v in volumes):
        out.append("ephemeral-volume")
    containers = (spec.get("initContainers") or []) + (spec.get("containers") or [])
    if any((p.get("hostPort") or 0) > 0 for c in containers for p in c.get("ports") or []):
        out.append("host-port")
    other = sorted({r for c in containers for r in ((c.get("resources") or {}).get("requests") or {})
                    if r not in ("cpu", "memory")})
    if other:
        out.append("extended-resource:" + ",".join(other))
    return out


def selector_matches(sel, lbls):
    """metav1.LabelSelector semantics (PDBs): None matches nothing, {} everything."""
    if sel is None:
        return False
    for k, v in (sel.get("matchLabels") or {}).items():
        if lbls.get(k) != v:
            return False
    for e in sel.get("matchExpressions") or []:
        k, op, vals = e.get("key"), e.get("operator"), e.get("values") or []
        if op == "In" and not (k in lbls and lbls[k] in vals):
            return False
        if op == "NotIn" and k in lbls and lbls[k] in vals:
            return False
        if op == "Exists" and k not in lbls:
            return False
        if op == "DoesNotExist" and k in lbls:
            return False
        if op not in ("In", "NotIn", "Exists", "DoesNotExist"):
            return False
    return True


def _int(s):
    try:
        if not re.fullmatch(r"[+-]?\d+", s):
            return None
        return int(s)
    except (TypeError, ValueError):
        return None


def expression_holds(e, lbls):
    k, op, vals = e.get("key"), e.get("operator"), e.get("values") or []
    present = k in lbls
    if op == "In":
        return bool(vals) and present and lbls[k] in vals
    if op == "NotIn":
        return bool(vals) and (not present or lbls[k] not in vals)
    if op == "Exists":
        return not vals and present
    if op == "DoesNotExist":
        return not vals and not present
    if op in ("Gt", "Lt"):
        if len(vals) != 1 or _int(vals[0]) is None or not present:
            return False
        have, bound = _int(lbls[k]), _int(vals[0])
        if have is None:
            return False
        return have > bound if op == "Gt" else have < bound
    return False


def node_affinity_ok(pod, node):
    required = (((pod.get("spec") or {}).get("affinity") or {}).get("nodeAffinity") or {}) \
        .get("requiredDuringSchedulingIgnoredDuringExecution")
    if required is None:
        return True
    lbls = meta(node).get("labels") or {}
    name = meta(node)["name"]
    for term in required.get("nodeSelectorTerms") or []:
        exprs, fields = term.get("matchExpressions") or [], term.get("matchFields") or []
        if not exprs and not fields:
            continue                                  # an empty term selects nothing
        ok = all(expression_holds(e, lbls) for e in exprs)
        for f in fields:
            vals = f.get("values") or []
            if f.get("key") != "metadata.name" or f.get("operator") not in ("In", "NotIn"):
                ok = False
            elif f.get("operator") == "In" and name not in vals:
                ok = False
            elif f.get("operator") == "NotIn" and name in vals:
                ok = False
        if ok:
            return True
    return False


def tolerated(taint, tolerations):
    for t in tolerations:
        if t.get("effect") and t["effect"] != taint.get("effect"):
            continue
        if t.get("key") and t["key"] != taint.get("key"):
            continue
        op = t.get("operator") or "Equal"
        if op == "Exists":
            return True
        if op == "Equal" and (t.get("value") or "") == (taint.get("value") or ""):
            return True
    return False


def node_accepts(pod, node):
    spec = pod.get("spec") or {}
    tolerations = spec.get("tolerations") or []
    for taint in (node.get("spec") or {}).get("taints") or []:
        if taint.get("effect") in ("NoSchedule", "NoExecute") and not tolerated(taint, tolerations):
            return False
    lbls = meta(node).get("labels") or {}
    if any(lbls.get(k) != v or k not in lbls for k, v in (spec.get("nodeSelector") or {}).items()):
        return False
    return node_affinity_ok(pod, node)


def ready(node):
    for c in (node.get("status") or {}).get("conditions") or []:
        if c.get("type") == "Ready":
            return c.get("status") == "True"
    return False


# ── the model ────────────────────────────────────────────────────────────────

class Cluster:
    def __init__(self, items):
        kinds = {}
        for it in items:
            kinds.setdefault(it.get("kind"), []).append(it)
        self.nodes = {meta(n)["name"]: n for n in kinds.get("Node", [])}
        self.pods = kinds.get("Pod", [])
        self.pdbs = kinds.get("PodDisruptionBudget", [])
        self.replicas = {}
        for kind in ("ReplicaSet", "StatefulSet"):
            for o in kinds.get(kind, []):
                n = (o.get("spec") or {}).get("replicas")
                self.replicas[(kind, meta(o).get("namespace"), meta(o)["name"])] = (1 if n is None else n, meta(o).get("uid"))
        self.eff = {id(p): effective(p) for p in self.pods}

    def owner_replicas(self, pod, ref):
        hit = self.replicas.get((ref.get("kind"), meta(pod).get("namespace"), ref.get("name")))
        if hit is None:
            return None
        n, uid = hit
        if ref.get("uid") and uid and ref["uid"] != uid:
            return None
        return n

    def pdbs_for(self, pod):
        ns, lbls = meta(pod).get("namespace"), meta(pod).get("labels") or {}
        return sorted((b for b in self.pdbs if meta(b).get("namespace") == ns
                       and selector_matches((b.get("spec") or {}).get("selector"), lbls)), key=key)

    def blockers(self, pod, guards):
        ns = meta(pod).get("namespace")
        out = []
        if guards["protectCriticalNamespaces"] and ns in guards["criticalNamespaces"]:
            out.append("critical-namespace")
        if guards["selfNamespace"] and ns == guards["selfNamespace"]:
            out.append("self-namespace")
        ref = controller_ref(pod)
        if ref is None:
            out.append("no-controller")
        elif ref.get("kind") == "Job":
            out.append("job-owner")
        elif ref.get("kind") not in ("ReplicaSet", "StatefulSet", "ReplicationController"):
            out.append("unknown-owner-kind:" + str(ref.get("kind")))
        else:
            if ref.get("kind") == "StatefulSet" and guards["protectStatefulSets"]:
                out.append("statefulset")
            if guards["protectSingleReplicas"]:
                n = self.owner_replicas(pod, ref)
                if n is None:
                    out.append("replicas-unknown")
                elif n == 1:
                    out.append("single-replica")
        if (meta(pod).get("annotations") or {}).get(SAFE_TO_EVICT) == "false":
            out.append("safe-to-evict-false")
        pdbs = self.pdbs_for(pod)
        if len(pdbs) > 1:
            out.append("multiple-pdbs:" + ",".join(key(b) for b in pdbs))
        out += ["pdb-no-disruptions:" + key(b) for b in pdbs
                if ((b.get("status") or {}).get("disruptionsAllowed") or 0) < 1]
        out += ["pdb-stale:" + key(b) for b in pdbs
                if ((b.get("status") or {}).get("observedGeneration") or 0) < (meta(b).get("generation") or 0)]
        return out

    def drain(self, drain_set, guards):
        drain_set = set(drain_set)
        free = {}
        for name, n in self.nodes.items():
            alloc = (n.get("status") or {}).get("allocatable") or {}
            free[name] = [millicores(alloc.get("cpu")), whole(alloc.get("memory")), whole(alloc.get("pods"))]
        for p in self.pods:
            if is_bound(p) and p["spec"]["nodeName"] in free:
                cpu, mem = self.eff[id(p)]
                f = free[p["spec"]["nodeName"]]
                f[0], f[1], f[2] = f[0] - cpu, f[1] - mem, f[2] - 1

        excluded, refused, blocked, to_place = [], [], [], []
        pdb_hits = {}
        for p in self.pods:
            if not is_bound(p) or p["spec"]["nodeName"] not in drain_set:
                continue
            ref = controller_ref(p)
            if MIRROR in (meta(p).get("annotations") or {}) or (ref is not None and ref.get("kind") == "DaemonSet"):
                excluded.append(key(p))
                continue
            for b in self.pdbs_for(p):
                hit = pdb_hits.setdefault(key(b), {
                    "pdb": key(b), "matchedInSet": 0,
                    "disruptionsAllowed": (b.get("status") or {}).get("disruptionsAllowed") or 0,
                    "stale": ((b.get("status") or {}).get("observedGeneration") or 0) < (meta(b).get("generation") or 0)})
                hit["matchedInSet"] += 1
            why = refusals(p)
            if why:
                refused.append({"pod": key(p), "reason": "; ".join(why)})
                continue
            why = self.blockers(p, guards)
            if why:
                blocked.append({"pod": key(p), "reason": "; ".join(why)})
            to_place.append(p)

        def order(p):
            cpu, mem = self.eff[id(p)]
            return (-cpu, -mem, meta(p).get("namespace"), meta(p)["name"])

        targets = sorted(name for name, n in self.nodes.items()
                         if name not in drain_set and not (n.get("spec") or {}).get("unschedulable") and ready(n))
        moves, strands = [], []
        for p in sorted(to_place, key=order):
            cpu, mem = self.eff[id(p)]
            for t in targets:
                f = free[t]
                if f[0] >= cpu and f[1] >= mem and f[2] >= 1 and node_accepts(p, self.nodes[t]):
                    f[0], f[1], f[2] = f[0] - cpu, f[1] - mem, f[2] - 1
                    moves.append({"pod": key(p), "from": p["spec"]["nodeName"], "to": t, "cpuMillis": cpu, "memBytes": mem})
                    break
            else:
                strands.append(key(p))

        errors = [f"node {n} not in the snapshot" for n in sorted(drain_set) if n not in self.nodes]
        return {
            "nodes": sorted(drain_set),
            "drainable": bool(drain_set) and not errors and not refused and not blocked and not strands,
            "excludedCount": len(excluded),
            "excluded": sorted(excluded),
            "refused": sorted(refused, key=lambda f: f["pod"]),
            "blocked": sorted(blocked, key=lambda f: f["pod"]),
            "strands": sorted(strands),
            "moves": moves,
            "pdbChecks": [pdb_hits[k] for k in sorted(pdb_hits)],
            "errors": errors,
        }


# ── reduction (store live snapshots without anything section 3 does not use) ──

def _pick(d, keys):
    return {k: d[k] for k in keys if d.get(k) not in (None, {}, [], "")}


def _reduce_container(c):
    out = {"name": c.get("name")}
    req = (c.get("resources") or {}).get("requests")
    if req:
        out["resources"] = {"requests": req}
    ports = [_pick(p, ("containerPort", "hostPort", "protocol")) for p in c.get("ports") or [] if p.get("hostPort")]
    if ports:
        out["ports"] = ports
    if c.get("restartPolicy"):
        out["restartPolicy"] = c["restartPolicy"]
    return out


def reduce_item(o):
    """Keep only the fields plan.md section 3 reads. Drops env, images, commands,
    secrets/configmap volumes, every annotation except the mirror and safe-to-evict
    ones, managedFields, status details."""
    kind, m = o.get("kind"), meta(o)
    base = {"apiVersion": o.get("apiVersion"), "kind": kind}
    if kind == "Node":
        st = o.get("status") or {}
        base["metadata"] = _pick(m, ("name", "labels"))
        base["spec"] = _pick(o.get("spec") or {}, ("taints", "unschedulable"))
        base["status"] = {
            "allocatable": _pick(st.get("allocatable") or {}, ("cpu", "memory", "pods")),
            "conditions": [_pick(c, ("type", "status")) for c in st.get("conditions") or [] if c.get("type") == "Ready"],
        }
    elif kind == "Pod":
        spec, st = o.get("spec") or {}, o.get("status") or {}
        md = _pick(m, ("name", "namespace", "uid", "labels", "deletionTimestamp"))
        ann = {k: v for k, v in (m.get("annotations") or {}).items() if k in (MIRROR, SAFE_TO_EVICT)}
        if ann:
            md["annotations"] = ann
        refs = [_pick(r, ("apiVersion", "kind", "name", "uid", "controller")) for r in m.get("ownerReferences") or []]
        if refs:
            md["ownerReferences"] = refs
        aff = spec.get("affinity") or {}
        raff = {}
        for k in ("nodeAffinity", "podAffinity", "podAntiAffinity"):
            req = (aff.get(k) or {}).get("requiredDuringSchedulingIgnoredDuringExecution")
            if req:
                raff[k] = {"requiredDuringSchedulingIgnoredDuringExecution": req}
        rspec = _pick(spec, ("nodeName", "overhead", "tolerations", "nodeSelector"))
        rspec["containers"] = [_reduce_container(c) for c in spec.get("containers") or []]
        if spec.get("initContainers"):
            rspec["initContainers"] = [_reduce_container(c) for c in spec["initContainers"]]
        if raff:
            rspec["affinity"] = raff
        tsc = [_pick(t, ("maxSkew", "topologyKey", "whenUnsatisfiable")) for t in spec.get("topologySpreadConstraints") or []]
        if tsc:
            rspec["topologySpreadConstraints"] = tsc
        vols = []
        for v in spec.get("volumes") or []:          # only the volume kinds section 3 refuses
            if v.get("persistentVolumeClaim") is not None:
                vols.append({"name": v.get("name"), "persistentVolumeClaim": {"claimName": "redacted"}})
            elif v.get("ephemeral") is not None:
                vols.append({"name": v.get("name"), "ephemeral": {}})
        if vols:
            rspec["volumes"] = vols
        base["metadata"], base["spec"] = md, rspec
        rst = _pick(st, ("phase",))
        for k in ("containerStatuses", "initContainerStatuses"):
            cs = [_pick(c, ("name", "allocatedResources")) for c in st.get(k) or []]
            if cs:
                rst[k] = cs
        base["status"] = rst
    elif kind == "PodDisruptionBudget":
        st = o.get("status") or {}
        base["metadata"] = _pick(m, ("name", "namespace", "generation"))
        base["spec"] = {"selector": (o.get("spec") or {}).get("selector")}
        base["status"] = {"disruptionsAllowed": st.get("disruptionsAllowed", 0),
                          "observedGeneration": st.get("observedGeneration", 0)}
    elif kind in ("ReplicaSet", "StatefulSet"):
        base["metadata"] = _pick(m, ("name", "namespace", "uid"))
        base["spec"] = {"replicas": (o.get("spec") or {}).get("replicas")}
    else:
        return None
    return base


def load_items(path):
    raw = sys.stdin.read() if path == "-" else open(path).read()
    doc = json.loads(raw)
    if isinstance(doc, list):
        return doc
    if doc.get("kind", "").endswith("List") or "items" in doc:
        return doc.get("items") or []
    return [doc]


def guards_from_args(a):
    g = {"protectCriticalNamespaces": True, "protectSingleReplicas": True, "protectStatefulSets": True,
         "criticalNamespaces": list(DEFAULT_CRITICAL)}
    if a.policy:
        sg = json.load(open(a.policy)).get("safetyGuards") or {}
        for k in ("protectCriticalNamespaces", "protectSingleReplicas", "protectStatefulSets"):
            if k in sg:
                g[k] = bool(sg[k])
        if sg.get("criticalNamespaces") is not None:
            g["criticalNamespaces"] = list(sg["criticalNamespaces"])
    if a.critical is not None:
        g["criticalNamespaces"] = [x for x in a.critical.split(",") if x]
    if a.no_protect_critical:
        g["protectCriticalNamespaces"] = False
    if a.no_protect_single:
        g["protectSingleReplicas"] = False
    if a.no_protect_sts:
        g["protectStatefulSets"] = False
    g["selfNamespace"] = a.self_namespace
    return g


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("snapshot", help="kubectl get nodes,pods,pdb,rs,sts -A -o json output, or -")
    ap.add_argument("--critical", help="comma-separated critical namespaces (overrides --policy)")
    ap.add_argument("--policy", help="policy fixture whose safetyGuards are used")
    ap.add_argument("--self-namespace", default="xamops-system")
    ap.add_argument("--no-protect-critical", action="store_true")
    ap.add_argument("--no-protect-single", action="store_true")
    ap.add_argument("--no-protect-sts", action="store_true")
    ap.add_argument("--set", help="evaluate this comma-separated node set instead of every node singly")
    ap.add_argument("--json", action="store_true", help="print JSON instead of the text report")
    ap.add_argument("--reduce", metavar="OUT", help="write the reduced snapshot (only section-3 fields) to OUT and exit")
    a = ap.parse_args()

    items = load_items(a.snapshot)
    if a.reduce:
        reduced = [r for r in (reduce_item(o) for o in items) if r is not None]
        with open(a.reduce, "w") as fh:
            json.dump({"apiVersion": "v1", "kind": "List", "items": reduced}, fh, indent=1, sort_keys=True)
            fh.write("\n")
        print(f"reduced {len(items)} object(s) -> {len(reduced)} in {a.reduce}")
        return
    cluster = Cluster(items)
    guards = guards_from_args(a)
    sets = [a.set.split(",")] if a.set else [[n] for n in sorted(cluster.nodes)]
    results = {",".join(sorted(s)): cluster.drain(s, guards) for s in sets}

    if a.json:
        json.dump({"options": guards, "nodes": results}, sys.stdout, indent=2, sort_keys=True)
        sys.stdout.write("\n")
        return
    print(f"guards: {json.dumps(guards, sort_keys=True)}")
    for name, r in results.items():
        verdict = "DRAINABLE" if r["drainable"] else "NOT DRAINABLE"
        print(f"== {name}: {verdict}  excluded={r['excludedCount']} moves={len(r['moves'])}")
        for e in r["errors"]:
            print(f"   ERROR    {e}")
        for f in r["refused"]:
            print(f"   REFUSED  {f['pod']}  ({f['reason']})")
        for f in r["blocked"]:
            print(f"   BLOCKED  {f['pod']}  ({f['reason']})")
        for s in r["strands"]:
            print(f"   STRAND   {s}")
        for m in r["moves"]:
            print(f"   move     {m['pod']}  {m['from']} -> {m['to']}  ({m['cpuMillis']}m, {m['memBytes'] // 2 ** 20}Mi)")
        for c in r["pdbChecks"]:
            print(f"   pdb      {c['pdb']}  matchedInSet={c['matchedInSet']} disruptionsAllowed={c['disruptionsAllowed']}"
                  + (" STALE" if c["stale"] else ""))


if __name__ == "__main__":
    main()
