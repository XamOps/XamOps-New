"""Deterministic synthetic corpus for offline smoke runs and tests."""
import numpy as np
import pandas as pd

from ..config import SEED
from .types import Series

ANCHOR = pd.Timestamp("2018-01-01T00:00:00Z")


def _series_values(rng: np.random.Generator, length: int, resource: str) -> np.ndarray:
    t = np.arange(length, dtype=float)
    if resource == "cpu":
        base = rng.uniform(10, 50)
        daily = rng.uniform(3, 20) * np.sin(2 * np.pi * (t + rng.uniform(0, 24)) / 24)
        weekly = rng.uniform(0, 6) * np.sin(2 * np.pi * t / 168)
        noise = rng.normal(0, rng.uniform(1, 4), length)
        spikes = np.zeros(length)
        for start in rng.choice(length, size=rng.poisson(4), replace=False):
            spikes[start:start + rng.integers(1, 4)] += rng.uniform(10, 40)
        v = base + daily + weekly + noise + spikes
    else:
        base = rng.uniform(30, 70)
        drift = rng.uniform(-0.02, 0.05) * t
        daily = rng.uniform(0, 5) * np.sin(2 * np.pi * (t + rng.uniform(0, 24)) / 24)
        weekly = rng.uniform(0, 3) * np.sin(2 * np.pi * t / 168)
        noise = rng.normal(0, rng.uniform(0.3, 1.5), length)
        spikes = np.zeros(length)
        for start in rng.choice(length, size=rng.poisson(1), replace=False):
            spikes[start:start + rng.integers(1, 6)] += rng.uniform(5, 20)
        v = base + drift + daily + weekly + noise + spikes
    return np.clip(v, 0, 100)


def generate(n_series: int, seed: int = SEED, length: int = 192) -> list[Series]:
    """``n_series`` synthetic containers -> 2*n Series (cpu, mem), values in 0-100.

    Each container uses its own ``default_rng([seed, i])`` stream, so series ``i`` is
    identical regardless of ``n_series``.
    """
    index = pd.date_range(ANCHOR, periods=length, freq="h")
    out: list[Series] = []
    for i in range(n_series):
        rng = np.random.default_rng([seed, i])
        sid = f"syn{i:04d}"
        for resource in ("cpu", "mem"):
            out.append(Series(sid, resource, "pct", index, _series_values(rng, length, resource), "synthetic"))
    return out
