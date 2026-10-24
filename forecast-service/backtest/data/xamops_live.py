"""Read-only live sample from the xamops-test-1 in-cluster Prometheus (D12, optional).

Every kubectl call goes through ``_kubectl``, which always builds
``kubectl --context <CONTEXT> get ...`` (list argv, no shell). Only ``get`` is used
(``get --raw`` service-proxy GETs against ``monitoring/prometheus-server:80``); the
current kubectl context is never read or changed and ``config`` is rejected.

Fetches hourly ``query_range`` (step=3600) over the whole retention for container CPU
usage (rate over 5m), memory working set and ``kube_pod_container_resource_requests``,
then normalises usage to % of the container request (series without a request are
dropped). Namespace, pod and container names are hashed (``sha256(seed:name)[:12]``)
before anything is written to ``.cache/xamops-live/sample.csv.gz``; raw names never
leave memory. Values are percent of request and may exceed 100, so the unit is
``pct_request`` (not clipped at 100, unlike Alibaba ``pct``).
"""
import gzip
import hashlib
import io
import json
import math
import re
import subprocess
import sys
import time
import urllib.parse
from pathlib import Path
from typing import Optional

import numpy as np
import pandas as pd

from ..config import CACHE_DIR, SEED
from .alibaba import MAX_GAP_FILL_H, MAX_MISSING_FRAC, MIN_HOURS, _fill_short_gaps, _longest_run
from .types import Series

CONTEXT = "arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-1"
PROM_PROXY = "/api/v1/namespaces/monitoring/services/prometheus-server:80/proxy"
STEP_S = 3600
SOURCE = "xamops-test-1"
UNIT = "pct_request"

Q_CPU = 'max by (namespace,pod,container) (rate(container_cpu_usage_seconds_total{container!="",container!="POD"}[5m]))'
Q_MEM = 'max by (namespace,pod,container)(container_memory_working_set_bytes{container!="",container!="POD"})'
Q_REQ = 'max by (namespace,pod,container,resource) (kube_pod_container_resource_requests{resource=~"cpu|memory"})'
Q_OLDEST = "min(prometheus_tsdb_lowest_timestamp_seconds)"

_RESOURCE_NAME = re.compile(r"^[a-z][a-z0-9.\-]*$")
_FORBIDDEN = {"config", "--context", "--kubeconfig", "--cluster", "--user"}


def build_argv(args: list[str]) -> list[str]:
    """``kubectl --context CONTEXT get <args>``; ``args[0]`` must be ``--raw`` or a resource name."""
    if not args:
        raise ValueError("kubectl get needs arguments")
    head = args[0]
    if head == "config" or not (head == "--raw" or _RESOURCE_NAME.match(head)):
        raise ValueError(f"refusing kubectl get {head!r}: only --raw or a resource name is allowed")
    bad = [a for a in args if a.split("=", 1)[0] in _FORBIDDEN]
    if bad:
        raise ValueError(f"refusing kubectl args {bad}")
    return ["kubectl", "--context", CONTEXT, "get", *args]


def _kubectl(args: list[str], timeout: float = 120) -> str:
    argv = build_argv(args)
    assert argv[1:4] == ["--context", CONTEXT, "get"] and "config" not in argv
    res = subprocess.run(argv, capture_output=True, text=True, timeout=timeout, check=False)
    if res.returncode != 0:
        raise RuntimeError(f"kubectl get failed ({res.returncode}): {res.stderr.strip()[:300]}")
    return res.stdout


def _prom(endpoint: str, params: dict) -> list:
    path = f"{PROM_PROXY}/api/v1/{endpoint}?{urllib.parse.urlencode(params)}"
    body = json.loads(_kubectl(["--raw", path]))
    if body.get("status") != "success":
        raise RuntimeError(f"prometheus {endpoint} failed: {body.get('error', body)}")
    return body["data"]["result"]


def _range(query: str, start: int, end: int) -> list:
    return _prom("query_range", {"query": query, "start": start, "end": end, "step": STEP_S})


def _h(seed: int, name: str) -> str:
    return hashlib.sha256(f"{seed}:{name}".encode()).hexdigest()[:12]


def _log(msg: str) -> None:
    print(f"[xamops-live] {msg}", file=sys.stderr, flush=True)


def _by_key(result: list, labels: tuple) -> dict:
    out = {}
    for r in result:
        key = tuple(r["metric"].get(lbl, "") for lbl in labels)
        out[key] = {int(float(t)): float(v) for t, v in r["values"]}
    return out


