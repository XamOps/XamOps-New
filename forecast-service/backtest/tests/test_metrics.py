import math

import numpy as np

from backtest import metrics


def test_exceedance_four_points():
    y = np.array([1.0, 2.0, 3.0, 4.0])
    yq = np.array([2.0, 2.0, 2.0, 5.0])  # only y=3 > 2 exceeds; y=2 == 2 does not
    assert metrics.exceedance(y, yq) == 0.25


def test_pinball_single_point_under_and_over():
    # under-forecast: y=10, yhat=8, e=2 -> max(0.9*2, -0.1*2) = 1.8
    assert math.isclose(metrics.pinball([10.0], [[8.0]], (0.9,)), 1.8)
    # over-forecast: y=10, yhat=12, e=-2 -> max(-1.8, 0.2) = 0.2
    assert math.isclose(metrics.pinball([10.0], [[12.0]], (0.9,)), 0.2)


def test_pinball_mean_over_q_and_h():
    y = [10.0, 10.0]
    Yq = [[8.0, 12.0], [10.0, 10.0]]  # h1: q0.5 under (1.0), q0.9 over (0.2); h2: exact (0, 0)
    assert math.isclose(metrics.pinball(y, Yq, (0.5, 0.9)), (1.0 + 0.2 + 0 + 0) / 4)


def test_scaled_pinball_and_zero_mean():
    assert math.isclose(metrics.scaled_pinball([10.0], [[8.0]], (0.9,)), 0.18)
    assert math.isnan(metrics.scaled_pinball([0.0, 0.0], [[1.0], [1.0]], (0.9,)))


def test_waste_ratio_of_sums():
    y = np.array([10.0, 1.0, 9.0])
    q95 = np.array([15.0, 1.0, 5.0])  # headroom 5, 0, 0 (under-forecast does not offset)
    assert math.isclose(metrics.waste_pct(y, q95), 100 * 5 / 20)


def test_severe_under_strict_boundary():
    q95 = np.array([10.0, 10.0, 10.0, 10.0])
    y = np.array([12.0, 12.0001, 11.0, 13.0])  # 12 == 1.2*10 is NOT severe
    count, rate = metrics.severe_under(y, q95, factor=1.2)
    assert count == 2
    assert rate == 0.5


def test_peak_exceed():
    assert metrics.peak_exceed([1, 5, 2], [4, 4, 4]) is True
    assert metrics.peak_exceed([1, 4, 2], [3, 4, 3]) is False
