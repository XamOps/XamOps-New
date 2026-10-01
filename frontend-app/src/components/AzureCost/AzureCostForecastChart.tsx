import React, { useEffect, useRef } from 'react';
import { Chart, ChartConfiguration, LineElement, PointElement, LineController, CategoryScale, LinearScale, Tooltip, Legend, Filler } from 'chart.js';
import type { AzureForecastData } from '../../hooks/useAzureCostData';
import type { ForecastAccuracyMetrics } from '../../types/cost';
import { useMoneyIn } from '../Cost/costCurrency';

Chart.register(LineElement, PointElement, LineController, CategoryScale, LinearScale, Tooltip, Legend, Filler);

interface AzureCostForecastChartProps {
  forecast: AzureForecastData | null;
  isLoading: boolean;
  /** ISO code the subscription is billed in. See AzureCostKPICards on why this is not hardcoded. */
  currency: string;
  /**
   * How the previous forecasts for this subscription turned out, or null when nothing has been scored.
   *
   * <p>The AWS forecast has carried this badge since it was written and the Azure one did not, which
   * was not only a missing component: Azure predictions were recorded nowhere and scored against
   * nothing, so there was no track record to show. A forecast with no track record is a number the
   * reader has no basis to trust or to discount.
   */
  accuracy?: ForecastAccuracyMetrics | null;
  /** Which meter category is forecast. `'ALL'` for the whole subscription. */
  service?: string;
  /** Meter categories with enough daily history to forecast individually. */
  availableServices?: string[];
  onServiceChange?: (service: string) => void;
}

/**
 * How reliable the forecast has proven, as a badge.
 *
 * <p>Deliberately the same five states, wording and colours as the AWS badge in
 * {@code CostForecastChart}: the same metric shown two different ways on two cost pages would
 * undermine both. Money is formatted in the subscription's own currency, which is the one thing that
 * has to differ.
 */
const AccuracyBadge: React.FC<{ accuracy: ForecastAccuracyMetrics; currency: string }> = ({
  accuracy,
  currency,
}) => {
  const money = useMoneyIn(currency);
  const quality = accuracy.dataQuality;

  const styles: Record<string, { bg: string; text: string; dot: string; label: string }> = {
    GOOD: { bg: 'bg-emerald-50 border-emerald-200', text: 'text-emerald-700', dot: 'bg-emerald-500', label: 'Accurate' },
    FAIR: { bg: 'bg-amber-50 border-amber-200', text: 'text-amber-700', dot: 'bg-amber-500', label: 'Fair' },
    POOR: { bg: 'bg-red-50 border-red-200', text: 'text-red-700', dot: 'bg-red-500', label: 'Low' },
    INSUFFICIENT_DATA: { bg: 'bg-gray-50 border-gray-200', text: 'text-gray-500', dot: 'bg-gray-400', label: 'Learning' },
  };
  const s = styles[quality] || styles.INSUFFICIENT_DATA;

  const scoreText =
    quality === 'INSUFFICIENT_DATA' || accuracy.accuracyScore == null
      ? 'Calibrating…'
      : `${accuracy.accuracyScore.toFixed(0)}% accurate`;

  return (
    <div className={`group relative inline-flex items-center gap-2 px-3 py-1.5 rounded-lg border ${s.bg} cursor-help`}>
      <span className={`w-2 h-2 rounded-full ${s.dot}`}></span>
      <span className={`text-[12px] font-semibold ${s.text}`}>{scoreText}</span>
      <div className="absolute top-full right-0 mt-2 w-64 p-3 bg-[rgba(0,0,0,0.88)] text-white text-[11px] rounded-lg shadow-lg opacity-0 group-hover:opacity-100 transition-opacity pointer-events-none z-50 leading-snug text-left">
        <p className="font-semibold mb-1">Forecast accuracy ({s.label})</p>
        <p className="mb-1.5 text-[rgba(255,255,255,0.8)]">{accuracy.message}</p>
        {accuracy.mape != null && (
          <p>Avg error (MAPE): <span className="font-semibold">{accuracy.mape.toFixed(1)}%</span></p>
        )}
        {accuracy.mae != null && (
          <p>Avg error (MAE): <span className="font-semibold">{money(accuracy.mae)}</span></p>
        )}
        {accuracy.confidenceCoverage != null && (
          <p>Within confidence band: <span className="font-semibold">{accuracy.confidenceCoverage.toFixed(0)}%</span></p>
        )}
        <p className="mt-1 text-[rgba(255,255,255,0.6)]">Based on {accuracy.evaluatedCount} evaluated day(s)</p>
      </div>
    </div>
  );
};

