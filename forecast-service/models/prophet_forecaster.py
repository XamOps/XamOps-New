import pandas as pd
import numpy as np
from prophet import Prophet
import logging
from datetime import datetime, timedelta

logger = logging.getLogger(__name__)

class ProphetForecaster:
    """Prophet-based cost forecasting model with enhanced accuracy for sparse data"""

    def __init__(self):
        self.model = None
        self.historical_stats = {}

    def forecast(self, data, periods=30, weekly_seasonality=True, yearly_seasonality=False):
        """
        Generate cost forecast using Prophet - ONLY FUTURE DATES
        ✅ FIXED: Handles sparse cost data (many zero days) correctly

        Args:
            data: List of dictionaries with 'ds' (date) and 'y' (value)
            periods: Number of periods to forecast
            weekly_seasonality: Enable weekly seasonality
            yearly_seasonality: Enable yearly seasonality

        Returns:
            List of FUTURE forecast results with dates, predictions, and confidence intervals
        """
        try:
            # Convert to DataFrame
            df = pd.DataFrame(data)

            # Validate columns
            if 'ds' not in df.columns or 'y' not in df.columns:
                raise ValueError("Data must contain 'ds' (date) and 'y' (value) columns")

            # Convert and clean data
            df['ds'] = pd.to_datetime(df['ds'])
            df['y'] = pd.to_numeric(df['y'], errors='coerce')
            df = df.dropna()
            df = df.sort_values('ds').reset_index(drop=True)

            # Minimum data validation
            if len(df) < 14:
                raise ValueError(f"Insufficient data: {len(df)} points (minimum 14 required)")

            # ✅ CRITICAL FIX: Calculate statistics on NON-ZERO values for sparse data
            non_zero_costs = df[df['y'] > 0.01]['y']

            if len(non_zero_costs) == 0:
                raise ValueError("All cost values are zero - cannot generate meaningful forecast")

            # Calculate data span and statistics
            data_span_days = (df['ds'].max() - df['ds'].min()).days

            # Overall stats (including zeros)
            mean_cost = df['y'].mean()
            std_cost = df['y'].std()
            median_cost = df['y'].median()
            min_cost = df['y'].min()
            max_cost = df['y'].max()

            # Non-zero stats (for bounds calculation)
            mean_non_zero = non_zero_costs.mean()
            std_non_zero = non_zero_costs.std()
            median_non_zero = non_zero_costs.median()
            zero_ratio = (len(df) - len(non_zero_costs)) / len(df)

            # Store stats for validation
            self.historical_stats = {
                'mean': mean_cost,
                'std': std_cost,
                'median': median_cost,
                'min': min_cost,
                'max': max_cost,
                'mean_non_zero': mean_non_zero,
                'std_non_zero': std_non_zero,
                'zero_ratio': zero_ratio,
                'span_days': data_span_days
            }

            logger.info(f"📊 Overall stats: mean=${mean_cost:.2f}, std=${std_cost:.2f}, "
                        f"median=${median_cost:.2f}, min=${min_cost:.2f}, max=${max_cost:.2f}, span={data_span_days}d")
            logger.info(f"📊 Non-zero stats: mean=${mean_non_zero:.2f}, std=${std_non_zero:.2f}, "
                        f"median=${median_non_zero:.2f}, zero_ratio={zero_ratio:.1%}")

            # Smart seasonality configuration based on data characteristics
            use_weekly = weekly_seasonality and data_span_days >= 21  # At least 3 weeks
            use_yearly = yearly_seasonality and data_span_days >= 730  # At least 2 years

            # ✅ FIXED: Use non-zero coefficient of variation for changepoint prior
            coefficient_of_variation = std_non_zero / mean_non_zero if mean_non_zero > 0 else 0

            if coefficient_of_variation > 1.5:  # Very high variability
                changepoint_prior = 0.001  # Extremely conservative
                logger.info(f"🔧 Very high variability detected (CV={coefficient_of_variation:.2f}), using conservative changepoint_prior=0.001")
            elif coefficient_of_variation > 0.5:  # High variability
                changepoint_prior = 0.01  # Very conservative
                logger.info(f"🔧 High variability detected (CV={coefficient_of_variation:.2f}), using conservative changepoint_prior=0.01")
            elif coefficient_of_variation > 0.3:  # Medium variability
                changepoint_prior = 0.05  # Conservative
                logger.info(f"🔧 Medium variability detected (CV={coefficient_of_variation:.2f}), using changepoint_prior=0.05")
            else:  # Low variability
                changepoint_prior = 0.1  # Moderate
                logger.info(f"🔧 Low variability detected (CV={coefficient_of_variation:.2f}), using changepoint_prior=0.1")

            logger.info(f"⚙️ Prophet config: weekly={use_weekly}, yearly={use_yearly}, "
                        f"changepoint_prior={changepoint_prior}")

            # Initialize Prophet model with optimized parameters
            self.model = Prophet(
                # Seasonality
                yearly_seasonality=use_yearly,
                weekly_seasonality=use_weekly,
                daily_seasonality=False,

                # Confidence intervals (80% for more realistic range)
                interval_width=0.80,

                # Trend flexibility (conservative for stable predictions)
                changepoint_prior_scale=changepoint_prior,

                # Seasonality mode (additive works better for costs)
                seasonality_mode='additive',

                # Changepoints (let Prophet auto-detect, ~1 per week)
                n_changepoints=min(25, int(data_span_days / 7)),

                # Growth (linear is safer for cost forecasting)
                growth='linear'
            )

            # Fit model
            logger.info(f"🔄 Fitting Prophet model with {len(df)} data points ({len(non_zero_costs)} non-zero)...")
            self.model.fit(df)

            # Create future dataframe
            future = self.model.make_future_dataframe(periods=periods, freq='D')

            # Generate forecast
            logger.info(f"🔮 Generating {periods}-period forecast...")
            forecast_df = self.model.predict(future)

            # ✅ FIXED: Calculate bounds based on NON-ZERO costs (not all data including zeros)
            q1_nz = non_zero_costs.quantile(0.25)
            q3_nz = non_zero_costs.quantile(0.75)
            iqr_nz = q3_nz - q1_nz

            # Upper bound: mean + 2*std of non-zero costs (more realistic for sparse data)
            max_reasonable_upper = mean_non_zero + (2 * std_non_zero)

            # Lower bound: Allow small negative to catch zero predictions
            min_reasonable_lower = 0

            logger.info(f"📏 Forecast bounds (based on non-zero data): lower=${min_reasonable_lower:.2f}, upper=${max_reasonable_upper:.2f}")

            # ✅ Get today's date to filter
            today = pd.Timestamp.now().normalize()

            # Format results with validation - ONLY FUTURE DATES
            results = []
            future_count = 0
            capped_count = 0

            for _, row in forecast_df.iterrows():
                forecast_date = pd.to_datetime(row['ds']).normalize()

                # ✅ ONLY include dates AFTER today
                if forecast_date > today:
                    # Get raw predictions
                    yhat = row['yhat']
                    yhat_lower = row['yhat_lower']
                    yhat_upper = row['yhat_upper']

                    # Apply realistic bounds
                    # 1. Ensure non-negative
                    yhat = max(0, yhat)
                    yhat_lower = max(0, yhat_lower)
                    yhat_upper = max(0, yhat_upper)

                    # 2. Cap extreme outliers (based on non-zero statistics)
                    original_yhat = yhat
                    if yhat > max_reasonable_upper:
                        logger.debug(f"⚠️ Capping prediction from ${yhat:.2f} to ${max_reasonable_upper:.2f}")
                        yhat = max_reasonable_upper
                        capped_count += 1

                    # Allow upper bound to be higher (with flexibility)
                    if yhat_upper > max_reasonable_upper * 2:
                        yhat_upper = max_reasonable_upper * 2

                    # 3. Ensure logical ordering: lower <= yhat <= upper
                    yhat_lower = min(yhat_lower, yhat)
                    yhat_upper = max(yhat_upper, yhat)

                    results.append({
                        'ds': forecast_date.strftime('%Y-%m-%d'),
                        'yhat': round(yhat, 2),
                        'yhat_lower': round(yhat_lower, 2),
                        'yhat_upper': round(yhat_upper, 2)
                    })
                    future_count += 1

            # Log forecast summary
            if results:
                avg_forecast = np.mean([r['yhat'] for r in results])
                total_forecast = sum(r['yhat'] for r in results)

                logger.info(f"✅ Forecast completed: {future_count} future predictions, "
                            f"avg=${avg_forecast:.2f}/day, total=${total_forecast:.2f}")
                logger.info(f"📊 Forecast vs Historical (non-zero): avg forecast=${avg_forecast:.2f} vs "
                            f"avg historical non-zero=${mean_non_zero:.2f} "
                            f"(diff: {((avg_forecast - mean_non_zero) / mean_non_zero * 100) if mean_non_zero > 0 else 0:.1f}%)")

                if capped_count > 0:
                    logger.info(f"⚠️ {capped_count} predictions were capped to prevent unrealistic values")
            else:
                logger.warning("❌ No future predictions generated")

            return results

        except Exception as e:
            logger.error(f"❌ Forecasting error: {str(e)}", exc_info=True)
            raise

    def get_forecast_quality_metrics(self, df, forecast_df):
        """Calculate quality metrics for the forecast (optional validation)"""
        try:
            # Get historical period predictions
            historical_period = forecast_df[forecast_df['ds'] <= df['ds'].max()]

            # Merge with actual values
            merged = pd.merge(df, historical_period[['ds', 'yhat']], on='ds', how='inner')

            if len(merged) == 0:
                return {}

            # Calculate metrics
            actual = merged['y'].values
            predicted = merged['yhat'].values

            # Mean Absolute Percentage Error (handle zeros)
            non_zero_mask = actual > 0.01
            if np.any(non_zero_mask):
                mape = np.mean(np.abs((actual[non_zero_mask] - predicted[non_zero_mask]) / actual[non_zero_mask])) * 100
            else:
                mape = 0

            # Root Mean Square Error
            rmse = np.sqrt(np.mean((actual - predicted) ** 2))

            # Mean Absolute Error
            mae = np.mean(np.abs(actual - predicted))

            return {
                'mape': round(mape, 2),
                'rmse': round(rmse, 2),
                'mae': round(mae, 2),
                'sample_size': len(merged)
            }

        except Exception as e:
            logger.warning(f"⚠️ Could not calculate quality metrics: {e}")
            return {}
