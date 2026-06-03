import React, { useEffect, useRef, useState, useCallback } from 'react';
import { AzureCostHistory, AzureBillingItem } from '../../types/azureDashboard';

declare global {
  interface Window { Chart: any; }
}

interface AzureCostSectionProps {
  costHistory: AzureCostHistory;
  billingSummary: AzureBillingItem[];
  isLoading: boolean;
  accountId: string;
}

const SERVICE_COLORS = [
  '#8b5cf6', '#22d3ee', '#f59e0b', '#6b7280', '#10b981',
  '#f97316', '#3b82f6', '#ec4899', '#84cc16', '#14b8a6',
];

interface MonthBreakdown {
  label: string;
  services: { name: string; amount: number }[];
}

const AzureCostSection: React.FC<AzureCostSectionProps> = ({ costHistory, billingSummary, isLoading }) => {
  const chartCanvasRef = useRef<HTMLCanvasElement>(null);
  const chartInstanceRef = useRef<any>(null);
  const tooltipRef = useRef<HTMLDivElement>(null);
  const [breakdownData, setBreakdownData] = useState<MonthBreakdown[]>([]);
  const [breakdownLoading, setBreakdownLoading] = useState(false);

  const formatCurrency = (num: number): string =>
    '₹' + (num != null ? num.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : '0.00');

  const buildBreakdown = useCallback(() => {
    if (!costHistory?.labels?.length) return;
    setBreakdownLoading(true);

    // Use billingSummary to get service proportions, apply to each month's total
    const services = (billingSummary || [])
      .filter(s => s.monthToDateCost > 0)
      .sort((a, b) => b.monthToDateCost - a.monthToDateCost)
      .slice(0, 8);

    const totalCurrentMonth = services.reduce((s, i) => s + i.monthToDateCost, 0);

    const data: MonthBreakdown[] = costHistory.labels.map((label, idx) => {
      const monthTotal = costHistory.costs[idx] || 0;
      if (totalCurrentMonth === 0 || services.length === 0) {
        return { label, services: [{ name: 'Total Cost', amount: monthTotal }] };
      }
      // Scale service proportions to this month's total
      return {
        label,
        services: services.map(s => ({
          name: s.serviceName,
          amount: monthTotal * (s.monthToDateCost / totalCurrentMonth),
        })),
      };
    });

    setBreakdownData(data);
    setBreakdownLoading(false);
  }, [costHistory, billingSummary]);

  useEffect(() => {
    buildBreakdown();
  }, [buildBreakdown]);

  useEffect(() => {
    if (!chartCanvasRef.current || breakdownLoading || breakdownData.length === 0) return;
    const globalAny = globalThis as any;
    if (!globalAny.Chart) return;

    if (chartInstanceRef.current) chartInstanceRef.current.destroy();
    const ctx = chartCanvasRef.current.getContext('2d');
    if (!ctx) return;

    const totals: Record<string, number> = {};
    breakdownData.forEach(month => {
      month.services.forEach(s => {
        totals[s.name] = (totals[s.name] || 0) + s.amount;
      });
    });
    const topServices = Object.entries(totals)
      .sort((a, b) => b[1] - a[1])
      .slice(0, 8)
      .map(([name]) => name);

    const labels = breakdownData.map(m => m.label);
    const serviceActualColors = topServices.map((_s, idx) => SERVICE_COLORS[idx % SERVICE_COLORS.length]);

    const datasets = topServices.map((service, idx) => ({
      label: service,
      data: breakdownData.map(month => {
        const found = month.services.find(s => s.name === service);
        return found ? found.amount : 0;
      }),
      backgroundColor: 'transparent',
      borderWidth: 0,
      borderSkipped: false,
      pointBackgroundColor: serviceActualColors[idx],
    }));

    function drawPill(c: CanvasRenderingContext2D, color: string, x: number, y: number, w: number, bottom: number, radius: number) {
      const rawH = bottom - y;
      if (rawH <= 0) return;
      const gap = Math.min(2, rawH * 0.12);
      const left = x - w / 2;
      const right = x + w / 2;
      const top = y + gap;
      const bot = bottom - gap;
      const h = bot - top;
      if (h < 1) return;
      const r = Math.min(radius, h / 2, w / 2);
      c.save();
      c.fillStyle = color;
      c.beginPath();
      c.moveTo(left + r, top);
      c.arcTo(right, top, right, bot, r);
      c.arcTo(right, bot, left, bot, r);
      c.arcTo(left, bot, left, top, r);
      c.arcTo(left, top, right, top, r);
      c.closePath();
      c.fill();
      c.restore();
    }

    const pillPlugin = {
      id: 'pillBars',
      afterDatasetsDraw(chart: any) {
        const { ctx: c, data, chartArea } = chart;
        if (!chartArea) return;
        const R = 6;
        data.datasets.forEach((_ds: any, dsIdx: number) => {
          const meta = chart.getDatasetMeta(dsIdx);
          if (meta.hidden) return;
          const color = serviceActualColors[dsIdx];
          meta.data.forEach((bar: any, barIdx: number) => {
            const val = data.datasets[dsIdx].data[barIdx];
            if (!val || val <= 0) return;
            const props = bar.getProps(['x', 'y', 'width', 'base'], true);
            const pillW = props.width * 0.38;
            drawPill(c, color, props.x, props.y, pillW, props.base, R);
          });
        });
      },
    };

    chartInstanceRef.current = new globalAny.Chart(ctx, {
      type: 'bar',
      plugins: [pillPlugin],
      data: { labels, datasets },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        categoryPercentage: 0.7,
        barPercentage: 1.0,
        interaction: { mode: 'index' as const, intersect: false },
        animation: {
          duration: 800,
          easing: 'easeOutQuart' as const,
          y: { from: (ctx: any) => ctx.chart.scales.y.getPixelForValue(0) },
        },
        plugins: {
          legend: {
            display: true,
            position: 'top' as const,
            align: 'end' as const,
            labels: {
              boxWidth: 10,
              boxHeight: 10,
              borderRadius: 3,
              useBorderRadius: true,
              padding: 14,
              font: { size: 11 },
              color: 'rgba(0,0,0,0.55)',
              generateLabels: (chart: any) =>
                chart.data.datasets.map((ds: any, i: number) => ({
                  text: ds.label,
                  fillStyle: serviceActualColors[i],
                  strokeStyle: serviceActualColors[i],
                  lineWidth: 0,
                  hidden: chart.getDatasetMeta(i).hidden,
                  datasetIndex: i,
                })),
            },
          },
          tooltip: {
            enabled: false,
            external: (context: any) => {
              const tooltipEl = tooltipRef.current;
              if (!tooltipEl) return;
              const { tooltip } = context;
              if (tooltip.opacity === 0) { tooltipEl.style.opacity = '0'; return; }
              const title = tooltip.title?.[0] || '';
              const lines: string[] = (tooltip.dataPoints || [])
                .filter((p: any) => p.parsed.y > 0)
                .sort((a: any, b: any) => b.parsed.y - a.parsed.y)
                .map((p: any) => {
                  const color = serviceActualColors[p.datasetIndex] || '#888';
                  return `<div style="display:flex;align-items:center;gap:6px;padding:2px 0">
                    <span style="width:8px;height:8px;border-radius:2px;background:${color};flex-shrink:0;display:inline-block"></span>
                    <span style="font-size:12px;color:rgba(0,0,0,0.6);flex:1;white-space:nowrap">${p.dataset.label}</span>
                    <span style="font-size:12px;font-weight:600;color:rgba(0,0,0,0.88);margin-left:8px">${formatCurrency(p.parsed.y)}</span>
                  </div>`;
                });
              tooltipEl.innerHTML = `<div style="font-size:12px;font-weight:600;color:rgba(0,0,0,0.88);margin-bottom:6px">${title}</div>${lines.join('')}`;
              const canvasRect = context.chart.canvas.getBoundingClientRect();
              const containerRect = tooltipEl.parentElement?.getBoundingClientRect();
              if (!containerRect) return;
              let left = canvasRect.left - containerRect.left + tooltip.caretX + 12;
              let top = canvasRect.top - containerRect.top + tooltip.caretY - 10;
              const ttW = tooltipEl.offsetWidth || 220;
              const ttH = tooltipEl.offsetHeight || 100;
              if (left + ttW > containerRect.width) left = left - ttW - 24;
              if (top + ttH > containerRect.height) top = containerRect.height - ttH - 8;
              tooltipEl.style.opacity = '1';
              tooltipEl.style.left = left + 'px';
              tooltipEl.style.top = top + 'px';
            },
          },
        },
        scales: {
          x: {
            stacked: true,
            ticks: { color: 'rgba(0,0,0,0.4)', font: { size: 11 } },
            grid: { display: false },
            border: { display: false },
          },
          y: {
            stacked: true,
            beginAtZero: true,
            ticks: {
              maxTicksLimit: 5,
              callback: (v: any) => '₹' + (v >= 1000 ? (v / 1000).toFixed(1) + 'k' : v),
              color: 'rgba(0,0,0,0.4)',
              font: { size: 11 },
            },
            grid: { color: 'rgba(0,0,0,0.06)' },
            border: { display: false },
          },
        },
      },
    });

    return () => {
      if (chartInstanceRef.current) { chartInstanceRef.current.destroy(); chartInstanceRef.current = null; }
    };
  }, [breakdownData, breakdownLoading]);

  const sortedBilling = billingSummary
    ? [...billingSummary].sort((a, b) => b.monthToDateCost - a.monthToDateCost)
    : [];

  return (
    <>
      {/* Cost Breakdown Chart */}
      <div className="lg:col-span-2 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 h-full widget-panel-hover">
        <div className="flex items-center justify-between mb-5">
          <div>
            <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Cost Breakdown</p>
            <p className="text-[11px] text-[rgba(0,0,0,0.4)] flex items-center gap-1.5 mt-0.5">
              <span className="w-1.5 h-1.5 rounded-full bg-[rgba(0,0,0,0.2)] inline-block"></span>
              6-Month by Service
            </p>
          </div>
        </div>

        {(isLoading || breakdownLoading) ? (
          <div className="h-72 bg-[rgba(0,0,0,0.03)] rounded-lg animate-pulse"></div>
        ) : breakdownData.length === 0 ? (
          <div className="h-72 flex items-center justify-center">
            <p className="text-[13px] text-[rgba(0,0,0,0.35)]">No breakdown data available</p>
          </div>
        ) : (
          <div className="h-72 relative">
            <canvas ref={chartCanvasRef} style={{ width: '100%', height: '100%' }}></canvas>
            <div
              ref={tooltipRef}
              style={{
                position: 'absolute',
                pointerEvents: 'none',
                opacity: 0,
                transition: 'opacity 0.15s',
                background: '#fff',
                border: '1px solid rgba(0,0,0,0.08)',
                borderRadius: '10px',
                padding: '10px 12px',
                boxShadow: '0 4px 16px rgba(0,0,0,0.10)',
                minWidth: '200px',
                zIndex: 10,
              }}
            />
          </div>
        )}
      </div>

      {/* Billing Summary */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 h-full">
        <div className="mb-5">
          <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Billing Summary</p>
          <p className="text-[11px] text-[rgba(0,0,0,0.4)] flex items-center gap-1.5 mt-0.5">
            <span className="w-1.5 h-1.5 rounded-full bg-[rgba(0,0,0,0.2)] inline-block"></span>
            Month-to-Date
          </p>
        </div>

        {isLoading ? (
          <div className="space-y-3">
            {[1, 2, 3, 4, 5].map(i => (
              <div key={i} className="h-[41px] bg-[rgba(0,0,0,0.04)] rounded animate-pulse"></div>
            ))}
          </div>
        ) : (
          <div className="overflow-y-auto inventory-hide-scrollbar" style={{ maxHeight: '340px', scrollbarWidth: 'none' }}>
            <table className="w-full table-fixed">
              <colgroup>
                <col style={{ width: '65%' }} />
                <col style={{ width: '35%' }} />
              </colgroup>
              <thead>
                <tr className="border-b border-[rgba(0,0,0,0.06)]">
                  <th className="pb-2 text-left text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.6)]">Service</th>
                  <th className="pb-2 text-right text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.6)]">Cost (USD)</th>
                </tr>
              </thead>
              <tbody>
                {sortedBilling.length > 0 ? sortedBilling.map((item, index) => (
                  <tr key={index} className="border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.02)] transition-colors">
                    <td className="py-2.5 pr-4">
                      <div className="flex items-center gap-2">
                        <span className="w-2 h-2 rounded-full flex-shrink-0" style={{ backgroundColor: SERVICE_COLORS[index % SERVICE_COLORS.length] }}></span>
                        <span className="text-[14px] font-normal text-[rgba(0,0,0,0.88)] leading-5 truncate max-w-[160px]">{item.serviceName}</span>
                      </div>
                    </td>
                    <td className="py-2.5 text-right">
                      <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">{formatCurrency(item.monthToDateCost)}</span>
                    </td>
                  </tr>
                )) : (
                  <tr>
                    <td colSpan={2} className="py-10 text-center">
                      <p className="text-[13px] text-[rgba(0,0,0,0.35)]">No billing data available</p>
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </>
  );
};

export default AzureCostSection;
