import React, { useRef, useCallback } from 'react';
import { AzureKPIData, AzureCostHistory } from '../../types/azureDashboard';

interface AzureKPISectionProps {
  kpiData: AzureKPIData;
  costHistory: AzureCostHistory;
  isLoading: boolean;
}

interface KPICardProps {
  title: string;
  value: string;
  change: number;
  changeLabel: string;
  trendData: number[];
  chartColor: string;
  sparklineId: string;
  isSavings?: boolean;
}

const hexToRgba = (hex: string, alpha: number): string => {
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
};

const KPICard: React.FC<KPICardProps> = ({ title, value, change, changeLabel, trendData, chartColor, sparklineId, isSavings = false }) => {
  const chartInstanceRef = useRef<any>(null);

  const handleMouseEnter = useCallback(() => {
    if (!window.Chart || !trendData || trendData.length === 0) return;
    const canvas = document.getElementById(sparklineId) as HTMLCanvasElement;
    if (!canvas) return;
    if (chartInstanceRef.current) { chartInstanceRef.current.destroy(); chartInstanceRef.current = null; }
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    chartInstanceRef.current = new window.Chart(ctx, {
      type: 'line',
      data: {
        labels: trendData.map((_, i) => i.toString()),
        datasets: [{ data: trendData, borderColor: chartColor, backgroundColor: hexToRgba(chartColor, 0.08), borderWidth: 1.5, pointRadius: 0, tension: 0.4, fill: true }],
      },
      options: {
        responsive: true, maintainAspectRatio: false,
        scales: { x: { display: false }, y: { display: false } },
        plugins: { legend: { display: false }, tooltip: { enabled: false } },
      },
    });
  }, [sparklineId, trendData, chartColor]);

  const handleMouseLeave = useCallback(() => {
    if (chartInstanceRef.current) { chartInstanceRef.current.destroy(); chartInstanceRef.current = null; }
  }, []);

  const isGood = isSavings ? true : change < 0;
  const badgeText = `${change >= 0 ? '+' : ''}${Math.abs(change).toFixed(1)}%`;

  return (
    <div
      className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden group"
      onMouseEnter={handleMouseEnter}
      onMouseLeave={handleMouseLeave}
    >
      <div className="absolute top-0 right-0 w-full h-full pointer-events-none opacity-[0.07]">
        <svg className="absolute -top-8 -right-12 w-32 h-32" viewBox="0 0 200 120" fill="currentColor" style={{ color: '#6b7280' }}>
          <path d="M140 70c0-15-12-27-27-27-3 0-6 1-9 2-6-14-20-24-36-24-17 0-31 10-36 24-3-1-6-2-9-2-15 0-27 12-27 27s12 27 27 27h117c15 0 27-12 27-27z"/>
        </svg>
      </div>
      <div className="absolute inset-x-0 bottom-0 h-14 opacity-0 group-hover:opacity-100 transition-opacity duration-200 pointer-events-none">
        <canvas id={sparklineId} className="w-full h-full"></canvas>
      </div>
      <div className="relative z-10">
        <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)] mb-2">{title}</p>
        <p className="leading-8 mb-3 flex items-baseline gap-0.5">
          <span style={{ fontFamily: "'Bebas Neue','Barlow Condensed',sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>
            {value.includes('.') ? value.slice(0, value.lastIndexOf('.')) : value}
          </span>
          {value.includes('.') && (
            <span style={{ fontFamily: "'Inter',sans-serif", fontSize: '14px', fontWeight: 400, color: 'rgb(10,10,10)' }}>
              {value.slice(value.lastIndexOf('.'))}
            </span>
          )}
        </p>
        <div className="flex items-center gap-2">
          {change !== 0 && (
            <span className={`inline-flex items-center gap-0.5 text-[11px] font-bold ${isGood ? 'text-[#158045]' : 'text-[#DC2626]'}`}>
              <svg className="w-2.5 h-2.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d={isGood ? "M13 17h8m0 0v-8m0 8l-8-8-4 4-6-6" : "M13 7h8m0 0v8m0-8l-8 8-4-4-6 6"} />
              </svg>
              {badgeText}
            </span>
          )}
          <span className="text-[11px] text-[rgba(0,0,0,0.35)]">{changeLabel}</span>
        </div>
      </div>
    </div>
  );
};

const AzureKPISection: React.FC<AzureKPISectionProps> = ({ kpiData, costHistory, isLoading }) => {
  const formatCurrency = (num: number): string =>
    '$' + (num != null ? num.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : '0.00');

  const costs = costHistory?.costs || [];
  const mtdSpend = kpiData.mtdSpend || 0;
  const lastMonthSpend = kpiData.lastMonthSpend || 0;
  const forecastedSpend = kpiData.forecastedSpend || 0;
  const potentialSavings = kpiData.potentialSavings || 0;

  const mtdChange = lastMonthSpend > 0 ? ((mtdSpend - lastMonthSpend) / lastMonthSpend * 100) : 0;
  const forecastChange = mtdSpend > 0 ? ((forecastedSpend - mtdSpend) / mtdSpend * 100) : 0;
  const lastMonthChange = costs.length > 2 ? ((lastMonthSpend - costs[costs.length - 3]) / costs[costs.length - 3] * 100) : 0;

  if (isLoading) {
    return (
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        {[1, 2, 3, 4].map(i => (
          <div key={i} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 h-[110px] animate-pulse">
            <div className="h-2.5 w-24 bg-[rgba(0,0,0,0.06)] rounded mb-3"></div>
            <div className="h-7 w-28 bg-[rgba(0,0,0,0.06)] rounded mb-3"></div>
            <div className="h-2 w-16 bg-[rgba(0,0,0,0.04)] rounded"></div>
          </div>
        ))}
      </div>
    );
  }

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
      <KPICard title="Month-to-Date Spend" value={formatCurrency(mtdSpend)} change={mtdChange} changeLabel="vs last period" trendData={costs.length > 0 ? costs : [0]} chartColor="#6b7280" sparklineId="azure-sparkline-mtd" />
      <KPICard title="Forecasted Spend" value={formatCurrency(forecastedSpend)} change={forecastChange} changeLabel="vs current MTD" trendData={costs.length > 0 ? [...costs, forecastedSpend] : [forecastedSpend]} chartColor="#6b7280" sparklineId="azure-sparkline-forecast" />
      <KPICard title="Last Month Spend" value={formatCurrency(lastMonthSpend)} change={lastMonthChange} changeLabel="vs prior month" trendData={costs.length > 1 ? costs.slice(0, -1) : [lastMonthSpend]} chartColor="#6b7280" sparklineId="azure-sparkline-lastmonth" />
      <KPICard title="Potential Savings" value={formatCurrency(potentialSavings)} change={0} changeLabel="available to reclaim" trendData={[0, potentialSavings]} chartColor="#1BE68B" sparklineId="azure-sparkline-savings" isSavings />
    </div>
  );
};

export default AzureKPISection;
