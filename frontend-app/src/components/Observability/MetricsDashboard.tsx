import React, { useEffect, useState, useRef, useCallback, useMemo } from 'react';
import {
  AreaChart, Area, LineChart, Line, BarChart, Bar,
  XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend,
} from 'recharts';
import { useObservabilityData, MetricResult } from '../../hooks/useObservabilityData';
import { Icon } from '@iconify/react';
import { useNavigate } from 'react-router-dom';

// ── Time ranges ───────────────────────────────────────────────────────────────

const TIME_RANGES = [
  { label: '15m', ms: 15 * 60 * 1000,       step: '30s'  },
  { label: '1h',  ms: 60 * 60 * 1000,       step: '60s'  },
  { label: '6h',  ms: 6 * 60 * 60 * 1000,   step: '300s' },
  { label: '24h', ms: 24 * 60 * 60 * 1000,  step: '900s' },
  { label: '7d',  ms: 7 * 24 * 60 * 60 * 1000, step: '3600s' },
];

// ── Unit helpers ──────────────────────────────────────────────────────────────

// Network/disk queries return KB/s (divided by 1024 from bytes).
// Auto-scale KB/s → B/s or MB/s as needed.
function autoKBs(kbPerSec: number): string {
  if (kbPerSec === 0) return '0 B/s';
  if (kbPerSec < 1)       return `${(kbPerSec * 1024).toFixed(0)} B/s`;
  if (kbPerSec < 1024)    return `${kbPerSec.toFixed(2)} KB/s`;
  return `${(kbPerSec / 1024).toFixed(2)} MB/s`;
}

function formatVal(v: number, unit: string): string {
  if (unit === 'MB/s') return autoKBs(v);   // query returns KB/s, labeled MB/s for routing
  if (unit === '%') return `${v.toFixed(1)}%`;
  if (unit === 'GB') return `${v.toFixed(2)} GB`;
  if (unit === 'MB') return `${v.toFixed(1)} MB`;
  return `${v.toFixed(2)}${unit ? ' ' + unit : ''}`;
}

// ── Chart data helpers ────────────────────────────────────────────────────────

function metricsToChartData(results: MetricResult[]) {
  if (!results?.length) return [];
  const map = new Map<number, Record<string, number>>();
  results.forEach((series, idx) => {
    const label = Object.entries(series.metric)
      .filter(([k]) => !['__name__', 'instrumentation_library_name', 'instrumentation_library_version', 'start_time', 'flag', 'host_image_id'].includes(k))
      .map(([, v]) => v).join('/') || `series-${idx}`;
    series.values.forEach(([ts, val]) => {
      if (!map.has(ts)) map.set(ts, { ts });
      map.get(ts)![label] = parseFloat(val);
    });
  });
  return Array.from(map.entries()).sort(([a], [b]) => a - b).map(([, row]) => row);
}

function getSeriesKeys(results: MetricResult[]) {
  return results.map((s, i) =>
    Object.entries(s.metric)
      .filter(([k]) => !['__name__', 'instrumentation_library_name', 'instrumentation_library_version', 'start_time', 'flag', 'host_image_id'].includes(k))
      .map(([, v]) => v).join('/') || `series-${i}`
  );
}

// ── Metric panel definition ───────────────────────────────────────────────────

interface PanelDef {
  id: string;
  label: string;
  query: string;
  unit: string;
  chartType?: 'area' | 'line' | 'bar';
}

// Grouped by host_name → each EC2 / VM gets its own colored series automatically.
// When you add a second EC2, it appears as a new color without any config change.
const HOST_PANELS: PanelDef[] = [
  {
    id: 'cpu',    label: 'CPU Usage — per host',
    query: '(1 - avg by (host_name) (system_cpu_utilization{state="idle"})) * 100',
    unit: '%', chartType: 'area',
  },
  {
    id: 'memory', label: 'Memory Usage — per host',
    query: 'avg by (host_name) (system_memory_utilization{state="used"}) * 100',
    unit: '%', chartType: 'area',
  },
  {
    id: 'load1',  label: 'Load Avg 1m — per host',
    query: 'avg by (host_name) (system_cpu_load_average_1m)',
    unit: '', chartType: 'line',
  },
  {
    id: 'net_in', label: 'Network In — per host',
    query: 'sum by (host_name) (rate(system_network_io{direction="receive"}[5m])) / 1024',
    unit: 'MB/s', chartType: 'area',
  },
  {
    id: 'net_out', label: 'Network Out — per host',
    query: 'sum by (host_name) (rate(system_network_io{direction="transmit"}[5m])) / 1024',
    unit: 'MB/s', chartType: 'area',
  },
  {
    id: 'disk_r', label: 'Disk Read — per host',
    query: 'sum by (host_name) (rate(system_disk_io{direction="read"}[5m])) / 1024',
    unit: 'MB/s', chartType: 'area',
  },
  {
    id: 'disk_w', label: 'Disk Write — per host',
    query: 'sum by (host_name) (rate(system_disk_io{direction="write"}[5m])) / 1024',
    unit: 'MB/s', chartType: 'area',
  },
  {
    id: 'fs',     label: 'Filesystem Used — per host',
    query: 'sum by (host_name) (system_filesystem_usage{state="used"}) / 1024 / 1024 / 1024',
    unit: 'GB', chartType: 'bar',
  },
];

