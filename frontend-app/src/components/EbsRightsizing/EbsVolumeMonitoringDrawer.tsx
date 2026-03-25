import { useState, useEffect, useCallback } from 'react';
import { createPortal } from 'react-dom';
import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip,
  ResponsiveContainer,
} from 'recharts';
import { EbsVolume } from '../../hooks/useEbsRightsizingData';

// ── Types ─────────────────────────────────────────────────────────────────────

interface TimePoint { ts: number; v: number; }

interface MonitoringMetrics {
  readBytes?:      TimePoint[];
  writeBytes?:     TimePoint[];
  readOps?:        TimePoint[];
  writeOps?:       TimePoint[];
  totalReadTime?:  TimePoint[];
  totalWriteTime?: TimePoint[];
  queueLength?:    TimePoint[];
  idleTime?:       TimePoint[];
  burstBalance?:   TimePoint[];
  consumedOps?:    TimePoint[];
}

interface MonitoringData {
  volumeId: string;
  region:   string;
  period:   number;
  metrics:  MonitoringMetrics;
}

type TimeRange = '1' | '3' | '12' | '24' | '72' | '168';

const TIME_RANGES: { label: string; value: TimeRange }[] = [
  { label: '1h',  value: '1'   },
  { label: '3h',  value: '3'   },
  { label: '12h', value: '12'  },
  { label: '1d',  value: '24'  },
  { label: '3d',  value: '72'  },
  { label: '1w',  value: '168' },
];

// ── Helpers ───────────────────────────────────────────────────────────────────

function fmtTick(ts: number, hours: number): string {
  const d = new Date(ts);
  if (hours <= 3)  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  if (hours <= 24) return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  return d.toLocaleDateString([], { month: 'short', day: 'numeric' });
}

function fmtTooltipLabel(ts: number): string {
  return new Date(ts).toLocaleString([], {
    month: 'short', day: 'numeric',
    hour: '2-digit', minute: '2-digit',
  });
}

function buildIndex(pts?: TimePoint[]): Map<number, number> {
  const m = new Map<number, number>();
  pts?.forEach(p => m.set(p.ts, p.v));
  return m;
}

/** Compute (a / b) * scale for each aligned timestamp, skipping zero denominators */
function computeRatio(
  a: TimePoint[] | undefined,
  b: TimePoint[] | undefined,
  scale = 1,
): TimePoint[] {
  if (!a?.length || !b?.length) return [];
  const bi = buildIndex(b);
  return a
    .map(p => {
      const bv = bi.get(p.ts);
      return { ts: p.ts, v: bv && bv > 0 ? (p.v / bv) * scale : 0 };
    })
    .filter(p => p.v > 0);
}

/** Divide each value by a constant */
function normalize(pts: TimePoint[] | undefined, scale: number): TimePoint[] {
  if (!pts?.length) return [];
  return pts.map(p => ({ ts: p.ts, v: p.v / scale }));
}

// ── Chart definitions ─────────────────────────────────────────────────────────

interface ChartDef {
  id:          string;
  title:       string;
  unit:        string;
  color:       string;
  getData:     (m: MonitoringMetrics, period: number) => TimePoint[];
  precision?:  number;
  optional?:   boolean;  // hide if no data
}

