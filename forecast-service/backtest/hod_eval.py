"""Hour-of-day CPU histogram evaluation: standard D10/D11 metrics plus schedule shape.

Run from ``forecast-service/`` with the harness venv::

    backtest/.venv/bin/python -m backtest.hod_eval --corpus alibaba --n-series 300
    backtest/.venv/bin/python -m backtest.hod_eval --corpus xamops-live --n-series 300

Inputs are the SAME cached samples and loaders as ``backtest.run`` (Alibaba
``.cache/alibaba/sample_seed<seed>.csv.gz``, live ``.cache/xamops-live/sample.csv.gz``),
the same seed, origins (``engine.origins_for``) and horizon. Only CPU series are run.
Outputs go to ``backtest/out/<corpus>-hod/``: rows.csv.gz, timings.csv.gz, summary.json,
tables.md (via ``report.write_outputs``) and shape.json / shape.md (this module).
"""
import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np
import pandas as pd

from . import config  # noqa: F401  (HF env defaults)
from . import shape_metrics as sm
from .config import HORIZON, OUT_DIR, QUANTILES, SEED
from .engine import run_backtest, to_frame
from .methods import HOD_METHODS, REGISTRY
from .report import _clean, write_outputs
from .run import load_corpus

DEFAULT_METHODS = ",".join(("histogram", "ets") + HOD_METHODS)
BASELINES = ("cpuactive_wh", "cpuactive_hod")
DIURNAL_AC = 0.3  # lag-24 autocorrelation threshold for the "diurnal" stratum


def parse_args(argv=None) -> argparse.Namespace:
    p = argparse.ArgumentParser(prog="python -m backtest.hod_eval")
    p.add_argument("--corpus", choices=["alibaba", "synthetic", "xamops-live"], required=True)
    p.add_argument("--n-series", type=int, default=300)
    p.add_argument("--methods", default=DEFAULT_METHODS)
    p.add_argument("--agg", choices=["max", "mean"], default="max")
    p.add_argument("--workers", type=int, default=7)
    p.add_argument("--seed", type=int, default=SEED)
    p.add_argument("--out", type=Path, default=None)
    p.add_argument("--max-download-mb", type=float, default=2048)
    p.add_argument("--cache-target", type=int, default=500)
    return p.parse_args(argv)


def _forecast_frame(df: pd.DataFrame, series: dict) -> pd.DataFrame:
    """Add the target timestamp ``ts`` (series.index[origin + h - 1]) and its hour of day."""
    ts = np.empty(len(df), dtype="datetime64[ns]")
    for sid, g in df.groupby("series_id", sort=False):
        idx = pd.DatetimeIndex(series[sid].index).tz_convert(None).to_numpy()
        ts[g.index.to_numpy()] = idx[(g["origin"] + g["h"] - 1).to_numpy()]
    out = df.copy()
    out["ts"] = pd.DatetimeIndex(ts, tz="UTC")
    out["hour"] = out["ts"].dt.hour
    return out


def _baselines(series: dict, forecasts: pd.DataFrame) -> dict:
    """Per (series_id, origin): cpuActive grids from history only, plus weekday-slot counts."""
    out = {}
    for (sid, origin), g in forecasts.groupby(["series_id", "origin"], sort=True):
        s = series[sid]
        hist_idx, hist_v = s.index[:origin], s.values[:origin]
        tgt = pd.DatetimeIndex(g.sort_values("h")["ts"])
        out[(sid, origin)] = {
            "cpuactive_wh": sm.cpu_active_weekday_hour(hist_idx, hist_v, tgt),
            "cpuactive_hod": sm.cpu_active_hour_of_day(hist_idx, hist_v, tgt),
            "wh_slot_counts": sm.weekday_hour_slot_counts(hist_idx, tgt),
        }
    return out


def _idle_stats(per_fc: list) -> dict:
    """per_fc: list of (actual_idle(24,), pred_active(24,)). Raw and with 6 h idle absorption."""
    a = np.concatenate([x[0] for x in per_fc])
    raw = np.concatenate([~x[1] for x in per_fc])
    absorbed = np.concatenate([~sm.absorb_short_idle(x[1]) for x in per_fc])
    return {"raw": sm.idle_confusion(a, raw), "absorbed_6h": sm.idle_confusion(a, absorbed)}


