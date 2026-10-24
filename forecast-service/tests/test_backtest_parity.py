"""The production ports in models/usage_forecaster.py must match the approved backtest harness."""
import numpy as np
import pandas as pd
import pytest

from backtest.data import synthetic
from backtest.data.types import Series
from backtest.methods.histogram import HistogramForecaster
from backtest.methods.statsforecast_methods import ETSForecaster
from models.usage_forecaster import histogram_quantiles, ets_quantiles, QUANTILES

HARNESS_Q = (0.5, 0.8, 0.9, 0.95, 0.99)


def _series(values, resource):
    values = np.asarray(values, dtype=float)
    index = pd.date_range('2018-01-01', periods=len(values), freq='h', tz='UTC')
    return Series('t', resource, 'pct', index, values, 'test')


@pytest.mark.parametrize('resource,harness_resource', [('cpu', 'cpu'), ('memory', 'mem')])
def test_histogram_matches_harness(resource, harness_resource):
    for s in synthetic.generate(3):
        values = s.values
        ours = histogram_quantiles(values, QUANTILES, resource)
        theirs = HistogramForecaster().fit_predict(_series(values, harness_resource), 24, QUANTILES)
        assert np.allclose(ours, theirs[0])
        # Quantiles shared with the harness default grid.
        theirs_default = HistogramForecaster().fit_predict(_series(values, harness_resource), 24, HARNESS_Q)
        for q in (0.5, 0.9, 0.95):
            assert ours[QUANTILES.index(q)] == pytest.approx(theirs_default[0, HARNESS_Q.index(q)])


def test_histogram_raw_quantiles_match_harness():
    values = synthetic.generate(1)[0].values
    from models.usage_forecaster import _hist_quantiles_of
    assert np.allclose(_hist_quantiles_of(values, QUANTILES),
                       HistogramForecaster().quantiles_of(values, QUANTILES))


def test_ets_matches_harness():
    s = synthetic.generate(1)[1].head(120)  # memory series
    assert s.resource == 'mem'
    ours = ets_quantiles(s.values, 24)
    theirs = ETSForecaster().fit_predict(s, 24, HARNESS_Q)
    for q in (0.5, 0.9, 0.95):
        assert np.allclose(ours[:, QUANTILES.index(q)], theirs[:, HARNESS_Q.index(q)])
