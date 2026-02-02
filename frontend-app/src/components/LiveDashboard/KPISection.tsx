/**
 * KPISection Component
 *
 * Converted from: dashboard.html (KPI cards section)
 *
 * Displays 4 key performance indicator cards with sparkline charts on hover.
 * All logic and styling preserved from original.
 */

import React, { useRef, useCallback } from 'react';
import type { KPIData, CostHistory } from '../../types/liveDashboard';

interface KPISectionProps {
  kpiData: KPIData;
  costHistory?: CostHistory;
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

  // Render sparkline on mouse enter (matches original dashboard.html behavior)
  const handleMouseEnter = useCallback(() => {
    if (!window.Chart || !trendData || trendData.length === 0) return;

    const canvas = document.getElementById(sparklineId) as HTMLCanvasElement;
    if (!canvas) return;

    // Destroy existing chart if any
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
              label: (context: any) => `Value: ${context.parsed.y?.toLocaleString() || 0}`
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
      className="kpi-card rounded-xl group"
      onMouseEnter={handleMouseEnter}
    >
      {/* Main Content - visible by default, hidden on hover */}
      <div className="main-content">
        <div className="flex justify-between items-start h-full">
          <div className="flex-1">
            <h3 className="text-[10px] font-black text-gray-400 uppercase tracking-widest mb-1">{title}</h3>
            <p className="text-3xl font-black text-gray-900 tracking-tight mb-2 metric-value">{value}</p>
            <div className="flex items-center text-[11px] font-bold">
              <span className={`flex items-center gap-1 ${change >= 0 ? 'text-rose-500' : 'text-emerald-500'}`}>
                {change >= 0 ? (
                  <svg className="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" />
                  </svg>
                ) : (
                  <svg className="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M13 17h8m0 0v-8m0 8l-8-8-4 4-6-6" />
                  </svg>
                )}
                {Math.abs(change).toFixed(1)}%
              </span>
              <span className="text-gray-400 ml-1.5 uppercase font-black">vs last period</span>
            </div>
          </div>
          <div className={`w-12 h-12 bg-gradient-to-br ${gradientClass} rounded-2xl flex items-center justify-center shadow-lg shadow-indigo-500/20 transform group-hover:scale-110 transition-transform duration-300`}>
            {icon === 'fa-dollar-sign' && (
              <svg className="w-6 h-6 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
            )}
            {icon === 'fa-chart-line' && (
              <svg className="w-6 h-6 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M7 12l3-3 3 3 4-4M8 21l4-4 4 4M3 4h18M4 4h16v12a1 1 0 01-1 1H5a1 1 0 01-1-1V4z" />
              </svg>
            )}
            {icon === 'fa-calendar-alt' && (
              <svg className="w-6 h-6 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" />
              </svg>
            )}
            {icon === 'fa-piggy-bank' && (
              <svg className="w-6 h-6 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
            )}
          </div>
        </div>
      </div>

      {/* Sparkline Container - hidden by default, visible on hover */}
      <div className="sparkline-container">
        <div className="w-full h-full p-4">
          <canvas id={sparklineId}></canvas>
        </div>
      </div>
    </div>
  );
};

const KPISection: React.FC<KPISectionProps> = ({ kpiData, costHistory, isLoading }) => {
  console.log('🎨 [KPISection] Rendering with data:', kpiData, 'isLoading:', isLoading);

  const formatCurrency = (num: number): string => {
    return '$' + (num != null ? num.toLocaleString('en-US', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    }) : '0.00');
  };

  // Calculate change percentages (matches original dashboard.html logic)
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
      chartColor: '#3b82f6',
      trendData: costs.length > 0 ? costs : [0],
      change: mtdChange,
      sparklineId: 'sparkline-mtdSpend',
    },
    {
      title: 'Forecasted Spend',
      value: formatCurrency(forecastedSpend),
      icon: 'fa-chart-line',
      gradientClass: 'from-yellow-500 to-yellow-600',
      chartColor: '#f59e0b',
      trendData: costs.length > 0 ? [...costs, forecastedSpend] : [forecastedSpend],
      change: forecastChange,
      sparklineId: 'sparkline-forecastedSpend',
    },
    {
      title: 'Last Month Spend',
      value: formatCurrency(lastMonthSpend),
      icon: 'fa-calendar-alt',
      gradientClass: 'from-gray-500 to-gray-600',
      chartColor: '#6b7280',
      trendData: costs.length > 1 ? costs.slice(0, -1) : [lastMonthSpend],
      change: lastMonthChange,
      sparklineId: 'sparkline-lastMonthSpend',
    },
    {
      title: 'Potential Savings',
      value: formatCurrency(potentialSavings),
      icon: 'fa-piggy-bank',
      gradientClass: 'from-green-500 to-green-600',
      chartColor: '#22c55e',
      trendData: [0, potentialSavings],
      change: 100,
      sparklineId: 'sparkline-potentialSavings',
    },
  ];

  return (
    <>
      <div className="flex items-center justify-between mb-8">
        <div className="flex items-center group">
          <div className="w-8 h-8 bg-indigo-50 items-center justify-center rounded-lg mr-3 group-hover:bg-indigo-100 transition-colors hidden md:flex">
            <svg className="w-4 h-4 text-indigo-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 6h16M4 12h16M4 18h16" />
            </svg>
          </div>
          <h2 className="text-xl font-black text-gray-900 tracking-tight uppercase">Key Performance Indicators</h2>
        </div>
        <div className="flex items-center space-x-3 text-[11px] font-black uppercase tracking-widest text-gray-400 bg-white/50 backdrop-blur-sm px-4 py-2 rounded-full border border-gray-100 shadow-sm">
          {isLoading ? (
            <div className="flex items-center gap-2">
              <div className="loading-spinner w-3 h-3 border-2 border-indigo-500/20 border-t-indigo-500"></div>
              <span>Updating...</span>
            </div>
          ) : (
            <div className="flex items-center gap-2">
              <div className="w-2 h-2 bg-green-500 rounded-full shadow-[0_0_8px_rgba(34,197,94,0.4)] animate-pulse"></div>
              <span>Live metrics</span>
            </div>
          )}
        </div>
      </div>

      {/* Loading Skeleton */}
      {isLoading && (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
          {[1, 2, 3, 4].map(i => (
            <div key={i} className="glass-card p-6 rounded-xl h-40 animate-pulse">
              <div className="flex justify-between items-start h-full">
                <div className="flex-1 space-y-3">
                  <div className="h-4 w-28 shimmer rounded"></div>
                  <div className="h-8 w-32 shimmer rounded-lg"></div>
                  <div className="h-3 w-24 shimmer rounded"></div>
                </div>
                <div className="h-12 w-12 shimmer rounded-xl"></div>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* KPI Cards */}
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

export default KPISection;
