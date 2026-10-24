import numpy as np
import pandas as pd
import pytest

from backtest import shape_metrics as sm


def test_cloudsitter_constants():
    # Copied from CloudsitterService.CPU_IDLE_THRESHOLD / MIN_CONSECUTIVE_IDLE_HOURS.
    assert sm.CPU_IDLE_THRESHOLD == 5.0 and sm.MIN_CONSECUTIVE_IDLE_HOURS == 6


def test_per_hour_exceedance():
    hour = np.array([0, 0, 1, 1, 1])
    prof = sm.per_hour_exceedance([2, 0, 5, 5, 0], [1, 1, 1, 1, 1], hour)
    assert prof[0] == pytest.approx(0.5) and prof[1] == pytest.approx(2 / 3)
    assert np.isnan(prof[2]) and len(prof) == 24


def test_idle_confusion_and_f05():
    actual = np.array([1, 1, 1, 0, 0, 0], bool)
    pred = np.array([1, 1, 0, 1, 0, 0], bool)
    c = sm.idle_confusion(actual, pred)
    assert (c["tp"], c["fp"], c["fn"], c["tn"]) == (2, 1, 1, 2)
    assert c["precision"] == pytest.approx(2 / 3) and c["recall"] == pytest.approx(2 / 3)
    assert c["f0_5"] == pytest.approx(2 / 3)
    assert c["false_idle_rate"] == pytest.approx(1 / 3)
    none = sm.idle_confusion(actual, np.zeros(6, bool))
    assert np.isnan(none["precision"]) and none["recall"] == 0 and none["f0_5"] == 0


def test_absorb_short_idle_matches_cloudsitter_rule():
    act = np.ones(24, bool)
    act[2:5] = False      # 3 h idle -> absorbed
    act[10:16] = False    # 6 h idle -> kept
    out = sm.absorb_short_idle(act)
    assert out[2:5].all() and not out[10:16].any() and out.sum() == 18


def test_cpu_active_weekday_hour_empty_slot_is_idle():
    idx = pd.date_range("2018-01-01T00:00:00Z", periods=48, freq="h", tz="UTC")  # Mon+Tue
    vals = np.full(48, 50.0)
    tgt = pd.date_range("2018-01-03T00:00:00Z", periods=24, freq="h", tz="UTC")  # Wed: unseen slots
    assert not sm.cpu_active_weekday_hour(idx, vals, tgt).any()
    assert sm.cpu_active_hour_of_day(idx, vals, tgt).all()
    assert sm.weekday_hour_slot_counts(idx, tgt).max() == 0
    tgt2 = pd.date_range("2018-01-08T00:00:00Z", periods=24, freq="h", tz="UTC")  # next Mon
    assert sm.cpu_active_weekday_hour(idx, vals, tgt2).all()
    assert (sm.weekday_hour_slot_counts(idx, tgt2) == 1).all()


def test_cpu_active_threshold_is_inclusive_mean():
    idx = pd.date_range("2018-01-01T00:00:00Z", periods=48, freq="h", tz="UTC")
    vals = np.where(np.arange(48) < 24, 4.0, 6.0)  # mean per hour = 5.0 exactly
    tgt = pd.date_range("2018-01-03T00:00:00Z", periods=24, freq="h", tz="UTC")
    assert sm.cpu_active_hour_of_day(idx, vals, tgt).all()


def test_amplitude_and_correlation():
    assert sm.amplitude([1, 5, 3]) == 4
    assert sm.pearson_or_zero([1, 1, 1], [1, 2, 3]) == 0.0
    assert sm.pearson_or_zero([1, 2, 3], [2, 4, 6]) == pytest.approx(1.0)
    v = np.tile(np.r_[np.zeros(12), np.ones(12)], 4)
    assert sm.lag24_autocorr(v) == pytest.approx(1.0)
