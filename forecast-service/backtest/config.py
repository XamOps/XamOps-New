"""Shared constants and paths for the offline backtest harness.

Importing this module (which ``backtest/__init__.py`` does) sets the Hugging Face
environment defaults before any HF/transformers import, so model weights are cached
inside the package and telemetry is off.
"""
import os
from pathlib import Path

PKG_DIR = Path(__file__).resolve().parent
CACHE_DIR = PKG_DIR / ".cache"
OUT_DIR = PKG_DIR / "out"

os.environ.setdefault("HF_HOME", str(CACHE_DIR / "hf"))
os.environ.setdefault("HF_HUB_DISABLE_TELEMETRY", "1")

QUANTILES = (0.5, 0.8, 0.9, 0.95, 0.99)
HORIZON = 24
SEED = 20261024
