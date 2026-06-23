import React, { useState, useMemo, useEffect } from 'react';
import { Icon } from '@iconify/react';
import {
  BarChart, Bar, Cell, XAxis, YAxis, CartesianGrid, Tooltip,
  LineChart, Line, PieChart, Pie, Legend, ResponsiveContainer,
} from 'recharts';
import { AzureFinOpsReportData } from '../../types/azureFinops';

const AZURE_BLUE = '#0078D4';

const CHART_COLORS = [
  '#0078D4','#50e6ff','#ff8c00','#e81123','#107c10','#5c2d91',
  '#008272','#bad80a','#004b8d','#d83b01','#00b4d8','#7fba00',
  '#f2c811','#e74856','#0099bc','#ff6f00',
];

const fmtCurrency = (v: number): string => {
  if (v >= 1_000_000) return `$${(v / 1_000_000).toFixed(1)}M`;
  if (v >= 1_000)     return `$${(v / 1_000).toFixed(1)}K`;
  return `$${v.toFixed(2)}`;
};

const fmtDate = (d: Date) => d.toISOString().split('T')[0];

const getPresetDates = (preset: string): { start: string; end: string } => {
  const end = new Date();
  const start = new Date();
  if (preset === '7d')       start.setDate(end.getDate() - 7);
  else if (preset === '30d') start.setDate(end.getDate() - 30);
  else if (preset === '3m')  start.setMonth(end.getMonth() - 3);
  else if (preset === '6m')  start.setMonth(end.getMonth() - 6);
  else if (preset === '12m') start.setFullYear(end.getFullYear() - 1);
  return { start: fmtDate(start), end: fmtDate(end) };
};

const getCompareDates = (currentStart: Date, currentEnd: Date): { start: string; end: string } => {
  const duration = currentEnd.getTime() - currentStart.getTime();
  const cmpEnd   = new Date(currentStart.getTime() - 86_400_000);
  const cmpStart = new Date(cmpEnd.getTime() - duration);
  return { start: fmtDate(cmpStart), end: fmtDate(cmpEnd) };
};

const COMPARE_OPTIONS = [
  { id: 'prev7d',  label: 'Previous 7 days' },
  { id: 'prev30d', label: 'Previous 30 days' },
  { id: 'prev3m',  label: 'Previous 3 months' },
  { id: 'prev1y',  label: 'Previous year' },
];

const COMPARE_TO_PRESET: Record<string, TimePreset> = {
  prev7d: '7d', prev30d: '30d', prev3m: '3m', prev1y: '12m',
};

const CHART_TYPES = [
  { id: 'bar',  icon: 'mdi:chart-bar',  label: 'Bar',  desc: 'Bar charts compare cost by dimension' },
  { id: 'line', icon: 'mdi:chart-line', label: 'Line', desc: 'Line charts display cost trends' },
  { id: 'pie',  icon: 'mdi:chart-pie',  label: 'Pie',  desc: 'Pie charts show proportional breakdown' },
];

const DIMENSIONS = [
  { id: 'service', label: 'Service', icon: 'mdi:layers-outline',  example: 'Azure Database, App Service, Storage' },
  { id: 'region',  label: 'Region',  icon: 'mdi:earth',            example: 'eastus, westeurope, southeastasia' },
];

type ChartType   = 'bar' | 'line' | 'pie';
type TimePreset  = '7d' | '30d' | '3m' | '6m' | '12m' | 'custom';
type QueryTab    = 'query' | 'compare';
type Breakdown   = 'service' | 'region';
type MergedItem  = { name: string; current: number; previous?: number };

interface Props {
  reportData: AzureFinOpsReportData | null;
  isLoading: boolean;
  selectedAccountId: string | null;
}

