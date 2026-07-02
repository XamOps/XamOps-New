import React, { useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip,
  ResponsiveContainer, ReferenceLine,
} from 'recharts';
import type { CostHistory } from '../../types/liveDashboard';
import { fmtMoney, fmtMoneyShort } from '../../utils/currency';

interface CostTrendSectionProps {
  costHistory: CostHistory;
  isLoading: boolean;
  provider?: 'AWS' | 'GCP' | 'Azure';
}

type Slice = '3m' | '6m' | '12m' | 'all';

const SLICES: { id: Slice; label: string; months: number }[] = [
  { id: '3m',  label: '3M',  months: 3   },
  { id: '6m',  label: '6M',  months: 6   },
  { id: '12m', label: '12M', months: 12  },
  { id: 'all', label: 'All', months: 999 },
];

const ChartTooltip = ({ active, payload, label, provider }: any) => {
  if (!active || !payload?.length) return null;
  const value: number = payload[0]?.value ?? 0;
  return (
    <div className="bg-white border border-[rgba(0,0,0,0.1)] rounded-xl shadow-lg px-3.5 py-2.5 min-w-[130px]">
      <p className="text-[11px] font-semibold text-[rgba(0,0,0,0.45)] mb-1">{label}</p>
      <p className="text-[16px] font-bold text-[rgba(0,0,0,0.88)] tabular-nums">
        {fmtMoney(value, provider)}
      </p>
    </div>
  );
};

