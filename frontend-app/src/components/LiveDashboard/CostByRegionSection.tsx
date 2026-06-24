import React, { useState, useEffect } from 'react';
import { Icon } from '@iconify/react';
import {
  PieChart, Pie, Cell, Tooltip, ResponsiveContainer,
  BarChart, Bar, XAxis, YAxis, CartesianGrid,
} from 'recharts';

interface CostByRegionSectionProps {
  accountId: string | null;
  timePreset: string;
}

const COLORS = [
  '#3dc3e8','#2dd9db','#1feeaf','#FF9900','#8b5cf6',
  '#f59e0b','#22d3ee','#10b981','#f97316','#3b82f6',
];

const getPresetDates = (preset: string) => {
  const end = new Date();
  const start = new Date();
  if (preset === '7d')       start.setDate(end.getDate() - 7);
  else if (preset === '30d') start.setDate(end.getDate() - 30);
  else if (preset === '3m')  start.setMonth(end.getMonth() - 3);
  else if (preset === '6m')  start.setMonth(end.getMonth() - 6);
  else if (preset === '12m') start.setFullYear(end.getFullYear() - 1);
  const fmt = (d: Date) => d.toISOString().split('T')[0];
  return { start: fmt(start), end: fmt(end) };
};

const fmtShort = (v: number): string => {
  if (v >= 1_000_000) return `$${(v / 1_000_000).toFixed(1)}M`;
  if (v >= 1_000)     return `$${(v / 1_000).toFixed(1)}K`;
  return `$${v.toFixed(0)}`;
};

const ChartTooltip = ({ active, payload }: any) => {
  if (!active || !payload?.length) return null;
  const v: number = payload[0]?.value ?? 0;
  const name: string = payload[0]?.name || '';
  return (
    <div className="bg-white border border-[rgba(0,0,0,0.1)] rounded-xl shadow-lg px-3 py-2">
      <p className="text-[11px] text-[rgba(0,0,0,0.5)] mb-0.5">{name}</p>
      <p className="text-[13px] font-bold text-[rgba(0,0,0,0.88)] tabular-nums">
        {`$${v.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`}
      </p>
    </div>
  );
};

