"""Hourly usage forecaster for /forecast/usage (CloudSitter schedule recommendations).

Method per resource, chosen by the offline quantile backtest
(.agents/tasks/forecast-backtest-2026-10-24/results.md, harness in backtest/):

* memory -> StatsForecast AutoETS(season_length=24) prediction intervals.
* cpu    -> VPA-style decayed-percentile histogram (half-life 24 h, geometric buckets
            first edge 0.01, ratio 1.05). Every quantile comes from the histogram.
* resource missing -> the histogram with the CPU rule (fallback_reason="resource_unspecified").

The histogram is also the fallback when there are fewer than 48 usable hourly points or
when the ETS fit raises / returns non-finite values (fallback_reason says which). For
memory, the histogram replaces q >= 0.95 with max(last 192 h) * 1.15, as in the harness.

Quantile semantics of each forecast entry:
    yhat = p50, yhat_lower = p10,
    yhat_upper = p90 (upper bound of a central 80 % interval, the same meaning Prophet's
                 interval_width=0.80 had; it is NOT a P95),
    p95 = the honest 95th percentile, to be used as headroom.

The method bodies and parameters are ports of backtest/methods/histogram.py,
backtest/methods/statsforecast_methods.py and backtest/methods/base.py. Production must not
import the backtest package; tests/test_backtest_parity.py proves the ports match it.
"""
import logging
import math
import warnings

import numpy as np
import pandas as pd

logger = logging.getLogger(__name__)

# Decayed histogram (backtest HistogramForecaster defaults)
HALF_LIFE_H = 24.0
FIRST_EDGE = 0.01
RATIO = 1.05
MEM_WINDOW_H = 192
MEM_MARGIN = 0.15

# AutoETS (backtest ETSForecaster)
ETS_SEASON = 24
ETS_LEVELS = [80, 90]
# Production safety cap (bounds latency on long requests; 30 days = CloudSitter lookback).
ETS_MAX_HISTORY_H = 720

MODEL_MIN_HISTORY = 48
MAX_FILL_GAP_H = 2
QUANTILES = (0.1, 0.5, 0.9, 0.95)

RESOURCE_ALIASES = {'cpu': 'cpu', 'memory': 'memory', 'mem': 'memory'}
RESOURCE_UNSPECIFIED = 'unspecified'

QUANTILE_SEMANTICS = ("yhat=p50; yhat_lower=p10; yhat_upper=p90 (upper bound of central 80% "
                      "interval, NOT p95); p95 is the headroom quantile")


def normalize_resource(value):
    """Map a request ``resource`` to cpu | memory | unspecified. Raises ValueError if invalid."""
    if value is None:
        return RESOURCE_UNSPECIFIED
    key = str(value).strip().lower()
    if key not in RESOURCE_ALIASES:
        raise ValueError('resource must be one of cpu, memory')
    return RESOURCE_ALIASES[key]


def _postprocess(arr):
    """Port of backtest base.postprocess for percent units: finite check, clip [0,100], sort."""
    arr = np.asarray(arr, dtype=float)
    if not np.all(np.isfinite(arr)):
        raise ValueError("non-finite forecast values")
    arr = np.clip(arr, 0.0, 100.0)
    return np.sort(arr, axis=1)


# ---- decayed histogram (port of backtest/methods/histogram.py) ----

def _weights(n):
    t = np.arange(n, dtype=float)
    return np.power(2.0, (t - (n - 1)) / HALF_LIFE_H)


def _upper_edge(idx):
    return FIRST_EDGE * np.power(RATIO, np.asarray(idx, dtype=float))


def _bucket_index(values):
    v = np.asarray(values, dtype=float)
    idx = np.zeros(len(v), dtype=np.int64)
    pos = v >= FIRST_EDGE
    idx[pos] = np.floor(np.log(v[pos] / FIRST_EDGE) / math.log(RATIO)).astype(np.int64) + 1
    # Guard float rounding so that every value lies strictly below its bucket's upper edge.
    idx[pos & (_upper_edge(idx) <= v)] += 1
    return idx


def _hist_quantiles_of(values, quantiles):
    idx = _bucket_index(values)
    cum = np.cumsum(np.bincount(idx, weights=_weights(len(values))))
    total = cum[-1]
    out = []
    for q in quantiles:
        k = int(np.searchsorted(cum, q * total * (1 - 1e-12), side="left"))
        out.append(float(_upper_edge(min(k, len(cum) - 1))))
    return np.array(out)


def histogram_quantiles(values, quantiles=QUANTILES, resource='cpu'):
    """Flat quantile vector (len(quantiles),). Memory window-max rule only for resource=memory."""
    values = np.asarray(values, dtype=float)
    qv = _hist_quantiles_of(values, quantiles)
    if resource == 'memory':
        peak = float(np.max(values[-MEM_WINDOW_H:])) * (1 + MEM_MARGIN)
        qv = np.array([peak if q >= 0.95 else v for q, v in zip(quantiles, qv)])
    return _postprocess(qv.reshape(1, -1))[0]


# ---- AutoETS (port of backtest/methods/statsforecast_methods.py) ----

def _level(q):
    return int(round(abs(2 * q - 1) * 100))


def ets_quantiles(values, horizon, quantiles=QUANTILES):
    """AutoETS(season_length=24) quantiles, shape (horizon, len(quantiles))."""
    from statsforecast.models import AutoETS  # lazy: keeps /health and CPU requests light

    levels = sorted({_level(q) for q in quantiles if q != 0.5})
    y = np.asarray(values, dtype=float)
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        res = AutoETS(season_length=ETS_SEASON).forecast(y=y, h=horizon, level=levels)
    cols = []
    for q in quantiles:
        if q == 0.5:
            cols.append(res["mean"])
        else:
            cols.append(res[f"{'hi' if q > 0.5 else 'lo'}-{_level(q)}"])
    return _postprocess(np.column_stack(cols))


