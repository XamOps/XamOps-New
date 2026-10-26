"""Method registry: name -> zero-arg factory. Heavy imports stay inside the factories."""


def _histogram():
    from .histogram import HistogramForecaster
    return HistogramForecaster()


def _prophet():
    from .prophet_method import ProphetForecaster
    return ProphetForecaster()


def _ets():
    from .statsforecast_methods import ETSForecaster
    return ETSForecaster()


def _mstl():
    from .statsforecast_methods import MSTLForecaster
    return MSTLForecaster()


def _chronos_bolt():
    from .chronos_method import ChronosBoltForecaster
    return ChronosBoltForecaster()


def _hod(half_life_days: float, shrink_k=None):
    def factory():
        from .hod_histogram import HodHistogramForecaster
        return HodHistogramForecaster(half_life_days, shrink_k)
    return factory


REGISTRY = {
    "histogram": _histogram,
    "prophet": _prophet,
    "ets": _ets,
    "mstl": _mstl,
    "chronos_bolt": _chronos_bolt,
    # hour-of-day CPU histograms (option B): half-life in days, optional shrinkage k
    "hod_hl3": _hod(3),
    "hod_hl7": _hod(7),
    "hod_hl14": _hod(14),
    "hod_shrink_hl3_k4": _hod(3, 4),
    "hod_shrink_hl7_k4": _hod(7, 4),
    "hod_shrink_hl14_k4": _hod(14, 4),
    "hod_shrink_hl7_k1": _hod(7, 1),
    "hod_shrink_hl7_k16": _hod(7, 16),
}
HOD_METHODS = tuple(k for k in REGISTRY if k.startswith("hod_"))