const CostByRegionSection: React.FC<CostByRegionSectionProps> = ({ accountId, timePreset }) => {
  const [data, setData]       = useState<{ name: string; amount: number }[]>([]);
  const [loading, setLoading] = useState(false);
  const [mode, setMode]       = useState<'donut' | 'bar'>('donut');

  useEffect(() => {
    if (!accountId) return;
    const { start, end } = getPresetDates(timePreset);
    setLoading(true);
    fetch(`/api/xamops/finops/cost-by-region?accountId=${accountId}&startDate=${start}&endDate=${end}`,
      { credentials: 'include' })
      .then(r => r.ok ? r.json() : [])
      .then((raw: Array<{ region: string; cost: number }>) => {
        setData(
          raw.map(r => ({ name: r.region || 'Unknown', amount: r.cost }))
            .filter(r => r.name && r.amount > 0)
            .sort((a, b) => b.amount - a.amount)
            .slice(0, 10)
        );
      })
      .catch(() => setData([]))
      .finally(() => setLoading(false));
  }, [accountId, timePreset]);

  const total = data.reduce((s, d) => s + d.amount, 0);

  return (
    <div className="lg:col-span-2 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 flex flex-col widget-panel-hover">
      {/* Header */}
      <div className="flex items-center justify-between mb-3 flex-shrink-0">
        <div className="flex items-center gap-2">
          <div className="w-7 h-7 rounded-lg flex items-center justify-center"
            style={{ background: 'linear-gradient(135deg,#3dc3e820,#2dd9db14)' }}>
            <Icon icon="mdi:earth" width={15} color="#3dc3e8" />
          </div>
          <div>
            <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Cost by Region</p>
            <p className="text-[11px] text-[rgba(0,0,0,0.4)]">
              {fmtShort(total)} across {data.length} region{data.length !== 1 ? 's' : ''}
            </p>
          </div>
        </div>

        <div className="flex items-center bg-[rgba(0,0,0,0.04)] rounded-lg p-0.5 gap-0.5">
          <button onClick={() => setMode('donut')} title="Donut chart"
            className={`p-1.5 rounded-md transition-all ${
              mode === 'donut' ? 'bg-white shadow-sm text-[rgba(0,0,0,0.88)]' : 'text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.7)]'
            }`}>
            <Icon icon="mdi:chart-donut" width={13} />
          </button>
          <button onClick={() => setMode('bar')} title="Bar chart"
            className={`p-1.5 rounded-md transition-all ${
              mode === 'bar' ? 'bg-white shadow-sm text-[rgba(0,0,0,0.88)]' : 'text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.7)]'
            }`}>
            <Icon icon="mdi:chart-bar" width={13} />
          </button>
        </div>
      </div>

      {loading ? (
        <div className="flex-1 flex flex-col gap-2 pt-1">
          <div className="h-44 bg-[rgba(0,0,0,0.04)] rounded-xl animate-pulse" />
          {[1,2,3].map(i => <div key={i} className="h-7 bg-[rgba(0,0,0,0.03)] rounded animate-pulse" />)}
        </div>
      ) : data.length === 0 ? (
        <div className="flex-1 flex flex-col items-center justify-center gap-2">
          <Icon icon="mdi:earth" width={36} color="rgba(0,0,0,0.1)" />
          <p className="text-[12px] text-[rgba(0,0,0,0.35)]">No region data available</p>
        </div>
      ) : mode === 'donut' ? (
        <>
          <div style={{ height: 200 }}>
            <ResponsiveContainer width="100%" height="100%">
              <PieChart>
                <Pie data={data} dataKey="amount" nameKey="name"
                  cx="50%" cy="50%" innerRadius={52} outerRadius={85} paddingAngle={2}>
                  {data.map((_, i) => (
                    <Cell key={i} fill={COLORS[i % COLORS.length]} stroke="#fff" strokeWidth={2} />
                  ))}
                </Pie>
                <Tooltip content={<ChartTooltip />} />
              </PieChart>
            </ResponsiveContainer>
          </div>

          {/* Region list */}
          <div className="flex-1 overflow-y-auto mt-1 space-y-0.5">
            {data.map((d, i) => (
              <div key={d.name} className="flex items-center gap-2.5 py-1.5 border-b border-[rgba(0,0,0,0.04)] last:border-0">
                <span className="w-2.5 h-2.5 rounded-sm flex-shrink-0"
                  style={{ backgroundColor: COLORS[i % COLORS.length] }} />
                <span className="text-[12px] text-[rgba(0,0,0,0.65)] flex-1 truncate">{d.name}</span>
                <div className="text-right flex-shrink-0">
                  <p className="text-[12px] font-semibold text-[rgba(0,0,0,0.8)] tabular-nums">{fmtShort(d.amount)}</p>
                  <p className="text-[9.5px] text-[rgba(0,0,0,0.3)]">{total > 0 ? ((d.amount / total) * 100).toFixed(1) : 0}%</p>
                </div>
              </div>
            ))}
          </div>
        </>
      ) : (
        <div className="flex-1" style={{ minHeight: 200 }}>
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={data} layout="vertical" margin={{ top: 4, right: 16, left: 0, bottom: 4 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="rgba(0,0,0,0.05)" horizontal={false} />
              <XAxis type="number" tickFormatter={fmtShort}
                tick={{ fontSize: 10, fill: 'rgba(0,0,0,0.4)' }} axisLine={false} tickLine={false} />
              <YAxis type="category" dataKey="name" width={100}
                tick={{ fontSize: 10, fill: 'rgba(0,0,0,0.6)' }} axisLine={false} tickLine={false} />
              <Tooltip content={<ChartTooltip />} cursor={{ fill: 'rgba(0,0,0,0.025)' }} />
              <Bar dataKey="amount" radius={[0, 4, 4, 0]}>
                {data.map((_, i) => <Cell key={i} fill={COLORS[i % COLORS.length]} />)}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}
    </div>
  );
};

export default CostByRegionSection;
