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


REGISTRY = {
    "histogram": _histogram,
    "prophet": _prophet,
    "ets": _ets,
    "mstl": _mstl,
    "chronos_bolt": _chronos_bolt,
}
