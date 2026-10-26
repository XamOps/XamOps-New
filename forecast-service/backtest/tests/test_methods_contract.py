import os

import numpy as np
import pytest

from backtest.config import QUANTILES
from backtest.data import synthetic
from backtest.methods import REGISTRY

SERIES = synthetic.generate(2)  # cpu + mem for 2 containers, 192 h


def _check(arr):
    assert arr.shape == (24, len(QUANTILES))
    assert np.all(np.isfinite(arr)) and np.all(arr >= 0) and np.all(arr <= 100)
    assert np.all(np.diff(arr, axis=1) >= 0)


@pytest.mark.parametrize("name", ["histogram", "prophet", "ets", "mstl"])
@pytest.mark.parametrize("length", [72, 192])
def test_contract(name, length):
    m = REGISTRY[name]()
    for s in SERIES:
        _check(m.fit_predict(s.head(length), 24, QUANTILES))


@pytest.mark.slow
@pytest.mark.skipif(os.environ.get("BACKTEST_SLOW") != "1", reason="set BACKTEST_SLOW=1 (downloads weights)")
def test_chronos_bolt_contract():
    m = REGISTRY["chronos_bolt"]()
    hists = [s.head(n) for s in SERIES for n in (24, 120)]
    outs = m.fit_predict_batch(hists, 24, QUANTILES)
    assert len(outs) == len(hists)
    for arr in outs:
        _check(arr)
        # Bolt is trained on 0.1..0.9 and clamps higher levels to its 0.9 output (see chronos_method.py).
        j9, j95, j99 = (QUANTILES.index(q) for q in (0.9, 0.95, 0.99))
        assert np.allclose(arr[:, j95], arr[:, j9]) and np.allclose(arr[:, j99], arr[:, j9])
