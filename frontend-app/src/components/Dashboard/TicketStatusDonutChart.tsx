/**
 * TicketStatusDonutChart Component
 *
 * Dashboard widget showing an Open vs Closed support-ticket doughnut chart
 * with the raw counts, plus a breakdown of total Technical and total
 * Account & Billing tickets.
 *
 * Tickets live across two backend services (xamops holds Technical, billops
 * holds Account & Billing), so — mirroring TicketsPage — this component fetches
 * both categories from both services, deduplicates by id, and computes the
 * stats client-side. Self-contained via React Query so it renders instantly
 * from cache on repeat visits.
 */

import React, { useEffect, useRef } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Chart, registerables } from 'chart.js';
import { apiFetch } from '../../utils/apiClient';

Chart.register(...registerables);

interface TicketLite {
  id: number;
  status?: string;
  category?: string;
}

export interface TicketStats {
  open: number;
  closed: number;
  total: number;
  technical: number;
  accountBilling: number;
}

const CATEGORIES = ['Technical', 'Account and Billing'];
const SERVICES = ['xamops', 'billops'];

const fetchList = async (url: string): Promise<TicketLite[]> => {
  try {
    const res = await apiFetch(url);
    if (!res.ok) return [];
    const data = await res.json();
    return Array.isArray(data) ? data : [];
  } catch {
    return [];
  }
};

const fetchTicketStats = async (): Promise<TicketStats> => {
  const urls = SERVICES.flatMap((svc) =>
    CATEGORIES.map((cat) => `/api/${svc}/tickets/category/${encodeURIComponent(cat)}`)
  );

  const lists = await Promise.all(urls.map(fetchList));

  // Deduplicate by id (a ticket can be returned by more than one endpoint)
  const seen = new Set<number>();
  const tickets = lists.flat().filter((t) => {
    if (t == null || t.id == null || seen.has(t.id)) return false;
    seen.add(t.id);
    return true;
  });

  let open = 0;
  let closed = 0;
  let technical = 0;
  let accountBilling = 0;

  for (const t of tickets) {
    if ((t.status || '').toUpperCase() === 'CLOSED') closed++;
    else open++;

    if ((t.category || '').toLowerCase().includes('billing')) accountBilling++;
    else technical++;
  }

  return { open, closed, total: tickets.length, technical, accountBilling };
};

export const ticketStatsKey = ['dashboard', 'ticket-stats'] as const;

