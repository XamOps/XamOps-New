"""Schedule-shape metrics for the hour-of-day CPU evaluation. Pure numpy/pandas.

CloudSitter constants are copied from
``xamops-service/src/main/java/com/xammer/cloud/service/CloudsitterService.java``:
  * ``CPU_IDLE_THRESHOLD = 5.0``: ``cpuActive = avgCpu >= 5.0``, where ``avgCpu`` is the
    mean of the hourly CPU values that fall in the (weekday, hour) slot over the lookback
    (default 30 days); an empty slot has ``avgCpu = 0.0`` and is therefore NOT active.
    A forecast-driven grid marks an hour active when its forecast ``p95 >= 5.0``.
  * ``MIN_CONSECUTIVE_IDLE_HOURS = 6``: idle runs shorter than 6 h are absorbed (set active).
An hour is "predicted idle" when the baseline/forecast says not active; it is "actually
idle" when the actual value is below the same 5.0 threshold.
"""
import numpy as np
import pandas as pd

CPU_IDLE_THRESHOLD = 5.0
MIN_CONSECUTIVE_IDLE_HOURS = 6


def per_hour_exceedance(y, yq, hour) -> np.ndarray:
    """Exceedance (y > yq) per hour of day 0..23; nan for an hour with no rows."""
    y, yq, hour = np.asarray(y, float), np.asarray(yq, float), np.asarray(hour, int)
    n = np.bincount(hour, minlength=24).astype(float)
    hits = np.bincount(hour, weights=(y > yq).astype(float), minlength=24)
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(n > 0, hits / n, np.nan)


def idle_confusion(actual_idle, pred_idle) -> dict:
    """Confusion counts for 'idle' as the positive class, plus precision, recall, F0.5."""
    a, p = np.asarray(actual_idle, bool), np.asarray(pred_idle, bool)
    tp, fp = int(np.sum(a & p)), int(np.sum(~a & p))
    fn, tn = int(np.sum(a & ~p)), int(np.sum(~a & ~p))
    prec = tp / (tp + fp) if tp + fp else float("nan")
    rec = tp / (tp + fn) if tp + fn else float("nan")
    b2 = 0.25  # F-beta with beta = 0.5: precision weighted 4x (a false "idle" is an outage risk)
    f05 = ((1 + b2) * prec * rec / (b2 * prec + rec)) if tp else 0.0
    return {"tp": tp, "fp": fp, "fn": fn, "tn": tn, "precision": prec, "recall": rec, "f0_5": f05,
            "pred_idle_rate": float(np.mean(p)) if len(p) else float("nan"),
            "actual_idle_rate": float(np.mean(a)) if len(a) else float("nan"),
            "false_idle_rate": fp / (fp + tn) if fp + tn else float("nan")}


def absorb_short_idle(active, min_run: int = MIN_CONSECUTIVE_IDLE_HOURS) -> np.ndarray:
    """CloudSitter's churn guard on one window: idle runs shorter than ``min_run`` become active."""
    act = np.asarray(active, bool).copy()
    n, h = len(act), 0
    while h < n:
        if act[h]:
            h += 1
            continue
        k = h
        while k < n and not act[k]:
            k += 1
        if k - h < min_run:
            act[h:k] = True
        h = k
    return act


def cpu_active_weekday_hour(hist_index, hist_values, target_index,
                            threshold: float = CPU_IDLE_THRESHOLD) -> np.ndarray:
    """CloudSitter cpuActive, as coded: slot = (weekday, hour), mean of history in the slot,
    empty slot -> 0.0 -> not active. Returns active (bool) per target timestamp."""
    hi, ti = pd.DatetimeIndex(hist_index), pd.DatetimeIndex(target_index)
    slot_h = hi.dayofweek.to_numpy() * 24 + hi.hour.to_numpy()
    s = np.bincount(slot_h, weights=np.asarray(hist_values, float), minlength=168)
    c = np.bincount(slot_h, minlength=168)
    avg = np.where(c > 0, s / np.maximum(c, 1), 0.0)
    return avg[ti.dayofweek.to_numpy() * 24 + ti.hour.to_numpy()] >= threshold


def cpu_active_hour_of_day(hist_index, hist_values, target_index,
                           threshold: float = CPU_IDLE_THRESHOLD) -> np.ndarray:
    """cpuActive collapsed to hour of day (24 slots, mean over all history days). Same
    empty-slot rule. The testable analogue of the 7x24 grid when history is < 7 days."""
    hi, ti = pd.DatetimeIndex(hist_index), pd.DatetimeIndex(target_index)
    h = hi.hour.to_numpy()
    s = np.bincount(h, weights=np.asarray(hist_values, float), minlength=24)
    c = np.bincount(h, minlength=24)
    avg = np.where(c > 0, s / np.maximum(c, 1), 0.0)
    return avg[ti.hour.to_numpy()] >= threshold


def weekday_hour_slot_counts(hist_index, target_index) -> np.ndarray:
    """History samples in each target hour's (weekday, hour) slot (testability of hour+weekday)."""
    hi, ti = pd.DatetimeIndex(hist_index), pd.DatetimeIndex(target_index)
    c = np.bincount(hi.dayofweek.to_numpy() * 24 + hi.hour.to_numpy(), minlength=168)
    return c[ti.dayofweek.to_numpy() * 24 + ti.hour.to_numpy()]


def amplitude(curve) -> float:
    """Peak-to-trough range (max - min) of one 24 h curve."""
    c = np.asarray(curve, float)
    return float(np.max(c) - np.min(c))


def pearson_or_zero(a, b) -> float:
    """Pearson correlation; 0.0 when either side is constant (a flat forecast has no shape)."""
    a, b = np.asarray(a, float), np.asarray(b, float)
    if np.std(a) == 0 or np.std(b) == 0:
        return 0.0
    return float(np.corrcoef(a, b)[0, 1])


def lag24_autocorr(values) -> float:
    """Lag-24 autocorrelation of a whole series (used only to stratify, never to forecast)."""
    v = np.asarray(values, float)
    if len(v) < 48:
        return float("nan")
    return pearson_or_zero(v[:-24], v[24:])
