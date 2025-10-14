from flask import Flask, request, jsonify
from flask_cors import CORS
from models.prophet_forecaster import ProphetForecaster
import logging
from datetime import datetime

app = Flask(__name__)
CORS(app)

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Initialize forecaster
forecaster = ProphetForecaster()

@app.route('/health', methods=['GET'])
def health_check():
    """Health check endpoint"""
    return jsonify({
        'status': 'healthy',
        'service': 'Prophet Forecast Service',
        'timestamp': datetime.now().isoformat()
    }), 200

@app.route('/forecast/cost', methods=['POST'])
def forecast_cost():
    """
    Forecast cost using Prophet

    Request Body:
    {
        "data": [
            {"ds": "2025-10-01", "y": 100.50},
            {"ds": "2025-10-02", "y": 105.20}
        ],
        "periods": 30,
        "weekly_seasonality": true,
        "yearly_seasonality": false
    }
    """
    try:
        # Get request data
        request_data = request.get_json()

        if not request_data or 'data' not in request_data:
            return jsonify({
                'status': 'error',
                'message': 'Missing required field: data'
            }), 400

        historical_data = request_data.get('data', [])
        periods = request_data.get('periods', 30)
        weekly_seasonality = request_data.get('weekly_seasonality', True)
        yearly_seasonality = request_data.get('yearly_seasonality', False)

        # Validate data
        if len(historical_data) < 7:
            return jsonify({
                'status': 'error',
                'message': 'Insufficient data. Minimum 7 data points required.'
            }), 400

        logger.info(f"Generating forecast for {periods} periods with {len(historical_data)} historical data points")

        # Generate forecast
        forecast_result = forecaster.forecast(
            data=historical_data,
            periods=periods,
            weekly_seasonality=weekly_seasonality,
            yearly_seasonality=yearly_seasonality
        )

        logger.info(f"Forecast generated successfully: {len(forecast_result)} predictions")

        return jsonify({
            'status': 'success',
            'forecast': forecast_result,
            'metadata': {
                'historical_data_points': len(historical_data),
                'forecast_periods': periods,
                'weekly_seasonality': weekly_seasonality,
                'yearly_seasonality': yearly_seasonality
            }
        }), 200

    except ValueError as ve:
        logger.error(f"Validation error: {str(ve)}")
        return jsonify({
            'status': 'error',
            'message': str(ve)
        }), 400

    except Exception as e:
        logger.error(f"Forecast error: {str(e)}", exc_info=True)
        return jsonify({
            'status': 'error',
            'message': f'Internal server error: {str(e)}'
        }), 500

@app.errorhandler(404)
def not_found(error):
    return jsonify({
        'status': 'error',
        'message': 'Endpoint not found'
    }), 404

@app.errorhandler(500)
def internal_error(error):
    return jsonify({
        'status': 'error',
        'message': 'Internal server error'
    }), 500

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5002, debug=False)
