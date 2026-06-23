import React, { useState, useMemo, useEffect } from 'react';
import { Icon } from '@iconify/react';
import {
  BarChart, Bar, LineChart, Line, PieChart, Pie, Cell,
  XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer,
} from 'recharts';
import type { GcpCostBreakdown, GcpCostAnomaly, DateFilter } from '../../types/gcpFinops';

const CHART_COLORS = [
  '#4caefe', '#3dc3e8', '#2dd9db', '#1feeaf', '#0ff3a0',
  '#00e887', '#23e274', '#35d96f', '#4cd4a0', '#1addc8',
  '#2bcfee', '#0de0b0', '#38e8c8', '#50f0d0', '#18d4e8',
];

const fmtCurrency = (v: number): string => {
  if (v >= 1_000_000) return `$${(v / 1_000_000).toFixed(1)}M`;
  if (v >= 1_000) return `$${(v / 1_000).toFixed(1)}K`;
  return `$${v.toFixed(2)}`;
};

const fmtDate = (d: Date) => d.toISOString().split('T')[0];

const getPresetDates = (preset: string): { start: string; end: string } => {
  const end = new Date();
  const start = new Date();
  if (preset === '7d')  start.setDate(end.getDate() - 7);
  else if (preset === '30d') start.setDate(end.getDate() - 30);
  else if (preset === '3m')  start.setMonth(end.getMonth() - 3);
  else if (preset === '6m')  start.setMonth(end.getMonth() - 6);
  else if (preset === '12m') start.setFullYear(end.getFullYear() - 1);
  return { start: fmtDate(start), end: fmtDate(end) };
};

// Return the same-duration window immediately before currentStart (no overlap)
const getCompareDates = (currentStart: Date, currentEnd: Date): { start: string; end: string } => {
  const duration = currentEnd.getTime() - currentStart.getTime();
  const cmpEnd   = new Date(currentStart.getTime() - 86_400_000); // day before current start
  const cmpStart = new Date(cmpEnd.getTime() - duration);
  return { start: fmtDate(cmpStart), end: fmtDate(cmpEnd) };
};

const DIMENSIONS = [
  { id: 'service',     label: 'Service',      icon: 'mdi:layers-outline',       example: 'Compute Engine, Cloud Storage' },
  { id: 'region',      label: 'Region',       icon: 'mdi:earth',                example: 'us-central1, europe-west1' },
  { id: 'resource',    label: 'Resource',     icon: 'mdi:cube-outline',          example: 'my-vm, my-bucket' },
  { id: 'sku',         label: 'SKU',          icon: 'mdi:pound',                 example: 'N1 Predefined Instance Core' },
  { id: 'usageType',   label: 'Usage Type',   icon: 'mdi:chart-line-variant',    example: 'OnDemand, Preemptible' },
  { id: 'chargeType',  label: 'Charge Type',  icon: 'mdi:credit-card-outline',   example: 'USAGE, CREDIT' },
  { id: 'commitment',  label: 'Commitment',   icon: 'mdi:shield-check-outline',  example: 'On-Demand, 1-Year CUD' },
  { id: 'marketplace', label: 'Marketplace',  icon: 'mdi:storefront-outline',    example: 'GCP Marketplace' },
];

const COMPARE_OPTIONS = [
  { id: 'prev7d',  label: 'Previous 7 days' },
  { id: 'prev30d', label: 'Previous 30 days' },
  { id: 'prev3m',  label: 'Previous 3 months' },
  { id: 'prev1y',  label: 'Previous year' },
];

const COMPARE_TO_PRESET: Record<string, TimePreset> = {
  prev7d:  '7d',
  prev30d: '30d',
  prev3m:  '3m',
  prev1y:  '12m',
};

const CHART_TYPES = [
  { id: 'bar',  icon: 'mdi:chart-bar',     label: 'Bar',  desc: 'Bar charts compare cost by dimension' },
  { id: 'line', icon: 'mdi:chart-line',    label: 'Line', desc: 'Line charts display trends over time' },
  { id: 'pie',  icon: 'mdi:chart-pie',     label: 'Pie',  desc: 'Pie charts show proportional breakdown' },
];

type ChartType = 'bar' | 'line' | 'pie';
type BreakdownDim = typeof DIMENSIONS[number]['id'];
type TimePreset = '7d' | '30d' | '3m' | '6m' | '12m' | 'custom';
type QueryTab = 'query' | 'compare' | 'anomalies';
type MergedItem = { name: string; current: number; previous?: number };

interface Props {
  costBreakdown: GcpCostBreakdown;
  costAnomalies?: GcpCostAnomaly[];
  selectedAccountId?: string | null;
  dateFilter?: DateFilter;
  onTimePresetChange?: (startDate: string, endDate: string) => void;
}

