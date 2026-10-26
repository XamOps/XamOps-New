"""StatsForecast AutoETS and MSTL (D4).

Quantile q maps to the model's prediction intervals: q=0.5 -> ``mean``, q>0.5 -> ``hi-L``,
q<0.5 -> ``lo-L`` with L = round(|2q - 1| * 100). For Q=(0.5,0.8,0.9,0.95,0.99) the levels
are 60, 80, 90, 98.
"""
import warnings

import numpy as np

from ..data.types import Series
from .base import Forecaster, postprocess


def _level(q: float) -> int:
    return int(round(abs(2 * q - 1) * 100))


def _forecast(model, history: Series, horizon: int, quantiles: tuple) -> np.ndarray:
    levels = sorted({_level(q) for q in quantiles if q != 0.5})
    y = np.asarray(history.values, dtype=float)
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        res = model.forecast(y=y, h=horizon, level=levels)
    cols = []
    for q in quantiles:
        if q == 0.5:
            cols.append(res["mean"])
        else:
            cols.append(res[f"{'hi' if q > 0.5 else 'lo'}-{_level(q)}"])
    return postprocess(np.column_stack(cols), history.unit)


class ETSForecaster(Forecaster):
    name = "ets"
    min_history = 48

    def fit_predict(self, history: Series, horizon: int, quantiles: tuple) -> np.ndarray:
        from statsforecast.models import AutoETS

        return _forecast(AutoETS(season_length=24), history, horizon, quantiles)


class MSTLForecaster(Forecaster):
    name = "mstl"
    min_history = 48

    def fit_predict(self, history: Series, horizon: int, quantiles: tuple) -> np.ndarray:
        from statsforecast.models import MSTL, AutoETS

        seasons = [24, 168] if len(history) >= 336 else [24]
        model = MSTL(season_length=seasons, trend_forecaster=AutoETS(model="ZZN"))
        return _forecast(model, history, horizon, quantiles)
