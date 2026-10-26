"""Alibaba cluster-trace v2018 container_usage loader (D7, D8).

The 29.2 GB ``container_usage.tar.gz`` is streamed over HTTP
(urllib -> byte counter -> ``tarfile`` mode ``r|gz`` -> ``csv``) and never written to
disk. Rows are contiguous per ``container_id`` (but not time-sorted inside a block), so
each completed block is a whole container. A container is kept when its sha256 hash
selects it (1 in 32) and its hourly cpu and mem series pass the quality rules. Only the
small per-hour sample is cached at ``.cache/alibaba/sample_seed<seed>.csv.gz``.

Quality rules (applied per resource, a container is kept only if cpu and mem both pass,
for both ``max`` and ``mean`` aggregation so the kept set does not depend on ``--agg``):
  * more than 10 % of the hours between the first and last sample are missing -> drop
  * gaps of <= 2 h are forward-filled; longer gaps are never filled. Instead the series is
    trimmed to its longest gap-free run (shared by cpu and mem). Nearly every v2018
    container has one trace-wide 4-5 h outage around trace hour 39-44, which D7 does not
    address; trimming keeps only observed hours rather than inventing a flat 5 h stretch.
  * zero variance (on the kept run) -> drop
  * span or longest gap-free run under 72 h (one 48 h history + 24 h horizon) -> drop
"""
import csv
import gzip
import hashlib
import io
import json
import os
import sys
import tarfile
import urllib.request
from pathlib import Path
from typing import Callable, Iterable, Iterator, Optional

import numpy as np
import pandas as pd

from ..config import CACHE_DIR, SEED
from .types import Series

URL = "http://aliopentrace.oss-cn-beijing.aliyuncs.com/v2018Traces/container_usage.tar.gz"
COLUMNS = (
    "container_id", "machine_id", "time_stamp", "cpu_util_percent", "mem_util_percent",
    "cpi", "mem_gps", "mpki", "net_in", "net_out", "disk_io_percent",
)
ANCHOR = pd.Timestamp("2018-01-01T00:00:00Z")  # trace seconds are relative; weekly phase is arbitrary
AGGS = ("max", "mean")
MAX_GAP_FILL_H = 2
MAX_MISSING_FRAC = 0.10
MIN_HOURS = 72
SOURCE = "alibaba-v2018"

# A raw row kept for a selected container: (time_stamp_s, cpu_util_percent, mem_util_percent)
Row = tuple[int, float, float]


def _to_float(s: str) -> float:
    try:
        return float(s)
    except ValueError:
        return float("nan")


def iter_container_blocks(
    text_lines: Iterable[str], keep: Optional[Callable[[str], bool]] = None
) -> Iterator[tuple[str, Optional[list[Row]]]]:
    """Yield ``(container_id, rows)`` per contiguous container block.

    ``rows`` are sorted by ``time_stamp``. When ``keep(cid)`` is False the block's rows are
    not parsed and ``rows`` is None (lets the caller count blocks cheaply).
    """
    cur: Optional[str] = None
    cur_keep = False
    rows: list[Row] = []
    for rec in csv.reader(text_lines):
        if len(rec) < 5 or rec[0] == COLUMNS[0]:
            continue
        cid = rec[0]
        if cid != cur:
            if cur is not None:
                yield cur, (sorted(rows, key=lambda r: r[0]) if cur_keep else None)
            cur, rows = cid, []
            cur_keep = keep is None or keep(cid)
        if not cur_keep:
            continue
        try:
            ts = int(float(rec[2]))
        except ValueError:
            continue
        rows.append((ts, _to_float(rec[3]), _to_float(rec[4])))
    if cur is not None:
        yield cur, (sorted(rows, key=lambda r: r[0]) if cur_keep else None)


