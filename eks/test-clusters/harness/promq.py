#!/usr/bin/env python3
"""Run PromQL instant queries against a port-forwarded Prometheus and print a
compact, reviewable result (one line per series).

    kubectl -n monitoring port-forward svc/prometheus-server 19090:9090 &
    ./promq.py 'up' 'kube_pod_container_resource_requests{namespace="team-a"}'

PROM_URL overrides the default http://127.0.0.1:19090. Output is plain text so
it can be captured verbatim into evidence files.
"""
import json
import os
import sys
import urllib.parse
import urllib.request

PROM = os.environ.get("PROM_URL", "http://127.0.0.1:19090")
KEEP = ("namespace", "pod", "container", "node", "resource", "unit", "instance", "job")


def query(q):
    url = PROM + "/api/v1/query?" + urllib.parse.urlencode({"query": q})
    with urllib.request.urlopen(url, timeout=60) as r:
        return json.load(r)


def main(argv):
    for q in argv:
        print("QUERY:", q)
        try:
            d = query(q)
        except Exception as e:  # noqa: BLE001 - evidence tool, report and continue
            print("  ERROR:", e)
            continue
        if d.get("status") != "success":
            print("  STATUS:", d.get("status"), d.get("error"))
            continue
        res = d["data"]["result"]
        print(f"  resultType={d['data']['resultType']} series={len(res)}")
        for s in res:
            m = s.get("metric", {})
            lab = ",".join(f"{k}={m[k]}" for k in KEEP if k in m)
            val = s.get("value", [None, None])[1]
            print(f"  {{{lab}}} = {val}")
        print()


if __name__ == "__main__":
    args = sys.argv[1:]
    if args[:1] == ["-f"]:
        # one query per line; blank lines and '#' comments are skipped
        with open(args[1]) as fh:
            args = [l.strip() for l in fh if l.strip() and not l.lstrip().startswith("#")]
    main(args)
