import numpy as np
import pandas as pd
import pytest

from backtest.config import QUANTILES
from backtest.data.types import Series
from backtest.methods import HOD_METHODS, REGISTRY
from backtest.methods.histogram import HistogramForecaster
from backtest.methods.hod_histogram import HodHistogramForecaster

J50, J95 = QUANTILES.index(0.5), QUANTILES.index(0.95)


def _series(values, resource="cpu", start="2018-01-01T00:00:00Z"):
    values = np.asarray(values, dtype=float)
    index = pd.date_range(start, periods=len(values), freq="h", tz="UTC")
    return Series("t", resource, "pct", index, values, "test")


def _diurnal(days, busy=(9, 17), hi=60.0, lo=2.0):
    hours = np.arange(days * 24) % 24
    return np.where((hours >= busy[0]) & (hours < busy[1]), hi, lo)


def test_forecast_follows_hour_of_day_shape():
    hist = _series(_diurnal(5))  # history ends at 23:00, horizon covers 00:00..23:00
    out = HodHistogramForecaster(7).fit_predict(hist, 24, QUANTILES)
    q95 = out[:, J95]
    busy = np.arange(24)  # hour of day of h = 1..24
    is_busy = (busy >= 9) & (busy < 17)
    assert np.all(q95[is_busy] >= 60.0) and np.all(q95[is_busy] <= 63.0)
    assert np.all(q95[~is_busy] >= 2.0) and np.all(q95[~is_busy] <= 2.1)
    # The flat histogram cannot separate the two: one vector for every hour.
    flat = HistogramForecaster().fit_predict(hist, 24, QUANTILES)[:, J95]
    assert np.ptp(flat) == 0 and flat[0] >= 60.0


def test_future_hours_use_their_own_hour_of_day_bucket():
    # History ends at 05:00, so h=1 is 06:00. Hour 6 is always 50, every other hour 1.
    vals = np.where(np.arange(24 * 3 + 6) % 24 == 6, 50.0, 1.0)
    out = HodHistogramForecaster(7).fit_predict(_series(vals), 24, QUANTILES)
    assert out[0, J95] >= 50.0 and np.all(out[1:, J95] <= 1.05)


def test_half_life_is_in_days():
    # 49 h: the hour-0 bucket holds 10 (48 h old), 20 (24 h old), 30 (age 0).
    vals = np.full(49, 1.0)
    vals[0], vals[24], vals[48] = 10.0, 20.0, 30.0
    hist = _series(vals)
    # half-life 1 day -> weights 0.25, 0.5, 1 (total 1.75). Cumulative 0.25, 0.75, 1.75:
    # q0.4 (0.7) is reached by the 20 sample, q0.5 (0.875) only by the 30 sample.
    table, counts, used_global = HodHistogramForecaster(1).bucket_table(hist, (0.4, 0.5))
    assert counts[0] == 3 and not used_global[0]
    assert 20.0 <= table[0, 0] <= 21.0 and 30.0 <= table[0, 1] <= 31.5
    # half-life 14 days -> near-equal weights (0.906, 0.952, 1): q0.4 is now the 20 sample
    # too, but q0.3 (0.857 of 2.858) falls on the 10 sample instead of 20 under 1 day.
    slow = HodHistogramForecaster(14).bucket_table(hist, (0.3,))[0]
    fast = HodHistogramForecaster(1).bucket_table(hist, (0.3,))[0]
    assert 10.0 <= slow[0, 0] <= 10.5 and 20.0 <= fast[0, 0] <= 21.0


def test_sparse_bucket_falls_back_to_global_histogram():
    # 49 h history: hour-of-day 0 has 3 samples, hours 1..23 have 2 -> all buckets are used.
    hist = _series(_diurnal(2).tolist() + [5.0])
    _, counts, used_global = HodHistogramForecaster(7).bucket_table(hist, QUANTILES)
    assert counts.min() >= 2 and not used_global.any()
    # Drop a day by starting mid-day: 48 h starting at 12:00 still gives 2 per hour; force a gap
    # in hour coverage with a 47 h window -> one hour of day has a single sample -> global.
    idx = pd.date_range("2018-01-01T00:00:00Z", periods=47, freq="h", tz="UTC")
    s = Series("t", "cpu", "pct", idx, np.linspace(1, 50, 47), "test")
    table, counts, used_global = HodHistogramForecaster(7).bucket_table(s, QUANTILES)
    glob = HistogramForecaster().quantiles_of(s.values, QUANTILES)
    assert counts[23] == 1 and used_global[23]
    assert table[23] == pytest.approx(glob)
    assert not used_global[:23].any()


def test_short_history_returns_flat_histogram():
    hist = _series(_diurnal(1)[:40])
    m = HodHistogramForecaster(7)
    assert m.min_history == 48
    np.testing.assert_allclose(m.fit_predict(hist, 24, QUANTILES),
                               HistogramForecaster().fit_predict(hist, 24, QUANTILES))


def test_memory_is_delegated_to_flat_histogram():
    hist = _series(np.linspace(20, 60, 100), resource="mem")
    np.testing.assert_allclose(HodHistogramForecaster(7).fit_predict(hist, 24, QUANTILES),
                               HistogramForecaster().fit_predict(hist, 24, QUANTILES))


def test_shrinkage_pulls_sparse_buckets_toward_global():
    hist = _series(_diurnal(3))
    raw = HodHistogramForecaster(7).bucket_table(hist, QUANTILES)[0]
    glob = HistogramForecaster().quantiles_of(hist.values, QUANTILES)
    for k in (1, 4, 16):
        sh, counts, _ = HodHistogramForecaster(7, shrink_k=k).bucket_table(hist, QUANTILES)
        a = counts[:, None] / (counts[:, None] + k)
        np.testing.assert_allclose(sh, a * raw + (1 - a) * glob)
    # More shrinkage -> closer to the global quantile.
    d = [np.abs(HodHistogramForecaster(7, shrink_k=k).bucket_table(hist, QUANTILES)[0] - glob).max()
         for k in (1, 4, 16)]
    assert d[0] > d[1] > d[2]


def test_no_future_leakage_through_the_engine_history():
    vals = _diurnal(7)
    full = _series(vals)
    other = _series(np.concatenate([vals[:96], np.full(len(vals) - 96, 99.0)]))
    m = HodHistogramForecaster(7)
    np.testing.assert_allclose(m.fit_predict(full.head(96), 24, QUANTILES),
                               m.fit_predict(other.head(96), 24, QUANTILES))


@pytest.mark.parametrize("name", HOD_METHODS)
@pytest.mark.parametrize("length", [48, 72, 172])
def test_registry_contract(name, length):
    rng = np.random.default_rng(1)
    vals = np.clip(_diurnal(8) + rng.normal(0, 3, 192), 0, 100)
    arr = REGISTRY[name]().fit_predict(_series(vals).head(length), 24, QUANTILES)
    assert arr.shape == (24, len(QUANTILES))
    assert np.all(np.isfinite(arr)) and np.all(arr >= 0) and np.all(arr <= 100)
    assert np.all(np.diff(arr, axis=1) >= 0)
