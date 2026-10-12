#!/usr/bin/env python3
"""Compare the oracle (fit_ground_truth.py --json) with the autoscaler's fit plans.

    ./compare_fit.py oracle.json <capture>  [--seq N] [--reasons]

<capture> is one of:
  - a pull_captures.py destination directory (reads
    collectors/autoscaling-evictor-candidates.jsonl),
  - that .jsonl file,
  - one stub ingest file (ingest/*.json: {"payload": {"batches": [...]}}),
  - a JSON list of evictor candidates.
From a .jsonl the last batch is used unless --seq picks one.

Per node it compares the drainable verdict, the strand set, the blocked pods and the
refused pods (with --reasons also their reason strings) and prints a PASS/FAIL
table. Exit status 0 = every node PASS, 1 = any FAIL or a node missing on one side.
Read-only; stdlib only.
"""
import argparse
import json
import os
import sys

COLLECTOR = "autoscaling-evictor-candidates"


def load_candidates(path, seq):
    if os.path.isdir(path):
        path = os.path.join(path, "collectors", COLLECTOR + ".jsonl")
    if path.endswith(".jsonl"):
        rows = [json.loads(line) for line in open(path) if line.strip()]
        if not rows:
            raise SystemExit(f"{path}: no batches")
        if seq is not None:
            rows = [r for r in rows if r.get("seq") == seq]
            if not rows:
                raise SystemExit(f"{path}: no batch with seq {seq}")
        row = rows[-1]
        return row.get("items") or [], f"{path} seq {row.get('seq')} receivedAt {row.get('receivedAt')}"
    doc = json.load(open(path))
    if isinstance(doc, list):
        return doc, path
    payload = doc.get("payload", doc)
    for b in payload.get("batches") or []:
        if b.get("collector") == COLLECTOR:
            return b.get("items") or [], path
    raise SystemExit(f"{path}: no {COLLECTOR} batch")


def view(plan, reasons):
    def pods(findings):
        return sorted((f["pod"], f["reason"]) if reasons else f["pod"] for f in findings or [])
    return {
        "drainable": bool(plan.get("drainable")),
        "strands": sorted(plan.get("strands") or []),
        "blocked": pods(plan.get("blocked")),
        "refused": pods(plan.get("refused")),
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("oracle")
    ap.add_argument("capture")
    ap.add_argument("--seq", type=int)
    ap.add_argument("--reasons", action="store_true", help="also compare reason strings")
    a = ap.parse_args()

    oracle = json.load(open(a.oracle))["nodes"]
    items, source = load_candidates(a.capture, a.seq)
    agent = {}
    for c in items:
        if c.get("fit") is None:
            print(f"WARN: candidate {c.get('nodeName')} has no fit object")
            continue
        agent[c["nodeName"]] = c["fit"]

    print(f"oracle: {a.oracle}\ncapture: {source}")
    fields = ("drainable", "strands", "blocked", "refused")
    print(f"{'node':<48} {'verdict':<7} " + " ".join(f"{f:<9}" for f in fields))
    failures = 0
    for node in sorted(set(oracle) | set(agent)):
        if node not in oracle or node not in agent:
            failures += 1
            print(f"{node:<48} FAIL    missing in {'oracle' if node not in oracle else 'capture'}")
            continue
        o, g = view(oracle[node], a.reasons), view(agent[node], a.reasons)
        marks = ["ok" if o[f] == g[f] else "DIFF" for f in fields]
        ok = all(m == "ok" for m in marks)
        failures += 0 if ok else 1
        print(f"{node:<48} {'PASS' if ok else 'FAIL':<7} " + " ".join(f"{m:<9}" for m in marks))
        for f, m in zip(fields, marks):
            if m != "ok":
                print(f"    {f}: oracle={json.dumps(o[f])}\n    {' ' * len(f)}  agent ={json.dumps(g[f])}")
    print("RESULT:", "PASS" if failures == 0 else f"FAIL ({failures} node(s))")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
