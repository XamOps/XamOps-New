import gzip
import json

import pandas as pd

from backtest.config import QUANTILES
from backtest.data import synthetic
from backtest.engine import origins_for, run_backtest, to_frame
from backtest.report import write_outputs


def _run(out_dir, workers):
    series = synthetic.generate(3)
    df = to_frame(run_backtest(series, ["histogram", "prophet"], 24, QUANTILES, workers=workers, seed=1), QUANTILES)
    write_outputs(df, out_dir, {"corpus": "test", "methods": ["histogram", "prophet", "prophet_asis"]})
    return df


def test_origins():
    assert origins_for(192) == [(24, "short"), (36, "short"), (48, "normal"), (72, "normal"), (96, "normal"),
                                (120, "normal"), (144, "normal"), (168, "normal")]
    assert origins_for(60) == [(24, "short"), (36, "short")]


def test_engine_rows_fallback_and_determinism(tmp_path):
    df = _run(tmp_path / "a", workers=2)
    # 3 containers x 2 resources x 8 origins x 3 method rows x 24 h
    assert len(df) == 3 * 2 * 8 * 3 * 24
    per_fc = df[df["h"] == 1]
    assert set(per_fc["method"]) == {"histogram", "prophet", "prophet_asis"}
    assert (per_fc.groupby(["series_id", "resource", "method"]).size() == 8).all()
    assert set(per_fc["bucket"]) == {"normal", "short"}

    prophet = per_fc[per_fc["method"].isin(["prophet", "prophet_asis"])]
    short = prophet[prophet["origin"].isin([24, 36])]
    assert short["fallback"].all() and short["reason"].str.contains("min_history").all()
    assert not prophet[prophet["origin"] >= 48]["fallback"].any()
    # fallback rows carry the histogram forecast
    hist = per_fc[(per_fc["method"] == "histogram") & per_fc["origin"].isin([24, 36])]
    qc = [f"q_{q}" for q in QUANTILES]
    pd.testing.assert_frame_equal(
        short[short["method"] == "prophet"][qc].reset_index(drop=True), hist[qc].reset_index(drop=True))

    summary = json.loads((tmp_path / "a" / "summary.json").read_text())
    assert {(g["method"], g["resource"], g["bucket"]) for g in summary["groups"]} == {
        (m, r, b) for m in ("histogram", "prophet", "prophet_asis") for r in ("cpu", "mem")
        for b in ("normal", "short")}
    assert set(summary["winners"]) == {"cpu", "mem"}

    _run(tmp_path / "b", workers=1)  # serial path must give the same rows as the pool
    a = (tmp_path / "a" / "rows.csv.gz").read_bytes()
    b = (tmp_path / "b" / "rows.csv.gz").read_bytes()
    assert a == b
    with gzip.open(tmp_path / "a" / "rows.csv.gz", "rt") as f:
        assert f.readline().strip().split(",") == [
            "series_id", "resource", "origin", "bucket", "method", "h", "y", *qc, "fallback", "reason"]
