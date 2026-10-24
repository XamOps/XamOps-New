"""Production-config Prophet (D3).

The constructor arguments mirror forecast-service/models/prophet_forecaster.py::forecast_usage
(the CloudSitter hourly usage model). That function drops every row before
``pd.Timestamp.now()``, so it cannot score historical origins and is re-created here
instead of called; ``tests/test_prophet_parity.py`` guards against source drift.

Two views come from one fit:
  * ``prophet``      - every quantile from ``np.quantile`` over ``predictive_samples(future)['yhat']``.
  * ``prophet_asis`` - what the product consumes today: q=0.5 := yhat, q>=0.95 := yhat_upper
                       (the upper bound of the 0.80 interval, i.e. roughly a P90), and
                       q=0.8/0.9 taken from the samples as in ``prophet``.
"""
import logging
import warnings

import numpy as np
import pandas as pd

from ..config import SEED
from ..data.types import Series
from .base import Forecaster, postprocess


def _silence() -> None:
    # cmdstanpy resets its logger to DEBUG on first use, so this runs after the import.
    for name in ("cmdstanpy", "prophet"):
        logging.getLogger(name).setLevel(logging.ERROR)


class ProphetForecaster(Forecaster):
    name = "prophet"
    min_history = 48
    views = ("prophet", "prophet_asis")

    def __init__(self, seed: int = SEED):
        self.seed = seed

    def fit_predict_views(self, history: Series, horizon: int, quantiles: tuple) -> dict[str, np.ndarray]:
        from prophet import Prophet

        _silence()
        if len(history) < self.min_history:
            raise ValueError(f"Insufficient data: {len(history)} hourly points (minimum 48 required)")
        y = np.asarray(history.values, dtype=float)
        if history.unit == "pct":
            y = np.clip(y, 0, 100)
        ds = pd.DatetimeIndex(history.index).tz_convert(None) if history.index.tz else pd.DatetimeIndex(history.index)
        df = pd.DataFrame({"ds": ds, "y": y})
        span_h = (df["ds"].max() - df["ds"].min()).total_seconds() / 3600

        # Same arguments as models/prophet_forecaster.py::forecast_usage.
        model = Prophet(
            yearly_seasonality=False,
            weekly_seasonality=span_h >= 168,
            daily_seasonality=True,
            interval_width=0.80,
            changepoint_prior_scale=0.05,
            seasonality_mode="additive",
            growth="linear",
        )
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            model.fit(df, seed=self.seed)
            future = model.make_future_dataframe(periods=horizon, freq="H", include_history=False)
            np.random.seed(self.seed)
            fc = model.predict(future)
            np.random.seed(self.seed)
            samples = model.predictive_samples(future)["yhat"]  # (horizon, n_samples)

        q = np.asarray(quantiles, dtype=float)
        sampled = np.quantile(samples, q, axis=1).T  # (horizon, len(q))
        asis = sampled.copy()
        for j, qj in enumerate(q):
            if qj == 0.5:
                asis[:, j] = fc["yhat"].to_numpy()
            elif qj >= 0.95:
                asis[:, j] = fc["yhat_upper"].to_numpy()
        return {"prophet": postprocess(sampled, history.unit), "prophet_asis": postprocess(asis, history.unit)}

    def fit_predict(self, history: Series, horizon: int, quantiles: tuple) -> np.ndarray:
        return self.fit_predict_views(history, horizon, quantiles)["prophet"]
