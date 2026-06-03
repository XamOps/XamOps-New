import React, { useEffect, useRef } from 'react';
import { Icon } from '@iconify/react';
import { AzureFinOpsReportData } from '../../types/azureFinops';
import { Chart, registerables } from 'chart.js';
import Highcharts from '../../lib/highcharts-init';

Chart.register(...registerables);

interface AzureFinOpsCostChartsProps {
  reportData: AzureFinOpsReportData | null;
  isLoading: boolean;
}

const VAR_PIE_PALETTE = [
  '#4caefe', '#3dc3e8', '#2dd9db', '#1feeaf', '#0ff3a0',
  '#00e887', '#23e274', '#35d96f', '#4cd4a0', '#1addc8',
  '#2bcfee', '#0de0b0', '#38e8c8', '#50f0d0', '#18d4e8',
  '#3aefb8', '#29c9e0', '#10f8c0', '#40dce0', '#5af0a8',
];

const REGION_COLORS = [
  '#FF6B6B', '#4ECDC4', '#45B7D1', '#FFA07A', '#98D8C8',
  '#F7DC6F', '#BB8FCE', '#85C1E2', '#F8B88B', '#ABEBC6',
];

const tickColor = 'rgba(0,0,0,0.45)';
const gridColor = 'rgba(0,0,0,0.05)';

const AzureFinOpsCostCharts: React.FC<AzureFinOpsCostChartsProps> = ({ reportData, isLoading }) => {
  const serviceContainerRef = useRef<HTMLDivElement>(null);
  const regionChartRef = useRef<HTMLCanvasElement>(null);
  const serviceChartInstance = useRef<Highcharts.Chart | null>(null);
  const regionChartInstance = useRef<any>(null);

  // Variable Radius Pie – Cost by Service
  useEffect(() => {
    if (isLoading || !reportData || !serviceContainerRef.current) return;

    if (serviceChartInstance.current) {
      serviceChartInstance.current.destroy();
      serviceChartInstance.current = null;
    }

    const data = reportData.costBreakdown ?? [];
    if (data.length === 0) return;

    serviceChartInstance.current = Highcharts.chart(serviceContainerRef.current, {
      chart: {
        type: 'variablepie',
        backgroundColor: 'transparent',
        style: { fontFamily: "'Inter', sans-serif" },
        animation: { duration: 600 },
        height: 320,
      },
      title: { text: undefined },
      credits: { enabled: false },
      exporting: { enabled: false },
      colors: VAR_PIE_PALETTE,
      tooltip: {
        headerFormat: '',
        pointFormat: '<b>{point.name}</b><br/>Cost: <b>${point.y:,.2f}</b><br/>Share: <b>{point.percentage:.1f}%</b>',
      },
      plotOptions: {
        variablepie: {
          innerSize: '35%',
          dataLabels: {
            enabled: true,
            format: '{point.name}',
            style: {
              fontSize: '10px',
              fontWeight: '500',
              color: 'rgba(0,0,0,0.65)',
              textOutline: 'none',
            },
            distance: 16,
            connectorColor: 'rgba(0,0,0,0.2)',
          },
          showInLegend: true,
          borderWidth: 2,
          borderColor: '#ffffff',
        },
      },
      legend: {
        layout: 'vertical',
        align: 'right',
        verticalAlign: 'middle',
        width: 190,
        itemStyle: {
          fontSize: '11px',
          fontWeight: '500',
          color: 'rgba(0,0,0,0.6)',
        },
        symbolRadius: 3,
        maxHeight: 280,
      },
      series: [{
        type: 'variablepie',
        name: 'Cost',
        zMin: 0,
        data: data.map((d) => ({ name: d.service, y: d.cost, z: d.cost })),
      }],
    } as Highcharts.Options);

    return () => {
      if (serviceChartInstance.current) {
        serviceChartInstance.current.destroy();
        serviceChartInstance.current = null;
      }
    };
  }, [reportData, isLoading]);

  // Bar – Cost by Region
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
              label: (ctx) =>
                ` ₹${(ctx.raw as number).toLocaleString('en-IN', { maximumFractionDigits: 0 })}`,
            },
          },
        },
        scales: {
          x: {
            grid: { color: gridColor },
            ticks: {
              color: tickColor,
              callback: (v) => `₹${(v as number).toLocaleString('en-IN', { maximumFractionDigits: 0 })}`,
            },
          },
          y: { grid: { display: false }, ticks: { color: tickColor } },
        },
      },
    });

    return () => { regionChartInstance.current?.destroy(); };
  }, [reportData, isLoading]);

  if (isLoading) {
    return (
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {[0, 1].map(i => (
          <div key={i} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 animate-pulse">
            <div className="h-5 bg-[rgba(0,0,0,0.04)] rounded-lg w-1/3 mb-4" />
            <div className="h-64 bg-[rgba(0,0,0,0.04)] rounded-lg" />
          </div>
        ))}
      </div>
    );
  }

  const hasServiceData = (reportData?.costBreakdown ?? []).length > 0;
  const hasRegionData = (reportData?.costByRegion ?? []).length > 0;

  return (
    <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
      {/* Cost by Service – Variable Radius Pie */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 widget-panel-hover">
        <div className="flex items-center gap-3 mb-5">
          <div className="w-7 h-7 flex items-center justify-center rounded-md border border-[rgba(0,0,0,0.06)]" style={{ backgroundColor: '#0078D408' }}>
            <Icon icon="mdi:chart-pie" width={16} color="#0078D4" />
          </div>
          <div>
            <h3 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Cost by Service</h3>
            <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-0.5">Top Azure services this month</p>
          </div>
        </div>
        {hasServiceData ? (
          <div ref={serviceContainerRef} className="w-full" style={{ height: 320 }} />
        ) : (
          <div className="flex flex-col items-center justify-center gap-2" style={{ height: 320 }}>
            <Icon icon="mdi:chart-pie" width={40} color="rgba(0,0,0,0.1)" />
            <p className="text-[13px] text-[rgba(0,0,0,0.4)]">No service cost data available</p>
          </div>
        )}
      </div>

      {/* Cost by Region */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 widget-panel-hover">
        <div className="flex items-center gap-3 mb-5">
          <div className="w-7 h-7 flex items-center justify-center rounded-md border border-[rgba(0,0,0,0.06)]" style={{ backgroundColor: '#F9731614' }}>
            <Icon icon="mdi:earth" width={16} color="#F97316" />
          </div>
          <div>
            <h3 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Cost by Region</h3>
            <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-0.5">Geographic cost distribution</p>
          </div>
        </div>
        {hasRegionData ? (
          <div className="relative" style={{ height: 320 }}>
            <canvas ref={regionChartRef} />
          </div>
        ) : (
          <div className="flex flex-col items-center justify-center gap-2" style={{ height: 320 }}>
            <Icon icon="mdi:earth" width={40} color="rgba(0,0,0,0.1)" />
            <p className="text-[13px] text-[rgba(0,0,0,0.4)]">No region cost data available</p>
          </div>
        )}
      </div>
    </div>
  );
};

export default AzureFinOpsCostCharts;