def shape(df: pd.DataFrame, series: dict, methods: list) -> dict:
    normal = df[df["bucket"] == "normal"]
    base = _baselines(series, normal[normal["method"] == methods[0]])
    ac = {sid: sm.lag24_autocorr(s.values) for sid, s in series.items()}
    diurnal = {sid for sid, v in ac.items() if np.isfinite(v) and v >= DIURNAL_AC}
    res = {"threshold": sm.CPU_IDLE_THRESHOLD, "min_idle_run_h": sm.MIN_CONSECUTIVE_IDLE_HOURS,
           "diurnal_ac": DIURNAL_AC, "n_series": len(series), "n_diurnal_series": len(diurnal),
           "methods": {}, "baselines": {}}
    counts = np.concatenate([b["wh_slot_counts"] for b in base.values()]) if base else np.array([])
    res["weekday_hour_testability"] = {
        "target_hours": int(len(counts)),
        "slot_ge1": int(np.sum(counts >= 1)), "slot_ge2": int(np.sum(counts >= 2)),
        "max_slot_samples": int(counts.max()) if len(counts) else 0,
        "series_length_max": int(max(len(s) for s in series.values())),
    }
    for stratum, keep in (("all", None), ("diurnal", diurnal)):
        sub = normal if keep is None else normal[normal["series_id"].isin(keep)]
        for m in methods:
            g = sub[sub["method"] == m]
            if g.empty:
                continue
            prof = sm.per_hour_exceedance(g["y"], g["q_0.95"], g["hour"])
            per_fc_idle, amps, corr50, corr95 = [], [], [], []
            for (sid, origin), f in g.groupby(["series_id", "origin"], sort=True):
                f = f.sort_values("h")
                y, q50, q95 = f["y"].to_numpy(), f["q_0.5"].to_numpy(), f["q_0.95"].to_numpy()
                per_fc_idle.append((y < sm.CPU_IDLE_THRESHOLD, q95 >= sm.CPU_IDLE_THRESHOLD))
                amps.append((sm.amplitude(y), sm.amplitude(q50), sm.amplitude(q95)))
                corr50.append(sm.pearson_or_zero(q50, y))
                corr95.append(sm.pearson_or_zero(q95, y))
            amps = np.array(amps)
            ok = amps[:, 0] > 0
            # Series-level hour-of-day profile amplitude (mean by hour over normal target hours).
            prof_ratio = []
            for sid, sg in g.groupby("series_id"):
                ay = sg.groupby("hour")["y"].mean()
                ap = sg.groupby("hour")["q_0.5"].mean()
                if len(ay) == 24 and sm.amplitude(ay) > 0:
                    prof_ratio.append(sm.amplitude(ap) / sm.amplitude(ay))
            res["methods"].setdefault(m, {})[stratum] = {
                "n_forecasts": len(per_fc_idle),
                "per_hour_exceedance_95": [None if np.isnan(v) else float(v) for v in prof],
                "per_hour_exc_max": float(np.nanmax(prof)), "per_hour_exc_min": float(np.nanmin(prof)),
                "hours_over_10pct": int(np.sum(prof > 0.10)),
                "idle": _idle_stats(per_fc_idle),
                "amp_ratio_q50_median": float(np.median(amps[ok, 1] / amps[ok, 0])) if ok.any() else None,
                "amp_ratio_q95_median": float(np.median(amps[ok, 2] / amps[ok, 0])) if ok.any() else None,
                "share_forecasts_flat": float(np.mean(amps[:, 1] == 0)),
                "corr_q50_y_mean": float(np.mean(corr50)), "corr_q95_y_mean": float(np.mean(corr95)),
                "profile_amp_ratio_median": float(np.median(prof_ratio)) if prof_ratio else None,
                "profile_amp_series": len(prof_ratio),
            }
        # cpuActive baselines on the same forecast hours (history-only grids).
        ref = sub[sub["method"] == methods[0]]
        for b in BASELINES:
            per_fc = []
            for (sid, origin), f in ref.groupby(["series_id", "origin"], sort=True):
                y = f.sort_values("h")["y"].to_numpy()
                per_fc.append((y < sm.CPU_IDLE_THRESHOLD, base[(sid, origin)][b]))
            if per_fc:
                res["baselines"].setdefault(b, {})[stratum] = {"n_forecasts": len(per_fc),
                                                               "idle": _idle_stats(per_fc)}
    return res


def _p(v) -> str:
    return "n/a" if v is None or (isinstance(v, float) and np.isnan(v)) else f"{100 * v:.1f}%"


def _f(v, fmt=".2f") -> str:
    return "n/a" if v is None or (isinstance(v, float) and np.isnan(v)) else format(v, fmt)


