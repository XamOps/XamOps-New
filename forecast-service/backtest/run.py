"""Offline quantile-forecast backtest CLI.

Run from ``forecast-service/`` with the harness venv (CPython 3.11)::

    # one-time setup
    ~/.local/bin/uv venv --python 3.11 backtest/.venv
    ~/.local/bin/uv pip install --python backtest/.venv/bin/python -r backtest/requirements-backtest.txt

    # offline smoke run on the deterministic synthetic corpus
    backtest/.venv/bin/python -m backtest.run --corpus synthetic --n-series 10

    # real run: streams the public Alibaba v2018 trace once (inbound only, stops at
    # --cache-target kept containers or --max-download-mb), then reuses the cache
    backtest/.venv/bin/python -m backtest.run --corpus alibaba --n-series 300

    # tests (add BACKTEST_SLOW=1 ... -m slow for the Chronos weights test)
    backtest/.venv/bin/python -m pytest backtest/tests -q

Outputs go to ``--out`` (default ``backtest/out/<corpus>``): rows.csv.gz (deterministic),
timings.csv.gz, summary.json, and tables.md (or ``--report-md``). Progress goes to stderr.
Nothing is sent to any hosted API; Chronos weights are cached under backtest/.cache/hf.
Delete ``backtest/.cache/alibaba/sample_seed<seed>.csv.gz`` to re-sample the trace.
"""
import argparse
import json
import platform
import sys
import time
from pathlib import Path

from . import config  # noqa: F401  (HF env defaults before any HF import)
from .config import HORIZON, OUT_DIR, QUANTILES, SEED
from .engine import LOAD_SECONDS, run_backtest, to_frame, view_names
from .methods import REGISTRY
from .report import write_outputs

DEFAULT_METHODS = "histogram,prophet,ets,mstl,chronos_bolt"
KEY_PINS = ("prophet", "cmdstanpy", "pandas", "numpy", "statsforecast", "chronos-forecasting", "torch",
            "transformers", "pyarrow")


def _pins() -> dict:
    path = Path(config.PKG_DIR) / "requirements-backtest.txt"
    pins = {}
    if path.exists():
        for line in path.read_text().splitlines():
            name, sep, ver = line.strip().partition("==")
            if sep and name in KEY_PINS:
                pins[name] = ver
    return pins


def parse_args(argv=None) -> argparse.Namespace:
    p = argparse.ArgumentParser(prog="python -m backtest.run", description=__doc__.split("\n\n")[0])
    p.add_argument("--corpus", choices=["alibaba", "synthetic"], required=True)
    p.add_argument("--n-series", type=int, default=300, help="containers (each gives a cpu and a mem series)")
    p.add_argument("--methods", default=DEFAULT_METHODS, help=f"comma list from {','.join(REGISTRY)}")
    p.add_argument("--agg", choices=["max", "mean"], default="max", help="hourly aggregation (alibaba)")
    p.add_argument("--workers", type=int, default=7)
    p.add_argument("--seed", type=int, default=SEED)
    p.add_argument("--out", type=Path, default=None, help="output dir (default backtest/out/<corpus>)")
    p.add_argument("--max-download-mb", type=float, default=2048)
    p.add_argument("--cache-target", type=int, default=500)
    p.add_argument("--report-md", type=Path, default=None, help="markdown tables path (default <out>/tables.md)")
    return p.parse_args(argv)


def load_corpus(args) -> list:
    if args.corpus == "synthetic":
        from .data import synthetic
        return synthetic.generate(args.n_series, args.seed)
    from .data import alibaba
    return alibaba.load(args.n_series, args.agg, args.seed, args.cache_target, args.max_download_mb)


def main(argv=None) -> int:
    args = parse_args(argv)
    methods = [m.strip() for m in args.methods.split(",") if m.strip()]
    unknown = [m for m in methods if m not in REGISTRY]
    if unknown:
        print(f"unknown methods: {unknown}; known: {list(REGISTRY)}", file=sys.stderr)
        return 2
    out_dir = args.out or OUT_DIR / args.corpus
    t0 = time.perf_counter()
    series = load_corpus(args)
    n_containers = len({s.id for s in series})
    print(f"[run] {args.corpus}: {n_containers} containers, {len(series)} series; methods={methods}",
          file=sys.stderr, flush=True)
    if not series:
        print("[run] no series loaded", file=sys.stderr)
        return 1
    forecasts = run_backtest(series, methods, HORIZON, QUANTILES, args.workers, args.seed)
    df = to_frame(forecasts, QUANTILES)
    meta = {
        "corpus": args.corpus, "n_series_requested": args.n_series, "n_containers": n_containers,
        "n_series": len(series), "agg": args.agg if args.corpus == "alibaba" else "n/a",
        "seed": args.seed, "horizon": HORIZON, "quantiles": list(QUANTILES), "workers": args.workers,
        "methods": [v for m in methods for v in view_names(m)],
        "origins": "normal: t=48,72,... while t+24<=len; short: t in {24,36}; expanding window",
        "load_seconds": dict(LOAD_SECONDS), "python": platform.python_version(), "pins": _pins(),
        "series_lengths": sorted({len(s) for s in series}),
    }
    if args.corpus == "alibaba":
        from .data import alibaba
        meta_path = alibaba.cache_path(args.seed).with_suffix("").with_suffix(".meta.json")
        if meta_path.exists():
            meta["data"] = json.loads(meta_path.read_text())
    meta["wall_seconds"] = round(time.perf_counter() - t0, 1)
    summary = write_outputs(df, out_dir, meta, args.report_md, QUANTILES)
    print(f"[run] wrote {out_dir} in {meta['wall_seconds']}s; winners: "
          f"{ {r: w['method'] for r, w in summary['winners'].items()} }", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
