"""Aggregation, D11 winner rule and output files.

Outputs in ``out_dir``:
  * ``rows.csv.gz``    - every (series, resource, origin, method, h) row WITHOUT the timing
                         column, gzip mtime=0, so identical runs give identical bytes.
  * ``timings.csv.gz`` - one row per forecast with fit+predict ``seconds`` (non-deterministic).
  * ``summary.json``   - run metadata, per-(method, resource, bucket) metrics, winners.
  * markdown tables at ``report_md`` (default ``out_dir/tables.md``).
"""
import gzip
import io
import json
import math
from pathlib import Path
from typing import Optional

import numpy as np
import pandas as pd

from . import metrics
from .config import QUANTILES

HEADLINE_Q = 0.95
MAX_EXCEEDANCE = 0.07
MAX_SEVERE_RATE = 0.01


def _qcols(quantiles) -> list[str]:
    return [f"q_{q}" for q in quantiles]


def aggregate(df: pd.DataFrame, quantiles: tuple = QUANTILES) -> list[dict]:
    """Metrics per (method, resource, bucket), pooled over series x origins x hours."""
    qcols = _qcols(quantiles)
    q95 = f"q_{HEADLINE_Q}"
    out = []
    for (method, resource, bucket), g in df.groupby(["method", "resource", "bucket"], sort=True):
        y = g["y"].to_numpy(float)
        Q = g[qcols].to_numpy(float)
        severe_n, severe_rate = metrics.severe_under(y, g[q95].to_numpy(float))
        per_series = list(g.groupby("series_id", sort=True))
        fc = g[g["h"] == 1]  # one row per forecast (series, origin)
        # Next-horizon peak: max_h y vs max_h q95 per forecast (same as metrics.peak_exceed, vectorised).
        peaks = g.groupby(["series_id", "origin"])[["y", q95]].max()
        sec_series = fc.groupby("series_id")["seconds"].sum()
        out.append({
            "method": method, "resource": resource, "bucket": bucket,
            "n_series": len(per_series), "n_forecasts": int(len(fc)), "n_hours": int(len(g)),
            "exceedance": {str(q): metrics.exceedance(y, Q[:, j]) for j, q in enumerate(quantiles)},
            "median_series_exceedance_95": float(np.median(
                [metrics.exceedance(s["y"], s[q95]) for _, s in per_series])),
            "pinball": metrics.pinball(y, Q, quantiles),
            "scaled_pinball": float(np.nanmean(
                [metrics.scaled_pinball(s["y"], s[qcols], quantiles) for _, s in per_series])),
            "waste_pct": metrics.waste_pct(y, g[q95].to_numpy(float)),
            "severe_under_count": severe_n, "severe_under_rate": severe_rate,
            "peak_exceed_rate": float(np.mean(peaks["y"].to_numpy() > peaks[q95].to_numpy())),
            "seconds_per_series_median": float(np.median(sec_series)),
            "seconds_per_series_p95": float(np.percentile(sec_series, 95)),
            "seconds_per_fit_median": float(np.median(fc["seconds"])),
            "seconds_per_fit_p95": float(np.percentile(fc["seconds"], 95)),
            "fallback_count": int(fc["fallback"].sum()),
        })
    return out


def winners(groups: list[dict]) -> dict:
    """D11 per resource on the normal bucket: among methods with P95 exceedance <= 7 % and
    severe-under rate <= 1 %, lowest waste (ties: scaled pinball). If none qualify, the
    lowest exceedance wins and ``qualified`` is False."""
    out = {}
    for resource in sorted({g["resource"] for g in groups}):
        cands = [g for g in groups if g["resource"] == resource and g["bucket"] == "normal"]
        if not cands:
            continue
        ok = [g for g in cands if g["exceedance"][str(HEADLINE_Q)] <= MAX_EXCEEDANCE
              and g["severe_under_rate"] <= MAX_SEVERE_RATE]
        if ok:
            best = min(ok, key=lambda g: (g["waste_pct"], _nan_last(g["scaled_pinball"]), g["method"]))
            rule = "lowest waste among qualifying methods (P95 exceedance <= 7%, severe-under <= 1%)"
        else:
            best = min(cands, key=lambda g: (g["exceedance"][str(HEADLINE_Q)], g["method"]))
            rule = "NO method qualified; lowest P95 exceedance"
        out[resource] = {"method": best["method"], "qualified": bool(ok), "rule": rule,
                         "qualifying_methods": sorted(g["method"] for g in ok),
                         "exceedance_95": best["exceedance"][str(HEADLINE_Q)],
                         "waste_pct": best["waste_pct"], "severe_under_rate": best["severe_under_rate"]}
    return out


