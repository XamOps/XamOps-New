"""Offline quantile-forecast backtest harness (research tooling, not production).

Never imports ``app.py`` or the production ``models`` package.
"""
from . import config  # noqa: F401  (sets HF_HOME / HF_HUB_DISABLE_TELEMETRY first)
