"""Rolling-origin backtest engine (D9).

Origins (expanding window, 24 h horizon): t = 48, 72, ... while t + 24 <= len -> bucket
``normal``; t in {24, 36} -> bucket ``short``. A method whose history is shorter than its
``min_history`` or that raises falls back to the histogram output (``fallback=True`` plus a
reason). Prophet/ETS/MSTL/histogram run in a spawn-based process pool, one task per
(series, origin, method), each seeded from (seed, series, resource, origin, method).
Chronos runs batched in the main process after the pool finishes. Output rows are sorted
by (series_id, resource, origin, method, h), so they do not depend on worker order.
"""
import hashlib
import logging
import multiprocessing as mp
import sys
import time
import warnings
from concurrent.futures import ProcessPoolExecutor
from dataclasses import dataclass

import numpy as np
import pandas as pd

from .config import HORIZON, QUANTILES, SEED
from .data.types import Series
from .methods import REGISTRY
from .methods.histogram import HistogramForecaster

BATCH_METHODS = ("chronos_bolt",)
SORT_KEYS = ["series_id", "resource", "origin", "method", "h"]


@dataclass
class Forecast:
    series_id: str
    resource: str
    origin: int
    bucket: str
    method: str
    y: np.ndarray
    q: np.ndarray  # (horizon, len(quantiles))
    fallback: bool
    reason: str
    seconds: float


def origins_for(length: int, horizon: int = HORIZON) -> list[tuple[int, str]]:
    normal = [(t, "normal") for t in range(48, length - horizon + 1, 24)]
    short = [(t, "short") for t in (24, 36) if t + horizon <= length]
    return short + normal


def task_seed(seed: int, series_id: str, resource: str, origin: int, method: str) -> int:
    key = f"{seed}:{series_id}:{resource}:{origin}:{method}".encode()
    return int(hashlib.sha256(key).hexdigest()[:8], 16)


_INSTANCES: dict = {}
LOAD_SECONDS: dict = {}  # one-off model load time per batched method (excluded from per-fit seconds)


def _method(name: str):
    if name not in _INSTANCES:
        _INSTANCES[name] = REGISTRY[name]()
    return _INSTANCES[name]


def view_names(name: str) -> tuple:
    """Method rows produced by one fit (prophet also yields prophet_asis)."""
    return tuple(getattr(_method(name), "views", (name,)))


def _quiet() -> None:
    warnings.simplefilter("ignore")
    for name in ("cmdstanpy", "prophet"):
        logging.getLogger(name).setLevel(logging.ERROR)


def _fallback(hist: Series, horizon: int, quantiles: tuple) -> np.ndarray:
    return HistogramForecaster().fit_predict(hist, horizon, quantiles)


def _run_task(args) -> list[Forecast]:
    series, origin, bucket, method, horizon, quantiles, seed = args
    np.random.seed(task_seed(seed, series.id, series.resource, origin, method))
    hist = series.head(origin)
    y = np.asarray(series.values[origin:origin + horizon], dtype=float)
    m = _method(method)
    views = view_names(method)
    start = time.perf_counter()
    fallback, reason = False, ""
    try:
        if len(hist) < m.min_history:
            fallback, reason = True, f"history {len(hist)} < min_history {m.min_history}"
            outs = {}
        elif hasattr(m, "fit_predict_views"):
            outs = m.fit_predict_views(hist, horizon, quantiles)
        else:
            outs = {method: m.fit_predict(hist, horizon, quantiles)}
    except Exception as e:  # any fit/predict failure -> histogram fallback
        fallback, reason = True, f"{type(e).__name__}: {e}"[:200].replace("\n", " ")
    if fallback:
        fb = _fallback(hist, horizon, quantiles)
        outs = {v: fb for v in views}
    secs = time.perf_counter() - start
    return [Forecast(series.id, series.resource, origin, bucket, v, y, outs[v], fallback, reason, secs)
            for v in views]


def _log(msg: str) -> None:
    print(f"[engine] {msg}", file=sys.stderr, flush=True)