const ChartTooltip = ({ active, payload, label }: any) => {
  if (!active || !payload?.length) return null;
  const hasCompare = payload.length > 1 && payload[1]?.value != null;
  return (
    <div className="bg-white border border-[rgba(0,0,0,0.1)] rounded-xl shadow-lg px-3.5 py-2.5 min-w-[160px]">
      <p className="text-[12px] font-semibold text-[rgba(0,0,0,0.88)] mb-2">{label || payload[0]?.name}</p>
      <div className="flex items-center gap-2 mb-1">
        <span className="w-2 h-2 rounded-full flex-shrink-0"
          style={{ backgroundColor: payload[0]?.fill || payload[0]?.color }} />
        <span className="text-[11px] text-[rgba(0,0,0,0.5)]">Current</span>
        <span className="text-[12px] font-semibold text-[rgba(0,0,0,0.88)] ml-auto tabular-nums">
          {fmtCurrency(payload[0]?.value ?? 0)}
        </span>
      </div>
      {hasCompare && (
        <div className="flex items-center gap-2">
          <span className="w-2 h-2 rounded-full bg-[rgba(0,0,0,0.18)] flex-shrink-0" />
          <span className="text-[11px] text-[rgba(0,0,0,0.5)]">Previous</span>
          <span className="text-[12px] font-semibold text-[rgba(0,0,0,0.55)] ml-auto tabular-nums">
            {fmtCurrency(payload[1]?.value ?? 0)}
          </span>
        </div>
      )}
      {hasCompare && payload[0]?.value != null && payload[1]?.value != null && (
        <div className="mt-2 pt-2 border-t border-[rgba(0,0,0,0.07)]">
          {(() => {
            const diff = payload[0].value - payload[1].value;
            const pct = payload[1].value > 0 ? ((diff / payload[1].value) * 100).toFixed(1) : null;
            const up = diff >= 0;
            return (
              <div className="flex items-center gap-1.5">
                <Icon icon={up ? 'mdi:trending-up' : 'mdi:trending-down'} width={13}
                  color={up ? '#ef4444' : '#22c55e'} />
                <span className={`text-[11px] font-semibold ${up ? 'text-red-500' : 'text-green-500'}`}>
                  {up ? '+' : ''}{pct ? `${pct}%` : fmtCurrency(Math.abs(diff))}
                </span>
                <span className="text-[11px] text-[rgba(0,0,0,0.38)]">vs previous</span>
              </div>
            );
          })()}
        </div>
      )}
    </div>
  );
};