const CHART_DEFS: ChartDef[] = [
  {
    id: 'readLatency',
    title: 'Volume average read latency',
    unit: 'ms',
    color: '#6366f1',
    getData: (m) => computeRatio(m.totalReadTime, m.readOps, 1000),
    precision: 3,
  },
  {
    id: 'writeLatency',
    title: 'Volume average write latency',
    unit: 'ms',
    color: '#8b5cf6',
    getData: (m) => computeRatio(m.totalWriteTime, m.writeOps, 1000),
    precision: 3,
  },
  {
    id: 'queueLength',
    title: 'Average queue length',
    unit: 'ops',
    color: '#f59e0b',
    getData: (m) => m.queueLength || [],
    precision: 2,
  },
  {
    id: 'idleTime',
    title: 'Time spent idle',
    unit: '%',
    color: '#10b981',
    getData: (m, p) => normalize(m.idleTime, p).map(tp => ({ ts: tp.ts, v: tp.v * 100 })),
    precision: 1,
  },
  {
    id: 'readThroughput',
    title: 'Read throughput',
    unit: 'KiB/s',
    color: '#3b82f6',
    getData: (m, p) => normalize(m.readBytes, p * 1024),
    precision: 2,
  },
  {
    id: 'writeThroughput',
    title: 'Write throughput',
    unit: 'KiB/s',
    color: '#0ea5e9',
    getData: (m, p) => normalize(m.writeBytes, p * 1024),
    precision: 2,
  },
  {
    id: 'readOps',
    title: 'Read operations',
    unit: 'Ops/s',
    color: '#22c55e',
    getData: (m, p) => normalize(m.readOps, p),
    precision: 2,
  },
  {
    id: 'writeOps',
    title: 'Write operations',
    unit: 'Ops/s',
    color: '#84cc16',
    getData: (m, p) => normalize(m.writeOps, p),
    precision: 2,
  },
  {
    id: 'avgReadSize',
    title: 'Average read size',
    unit: 'KiB/op',
    color: '#f97316',
    getData: (m) => computeRatio(m.readBytes, m.readOps, 1 / 1024),
    precision: 2,
  },
  {
    id: 'avgWriteSize',
    title: 'Average write size',
    unit: 'KiB/op',
    color: '#ef4444',
    getData: (m) => computeRatio(m.writeBytes, m.writeOps, 1 / 1024),
    precision: 2,
  },
  {
    id: 'burstBalance',
    title: 'Burst balance',
    unit: '%',
    color: '#a78bfa',
    getData: (m) => m.burstBalance || [],
    precision: 1,
    optional: true,
  },
  {
    id: 'consumedOps',
    title: 'IOPS consumed (io1/io2)',
    unit: 'Ops',
    color: '#f43f5e',
    getData: (m, p) => normalize(m.consumedOps, p),
    precision: 1,
    optional: true,
  },
];

// ── Single chart card ─────────────────────────────────────────────────────────

