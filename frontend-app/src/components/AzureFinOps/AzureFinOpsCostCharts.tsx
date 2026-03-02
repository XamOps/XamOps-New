import React, { useEffect, useRef } from 'react';
import { AzureFinOpsReportData } from '../../types/azureFinops';
import { useTheme } from '../../contexts/ThemeContext';
import { Chart, registerables } from 'chart.js';
import clsx from 'clsx';

Chart.register(...registerables);

interface AzureFinOpsCostChartsProps {
  reportData: AzureFinOpsReportData | null;
  isLoading: boolean;
}

const AZURE_COLORS = [
  '#0078D4', '#005A9E', '#50E6FF', '#00BCF2', '#8661C5',
  '#5C2D91', '#E81123', '#FFB900', '#00A4EF', '#7FBA00',
  '#00B294', '#F25022',
];

const REGION_COLORS = [
  '#FF6B6B', '#4ECDC4', '#45B7D1', '#FFA07A', '#98D8C8',
  '#F7DC6F', '#BB8FCE', '#85C1E2', '#F8B88B', '#ABEBC6',
];

const AzureFinOpsCostCharts: React.FC<AzureFinOpsCostChartsProps> = ({ reportData, isLoading }) => {
  const { theme } = useTheme();
  const serviceChartRef = useRef<HTMLCanvasElement>(null);
  const regionChartRef = useRef<HTMLCanvasElement>(null);
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const serviceChartInstance = useRef<any>(null);
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const regionChartInstance = useRef<any>(null);

  const tickColor = theme === 'dark' ? '#94a3b8' : '#6b7280';
  const gridColor = theme === 'dark' ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.05)';

  // Service doughnut chart
  useEffect(() => {
    if (isLoading || !reportData || !serviceChartRef.current) return;
    const data = reportData.costBreakdown ?? [];
    if (data.length === 0) return;

    serviceChartInstance.current?.destroy();
    const ctx = serviceChartRef.current.getContext('2d');
    if (!ctx) return;

    serviceChartInstance.current = new Chart(ctx, {
      type: 'doughnut',
      data: {
        labels: data.map(d => d.service),
        datasets: [{
          data: data.map(d => d.cost),
          backgroundColor: AZURE_COLORS,
          borderWidth: 0,
        }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: {
            position: 'right',
            labels: {
              usePointStyle: true,
              color: tickColor,
              font: { family: "'Inter', sans-serif", size: 11 },
              boxWidth: 10,
            },
          },
          tooltip: {
            callbacks: {
              label: (ctx) => {
                const val = ctx.raw as number;
                return ` $${val.toLocaleString('en-US', { maximumFractionDigits: 0 })}`;
              },
            },
          },
        },
      },
    });

    return () => { serviceChartInstance.current?.destroy(); };
  }, [reportData, isLoading, theme]);

  // Region bar chart
  useEffect(() => {
    if (isLoading || !reportData || !regionChartRef.current) return;
    const data = reportData.costByRegion ?? [];
    if (data.length === 0) return;

    regionChartInstance.current?.destroy();
    const ctx = regionChartRef.current.getContext('2d');
    if (!ctx) return;

    regionChartInstance.current = new Chart(ctx, {
      type: 'bar',
      data: {
        labels: data.map(d => d.region),
        datasets: [{
          label: 'Cost (USD)',
          data: data.map(d => d.cost),
          backgroundColor: REGION_COLORS,
          borderRadius: 6,
          borderSkipped: false,
        }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        indexAxis: 'y',
        plugins: {
          legend: { display: false },
          tooltip: {
            callbacks: {
              label: (ctx) => ` $${(ctx.raw as number).toLocaleString('en-US', { maximumFractionDigits: 0 })}`,
            },
          },
        },
        scales: {
          x: {
            grid: { color: gridColor },
            ticks: {
              color: tickColor,
              callback: (v) => `$${(v as number).toLocaleString('en-US', { maximumFractionDigits: 0 })}`,
            },
          },
          y: {
            grid: { display: false },
            ticks: { color: tickColor },
          },
        },
      },
    });

    return () => { regionChartInstance.current?.destroy(); };
  }, [reportData, isLoading, theme]);

  const cardCls = clsx('glass-card rounded-xl p-6', theme === 'dark' ? 'bg-slate-800/50' : '');
  const titleCls = clsx('text-base font-bold mb-1', theme === 'dark' ? 'text-slate-100' : 'text-gray-900');
  const subCls = clsx('text-xs', theme === 'dark' ? 'text-slate-400' : 'text-gray-500');

  if (isLoading) {
    return (
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {[0, 1].map(i => (
          <div key={i} className={clsx(cardCls, 'animate-pulse')}>
            <div className="h-5 bg-gray-200 rounded w-1/3 mb-4" />
            <div className="h-64 bg-gray-200 rounded" />
          </div>
        ))}
      </div>
    );
  }

  const hasServiceData = (reportData?.costBreakdown ?? []).length > 0;
  const hasRegionData = (reportData?.costByRegion ?? []).length > 0;

  return (
    <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
      {/* Cost by Service */}
      <div className={cardCls}>
        <div className="flex items-center gap-3 mb-5">
          <div className="w-9 h-9 bg-gradient-to-br from-blue-500 to-cyan-600 rounded-lg flex items-center justify-center shadow">
            <i className="fas fa-chart-pie text-white text-sm" />
          </div>
          <div>
            <h3 className={titleCls}>Cost by Service</h3>
            <p className={subCls}>Top Azure services this month</p>
          </div>
        </div>
        {hasServiceData ? (
          <div className="h-72 relative">
            <canvas ref={serviceChartRef} />
          </div>
        ) : (
          <div className="h-72 flex flex-col items-center justify-center">
            <i className="fas fa-chart-pie text-gray-300 text-4xl mb-3" />
            <p className={clsx('text-sm', theme === 'dark' ? 'text-slate-500' : 'text-gray-400')}>
              No service cost data available
            </p>
          </div>
        )}
      </div>

      {/* Cost by Region */}
      <div className={cardCls}>
        <div className="flex items-center gap-3 mb-5">
          <div className="w-9 h-9 bg-gradient-to-br from-orange-500 to-red-600 rounded-lg flex items-center justify-center shadow">
            <i className="fas fa-globe text-white text-sm" />
          </div>
          <div>
            <h3 className={titleCls}>Cost by Region</h3>
            <p className={subCls}>Geographic cost distribution</p>
          </div>
        </div>
        {hasRegionData ? (
          <div className="h-72 relative">
            <canvas ref={regionChartRef} />
          </div>
        ) : (
          <div className="h-72 flex flex-col items-center justify-center">
            <i className="fas fa-globe text-gray-300 text-4xl mb-3" />
            <p className={clsx('text-sm', theme === 'dark' ? 'text-slate-500' : 'text-gray-400')}>
              No region cost data available
            </p>
          </div>
        )}
      </div>
    </div>
  );
};

export default AzureFinOpsCostCharts;