def _hourly_with_reason(rows: list[Row], agg: str) -> tuple[Optional[dict], str]:
    if agg not in AGGS:
        raise ValueError(f"agg must be one of {AGGS}")
    if not rows:
        return None, "empty"
    hours = np.array([r[0] for r in rows], dtype=np.int64) // 3600
    h0 = int(hours.min())
    n = int(hours.max()) - h0 + 1
    if n < MIN_HOURS:
        return None, f"short span={n}h<{MIN_HOURS}h"
    filled = {}
    for name, col in (("cpu", 1), ("mem", 2)):
        v = np.array([r[col] for r in rows], dtype=float)
        ok = np.isfinite(v)
        per_hour = pd.Series(v[ok]).groupby(hours[ok] - h0).agg(agg).reindex(range(n))
        missing = float(per_hour.isna().mean())
        if missing > MAX_MISSING_FRAC:
            return None, f"missing_{name} {missing:.1%} of hours"
        filled[name] = _fill_short_gaps(per_hour, MAX_GAP_FILL_H)
    a, b = _longest_run(np.isfinite(filled["cpu"]) & np.isfinite(filled["mem"]))
    if b - a < MIN_HOURS:
        return None, f"short_run longest gap-free run={b - a}h<{MIN_HOURS}h"
    out = {}
    for name, arr in filled.items():
        arr = arr[a:b]
        if np.std(arr) == 0:
            return None, f"zero_variance_{name}"
        out[name] = (h0 + a, arr)
    return out, ""


def _fill_short_gaps(per_hour: pd.Series, limit: int) -> np.ndarray:
    """Forward-fill NaN runs of length <= ``limit``; longer runs stay NaN entirely."""
    v = per_hour.to_numpy(dtype=float)
    isna = np.isnan(v)
    run_id = np.cumsum(~isna)  # a NaN run shares its id with the preceding valid hour
    run_len = pd.Series(isna).groupby(run_id).transform("sum").to_numpy()
    return np.where(isna & (run_len <= limit), per_hour.ffill().to_numpy(dtype=float), v)


def _longest_run(valid: np.ndarray) -> tuple[int, int]:
    """[start, end) of the first longest run of True values."""
    best, start = (0, 0), None
    for i, ok in enumerate(np.append(valid, False)):
        if ok and start is None:
            start = i
        elif not ok and start is not None:
            if i - start > best[1] - best[0]:
                best = (start, i)
            start = None
    return best


def block_to_hourly(rows: list[Row], agg: str) -> Optional[dict[str, tuple[int, np.ndarray]]]:
    """Hourly ``agg`` (max|mean) of cpu and mem: ``{'cpu': (start_hour, values), 'mem': ...}``.

    ``start_hour`` is hours since ANCHOR. Returns None when the container is dropped.
    """
    return _hourly_with_reason(rows, agg)[0]


def select(cid: str, seed: int) -> bool:
    return _hash(cid, seed) % 32 == 0


def _hash(cid: str, seed: int) -> int:
    return int(hashlib.sha256(f"{seed}:{cid}".encode()).hexdigest(), 16)


def cache_path(seed: int) -> Path:
    return CACHE_DIR / "alibaba" / f"sample_seed{seed}.csv.gz"


class _CountingReader:
    """File-like wrapper that counts compressed bytes read from the HTTP stream."""

    def __init__(self, raw):
        self.raw = raw
        self.bytes = 0

    def read(self, n: int = -1) -> bytes:
        b = self.raw.read(n)
        self.bytes += len(b)
        return b


class _StreamMember(io.RawIOBase):
    """Non-seekable raw adapter: a stream-mode tar member's ``seekable()`` raises, which
    ``io.TextIOWrapper`` calls, so expose only ``readinto``."""

    def __init__(self, member):
        self.member = member

    def readable(self) -> bool:
        return True

    def seekable(self) -> bool:
        return False

    def readinto(self, b) -> int:
        data = self.member.read(len(b))
        b[:len(data)] = data
        return len(data)


def _log(msg: str) -> None:
    print(f"[alibaba] {msg}", file=sys.stderr, flush=True)