const ChartTooltip = ({ active, payload, label }: any) => {
  if (!active || !payload?.length) return null;
  const hasCompare = payload.length > 1 && payload[1]?.value != null;
  return (
    <div className="bg-white border border-[rgba(0,0,0,0.1)] rounded-xl shadow-lg px-3.5 py-2.5 min-w-[160px]">
      <p className="text-[12px] font-semibold text-[rgba(0,0,0,0.88)] mb-2">{label || payload[0]?.name}</p>
      <div className="flex items-center gap-2 mb-1">
        <span className="w-2 h-2 rounded-full flex-shrink-0" style={{ backgroundColor: payload[0]?.fill || payload[0]?.color }} />
        <span className="text-[11px] text-[rgba(0,0,0,0.5)]">Current</span>
        <span className="text-[12px] font-semibold text-[rgba(0,0,0,0.88)] ml-auto tabular-nums">{fmtCurrency(payload[0]?.value ?? 0)}</span>
      </div>
      {hasCompare && (
        <div className="flex items-center gap-2">
          <span className="w-2 h-2 rounded-full bg-[rgba(0,0,0,0.18)] flex-shrink-0" />
          <span className="text-[11px] text-[rgba(0,0,0,0.5)]">Previous</span>
          <span className="text-[12px] font-semibold text-[rgba(0,0,0,0.55)] ml-auto tabular-nums">{fmtCurrency(payload[1]?.value ?? 0)}</span>
        </div>
      )}
      {hasCompare && payload[0]?.value != null && payload[1]?.value != null && (() => {
        const diff = payload[0].value - payload[1].value;
        const pct = payload[1].value > 0 ? ((diff / payload[1].value) * 100).toFixed(1) : null;
        const up = diff >= 0;
        return (
          <div className="mt-2 pt-2 border-t border-[rgba(0,0,0,0.07)] flex items-center gap-1.5">
            <Icon icon={up ? 'mdi:trending-up' : 'mdi:trending-down'} width={13} color={up ? '#ef4444' : '#22c55e'} />
            <span className={`text-[11px] font-semibold ${up ? 'text-red-500' : 'text-green-500'}`}>
              {up ? '+' : ''}{pct ? `${pct}%` : fmtCurrency(Math.abs(diff))}
            </span>
            <span className="text-[11px] text-[rgba(0,0,0,0.38)]">vs previous</span>
          </div>
        );
      })()}
    </div>
  );
};

