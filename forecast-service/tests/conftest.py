import numpy as np
import pandas as pd
import pytest


def now_floor_hour():
    return pd.Timestamp.now(tz='UTC').tz_localize(None).floor('h')


def hourly(values, end=None):
    """[{ds, y}] hourly points whose last timestamp is ``end`` (default: the current UTC hour)."""
    end = now_floor_hour() if end is None else end
    idx = pd.date_range(end=end, periods=len(values), freq='h')
    return [{'ds': ts.isoformat() + 'Z', 'y': float(v)} for ts, v in zip(idx, values)]


def daily_pattern(n, base=40.0, amp=10.0, seed=7):
    rng = np.random.default_rng(seed)
    t = np.arange(n, dtype=float)
    return np.clip(base + amp * np.sin(2 * np.pi * t / 24) + rng.normal(0, 1.5, n), 0, 100)


@pytest.fixture
def client():
    import app as app_module
    app_module.app.config['TESTING'] = True
    with app_module.app.test_client() as c:
        yield c
