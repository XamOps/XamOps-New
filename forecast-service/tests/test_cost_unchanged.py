import pandas as pd

import app as app_module
from models.prophet_forecaster import ProphetForecaster


def test_cost_still_uses_prophet():
    assert isinstance(app_module.forecaster, ProphetForecaster)


def test_cost_response_shape_unchanged(client, monkeypatch):
    fixed = [{'ds': '2026-01-01', 'yhat': 1.0, 'yhat_lower': 0.5, 'yhat_upper': 1.5}]
    monkeypatch.setattr(app_module.forecaster, 'forecast', lambda **kw: fixed)
    days = pd.date_range('2025-12-01', periods=10, freq='D')
    data = [{'ds': d.strftime('%Y-%m-%d'), 'y': 10.0 + i} for i, d in enumerate(days)]
    r = client.post('/forecast/cost', json={'data': data, 'periods': 5})
    assert r.status_code == 200
    j = r.get_json()
    assert set(j) == {'status', 'forecast', 'metadata'}
    assert j['forecast'] == fixed
    assert set(j['metadata']) == {'environment', 'historical_data_points', 'forecast_periods',
                                  'weekly_seasonality', 'yearly_seasonality',
                                  'processing_time_seconds', 'historical_stats'}
