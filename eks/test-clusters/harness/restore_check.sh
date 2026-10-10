#!/usr/bin/env bash
# Read-only restore check for xamops-test-1 (plan.md D16). Run after every row group.
#
#   ./restore_check.sh <pre-test snapshot.json> [--allow-gen <ns>/<name> ...]
#
# PASS requires all of:
#   - every node schedulable, untainted, no xamops.io/* annotation
#   - census 51 Running / 1 Pending, the Pending pod = team-a/unschedulable-giant-*
#   - seeded Deployments (eks/test-clusters/1x-*.yaml): replicas, requests and limits
#     equal the manifests (HPA targets: replicas within the HPA's min..max)
#   - Deployment generations equal the pre-test snapshot, except --allow-gen ones
#     and HPA targets (scaling bumps generation; reported, not failed)
#   - every HPA min 1 / max 5; 9 VPAs, all updateMode Off
#   - no harness-pdb-* PDB; no xamops-automation-state ConfigMap
#   - autoscaler ConfigMap DRY_RUN=true and every ENABLE_* false, incl. ENABLE_RIGHTSIZER_APPLY
#   - the stub serves policy _label default-bundle
#   - the autoscaler's latest cycle is idle (mode=disabled — idle this cycle)
# Exit 0 on PASS, 1 on FAIL. Writes nothing to the cluster.
set -euo pipefail

PRE="${1:?pre-test snapshot.json}"; shift
ALLOW=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --allow-gen) ALLOW+=("${2:?ns/name}"); shift 2;;
    *) echo "unknown argument $1" >&2; exit 2;;
  esac
done
CTX="arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-1"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEEDED_DIR="$(cd "$HERE/.." && pwd)"
K=(kubectl --context "$CTX")
WORK="$(mktemp -d "${TMPDIR:-/tmp}/xamops-restore-check.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

echo "restore_check $(date -u +%Y-%m-%dT%H:%M:%SZ) pre=$PRE allow-gen=${ALLOW[*]:-none}"
python3 "$HERE/snapshot.py" "$WORK/now.json" >/dev/null
for f in "$SEEDED_DIR"/1[0-9]-*.yaml; do
  "${K[@]}" create --dry-run=client -o json -f "$f" > "$WORK/seed-$(basename "$f" .yaml).json"
done
"${K[@]}" -n xamops-system get cm xamops-automation-config -o json > "$WORK/autoscaler-cm.json"
"${K[@]}" -n xamops-system exec deploy/xamops-stub -- python3 -c \
  'import json;print(json.load(open("/etc/stub-policy/policy.json")).get("_label",""))' > "$WORK/stub-label.txt"

# The autoscaler polls every 60s after a 15s initial delay: wait for one idle cycle.
for _ in $(seq 1 18); do
  "${K[@]}" -n xamops-system logs deploy/xamops-automation --tail=40 > "$WORK/autoscaler-log.txt" 2>&1 || true
  grep -q 'Automation cycle start' "$WORK/autoscaler-log.txt" && break
  sleep 10
done

python3 - "$PRE" "$WORK" "${ALLOW[@]+"${ALLOW[@]}"}" <<'EOF'
import glob, json, os, sys
pre = json.load(open(sys.argv[1])); work = sys.argv[2]; allow = set(sys.argv[3:])
now = json.load(open(os.path.join(work, "now.json")))
fails, oks = [], []
def check(cond, ok, bad):
    (oks if cond else fails).append(ok if cond else bad)

# nodes
for n in now["nodes"]:
    check(not n["unschedulable"] and not n["taints"] and not n["xamopsAnnotations"] and n["ready"] == "True",
          f"node {n['name']} schedulable, untainted, Ready, no xamops.io annotation",
          f"node {n['name']} unschedulable={n['unschedulable']} taints={n['taints']} ann={n['xamopsAnnotations']} ready={n['ready']}")
# census
c = now["census"]
pend_ok = len(c["pending"]) == 1 and c["pending"][0].startswith("team-a/unschedulable-giant-")
check(c["byPhase"].get("Running") == 51 and c["byPhase"].get("Pending", 0) == 1 and pend_ok
      and set(c["byPhase"]) <= {"Running", "Pending"},
      f"census {c['byPhase']} pending={c['pending']}",
      f"census {c['byPhase']} pending={c['pending']} (want 51 Running / 1 Pending = unschedulable-giant)")
