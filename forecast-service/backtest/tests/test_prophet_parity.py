from pathlib import Path

import numpy as np

import backtest
from backtest.config import QUANTILES
from backtest.data import synthetic
from backtest.methods.prophet_method import ProphetForecaster

PROD_SOURCE = Path(backtest.__file__).resolve().parent.parent / "models" / "prophet_forecaster.py"


def test_production_forecast_usage_config_unchanged():
    """Drift guard: the harness copies forecast_usage's Prophet arguments (D3)."""
    src = PROD_SOURCE.read_text()
    for needle in ("daily_seasonality=True", "interval_width=0.80", "changepoint_prior_scale=0.05",
                   ">= 168", "minimum 48"):
        assert needle in src, f"{needle!r} no longer in {PROD_SOURCE}; re-sync prophet_method.py"


def test_tiny_fit_shape_and_monotone():
    s = synthetic.generate(1)[0].head(72)
    views = ProphetForecaster().fit_predict_views(s, 24, QUANTILES)
    for arr in views.values():
        assert arr.shape == (24, 5)
        assert np.all(np.diff(arr, axis=1) >= 0) and np.all(arr >= 0) and np.all(arr <= 100)
