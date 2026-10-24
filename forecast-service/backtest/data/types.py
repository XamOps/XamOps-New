from dataclasses import dataclass, replace
from typing import Literal

import numpy as np
import pandas as pd


@dataclass
class Series:
    """One hourly, gap-free usage series (UTC index, one value per hour)."""

    id: str
    resource: Literal["cpu", "mem"]
    unit: str  # "pct" -> values are percent and clipped to [0, 100]
    index: pd.DatetimeIndex
    values: np.ndarray
    source: str

    def __len__(self) -> int:
        return len(self.values)

    def head(self, t: int) -> "Series":
        """The first ``t`` hours (expanding-window history at origin ``t``)."""
        return replace(self, index=self.index[:t], values=self.values[:t])
