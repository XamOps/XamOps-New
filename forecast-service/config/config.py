import os

class Config:
    """Configuration for forecast service"""

    # Flask config
    DEBUG = os.getenv('DEBUG', 'False') == 'True'
    HOST = os.getenv('HOST', '0.0.0.0')
    PORT = int(os.getenv('PORT', 5002))

    # Prophet config
    MIN_DATA_POINTS = int(os.getenv('MIN_DATA_POINTS', 7))
    MAX_FORECAST_PERIODS = int(os.getenv('MAX_FORECAST_PERIODS', 90))
    CONFIDENCE_INTERVAL = float(os.getenv('CONFIDENCE_INTERVAL', 0.95))

    # Logging
    LOG_LEVEL = os.getenv('LOG_LEVEL', 'INFO')
