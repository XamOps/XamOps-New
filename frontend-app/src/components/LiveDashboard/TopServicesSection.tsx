import React, { useState, useMemo, useEffect } from 'react';
import { Icon } from '@iconify/react';
import {
  BarChart, Bar, Cell, XAxis, YAxis, CartesianGrid, Tooltip,
  PieChart, Pie, Legend, ResponsiveContainer,
} from 'recharts';

type View = 'service' | 'region';
type ChartMode = 'bar' | 'pie';

interface BillingItem  { serviceName: string; monthToDateCost: number; }
interface RegionItem   { region: string; cost: number; }

interface TopServicesSectionProps {
  billingSummary: BillingItem[] | any;
  accountId: string | null;
  timePreset: string;
  isLoading: boolean;
}

const COLORS = [
  '#FF9900','#3dc3e8','#2dd9db','#1feeaf','#8b5cf6',
  '#f59e0b','#22d3ee','#10b981','#6b7280','#f97316',
  '#3b82f6','#ec4899','#84cc16','#14b8a6',
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

const fmt = (v: number): string => {
  if (v >= 1_000_000) return `$${(v / 1_000_000).toFixed(1)}M`;
  if (v >= 1_000)     return `$${(v / 1_000).toFixed(1)}K`;
  return `$${v.toFixed(2)}`;
};

const ChartTooltip = ({ active, payload, label }: any) => {
  if (!active || !payload?.length) return null;
  return (
    <div className="bg-white border border-[rgba(0,0,0,0.1)] rounded-xl shadow-lg px-3.5 py-2.5 min-w-[160px]">
      <p className="text-[12px] font-semibold text-[rgba(0,0,0,0.75)] mb-1.5 truncate">{label || payload[0]?.name}</p>
      <div className="flex items-center gap-2">
        <span className="w-2 h-2 rounded-full flex-shrink-0" style={{ backgroundColor: payload[0]?.fill || payload[0]?.color }} />
        <span className="text-[13px] font-bold text-[rgba(0,0,0,0.88)] tabular-nums">{fmt(payload[0]?.value ?? 0)}</span>
      </div>
    </div>
  );
};

const TopServicesSection: React.FC<TopServicesSectionProps> = ({
  billingSummary, accountId, timePreset, isLoading,
}) => {
  const [view, setView] = useState<View>('service');
  const [mode, setMode] = useState<ChartMode>('bar');
  const [regionData, setRegionData]     = useState<{ name: string; amount: number }[]>([]);
  const [regionLoading, setRegionLoading] = useState(false);

  // Normalise billing summary
  const serviceData = useMemo(() => {
    if (!billingSummary) return [];
    const arr: BillingItem[] = Array.isArray(billingSummary)
      ? billingSummary
      : (billingSummary.services || []).map((s: any) => ({
          serviceName: s.serviceName || s.name,
          monthToDateCost: s.cost || s.monthToDateCost || 0,
        }));
    return arr
      .map(s => ({ name: s.serviceName, amount: s.monthToDateCost }))
      .filter(s => s.name && s.amount > 0)
      .sort((a, b) => b.amount - a.amount)
      .slice(0, 12);
  }, [billingSummary]);

  // Fetch region data when view=region or timePreset changes
  useEffect(() => {
    if (!accountId || view !== 'region') return;
    const { start, end } = getPresetDates(timePreset);
    setRegionLoading(true);
    fetch(`/api/xamops/finops/cost-by-region?accountId=${accountId}&startDate=${start}&endDate=${end}`,
      { credentials: 'include' })
      .then(r => r.ok ? r.json() : [])
      .then((raw: RegionItem[]) => {
        setRegionData(
          raw.map(r => ({ name: r.region || '', amount: r.cost }))
            .filter(r => r.name && r.amount > 0)
            .sort((a, b) => b.amount - a.amount)
            .slice(0, 12)
        );
      })
      .catch(() => setRegionData([]))
      .finally(() => setRegionLoading(false));
  }, [accountId, view, timePreset]);

  const chartData = view === 'service' ? serviceData : regionData;
  const loading   = isLoading || (view === 'region' && regionLoading);
  const total     = chartData.reduce((s, d) => s + d.amount, 0);

  const renderBarChart = () => (
    <ResponsiveContainer width="100%" height={Math.max(200, chartData.length * 36)}>
      <BarChart data={chartData} layout="vertical" margin={{ top: 4, right: 20, left: 0, bottom: 4 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="rgba(0,0,0,0.05)" horizontal={false} />
        <XAxis type="number" tickFormatter={fmt} tick={{ fontSize: 11, fill: 'rgba(0,0,0,0.4)' }}
          axisLine={false} tickLine={false} />
        <YAxis type="category" dataKey="name" width={150}
          tick={{ fontSize: 11, fill: 'rgba(0,0,0,0.65)' }} axisLine={false} tickLine={false} />
        <Tooltip content={<ChartTooltip />} cursor={{ fill: 'rgba(0,0,0,0.025)' }} />
        <Bar dataKey="amount" radius={[0, 5, 5, 0]}>
          {chartData.map((_, i) => <Cell key={i} fill={COLORS[i % COLORS.length]} />)}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  );

  const renderPieChart = () => (
    <ResponsiveContainer width="100%" height={280}>
      <PieChart>
        <Pie data={chartData} dataKey="amount" nameKey="name"
          cx="42%" cy="50%" outerRadius={100} innerRadius={55} paddingAngle={2}>
          {chartData.map((_, i) => (
            <Cell key={i} fill={COLORS[i % COLORS.length]} stroke="#fff" strokeWidth={2} />
          ))}
        </Pie>
        <Tooltip formatter={(v) => fmt(v as number)} />
        <Legend layout="vertical" align="right" verticalAlign="middle"
          wrapperStyle={{ fontSize: 11 }}
          formatter={(v: string) => (
            <span className="text-[11px] text-[rgba(0,0,0,0.6)]">{v}</span>
          )} />
      </PieChart>
    </ResponsiveContainer>
  );

  return (
    <div className="lg:col-span-3 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 flex flex-col widget-panel-hover">
      {/* Header */}
      <div className="flex items-center justify-between mb-4 flex-shrink-0">
        <div className="flex items-center gap-2">
          <div className="w-7 h-7 rounded-lg flex items-center justify-center"
            style={{ background: 'linear-gradient(135deg,#FF990020,#FF6B3514)' }}>
            <Icon icon="mdi:chart-bar" width={15} color="#FF9900" />
          </div>
          <div>
            <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Top {view === 'service' ? 'Services' : 'Regions'}</p>
            <p className="text-[11px] text-[rgba(0,0,0,0.4)]">Cost breakdown · {fmt(total)} total</p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          {/* Service / Region toggle */}
          <div className="flex items-center bg-[rgba(0,0,0,0.04)] rounded-lg p-0.5 gap-0.5">
            <button onClick={() => setView('service')}
              className={`flex items-center gap-1.5 px-2.5 py-1 rounded-md text-[11px] font-medium transition-all ${
                view === 'service' ? 'bg-white text-[rgba(0,0,0,0.88)] shadow-sm' : 'text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.7)]'
              }`}>
              <Icon icon="mdi:layers-outline" width={12} />Service
            </button>
            <button onClick={() => setView('region')}
              className={`flex items-center gap-1.5 px-2.5 py-1 rounded-md text-[11px] font-medium transition-all ${
                view === 'region' ? 'bg-white text-[rgba(0,0,0,0.88)] shadow-sm' : 'text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.7)]'
              }`}>
              <Icon icon="mdi:earth" width={12} />Region
            </button>
          </div>

          {/* Bar / Pie chart mode */}
          <div className="flex items-center bg-[rgba(0,0,0,0.04)] rounded-lg p-0.5 gap-0.5">
            <button onClick={() => setMode('bar')} title="Bar chart"
              className={`p-1.5 rounded-md transition-all ${
                mode === 'bar' ? 'bg-white shadow-sm text-[rgba(0,0,0,0.88)]' : 'text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.7)]'
              }`}>
              <Icon icon="mdi:chart-bar" width={13} />
            </button>
            <button onClick={() => setMode('pie')} title="Pie chart"
              className={`p-1.5 rounded-md transition-all ${
                mode === 'pie' ? 'bg-white shadow-sm text-[rgba(0,0,0,0.88)]' : 'text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.7)]'
              }`}>
              <Icon icon="mdi:chart-pie-outline" width={13} />
            </button>
          </div>
        </div>
      </div>

      {/* Chart */}
      <div className="flex-1 overflow-y-auto" style={{ maxHeight: 340 }}>
        {loading ? (
          <div className="flex flex-col gap-2 pt-1">
            {[1,2,3,4,5,6].map(i => (
              <div key={i} className="h-8 bg-[rgba(0,0,0,0.04)] rounded-lg animate-pulse" />
            ))}
          </div>
        ) : chartData.length === 0 ? (
          <div className="h-48 flex flex-col items-center justify-center gap-2">
            <Icon icon="mdi:chart-bar" width={36} color="rgba(0,0,0,0.12)" />
            <p className="text-[12px] text-[rgba(0,0,0,0.35)]">No data available</p>
          </div>
        ) : mode === 'pie' ? renderPieChart() : renderBarChart()}
      </div>

      {/* Legend row (bar mode only) */}
      {!loading && chartData.length > 0 && mode === 'bar' && (
        <div className="mt-3 pt-3 border-t border-[rgba(0,0,0,0.06)] flex flex-wrap gap-x-4 gap-y-1.5 flex-shrink-0">
          {chartData.slice(0, 8).map((d, i) => (
            <div key={d.name} className="flex items-center gap-1.5">
              <span className="w-2 h-2 rounded-sm flex-shrink-0" style={{ backgroundColor: COLORS[i % COLORS.length] }} />
              <span className="text-[10.5px] text-[rgba(0,0,0,0.5)] truncate max-w-[110px]">{d.name}</span>
            </div>
          ))}
          {chartData.length > 8 && (
            <span className="text-[10.5px] text-[rgba(0,0,0,0.3)]">+{chartData.length - 8} more</span>
          )}
        </div>
      )}
    </div>
  );
};

export default TopServicesSection;
