import React, { useEffect, useRef } from 'react';
import { Icon } from '@iconify/react';
import Chart from 'chart.js/auto';
import Highcharts from '../../lib/highcharts-init';
import type { GcpCostBreakdown } from '../../types/gcpFinops';

const VAR_PIE_PALETTE = [
  '#4caefe', '#3dc3e8', '#2dd9db', '#1feeaf', '#0ff3a0',
  '#00e887', '#23e274', '#35d96f', '#4cd4a0', '#1addc8',
  '#2bcfee', '#0de0b0', '#38e8c8', '#50f0d0', '#18d4e8',
  '#3aefb8', '#29c9e0', '#10f8c0', '#40dce0', '#5af0a8',
];

const formatCurrency = (value: number): string =>
  '₹' + value.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

interface GcpFinOpsChartsProps {
  costBreakdown: GcpCostBreakdown;
}

const GcpFinOpsCharts: React.FC<GcpFinOpsChartsProps> = ({ costBreakdown }) => {
  const serviceContainerRef = useRef<HTMLDivElement>(null);
  const regionChartRef = useRef<HTMLCanvasElement>(null);
  const serviceChartInstance = useRef<Highcharts.Chart | null>(null);
  const regionChartInstance = useRef<Chart<'bar'> | null>(null);

  // Variable Radius Pie – Cost by Service
  useEffect(() => {
    if (!serviceContainerRef.current) return;

    if (serviceChartInstance.current) {
      serviceChartInstance.current.destroy();
      serviceChartInstance.current = null;
    }

    const serviceData = costBreakdown?.byService || [];
    if (serviceData.length === 0) return;

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
        data: serviceData.map((d) => ({ name: d.name, y: d.amount, z: d.amount })),
      }],
    } as Highcharts.Options);

    return () => {
      if (serviceChartInstance.current) {
        serviceChartInstance.current.destroy();
        serviceChartInstance.current = null;
      }
    };
  }, [costBreakdown]);

  // Bar – Cost by Region
  useEffect(() => {
    if (!regionChartRef.current) return;
    regionChartInstance.current?.destroy();

    const regionData = costBreakdown?.byRegion || [];
    if (regionData.length === 0) return;

    const ctx = regionChartRef.current.getContext('2d');
    if (!ctx) return;

    regionChartInstance.current = new Chart(ctx, {
      type: 'bar',
      data: {
        labels: regionData.map((d) => d.name),
        datasets: [{
          label: 'Cost by Region',
          data: regionData.map((d) => d.amount),
          backgroundColor: 'rgba(251, 146, 60, 0.8)',
          borderColor: 'rgba(251, 146, 60, 1)',
          borderWidth: 2,
          borderRadius: 8,
        }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        indexAxis: 'y',
        plugins: {
          legend: { display: false },
          tooltip: {
            callbacks: { label: (ctx) => `Cost: ${formatCurrency(ctx.parsed.x)}` },
          },
        },
      },
    });

    return () => { regionChartInstance.current?.destroy(); };
  }, [costBreakdown]);

  return (
    <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
      {/* Cost by Service – Variable Radius Pie */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5">
        <div className="px-0 pt-0 pb-4 flex items-center gap-3">
          <div className="w-7 h-7 flex items-center justify-center rounded-md border border-[rgba(0,0,0,0.06)]" style={{ backgroundColor: '#4285F414' }}>
            <Icon icon="mdi:chart-pie" width={16} color="#4285F4" />
          </div>
          <div>
            <h3 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Cost by Service</h3>
            <p className="text-[11px] text-[rgba(0,0,0,0.4)] flex items-center gap-1.5 mt-0.5">
              <span className="w-1.5 h-1.5 rounded-full bg-blue-400 animate-pulse inline-block"></span>
              Top GCP services by spend
            </p>
          </div>
        </div>
        {(costBreakdown?.byService?.length ?? 0) === 0 ? (
          <div className="flex flex-col items-center justify-center text-[rgba(0,0,0,0.3)]" style={{ height: 320 }}>
            <Icon icon="mdi:chart-pie" width={40} />
            <p className="text-[13px] mt-3">No service cost data available</p>
          </div>
        ) : (
          <div ref={serviceContainerRef} className="w-full" style={{ height: 320 }} />
        )}
      </div>

      {/* Cost by Region */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5">
        <div className="px-0 pt-0 pb-4 flex items-center gap-3">
          <div className="w-7 h-7 flex items-center justify-center rounded-md border border-[rgba(0,0,0,0.06)]" style={{ backgroundColor: '#EA433514' }}>
            <Icon icon="mdi:earth" width={16} color="#EA4335" />
          </div>
          <div>
            <h3 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Cost by Region</h3>
            <p className="text-[11px] text-[rgba(0,0,0,0.4)] flex items-center gap-1.5 mt-0.5">
              <span className="w-1.5 h-1.5 rounded-full bg-orange-400 animate-pulse inline-block"></span>
              Geographic distribution
            </p>
          </div>
        </div>
        <div className="relative" style={{ height: 320 }}>
          {(costBreakdown?.byRegion?.length ?? 0) === 0 ? (
            <div className="flex flex-col items-center justify-center h-full text-[rgba(0,0,0,0.3)]">
              <Icon icon="mdi:earth" width={40} />
              <p className="text-[13px] mt-3">No region cost data available</p>
            </div>
          ) : (
            <canvas ref={regionChartRef}></canvas>
          )}
        </div>
      </div>
    </div>
  );
};

export default GcpFinOpsCharts;
