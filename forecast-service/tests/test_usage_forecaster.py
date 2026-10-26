import numpy as np
import pandas as pd
import pytest

from models import usage_forecaster as uf
from models.usage_forecaster import UsageForecaster, histogram_quantiles, QUANTILES
from tests.conftest import hourly, daily_pattern

END = pd.Timestamp('2026-01-01 00:00:00')
PERIODS = 48


def run(values, resource, periods=PERIODS, data=None):
    data = hourly(values, end=END) if data is None else data
    return UsageForecaster().forecast_usage(data, periods=periods, resource=resource, now=END)


def assert_entries_valid(out, periods=PERIODS):
    fc = out['forecast']
    assert len(fc) == periods
    for e in fc:
        assert e['ds'].endswith('Z')
        assert pd.Timestamp(e['ds'].rstrip('Z')) > END
        assert 0 <= e['yhat_lower'] <= e['p50'] <= e['p90'] <= e['p95'] <= 100
        assert e['yhat'] == e['p50']
        assert e['yhat_upper'] == e['p90']
        assert set(e) == {'ds', 'yhat', 'yhat_lower', 'yhat_upper', 'p50', 'p90', 'p95'}


def test_memory_uses_ets():
    out = run(daily_pattern(96), 'memory')
    assert out['method'] == 'ets' and out['fallback_reason'] is None and out['resource'] == 'memory'
    assert out['model_params'] == {'season_length': 24, 'levels': [80, 90], 'max_history_h': 720}
    assert_entries_valid(out)


def test_mem_alias():
    assert run(daily_pattern(96), 'MEM')['resource'] == 'memory'


def test_cpu_uses_histogram():
    out = run(daily_pattern(96), 'cpu')
    assert out['method'] == 'histogram' and out['fallback_reason'] is None
    assert out['model_params'] == {'half_life_h': 24.0, 'first_edge': 0.01, 'ratio': 1.05}
    assert_entries_valid(out)


def test_unspecified_resource_defaults_to_histogram_cpu_rule():
    values = daily_pattern(96)
    out = run(values, None)
    assert out['method'] == 'histogram'
    assert out['fallback_reason'] == 'resource_unspecified'
    assert out['resource'] == 'unspecified'
    expected = histogram_quantiles(values, QUANTILES, 'cpu')
    p95 = out['forecast'][0]['p95']
    assert p95 == round(float(expected[3]), 2)
    assert p95 != round(min(100.0, values[-192:].max() * 1.15), 2)
    assert_entries_valid(out)


def test_cpu_has_no_memory_window_rule():
    values = daily_pattern(96)
    out = run(values, 'cpu')
    assert out['forecast'][0]['p95'] < round(values.max() * 1.15, 2)


def test_memory_short_history_falls_back_with_memory_rule():
    values = daily_pattern(30)
    out = run(values, 'memory')
    assert out['method'] == 'histogram' and out['fallback_reason'] == 'insufficient_history'
    assert out['forecast'][0]['p95'] == min(100, round(values[-192:].max() * 1.15, 2))
    assert out['model_params']['mem_window_h'] == 192 and out['model_params']['mem_margin'] == 0.15
    assert_entries_valid(out)


def test_cpu_short_history_flags_insufficient_history():
    out = run(daily_pattern(30), 'cpu')
    assert out['method'] == 'histogram' and out['fallback_reason'] == 'insufficient_history'


def test_ets_exception_falls_back(monkeypatch):
    def boom(*a, **k):
        raise RuntimeError('fit failed')
    monkeypatch.setattr(uf, 'ets_quantiles', boom)
    out = run(daily_pattern(96), 'memory')
    assert out['method'] == 'histogram' and out['fallback_reason'] == 'fit_error: RuntimeError'
    assert_entries_valid(out)


def test_ets_non_finite_falls_back(monkeypatch):
    monkeypatch.setattr(uf, 'ets_quantiles', lambda v, h, *a, **k: np.full((h, 4), np.nan))
    out = run(daily_pattern(96), 'memory')
    assert out['method'] == 'histogram' and out['fallback_reason'] == 'fit_error: ValueError'
    assert_entries_valid(out)


def test_long_gap_leaves_short_contiguous_tail():
    values = daily_pattern(96)
    data = hourly(values, end=END)
    del data[-35:-30]  # 5 h gap -> trailing contiguous run of 30 h
    out = run(None, 'memory', data=data)
    assert out['method'] == 'histogram'
    assert out['fallback_reason'] == 'insufficient_contiguous_history'
    assert_entries_valid(out)


def test_short_gap_is_forward_filled_and_stays_on_ets():
    values = daily_pattern(96)
    data = hourly(values, end=END)
    del data[-32:-30]  # 2 h gap -> forward-filled
    out = run(None, 'memory', data=data)
    assert out['method'] == 'ets' and out['fallback_reason'] is None
    assert out['history_points_used'] == 96
    assert_entries_valid(out)


def test_clean_hourly_floors_and_aggregates_by_max():
    data = [{'ds': '2026-01-01T00:10:00Z', 'y': 10}, {'ds': '2026-01-01T00:50:00Z', 'y': 30},
            {'ds': '2026-01-01T01:00:00Z', 'y': 150}, {'ds': '2026-01-01T02:00:00Z', 'y': 'bad'}]
    values, _, tail, last = uf.clean_hourly(data)
    assert list(values) == [30.0, 100.0]
    assert last == pd.Timestamp('2026-01-01 01:00:00')


def test_no_valid_points_raises():
    with pytest.raises(ValueError):
        uf.clean_hourly([{'ds': 'x', 'y': None}])


def test_forecast_only_future_hours():
    # History ending 10 h before "now": only the hours after now are emitted.
    data = hourly(daily_pattern(96), end=END - pd.Timedelta(hours=10))
    out = UsageForecaster().forecast_usage(data, periods=PERIODS, resource='cpu', now=END)
    assert len(out['forecast']) == PERIODS - 10
    assert all(pd.Timestamp(e['ds'].rstrip('Z')) > END for e in out['forecast'])


@pytest.mark.parametrize('values,expected', [
    (np.linspace(10, 60, 120), 'GROWING'),
    (np.full(120, 40.0), 'STABLE'),
    (np.linspace(60, 10, 120), 'DECLINING'),
    (np.linspace(10, 60, 30), 'STABLE'),
])
def test_trend_direction(values, expected):
    assert run(values, 'cpu')['trend_direction'] == expected


def test_long_memory_history_is_capped_for_ets():
    out = run(daily_pattern(4000), 'memory', periods=24)
    assert out['method'] == 'ets'
    assert out['history_points_used'] == 720
    assert_entries_valid(out, periods=24)
