import React, { useEffect, useRef, useState, useCallback } from 'react';
import type { CostHistory } from '../../types/liveDashboard';

interface BillingSummaryItem {
  serviceName: string;
  monthToDateCost: number;
}

interface CostSectionProps {
  costHistory: CostHistory;
  billingSummary: BillingSummaryItem[] | any;
  isLoading: boolean;
  accountId: string | null;
}

declare global {
  interface Window { Chart: any; }
}

// Pill-chart palette — matches the Pump.co screenshot style
const SERVICE_COLORS = [
  '#60a5fa', // blue-400
  '#fb923c', // orange-400
  '#a78bfa', // violet-400
  '#34d399', // emerald-400
  '#f472b6', // pink-400
  '#facc15', // yellow-400
  '#2dd4bf', // teal-400
  '#f87171', // red-400
  '#4ade80', // green-400
  '#818cf8', // indigo-400
];


function getLastSixMonths(): { label: string; startDate: string; endDate: string }[] {
  const months = [];
  const now = new Date();
  const todayStr = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
  for (let i = 5; i >= 0; i--) {
    const d = new Date(now.getFullYear(), now.getMonth() - i, 1);
    const year = d.getFullYear();
    const month = d.getMonth();
    const start = `${year}-${String(month + 1).padStart(2, '0')}-01`;
    const isCurrentMonth = year === now.getFullYear() && month === now.getMonth();
    const lastDay = new Date(year, month + 1, 0).getDate();
    const end = isCurrentMonth ? todayStr : `${year}-${String(month + 1).padStart(2, '0')}-${lastDay}`;
    const label = d.toLocaleString('default', { month: 'short', year: '2-digit' });
    months.push({ label, startDate: start, endDate: end });
  }
  return months;
}

interface MonthBreakdown {
  label: string;
  services: { name: string; amount: number }[];
}

