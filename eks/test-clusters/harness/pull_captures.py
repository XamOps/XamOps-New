#!/usr/bin/env python3
"""Pull the stub's recorded requests and ingest payloads (read-only).

    ./pull_captures.py <dest> [--since-ts 2026-10-10T15:00:00Z] [--cluster xamops-test-1]

Starts `kubectl port-forward svc/xamops-stub 18080:8080` in the background,
reads /_stub/captures (the stub's read-only evidence API), and writes:

  <dest>/requests.jsonl          request log lines with ts >= --since-ts
  <dest>/ingest/<file>.json      every ingest body received since --since-ts
  <dest>/summary.txt             one line per ingest: seq ts agent collectors(count)
  <dest>/policy-fetches.txt      every autoscaling-policy GET with the served _label
  <dest>/collectors/<name>.jsonl one line per batch: {seq, receivedAt, agentVersion,
                                 collectedAt, count, items}

The stub keeps its captures in an emptyDir: this script never restarts or
modifies it. Timestamps compare as ISO-8601 UTC strings.
"""
import argparse
import json
import os
import socket
import subprocess
import sys
import time
import urllib.request

CLUSTERS = ("xamops-test-1", "xamops-test-2")


def get(base, path, timeout=60):
    with urllib.request.urlopen(base + path, timeout=timeout) as r:
        return r.read()


def wait_port(port, proc, timeout=30):
    end = time.time() + timeout
    while time.time() < end:
        if proc.poll() is not None:
            raise SystemExit(f"port-forward exited: {proc.stderr.read().decode(errors='replace')}")
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=1):
                return
        except OSError:
            time.sleep(0.5)
    raise SystemExit("port-forward did not come up")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("dest")
    ap.add_argument("--since-ts", default="")
    ap.add_argument("--cluster", default="xamops-test-1", choices=CLUSTERS)
    ap.add_argument("--port", type=int, default=18080)
    a = ap.parse_args()
    ctx = f"arn:aws:eks:ap-south-1:102227938259:cluster/{a.cluster}"
    os.makedirs(os.path.join(a.dest, "ingest"), exist_ok=True)
    os.makedirs(os.path.join(a.dest, "collectors"), exist_ok=True)

    proc = subprocess.Popen(["kubectl", "--context", ctx, "-n", "xamops-system", "port-forward",
                             "svc/xamops-stub", f"{a.port}:8080"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    try:
        wait_port(a.port, proc)
        base = f"http://127.0.0.1:{a.port}"
        reqs = []
        for line in get(base, "/_stub/captures/requests.jsonl", timeout=120).decode().splitlines():
            if not line.strip():
                continue
            e = json.loads(line)
            if e.get("ts", "") >= a.since_ts:
                reqs.append(e)
        with open(os.path.join(a.dest, "requests.jsonl"), "w") as fh:
            for e in reqs:
                fh.write(json.dumps(e, separators=(",", ":")) + "\n")
        with open(os.path.join(a.dest, "policy-fetches.txt"), "w") as fh:
            for e in reqs:
                if e.get("path") == "/api/v1/agent/autoscaling-policy":
                    fh.write(f"{e['ts']} #{e['seq']} {e['status']} {e.get('note', '')} ua={e.get('userAgent', '')}\n")
        want = {e["seq"] for e in reqs if e.get("path") == "/api/v1/agent/ingest" and e.get("status") == 200}
        listing = json.loads(get(base, "/_stub/captures"))["files"]
        files = sorted(f["path"] for f in listing if f["path"].startswith("ingest/")
                       and f["path"][7:12].isdigit() and int(f["path"][7:12]) in want)
        collectors = {}
        summary = []
        for path in files:
            raw = get(base, "/_stub/captures/" + path)
            # Older stub builds truncate long names past the ".json" suffix.
            local = path if path.endswith(".json") else path + ".json"
            with open(os.path.join(a.dest, local), "wb") as fh:
                fh.write(raw)
            doc = json.loads(raw)
            p = doc.get("payload") or {}
            seq = int(path[7:12])
            parts = []
            for b in p.get("batches") or []:
                name = b.get("collector")
                parts.append(f"{name}({b.get('count')})")
                collectors.setdefault(name, []).append({
                    "seq": seq, "receivedAt": doc.get("receivedAt"), "agentVersion": p.get("agentVersion"),
                    "collectedAt": b.get("collectedAt"), "count": b.get("count"), "items": b.get("items")})
            summary.append(f"{seq:05d} {doc.get('receivedAt')} {p.get('agentVersion')} {' '.join(parts) or '-'}")
        with open(os.path.join(a.dest, "summary.txt"), "w") as fh:
            fh.write("\n".join(summary) + ("\n" if summary else ""))
        for name, rows in collectors.items():
            with open(os.path.join(a.dest, "collectors", f"{name}.jsonl"), "w") as fh:
                for r in rows:
                    fh.write(json.dumps(r, separators=(",", ":")) + "\n")
        print(f"pulled {len(reqs)} request(s), {len(files)} ingest file(s) since '{a.since_ts}' -> {a.dest}")
        for name in sorted(collectors):
            print(f"  {name}: {len(collectors[name])} batch(es)")
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
    return 0


if __name__ == "__main__":
    sys.exit(main())
