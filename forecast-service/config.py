import os

class Config:
    """Base configuration with common settings"""
    # Flask settings
    SECRET_KEY = os.getenv('SECRET_KEY', 'your-secret-key-change-in-production')

    # Service settings
    SERVICE_NAME = 'XamOps Prophet Forecast Service'
    VERSION = '1.1.0'

    # Logging
    LOG_LEVEL = os.getenv('LOG_LEVEL', 'INFO')
    LOG_FORMAT = '[%(asctime)s] %(levelname)s in %(name)s: %(message)s'

    # Forecasting settings
    MAX_FORECAST_PERIODS = 365
    MIN_DATA_POINTS = 7

    # Usage forecasting settings (CloudSitter schedule recommendations)
    MIN_USAGE_DATA_POINTS = 24  # below USAGE_MODEL_MIN_HISTORY the histogram fallback is used
    USAGE_MODEL_MIN_HISTORY = 48
    MAX_USAGE_DATA_POINTS = 24 * 365  # 1 year of hourly data
    MAX_USAGE_FORECAST_PERIODS = 24 * 30  # 30 days of hourly predictions

    # Gunicorn settings
    WORKERS = int(os.getenv('WORKERS', 2))
    THREADS = int(os.getenv('THREADS', 4))
    TIMEOUT = int(os.getenv('TIMEOUT', 180))

class LocalConfig(Config):
    """Local development configuration"""
    DEBUG = True
    ENV = 'local'
    ALLOWED_ORIGINS = [
        'http://localhost:8080',
        'http://localhost:3000',
        'http://127.0.0.1:8080',
        'http://127.0.0.1:3000'
    ]
    LOG_LEVEL = 'DEBUG'

class SITConfig(Config):
    """SIT (System Integration Testing) configuration"""
    DEBUG = False
    ENV = 'sit'
    ALLOWED_ORIGINS = [
        'https://sit.xamops.com',
        'http://sit.xamops.com'
    ]
    LOG_LEVEL = 'INFO'

class UATConfig(Config):
    """UAT (User Acceptance Testing) configuration"""
    DEBUG = False
    ENV = 'uat'
    ALLOWED_ORIGINS = [
        'https://uat.xamops.com',
        'http://uat.xamops.com'
    ]
    LOG_LEVEL = 'INFO'

class ProductionConfig(Config):
    """Production configuration"""
    DEBUG = False
    ENV = 'production'
    ALLOWED_ORIGINS = [
        'https://live.xamops.com',
        'https://www.xamops.com'
    ]
    LOG_LEVEL = 'WARNING'
    WORKERS = int(os.getenv('WORKERS', 4))  # More workers in production

# Configuration mapping
config_by_name = {
    'local': LocalConfig,
    'sit': SITConfig,
    'uat': UATConfig,
    'production': ProductionConfig,
    'default': LocalConfig
}

def get_config(env_name=None):
    """Get configuration based on environment name"""
    if env_name is None:
        env_name = os.getenv('FLASK_ENV', 'local')
    return config_by_name.get(env_name, LocalConfig)