const CostSection: React.FC<CostSectionProps> = ({ billingSummary, isLoading, accountId }) => {
  const chartCanvasRef = useRef<HTMLCanvasElement>(null);
  const chartInstanceRef = useRef<any>(null);
  const tooltipRef = useRef<HTMLDivElement>(null);
  const [breakdownData, setBreakdownData] = useState<MonthBreakdown[]>([]);
  const [breakdownLoading, setBreakdownLoading] = useState(false);

  const formatCurrency = (num: number): string =>
    '$' + (num != null ? num.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : '0.00');

  const getBillingSummaryServices = useCallback((): { name: string; amount: number }[] => {
    if (!billingSummary) return [];
    const arr = Array.isArray(billingSummary)
      ? billingSummary
      : billingSummary.services
        ? billingSummary.services.map((s: any) => ({ serviceName: s.serviceName, monthToDateCost: s.cost || s.monthToDateCost || 0 }))
        : [];
    return arr.map((s: any) => ({
      name: s.serviceName || s.name,
      amount: s.monthToDateCost || s.cost || s.amount || 0,
    })).filter((s: any) => s.name && s.amount > 0);
  }, [billingSummary]);

  const fetchBreakdown = useCallback(async () => {
    if (!accountId) return;
    setBreakdownLoading(true);
    const months = getLastSixMonths();
    // Last 5 months from API, current month from billingSummary (already loaded, no extra call)
    const historicalMonths = months.slice(0, 5);
    const currentMonth = months[5];
    try {
      const results = await Promise.all(
        historicalMonths.map(m =>
          fetch(`/api/xamops/costs/breakdown?accountId=${accountId}&groupBy=Service&forceRefresh=false&startDate=${m.startDate}&endDate=${m.endDate}`)
            .then(r => r.ok ? r.json() : [])
            .catch(() => [])
        )
      );
      const historical = historicalMonths.map((m, i) => ({
        label: m.label,
        services: Array.isArray(results[i])
          ? results[i].map((s: any) => ({ name: s.name || s.serviceName, amount: s.amount || s.cost || 0 }))
          : [],
      }));
      // Current month — reuse billingSummary already loaded by the dashboard API
      const currentServices = getBillingSummaryServices();
      setBreakdownData([...historical, { label: currentMonth.label, services: currentServices }]);
    } finally {
      setBreakdownLoading(false);
    }
  }, [accountId, getBillingSummaryServices]);

  useEffect(() => {
    fetchBreakdown();
  }, [fetchBreakdown, billingSummary]);

  // Build stacked chart
  useEffect(() => {
    if (!chartCanvasRef.current || breakdownLoading || breakdownData.length === 0) return;
    const globalAny = globalThis as any;
    if (!globalAny.Chart) return;

    if (chartInstanceRef.current) chartInstanceRef.current.destroy();
    const ctx = chartCanvasRef.current.getContext('2d');
    if (!ctx) return;

    // Collect top N services by total spend across all months
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
    // Store actual colors separately — native bars are transparent, plugin draws pills
    const serviceActualColors = topServices.map((_s, idx) => SERVICE_COLORS[idx % SERVICE_COLORS.length]);

    const datasets = topServices.map((service, idx) => ({
      label: service,
      data: breakdownData.map(month => {
        const found = month.services.find(s => s.name === service);
        return found ? found.amount : 0;
      }),
      // Native bar transparent — pill plugin draws over it
      backgroundColor: 'transparent',
      borderWidth: 0,
      borderSkipped: false,
      // Keep legend color via pointStyle color hack
      pointBackgroundColor: serviceActualColors[idx],
    }));

    function drawPill(c: CanvasRenderingContext2D, color: string, x: number, y: number, w: number, bottom: number, radius: number) {
      const rawH = bottom - y;
      if (rawH <= 0) return;
      // Adaptive gap: proportional to height so tiny segments still render
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

    // afterDatasetsDraw: pills render after bars, tooltip renders after this hook
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
            // Pill width = 38% of allocated bar width for a narrow capsule look
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
          y: {
            from: (ctx: any) => ctx.chart.scales.y.getPixelForValue(0),
          },
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
              if (tooltip.opacity === 0) {
                tooltipEl.style.opacity = '0';
                return;
              }
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
              tooltipEl.innerHTML = `
                <div style="font-size:12px;font-weight:600;color:rgba(0,0,0,0.88);margin-bottom:6px">${title}</div>
                ${lines.join('')}
              `;
              const canvasRect = context.chart.canvas.getBoundingClientRect();
              const containerRect = tooltipEl.parentElement?.getBoundingClientRect();
              if (!containerRect) return;
              let left = canvasRect.left - containerRect.left + tooltip.caretX + 12;
              let top = canvasRect.top - containerRect.top + tooltip.caretY - 10;
              // Keep within bounds
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
              callback: (v: any) => '$' + (v >= 1000 ? (v / 1000).toFixed(1) + 'k' : v),
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
      if (chartInstanceRef.current) {
        chartInstanceRef.current.destroy();
        chartInstanceRef.current = null;
      }
    };
  }, [breakdownData, breakdownLoading]);

  const getBillingSummaryArray = (): BillingSummaryItem[] => {
    if (!billingSummary) return [];
    if (Array.isArray(billingSummary)) return billingSummary;
    if (billingSummary.services && Array.isArray(billingSummary.services)) {
      return billingSummary.services.map((s: any) => ({
        serviceName: s.serviceName,
        monthToDateCost: s.cost || s.monthToDateCost || 0,
      }));
    }
    return [];
  };

  const sortedSummary = [...getBillingSummaryArray()].sort((a, b) => b.monthToDateCost - a.monthToDateCost);

  return (
    <>
      {/* Service Cost Breakdown — 6 months stacked */}
      <div className="lg:col-span-3 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 h-full widget-panel-hover">
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
            {/* HTML tooltip — always above canvas pills */}
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
      <div className="lg:col-span-2 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 h-full widget-panel-hover">
        <div className="mb-5">
          <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Billing Summary</p>
          <p className="text-[11px] text-[rgba(0,0,0,0.4)] flex items-center gap-1.5 mt-0.5">
            <span className="w-1.5 h-1.5 rounded-full bg-[rgba(0,0,0,0.2)] inline-block"></span>
            Month-to-Date
          </p>
        </div>

        {isLoading ? (
          <div className="space-y-3">
            {[1,2,3,4,5].map(i => (
              <div key={i} className="h-[41px] bg-[rgba(0,0,0,0.04)] rounded animate-pulse"></div>
            ))}
          </div>
        ) : (
          <div className="overflow-y-auto inventory-hide-scrollbar" style={{ maxHeight: '340px' }}>
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
                {sortedSummary.length > 0 ? (
                  sortedSummary.map((item, index) => (
                    <tr key={item.serviceName} className="border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.02)] transition-colors">
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
                  ))
                ) : (
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

export default CostSection;