// K8s panels: grouped by node/pod/container — each resource gets its own color.
const K8S_PANELS: PanelDef[] = [
  {
    id: 'node_cpu',  label: 'Node CPU — per node',
    query: 'avg by (k8s_node_name) (k8s_node_cpu_utilization) * 100',
    unit: '%', chartType: 'area',
  },
  {
    id: 'node_mem',  label: 'Node Memory — per node',
    query: 'avg by (k8s_node_name) (k8s_node_memory_usage) / 1024 / 1024 / 1024',
    unit: 'GB', chartType: 'area',
  },
  {
    id: 'pod_cpu',   label: 'Pod CPU — per pod',
    query: 'avg by (k8s_pod_name) (k8s_pod_cpu_utilization) * 100',
    unit: '%', chartType: 'line',
  },
  {
    id: 'pod_mem',   label: 'Pod Memory — per pod',
    query: 'avg by (k8s_pod_name) (k8s_pod_memory_working_set) / 1024 / 1024',
    unit: 'MB', chartType: 'line',
  },
  {
    id: 'pod_net',   label: 'Pod Network I/O',
    query: 'sum by (k8s_pod_name) (rate(k8s_pod_network_io[5m])) / 1024',
    unit: 'MB/s', chartType: 'area',
  },
  {
    id: 'ctr_cpu',   label: 'Container CPU — per container',
    query: 'avg by (k8s_container_name) (container_cpu_utilization) * 100',
    unit: '%', chartType: 'bar',
  },
];

// Categorical series palette — blue-led with coral/cyan/orange accents (no
// green/yellow/purple in the leading slots). Fixed slot order, CVD-validated on
// white (worst adjacent ΔE 34.0). Never re-sort or cycle; series past 8 reuse.
const COLORS = ['#2563EB', '#F43F5E', '#0891B2', '#F97316', '#0D9488', '#C026D3', '#B45309', '#0369A1'];

// Shorten a series key for legends/labels: hostnames → first segment, long
// pod/container names → truncated with ellipsis.
function shortLabel(k: string): string {
  if (k.includes('.')) return k.slice(0, k.indexOf('.'));
  return k.length > 22 ? k.slice(0, 20) + '…' : k;
}

// ── Hero summary KPI cards ────────────────────────────────────────────────────

interface SummaryDef {
  id: string;
  label: string;
  query: string;
  unit: string;
  icon: string;
  accent: string;
  bg: string;
  agg: 'avg' | 'max' | 'count';   // how to reduce multiple series into one headline number
}

const SUMMARY_DEFS: SummaryDef[] = [
  { id: 's_cpu', label: 'Avg CPU', unit: '%', icon: 'mdi:chip', accent: '#2563EB', bg: '#2563EB14', agg: 'avg',
    query: '(1 - avg by (host_name) (system_cpu_utilization{state="idle"})) * 100' },
  { id: 's_mem', label: 'Avg Memory', unit: '%', icon: 'mdi:memory', accent: '#F97316', bg: '#F9731614', agg: 'avg',
    query: 'avg by (host_name) (system_memory_utilization{state="used"}) * 100' },
  { id: 's_load', label: 'Peak Load 1m', unit: '', icon: 'mdi:speedometer', accent: '#F43F5E', bg: '#F43F5E14', agg: 'max',
    query: 'avg by (host_name) (system_cpu_load_average_1m)' },
  { id: 's_hosts', label: 'Active Hosts', unit: '', icon: 'mdi:server-network', accent: '#0891B2', bg: '#0891B214', agg: 'count',
    query: 'avg by (host_name) (system_cpu_utilization{state="idle"})' },
];

