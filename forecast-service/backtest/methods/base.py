import numpy as np

from ..data.types import Series


class Forecaster:
    """One forecasting method. ``fit_predict`` returns shape (horizon, len(quantiles))."""

    name: str = "base"
    min_history: int = 1

    def fit_predict(self, history: Series, horizon: int, quantiles: tuple) -> np.ndarray:
        raise NotImplementedError


def postprocess(arr, unit: str) -> np.ndarray:
    """Clip to >= 0 (and <= 100 for percent units) and make monotone across quantiles."""
    arr = np.asarray(arr, dtype=float)
    if not np.all(np.isfinite(arr)):
        raise ValueError("non-finite forecast values")
    arr = np.clip(arr, 0.0, 100.0 if unit == "pct" else None)
    return np.sort(arr, axis=1)