function MetricChart({
  def, data, hours, loading,
}: {
  def:     ChartDef;
  data:    TimePoint[];
  hours:   number;
  loading: boolean;
}) {
  const prec = def.precision ?? 2;
  const chartData = data.map(p => ({
    ts:    p.ts,
    v:     Math.round(p.v * Math.pow(10, prec)) / Math.pow(10, prec),
    label: fmtTooltipLabel(p.ts),
    tick:  fmtTick(p.ts, hours),
  }));

  const peak = chartData.length ? Math.max(...chartData.map(d => d.v)) : 0;

  return (
    <div className="rounded-xl border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 p-4">
      <div className="flex items-center justify-between mb-2">
        <span className="text-xs font-semibold text-gray-700 dark:text-gray-300 leading-tight">
          {def.title}
        </span>
        <span className="text-[10px] text-gray-400 font-medium ml-2 shrink-0">{def.unit}</span>
      </div>

      {loading ? (
        <>
          <div className="h-4 w-20 bg-gray-100 dark:bg-gray-700 rounded animate-pulse mb-2" />
          <div className="h-20 bg-gray-100 dark:bg-gray-700 rounded animate-pulse" />
        </>
      ) : chartData.length === 0 ? (
        <div className="h-24 flex items-center justify-center text-xs text-gray-400">
          No data in this period
        </div>
      ) : (
        <>
          <div className="text-sm font-bold text-gray-800 dark:text-gray-100 mb-2">
            {peak.toFixed(prec)}{' '}
            <span className="text-xs font-normal text-gray-400">{def.unit}</span>
          </div>
          <ResponsiveContainer width="100%" height={80}>
            <AreaChart data={chartData} margin={{ top: 2, right: 2, bottom: 0, left: 0 }}>
              <defs>
                <linearGradient id={`ebs-grad-${def.id}`} x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%"  stopColor={def.color} stopOpacity={0.35} />
                  <stop offset="95%" stopColor={def.color} stopOpacity={0.02} />
                </linearGradient>
              </defs>
              <CartesianGrid
                strokeDasharray="3 3"
                stroke="currentColor"
                className="text-gray-200 dark:text-gray-700"
                strokeOpacity={0.6}
              />
              <XAxis
                dataKey="tick"
                tick={{ fontSize: 9, fill: '#9ca3af' }}
                tickLine={false}
                axisLine={false}
                interval="preserveStartEnd"
              />
              <YAxis
                tick={{ fontSize: 9, fill: '#9ca3af' }}
                tickLine={false}
                axisLine={false}
                width={32}
                tickFormatter={(v: number) =>
                  v >= 1000 ? `${(v / 1000).toFixed(1)}k` : String(v)
                }
              />
              <Tooltip
                contentStyle={{
                  backgroundColor: '#111827',
                  border: 'none',
                  borderRadius: '8px',
                  fontSize: '11px',
                  color: '#f9fafb',
                  padding: '6px 10px',
                }}
                formatter={((val: number) => [`${val} ${def.unit}`, def.title]) as any}
                labelFormatter={(_l, payload) =>
                  payload?.[0]?.payload?.label ?? ''
                }
              />
              <Area
                type="monotone"
                dataKey="v"
                stroke={def.color}
                strokeWidth={1.5}
                fill={`url(#ebs-grad-${def.id})`}
                dot={false}
                activeDot={{ r: 3, fill: def.color, strokeWidth: 0 }}
              />
            </AreaChart>
          </ResponsiveContainer>
        </>
      )}
    </div>
  );
}

// ── Main drawer ───────────────────────────────────────────────────────────────

interface Props {
  volume:    EbsVolume | null;
  accountId: string;
  onClose:   () => void;
}

