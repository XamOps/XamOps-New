import gzip
import hashlib
import io
import random
import tarfile

import numpy as np
import pandas as pd
import pytest

from backtest.data import alibaba

SEED = 7


def _rows(cid, hours, cpu_fn, mem_fn, per_hour=3):
    """Raw CSV lines: ``per_hour`` samples inside each listed hour."""
    out = []
    for h in hours:
        for k in range(per_hour):
            ts = h * 3600 + 600 * k + 10
            out.append(f"{cid},m_1,{ts},{cpu_fn(h, k)},{mem_fn(h, k)},,,,,,")
    return out


def _cpu(h, k):
    return 10 + (h % 5) + 10 * k  # samples 10+x, 20+x, 30+x -> max 30+x, mean 20+x


def _mem(h, k):
    return 50 + (h % 3) + k


def _shuffled(lines, seed=0):
    lines = list(lines)
    random.Random(seed).shuffle(lines)
    return lines


def test_blocks_grouped_and_time_sorted():
    a = _shuffled(_rows("c_a", range(80), _cpu, _mem))
    b = _shuffled(_rows("c_b", range(80), _cpu, _mem), seed=1)
    blocks = list(alibaba.iter_container_blocks(a + b))
    assert [cid for cid, _ in blocks] == ["c_a", "c_b"]
    for _, rows in blocks:
        ts = [r[0] for r in rows]
        assert ts == sorted(ts) and len(rows) == 240


def test_keep_predicate_skips_parsing():
    lines = _rows("c_a", range(2), _cpu, _mem) + _rows("c_b", range(2), _cpu, _mem)
    blocks = list(alibaba.iter_container_blocks(lines, keep=lambda c: c == "c_b"))
    assert blocks[0] == ("c_a", None) and blocks[1][0] == "c_b" and len(blocks[1][1]) == 6


def test_max_vs_mean_and_two_hour_gap_filled():
    hours = [h for h in range(80) if h not in (10, 11)]  # 2 h gap -> forward-filled
    (_, rows), = alibaba.iter_container_blocks(_shuffled(_rows("c_a", hours, _cpu, _mem)))
    hmax = alibaba.block_to_hourly(rows, "max")
    hmean = alibaba.block_to_hourly(rows, "mean")
    h0, cpu_max = hmax["cpu"]
    assert h0 == 0 and len(cpu_max) == 80
    assert cpu_max[3] == 30 + 3 and hmean["cpu"][1][3] == 20 + 3
    assert hmax["mem"][1][4] == 50 + 1 + 2 and hmean["mem"][1][4] == 50 + 1 + 1
    assert cpu_max[10] == cpu_max[9] and cpu_max[11] == cpu_max[9]  # ffill of hours 10, 11


def test_gap_longer_than_two_hours_is_not_filled_series_trimmed():
    # 3 h gap at 10-12 is never filled: keep the longest gap-free run (13..99) if it is >= 72 h.
    hours = [h for h in range(100) if h not in (10, 11, 12) and h != 50]
    (_, rows), = alibaba.iter_container_blocks(_rows("c_a", hours, _cpu, _mem))
    out = alibaba.block_to_hourly(rows, "max")
    h0, cpu = out["cpu"]
    assert h0 == 13 and len(cpu) == 87 and out["mem"][0] == 13 and len(out["mem"][1]) == 87
    assert cpu[50 - 13] == cpu[49 - 13]  # the 1 h gap inside the run is still forward-filled
    # same gap on an 80 h container leaves a 67 h run -> too short for one normal origin -> drop
    hours = [h for h in range(80) if h not in (10, 11, 12)]
    (_, rows), = alibaba.iter_container_blocks(_rows("c_a", hours, _cpu, _mem))
    assert alibaba.block_to_hourly(rows, "max") is None


def test_more_than_ten_percent_missing_drops():
    missing = {5, 10, 15, 20, 25, 30, 35, 40, 45}  # 9 / 80 = 11.25 %, every gap is 1 h
    hours = [h for h in range(80) if h not in missing]
    (_, rows), = alibaba.iter_container_blocks(_rows("c_a", hours, _cpu, _mem))
    assert alibaba.block_to_hourly(rows, "max") is None
    hours = [h for h in range(80) if h not in {5, 10, 15, 20, 25, 30, 35, 40}]  # 10 % exactly -> kept
    (_, rows), = alibaba.iter_container_blocks(_rows("c_a", hours, _cpu, _mem))
    assert alibaba.block_to_hourly(rows, "max") is not None


def test_zero_variance_drops():
    (_, rows), = alibaba.iter_container_blocks(_rows("c_a", range(80), lambda h, k: 5, _mem))
    assert alibaba.block_to_hourly(rows, "max") is None


def test_selection_deterministic_and_matches_hash():
    ids = [f"c_{i}" for i in range(2000)]
    first = [alibaba.select(c, SEED) for c in ids]
    assert first == [alibaba.select(c, SEED) for c in ids]
    expected = [int(hashlib.sha256(f"{SEED}:{c}".encode()).hexdigest(), 16) % 32 == 0 for c in ids]
    assert first == expected
    assert 30 < sum(first) < 100  # ~1/32 of 2000
    assert first != [alibaba.select(c, SEED + 1) for c in ids]


def _targz(lines):
    data = ("\n".join(lines) + "\n").encode()
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tf:
        info = tarfile.TarInfo("container_usage.csv")
        info.size = len(data)
        tf.addfile(info, io.BytesIO(data))
    buf.seek(0)
    return buf


def test_stream_to_cache_and_load(tmp_path):
    selected = [c for c in (f"c_{i}" for i in range(500)) if alibaba.select(c, SEED)][:3]
    unselected = next(c for c in (f"c_{i}" for i in range(500)) if not alibaba.select(c, SEED))
    lines = []
    for cid in [unselected] + selected:
        lines += _shuffled(_rows(cid, range(96), _cpu, _mem))
    path = tmp_path / "sample.csv.gz"
    stats = alibaba.build_cache_from_stream(_targz(lines), SEED, cache_target=2, max_download_mb=10, path=path)
    assert stats["kept"] == 2 and stats["stopped_by"] == "cache_target"
    with gzip.open(path, "rt") as f:
        assert f.readline().strip() == "series_id,resource,agg,hour,value"
    series = alibaba.load(5, "max", SEED, path=path)
    kept_ids = sorted(selected[:2], key=lambda c: int(hashlib.sha256(f"{SEED}:{c}".encode()).hexdigest(), 16))
    assert [s.id for s in series] == [kept_ids[0], kept_ids[0], kept_ids[1], kept_ids[1]]
    assert [s.resource for s in series] == ["cpu", "mem", "cpu", "mem"]
    s = series[0]
    assert len(s) == 96 and s.index[0] == pd.Timestamp("2018-01-01T00:00:00Z") and s.index.tz is not None
    assert s.values[3] == pytest.approx(33.0)
    mean = alibaba.load(5, "mean", SEED, path=path)
    assert mean[0].values[3] == pytest.approx(23.0)
    assert np.all(np.diff(s.index.asi8) == 3600 * 10**9)