const AzureFinOpsCostCharts: React.FC<Props> = ({ reportData, isLoading, selectedAccountId }) => {
  const [chartType, setChartType]         = useState<ChartType>('bar');
  const [breakdown, setBreakdown]         = useState<Breakdown>('service');
  const [timePreset, setTimePreset]       = useState<TimePreset>('30d');
  const [showPanel, setShowPanel]         = useState(true);
  const [queryTab, setQueryTab]           = useState<QueryTab>('query');
  const [compareOption, setCompareOption] = useState<string | null>(null);
  const [compareOpen, setCompareOpen]     = useState(false);
  const [bdOpen, setBdOpen]               = useState(false);

  const [compareCurrent, setCompareCurrent]     = useState<{ name: string; amount: number }[]>([]);
  const [compareData, setCompareData]           = useState<{ name: string; amount: number }[]>([]);
  const [compareLoading, setCompareLoading]     = useState(false);
  const [compareDateRange, setCompareDateRange] = useState<{ start: string; end: string } | null>(null);
  const [currentDateRange, setCurrentDateRange] = useState<{ start: string; end: string } | null>(null);

  useEffect(() => {
    const close = () => { setCompareOpen(false); setBdOpen(false); };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, []);

  const handleCompareSelect = (optId: string) => {
    const newOpt = compareOption === optId ? null : optId;
    setCompareOption(newOpt);
    setCompareOpen(false);
    if (newOpt && COMPARE_TO_PRESET[newOpt]) setTimePreset(COMPARE_TO_PRESET[newOpt]);
    if (!newOpt) { setCompareCurrent([]); setCompareData([]); setCompareDateRange(null); setCurrentDateRange(null); }
  };

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

    const endpoint = breakdown === 'region' ? 'cost-by-region' : 'billing-summary';
    const fetchPeriod = (s: string, e: string) =>
      fetch(`/api/azure/finops/${endpoint}?subscriptionId=${selectedAccountId}&startDate=${s}&endDate=${e}`,
        { credentials: 'include', signal: ctrl.signal })
        .then(r => r.ok ? r.json() : Promise.resolve([]));

    Promise.all([fetchPeriod(currStart, currEnd), fetchPeriod(prevStart, prevEnd)])
      .then(([currRaw, prevRaw]) => {
        if (breakdown === 'region') {
          const norm = (d: Array<{ region: string; cost: number }>) =>
            d.map(x => ({ name: x.region || '', amount: x.cost }))
              .filter(x => x.name && x.amount > 0).sort((a, b) => b.amount - a.amount).slice(0, 15);
          setCompareCurrent(norm(currRaw));
          setCompareData(norm(prevRaw));
        } else {
          const norm = (d: Array<{ serviceName: string; monthToDateCost: number }>) =>
            d.map(x => ({ name: x.serviceName, amount: x.monthToDateCost }))
              .sort((a, b) => b.amount - a.amount).slice(0, 15);
          setCompareCurrent(norm(currRaw));
          setCompareData(norm(prevRaw));
        }
      })
      .catch(() => { setCompareCurrent([]); setCompareData([]); })
      .finally(() => setCompareLoading(false));
    return () => ctrl.abort();
  }, [compareOption, selectedAccountId, breakdown]);

  const chartData = useMemo(() => {
    if (compareOption && compareCurrent.length) return compareCurrent;
    if (breakdown === 'service') {
      return (reportData?.costBreakdown ?? [])
        .map(d => ({ name: d.service, amount: d.cost }))
        .filter(d => d.amount > 0 && d.name).sort((a, b) => b.amount - a.amount).slice(0, 15);
    }
    return (reportData?.costByRegion ?? [])
      .map(d => ({ name: d.region, amount: d.cost }))
      .filter(d => d.amount > 0 && d.name).sort((a, b) => b.amount - a.amount).slice(0, 15);
  }, [reportData, breakdown, compareOption, compareCurrent]);

  const mergedData = useMemo<MergedItem[]>(() => {
    if (!compareData.length) return chartData.map(d => ({ name: d.name, current: d.amount }));
    const cmpMap = new Map(compareData.map(d => [d.name, d.amount]));
    return chartData.map(d => ({ name: d.name, current: d.amount, previous: cmpMap.get(d.name) ?? 0 }));
  }, [chartData, compareData]);

  const total        = useMemo(() => chartData.reduce((s, d) => s + d.amount, 0), [chartData]);
  const compareTotal = useMemo(() => compareData.reduce((s, d) => s + d.amount, 0), [compareData]);
  const hasData      = chartData.length > 0;
  const hasCompare   = compareData.length > 0;
  const activeDim    = DIMENSIONS.find(d => d.id === breakdown)!;

  const compareDiff    = hasCompare ? total - compareTotal : null;
  const compareDiffPct = compareDiff != null && compareTotal > 0
    ? ((compareDiff / compareTotal) * 100).toFixed(1) : null;

  const handlePreset = (preset: TimePreset) => {
    if (compareOption) { setCompareOption(null); setCompareCurrent([]); setCompareData([]); setCompareDateRange(null); setCurrentDateRange(null); }
    setTimePreset(preset);
  };

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
            <Line type="monotone" dataKey="current" name="Current" stroke={AZURE_BLUE} strokeWidth={2.5}
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

    // Horizontal bar — dynamic height so no scrollbar needed
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

  if (isLoading) {
    return (
      <div className="bg-white border border-[rgba(0,0,0,0.1)] rounded-2xl shadow-sm overflow-hidden animate-pulse">
        <div className="px-5 pt-5 pb-4 border-b border-[rgba(0,0,0,0.06)]">
          <div className="h-5 bg-[rgba(0,0,0,0.04)] rounded-lg w-1/4 mb-4" />
          <div className="h-9 bg-[rgba(0,0,0,0.04)] rounded-lg w-1/2" />
        </div>
        <div className="p-5"><div className="h-64 bg-[rgba(0,0,0,0.04)] rounded-lg" /></div>
      </div>
    );
  }

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.1)] rounded-2xl shadow-sm overflow-hidden">

      {/* ── Card header ── */}
      <div className="px-5 pt-5 pb-4 border-b border-[rgba(0,0,0,0.06)]">
        <div className="flex items-center gap-3 mb-4">
          <div className="w-8 h-8 rounded-lg flex items-center justify-center"
            style={{ background: `linear-gradient(135deg,${AZURE_BLUE}22,${AZURE_BLUE}11)` }}>
            <Icon icon="mdi:microsoft-azure" width={18} color={AZURE_BLUE} />
          </div>
          <div>
            <h3 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Cost Explorer</h3>
            <p className="text-[11px] text-[rgba(0,0,0,0.4)]">Interactive cost breakdown · powered by Azure Cost Management</p>
          </div>
        </div>

        {/* ── Toolbar ── */}
        <div className="flex items-center justify-between flex-wrap gap-3">
          <div className="flex items-center gap-2 flex-wrap">

            {/* Time presets */}
            <div className="flex items-center bg-[rgba(0,0,0,0.04)] rounded-lg p-0.5 gap-0.5">
              {(['7d','30d','3m','6m','12m'] as TimePreset[]).map(p => (
                <button key={p} onClick={() => handlePreset(p)}
                  className={`px-2.5 py-1 rounded-md text-[12px] font-medium transition-all ${
                    timePreset === p
                      ? 'bg-white text-[rgba(0,0,0,0.88)] shadow-sm'
                      : 'text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.7)]'
                  }`}>{p.toUpperCase()}
                </button>
              ))}
            </div>

            {/* Compare */}
            <div className="relative" onMouseDown={e => e.stopPropagation()}>
              <button onClick={() => setCompareOpen(!compareOpen)}
                className={`flex items-center gap-1.5 px-3 py-1.5 text-[12px] font-medium border rounded-lg transition-colors ${
                  compareOption
                    ? 'border-[#0078D4] text-[#0078D4] bg-[#0078D412]'
                    : 'border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.03)]'
                }`}>
                <Icon icon="mdi:compare" width={14} />
                {compareLoading
                  ? <span className="flex items-center gap-1">Fetching… <Icon icon="mdi:loading" width={12} className="animate-spin" /></span>
                  : compareOption
                    ? COMPARE_OPTIONS.find(o => o.id === compareOption)?.label
                    : 'Compare'
                }
                <Icon icon="mdi:chevron-down" width={13} />
              </button>
              {compareOpen && (
                <div className="absolute top-full left-0 mt-1 bg-white border border-[rgba(0,0,0,0.1)] rounded-xl shadow-lg py-1.5 min-w-[200px] z-30">
                  {compareOption && (
                    <button onClick={() => { setCompareOption(null); setCompareOpen(false); setCompareCurrent([]); setCompareData([]); setCompareDateRange(null); setCurrentDateRange(null); }}
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
                    <p className="text-[10px] text-[rgba(0,0,0,0.35)] leading-tight">Selecting a comparison period will call the Azure Billing API for that date range if not already cached.</p>
                  </div>
                </div>
              )}
            </div>
          </div>

          {/* Chart type + Query toggle */}
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
              <Icon icon="mdi:filter-variant" width={14} />Query
            </button>
          </div>
        </div>
      </div>

      {/* ── Body ── */}
      <div className="flex" style={{ minHeight: 400 }}>

        {/* Chart area */}
        <div className="flex-1 p-5 min-w-0 flex flex-col">

          {/* Summary row */}
          <div className="flex items-end gap-5 mb-1 flex-wrap">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.35)] mb-0.5">Costs</p>
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
            {' '}· <span className="font-medium">{chartData.length}</span> items
            {compareDateRange && currentDateRange && hasCompare && (
              <span className="ml-2 text-[rgba(0,0,0,0.3)]">
                · {currentDateRange.start} → {currentDateRange.end} <span className="text-[rgba(0,0,0,0.2)]">vs</span> {compareDateRange.start} → {compareDateRange.end}
              </span>
            )}
          </p>

          {/* Chart — no overflow wrapper, height is dynamic so it expands naturally */}
          <div className="flex-1">{renderChart()}</div>

          {/* Color legend */}
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
                  <span className="text-[11px] text-[rgba(0,0,0,0.4)]">
                    {COMPARE_OPTIONS.find(o => o.id === compareOption)?.label}
                  </span>
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
            <div className="flex border-b border-[rgba(0,0,0,0.07)]">
              {(['query','compare'] as QueryTab[]).map(tab => (
                <button key={tab} onClick={() => setQueryTab(tab)}
                  className={`flex-1 py-2.5 text-[12px] font-medium relative transition-colors capitalize ${
                    queryTab === tab ? 'text-[rgba(0,0,0,0.88)]' : 'text-[rgba(0,0,0,0.38)] hover:text-[rgba(0,0,0,0.6)]'
                  }`}>
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
                <div className="px-4 py-3.5">
                  <div className="flex items-center justify-between mb-2.5">
                    <span className="text-[10.5px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.38)]">Data Sources</span>
                  </div>
                  <div className="flex items-center gap-2.5 px-3 py-2 bg-[rgba(0,0,0,0.03)] rounded-xl border border-[rgba(0,0,0,0.06)]">
                    <Icon icon="mdi:microsoft-azure" width={18} color={AZURE_BLUE} />
                    <span className="text-[12px] text-[rgba(0,0,0,0.72)] font-medium">All Azure Services</span>
                  </div>
                </div>

                <div className="px-4 py-3.5" onMouseDown={e => e.stopPropagation()}>
                  <div className="flex items-center justify-between mb-2.5">
                    <span className="text-[10.5px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.38)]">Breakdown</span>
                    <button onClick={() => setBdOpen(!bdOpen)}
                      className="w-5 h-5 rounded flex items-center justify-center text-[rgba(0,0,0,0.28)] hover:bg-[rgba(0,0,0,0.05)]">
                      <Icon icon="mdi:plus" width={13} />
                    </button>
                  </div>
                  <button onClick={() => setBdOpen(!bdOpen)}
                    className="w-full flex items-center gap-2.5 px-3 py-2 bg-[rgba(0,0,0,0.03)] rounded-xl border border-[rgba(0,0,0,0.06)] hover:border-[rgba(0,0,0,0.12)] transition-colors">
                    <Icon icon={activeDim.icon} width={14} className="text-[rgba(0,0,0,0.45)] flex-shrink-0" />
                    <span className="text-[12px] font-medium text-[rgba(0,0,0,0.72)] flex-1 text-left">{activeDim.label}</span>
                    <Icon icon="mdi:chevron-down" width={12} className="text-[rgba(0,0,0,0.28)]" />
                  </button>
                  {bdOpen && (
                    <div className="mt-2 bg-white border border-[rgba(0,0,0,0.1)] rounded-2xl shadow-xl overflow-hidden z-40 relative">
                      <div className="py-1">
                        {DIMENSIONS.map(dim => (
                          <button key={dim.id}
                            onClick={() => { setBreakdown(dim.id as Breakdown); setBdOpen(false); }}
                            className={`w-full text-left px-3 py-2 flex items-start gap-2.5 hover:bg-[rgba(0,0,0,0.04)] ${
                              breakdown === dim.id ? 'bg-[rgba(0,0,0,0.035)]' : ''
                            }`}>
                            <Icon icon={dim.icon} width={14} className="text-[rgba(0,0,0,0.4)] mt-0.5 flex-shrink-0" />
                            <div className="flex-1 min-w-0">
                              <p className="text-[12px] font-medium text-[rgba(0,0,0,0.85)]">{dim.label}</p>
                              <p className="text-[10.5px] text-[rgba(0,0,0,0.38)] truncate">{dim.example}</p>
                            </div>
                            {breakdown === dim.id && <Icon icon="mdi:check" width={13} className="text-[rgba(0,0,0,0.45)] mt-0.5 flex-shrink-0" />}
                          </button>
                        ))}
                      </div>
                    </div>
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
                    <button key={opt.id} onClick={() => handleCompareSelect(opt.id)}
                      className={`w-full text-left px-3 py-2.5 rounded-xl border text-[12px] transition-all flex items-center justify-between ${
                        compareOption === opt.id
                          ? 'border-[#0078D4] bg-[#0078D412] text-[rgba(0,0,0,0.88)] font-medium'
                          : 'border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.6)] hover:border-[rgba(0,0,0,0.15)]'
                      }`}>
                      {opt.label}
                      {compareOption === opt.id && (
                        compareLoading
                          ? <Icon icon="mdi:loading" width={14} color={AZURE_BLUE} className="animate-spin" />
                          : <Icon icon="mdi:check-circle" width={15} color={AZURE_BLUE} />
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
          </div>
        )}
      </div>
    </div>
  );
};

export default AzureFinOpsCostCharts;
