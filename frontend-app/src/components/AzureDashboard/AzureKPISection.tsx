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
  icon: string;
  gradientClass: string;
  chartColor: string;
  trendData: number[];
  change: number;
  sparklineId: string;
}

// Helper to convert hex to rgba
const hexToRgba = (hex: string, alpha: number): string => {
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
};

const KPICard: React.FC<KPICardProps> = ({
  title,
  value,
  icon,
  gradientClass,
  chartColor,
  trendData,
  change,
  sparklineId
}) => {
  const chartInstanceRef = useRef<any>(null);

  const handleMouseEnter = useCallback(() => {
    if (!window.Chart || !trendData || trendData.length === 0) return;

    const canvas = document.getElementById(sparklineId) as HTMLCanvasElement;
    if (!canvas) return;

    if (chartInstanceRef.current) {
      chartInstanceRef.current.destroy();
      chartInstanceRef.current = null;
    }

    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    chartInstanceRef.current = new window.Chart(ctx, {
      type: 'line',
      data: {
        labels: trendData.map((_, i) => i.toString()),
        datasets: [{
          data: trendData,
          borderColor: chartColor,
          backgroundColor: hexToRgba(chartColor, 0.1),
          borderWidth: 3,
          pointRadius: 0,
          pointHoverRadius: 6,
          pointHoverBackgroundColor: chartColor,
          pointHoverBorderColor: '#ffffff',
          pointHoverBorderWidth: 2,
          tension: 0.4,
          fill: true
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        scales: {
          x: { display: false },
          y: { display: false }
        },
        plugins: {
          legend: { display: false },
          tooltip: {
            enabled: true,
            backgroundColor: 'rgba(0, 0, 0, 0.8)',
            titleColor: 'white',
            bodyColor: 'white',
            borderColor: chartColor,
            borderWidth: 1,
            cornerRadius: 6,
            callbacks: {
              label: (context: any) => `Value: ₹${context.parsed.y?.toLocaleString('en-IN') || 0}`
            }
          }
        },
        interaction: {
          intersect: false,
          mode: 'index'
        }
      }
    });
  }, [sparklineId, trendData, chartColor]);

  return (
    <div
      className="glass-card kpi-card p-6 group transition-all duration-500 hover-glow"
      onMouseEnter={handleMouseEnter}
      style={{ borderTop: `4px solid ${chartColor}` }}
    >
      <div className="main-content relative z-10">
        <div className="flex justify-between items-start h-full">
          <div className="flex-1">
            <h3 className="text-[11px] font-extrabold text-slate-400 uppercase tracking-widest mb-1.5">{title}</h3>
            <p className="text-3xl font-extrabold text-slate-900 tracking-tight mb-2 metric-value">{value}</p>
            <div className="flex items-center text-[12px] font-bold">
              <span className={`flex items-center gap-1.5 px-2 py-0.5 rounded-full ${change >= 0 ? 'bg-rose-50 text-rose-500' : 'bg-emerald-50 text-emerald-500'}`}>
                {change !== 0 && (
                  <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d={change >= 0 ? "M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" : "M13 17h8m0 0v-8m0 8l-8-8-4 4-6-6"} />
                  </svg>
                )}
                {Math.abs(change).toFixed(1)}%
              </span>
              <span className="text-slate-400 ml-2 uppercase font-extrabold text-[10px] tracking-wider">vs last period</span>
            </div>
          </div>
          <div className={`w-14 h-14 bg-gradient-to-br ${gradientClass} rounded-2xl flex items-center justify-center shadow-lg shadow-blue-500/10 transform group-hover:scale-110 group-hover:rotate-6 transition-all duration-500`}>
            <i className={`fas ${icon} text-white text-xl`}></i>
          </div>
        </div>
      </div>

      <div className="sparkline-container absolute inset-0 bg-white/98 backdrop-blur-md opacity-0 group-hover:opacity-100 transition-all duration-500 z-0">
        <div className="w-full h-full p-6">
          <canvas id={sparklineId}></canvas>
        </div>
      </div>
    </div>
  );
};

const AzureKPISection: React.FC<AzureKPISectionProps> = ({ kpiData, costHistory, isLoading }) => {
  const formatCurrency = (num: number): string => {
    return '₹' + (num != null ? num.toLocaleString('en-IN', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    }) : '0.00');
  };

  const costs = costHistory?.costs || [];
  const mtdSpend = kpiData.mtdSpend || 0;
  const lastMonthSpend = kpiData.lastMonthSpend || 0;
  const forecastedSpend = kpiData.forecastedSpend || 0;
  const potentialSavings = kpiData.potentialSavings || 0;

  const mtdChange = lastMonthSpend > 0 ? ((mtdSpend - lastMonthSpend) / lastMonthSpend * 100) : 0;
  const forecastChange = mtdSpend > 0 ? ((forecastedSpend - mtdSpend) / mtdSpend * 100) : 0;
  const lastMonthChange = costs.length > 2 ? ((lastMonthSpend - costs[costs.length - 3]) / costs[costs.length - 3] * 100) : 0;

  const kpiCards: KPICardProps[] = [
    {
      title: 'Month-to-Date Spend',
      value: formatCurrency(mtdSpend),
      icon: 'fa-dollar-sign',
      gradientClass: 'from-blue-500 to-blue-600',
      chartColor: '#0078D4',
      trendData: costs.length > 0 ? costs : [0],
      change: mtdChange,
      sparklineId: 'sparkline-azure-mtdSpend',
    },
    {
      title: 'Forecasted Spend',
      value: formatCurrency(forecastedSpend),
      icon: 'fa-chart-line',
      gradientClass: 'from-amber-500 to-amber-600',
      chartColor: '#F59E0B',
      trendData: costs.length > 0 ? [...costs, forecastedSpend] : [forecastedSpend],
      change: forecastChange,
      sparklineId: 'sparkline-azure-forecastedSpend',
    },
    {
      title: 'Last Month Spend',
      value: formatCurrency(lastMonthSpend),
      icon: 'fa-calendar-alt',
      gradientClass: 'from-slate-500 to-slate-600',
      chartColor: '#64748B',
      trendData: costs.length > 1 ? costs.slice(0, -1) : [lastMonthSpend],
      change: lastMonthChange,
      sparklineId: 'sparkline-azure-lastMonthSpend',
    },
    {
      title: 'Potential Savings',
      value: formatCurrency(potentialSavings),
      icon: 'fa-piggy-bank',
      gradientClass: 'from-emerald-500 to-emerald-600',
      chartColor: '#10B981',
      trendData: [0, potentialSavings],
      change: 0,
      sparklineId: 'sparkline-azure-potentialSavings',
    },
  ];

  return (
    <>
      {isLoading && (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
          {[1, 2, 3, 4].map(i => (
            <div key={i} className="glass-card p-6 rounded-xl h-40 shimmer"></div>
          ))}
        </div>
      )}

      {!isLoading && (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6 fade-in">
          {kpiCards.map(card => (
            <KPICard key={card.sparklineId} {...card} />
          ))}
        </div>
      )}
    </>
  );
};

export default AzureKPISection;