const SummaryMetricCard: React.FC<{
  def: SummaryDef; step: string; startTime: number; endTime: number; refreshTick: number; index: number;
}> = ({ def, step, startTime, endTime, refreshTick, index }) => {
  const { queryMetrics } = useObservabilityData();
  const [spark, setSpark] = useState<{ ts: number; v: number }[]>([]);
  const [value, setValue] = useState<string>('—');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    queryMetrics(def.query, startTime, endTime, step).then(results => {
      if (cancelled) return;
      if (def.agg === 'count') {
        // Number of distinct series (hosts) currently reporting
        setValue(String(results.length));
        // Sparkline = total series count is flat; instead show count of active points over time
        const map = new Map<number, number>();
        results.forEach(s => s.values.forEach(([ts]) => map.set(ts, (map.get(ts) || 0) + 1)));
        setSpark(Array.from(map.entries()).sort(([a], [b]) => a - b).map(([ts, v]) => ({ ts, v })));
        setLoading(false);
        return;
      }
      // Build combined timeline reducing across series per timestamp
      const perTs = new Map<number, number[]>();
      results.forEach(s => s.values.forEach(([ts, val]) => {
        const arr = perTs.get(ts) || [];
        arr.push(parseFloat(val));
        perTs.set(ts, arr);
      }));
      const timeline = Array.from(perTs.entries()).sort(([a], [b]) => a - b).map(([ts, vals]) => {
        const reduced = def.agg === 'max' ? Math.max(...vals) : vals.reduce((a, b) => a + b, 0) / vals.length;
        return { ts, v: reduced };
      });
      setSpark(timeline);
      if (timeline.length > 0) {
        const last = timeline[timeline.length - 1].v;
        setValue(def.unit === '%' ? `${last.toFixed(1)}%` : last.toFixed(2));
      } else {
        setValue('—');
      }
      setLoading(false);
    }).catch(() => { if (!cancelled) { setValue('—'); setLoading(false); } });
    return () => { cancelled = true; };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [def.query, startTime, endTime, step, refreshTick]);

  // Trend vs the start of the window (not shown for the host-count card).
  const trend = (() => {
    if (def.agg === 'count' || spark.length < 2) return null;
    const first = spark[0].v, last = spark[spark.length - 1].v;
    if (!Number.isFinite(first) || !Number.isFinite(last) || Math.abs(first) < 1e-9) return null;
    return ((last - first) / Math.abs(first)) * 100;
  })();
  const trendUp = trend !== null && trend >= 0;

  return (
    <div
      className="group bg-white border border-[rgba(0,0,0,0.08)] rounded-2xl p-4 relative overflow-hidden hover:border-[rgba(0,0,0,0.16)] hover:shadow-[0_8px_24px_-12px_rgba(0,0,0,0.18)] transition-all duration-300"
      style={{ animation: `fadeInUp 0.45s cubic-bezier(0.22,1,0.36,1) ${0.04 + index * 0.06}s both` }}
    >
      {/* faint accent wash top-right */}
      <span className="absolute -top-8 -right-8 w-24 h-24 rounded-full opacity-[0.06] blur-2xl pointer-events-none"
        style={{ backgroundColor: def.accent }} />

      <div className="flex items-center justify-between mb-2">
        <p className="text-[10.5px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.4)]">{def.label}</p>
        <div className="w-8 h-8 rounded-xl flex items-center justify-center flex-shrink-0 ring-1 ring-inset ring-[rgba(0,0,0,0.04)]" style={{ backgroundColor: def.bg }}>
          <Icon icon={def.icon} width={16} style={{ color: def.accent }} />
        </div>
      </div>

      <div className="flex items-end gap-2">
        <p className="text-[27px] font-bold leading-8 tracking-tight text-[#0b1220]" style={{ fontVariantNumeric: 'tabular-nums' }}>
          {loading ? <span className="text-sm animate-pulse text-[rgba(0,0,0,0.3)]">Loading…</span> : value}
        </p>
        {!loading && trend !== null && Math.abs(trend) >= 0.1 && (
          <span className={`mb-1 inline-flex items-center gap-0.5 text-[11px] font-semibold rounded-full px-1.5 py-0.5 ${
            trendUp ? 'text-rose-600 bg-rose-50' : 'text-emerald-600 bg-emerald-50'
          }`} title="Change vs start of window">
            <Icon icon={trendUp ? 'mdi:trending-up' : 'mdi:trending-down'} width={12} />
            {Math.abs(trend).toFixed(0)}%
          </span>
        )}
      </div>

      <div className="mt-2 -mx-1 h-9">
        {!loading && spark.length > 1 && (
          <ResponsiveContainer width="100%" height={36}>
            <AreaChart data={spark} margin={{ top: 2, right: 2, bottom: 0, left: 2 }}>
              <defs>
                <linearGradient id={`sgrad-${def.id}`} x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor={def.accent} stopOpacity={0.28} />
                  <stop offset="100%" stopColor={def.accent} stopOpacity={0} />
                </linearGradient>
              </defs>
              <Area type="monotone" dataKey="v" stroke={def.accent} strokeWidth={2} strokeLinecap="round" fill={`url(#sgrad-${def.id})`} dot={false} isAnimationActive={false} />
            </AreaChart>
          </ResponsiveContainer>
        )}
      </div>
    </div>
  );
};