const CostTrendSection: React.FC<CostTrendSectionProps> = ({ costHistory, isLoading, provider }) => {
  const [slice, setSlice] = useState<Slice>('6m');
  const fmt = (v: number) => fmtMoneyShort(v, provider);

  const chartData = useMemo(() => {
    if (!costHistory?.labels?.length) return [];
    const maxN = SLICES.find(s => s.id === slice)!.months;
    const labels = costHistory.labels.slice(-maxN);
    const costs  = (costHistory.costs  || []).slice(-maxN);
    return labels.map((label, i) => ({ label, cost: costs[i] ?? 0 }));
  }, [costHistory, slice]);

  const total   = useMemo(() => chartData.reduce((s, d) => s + d.cost, 0), [chartData]);
  const maxCost = useMemo(() => Math.max(...chartData.map(d => d.cost), 0), [chartData]);
  const avgCost = chartData.length ? total / chartData.length : 0;

  const trend = useMemo(() => {
    if (chartData.length < 2) return null;
    const last = chartData[chartData.length - 1].cost;
    const prev = chartData[chartData.length - 2].cost;
    if (!prev) return null;
    return { diff: last - prev, pct: ((last - prev) / prev) * 100 };
  }, [chartData]);

  if (isLoading) {
    return (
      <div className="lg:col-span-3 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 h-full animate-pulse">
        <div className="h-4 bg-[rgba(0,0,0,0.05)] rounded w-1/4 mb-4" />
        <div className="h-52 bg-[rgba(0,0,0,0.04)] rounded-lg" />
      </div>
    );
  }

  return (
    <div className="lg:col-span-3 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 h-full flex flex-col widget-panel-hover">
      {/* Header */}
      <div className="flex items-center justify-between mb-3 flex-shrink-0">
        <div className="flex items-center gap-2">
          <div className="w-7 h-7 rounded-lg flex items-center justify-center"
            style={{ background: 'linear-gradient(135deg,#FF990020,#FF6B3514)' }}>
            <Icon icon="mdi:chart-areaspline" width={15} color="#FF9900" />
          </div>
          <div>
            <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Cost Trend</p>
            <p className="text-[11px] text-[rgba(0,0,0,0.4)]">Monthly spend history</p>
          </div>
        </div>

        <div className="flex items-center bg-[rgba(0,0,0,0.04)] rounded-lg p-0.5 gap-0.5">
          {SLICES.map(s => (
            <button key={s.id} onClick={() => setSlice(s.id)}
              className={`px-2.5 py-1 rounded-md text-[11px] font-medium transition-all ${
                slice === s.id
                  ? 'bg-white text-[rgba(0,0,0,0.88)] shadow-sm'
                  : 'text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.7)]'
              }`}>{s.label}
            </button>
          ))}
        </div>
      </div>

      {/* KPIs */}
      <div className="flex items-end gap-5 mb-3 flex-shrink-0">
        <div>
          <p className="text-[10px] uppercase tracking-wider font-semibold text-[rgba(0,0,0,0.3)] mb-0.5">Total spend</p>
          <p className="text-[26px] font-bold text-[rgba(0,0,0,0.88)] leading-none tabular-nums">{fmt(total)}</p>
        </div>
        <div className="flex items-end gap-4 pb-0.5">
          <div>
            <p className="text-[10px] uppercase tracking-wider font-semibold text-[rgba(0,0,0,0.28)] mb-0.5">Avg / Mo</p>
            <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.55)] tabular-nums">{fmt(avgCost)}</p>
          </div>
          <div>
            <p className="text-[10px] uppercase tracking-wider font-semibold text-[rgba(0,0,0,0.28)] mb-0.5">Peak</p>
            <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.55)] tabular-nums">{fmt(maxCost)}</p>
          </div>
          {trend && (
            <div className={`flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-bold border ${
              trend.diff >= 0
                ? 'bg-red-50 text-red-500 border-red-100'
                : 'bg-green-50 text-green-600 border-green-100'
            }`}>
              <Icon icon={trend.diff >= 0 ? 'mdi:trending-up' : 'mdi:trending-down'} width={12} />
              {trend.diff >= 0 ? '+' : ''}{trend.pct.toFixed(1)}% MoM
            </div>
          )}
        </div>
      </div>

      {/* Chart */}
      <div className="flex-1" style={{ minHeight: 160 }}>
        {chartData.length === 0 ? (
          <div className="h-full flex flex-col items-center justify-center gap-2">
            <Icon icon="mdi:chart-line" width={36} color="rgba(0,0,0,0.12)" />
            <p className="text-[12px] text-[rgba(0,0,0,0.35)]">No cost history available</p>
          </div>
        ) : (
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={chartData} margin={{ top: 4, right: 4, left: -8, bottom: 0 }}>
              <defs>
                <linearGradient id="trendGrad" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%"  stopColor="#FF9900" stopOpacity={0.18} />
                  <stop offset="95%" stopColor="#FF9900" stopOpacity={0.01} />
                </linearGradient>
              </defs>
              <CartesianGrid strokeDasharray="3 3" stroke="rgba(0,0,0,0.05)" vertical={false} />
              <XAxis dataKey="label" tick={{ fontSize: 11, fill: 'rgba(0,0,0,0.4)' }}
                axisLine={false} tickLine={false} />
              <YAxis tickFormatter={fmt} tick={{ fontSize: 10, fill: 'rgba(0,0,0,0.35)' }}
                axisLine={false} tickLine={false} width={50} />
              <Tooltip content={<ChartTooltip provider={provider} />} />
              {avgCost > 0 && (
                <ReferenceLine y={avgCost} stroke="rgba(0,0,0,0.15)" strokeDasharray="4 3"
                  label={{ value: 'avg', position: 'insideTopRight', fontSize: 9, fill: 'rgba(0,0,0,0.3)' }} />
              )}
              <Area type="monotone" dataKey="cost" stroke="#FF9900" strokeWidth={2.5}
                fill="url(#trendGrad)"
                dot={{ r: 3, fill: '#FF9900', stroke: '#fff', strokeWidth: 2 }}
                activeDot={{ r: 5, fill: '#FF9900', stroke: '#fff', strokeWidth: 2 }} />
            </AreaChart>
          </ResponsiveContainer>
        )}
      </div>
    </div>
  );
};

export default CostTrendSection;
