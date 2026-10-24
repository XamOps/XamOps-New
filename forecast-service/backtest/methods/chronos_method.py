"""Chronos-Bolt-small, local CPU inference only (D5). Weights cache under .cache/hf.

Quantiles above 0.9: chronos-forecasting 2.3.2 ``ChronosBoltPipeline.predict_quantiles``
only has the trained levels 0.1..0.9. When any requested level is not a trained one, it
pads the 9 trained quantiles with copies of the first and last ([q0.1, q0.1..q0.9, q0.9])
and linearly interpolates with ``torch.quantile`` over those 11 points (position q*10).
So q=0.95 and q=0.99 both come back exactly equal to q=0.9 (clamped, not extrapolated),
and the library logs a warning. Reported raw here, not patched.
"""
import logging

import numpy as np

from ..config import SEED
from ..data.types import Series
from .base import Forecaster, postprocess

MODEL_ID = "amazon/chronos-bolt-small"


class ChronosBoltForecaster(Forecaster):
    name = "chronos_bolt"
    min_history = 1

    def __init__(self, model_id: str = MODEL_ID, num_threads: int = 8, batch_size: int = 64, seed: int = SEED):
        self.model_id = model_id
        self.num_threads = num_threads
        self.batch_size = batch_size
        self.seed = seed
        self._pipeline = None

    def pipeline(self):
        if self._pipeline is None:
            import torch
            from chronos import BaseChronosPipeline

            torch.set_num_threads(self.num_threads)
            self._pipeline = BaseChronosPipeline.from_pretrained(
                self.model_id, device_map="cpu", torch_dtype=torch.float32)
        return self._pipeline

    def fit_predict_batch(self, histories: list[Series], horizon: int, quantiles: tuple) -> list[np.ndarray]:
        import torch

        pipe = self.pipeline()
        out: list[np.ndarray] = []
        for start in range(0, len(histories), self.batch_size):
            batch = histories[start:start + self.batch_size]
            torch.manual_seed(self.seed)
            context = [torch.tensor(np.asarray(h.values, dtype=np.float32)) for h in batch]
            q, _ = pipe.predict_quantiles(inputs=context, prediction_length=horizon,
                                          quantile_levels=list(quantiles))
            arr = q.detach().cpu().numpy().astype(float)  # (batch, horizon, len(q))
            out.extend(postprocess(arr[i], h.unit) for i, h in enumerate(batch))
            # The q>0.9 clamp warning has been shown once; drop the per-batch repeats.
            from chronos import chronos_bolt
            logging.getLogger(chronos_bolt.__file__).setLevel(logging.ERROR)
        return out

    def fit_predict(self, history: Series, horizon: int, quantiles: tuple) -> np.ndarray:
        return self.fit_predict_batch([history], horizon, quantiles)[0]