export function EbsVolumeMonitoringDrawer({ volume, accountId, onClose }: Props) {
  const [timeRange, setTimeRange] = useState<TimeRange>('1');
  const [monData,   setMonData]   = useState<MonitoringData | null>(null);
  const [loading,   setLoading]   = useState(false);

  const fetchMonitoring = useCallback(async () => {
    if (!volume || !accountId) return;
    setLoading(true);
    setMonData(null);
    try {
      const res = await fetch(
        `/api/ebs/monitoring/${accountId}/${volume.volumeId}?region=${encodeURIComponent(volume.region)}&hours=${timeRange}`,
        { credentials: 'include' },
      );
      if (res.ok) setMonData(await res.json());
    } catch (e) {
      console.error('[EBS Monitoring]', e);
    } finally {
      setLoading(false);
    }
  }, [volume, accountId, timeRange]);

  useEffect(() => {
    if (volume) fetchMonitoring();
  }, [volume, timeRange]);

  // Close on Escape
  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [onClose]);

  // Lock scroll on the main content container while drawer is open
  useEffect(() => {
    if (!volume) return;
    const el = document.getElementById('main-content');
    if (!el) return;
    const prev = el.style.overflowY;
    el.style.overflowY = 'hidden';
    return () => { el.style.overflowY = prev; };
  }, [volume]);

  if (!volume) return null;

  const metrics = monData?.metrics ?? {};
  const period  = monData?.period  ?? 60;
  const hours   = parseInt(timeRange);

  const visibleCharts = CHART_DEFS.filter(def => {
    if (!def.optional) return true;
    // Only show optional charts if data exists (after loading completes)
    return loading || (def.getData(metrics, period)).length > 0;
  });

  return createPortal(
    <>
      {/* Backdrop */}
      <div
        className="fixed inset-0 bg-black/40 backdrop-blur-sm z-40"
        onClick={onClose}
      />

      {/* Drawer panel */}
      <div className="fixed right-0 top-0 h-full w-full max-w-4xl bg-gray-50 dark:bg-gray-900 shadow-2xl z-50 flex flex-col">

        {/* Header */}
        <div className="px-6 py-4 border-b border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 flex items-start justify-between shrink-0">
          <div className="min-w-0">
            <div className="flex items-center gap-2 mb-1">
              <i className="fas fa-hdd text-indigo-500 text-sm shrink-0" />
              <span className="text-base font-bold text-gray-900 dark:text-white font-mono truncate">
                {volume.volumeId}
              </span>
              {volume.volumeName && (
                <span className="text-xs text-gray-400 font-sans shrink-0">
                  ({volume.volumeName})
                </span>
              )}
            </div>
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-gray-500 dark:text-gray-400">
              <span><i className="fas fa-map-marker-alt mr-1" />{volume.region}</span>
              <span className="px-1.5 py-0.5 rounded bg-blue-50 dark:bg-blue-900/30 text-blue-600 dark:text-blue-300 font-medium">
                {volume.volumeType}
              </span>
              <span>{volume.sizeGb} GB</span>
              {volume.instanceId && (
                <span className="font-mono">
                  → {volume.instanceId}
                  {volume.device ? ` (${volume.device})` : ''}
                </span>
              )}
              {volume.usedPercent !== undefined && (
                <span className="text-green-600 dark:text-green-400">
                  {volume.usedPercent}% used
                </span>
              )}
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-2 ml-4 shrink-0 rounded-lg hover:bg-gray-100 dark:hover:bg-gray-700 text-gray-500 dark:text-gray-400 transition-colors"
            title="Close (Esc)"
          >
            <i className="fas fa-times text-sm" />
          </button>
        </div>

        {/* Time range bar */}
        <div className="px-6 py-3 border-b border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 flex items-center gap-3 shrink-0">
          <span className="text-xs font-medium text-gray-500 dark:text-gray-400">Period:</span>
          <div className="flex items-center rounded-lg border border-gray-200 dark:border-gray-600 overflow-hidden">
            {TIME_RANGES.map(({ label, value }) => (
              <button
                key={value}
                onClick={() => setTimeRange(value)}
                className={`px-3 py-1.5 text-xs font-medium transition-colors ${
                  timeRange === value
                    ? 'bg-indigo-600 text-white'
                    : 'text-gray-600 dark:text-gray-400 hover:bg-gray-100 dark:hover:bg-gray-700'
                }`}
              >
                {label}
              </button>
            ))}
          </div>
          <button
            onClick={fetchMonitoring}
            disabled={loading}
            className="p-1.5 rounded-lg border border-gray-200 dark:border-gray-600 text-gray-500 dark:text-gray-400 hover:bg-gray-100 dark:hover:bg-gray-700 transition-colors disabled:opacity-50"
            title="Refresh"
          >
            <i className={`fas fa-sync-alt text-xs ${loading ? 'animate-spin' : ''}`} />
          </button>
          {loading && (
            <span className="text-xs text-indigo-400 font-medium">
              <i className="fas fa-circle-notch fa-spin mr-1" />Loading metrics…
            </span>
          )}
        </div>

        {/* Charts grid */}
        <div className="flex-1 overflow-y-auto p-4">
          <div className="grid grid-cols-2 gap-4">
            {visibleCharts.map(def => (
              <MetricChart
                key={def.id}
                def={def}
                data={loading ? [] : def.getData(metrics, period)}
                hours={hours}
                loading={loading}
              />
            ))}
          </div>

          {/* Footer note */}
          {!loading && (
            <p className="text-center text-xs text-gray-400 mt-4 pb-2">
              Metrics from <span className="font-medium">AWS/EBS</span> CloudWatch namespace ·
              Period: {period}s · UTC timestamps
            </p>
          )}
        </div>
      </div>
    </>,
    document.body,
  );
}