const GcpFinOpsCharts: React.FC<Props> = ({
  costBreakdown, costAnomalies = [], selectedAccountId, onTimePresetChange,
}) => {
  const [chartType, setChartType]           = useState<ChartType>('bar');
  const [breakdown, setBreakdown]           = useState<BreakdownDim>('service');
  const [timePreset, setTimePreset]         = useState<TimePreset>('30d');
  const [granularity, setGranularity]       = useState('Day');
  const [showPanel, setShowPanel]           = useState(true);
  const [queryTab, setQueryTab]             = useState<QueryTab>('query');
  const [compareOption, setCompareOption]   = useState<string | null>(null);
  const [compareOpen, setCompareOpen]       = useState(false);
  const [granOpen, setGranOpen]             = useState(false);
  const [bdOpen, setBdOpen]                 = useState(false);
  const [bdSearch, setBdSearch]             = useState('');
  const [filterOpen, setFilterOpen]         = useState(false);
  const [compareCurrent, setCompareCurrent] = useState<{ name: string; amount: number }[]>([]);
  const [compareData, setCompareData]       = useState<{ name: string; amount: number }[]>([]);
  const [compareLoading, setCompareLoading] = useState(false);
  const [compareDateRange, setCompareDateRange] = useState<{ start: string; end: string } | null>(null);
  const [currentDateRange, setCurrentDateRange] = useState<{ start: string; end: string } | null>(null);

  // Selecting compare fetches its own data — never triggers a parent reload (avoids unmounting)
  const handleCompareSelect = (optId: string) => {
    const newOpt = compareOption === optId ? null : optId;
    setCompareOption(newOpt);
    setCompareOpen(false);
    if (newOpt && COMPARE_TO_PRESET[newOpt]) {
      setTimePreset(COMPARE_TO_PRESET[newOpt]);
    }
    if (!newOpt) {
      setCompareCurrent([]);
      setCompareData([]);
      setCompareDateRange(null);
    }
  };

  useEffect(() => {
    const close = () => { setCompareOpen(false); setGranOpen(false); setBdOpen(false); setFilterOpen(false); };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, []);

  type GcpBreakdownPayload = { costBreakdown?: { byService?: { name: string; amount: number }[]; byRegion?: { name: string; amount: number }[] } };

  // Compare mode: fetch both current + previous from GCP report (no parent reload)
  useEffect(() => {
    if (!compareOption || !selectedAccountId) {
      setCompareData([]); setCompareCurrent([]); setCompareDateRange(null); setCurrentDateRange(null); return;
    }

    const preset = COMPARE_TO_PRESET[compareOption] || '30d';
    const { start: currStart, end: currEnd } = getPresetDates(preset);
    const { start: prevStart, end: prevEnd } = getCompareDates(new Date(currStart), new Date(currEnd));
    setCurrentDateRange({ start: currStart, end: currEnd });
    setCompareDateRange({ start: prevStart, end: prevEnd });
    setCompareLoading(true);
    const ctrl = new AbortController();

    const fetchPeriod = (s: string, e: string) =>
      fetch(`/api/xamops/gcp/finops/report?accountId=${selectedAccountId}&startDate=${s}&endDate=${e}`,
        { credentials: 'include', signal: ctrl.signal })
        .then(r => r.ok ? r.json() : Promise.resolve({}));

    const extract = (data: GcpBreakdownPayload) => {
      const raw = breakdown === 'region'
        ? (data?.costBreakdown?.byRegion || [])
        : (data?.costBreakdown?.byService || []);
      return raw.filter(d => d.amount > 0).sort((a, b) => b.amount - a.amount).slice(0, 15);
    };

    Promise.all([fetchPeriod(currStart, currEnd), fetchPeriod(prevStart, prevEnd)])
      .then(([currData, prevData]) => {
        setCompareCurrent(extract(currData as GcpBreakdownPayload));
        setCompareData(extract(prevData as GcpBreakdownPayload));
      })
      .catch(() => { setCompareCurrent([]); setCompareData([]); })
      .finally(() => setCompareLoading(false));

    return () => ctrl.abort();
  }, [compareOption, selectedAccountId, breakdown]);

  // Current period chart data — use self-fetched compareCurrent when compare is active
  const chartData = useMemo(() => {
    if (compareOption && compareCurrent.length) return compareCurrent;
    const raw = breakdown === 'region'
      ? (costBreakdown?.byRegion || [])
      : (costBreakdown?.byService || []);
    return raw.filter(d => d.amount > 0).sort((a, b) => b.amount - a.amount).slice(0, 15);
  }, [costBreakdown, breakdown, compareOption, compareCurrent]);

  const mergedData = useMemo<MergedItem[]>(() => {
    if (!compareData.length) return chartData.map(d => ({ name: d.name, current: d.amount }));
    const cmpMap = new Map(compareData.map(d => [d.name, d.amount]));
    return chartData.map(d => ({ name: d.name, current: d.amount, previous: cmpMap.get(d.name) ?? 0 }));
  }, [chartData, compareData]);

  const total         = useMemo(() => chartData.reduce((s, d) => s + d.amount, 0), [chartData]);
  const compareTotal  = useMemo(() => compareData.reduce((s, d) => s + d.amount, 0), [compareData]);
  const compareDiff   = compareData.length ? total - compareTotal : null;
  const compareDiffPct = compareDiff != null && compareTotal > 0
    ? ((compareDiff / compareTotal) * 100).toFixed(1) : null;
  const hasCompare    = compareData.length > 0;

  const activeDim    = DIMENSIONS.find(d => d.id === breakdown)!;
  const filteredDims = DIMENSIONS.filter(d => d.label.toLowerCase().includes(bdSearch.toLowerCase()));

  const handlePreset = (preset: TimePreset) => {
    // Manually picking a preset exits compare mode
    if (compareOption) { setCompareOption(null); setCompareCurrent([]); setCompareData([]); setCompareDateRange(null); }
    if (preset === 'custom') { setTimePreset(preset); return; }
    setTimePreset(preset);
    if (onTimePresetChange) {
      const { start, end } = getPresetDates(preset);
      onTimePresetChange(start, end);
    }
  };

  const hasData = chartData.length > 0;

  const renderChart = () => {
    if (!hasData) {
      return (
        <div className="flex flex-col items-center justify-center h-64 text-[rgba(0,0,0,0.2)]">
          <Icon icon="mdi:chart-bar-stacked" width={52} />
          <p className="text-[14px] font-semibold mt-3 text-[rgba(0,0,0,0.35)]">No data available</p>
          <p className="text-[12px] mt-1 text-[rgba(0,0,0,0.3)]">Select an account or adjust the date range</p>
        </div>
      );
    }

    if (chartType === 'pie') {
      return (
        <ResponsiveContainer width="100%" height={340}>
          <PieChart>
            <Pie data={mergedData} cx="42%" cy="50%" innerRadius={72} outerRadius={118}
              dataKey="current" nameKey="name" paddingAngle={2}>
              {mergedData.map((_, i) => (
                <Cell key={i} fill={CHART_COLORS[i % CHART_COLORS.length]} stroke="#fff" strokeWidth={2} />
              ))}
            </Pie>
            <Tooltip content={<ChartTooltip />} />
            <Legend layout="vertical" align="right" verticalAlign="middle"
              wrapperStyle={{ fontSize: 11, paddingLeft: 16 }}
              formatter={(v: string) => <span className="text-[11px] text-[rgba(0,0,0,0.65)]">{v}</span>} />
          </PieChart>
        </ResponsiveContainer>
      );
    }

    if (chartType === 'line') {
      const top8 = mergedData.slice(0, 8);
      return (
        <ResponsiveContainer width="100%" height={340}>
          <LineChart data={top8} margin={{ top: 8, right: 20, left: 10, bottom: 60 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="rgba(0,0,0,0.05)" vertical={false} />
            <XAxis dataKey="name" tick={{ fontSize: 11, fill: 'rgba(0,0,0,0.45)' }}
              angle={-30} textAnchor="end" interval={0} />
            <YAxis tick={{ fontSize: 11, fill: 'rgba(0,0,0,0.4)' }} tickFormatter={fmtCurrency}
              axisLine={false} tickLine={false} />
            <Tooltip content={<ChartTooltip />} />
            <Line type="monotone" dataKey="current" name="Current" stroke="#4caefe" strokeWidth={2.5}
              dot={(props: any) => {
                const { cx, cy, index } = props;
                return <circle key={index} cx={cx} cy={cy} r={4} fill={CHART_COLORS[index % CHART_COLORS.length]} stroke="#fff" strokeWidth={2} />;
              }} />
            {hasCompare && (
              <Line type="monotone" dataKey="previous" name="Previous" stroke="rgba(0,0,0,0.2)"
                strokeWidth={2} strokeDasharray="5 3"
                dot={(props: any) => {
                  const { cx, cy, index } = props;
                  return <circle key={index} cx={cx} cy={cy} r={3} fill="rgba(0,0,0,0.2)" stroke="#fff" strokeWidth={1.5} />;
                }} />
            )}
          </LineChart>
        </ResponsiveContainer>
      );
    }

    // Default: horizontal bar with optional ghost comparison bars
    return (
      <ResponsiveContainer width="100%" height={Math.max(260, mergedData.length * (hasCompare ? 52 : 38))}>
        <BarChart data={mergedData} layout="vertical" margin={{ top: 4, right: 24, left: 0, bottom: 4 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="rgba(0,0,0,0.05)" horizontal={false} />
          <XAxis type="number" tickFormatter={fmtCurrency}
            tick={{ fontSize: 11, fill: 'rgba(0,0,0,0.4)' }} axisLine={false} tickLine={false} />
          <YAxis type="category" dataKey="name" width={148}
            tick={{ fontSize: 12, fill: 'rgba(0,0,0,0.65)' }} axisLine={false} tickLine={false} />
          <Tooltip content={<ChartTooltip />} cursor={{ fill: 'rgba(0,0,0,0.025)' }} />
          <Bar dataKey="current" name="Current" radius={[0, 5, 5, 0]}>
            {mergedData.map((_, i) => (
              <Cell key={i} fill={CHART_COLORS[i % CHART_COLORS.length]} />
            ))}
          </Bar>
          {hasCompare && (
            <Bar dataKey="previous" name="Previous" radius={[0, 5, 5, 0]}
              fill="rgba(0,0,0,0.1)" stroke="rgba(0,0,0,0.08)" strokeWidth={1} />
          )}
        </BarChart>
      </ResponsiveContainer>
    );
  };

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.1)] rounded-2xl shadow-sm overflow-hidden">

      {/* ── Card header ── */}
      <div className="px-5 pt-5 pb-4 border-b border-[rgba(0,0,0,0.06)]">
        <div className="flex items-center gap-3 mb-4">
          <div className="w-8 h-8 rounded-lg flex items-center justify-center"
            style={{ background: 'linear-gradient(135deg,#4caefe22,#2dd9db22)' }}>
            <Icon icon="mdi:chart-areaspline" width={18} color="#4caefe" />
          </div>
          <div>
            <h3 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Cost Explorer</h3>
            <p className="text-[11px] text-[rgba(0,0,0,0.4)]">Interactive cost breakdown · powered by GCP Billing</p>
          </div>
        </div>

        {/* ── Toolbar ── */}
        <div className="flex items-center justify-between flex-wrap gap-3">

          {/* Left: time presets + granularity + compare */}
          <div className="flex items-center gap-2 flex-wrap">

            {/* Time preset pill group */}
            <div className="flex items-center bg-[rgba(0,0,0,0.04)] rounded-lg p-0.5 gap-0.5">
              {(['7d','30d','3m','6m','12m'] as TimePreset[]).map(p => (
                <button key={p} onClick={() => handlePreset(p)}
                  className={`px-2.5 py-1 rounded-md text-[12px] font-medium transition-all ${
                    timePreset === p
                      ? 'bg-white text-[rgba(0,0,0,0.88)] shadow-sm'
                      : 'text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.7)]'
                  }`}>{p.toUpperCase()}</button>
              ))}
            </div>

            {/* Granularity */}
            <div className="relative" onMouseDown={e => e.stopPropagation()}>
              <button onClick={() => setGranOpen(!granOpen)}
                className="flex items-center gap-1.5 px-3 py-1.5 text-[12px] font-medium text-[rgba(0,0,0,0.6)] border border-[rgba(0,0,0,0.1)] rounded-lg hover:bg-[rgba(0,0,0,0.03)] transition-colors">
                {granularity} <Icon icon="mdi:chevron-down" width={13} />
              </button>
              {granOpen && (
                <div className="absolute top-full left-0 mt-1 bg-white border border-[rgba(0,0,0,0.1)] rounded-xl shadow-lg py-1 min-w-[100px] z-30">
                  {['Day','Week','Month'].map(g => (
                    <button key={g} onClick={() => { setGranularity(g); setGranOpen(false); }}
                      className={`w-full text-left px-3 py-1.5 text-[12px] hover:bg-[rgba(0,0,0,0.04)] ${
                        granularity === g ? 'font-semibold text-[rgba(0,0,0,0.88)]' : 'text-[rgba(0,0,0,0.6)]'
                      }`}>{g}</button>
                  ))}
                </div>
              )}
            </div>

            {/* Compare */}
            <div className="relative" onMouseDown={e => e.stopPropagation()}>
              <button onClick={() => setCompareOpen(!compareOpen)}
                className={`flex items-center gap-1.5 px-3 py-1.5 text-[12px] font-medium border rounded-lg transition-colors ${
                  compareOption
                    ? 'border-[#4caefe] text-[#4caefe] bg-[#4caefe0d]'
                    : 'border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.03)]'
                }`}>
                <Icon icon="mdi:compare" width={14} />
                {compareLoading
                  ? <span className="flex items-center gap-1">Fetching… <Icon icon="mdi:loading" width={12} className="animate-spin" /></span>
                  : compareOption ? COMPARE_OPTIONS.find(o => o.id === compareOption)?.label : 'Compare'
                }
                <Icon icon="mdi:chevron-down" width={13} />
              </button>
              {compareOpen && (
                <div className="absolute top-full left-0 mt-1 bg-white border border-[rgba(0,0,0,0.1)] rounded-xl shadow-lg py-1.5 min-w-[190px] z-30">
                  {compareOption && (
                    <button onClick={() => { setCompareOption(null); setCompareOpen(false); }}
                      className="w-full text-left px-3 py-1.5 text-[12px] text-red-500 hover:bg-[rgba(0,0,0,0.04)] border-b border-[rgba(0,0,0,0.06)] mb-1">
                      Remove comparison
                    </button>
                  )}
                  {COMPARE_OPTIONS.map(opt => (
                    <button key={opt.id} onClick={() => handleCompareSelect(opt.id)}
                      className={`w-full text-left px-3 py-2 text-[12px] hover:bg-[rgba(0,0,0,0.04)] flex items-center justify-between ${
                        compareOption === opt.id ? 'font-semibold text-[rgba(0,0,0,0.88)]' : 'text-[rgba(0,0,0,0.65)]'
                      }`}>
                      {opt.label}
                      {compareOption === opt.id && <Icon icon="mdi:check" width={13} />}
                    </button>
                  ))}
                  <div className="px-3 pt-1.5 pb-1 border-t border-[rgba(0,0,0,0.06)] mt-1 flex items-start gap-1.5">
                    <Icon icon="mdi:information-outline" width={11} className="text-[rgba(0,0,0,0.3)] mt-[1px] flex-shrink-0" />
                    <p className="text-[10px] text-[rgba(0,0,0,0.35)] leading-tight">Selecting a comparison period will call the GCP Billing API for that date range if not already cached.</p>
                  </div>
                </div>
              )}
            </div>
          </div>

          {/* Right: chart type + query panel toggle */}
          <div className="flex items-center gap-2">
            <div className="flex items-center bg-[rgba(0,0,0,0.04)] rounded-lg p-0.5 gap-0.5">
              {CHART_TYPES.map(ct => (
                <button key={ct.id} onClick={() => setChartType(ct.id as ChartType)} title={ct.desc}
                  className={`flex items-center gap-1.5 px-2.5 py-1.5 rounded-md text-[12px] font-medium transition-all ${
                    chartType === ct.id
                      ? 'bg-white text-[rgba(0,0,0,0.88)] shadow-sm'
                      : 'text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.7)]'
                  }`}>
                  <Icon icon={ct.icon} width={14} />{ct.label}
                </button>
              ))}
            </div>
            <button onClick={() => setShowPanel(!showPanel)}
              className={`flex items-center gap-1.5 px-3 py-1.5 text-[12px] font-medium rounded-lg border transition-all ${
                showPanel
                  ? 'bg-[rgba(0,0,0,0.88)] text-white border-transparent'
                  : 'border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.03)]'
              }`}>
              <Icon icon="mdi:filter-variant" width={14} />
              Query
            </button>
          </div>
        </div>
      </div>

      {/* ── Body (chart + query panel) ── */}
      <div className="flex" style={{ minHeight: 400 }}>

        {/* Chart area */}
        <div className="flex-1 p-5 min-w-0 flex flex-col">
          {/* Summary */}
          <div className="flex items-end gap-5 mb-1 flex-wrap">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.35)] mb-0.5">Amortized Costs</p>
              <p className="text-[30px] font-bold text-[rgba(0,0,0,0.88)] leading-none tabular-nums">{fmtCurrency(total)}</p>
            </div>
            {hasCompare && !compareLoading && (
              <div className="mb-1 flex items-center gap-3 px-3 py-1.5 bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.07)] rounded-xl">
                <div>
                  <p className="text-[10px] text-[rgba(0,0,0,0.38)] font-medium">
                    vs {COMPARE_OPTIONS.find(o => o.id === compareOption)?.label}
                  </p>
                  <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.55)] tabular-nums">{fmtCurrency(compareTotal)}</p>
                </div>
                {compareDiff != null && (
                  <div className={`flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-bold ${
                    compareDiff >= 0
                      ? 'bg-red-50 text-red-500 border border-red-100'
                      : 'bg-green-50 text-green-600 border border-green-100'
                  }`}>
                    <Icon icon={compareDiff >= 0 ? 'mdi:trending-up' : 'mdi:trending-down'} width={13} />
                    {compareDiff >= 0 ? '+' : ''}{compareDiffPct ? `${compareDiffPct}%` : fmtCurrency(Math.abs(compareDiff))}
                  </div>
                )}
              </div>
            )}
            {compareLoading && (
              <div className="mb-1 flex items-center gap-2 px-3 py-1.5 bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.07)] rounded-xl">
                <Icon icon="mdi:loading" width={14} className="animate-spin text-[rgba(0,0,0,0.35)]" />
                <span className="text-[12px] text-[rgba(0,0,0,0.4)]">Loading comparison…</span>
              </div>
            )}
          </div>
          <p className="text-[11px] text-[rgba(0,0,0,0.35)] mb-5">
            Breakdown by <span className="font-medium text-[rgba(0,0,0,0.55)]">{activeDim.label}</span>
            {' '}· <span className="font-medium">{chartData.length}</span> items · <span className="font-medium">{granularity.toLowerCase()} view</span>
            {compareDateRange && currentDateRange && hasCompare && (
              <span className="ml-2 text-[rgba(0,0,0,0.3)]">
                · {currentDateRange.start} → {currentDateRange.end} <span className="text-[rgba(0,0,0,0.2)]">vs</span> {compareDateRange.start} → {compareDateRange.end}
              </span>
            )}
          </p>

          {/* Chart */}
          <div className="flex-1">
            {renderChart()}
          </div>

          {hasData && chartType !== 'pie' && (
            <div className="mt-4 flex flex-wrap gap-x-4 gap-y-1.5">
              {chartData.slice(0, 8).map((d, i) => (
                <div key={d.name} className="flex items-center gap-1.5">
                  <span className="w-2.5 h-2.5 rounded-sm flex-shrink-0"
                    style={{ backgroundColor: CHART_COLORS[i % CHART_COLORS.length] }} />
                  <span className="text-[11px] text-[rgba(0,0,0,0.55)] truncate max-w-[130px]">{d.name}</span>
                </div>
              ))}
              {hasCompare && (
                <div className="flex items-center gap-1.5">
                  <span className="w-2.5 h-2.5 rounded-sm bg-[rgba(0,0,0,0.12)] flex-shrink-0 border border-[rgba(0,0,0,0.1)]" />
                  <span className="text-[11px] text-[rgba(0,0,0,0.4)]">{COMPARE_OPTIONS.find(o => o.id === compareOption)?.label}</span>
                </div>
              )}
              {chartData.length > 8 && (
                <span className="text-[11px] text-[rgba(0,0,0,0.35)]">+{chartData.length - 8} more</span>
              )}
            </div>
          )}
        </div>

        {/* ── Query Panel ── */}
        {showPanel && (
          <div className="w-[272px] flex-shrink-0 border-l border-[rgba(0,0,0,0.07)] flex flex-col">

            {/* Tabs */}
            <div className="flex border-b border-[rgba(0,0,0,0.07)]">
              {(['query','compare','anomalies'] as QueryTab[]).map(tab => (
                <button key={tab} onClick={() => setQueryTab(tab)}
                  className={`flex-1 py-2.5 text-[12px] font-medium relative transition-colors capitalize ${
                    queryTab === tab ? 'text-[rgba(0,0,0,0.88)]' : 'text-[rgba(0,0,0,0.38)] hover:text-[rgba(0,0,0,0.6)]'
                  }`}>
                  {tab === 'anomalies' && costAnomalies.length > 0 && (
                    <span className="absolute top-2 right-2 w-1.5 h-1.5 rounded-full bg-orange-400" />
                  )}
                  {tab.charAt(0).toUpperCase() + tab.slice(1)}
                  {queryTab === tab && (
                    <span className="absolute bottom-0 left-2 right-2 h-[2px] bg-[rgba(0,0,0,0.88)] rounded-full" />
                  )}
                </button>
              ))}
            </div>

            {/* ── Query tab ── */}
            {queryTab === 'query' && (
              <div className="flex-1 overflow-y-auto divide-y divide-[rgba(0,0,0,0.06)]">

                {/* Data Sources */}
                <div className="px-4 py-3.5">
                  <div className="flex items-center justify-between mb-2.5">
                    <span className="text-[10.5px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.38)]">Data Sources</span>
                    <button className="w-5 h-5 rounded flex items-center justify-center text-[rgba(0,0,0,0.28)] hover:bg-[rgba(0,0,0,0.05)] transition-colors">
                      <Icon icon="mdi:plus" width={13} />
                    </button>
                  </div>
                  <div className="flex items-center gap-2.5 px-3 py-2 bg-[rgba(0,0,0,0.03)] rounded-xl border border-[rgba(0,0,0,0.06)]">
                    <Icon icon="logos:google-cloud" width={16} />
                    <span className="text-[12px] text-[rgba(0,0,0,0.72)] font-medium">All GCP Services</span>
                  </div>
                </div>

                {/* Breakdown */}
                <div className="px-4 py-3.5" onMouseDown={e => e.stopPropagation()}>
                  <div className="flex items-center justify-between mb-2.5">
                    <span className="text-[10.5px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.38)]">Breakdown</span>
                    <button onClick={() => setBdOpen(!bdOpen)}
                      className="w-5 h-5 rounded flex items-center justify-center text-[rgba(0,0,0,0.28)] hover:bg-[rgba(0,0,0,0.05)] transition-colors">
                      <Icon icon="mdi:plus" width={13} />
                    </button>
                  </div>

                  {/* Active breakdown chip */}
                  <button onClick={() => setBdOpen(!bdOpen)}
                    className="w-full flex items-center gap-2.5 px-3 py-2 bg-[rgba(0,0,0,0.03)] rounded-xl border border-[rgba(0,0,0,0.06)] hover:border-[rgba(0,0,0,0.12)] transition-colors">
                    <Icon icon={activeDim.icon} width={14} className="text-[rgba(0,0,0,0.45)] flex-shrink-0" />
                    <span className="text-[12px] font-medium text-[rgba(0,0,0,0.72)] flex-1 text-left">{activeDim.label}</span>
                    <Icon icon="mdi:chevron-down" width={12} className="text-[rgba(0,0,0,0.28)]" />
                  </button>

                  {/* Dimension picker dropdown */}
                  {bdOpen && (
                    <div className="mt-2 bg-white border border-[rgba(0,0,0,0.1)] rounded-2xl shadow-xl overflow-hidden z-40 relative">
                      <div className="p-2 border-b border-[rgba(0,0,0,0.06)]">
                        <input autoFocus type="text" value={bdSearch}
                          onChange={e => setBdSearch(e.target.value)}
                          placeholder="Search dimensions…"
                          className="w-full px-3 py-1.5 text-[12px] bg-[rgba(0,0,0,0.04)] rounded-lg outline-none placeholder:text-[rgba(0,0,0,0.3)] text-[rgba(0,0,0,0.88)]" />
                      </div>
                      <div className="py-1 max-h-56 overflow-y-auto">
                        {filteredDims.map(dim => (
                          <button key={dim.id}
                            onClick={() => { setBreakdown(dim.id as BreakdownDim); setBdOpen(false); setBdSearch(''); }}
                            className={`w-full text-left px-3 py-2 flex items-start gap-2.5 hover:bg-[rgba(0,0,0,0.04)] transition-colors ${
                              breakdown === dim.id ? 'bg-[rgba(0,0,0,0.035)]' : ''
                            }`}>
                            <Icon icon={dim.icon} width={14} className="text-[rgba(0,0,0,0.4)] mt-0.5 flex-shrink-0" />
                            <div className="flex-1 min-w-0">
                              <p className="text-[12px] font-medium text-[rgba(0,0,0,0.85)]">{dim.label}</p>
                              <p className="text-[10.5px] text-[rgba(0,0,0,0.38)] truncate">{dim.example}</p>
                            </div>
                            {breakdown === dim.id && (
                              <Icon icon="mdi:check" width={13} className="text-[rgba(0,0,0,0.45)] mt-0.5 flex-shrink-0" />
                            )}
                          </button>
                        ))}
                      </div>
                    </div>
                  )}
                </div>

                {/* Filter */}
                <div className="px-4 py-3.5" onMouseDown={e => e.stopPropagation()}>
                  <div className="flex items-center justify-between mb-2.5">
                    <span className="text-[10.5px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.38)]">Filter</span>
                    <button onClick={() => setFilterOpen(!filterOpen)}
                      className="w-5 h-5 rounded flex items-center justify-center text-[rgba(0,0,0,0.28)] hover:bg-[rgba(0,0,0,0.05)] transition-colors">
                      <Icon icon="mdi:plus" width={13} />
                    </button>
                  </div>

                  {filterOpen ? (
                    <div className="bg-white border border-[rgba(0,0,0,0.1)] rounded-2xl shadow-xl overflow-hidden z-40 relative">
                      <div className="p-2 border-b border-[rgba(0,0,0,0.06)]">
                        <input autoFocus type="text" placeholder="Search dimensions…"
                          className="w-full px-3 py-1.5 text-[12px] bg-[rgba(0,0,0,0.04)] rounded-lg outline-none placeholder:text-[rgba(0,0,0,0.3)] text-[rgba(0,0,0,0.88)]" />
                      </div>
                      <div className="py-1">
                        {['Service','Region','Resource','SKU','Usage Type','Charge Type'].map(dim => (
                          <button key={dim} onClick={() => setFilterOpen(false)}
                            className="w-full text-left px-3 py-2 text-[12px] text-[rgba(0,0,0,0.7)] hover:bg-[rgba(0,0,0,0.04)] transition-colors">
                            {dim}
                          </button>
                        ))}
                      </div>
                    </div>
                  ) : (
                    <p className="text-[12px] text-[rgba(0,0,0,0.28)] italic">No filters applied</p>
                  )}
                </div>

                {hasData && (
                  <div className="px-4 py-3.5">
                    <span className="text-[10.5px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.38)]">Top {activeDim.label}s</span>
                    <div className="mt-2.5 space-y-2">
                      {chartData.slice(0, 5).map((d, i) => {
                        const cmp = compareData.find(c => c.name === d.name);
                        const diff = cmp ? d.amount - cmp.amount : null;
                        return (
                          <div key={d.name} className="flex items-center gap-2.5">
                            <span className="w-2 h-2 rounded-sm flex-shrink-0"
                              style={{ backgroundColor: CHART_COLORS[i % CHART_COLORS.length] }} />
                            <span className="text-[12px] text-[rgba(0,0,0,0.65)] flex-1 truncate">{d.name}</span>
                            <div className="flex-shrink-0 text-right">
                              <p className="text-[12px] font-semibold text-[rgba(0,0,0,0.8)] tabular-nums">{fmtCurrency(d.amount)}</p>
                              {diff != null && (
                                <p className={`text-[10px] font-medium tabular-nums ${diff >= 0 ? 'text-red-400' : 'text-green-500'}`}>
                                  {diff >= 0 ? '+' : ''}{fmtCurrency(diff)}
                                </p>
                              )}
                            </div>
                          </div>
                        );
                      })}
                      {chartData.length > 5 && (
                        <p className="text-[11px] text-[rgba(0,0,0,0.3)] pt-0.5">+{chartData.length - 5} more items</p>
                      )}
                    </div>
                  </div>
                )}
              </div>
            )}

            {/* ── Compare tab ── */}
            {queryTab === 'compare' && (
              <div className="flex-1 p-4">
                <p className="text-[12px] font-semibold text-[rgba(0,0,0,0.65)] mb-3">Compare current period with</p>
                <div className="space-y-2">
                  {COMPARE_OPTIONS.map(opt => (
                    <button key={opt.id}
                      onClick={() => handleCompareSelect(opt.id)}
                      className={`w-full text-left px-3 py-2.5 rounded-xl border text-[12px] transition-all flex items-center justify-between ${
                        compareOption === opt.id
                          ? 'border-[#4caefe] bg-[#4caefe0d] text-[rgba(0,0,0,0.88)] font-medium'
                          : 'border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.6)] hover:border-[rgba(0,0,0,0.15)]'
                      }`}>
                      {opt.label}
                      {compareOption === opt.id && (
                        compareLoading
                          ? <Icon icon="mdi:loading" width={14} color="#4caefe" className="animate-spin" />
                          : <Icon icon="mdi:check-circle" width={15} color="#4caefe" />
                      )}
                    </button>
                  ))}
                </div>
                {hasCompare && compareDateRange && (
                  <div className="mt-4 p-3 bg-[rgba(0,0,0,0.03)] rounded-xl border border-[rgba(0,0,0,0.07)]">
                    <p className="text-[10.5px] font-semibold text-[rgba(0,0,0,0.45)] uppercase tracking-wide mb-1.5">Comparison Period</p>
                    <p className="text-[12px] text-[rgba(0,0,0,0.65)] font-medium">{compareDateRange.start}</p>
                    <p className="text-[11px] text-[rgba(0,0,0,0.35)]">to {compareDateRange.end}</p>
                    <div className="mt-2.5 pt-2 border-t border-[rgba(0,0,0,0.07)] flex items-center justify-between">
                      <span className="text-[11px] text-[rgba(0,0,0,0.45)]">Total spend</span>
                      <span className="text-[13px] font-bold text-[rgba(0,0,0,0.7)] tabular-nums">{fmtCurrency(compareTotal)}</span>
                    </div>
                  </div>
                )}
              </div>
            )}

            {/* ── Anomalies tab ── */}
            {queryTab === 'anomalies' && (
              <div className="flex-1 overflow-y-auto">
                {costAnomalies.length === 0 ? (
                  <div className="flex flex-col items-center justify-center py-16 px-4 text-center">
                    <div className="w-12 h-12 rounded-full flex items-center justify-center mb-3"
                      style={{ background: '#1feeaf18' }}>
                      <Icon icon="mdi:check-circle-outline" width={26} color="#1feeaf" />
                    </div>
                    <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.65)]">No Anomalies</p>
                    <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-1.5 leading-relaxed">
                      Your spending is within expected ranges for this period.
                    </p>
                  </div>
                ) : (
                  <div className="divide-y divide-[rgba(0,0,0,0.06)]">
                    {costAnomalies.map(a => (
                      <div key={a.anomalyId} className="px-4 py-3.5">
                        <div className="flex items-start justify-between gap-2 mb-1.5">
                          <p className="text-[12.5px] font-semibold text-[rgba(0,0,0,0.88)] leading-tight">{a.service}</p>
                          <span className="text-[12px] font-bold text-red-500 flex-shrink-0 tabular-nums">
                            +{fmtCurrency(a.unexpectedSpend)}
                          </span>
                        </div>
                        <p className="text-[10.5px] text-[rgba(0,0,0,0.38)]">{a.startDate} – {a.endDate}</p>
                        <div className="mt-1.5 flex items-center gap-1.5">
                          <span className="w-1.5 h-1.5 rounded-full bg-orange-400 flex-shrink-0" />
                          <span className="text-[10.5px] text-orange-500 font-medium">Unexpected spike detected</span>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

export default GcpFinOpsCharts;