// ── Single metric panel ───────────────────────────────────────────────────────

interface MetricPanelProps extends PanelDef {
  step: string;
  startTime: number;
  endTime: number;
  refreshTick: number;
}

const MetricPanel: React.FC<MetricPanelProps> = ({
  label, unit, query, step, startTime, endTime, chartType = 'area', refreshTick,
}) => {
  const { queryMetrics } = useObservabilityData();
  const navigate = useNavigate();
  const [data, setData] = useState<Record<string, number>[]>([]);
  const [seriesKeys, setSeriesKeys] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [currentVal, setCurrentVal] = useState<string>('—');

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    queryMetrics(query, startTime, endTime, step).then(results => {
      if (cancelled) return;
      const chartData = metricsToChartData(results);
      const keys = getSeriesKeys(results);
      setData(chartData);
      setSeriesKeys(keys);
      if (chartData.length > 0) {
        const last = chartData[chartData.length - 1];
        const vals = Object.keys(last).filter(k => k !== 'ts').map(k => last[k]);
        if (vals.length > 0) {
          const avg = vals.reduce((a, b) => a + b, 0) / vals.length;
          setCurrentVal(formatVal(avg, unit));
        }
      } else {
        setCurrentVal('—');
      }
      setLoading(false);
    });
    return () => { cancelled = true; };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, startTime, endTime, step, refreshTick]);

  const cardCls = 'group rounded-2xl border border-[rgba(0,0,0,0.08)] p-4 bg-white hover:border-[rgba(0,0,0,0.16)] hover:shadow-[0_10px_30px_-14px_rgba(0,0,0,0.22)] transition-all duration-300';
  const chartHeight = 132;

  // Shared axis + grid styling for a cleaner, more readable chart.
  const axisFmt = (ts: number) => new Date(ts * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  const yFmt = (v: number) => {
    if (unit === '%') return `${Math.round(v)}`;
    if (Math.abs(v) >= 1000) return `${(v / 1000).toFixed(1)}k`;
    if (Math.abs(v) >= 100) return `${Math.round(v)}`;
    return v.toFixed(v < 10 ? 1 : 0);
  };

  function renderChart() {
    if (data.length === 0) return (
      <div className="flex flex-col items-center justify-center gap-1.5 text-[rgba(0,0,0,0.3)]" style={{ height: chartHeight }}>
        <Icon icon="mdi:chart-line-variant" width={22} />
        <span className="text-[11px] font-medium">No data in range</span>
      </div>
    );

    const commonProps = {
      data,
      margin: { top: 6, right: 8, bottom: 0, left: -8 },
    };
    const grid = <CartesianGrid strokeDasharray="2 4" stroke="rgba(0,0,0,0.045)" vertical={false} />;
    const xAxis = (
      <XAxis dataKey="ts" tickFormatter={axisFmt}
        tick={{ fontSize: 10, fill: 'rgba(0,0,0,0.4)' }} axisLine={false} tickLine={false}
        minTickGap={44} interval="preserveStartEnd" />
    );
    const yAxis = (
      <YAxis tickFormatter={yFmt} tick={{ fontSize: 10, fill: 'rgba(0,0,0,0.4)' }}
        axisLine={false} tickLine={false} width={38} domain={unit === '%' ? [0, 100] : ['auto', 'auto']} />
    );
    const tooltip = (
      <Tooltip
        contentStyle={{ background: '#fff', border: '1px solid rgba(0,0,0,0.1)', borderRadius: 10, fontSize: 11, boxShadow: '0 6px 18px rgba(0,0,0,0.1)', padding: '8px 10px' }}
        labelStyle={{ color: 'rgba(0,0,0,0.45)', fontSize: 10, marginBottom: 4, fontWeight: 600 }}
        itemStyle={{ padding: '1px 0' }}
        formatter={(v: any, name: any) => {
          const short = String(name).includes('.') ? String(name).slice(0, String(name).indexOf('.')) : String(name);
          return [formatVal(Number(v), unit), short];
        }}
        labelFormatter={(ts: any) => new Date(ts * 1000).toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })}
        cursor={{ stroke: 'rgba(0,0,0,0.15)', strokeWidth: 1, strokeDasharray: '3 3' }}
      />
    );

    if (chartType === 'bar') {
      return (
        <ResponsiveContainer width="100%" height={chartHeight}>
          <BarChart {...commonProps} barCategoryGap="18%">
            {grid}{xAxis}{yAxis}{tooltip}
            {seriesKeys.map((k, i) => <Bar key={k} dataKey={k} name={k} fill={COLORS[i % COLORS.length]} radius={[3, 3, 0, 0]} maxBarSize={26} />)}
          </BarChart>
        </ResponsiveContainer>
      );
    }

    if (chartType === 'line') {
      return (
        <ResponsiveContainer width="100%" height={chartHeight}>
          <LineChart {...commonProps}>
            {grid}{xAxis}{yAxis}{tooltip}
            {seriesKeys.map((k, i) => (
              <Line key={k} type="monotone" dataKey={k} name={k} stroke={COLORS[i % COLORS.length]}
                strokeWidth={2.25} strokeLinecap="round" strokeLinejoin="round"
                dot={false} activeDot={{ r: 4, strokeWidth: 2, stroke: '#fff' }} />
            ))}
          </LineChart>
        </ResponsiveContainer>
      );
    }

    // area
    return (
      <ResponsiveContainer width="100%" height={chartHeight}>
        <AreaChart {...commonProps}>
          <defs>
            {seriesKeys.map((k, i) => (
              <linearGradient key={k} id={`grad-${i}`} x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%"   stopColor={COLORS[i % COLORS.length]} stopOpacity={0.35} />
                <stop offset="60%"  stopColor={COLORS[i % COLORS.length]} stopOpacity={0.08} />
                <stop offset="100%" stopColor={COLORS[i % COLORS.length]} stopOpacity={0} />
              </linearGradient>
            ))}
          </defs>
          {grid}{xAxis}{yAxis}{tooltip}
          {seriesKeys.map((k, i) => (
            <Area key={k} type="monotone" dataKey={k} name={k}
              stroke={COLORS[i % COLORS.length]} strokeWidth={2.25}
              strokeLinecap="round" strokeLinejoin="round"
              fill={`url(#grad-${i})`} dot={false} activeDot={{ r: 4, strokeWidth: 2, stroke: '#fff' }} />
          ))}
        </AreaChart>
      </ResponsiveContainer>
    );
  }

  // Peak across all loaded series/points — shown as light context next to the value.
  const peakVal = (() => {
    let m = -Infinity;
    for (const row of data) for (const k of Object.keys(row)) if (k !== 'ts' && Number.isFinite(row[k])) m = Math.max(m, row[k]);
    return Number.isFinite(m) ? m : null;
  })();

  return (
    <div className={cardCls}>
      <div className="mb-2.5 flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="text-[10.5px] font-semibold uppercase tracking-[0.07em] text-[rgba(0,0,0,0.4)] truncate">{label}</p>
          <div className="flex items-baseline gap-2 mt-0.5">
            <p className="text-[21px] font-bold leading-7 tracking-tight text-[#0b1220]" style={{ fontVariantNumeric: 'tabular-nums' }}>
              {loading ? <span className="text-sm animate-pulse text-[rgba(0,0,0,0.35)]">Loading…</span> : currentVal}
            </p>
            {!loading && peakVal !== null && seriesKeys.length > 0 && (
              <span className="text-[11px] text-[rgba(0,0,0,0.4)]" style={{ fontVariantNumeric: 'tabular-nums' }}>
                peak {formatVal(peakVal, unit)}
              </span>
            )}
          </div>
        </div>
        {!loading && seriesKeys.length > 1 && (
          <span className="flex-shrink-0 text-[10px] font-semibold text-[rgba(0,0,0,0.4)] bg-[rgba(0,0,0,0.04)] rounded-full px-2 py-0.5 mt-0.5">
            avg · {seriesKeys.length} hosts
          </span>
        )}
      </div>

      {!loading && seriesKeys.length > 0 && (
        <div className="flex flex-wrap gap-1.5 mb-2">
          {seriesKeys.map((k, i) => (
            <button key={k} type="button" title={`View logs for ${k} in this time window`}
              onClick={() => navigate(`/observability/logs?host=${encodeURIComponent(k)}&from=${startTime}&to=${endTime}`)}
              className="inline-flex items-center gap-1.5 text-[10.5px] rounded-full pl-1.5 pr-2 py-0.5 font-medium border border-[rgba(0,0,0,0.07)] bg-[rgba(0,0,0,0.015)] text-[rgba(0,0,0,0.6)] hover:border-[rgba(0,0,0,0.22)] hover:bg-white hover:text-[#0b1220] transition-colors cursor-pointer">
              <span className="w-2 h-2 rounded-full flex-shrink-0" style={{ backgroundColor: COLORS[i % COLORS.length] }} />
              {shortLabel(k)}
              <Icon icon="mdi:text-box-search-outline" width={10} className="opacity-0 group-hover:opacity-40 transition-opacity" />
            </button>
          ))}
        </div>
      )}

      {loading
        ? <div className="flex items-center justify-center" style={{ height: chartHeight }}><div className="w-5 h-5 border-2 border-[rgba(0,0,0,0.15)] border-t-[#2563EB] rounded-full animate-spin" /></div>
        : renderChart()
      }
    </div>
  );
};

