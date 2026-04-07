import React, { useEffect, useState, useRef, useCallback } from 'react';
import { useTheme } from '../../contexts/ThemeContext';
import {
  AreaChart, Area, LineChart, Line, BarChart, Bar,
  XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend,
} from 'recharts';
import { useObservabilityData, MetricResult } from '../../hooks/useObservabilityData';
import clsx from 'clsx';

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
  color: string;
  chartType?: 'area' | 'line' | 'bar';
}

// Grouped by host_name → each EC2 / VM gets its own colored series automatically.
// When you add a second EC2, it appears as a new color without any config change.
const HOST_PANELS: PanelDef[] = [
  {
    id: 'cpu',    label: 'CPU Usage — per host',
    query: '(1 - avg by (host_name) (system_cpu_utilization{state="idle"})) * 100',
    unit: '%', color: '#7c3aed', chartType: 'area',
  },
  {
    id: 'memory', label: 'Memory Usage — per host',
    query: 'avg by (host_name) (system_memory_utilization{state="used"}) * 100',
    unit: '%', color: '#0ea5e9', chartType: 'area',
  },
  {
    id: 'load1',  label: 'Load Avg 1m — per host',
    query: 'avg by (host_name) (system_cpu_load_average_1m)',
    unit: '', color: '#a855f7', chartType: 'line',
  },
  {
    id: 'net_in', label: 'Network In — per host',
    query: 'sum by (host_name) (rate(system_network_io{direction="receive"}[5m])) / 1024',
    unit: 'MB/s', color: '#10b981', chartType: 'area',
  },
  {
    id: 'net_out', label: 'Network Out — per host',
    query: 'sum by (host_name) (rate(system_network_io{direction="transmit"}[5m])) / 1024',
    unit: 'MB/s', color: '#f59e0b', chartType: 'area',
  },
  {
    id: 'disk_r', label: 'Disk Read — per host',
    query: 'sum by (host_name) (rate(system_disk_io{direction="read"}[5m])) / 1024',
    unit: 'MB/s', color: '#ec4899', chartType: 'area',
  },
  {
    id: 'disk_w', label: 'Disk Write — per host',
    query: 'sum by (host_name) (rate(system_disk_io{direction="write"}[5m])) / 1024',
    unit: 'MB/s', color: '#f97316', chartType: 'area',
  },
  {
    id: 'fs',     label: 'Filesystem Used — per host',
    query: 'sum by (host_name) (system_filesystem_usage{state="used"}) / 1024 / 1024 / 1024',
    unit: 'GB', color: '#06b6d4', chartType: 'bar',
  },
];

// K8s panels: grouped by node/pod/container — each resource gets its own color.
const K8S_PANELS: PanelDef[] = [
  {
    id: 'node_cpu',  label: 'Node CPU — per node',
    query: 'avg by (k8s_node_name) (k8s_node_cpu_utilization) * 100',
    unit: '%', color: '#7c3aed', chartType: 'area',
  },
  {
    id: 'node_mem',  label: 'Node Memory — per node',
    query: 'avg by (k8s_node_name) (k8s_node_memory_usage) / 1024 / 1024 / 1024',
    unit: 'GB', color: '#0ea5e9', chartType: 'area',
  },
  {
    id: 'pod_cpu',   label: 'Pod CPU — per pod',
    query: 'avg by (k8s_pod_name) (k8s_pod_cpu_utilization) * 100',
    unit: '%', color: '#10b981', chartType: 'line',
  },
  {
    id: 'pod_mem',   label: 'Pod Memory — per pod',
    query: 'avg by (k8s_pod_name) (k8s_pod_memory_working_set) / 1024 / 1024',
    unit: 'MB', color: '#f59e0b', chartType: 'line',
  },
  {
    id: 'pod_net',   label: 'Pod Network I/O',
    query: 'sum by (k8s_pod_name) (rate(k8s_pod_network_io[5m])) / 1024',
    unit: 'MB/s', color: '#ec4899', chartType: 'area',
  },
  {
    id: 'ctr_cpu',   label: 'Container CPU — per container',
    query: 'avg by (k8s_container_name) (container_cpu_utilization) * 100',
    unit: '%', color: '#a855f7', chartType: 'bar',
  },
];

const COLORS = ['#7c3aed', '#0ea5e9', '#10b981', '#f59e0b', '#ec4899', '#f97316', '#06b6d4', '#a855f7'];

// ── Single metric panel ───────────────────────────────────────────────────────