# ---- series cleaning and trend ----

def clean_hourly(data):
    """Return (values, t_hours, contiguous_tail, last_ds) from [{ds, y}] points.

    ds -> UTC tz-naive, floored to the hour, duplicates aggregated by max; y numeric, clipped
    0-100. Reindexed to a continuous hourly grid; gaps of <= 2 h are forward-filled, longer
    gaps stay missing. ``values``/``t_hours`` are the non-missing points in time order;
    ``contiguous_tail`` is the trailing run with no missing hour.
    """
    df = pd.DataFrame(data)
    if 'ds' not in df.columns or 'y' not in df.columns:
        raise ValueError("Data must contain 'ds' and 'y' columns")
    ds = pd.to_datetime(df['ds'], utc=True, errors='coerce').dt.tz_localize(None)
    y = pd.to_numeric(df['y'], errors='coerce')
    s = pd.Series(y.values, index=ds).dropna()
    s = s[s.index.notna()].clip(0, 100)
    if s.empty:
        raise ValueError("No valid hourly points after cleaning")
    s = s.groupby(s.index.floor('h')).max().sort_index()
    grid = s.reindex(pd.date_range(s.index[0], s.index[-1], freq='h'))

    missing = grid.isna()
    run_id = (~missing).cumsum()
    run_len = missing.groupby(run_id).transform('sum')
    fill_mask = missing & (run_len <= MAX_FILL_GAP_H)
    grid = grid.where(~fill_mask, grid.ffill())

    present = grid.notna().values
    values = grid.values[present].astype(float)
    t_hours = np.arange(len(grid), dtype=float)[present]
    gaps = np.flatnonzero(~present)
    tail = grid.values[gaps[-1] + 1:] if len(gaps) else grid.values
    return values, t_hours, np.asarray(tail, dtype=float), grid.index[-1]


def trend_direction(values, t_hours=None):
    """GROWING / STABLE / DECLINING from an OLS line (first vs last quarter, +-5 %)."""
    values = np.asarray(values, dtype=float)
    n = len(values)
    if n < MODEL_MIN_HISTORY:
        return "STABLE"
    t = np.arange(n, dtype=float) if t_hours is None else np.asarray(t_hours, dtype=float)
    slope, intercept = np.polyfit(t, values, 1)
    quarter = max(1, n // 4)
    early = slope * t[:quarter].mean() + intercept
    late = slope * t[-quarter:].mean() + intercept
    pct = (late - early) / max(0.1, abs(early)) * 100
    if pct > 5:
        return "GROWING"
    if pct < -5:
        return "DECLINING"
    return "STABLE"


class UsageForecaster:
    """Quantile forecaster for hourly usage (percent)."""

    def forecast_usage(self, data, periods=336, resource=None, now=None):
        """Return {forecast, trend_direction, method, fallback_reason, resource,
        history_points_used, model_params}. ``resource`` may be cpu | memory | mem | None."""
        if resource != RESOURCE_UNSPECIFIED:
            resource = normalize_resource(resource)
        values, t_hours, tail, last_ds = clean_hourly(data)

        hist_params = {'half_life_h': HALF_LIFE_H, 'first_edge': FIRST_EDGE, 'ratio': RATIO}
        if resource == 'memory':
            hist_params.update({'mem_window_h': MEM_WINDOW_H, 'mem_margin': MEM_MARGIN})

        method, fallback_reason, qmat, used = 'histogram', None, None, len(values)
        if resource == RESOURCE_UNSPECIFIED:
            fallback_reason = 'resource_unspecified'
        elif len(values) < MODEL_MIN_HISTORY:
            fallback_reason = 'insufficient_history'
        elif resource == 'memory':
            if len(tail) < MODEL_MIN_HISTORY:
                fallback_reason = 'insufficient_contiguous_history'
            else:
                ets_input = tail[-ETS_MAX_HISTORY_H:]
                try:
                    qmat = _postprocess(ets_quantiles(ets_input, periods))
                    method, used = 'ets', len(ets_input)
                except Exception as e:  # any fit failure -> histogram
                    fallback_reason = f"fit_error: {type(e).__name__}"
                    logger.warning(f"⚠ AutoETS failed, falling back to histogram: {e}", exc_info=True)

        if qmat is None:
            qv = histogram_quantiles(values, QUANTILES, 'memory' if resource == 'memory' else 'cpu')
            qmat = np.tile(qv, (periods, 1))
            model_params = hist_params
        else:
            model_params = {'season_length': ETS_SEASON, 'levels': list(ETS_LEVELS),
                            'max_history_h': ETS_MAX_HISTORY_H}

        if now is None:
            now = pd.Timestamp.now(tz='UTC').tz_localize(None)
        results = []
        for i in range(periods):
            ts = last_ds + pd.Timedelta(hours=i + 1)
            if ts <= now:
                continue
            p10, p50, p90, p95 = (round(float(v), 2) for v in qmat[i])
            results.append({
                'ds': ts.isoformat() + 'Z',
                'yhat': p50,
                'yhat_lower': p10,
                'yhat_upper': p90,
                'p50': p50,
                'p90': p90,
                'p95': p95,
            })

        return {
            'forecast': results,
            'trend_direction': trend_direction(values, t_hours),
            'method': method,
            'fallback_reason': fallback_reason,
            'resource': resource,
            'history_points_used': used,
            'model_params': model_params,
        }
