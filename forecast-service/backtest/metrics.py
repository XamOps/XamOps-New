"""Forecast-evaluation metrics (D10). Pure numpy.

Shapes: ``y`` is (N,) actuals; ``yq`` is (N,) one quantile forecast; ``Yq`` is (N, len(qs)).
"""
import numpy as np


def exceedance(y, yq) -> float:
    """Share of hours where the actual exceeds the forecast quantile (target ~= 1 - q)."""
    y, yq = np.asarray(y, float), np.asarray(yq, float)
    return float(np.mean(y > yq))


def pinball(y, Yq, qs) -> float:
    """Mean over q and h of max(q*e, (q-1)*e), e = y - yhat_q."""
    y = np.asarray(y, float)
    Yq = np.asarray(Yq, float).reshape(len(y), len(qs))
    q = np.asarray(qs, float)[None, :]
    e = y[:, None] - Yq
    return float(np.mean(np.maximum(q * e, (q - 1) * e)))


def scaled_pinball(y, Yq, qs) -> float:
    """Pinball divided by the mean actual (nan when the mean is 0)."""
    m = float(np.mean(np.asarray(y, float)))
    return float("nan") if m == 0 else pinball(y, Yq, qs) / m


def waste_pct(y, yq95) -> float:
    """100 * sum(max(yq95 - y, 0)) / sum(y): over-provisioned headroom, ratio of sums."""
    y, yq95 = np.asarray(y, float), np.asarray(yq95, float)
    total = float(np.sum(y))
    return float("nan") if total == 0 else 100.0 * float(np.sum(np.maximum(yq95 - y, 0))) / total


def severe_under(y, yq95, factor: float = 1.2) -> tuple[int, float]:
    """(count, rate) of hours with y strictly greater than factor * yq95."""
    y, yq95 = np.asarray(y, float), np.asarray(yq95, float)
    hit = y > factor * yq95
    return int(np.sum(hit)), float(np.mean(hit)) if len(y) else float("nan")


def peak_exceed(y, yq95) -> bool:
    """Next-horizon peak check: actual peak above the predicted peak (max over h)."""
    return bool(np.max(np.asarray(y, float)) > np.max(np.asarray(yq95, float)))