export const TicketStatusDonutChart: React.FC = () => {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const chartRef = useRef<Chart | null>(null);

  const { data, isLoading, error } = useQuery({
    queryKey: ticketStatsKey,
    queryFn: fetchTicketStats,
    staleTime: 5 * 60 * 1000,
    gcTime: 24 * 60 * 60 * 1000,
    retry: 1,
  });

  const stats: TicketStats = data ?? { open: 0, closed: 0, total: 0, technical: 0, accountBilling: 0 };

  useEffect(() => {
    if (!canvasRef.current) return;
    const ctx = canvasRef.current.getContext('2d');
    if (!ctx) return;

    if (chartRef.current) chartRef.current.destroy();

    const { open, closed, total } = stats;

    const centerTextPlugin = {
      id: 'ticketCenterText',
      beforeDraw(chart: Chart) {
        const { ctx: c, chartArea } = chart;
        if (!chartArea) return;
        c.save();
        const centerX = (chartArea.left + chartArea.right) / 2;
        const centerY = (chartArea.top + chartArea.bottom) / 2;

        c.textAlign = 'center';
        c.textBaseline = 'middle';
        c.font = "bolder 1.9em 'Plus Jakarta Sans', sans-serif";
        c.fillStyle = '#1e293b';
        c.fillText(`${total}`, centerX, centerY - 8);

        c.font = "normal 0.7em 'Plus Jakarta Sans', sans-serif";
        c.fillStyle = '#64748b';
        c.fillText('Total Tickets', centerX, centerY + 14);
        c.restore();
      },
    };

    chartRef.current = new Chart(ctx, {
      type: 'doughnut',
      data: {
        labels: ['Open', 'Closed'],
        datasets: [
          {
            data: [open, closed],
            backgroundColor: ['#059669', '#94a3b8'],
            hoverBackgroundColor: ['#047857', '#64748b'],
            borderWidth: 0,
            hoverOffset: 10,
            borderRadius: 12,
            spacing: 4,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        cutout: '72%',
        animation: { animateRotate: true, animateScale: true, duration: 1200, easing: 'easeOutQuart' },
        layout: { padding: 10 },
        plugins: {
          legend: {
            position: 'bottom',
            labels: {
              usePointStyle: true,
              pointStyle: 'circle',
              padding: 18,
              font: { family: "'Plus Jakarta Sans', sans-serif", size: 12, weight: '600' },
              color: '#64748b',
              generateLabels(chart: Chart) {
                const d = chart.data.datasets[0].data as number[];
                const labels = chart.data.labels as string[];
                const colors = chart.data.datasets[0].backgroundColor as string[];
                return labels.map((label, i) => ({
                  text: `${label} (${d[i] ?? 0})`,
                  fillStyle: colors[i],
                  strokeStyle: 'transparent',
                  hidden: false,
                  index: i,
                  fontColor: '#475569',
                }));
              },
            },
          },
          tooltip: {
            callbacks: {
              label(context) {
                const value = context.parsed as number;
                const pct = total > 0 ? ((value / total) * 100).toFixed(1) : '0';
                return `${context.label}: ${value} (${pct}%)`;
              },
            },
          },
        },
      },
      plugins: [centerTextPlugin],
    }) as Chart;

    return () => {
      if (chartRef.current) chartRef.current.destroy();
    };
  }, [stats.open, stats.closed, stats.total]);

  return (
    <div className="glass-card rounded-2xl p-8 flex flex-col border-t-0 shadow-lg hover:shadow-xl transition-shadow duration-300">
      <div className="flex items-center justify-between mb-4">
        <div>
          <h3 className="text-xl font-bold text-slate-800 flex items-center gap-3">
            <span className="w-10 h-10 rounded-xl bg-[rgba(0,0,0,0.04)] flex items-center justify-center text-[rgba(0,0,0,0.45)] border border-[rgba(0,0,0,0.08)]">
              <i className="fas fa-ticket-alt"></i>
            </span>
            Support Tickets
          </h3>
          <p className="text-sm text-slate-500 mt-1.5 ml-[52px] font-medium">Open vs closed ticket status</p>
        </div>
      </div>

      {error ? (
        <div className="flex-1 flex flex-col items-center justify-center text-center py-10">
          <i className="fas fa-exclamation-triangle text-2xl text-amber-500 mb-3"></i>
          <p className="text-sm text-slate-500">Unable to load ticket data</p>
        </div>
      ) : isLoading ? (
        <div className="flex-1 flex items-center justify-center py-10">
          <div className="w-40 h-40 rounded-full border-8 border-[rgba(0,0,0,0.06)] shimmer"></div>
        </div>
      ) : (
        <>
          <div className="relative w-full flex items-center justify-center py-2 min-h-[240px]">
            <canvas ref={canvasRef}></canvas>
          </div>

          {/* Category breakdown */}
          <div className="grid grid-cols-2 gap-4 mt-4 pt-4 border-t border-[rgba(0,0,0,0.06)]">
            <div className="flex flex-col items-center rounded-xl bg-[rgba(37,99,235,0.05)] py-3">
              <span className="text-[11px] font-semibold uppercase tracking-[0.06em] text-[rgba(0,0,0,0.55)]">Technical</span>
              <span
                className="mt-1 text-slate-800"
                style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '28px', lineHeight: '28px', fontVariantNumeric: 'tabular-nums' }}
              >
                {stats.technical}
              </span>
            </div>
            <div className="flex flex-col items-center rounded-xl bg-[rgba(5,150,105,0.05)] py-3">
              <span className="text-[11px] font-semibold uppercase tracking-[0.06em] text-[rgba(0,0,0,0.55)]">Account & Billing</span>
              <span
                className="mt-1 text-slate-800"
                style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '28px', lineHeight: '28px', fontVariantNumeric: 'tabular-nums' }}
              >
                {stats.accountBilling}
              </span>
            </div>
          </div>
        </>
      )}
    </div>
  );
};