def _normalise(usage: dict, req: dict, start: int, end: int) -> tuple[Optional[np.ndarray], int, str]:
    """% of request on the hourly grid, trimmed to the longest gap-free run (alibaba rules)."""
    if not req:
        return None, 0, "no_request"
    grid = list(range(start, end + 1, STEP_S))
    u = pd.Series([usage.get(t, np.nan) for t in grid], dtype=float)
    r = pd.Series([req.get(t, np.nan) for t in grid], dtype=float).ffill().bfill()
    if not (r > 0).any():
        return None, 0, "no_request"
    present = np.flatnonzero(u.notna().to_numpy())
    if present.size == 0:
        return None, 0, "empty"
    lo, hi = int(present[0]), int(present[-1]) + 1
    u, r = u.iloc[lo:hi].reset_index(drop=True), r.iloc[lo:hi].reset_index(drop=True)
    if float(u.isna().mean()) > MAX_MISSING_FRAC:
        return None, 0, "missing"
    pct = 100.0 * u / r.where(r > 0)
    filled = _fill_short_gaps(pct, MAX_GAP_FILL_H)
    a, b = _longest_run(np.isfinite(filled))
    if b - a < MIN_HOURS:
        return None, 0, "short_run"
    vals = filled[a:b]
    if np.std(vals) == 0:
        return None, 0, "zero_variance"
    return vals, grid[lo + a], ""


def cache_path() -> Path:
    return CACHE_DIR / "xamops-live" / "sample.csv.gz"


def fetch(seed: int = SEED, path: Optional[Path] = None, now: Optional[float] = None) -> dict:
    """Pull the live sample through kubectl service-proxy GETs and write the hashed cache."""
    path = path or cache_path()
    end = int((now if now is not None else time.time()) // STEP_S * STEP_S)
    oldest = _prom("query", {"query": Q_OLDEST, "time": end})
    start = int(math.ceil(float(oldest[0]["value"][1]) / STEP_S) * STEP_S) if oldest else end - 3 * 86400
    _log(f"query_range {pd.Timestamp(start, unit='s', tz='UTC')} .. {pd.Timestamp(end, unit='s', tz='UTC')} "
         f"step={STEP_S}s ({(end - start) // STEP_S + 1} hours)")
    cpu = _by_key(_range(Q_CPU, start, end), ("namespace", "pod", "container"))
    mem = _by_key(_range(Q_MEM, start, end), ("namespace", "pod", "container"))
    reqs = _by_key(_range(Q_REQ, start, end), ("namespace", "pod", "container", "resource"))
    stats = {"start": start, "end": end, "step_s": STEP_S, "hours": (end - start) // STEP_S + 1,
             "usage_series": {"cpu": len(cpu), "mem": len(mem)}, "kept": {"cpu": 0, "mem": 0}, "dropped": {}}
    rows = []
    namespaces = set()
    for resource, usage, req_name in (("cpu", cpu, "cpu"), ("mem", mem, "memory")):
        for (ns, pod, ctr), u in sorted(usage.items()):
            vals, t0, reason = _normalise(u, reqs.get((ns, pod, ctr, req_name), {}), start, end)
            if vals is None:
                k = f"{resource}_{reason}"
                stats["dropped"][k] = stats["dropped"].get(k, 0) + 1
                continue
            sid = f"{_h(seed, ns)}-{_h(seed, pod)}-{_h(seed, ctr)}"
            namespaces.add(_h(seed, ns))
            stats["kept"][resource] += 1
            for i, v in enumerate(vals):
                hour = pd.Timestamp(t0 + i * STEP_S, unit="s", tz="UTC").strftime("%Y-%m-%dT%H:%M:%SZ")
                rows.append(f"{sid},{_h(seed, ns)},{resource},{hour},{v:.6f}\n")
    stats["namespaces"] = len(namespaces)
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0) as gz:
        w = io.TextIOWrapper(gz, encoding="utf-8", newline="")
        w.write("series_id,namespace,resource,hour,value\n")
        w.writelines(rows)
        w.flush()
        w.detach()
    path.with_suffix("").with_suffix(".meta.json").write_text(json.dumps(stats, indent=2, sort_keys=True))
    _log(f"cache written: {path} ({stats})")
    return stats


def load(n_series: int, seed: int = SEED, path: Optional[Path] = None) -> list[Series]:
    """First ``n_series`` hashed containers (sorted by id) as cpu and/or mem Series."""
    path = path or cache_path()
    if not path.exists():
        fetch(seed, path)
    df = pd.read_csv(path, dtype={"series_id": str, "namespace": str})
    ids = sorted(df["series_id"].unique())[:n_series]
    out: list[Series] = []
    for (sid, resource), g in df[df["series_id"].isin(set(ids))].groupby(["series_id", "resource"], sort=True):
        index = pd.DatetimeIndex(pd.to_datetime(g["hour"], utc=True))
        out.append(Series(sid, resource, UNIT, index, g["value"].to_numpy(dtype=float), SOURCE))
    return out