def _run_batched(method: str, tasks: list, horizon: int, quantiles: tuple) -> list[Forecast]:
    m = _method(method)
    out: list[Forecast] = []
    t0 = time.perf_counter()
    if hasattr(m, "pipeline"):
        m.pipeline()  # load weights once, outside the per-batch timing
    load_s = time.perf_counter() - t0
    LOAD_SECONDS[method] = round(load_s, 2)
    _log(f"{method}: model ready in {load_s:.1f}s; {len(tasks)} forecasts")
    bs = getattr(m, "batch_size", 64)
    for start in range(0, len(tasks), bs):
        chunk = tasks[start:start + bs]
        hists = [s.head(o) for s, o, _ in chunk]
        ys = [np.asarray(s.values[o:o + horizon], dtype=float) for s, o, _ in chunk]
        short = [len(h) < m.min_history for h in hists]
        t = time.perf_counter()
        reason = ""
        try:
            ok = [h for h, sh in zip(hists, short) if not sh]
            preds = iter(m.fit_predict_batch(ok, horizon, quantiles) if ok else [])
            arrs = [None if sh else next(preds) for sh in short]
        except Exception as e:
            reason = f"{type(e).__name__}: {e}"[:200].replace("\n", " ")
            arrs = [None] * len(chunk)
        per = (time.perf_counter() - t) / len(chunk)
        for (s, o, b), h, yv, sh, arr in zip(chunk, hists, ys, short, arrs):
            if arr is None:
                r = f"history {len(h)} < min_history {m.min_history}" if sh else reason
                out.append(Forecast(s.id, s.resource, o, b, method, yv, _fallback(h, horizon, quantiles),
                                    True, r, per))
            else:
                out.append(Forecast(s.id, s.resource, o, b, method, yv, arr, False, "", per))
        _log(f"{method}: {min(start + bs, len(tasks))}/{len(tasks)}")
    return out


def run_backtest(series_list: list[Series], methods: list[str], horizon: int = HORIZON,
                 quantiles: tuple = QUANTILES, workers: int = 7, seed: int = SEED) -> list[Forecast]:
    unknown = [m for m in methods if m not in REGISTRY]
    if unknown:
        raise ValueError(f"unknown methods {unknown}; known: {sorted(REGISTRY)}")
    pool_methods = [m for m in methods if m not in BATCH_METHODS]
    batch_methods = [m for m in methods if m in BATCH_METHODS]
    origins = [(s, o, b) for s in series_list for o, b in origins_for(len(s), horizon)]
    tasks = [(s, o, b, m, horizon, quantiles, seed) for m in pool_methods for s, o, b in origins]

    forecasts: list[Forecast] = []
    if tasks:
        _log(f"{len(tasks)} pool tasks ({', '.join(pool_methods)}) on {workers} worker(s)")
        step = max(1, len(tasks) // 20)
        if workers <= 1:
            _quiet()
            results = map(_run_task, tasks)
            pool = None
        else:
            pool = ProcessPoolExecutor(max_workers=workers, mp_context=mp.get_context("spawn"), initializer=_quiet)
            results = pool.map(_run_task, tasks, chunksize=4)
        try:
            for i, res in enumerate(results, 1):
                forecasts.extend(res)
                if i % step == 0 or i == len(tasks):
                    _log(f"pool {i}/{len(tasks)}")
        finally:
            if pool is not None:
                pool.shutdown()
    for m in batch_methods:
        ordered = sorted(origins, key=lambda x: (x[0].id, x[0].resource, x[1]))
        forecasts.extend(_run_batched(m, ordered, horizon, quantiles))
    return forecasts


def to_frame(forecasts: list[Forecast], quantiles: tuple = QUANTILES) -> pd.DataFrame:
    """One row per (series, resource, origin, method, h), sorted by SORT_KEYS."""
    if not forecasts:
        return pd.DataFrame(columns=SORT_KEYS[:4] + ["bucket", "h", "y"] + [f"q_{q}" for q in quantiles]
                            + ["fallback", "reason", "seconds"])
    n = np.array([len(f.y) for f in forecasts])

    def rep(attr):
        return np.repeat(np.array([getattr(f, attr) for f in forecasts], dtype=object), n)

    q = np.concatenate([f.q for f in forecasts])
    df = pd.DataFrame({
        "series_id": rep("series_id"), "resource": rep("resource"),
        "origin": np.repeat([f.origin for f in forecasts], n), "bucket": rep("bucket"),
        "method": rep("method"), "h": np.concatenate([np.arange(1, k + 1) for k in n]),
        "y": np.concatenate([f.y for f in forecasts]),
        **{f"q_{qq}": q[:, j] for j, qq in enumerate(quantiles)},
        "fallback": np.repeat([f.fallback for f in forecasts], n), "reason": rep("reason"),
        "seconds": np.repeat([f.seconds for f in forecasts], n),
    })
    return df.sort_values(SORT_KEYS, kind="mergesort").reset_index(drop=True)