def _nan_last(v: float) -> float:
    return math.inf if v is None or (isinstance(v, float) and math.isnan(v)) else v


def _pct(v) -> str:
    return "n/a" if v is None or (isinstance(v, float) and math.isnan(v)) else f"{100 * v:.1f}%"


def _num(v, fmt=".3f") -> str:
    return "n/a" if v is None or (isinstance(v, float) and math.isnan(v)) else format(v, fmt)


def markdown(groups: list[dict], win: dict, meta: dict, quantiles: tuple = QUANTILES) -> str:
    lines = [f"# Backtest tables: {meta.get('corpus')}", "",
             f"Series: {meta.get('n_containers')} containers x cpu/mem, agg={meta.get('agg')}, "
             f"horizon={meta.get('horizon')}h, seed={meta.get('seed')}, methods={', '.join(meta.get('methods', []))}.",
             "Exceedance@q = share of hours with actual > forecast q (target 1-q). Waste = "
             "100*sum(max(q95-y,0))/sum(y). Severe = hours with y > 1.2*q95. Peak = share of forecasts "
             "whose actual 24h peak exceeds the predicted 24h q95 peak.", ""]
    for bucket in ("normal", "short"):
        for resource in ("cpu", "mem"):
            rows = [g for g in groups if g["bucket"] == bucket and g["resource"] == resource]
            if not rows:
                continue
            lines += [f"## {resource} / {bucket} origins", "",
                      "| method | forecasts | " + " | ".join(f"exc@{q}" for q in quantiles)
                      + " | median series exc@0.95 | pinball | scaled pinball | waste % | severe n (rate) "
                        "| peak exc | s/series med | s/series p95 | fallbacks |",
                      "|" + "---|" * (len(quantiles) + 11)]
            for g in rows:
                lines.append(
                    f"| {g['method']} | {g['n_forecasts']} | "
                    + " | ".join(_pct(g["exceedance"][str(q)]) for q in quantiles)
                    + f" | {_pct(g['median_series_exceedance_95'])} | {_num(g['pinball'])} | "
                      f"{_num(g['scaled_pinball'])} | {_num(g['waste_pct'], '.1f')} | "
                      f"{g['severe_under_count']} ({_pct(g['severe_under_rate'])}) | {_pct(g['peak_exceed_rate'])} | "
                      f"{_num(g['seconds_per_series_median'])} | {_num(g['seconds_per_series_p95'])} | "
                      f"{g['fallback_count']} |")
            lines.append("")
    lines += ["## Winner per resource (D11, normal origins)", ""]
    for resource, w in win.items():
        lines.append(f"- {resource}: **{w['method']}** ({w['rule']}; exc@0.95={_pct(w['exceedance_95'])}, "
                     f"waste={_num(w['waste_pct'], '.1f')}%, severe={_pct(w['severe_under_rate'])}; "
                     f"qualifying: {', '.join(w['qualifying_methods']) or 'none'})")
    if meta.get("load_seconds"):
        lines += ["", f"One-off model load seconds (not in per-fit timings): {meta['load_seconds']}"]
    return "\n".join(lines) + "\n"


def _clean(o):
    if isinstance(o, dict):
        return {k: _clean(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)):
        return [_clean(v) for v in o]
    if isinstance(o, (np.integer,)):
        return int(o)
    if isinstance(o, (float, np.floating)):
        return None if math.isnan(o) else float(o)
    return o


def _write_csv_gz(df: pd.DataFrame, path: Path) -> None:
    buf = io.StringIO()
    df.to_csv(buf, index=False, float_format="%.6f", lineterminator="\n")
    with open(path, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0) as gz:
        gz.write(buf.getvalue().encode("utf-8"))


def write_outputs(df: pd.DataFrame, out_dir: Path, meta: dict, report_md: Optional[Path] = None,
                  quantiles: tuple = QUANTILES) -> dict:
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    _write_csv_gz(df.drop(columns=["seconds"]), out_dir / "rows.csv.gz")
    _write_csv_gz(df[df["h"] == 1][["series_id", "resource", "origin", "bucket", "method", "fallback", "seconds"]],
                  out_dir / "timings.csv.gz")
    groups = aggregate(df, quantiles)
    win = winners(groups)
    summary = _clean({**meta, "groups": groups, "winners": win})
    (out_dir / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True))
    report_md = Path(report_md) if report_md else out_dir / "tables.md"
    report_md.parent.mkdir(parents=True, exist_ok=True)
    report_md.write_text(markdown(groups, win, meta, quantiles))
    return summary