# seeded spec
deps = {f"{d['namespace']}/{d['name']}": d for d in now["deployments"]}
hpas = {f"{h['namespace']}/{(h.get('target') or {}).get('name')}": h for h in now["hpas"]}
for f in sorted(glob.glob(os.path.join(work, "seed-*.json"))):
    # kubectl prints one JSON document per manifest object, concatenated
    text, pos, items, dec = open(f).read(), 0, [], json.JSONDecoder()
    while text[pos:].strip():
        while text[pos].isspace():
            pos += 1
        doc, pos = dec.raw_decode(text, pos)
        items.extend(doc["items"] if doc.get("kind") == "List" else [doc])
    for obj in items:
        if obj.get("kind") != "Deployment":
            continue
        k = f"{obj['metadata']['namespace']}/{obj['metadata']['name']}"
        d = deps.get(k)
        if not d:
            fails.append(f"seeded deployment {k} missing"); continue
        want = {c_["name"]: {"requests": (c_.get("resources") or {}).get("requests") or {},
                             "limits": (c_.get("resources") or {}).get("limits") or {}}
                for c_ in obj["spec"]["template"]["spec"]["containers"]}
        check(d["template"] == want, f"{k} template requests/limits = seeded {want}",
              f"{k} template {d['template']} != seeded {want}")
        wr = obj["spec"].get("replicas", 1)
        if k in hpas:
            h = hpas[k]
            check(h["minReplicas"] <= (d["replicas"] or 0) <= h["maxReplicas"],
                  f"{k} replicas {d['replicas']} within HPA {h['minReplicas']}..{h['maxReplicas']} (seeded {wr})",
                  f"{k} replicas {d['replicas']} outside HPA {h['minReplicas']}..{h['maxReplicas']}")
        else:
            check(d["replicas"] == wr, f"{k} replicas {d['replicas']} = seeded", f"{k} replicas {d['replicas']} != seeded {wr}")
# generations vs the pre-test snapshot
pre_deps = {f"{d['namespace']}/{d['name']}": d for d in pre["deployments"]}
for k, d in sorted(deps.items()):
    if k.startswith("xamops-system/"):
        continue
    p = pre_deps.get(k)
    if not p:
        fails.append(f"deployment {k} not in the pre-test snapshot"); continue
    if p["generation"] == d["generation"]:
        oks.append(f"{k} generation {d['generation']} unchanged"); continue
    if k in allow:
        oks.append(f"{k} generation {p['generation']} -> {d['generation']} (allow-listed)")
    elif k in hpas and p["template"] == d["template"]:
        oks.append(f"{k} generation {p['generation']} -> {d['generation']} (HPA target, template unchanged)")
    else:
        fails.append(f"{k} generation {p['generation']} -> {d['generation']} (not allow-listed)")
# HPA / VPA / PDB / ledger
for h in now["hpas"]:
    check(h["minReplicas"] == 1 and h["maxReplicas"] == 5, f"HPA {h['namespace']}/{h['name']} min1/max5",
          f"HPA {h['namespace']}/{h['name']} min{h['minReplicas']}/max{h['maxReplicas']}")
v = now["vpas"] or []
check(len(v) == 9 and all(x["updateMode"] == "Off" for x in v), "9 VPAs, all updateMode Off",
      f"VPAs: {[(x['namespace'] + '/' + x['name'], x['updateMode']) for x in v]}")
hp = [f"{p['namespace']}/{p['name']}" for p in now["pdbs"] if p["name"].startswith("harness-pdb-")]
check(not hp, "no harness-pdb-* PDB", f"harness PDBs remain: {hp}")
cms = [x["name"] for x in now["configmaps"]]
check("xamops-automation-state" not in cms, "no xamops-automation-state ConfigMap", "xamops-automation-state ConfigMap exists")
# autoscaler config
data = json.load(open(os.path.join(work, "autoscaler-cm.json")))["data"]
flags = {k: v for k, v in data.items() if k.startswith("ENABLE_")}
check(data.get("DRY_RUN") == "true" and "ENABLE_RIGHTSIZER_APPLY" in flags and all(v == "false" for v in flags.values()),
      f"autoscaler DRY_RUN=true, all ENABLE_* false {flags}", f"autoscaler DRY_RUN={data.get('DRY_RUN')} flags={flags}")
label = open(os.path.join(work, "stub-label.txt")).read().strip()
check(label == "default-bundle", "stub serves default-bundle", f"stub serves {label!r}")
log = open(os.path.join(work, "autoscaler-log.txt")).read().splitlines()
starts = [i for i, l in enumerate(log) if "Automation cycle start" in l]
last = log[starts[-1]:] if starts else []
idle = bool(last) and any("mode=disabled" in l for l in last) and any("idle this cycle" in l for l in last)
check(idle, "autoscaler latest cycle idle: " + " | ".join(l.split(" ", 2)[-1] for l in last[:3]),
      "autoscaler latest cycle not idle: " + " | ".join(last[:6]))
for o in oks:
    print("OK   " + o)
for f in fails:
    print("FAIL " + f)
print("RESULT: " + ("FAIL" if fails else "PASS") + f" ({len(oks)} ok, {len(fails)} failed)")
sys.exit(1 if fails else 0)
EOF