const AzureCostForecastChart: React.FC<AzureCostForecastChartProps> = ({
  forecast,
  isLoading,
  currency,
  accuracy,
  service = 'ALL',
  availableServices = [],
  onServiceChange,
}) => {
  const money = useMoneyIn(currency);
  const chartRef = useRef<Chart<'line'> | null>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);

  /**
   * What the points actually are.
   *
   * <p>The caption used to read "predictions for next 30 days" unconditionally. On the monthly
   * fallback path those 30 points are 30 months, so the sentence was wrong by a factor of thirty
   * about the one thing a forecast has to be right about.
   */
  const monthly = forecast?.granularity === 'MONTHLY';
  const pointCount = forecast?.dates?.length ?? 0;

  useEffect(() => {
    if (!canvasRef.current || !forecast?.dates?.length) return;
    if (chartRef.current) chartRef.current.destroy();

    const config: ChartConfiguration<'line'> = {
      type: 'line',
      data: {
        labels: forecast.dates,
        datasets: [
          {
            label: 'Predicted Cost',
            data: forecast.predictions,
            borderColor: '#10b981',
            backgroundColor: 'rgba(16,185,129,0.1)',
            borderWidth: 3,
            borderDash: [],
            pointRadius: 3,
            pointBackgroundColor: '#10b981',
            fill: true,
            tension: 0.3,
          },
          {
            label: 'Lower Bound',
            data: forecast.lowerBounds,
            borderColor: 'rgba(16,185,129,0.25)',
            backgroundColor: 'rgba(16,185,129,0.05)',
            borderWidth: 1,
            borderDash: [5, 5],
            pointRadius: 0,
            fill: false,
            tension: 0.3,
          },
          {
            label: 'Upper Bound',
            data: forecast.upperBounds,
            borderColor: 'rgba(16,185,129,0.25)',
            backgroundColor: 'rgba(16,185,129,0.05)',
            borderWidth: 1,
            borderDash: [5, 5],
            pointRadius: 0,
            fill: '-1',
            tension: 0.3,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        interaction: { mode: 'index', intersect: false },
        plugins: {
          legend: {
            display: true,
            position: 'top',
            align: 'end',
            labels: {
              boxWidth: 8,
              font: { size: 11 },
              usePointStyle: true,
              padding: 20,
              color: 'rgba(0,0,0,0.55)',
            },
          },
          tooltip: {
            backgroundColor: 'rgba(255,255,255,0.97)',
            titleColor: '#1e293b',
            bodyColor: '#475569',
            borderColor: 'rgba(16,185,129,0.3)',
            borderWidth: 1,
            cornerRadius: 8,
            padding: 12,
            boxPadding: 4,
            usePointStyle: true,
            callbacks: {
              label: (c: any) => {
                const v = c.parsed.y;
                if (v == null) return '';
                return `${c.dataset.label}: ${money(v)}`;
              },
            },
          },
        },
        scales: {
          y: {
            beginAtZero: true,
            grid: { color: 'rgba(226,232,240,0.5)' },
            ticks: { font: { size: 11 }, callback: (v) => money(Number(v)) },
          },
          x: {
            grid: { display: false },
            ticks: { font: { size: 10 }, maxRotation: 0, autoSkip: true, maxTicksLimit: 12 },
          },
        },
      },
    };

    chartRef.current = new Chart(canvasRef.current, config);
    return () => { chartRef.current?.destroy(); };
  }, [forecast, money]);

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6">
      <div className="flex flex-wrap items-center justify-between gap-4 mb-6">
        <div>
          <h2 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">
            <i className="fas fa-chart-line mr-2 text-emerald-500"></i>
            Cost Forecast
          </h2>
          <p className="text-[11px] text-[rgba(0,0,0,0.4)]">
            {pointCount > 0
              ? `ML-powered predictions for the next ${pointCount} ${monthly ? 'months' : 'days'}`
              : 'ML-powered cost predictions'}
            {service !== 'ALL' && <> · {service}</>}
          </p>
          {/* Said plainly rather than left for the reader to infer from the axis. A monthly fit comes
              from at most six observations, which is why its band is wide. */}
          {monthly && pointCount > 0 && (
            <p className="text-[11px] text-[rgba(0,0,0,0.38)] mt-0.5">
              Fitted to monthly totals — this subscription has too little daily history for a daily
              forecast yet.
            </p>
          )}
        </div>
        <div className="flex items-center gap-2">
          {accuracy && <AccuracyBadge accuracy={accuracy} currency={currency} />}
          {onServiceChange && availableServices.length > 0 && (
            <select
              aria-label="Forecast scope"
              value={service}
              onChange={(e) => onServiceChange(e.target.value)}
              className="px-2.5 py-1.5 text-[12px] rounded-lg border border-[rgba(0,0,0,0.1)] bg-white text-[rgba(0,0,0,0.88)] focus:outline-none focus:border-[rgba(0,0,0,0.25)] transition-colors"
            >
              <option value="ALL">All Services</option>
              {availableServices.map((svc) => (
                <option key={svc} value={svc}>{svc}</option>
              ))}
            </select>
          )}
        </div>
      </div>

      {isLoading && (
        <div className="animate-pulse bg-gray-100 h-72 rounded-xl"></div>
      )}

      {!isLoading && !forecast?.dates?.length && (
        <div className="text-center py-12 bg-gray-50 rounded-xl border border-dashed border-gray-300">
          <i className="fas fa-chart-line text-4xl mb-4 text-gray-300"></i>
          <p className="font-medium text-gray-400">Forecast unavailable</p>
          <p className="text-[11px] text-gray-400 mt-1 max-w-sm mx-auto">
            {service === 'ALL'
              ? 'Not enough cost history to fit a model yet, or the forecast service is offline.'
              : `${service} does not have enough daily history to be forecast on its own yet.`}
          </p>
        </div>
      )}

      {!isLoading && (forecast?.dates?.length ?? 0) > 0 && (
        <div className="h-72 w-full">
          <canvas ref={canvasRef}></canvas>
        </div>
      )}
    </div>
  );
};

export default AzureCostForecastChart;