// ── PromQL editor ─────────────────────────────────────────────────────────────

const PROMQL_TEMPLATES = [
  { label: 'CPU per host',     query: '(1 - avg by (host_name) (system_cpu_utilization{state="idle"})) * 100' },
  { label: 'Memory per host',  query: 'avg by (host_name) (system_memory_utilization{state="used"}) * 100' },
  { label: 'Net In KB/s',      query: 'sum by (host_name) (rate(system_network_io{direction="receive"}[5m])) / 1024' },
  { label: 'Disk Write KB/s',  query: 'sum by (host_name) (rate(system_disk_io{direction="write"}[5m])) / 1024' },
  { label: 'Node CPU',         query: 'avg by (k8s_node_name) (k8s_node_cpu_utilization) * 100' },
  { label: 'Node Memory GB',   query: 'avg by (k8s_node_name) (k8s_node_memory_usage) / 1024 / 1024 / 1024' },
  { label: 'Pod CPU',          query: 'avg by (k8s_pod_name) (k8s_pod_cpu_utilization) * 100' },
  { label: 'Pod Memory MB',    query: 'avg by (k8s_pod_name) (k8s_pod_memory_working_set) / 1024 / 1024' },
  { label: 'Container CPU',    query: 'avg by (k8s_container_name) (container_cpu_utilization) * 100' },
];

