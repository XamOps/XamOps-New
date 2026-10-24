"""Hour-of-day-aware decayed CPU histogram ("option B", FEAT hod follow-up).

The flat histogram (``histogram.py``) returns one quantile vector for every horizon hour,
so it carries no schedule shape. This method keeps 24 separate decayed histograms, one per
UTC hour of day, and forecasts each horizon hour from the bucket of its own hour of day.

* Bucket geometry (geometric edges, upper-edge quantile) is the flat histogram's.
* A sample's weight is ``2 ** (-age_h / (24 * half_life_days))``, where ``age_h`` is its
  age in hours relative to the last history point. One sample lands in a bucket per day,
  so the half-life is effectively "per day seen in that bucket".
* ``shrink_k`` (optional) blends the bucket quantile toward the global (flat) histogram
  quantile with weight ``n / (n + shrink_k)``, ``n`` = raw sample count in the bucket, so
  sparse buckets stay close to the global level instead of following one or two samples.

Fallbacks:
  * a bucket with fewer than ``MIN_BUCKET_SAMPLES`` (2) samples -> the global histogram
    quantiles (the flat histogram over the whole history, half-life 24 h, as today);
  * history shorter than ``min_history`` (48 points) -> the engine's flat-histogram fallback
    (``fallback=True``). A direct call with < 48 points returns the flat histogram too.
  * memory series -> the flat histogram unchanged (this method is evaluated for CPU only).

Only ``history.index`` and ``history.values`` are read; future hour-of-day labels come
from ``history.index[-1] + k h``, so no future value can enter the forecast.
"""
import numpy as np
import pandas as pd

from ..data.types import Series
from .base import Forecaster, postprocess
from .histogram import HistogramForecaster

MIN_BUCKET_SAMPLES = 2
MIN_HISTORY = 48


class HodHistogramForecaster(Forecaster):
    min_history = MIN_HISTORY

    def __init__(self, half_life_days: float = 7.0, shrink_k: float | None = None, name: str | None = None):
        self.half_life_days = float(half_life_days)
        self.shrink_k = shrink_k
        self.name = name or (f"hod_hl{half_life_days:g}" if shrink_k is None
                             else f"hod_shrink_hl{half_life_days:g}_k{shrink_k:g}")
        self.global_hist = HistogramForecaster()  # flat, half-life 24 h: the global quantiles

    def weighted_quantiles(self, values: np.ndarray, weights: np.ndarray, quantiles: tuple) -> np.ndarray:
        """Upper edge of the first bucket whose cumulative weight reaches q * total."""
        g = self.global_hist
        cum = np.cumsum(np.bincount(g.bucket_index(values), weights=weights))
        total = cum[-1]
        ks = [int(np.searchsorted(cum, q * total * (1 - 1e-12), side="left")) for q in quantiles]
        return np.array([float(g.upper_edge(min(k, len(cum) - 1))) for k in ks])

    def bucket_table(self, history: Series, quantiles: tuple) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        """(table (24, nq), sample counts (24,), used_global (24,) bool) for one history."""
        values = np.asarray(history.values, dtype=float)
        idx = pd.DatetimeIndex(history.index)
        glob = self.global_hist.quantiles_of(values, quantiles)
        age_h = (idx[-1] - idx).total_seconds().to_numpy() / 3600.0
        w = np.power(2.0, -age_h / (24.0 * self.half_life_days))
        hours = idx.hour.to_numpy()
        table = np.tile(glob, (24, 1))
        counts = np.bincount(hours, minlength=24)
        used_global = counts < MIN_BUCKET_SAMPLES
        for b in np.flatnonzero(~used_global):
            m = hours == b
            qb = self.weighted_quantiles(values[m], w[m], quantiles)
            if self.shrink_k is not None:
                a = counts[b] / (counts[b] + float(self.shrink_k))
                qb = a * qb + (1.0 - a) * glob
            table[b] = qb
        return table, counts, used_global

    def fit_predict(self, history: Series, horizon: int, quantiles: tuple) -> np.ndarray:
        if history.resource != "cpu" or len(history) < self.min_history:
            return self.global_hist.fit_predict(history, horizon, quantiles)
        table, _, _ = self.bucket_table(history, quantiles)
        future = pd.DatetimeIndex(history.index)[-1] + pd.to_timedelta(np.arange(1, horizon + 1), unit="h")
        return postprocess(table[future.hour.to_numpy()], history.unit)
