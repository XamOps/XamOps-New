import React, { useEffect, useRef, useMemo } from 'react';
import { Chart, ChartConfiguration, LineElement, PointElement, LineController, CategoryScale, LinearScale, Tooltip, Legend, Filler } from 'chart.js';
import type { GcpForecastDataPoint } from '../../types/gcpCost';
import type { ForecastAccuracyMetrics } from '../../types/cost';
import { useMoneyIn } from '../Cost/costCurrency';

Chart.register(LineElement, PointElement, LineController, CategoryScale, LinearScale, Tooltip, Legend, Filler);

interface GcpCostForecastChartProps {
  data: GcpForecastDataPoint[];
  isLoading: boolean;
  service: string;
  periods: number;
  availableServices: string[];
  onServiceChange: (service: string) => void;
  onPeriodsChange: (periods: number) => void;
  /** ISO code the billing account is denominated in. */
  currency: string;
  /**
   * How the previous forecasts for this project turned out, or null when nothing has been scored.
   *
   * <p>The metrics have existed for a while and nothing displayed them: the forecast endpoint records
   * its predictions against `PROVIDER_GCP` and the nightly evaluation scores them against the daily
   * costs `GcpCostService` already serves. A forecast with no track record is a number the reader has
   * no basis to trust or to discount.
   */
  accuracy?: ForecastAccuracyMetrics | null;
}

const inputCls = 'px-2.5 py-1.5 text-[12px] rounded-lg border border-[rgba(0,0,0,0.1)] bg-white text-[rgba(0,0,0,0.88)] focus:outline-none focus:border-[rgba(0,0,0,0.25)] transition-colors';

/**
 * How reliable the forecast has proven, as a badge.
 *
 * <p>Deliberately the same five states, wording and colours as the AWS and Azure badges. The same
 * metric shown three different ways on three cost pages would undermine all three; only the currency
 * differs, because a GCP billing account is denominated in whatever the customer signed up in.
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

const GcpCostForecastChart: React.FC<GcpCostForecastChartProps> = ({
  data,
  isLoading,
  service,
  periods,
  availableServices,
  onServiceChange,
  onPeriodsChange,
  currency,
  accuracy,
}) => {
  const money = useMoneyIn(currency);
  const chartRef = useRef<HTMLCanvasElement>(null);
  const chartInstanceRef = useRef<Chart<'line'> | null>(null);

  const processedData = useMemo(() => {
    if (!data || data.length === 0) return null;
    const labels = data.map(d => new Date(d.ds).toLocaleDateString('en-US', { month: 'short', day: 'numeric' }));
    const actuals = data.map(d => d.yhat_lower === 0 ? d.yhat : null);
    const forecast = data.map(d => d.yhat);
    const lower = data.map(d => d.yhat_lower);
    const upper = data.map(d => d.yhat_upper);
    return { labels, actuals, forecast, lower, upper };
  }, [data]);

  useEffect(() => {
    if (!chartRef.current || !processedData || isLoading) return;
    if (chartInstanceRef.current) chartInstanceRef.current.destroy();

    const config: ChartConfiguration<'line'> = {
      type: 'line',
      data: {
        labels: processedData.labels,
        datasets: [
          {
            label: 'Observed',
            data: processedData.actuals,
            borderColor: '#6366f1',
            backgroundColor: 'rgba(99,102,241,0.08)',
            borderWidth: 2.5,
            pointRadius: 3,
            pointBackgroundColor: '#6366f1',
            fill: false,
            tension: 0.3,
            spanGaps: false,
          },
          {
            label: 'Forecast',
            data: processedData.forecast,
            borderColor: '#10b981',
            backgroundColor: 'rgba(16,185,129,0.08)',
            borderWidth: 2.5,
            borderDash: [6, 4],
            pointRadius: 3,
            pointBackgroundColor: '#10b981',
            fill: false,
            tension: 0.3,
          },
          {
            label: 'Confidence Interval',
            data: processedData.upper,
            borderColor: 'rgba(16,185,129,0.2)',
            backgroundColor: 'rgba(16,185,129,0.06)',
            borderWidth: 1,
            pointRadius: 0,
            fill: '+1',
            tension: 0.3,
          },
          {
            label: 'Lower Bound',
            data: processedData.lower,
            borderColor: 'transparent',
            backgroundColor: 'transparent',
            borderWidth: 0,
            pointRadius: 0,
            fill: false,
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
              filter: (item) => item.text !== 'Lower Bound',
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
              label: (ctx) => {
                const v = ctx.parsed.y;
                if (v === null || v === undefined) return '';
                if (ctx.dataset.label === 'Lower Bound') return '';
                // Was a hardcoded '$' while the y-axis below used '₹' — the same money labelled with
                // two different currencies on one chart. Both now read the billing account's own.
                return `${ctx.dataset.label}: ${money(v)}`;
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

    chartInstanceRef.current = new Chart(chartRef.current, config);
    return () => { chartInstanceRef.current?.destroy(); };
  }, [processedData, isLoading, money]);

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6 mb-6">
      <div className="flex flex-wrap items-center justify-between gap-4 mb-6">
        <div>
          <h2 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">
            <i className="fas fa-chart-line mr-2 text-emerald-500"></i>
            Cost Forecast
          </h2>
          <p className="text-[11px] text-[rgba(0,0,0,0.4)]">ML-powered cost predictions — next {periods} days</p>
        </div>
        <div className="flex items-center gap-2 flex-wrap">
          {accuracy && <AccuracyBadge accuracy={accuracy} currency={currency} />}
          <select value={service} onChange={e => onServiceChange(e.target.value)} className={inputCls}>
            {availableServices.map(svc => (
              <option key={svc} value={svc}>{svc}</option>
            ))}
          </select>
          <select value={periods} onChange={e => onPeriodsChange(Number(e.target.value))} className={inputCls}>
            <option value={30}>Next 30 Days</option>
            <option value={60}>Next 60 Days</option>
            <option value={90}>Next 90 Days</option>
          </select>
        </div>
      </div>

      {isLoading && (
        <div className="animate-pulse bg-gray-100 h-[360px] rounded-xl"></div>
      )}

      {!isLoading && data && data.length > 0 && (
        <div className="h-[360px] w-full">
          <canvas ref={chartRef}></canvas>
        </div>
      )}

      {!isLoading && (!data || data.length === 0) && (
        <div className="text-center py-12 text-gray-500 bg-gray-50 rounded-xl border border-dashed border-gray-300">
          <i className="fas fa-chart-line text-4xl mb-4 text-gray-300"></i>
          <p className="font-medium text-gray-400">No forecast data available for this selection</p>
        </div>
      )}
    </div>
  );
};

export default GcpCostForecastChart;
