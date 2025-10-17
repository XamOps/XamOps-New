from flask import Flask, request, jsonify
from flask_cors import CORS
from models.prophet_forecaster import ProphetForecaster
import logging
from datetime import datetime
import os
from config import get_config

# Initialize Flask app
app = Flask(__name__)

# Load environment-specific configuration
env = os.getenv('FLASK_ENV', 'local')
app.config.from_object(get_config(env))

# ✅ Environment-specific CORS configuration
CORS(app, resources={
    r"/forecast/*": {
        "origins": app.config['ALLOWED_ORIGINS'],
        "methods": ["POST", "OPTIONS"],
        "allow_headers": ["Content-Type", "Authorization"]
    },
    r"/health": {
        "origins": "*",  # Health check accessible from anywhere
        "methods": ["GET"]
    }
})

# ✅ Enhanced logging configuration
logging.basicConfig(
    level=getattr(logging, app.config['LOG_LEVEL']),
    format=app.config['LOG_FORMAT'],
    datefmt='%Y-%m-%d %H:%M:%S'
)
logger = logging.getLogger(__name__)

# Log startup environment
logger.info(f"🚀 Starting {app.config['SERVICE_NAME']} v{app.config['VERSION']}")
logger.info(f"🌍 Environment: {app.config['ENV']}")
logger.info(f"🔒 Allowed Origins: {', '.join(app.config['ALLOWED_ORIGINS'])}")

# Disable werkzeug logs in production
if not app.config['DEBUG']:
    logging.getLogger('werkzeug').setLevel(logging.WARNING)

# Initialize forecaster (reuse instance)
forecaster = ProphetForecaster()

@app.route('/health', methods=['GET'])
def health_check():
    """Health check endpoint for Docker/K8s"""
    return jsonify({
        'status': 'healthy',
        'service': app.config['SERVICE_NAME'],
        'version': app.config['VERSION'],
        'environment': app.config['ENV'],
        'timestamp': datetime.now().isoformat()
    }), 200

@app.route('/forecast/cost', methods=['POST'])
def forecast_cost():
    """Generate cost forecast using Prophet"""
    start_time = datetime.now()

    try:
        request_data = request.get_json()

        if not request_data or 'data' not in request_data:
            logger.warning("⚠️ Missing 'data' field in request")
            return jsonify({
                'status': 'error',
                'message': 'Missing required field: data'
            }), 400

        historical_data = request_data.get('data', [])
        periods = request_data.get('periods', 30)
        weekly_seasonality = request_data.get('weekly_seasonality', True)
        yearly_seasonality = request_data.get('yearly_seasonality', False)

        # Validation
        if not isinstance(historical_data, list):
            return jsonify({
                'status': 'error',
                'message': 'Data must be a list of objects'
            }), 400

        if len(historical_data) < app.config['MIN_DATA_POINTS']:
            logger.warning(f"⚠️ Insufficient data: {len(historical_data)} points")
            return jsonify({
                'status': 'error',
                'message': f'Insufficient data. Minimum {app.config["MIN_DATA_POINTS"]} points required, got {len(historical_data)}.'
            }), 400

        if periods < 1 or periods > app.config['MAX_FORECAST_PERIODS']:
            return jsonify({
                'status': 'error',
                'message': f'Periods must be between 1 and {app.config["MAX_FORECAST_PERIODS"]}'
            }), 400

        logger.info(f"📊 [{app.config['ENV']}] Forecast request: {len(historical_data)} points, {periods} periods")

        # Generate forecast
        forecast_result = forecaster.forecast(
            data=historical_data,
            periods=periods,
            weekly_seasonality=weekly_seasonality,
            yearly_seasonality=yearly_seasonality
        )

        processing_time = (datetime.now() - start_time).total_seconds()
        logger.info(f"✅ Forecast complete: {len(forecast_result)} predictions in {processing_time:.2f}s")

        return jsonify({
            'status': 'success',
            'forecast': forecast_result,
            'metadata': {
                'environment': app.config['ENV'],
                'historical_data_points': len(historical_data),
                'forecast_periods': periods,
                'weekly_seasonality': weekly_seasonality,
                'yearly_seasonality': yearly_seasonality,
                'processing_time_seconds': round(processing_time, 2),
                'historical_stats': forecaster.historical_stats
            }
        }), 200

    except ValueError as ve:
        logger.error(f"❌ Validation error: {str(ve)}")
        return jsonify({'status': 'error', 'message': str(ve)}), 400

    except Exception as e:
        logger.error(f"❌ Forecast error: {str(e)}", exc_info=True)
        return jsonify({
            'status': 'error',
            'message': f'Internal server error: {str(e)}'
        }), 500

@app.errorhandler(404)
def not_found(error):
    logger.warning(f"⚠️ 404: {request.url}")
    return jsonify({'status': 'error', 'message': 'Endpoint not found'}), 404

@app.errorhandler(405)
def method_not_allowed(error):
    logger.warning(f"⚠️ 405: {request.method} {request.url}")
    return jsonify({'status': 'error', 'message': f'Method {request.method} not allowed'}), 405

@app.errorhandler(500)
def internal_error(error):
    logger.error(f"❌ 500: {str(error)}", exc_info=True)
    return jsonify({'status': 'error', 'message': 'Internal server error'}), 500

if __name__ == '__main__':
    port = int(os.getenv("PORT", 5002))
    app.run(host='0.0.0.0', port=port, debug=app.config['DEBUG'])