def build_cache_from_stream(fileobj, seed: int, cache_target: int, max_download_mb: float, path: Path) -> dict:
    """Stream a container_usage tar.gz from ``fileobj`` and write the sample cache to ``path``."""
    counter = _CountingReader(fileobj)
    limit = max_download_mb * 1024 * 1024
    kept: list[tuple[int, str, dict]] = []
    seen_selected: set[str] = set()
    stats = {"blocks_seen": 0, "selected": 0, "kept": 0, "dropped": {}, "duplicate_blocks": 0,
             "stopped_by": "eof"}
    with tarfile.open(fileobj=counter, mode="r|gz") as tf:
        for member in tf:
            if not member.isfile():
                continue
            raw = io.BufferedReader(_StreamMember(tf.extractfile(member)), buffer_size=1 << 20)
            text = io.TextIOWrapper(raw, encoding="utf-8", newline="")
            for cid, rows in iter_container_blocks(text, keep=lambda c: select(c, seed)):
                stats["blocks_seen"] += 1
                if rows is not None:
                    stats["selected"] += 1
                    if cid in seen_selected:  # contiguity broken; keep the first block only
                        stats["duplicate_blocks"] += 1
                    else:
                        seen_selected.add(cid)
                        per_agg, reason = {}, ""
                        for agg in AGGS:
                            hourly, reason = _hourly_with_reason(rows, agg)
                            if hourly is None:
                                break
                            per_agg[agg] = hourly
                        if len(per_agg) == len(AGGS):
                            kept.append((_hash(cid, seed), cid, per_agg))
                        else:
                            key = reason.split(" ")[0]
                            stats["dropped"][key] = stats["dropped"].get(key, 0) + 1
                    if stats["selected"] % 50 == 0:
                        _log(f"{counter.bytes / 2**20:.0f} MB read, {stats['blocks_seen']} containers, "
                             f"{len(kept)} kept")
                if len(kept) >= cache_target:
                    stats["stopped_by"] = "cache_target"
                    break
                if counter.bytes >= limit:
                    stats["stopped_by"] = "max_download_mb"
                    break
            break  # the archive holds a single CSV member
    stats["kept"] = len(kept)
    stats["downloaded_mb"] = round(counter.bytes / 2**20, 1)

    kept.sort(key=lambda k: k[0])  # hash order
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    with open(tmp, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0) as gz:
        w = io.TextIOWrapper(gz, encoding="utf-8", newline="")
        w.write("series_id,resource,agg,hour,value\n")
        for _, cid, per_agg in kept:
            for resource in ("cpu", "mem"):
                for agg in AGGS:
                    h0, values = per_agg[agg][resource]
                    for i, v in enumerate(values):
                        hour = (ANCHOR + pd.Timedelta(hours=h0 + i)).strftime("%Y-%m-%dT%H:%M:%SZ")
                        w.write(f"{cid},{resource},{agg},{hour},{v:.6f}\n")
        w.flush()
        w.detach()
    os.replace(tmp, path)
    path.with_suffix("").with_suffix(".meta.json").write_text(
        json.dumps({"seed": seed, "cache_target": cache_target, "max_download_mb": max_download_mb, **stats},
                   indent=2, sort_keys=True))
    _log(f"cache written: {path} ({stats})")
    return stats


def build_cache(seed: int = SEED, cache_target: int = 500, max_download_mb: float = 2048, url: str = URL) -> dict:
    """Stream the public trace (inbound download only) and write the sample cache."""
    _log(f"streaming {url} (stop at {cache_target} kept or {max_download_mb} MB)")
    with urllib.request.urlopen(url, timeout=120) as resp:
        return build_cache_from_stream(resp, seed, cache_target, max_download_mb, cache_path(seed))


def load(n_series: int, agg: str = "max", seed: int = SEED, cache_target: int = 500,
         max_download_mb: float = 2048, path: Optional[Path] = None) -> list[Series]:
    """First ``n_series`` kept containers (hash order) as cpu and mem Series (2n series)."""
    if agg not in AGGS:
        raise ValueError(f"agg must be one of {AGGS}")
    path = path or cache_path(seed)
    if not path.exists():
        build_cache(seed, cache_target, max_download_mb)
    df = pd.read_csv(path, dtype={"series_id": str})
    df = df[df["agg"] == agg]
    ids = list(dict.fromkeys(df["series_id"]))
    if len(ids) < n_series:
        _log(f"only {len(ids)} containers cached (< {n_series}); delete {path} and raise --cache-target "
             f"or --max-download-mb for more")
    wanted = set(ids[:n_series])
    groups = {k: g for k, g in df[df["series_id"].isin(wanted)].groupby(["series_id", "resource"], sort=False)}
    out: list[Series] = []
    for cid in ids[:n_series]:
        for resource in ("cpu", "mem"):
            g = groups[(cid, resource)]
            index = pd.DatetimeIndex(pd.to_datetime(g["hour"], utc=True))
            out.append(Series(cid, resource, "pct", index, g["value"].to_numpy(dtype=float), SOURCE))
    return out
