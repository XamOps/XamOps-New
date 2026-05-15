import os


class Config:
    SERVICE_NAME = 'XamOps Marketplace Procurement Service'
    VERSION = '1.0.0'
    LOG_LEVEL = os.getenv('LOG_LEVEL', 'INFO')
    LOG_FORMAT = '[%(asctime)s] %(levelname)s in %(name)s: %(message)s'
    AWS_REGION = os.getenv('AWS_REGION', 'us-east-1')
    WORKERS = int(os.getenv('WORKERS', 2))
    THREADS = int(os.getenv('THREADS', 4))
    TIMEOUT = int(os.getenv('TIMEOUT', 60))


class LocalConfig(Config):
    DEBUG = True
    ENV = 'local'
    ALLOWED_ORIGINS = [
        'http://localhost:8080',
        'http://localhost:5173',
        'http://127.0.0.1:8080',
        'http://127.0.0.1:5173',
    ]
    LOG_LEVEL = 'DEBUG'


class SITConfig(Config):
    DEBUG = False
    ENV = 'sit'
    ALLOWED_ORIGINS = ['https://sit.xamops.com']


class UATConfig(Config):
    DEBUG = False
    ENV = 'uat'
    ALLOWED_ORIGINS = ['https://uat.xamops.com']


class ProductionConfig(Config):
    DEBUG = False
    ENV = 'production'
    ALLOWED_ORIGINS = ['https://live.xamops.com', 'https://www.xamops.com']
    LOG_LEVEL = 'WARNING'
    WORKERS = int(os.getenv('WORKERS', 4))


config_by_name = {
    'local': LocalConfig,
    'sit': SITConfig,
    'uat': UATConfig,
    'production': ProductionConfig,
    'default': LocalConfig,
}


def get_config(env_name=None):
    if env_name is None:
        env_name = os.getenv('FLASK_ENV', 'local')
    return config_by_name.get(env_name, LocalConfig)
