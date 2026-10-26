"""VPA-style decaying-histogram baseline (D6). No ML; also the universal fallback.

Buckets are geometric: bucket 0 is [0, first_edge), bucket k >= 1 is
[first_edge * ratio**(k-1), first_edge * ratio**k). A sample at hour t_i has weight
2 ** ((t_i - t_last) / half_life_h). The q-quantile is the upper edge of the first bucket
whose cumulative weight reaches q * total. Memory q >= 0.95 instead uses
max(last mem_window_h values) * (1 + mem_margin). The forecast is flat over the horizon.
"""
import math

import numpy as np

from ..data.types import Series
from .base import Forecaster, postprocess


class HistogramForecaster(Forecaster):
    name = "histogram"
    min_history = 1

    def __init__(self, half_life_h: float = 24.0, first_edge: float = 0.01, ratio: float = 1.05,
                 mem_window_h: int = 192, mem_margin: float = 0.15):
        self.half_life_h = half_life_h
        self.first_edge = first_edge
        self.ratio = ratio
        self.mem_window_h = mem_window_h
        self.mem_margin = mem_margin

    def weights(self, n: int) -> np.ndarray:
        """Decay weights for ``n`` consecutive hourly samples (last sample has weight 1)."""
        t = np.arange(n, dtype=float)
        return np.power(2.0, (t - (n - 1)) / self.half_life_h)

    def bucket_index(self, values: np.ndarray) -> np.ndarray:
        v = np.asarray(values, dtype=float)
        idx = np.zeros(len(v), dtype=np.int64)
        pos = v >= self.first_edge
        idx[pos] = np.floor(np.log(v[pos] / self.first_edge) / math.log(self.ratio)).astype(np.int64) + 1
        # Guard float rounding so that every value lies strictly below its bucket's upper edge.
        idx[pos & (self.upper_edge(idx) <= v)] += 1
        return idx

    def upper_edge(self, idx) -> np.ndarray:
        return self.first_edge * np.power(self.ratio, np.asarray(idx, dtype=float))

    def quantiles_of(self, values: np.ndarray, quantiles: tuple) -> np.ndarray:
        idx = self.bucket_index(values)
        cum = np.cumsum(np.bincount(idx, weights=self.weights(len(values))))
        total = cum[-1]
        out = []
        for q in quantiles:
            k = int(np.searchsorted(cum, q * total * (1 - 1e-12), side="left"))
            out.append(float(self.upper_edge(min(k, len(cum) - 1))))
        return np.array(out)

    def fit_predict(self, history: Series, horizon: int, quantiles: tuple) -> np.ndarray:
        values = np.asarray(history.values, dtype=float)
        qv = self.quantiles_of(values, quantiles)
        if history.resource == "mem":
            peak = float(np.max(values[-self.mem_window_h:])) * (1 + self.mem_margin)
            qv = np.array([peak if q >= 0.95 else v for q, v in zip(quantiles, qv)])
        return postprocess(np.tile(qv, (horizon, 1)), history.unit)