function PromQLEditor({ timeRange, refreshTick: _refreshTick }: { timeRange: typeof TIME_RANGES[0]; refreshTick: number }) {
  const { queryMetrics } = useObservabilityData();
  const [promql, setPromql] = useState('');
  const [results, setResults] = useState<MetricResult[]>([]);
  const [chartData, setChartData] = useState<Record<string, number>[]>([]);
  const [seriesKeys, setSeriesKeys] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleRun = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!promql.trim()) return;
    setLoading(true);
    setError('');
    const end = Date.now();
    const start = end - timeRange.ms;
    try {
      const r = await queryMetrics(promql.trim(), start, end, timeRange.step);
      setResults(r);
      setChartData(metricsToChartData(r));
      setSeriesKeys(getSeriesKeys(r));
      if (r.length === 0) setError('No data returned for this query and time range.');
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Query failed');
    } finally {
      setLoading(false);
    }
  };

  const inputCls = 'text-[13px] rounded-lg border border-[rgba(0,0,0,0.12)] bg-white px-3 py-2 focus:outline-none focus:ring-2 focus:ring-black/5 focus:border-[rgba(0,0,0,0.25)] text-black placeholder:text-[rgba(0,0,0,0.3)] transition-colors';

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-2xl p-5 space-y-4">
      <div className="flex items-center gap-2">
        <Icon icon="mdi:chart-line" width={14} className="text-[rgba(0,0,0,0.45)]" />
        <span className="text-[14px] font-semibold text-black">PromQL Query Editor</span>
        <span className="text-[11px] px-2 py-0.5 rounded-full bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.06)] text-[rgba(0,0,0,0.5)]">Prometheus-compatible</span>
      </div>

      <form onSubmit={handleRun} className="flex gap-2">
        <input type="text" value={promql} onChange={e => setPromql(e.target.value)}
          placeholder='e.g. (1 - avg(system_cpu_utilization{state="idle"})) * 100'
          className={`${inputCls} flex-1 font-mono`} />
        <button type="submit" disabled={loading || !promql.trim()}
          className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-black hover:bg-[rgba(0,0,0,0.85)] text-white text-[13px] font-medium disabled:opacity-40 transition-colors whitespace-nowrap">
          <Icon icon="mdi:play" width={12} />
          {loading ? 'Running...' : 'Run'}
        </button>
      </form>

      <div className="flex flex-wrap gap-2">
        {PROMQL_TEMPLATES.map(t => (
          <button key={t.label} onClick={() => setPromql(t.query)}
            className="text-[11px] px-2.5 py-1 rounded-full border border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.5)] hover:border-[rgba(0,0,0,0.25)] hover:text-black hover:bg-[rgba(0,0,0,0.03)] transition-colors">
            {t.label}
          </button>
        ))}
      </div>

      {error && <p className="text-xs px-3 py-2 rounded-lg bg-red-50 text-red-600">{error}</p>}

      {chartData.length > 0 && (
        <div>
          <ResponsiveContainer width="100%" height={220}>
            <AreaChart data={chartData} margin={{ top: 4, right: 8, bottom: 0, left: 8 }}>
              <defs>
                {seriesKeys.map((_, i) => (
                  <linearGradient key={i} id={`pqgrad-${i}`} x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%"  stopColor={COLORS[i % COLORS.length]} stopOpacity={0.3} />
                    <stop offset="95%" stopColor={COLORS[i % COLORS.length]} stopOpacity={0} />
                  </linearGradient>
                ))}
              </defs>
              <CartesianGrid strokeDasharray="3 3" stroke="#e1e0d9" vertical={false} />
              <XAxis dataKey="ts"
                tickFormatter={(ts: number) => new Date(ts * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                tick={{ fontSize: 11, fill: '#898781' }} stroke="#c3c2b7" tickLine={false} />
              <YAxis tick={{ fontSize: 11, fill: '#898781' }} stroke="transparent" width={45}
                tickFormatter={(v: number) => Number(v).toFixed(1)} />
              <Tooltip
                contentStyle={{ background: '#fff', border: '1px solid rgba(0,0,0,0.12)', borderRadius: 8, fontSize: 12, boxShadow: '0 4px 12px rgba(0,0,0,0.08)' }}
                formatter={(v: any) => [Number(v).toFixed(3), '']}
                labelFormatter={(ts: any) => new Date(ts * 1000).toLocaleTimeString()} />
              <Legend wrapperStyle={{ fontSize: 11 }} />
              {seriesKeys.map((key, i) => (
                <Area key={key} type="monotone" dataKey={key}
                  stroke={COLORS[i % COLORS.length]} fill={`url(#pqgrad-${i})`}
                  strokeWidth={2} dot={false} name={key.slice(0, 40)} />
              ))}
            </AreaChart>
          </ResponsiveContainer>

          {results.length > 0 && (
            <div className="mt-3 rounded-lg overflow-hidden border border-[rgba(0,0,0,0.08)] text-xs">
              <div className="px-4 py-2 font-semibold border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.02)] text-[rgba(0,0,0,0.6)]">
                {results.length} series
              </div>
              {results.slice(0, 8).map((r, i) => {
                const last = r.values[r.values.length - 1];
                return (
                  <div key={i} className="flex items-center justify-between px-4 py-2 border-b border-[rgba(0,0,0,0.06)] last:border-b-0 hover:bg-[rgba(0,0,0,0.025)]">
                    <div className="flex items-center gap-2">
                      <span className="w-2 h-2 rounded-full flex-shrink-0" style={{ backgroundColor: COLORS[i % COLORS.length] }} />
                      <span className="font-mono truncate max-w-sm text-[rgba(0,0,0,0.7)]">
                        {Object.entries(r.metric).filter(([k]) => k !== '__name__').map(([k, v]) => `${k}="${v}"`).join(', ') || 'value'}
                      </span>
                    </div>
                    <span className="font-semibold ml-4 flex-shrink-0 text-[rgba(0,0,0,0.88)]">
                      {last ? Number(last[1]).toFixed(3) : '—'}
                    </span>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

// ── Section header ────────────────────────────────────────────────────────────

function SectionHeader({ icon, title, subtitle, accent = '#2a78d6' }: { icon: string; title: string; subtitle: string; accent?: string }) {
  return (
    <div className="flex items-center gap-3 pt-3">
      <div className="w-9 h-9 rounded-xl flex items-center justify-center flex-shrink-0 ring-1 ring-inset ring-[rgba(0,0,0,0.05)]"
        style={{ background: `linear-gradient(135deg, ${accent}22, ${accent}0d)` }}>
        <Icon icon={icon} width={18} style={{ color: accent }} />
      </div>
      <div className="flex-shrink-0">
        <h2 className="text-[16px] font-bold text-[#0b1220] leading-tight tracking-tight">{title}</h2>
        <span className="text-[12px] text-[rgba(0,0,0,0.4)]">{subtitle}</span>
      </div>
      <div className="flex-1 h-px bg-gradient-to-r from-[rgba(0,0,0,0.08)] to-transparent ml-2" />
    </div>
  );
}

// ── Main MetricsDashboard ─────────────────────────────────────────────────────

const REFRESH_INTERVAL_MS = 30_000;

const MetricsDashboard: React.FC = () => {
  const [timeRange, setTimeRange] = useState(TIME_RANGES[1]); // default 1h
  const [refreshTick, setRefreshTick] = useState(0);
  const [lastRefresh, setLastRefresh] = useState(new Date());
  const [liveEnabled, setLiveEnabled] = useState(true);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const doRefresh = useCallback(() => {
    setRefreshTick(t => t + 1);
    setLastRefresh(new Date());
  }, []);

  useEffect(() => {
    if (liveEnabled) {
      intervalRef.current = setInterval(doRefresh, REFRESH_INTERVAL_MS);
    }
    return () => { if (intervalRef.current) clearInterval(intervalRef.current); };
  }, [liveEnabled, doRefresh]);

  // Pinned to the range + refresh tick, not to render. Recomputing Date.now() on
  // every render made startTime/endTime new values each time, and since every
  // panel keys its query effect on them, unrelated state changes (toggling Live,
  // editing PromQL) re-fired all ~18 metric queries at once.
  const { startTime, endTime } = useMemo(() => {
    const end = Date.now();
    return { startTime: end - timeRange.ms, endTime: end };
  }, [timeRange, refreshTick]);

  return (
    <div className="space-y-4">

      {/* Toolbar */}
      <div className="flex items-center justify-between flex-wrap gap-3">
        <div className="flex items-center gap-2">
          <span className="text-[12px] font-medium text-[rgba(0,0,0,0.5)]">Range</span>
          {/* Segmented control — matches Cost Explorer */}
          <div className="flex items-center bg-[rgba(0,0,0,0.04)] rounded-lg p-0.5 gap-0.5">
            {TIME_RANGES.map(t => (
              <button key={t.label} onClick={() => setTimeRange(t)}
                className={`px-2.5 py-1 rounded-md text-[12px] font-medium transition-all ${
                  timeRange.label === t.label
                    ? 'bg-white text-[rgba(0,0,0,0.88)] shadow-sm'
                    : 'text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.7)]'
                }`}>
                {t.label.toUpperCase()}
              </button>
            ))}
          </div>
        </div>

        <div className="flex items-center gap-3">
          {/* Live indicator */}
          <button onClick={() => setLiveEnabled(v => !v)}
            className={`flex items-center gap-1.5 text-xs px-3 py-1.5 rounded-lg border font-medium transition-colors ${
              liveEnabled
                ? 'bg-emerald-50 border-emerald-300 text-emerald-700'
                : 'border-[rgba(0,0,0,0.08)] bg-white text-[rgba(0,0,0,0.5)]'
            }`}>
            <span className={`w-2 h-2 rounded-full ${liveEnabled ? 'bg-emerald-500 animate-pulse' : 'bg-[rgba(0,0,0,0.2)]'}`} />
            {liveEnabled ? 'Live' : 'Paused'}
          </button>

          <span className="text-[11px] text-[rgba(0,0,0,0.4)]" style={{ fontVariantNumeric: 'tabular-nums' }}>
            Updated {lastRefresh.toLocaleTimeString()}
          </span>

          <button onClick={doRefresh}
            className="flex items-center gap-1.5 text-xs px-3 py-1.5 rounded-lg font-medium bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.07)] transition-colors">
            <Icon icon="mdi:refresh" width={13} /> Refresh
          </button>
        </div>
      </div>

      {/* Hero summary KPI cards */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
        {SUMMARY_DEFS.map((def, i) => (
          <SummaryMetricCard key={def.id} def={def} step={timeRange.step}
            startTime={startTime} endTime={endTime} refreshTick={refreshTick} index={i} />
        ))}
      </div>

      {/* PromQL editor */}
      <PromQLEditor timeRange={timeRange} refreshTick={refreshTick} />

      {/* Host / EC2 metrics */}
      <SectionHeader icon="mdi:server-outline" title="Host Metrics" subtitle="EC2 / Linux VM — OTel hostmetrics" accent="#2563EB" />
      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-4">
        {HOST_PANELS.map(panel => (
          <MetricPanel key={panel.id} {...panel} step={timeRange.step} startTime={startTime} endTime={endTime} refreshTick={refreshTick} />
        ))}
      </div>

      {/* Kubernetes metrics */}
      <SectionHeader icon="mdi:kubernetes" title="Kubernetes Metrics" subtitle="Nodes · Pods · Containers — kubeletstats" accent="#326CE5" />
      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
        {K8S_PANELS.map(panel => (
          <MetricPanel key={panel.id} {...panel} step={timeRange.step} startTime={startTime} endTime={endTime} refreshTick={refreshTick} />
        ))}
      </div>

    </div>
  );
};

export default MetricsDashboard;