def shape_markdown(res: dict, corpus: str) -> str:
    L = [f"# Schedule-shape tables: {corpus} (CPU, normal origins)", "",
         f"Idle threshold {res['threshold']} (CloudSitter CPU_IDLE_THRESHOLD); absorption = idle runs "
         f"< {res['min_idle_run_h']} h set active within the 24 h window. Diurnal stratum: lag-24 "
         f"autocorrelation >= {res['diurnal_ac']} ({res['n_diurnal_series']} of {res['n_series']} series).", ""]
    for stratum in ("all", "diurnal"):
        L += [f"## Idle-hour detection ({stratum})", "",
              "| predictor | forecasts | actual idle | pred idle | precision | recall | F0.5 | false-idle rate "
              "| abs. precision | abs. recall | abs. F0.5 |", "|" + "---|" * 11]
        rows = [(m, d[stratum]) for m, d in res["methods"].items() if stratum in d]
        rows += [(b, d[stratum]) for b, d in res["baselines"].items() if stratum in d]
        for name, d in rows:
            r, a = d["idle"]["raw"], d["idle"]["absorbed_6h"]
            L.append(f"| {name} | {d['n_forecasts']} | {_p(r['actual_idle_rate'])} | {_p(r['pred_idle_rate'])} | "
                     f"{_p(r['precision'])} | {_p(r['recall'])} | {_f(r['f0_5'], '.3f')} | {_p(r['false_idle_rate'])} | "
                     f"{_p(a['precision'])} | {_p(a['recall'])} | {_f(a['f0_5'], '.3f')} |")
        L += ["", f"## Diurnal amplitude ({stratum})", "",
              "| method | median amp ratio q50/y | median amp ratio q95/y | flat forecasts | mean corr(q50,y) "
              "| mean corr(q95,y) | median profile amp ratio (series) |", "|" + "---|" * 7]
        for m, d in res["methods"].items():
            if stratum in d:
                x = d[stratum]
                L.append(f"| {m} | {_f(x['amp_ratio_q50_median'])} | {_f(x['amp_ratio_q95_median'])} | "
                         f"{_p(x['share_forecasts_flat'])} | {_f(x['corr_q50_y_mean'], '.3f')} | "
                         f"{_f(x['corr_q95_y_mean'], '.3f')} | {_f(x['profile_amp_ratio_median'])} "
                         f"(n={x['profile_amp_series']}) |")
        L += ["", f"## Per-hour P95 exceedance profile ({stratum})", "",
              "| hour | " + " | ".join(res["methods"]) + " |", "|" + "---|" * (len(res["methods"]) + 1)]
        for h in range(24):
            L.append(f"| {h:02d} | " + " | ".join(
                _p(d[stratum]["per_hour_exceedance_95"][h]) if stratum in d else "n/a"
                for d in res["methods"].values()) + " |")
        L.append("| max | " + " | ".join(_p(d[stratum]["per_hour_exc_max"]) for d in res["methods"].values()
                                        if stratum in d) + " |")
        L.append("| hours >10% | " + " | ".join(str(d[stratum]["hours_over_10pct"])
                                               for d in res["methods"].values() if stratum in d) + " |")
        L.append("")
    t = res["weekday_hour_testability"]
    L += ["## Hour + weekday testability", "",
          f"Normal-origin target hours: {t['target_hours']}; with >=1 history sample in their (weekday, hour) "
          f"slot: {t['slot_ge1']}; with >=2: {t['slot_ge2']}; max samples in a slot: {t['max_slot_samples']}; "
          f"longest series: {t['series_length_max']} h.", ""]
    return "\n".join(L)


def main(argv=None) -> int:
    args = parse_args(argv)
    methods = [m.strip() for m in args.methods.split(",") if m.strip()]
    unknown = [m for m in methods if m not in REGISTRY]
    if unknown:
        print(f"unknown methods: {unknown}", file=sys.stderr)
        return 2
    t0 = time.perf_counter()
    cpu = [s for s in load_corpus(args) if s.resource == "cpu"]
    series = {s.id: s for s in cpu}
    print(f"[hod_eval] {args.corpus}: {len(cpu)} cpu series; methods={methods}", file=sys.stderr, flush=True)
    df = to_frame(run_backtest(cpu, methods, HORIZON, QUANTILES, args.workers, args.seed), QUANTILES)
    out_dir = args.out or OUT_DIR / f"{args.corpus}-hod"
    meta = {"corpus": args.corpus, "n_series_requested": args.n_series, "n_containers": len(series),
            "n_series": len(cpu), "agg": args.agg if args.corpus == "alibaba" else "n/a", "seed": args.seed,
            "horizon": HORIZON, "quantiles": list(QUANTILES), "methods": methods, "resource": "cpu only",
            "origins": "normal: t=48,72,... while t+24<=len; short: t in {24,36}; expanding window",
            "series_lengths": sorted({len(s) for s in cpu})}
    summary = write_outputs(df, out_dir, meta, None, QUANTILES)
    res = shape(_forecast_frame(df, series), series, methods)
    (out_dir / "shape.json").write_text(json.dumps(_clean(res), indent=2, sort_keys=True))
    (out_dir / "shape.md").write_text(shape_markdown(res, args.corpus))
    print(f"[hod_eval] wrote {out_dir} in {time.perf_counter() - t0:.1f}s; winners "
          f"{ {r: w['method'] for r, w in summary['winners'].items()} }", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
