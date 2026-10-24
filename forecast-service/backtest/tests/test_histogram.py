import numpy as np
import pandas as pd
import pytest

from backtest.config import QUANTILES
from backtest.data.types import Series
from backtest.methods.histogram import HistogramForecaster


def _series(values, resource="cpu"):
    values = np.asarray(values, dtype=float)
    index = pd.date_range("2018-01-01", periods=len(values), freq="h", tz="UTC")
    return Series("t", resource, "pct", index, values, "test")


@pytest.mark.parametrize("v", [0.5, 3.0, 37.2, 99.0])
def test_constant_series_within_one_bucket(v):
    out = HistogramForecaster().fit_predict(_series([v] * 100), 24, QUANTILES)
    assert out.shape == (24, len(QUANTILES))
    assert np.all(out >= v) and np.all(out <= v * 1.05 + 1e-9)


def test_sample_24h_older_has_half_weight():
    w = HistogramForecaster(half_life_h=24).weights(49)
    assert w[-1] == 1.0
    assert w[-25] == pytest.approx(0.5)
    assert w[0] == pytest.approx(0.25)


def test_recent_spike_dominates_p95_after_decay():
    # 100 h at 10 %, then the last 12 h at 80 %: recent weight share >> 5 %, so P95 sits at the spike.
    values = [10.0] * 100 + [80.0] * 12
    out = HistogramForecaster().fit_predict(_series(values), 24, QUANTILES)
    j95 = QUANTILES.index(0.95)
    assert 80.0 <= out[0, j95] <= 84.0
    # Undecayed it would only be 12 / 112 = 10.7 % of the samples; the median stays at the old level.
    assert out[0, QUANTILES.index(0.5)] <= 10.5


def test_memory_upper_quantiles_use_window_max_with_margin():
    values = np.linspace(20, 60, 300)
    out = HistogramForecaster().fit_predict(_series(values, "mem"), 24, QUANTILES)
    expected = values[-192:].max() * 1.15
    for q in (0.95, 0.99):
        assert out[0, QUANTILES.index(q)] == pytest.approx(expected)
    assert out[0, QUANTILES.index(0.5)] < expected


def test_one_point_history():
    out = HistogramForecaster().fit_predict(_series([42.0]), 24, QUANTILES)
    assert out.shape == (24, 5) and np.all(out >= 42.0) and np.all(out <= 42.0 * 1.05)
