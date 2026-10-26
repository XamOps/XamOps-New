from flask import Flask, request, jsonify
from flask_cors import CORS
from models.prophet_forecaster import ProphetForecaster
from models.usage_forecaster import UsageForecaster, normalize_resource, QUANTILE_SEMANTICS
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

# Initialize forecasters (reuse instances).
# Prophet serves /forecast/cost only; ProphetForecaster.forecast_usage is no longer used.
forecaster = ProphetForecaster()
usage_forecaster = UsageForecaster()

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

@app.route('/forecast/usage', methods=['POST'])
def forecast_usage():
    """Forecast hourly resource usage (percent) for CloudSitter schedule recommendations.

    Request: {data: [{ds, y}], periods: int = 336, resource: "cpu" | "memory" | "mem"}
      resource (case-insensitive) picks the method:
        memory -> AutoETS(season_length=24); cpu -> decayed-percentile histogram.
      DEFAULT: when resource is missing or null, the decayed histogram (CPU rule) is used
      and fallback_reason="resource_unspecified". Any other value -> 400.
      The histogram is also used when fewer than 48 usable hourly points exist or the
      ETS fit fails; fallback_reason then says why.

    Response: {status, forecast: [{ds, yhat, yhat_lower, yhat_upper, p50, p90, p95}],
               trend_direction, method, fallback_reason, resource, metadata}
      yhat = p50, yhat_lower = p10, yhat_upper = p90 (upper bound of the central 80 %
      interval, kept for backward compatibility; NOT a P95). Use p95 for headroom.
    """
    start_time = datetime.now()
    try:
        request_data = request.get_json()
        if not request_data or 'data' not in request_data:
            return jsonify({'status': 'error', 'message': 'Missing required field: data'}), 400

        data = request_data.get('data', [])
        periods = request_data.get('periods', 336)  # 14 days × 24 h — guarantees full [7][24] coverage

        if not isinstance(data, list):
            return jsonify({'status': 'error', 'message': 'Data must be a list'}), 400

        try:
            periods = int(periods)
        except (TypeError, ValueError):
            return jsonify({'status': 'error', 'message': 'periods must be an integer'}), 400

        if periods < 1 or periods > app.config['MAX_USAGE_FORECAST_PERIODS']:
            return jsonify({'status': 'error',
                            'message': f'periods must be between 1 and {app.config["MAX_USAGE_FORECAST_PERIODS"]}'}), 400

        if len(data) < app.config['MIN_USAGE_DATA_POINTS']:
            return jsonify({'status': 'error',
                            'message': f'Insufficient data: {len(data)} points (minimum {app.config["MIN_USAGE_DATA_POINTS"]} required)'}), 400

        if len(data) > app.config['MAX_USAGE_DATA_POINTS']:
            return jsonify({'status': 'error',
                            'message': f'Too much data: {len(data)} points (maximum {app.config["MAX_USAGE_DATA_POINTS"]} allowed)'}), 400

        try:
            resource = normalize_resource(request_data.get('resource'))
        except ValueError as ve:
            return jsonify({'status': 'error', 'message': str(ve)}), 400

        logger.info(f"📊 Usage forecast request: {len(data)} hourly points, {periods} periods, resource={resource}")

        result = usage_forecaster.forecast_usage(data=data, periods=periods, resource=resource)
        forecast_result = result['forecast']
        trend_direction = result['trend_direction']

        processing_time = (datetime.now() - start_time).total_seconds()
        logger.info(f"✅ Usage forecast complete: {len(forecast_result)} predictions in {processing_time:.2f}s, "
                    f"method={result['method']}, resource={resource}, "
                    f"fallback_reason={result['fallback_reason']}, trend={trend_direction}")

        return jsonify({
            'status': 'success',
            'forecast': forecast_result,
            'trend_direction': trend_direction,
            'method': result['method'],
            'fallback_reason': result['fallback_reason'],
            'resource': resource,
            'metadata': {
                'data_points': len(data),
                'forecast_periods': periods,
                'processing_time_seconds': round(processing_time, 2),
                'method': result['method'],
                'fallback_reason': result['fallback_reason'],
                'resource': resource,
                'history_points_used': result['history_points_used'],
                'quantile_semantics': QUANTILE_SEMANTICS,
                'model_params': result['model_params'],
            }
        }), 200

    except ValueError as ve:
        return jsonify({'status': 'error', 'message': str(ve)}), 400
    except Exception as e:
        logger.error(f"❌ Usage forecast error: {str(e)}", exc_info=True)
        return jsonify({'status': 'error', 'message': f'Internal server error: {str(e)}'}), 500


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