interface MetricPanelProps extends PanelDef {
  step: string;
  startTime: number;
  endTime: number;
  refreshTick: number;
}

const MetricPanel: React.FC<MetricPanelProps> = ({
  label, unit, color, query, step, startTime, endTime, chartType = 'area', refreshTick,
}) => {
  const { theme } = useTheme();
  const { queryMetrics } = useObservabilityData();
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

  const dark = theme === 'dark';
  const cardCls = clsx('rounded-2xl border p-4 shadow-sm', dark ? 'bg-slate-800/80 border-slate-700' : 'bg-white border-gray-200');
  const gridColor = dark ? '#334155' : '#e5e7eb';
  const tickColor = dark ? '#64748b' : '#9ca3af';

  const chartHeight = 80;

  function renderChart() {
    if (data.length === 0) return (
      <div className={clsx('flex items-center justify-center text-xs', dark ? 'text-slate-500' : 'text-gray-400')} style={{ height: chartHeight }}>
        No data
      </div>
    );

    const commonProps = {
      data,
      margin: { top: 2, right: 0, bottom: 0, left: 0 },
    };
    const tooltip = (
      <Tooltip
        contentStyle={{ background: dark ? '#1e293b' : '#fff', border: `1px solid ${color}`, borderRadius: 8, fontSize: 11 }}
        formatter={(v: any) => [formatVal(Number(v), unit), '']}
        labelFormatter={(ts: any) => new Date(ts * 1000).toLocaleTimeString()}
      />
    );

    if (chartType === 'bar') {
      return (
        <ResponsiveContainer width="100%" height={chartHeight}>
          <BarChart {...commonProps}>
            <XAxis dataKey="ts" hide />
            <YAxis hide />
            {tooltip}
            {seriesKeys.map((k, i) => <Bar key={k} dataKey={k} fill={COLORS[i % COLORS.length]} radius={[2, 2, 0, 0]} />)}
          </BarChart>
        </ResponsiveContainer>
      );
    }

    if (chartType === 'line') {
      return (
        <ResponsiveContainer width="100%" height={chartHeight}>
          <LineChart {...commonProps}>
            <XAxis dataKey="ts" hide />
            <YAxis hide />
            {tooltip}
            {seriesKeys.map((k, i) => (
              <Line key={k} type="monotone" dataKey={k} stroke={COLORS[i % COLORS.length]} strokeWidth={1.5} dot={false} />
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
                <stop offset="5%"  stopColor={COLORS[i % COLORS.length]} stopOpacity={0.3} />
                <stop offset="95%" stopColor={COLORS[i % COLORS.length]} stopOpacity={0} />
              </linearGradient>
            ))}
          </defs>
          <XAxis dataKey="ts" hide />
          <YAxis hide />
          {tooltip}
          {seriesKeys.map((k, i) => (
            <Area key={k} type="monotone" dataKey={k}
              stroke={COLORS[i % COLORS.length]} strokeWidth={1.5}
              fill={`url(#grad-${i})`} dot={false} />
          ))}
        </AreaChart>
      </ResponsiveContainer>
    );
  }

  return (
    <div className={cardCls}>
      <div className="flex items-start justify-between mb-2">
        <div className="flex-1 min-w-0 mr-2">
          <p className={clsx('text-xs font-semibold uppercase tracking-wide', dark ? 'text-slate-400' : 'text-gray-500')}>{label}</p>
          <p className={clsx('text-xl font-bold mt-0.5', dark ? 'text-white' : 'text-gray-900')}>
            {loading ? <span className="text-sm animate-pulse">Loading...</span> : currentVal}
          </p>
          {!loading && seriesKeys.length > 0 && (
            <div className="flex flex-wrap gap-1 mt-1">
              {seriesKeys.map((k, i) => {
                // For hostnames like ip-172-31-0-224.ap-south-1.compute.internal → show ip-172-31-0-224
                // For pod/container names → show as-is, truncate if long
                const short = k.includes('.') ? k.slice(0, k.indexOf('.')) : (k.length > 18 ? k.slice(0, 16) + '…' : k);
                return (
                  <span key={k} title={k}
                    className="inline-flex items-center text-[10px] rounded px-1.5 py-0.5 font-medium"
                    style={{ backgroundColor: COLORS[i % COLORS.length] + '22', color: COLORS[i % COLORS.length] }}>
                    {short}
                  </span>
                );
              })}
            </div>
          )}
        </div>
        <div className="w-2.5 h-2.5 rounded-full mt-1 flex-shrink-0" style={{ backgroundColor: color }} />
      </div>
      {loading
        ? <div className="flex items-center justify-center" style={{ height: chartHeight }}><div className="w-5 h-5 border-2 border-violet-600 border-t-transparent rounded-full animate-spin" /></div>
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

function PromQLEditor({ timeRange, refreshTick }: { timeRange: typeof TIME_RANGES[0]; refreshTick: number }) {
  const { theme } = useTheme();
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

  const dark = theme === 'dark';
  const inputCls = clsx('text-sm rounded-lg border px-3 py-2 focus:outline-none focus:ring-2 focus:ring-violet-500',
    dark ? 'bg-slate-700 border-slate-600 text-white placeholder-slate-400' : 'bg-white border-gray-300 text-gray-900 placeholder-gray-400');

  return (
    <div className={clsx('rounded-2xl border shadow-sm p-5 space-y-4', dark ? 'bg-slate-800/80 border-slate-700' : 'bg-white border-gray-200')}>
      <div className="flex items-center gap-2">
        <i className="fas fa-chart-line text-violet-500 text-sm" />
        <span className={clsx('text-sm font-semibold', dark ? 'text-white' : 'text-gray-800')}>PromQL Query Editor</span>
        <span className={clsx('text-xs px-2 py-0.5 rounded-full', dark ? 'bg-slate-700 text-slate-400' : 'bg-gray-100 text-gray-500')}>Prometheus-compatible</span>
      </div>

      <form onSubmit={handleRun} className="flex gap-2">
        <input type="text" value={promql} onChange={e => setPromql(e.target.value)}
          placeholder='e.g. (1 - avg(system_cpu_utilization{state="idle"})) * 100'
          className={clsx(inputCls, 'flex-1 font-mono')} />
        <button type="submit" disabled={loading || !promql.trim()}
          className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-violet-600 hover:bg-violet-700 text-white text-sm font-medium disabled:opacity-60 transition-colors whitespace-nowrap">
          <i className="fas fa-play text-xs" />
          {loading ? 'Running...' : 'Run'}
        </button>
      </form>

      <div className="flex flex-wrap gap-2">
        {PROMQL_TEMPLATES.map(t => (
          <button key={t.label} onClick={() => setPromql(t.query)}
            className={clsx('text-xs px-2.5 py-1 rounded-full border transition-colors',
              dark ? 'border-slate-600 text-slate-400 hover:bg-slate-700 hover:text-slate-200'
                   : 'border-gray-200 text-gray-500 hover:bg-violet-50 hover:border-violet-300 hover:text-violet-700')}>
            {t.label}
          </button>
        ))}
      </div>

      {error && <p className={clsx('text-xs px-3 py-2 rounded-lg', dark ? 'bg-red-900/20 text-red-400' : 'bg-red-50 text-red-600')}>{error}</p>}

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
              <CartesianGrid strokeDasharray="3 3" stroke={dark ? '#334155' : '#e5e7eb'} />
              <XAxis dataKey="ts"
                tickFormatter={(ts: number) => new Date(ts * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                tick={{ fontSize: 11 }} stroke={dark ? '#64748b' : '#9ca3af'} />
              <YAxis tick={{ fontSize: 11 }} stroke={dark ? '#64748b' : '#9ca3af'} width={45}
                tickFormatter={(v: number) => Number(v).toFixed(1)} />
              <Tooltip
                contentStyle={{ background: dark ? '#1e293b' : '#fff', border: '1px solid #7c3aed', borderRadius: 8, fontSize: 12 }}
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
            <div className={clsx('mt-3 rounded-xl overflow-hidden border text-xs', dark ? 'border-slate-700' : 'border-gray-200')}>
              <div className={clsx('px-4 py-2 font-semibold border-b', dark ? 'bg-slate-900 border-slate-700 text-slate-300' : 'bg-gray-50 border-gray-200 text-gray-700')}>
                {results.length} series
              </div>
              {results.slice(0, 8).map((r, i) => {
                const last = r.values[r.values.length - 1];
                return (
                  <div key={i} className={clsx('flex items-center justify-between px-4 py-2 border-b last:border-b-0',
                    dark ? 'border-slate-700/50' : 'border-gray-100')}>
                    <div className="flex items-center gap-2">
                      <span className="w-2 h-2 rounded-full flex-shrink-0" style={{ backgroundColor: COLORS[i % COLORS.length] }} />
                      <span className={clsx('font-mono truncate max-w-sm', dark ? 'text-slate-300' : 'text-gray-700')}>
                        {Object.entries(r.metric).filter(([k]) => k !== '__name__').map(([k, v]) => `${k}="${v}"`).join(', ') || 'value'}
                      </span>
                    </div>
                    <span className={clsx('font-semibold ml-4 flex-shrink-0', dark ? 'text-white' : 'text-gray-900')}>
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

function SectionHeader({ icon, title, subtitle, dark }: { icon: string; title: string; subtitle: string; dark: boolean }) {
  return (
    <div className={clsx('rounded-2xl border px-5 py-3', dark ? 'bg-slate-800/40 border-slate-700' : 'bg-gray-50 border-gray-200')}>
      <div className="flex items-center gap-2">
        <i className={clsx(icon, 'text-violet-500 text-sm')} />
        <p className={clsx('text-sm font-semibold', dark ? 'text-white' : 'text-gray-800')}>{title}</p>
        <span className={clsx('text-xs', dark ? 'text-slate-500' : 'text-gray-400')}>{subtitle}</span>
      </div>
    </div>
  );
}

// ── Main MetricsDashboard ─────────────────────────────────────────────────────

const REFRESH_INTERVAL_MS = 30_000;

const MetricsDashboard: React.FC = () => {
  const { theme } = useTheme();
  const dark = theme === 'dark';

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

  const endTime = Date.now() + refreshTick * 0; // re-evaluate on tick
  const startTime = endTime - timeRange.ms;

  return (
    <div className="space-y-4">

      {/* Toolbar */}
      <div className="flex items-center justify-between flex-wrap gap-3">
        <div className="flex items-center gap-2">
          <span className={clsx('text-xs font-medium', dark ? 'text-slate-400' : 'text-gray-500')}>Range:</span>
          {TIME_RANGES.map(t => (
            <button key={t.label} onClick={() => setTimeRange(t)}
              className={clsx('text-xs px-3 py-1.5 rounded-lg border font-medium transition-colors',
                timeRange.label === t.label
                  ? 'bg-violet-600 border-violet-600 text-white'
                  : dark ? 'border-slate-600 text-slate-300 hover:bg-slate-700' : 'border-gray-200 text-gray-600 hover:bg-gray-50')}>
              {t.label}
            </button>
          ))}
        </div>

        <div className="flex items-center gap-3">
          {/* Live indicator */}
          <button onClick={() => setLiveEnabled(v => !v)}
            className={clsx('flex items-center gap-1.5 text-xs px-3 py-1.5 rounded-lg border font-medium transition-colors',
              liveEnabled
                ? dark ? 'bg-emerald-900/30 border-emerald-700 text-emerald-400' : 'bg-emerald-50 border-emerald-300 text-emerald-700'
                : dark ? 'border-slate-600 text-slate-400' : 'border-gray-200 text-gray-500')}>
            <span className={clsx('w-2 h-2 rounded-full', liveEnabled ? 'bg-emerald-500 animate-pulse' : 'bg-gray-400')} />
            {liveEnabled ? 'Live' : 'Paused'}
          </button>

          <span className={clsx('text-xs', dark ? 'text-slate-500' : 'text-gray-400')}>
            Updated {lastRefresh.toLocaleTimeString()}
          </span>

          <button onClick={doRefresh}
            className={clsx('text-xs px-3 py-1.5 rounded-lg border transition-colors',
              dark ? 'border-slate-600 text-slate-300 hover:bg-slate-700' : 'border-gray-200 text-gray-600 hover:bg-gray-50')}>
            ↻ Refresh
          </button>
        </div>
      </div>

      {/* PromQL editor */}
      <PromQLEditor timeRange={timeRange} refreshTick={refreshTick} />

      {/* Host / EC2 metrics */}
      <SectionHeader icon="fas fa-server" title="Host Metrics" subtitle="EC2 / Linux VM — OTel hostmetrics" dark={dark} />
      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-4">
        {HOST_PANELS.map(panel => (
          <MetricPanel key={panel.id} {...panel} step={timeRange.step} startTime={startTime} endTime={endTime} refreshTick={refreshTick} />
        ))}
      </div>

      {/* Kubernetes metrics */}
      <SectionHeader icon="fas fa-dharmachakra" title="Kubernetes Metrics" subtitle="Nodes · Pods · Containers — kubeletstats" dark={dark} />
      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
        {K8S_PANELS.map(panel => (
          <MetricPanel key={panel.id} {...panel} step={timeRange.step} startTime={startTime} endTime={endTime} refreshTick={refreshTick} />
        ))}
      </div>

    </div>
  );
};

export default MetricsDashboard;
