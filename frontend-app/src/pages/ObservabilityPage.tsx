import React, { useEffect, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { useTopbar } from '../contexts/TopbarContext';
import { useObservabilityData, ObservabilityConfig, OverviewStats, EksCluster, ConnectedInstance, ObservabilityAlert, AlertDestination } from '../hooks/useObservabilityData';
import LogsExplorer from '../components/Observability/LogsExplorer';
import MetricsDashboard from '../components/Observability/MetricsDashboard';
import { Icon } from '@iconify/react';

// ── Tab types ─────────────────────────────────────────────────────────────────

type Tab = 'overview' | 'logs' | 'metrics' | 'traces' | 'alerts' | 'setup';

function useActiveTab(): Tab {
  const { pathname } = useLocation();
  if (pathname.startsWith('/observability/logs'))    return 'logs';
  if (pathname.startsWith('/observability/metrics')) return 'metrics';
  if (pathname.startsWith('/observability/traces'))  return 'traces';
  if (pathname.startsWith('/observability/alerts'))  return 'alerts';
  if (pathname.startsWith('/observability/setup'))   return 'setup';
  return 'overview';
}

// ── Formatters ────────────────────────────────────────────────────────────────

function fmtEvents(n: number): string {
  if (!n) return '0';
  if (n >= 1_000_000_000) return (n / 1_000_000_000).toFixed(1) + 'B';
  if (n >= 1_000_000)     return (n / 1_000_000).toFixed(1) + 'M';
  if (n >= 1_000)         return (n / 1_000).toFixed(1) + 'K';
  return String(n);
}

function fmtMb(mb: number): string {
  if (!mb) return '0 MB';
  if (mb >= 1024) return (mb / 1024).toFixed(2) + ' GB';
  return mb.toFixed(2) + ' MB';
}

// ── Overview Tab ──────────────────────────────────────────────────────────────

function relativeTime(ms: number): string {
  if (!ms) return 'unknown';
  const diff = Math.floor((Date.now() - ms) / 1000);
  if (diff < 60)   return `${diff}s ago`;
  if (diff < 3600) return `${Math.floor(diff / 60)}m ago`;
  return `${Math.floor(diff / 3600)}h ago`;
}

function OverviewTab({ config, overview, instances }: {
  config: ObservabilityConfig | null;
  overview: OverviewStats | null;
  instances: ConnectedInstance[];
}) {
  const statCards = [
    {
      label: 'Total Streams',
      value: overview?.streamCount ?? '—',
      icon: 'mdi:sine-wave',
      iconBox: 'w-9 h-9 rounded-xl bg-violet-50 border border-violet-100 flex items-center justify-center',
      iconColor: 'text-violet-500',
      border: 'border-l-violet-500',
      tooltip: 'Total number of data streams in your org — includes log, metric, and trace streams.',
    },
    {
      label: 'Log Streams',
      value: overview?.logStreamCount ?? '—',
      icon: 'mdi:file-document-outline',
      iconBox: (overview?.logStreamCount ?? 0) > 0
        ? 'w-9 h-9 rounded-xl bg-emerald-50 border border-emerald-100 flex items-center justify-center'
        : 'w-9 h-9 rounded-xl bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] flex items-center justify-center',
      iconColor: (overview?.logStreamCount ?? 0) > 0 ? 'text-emerald-500' : 'text-[rgba(0,0,0,0.4)]',
      border: (overview?.logStreamCount ?? 0) > 0 ? 'border-l-emerald-500' : 'border-l-[rgba(0,0,0,0.2)]',
      tooltip: 'Streams receiving log records — e.g. application logs, syslog, Docker container logs.',
    },
    {
      label: 'Metric Streams',
      value: overview?.metricStreamCount ?? '—',
      icon: 'mdi:speedometer',
      iconBox: (overview?.metricStreamCount ?? 0) > 0
        ? 'w-9 h-9 rounded-xl bg-emerald-50 border border-emerald-100 flex items-center justify-center'
        : 'w-9 h-9 rounded-xl bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] flex items-center justify-center',
      iconColor: (overview?.metricStreamCount ?? 0) > 0 ? 'text-emerald-500' : 'text-[rgba(0,0,0,0.4)]',
      border: (overview?.metricStreamCount ?? 0) > 0 ? 'border-l-emerald-500' : 'border-l-[rgba(0,0,0,0.2)]',
      tooltip: 'Streams receiving time-series metrics — e.g. CPU, memory, disk, network from your hosts.',
    },
    {
      label: 'Trace Streams',
      value: overview?.traceStreamCount ?? '—',
      icon: 'mdi:graph-outline',
      iconBox: (overview?.traceStreamCount ?? 0) > 0
        ? 'w-9 h-9 rounded-xl bg-emerald-50 border border-emerald-100 flex items-center justify-center'
        : 'w-9 h-9 rounded-xl bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] flex items-center justify-center',
      iconColor: (overview?.traceStreamCount ?? 0) > 0 ? 'text-emerald-500' : 'text-[rgba(0,0,0,0.4)]',
      border: (overview?.traceStreamCount ?? 0) > 0 ? 'border-l-emerald-500' : 'border-l-[rgba(0,0,0,0.2)]',
      tooltip: 'Streams receiving distributed traces — spans from your services via OTLP. Used to track request flows across microservices.',
    },
    {
      label: 'Retention',
      value: config ? `${config.retentionDays}d` : '—',
      icon: 'mdi:clock-outline',
      iconBox: 'w-9 h-9 rounded-xl bg-sky-50 border border-sky-100 flex items-center justify-center',
      iconColor: 'text-sky-500',
      border: 'border-l-sky-500',
      tooltip: 'How long your data is stored before being automatically deleted. Configurable under the Setup tab.',
    },
    {
      label: 'Org ID',
      value: overview?.orgId ?? config?.orgId ?? '—',
      icon: 'mdi:office-building-outline',
      iconBox: 'w-9 h-9 rounded-xl bg-pink-50 border border-pink-100 flex items-center justify-center',
      iconColor: 'text-pink-500',
      border: 'border-l-pink-500',
      tooltip: 'Your unique organisation identifier in OpenObserve. Used in the collector endpoint URL when sending data.',
    },
  ];

  return (
    <div className="space-y-6">
      {/* Stat cards */}
      <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-6 gap-4">
        {statCards.map((c, i) => (
          <div key={i} className={`group relative rounded-2xl border border-l-4 p-4 shadow-sm flex flex-col gap-2 bg-white border-[rgba(0,0,0,0.12)] ${c.border}`}>
            <div className="flex items-start justify-between">
              <div className={c.iconBox}>
                <Icon icon={c.icon} className={`${c.iconColor} text-base`} />
              </div>
              <Icon icon="mdi:information-outline" className="text-[11px] mt-1 opacity-0 group-hover:opacity-100 transition-opacity text-[rgba(0,0,0,0.4)]" />
            </div>
            <p className="text-[10px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.5)]">
              {c.label}
            </p>
            <p style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '24px', fontWeight: 400, lineHeight: '24px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>
              {String(c.value)}
            </p>
            {/* Tooltip */}
            <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-2 w-52 rounded-xl px-3 py-2 text-[11px] leading-snug shadow-lg z-50 opacity-0 group-hover:opacity-100 pointer-events-none transition-opacity duration-150 bg-gray-900 text-white">
              {c.tooltip}
              <div className="absolute top-full left-1/2 -translate-x-1/2 w-0 h-0 border-l-4 border-r-4 border-t-4 border-l-transparent border-r-transparent border-t-gray-900" />
            </div>
          </div>
        ))}
      </div>

      {/* Ingestion KPI cards */}
      {overview && (
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5">
          <p className="text-xs font-semibold uppercase tracking-wider mb-4 text-[rgba(0,0,0,0.5)]">
            Ingestion Stats
          </p>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
            {[
              { label: 'Events', value: fmtEvents(overview.totalEvents), icon: 'mdi:database-outline', color: 'text-violet-500', tooltip: 'Total number of individual log records, metric data points, and trace spans ingested across all streams.' },
              { label: 'Ingested Size', value: fmtMb(overview.ingestedSizeMb), icon: 'mdi:arrow-down', color: 'text-sky-500', tooltip: 'Raw uncompressed size of all data received. This is what your agents sent over the network.' },
              { label: 'Compressed Size', value: fmtMb(overview.compressedSizeMb), icon: 'mdi:arrow-collapse', color: 'text-emerald-500', tooltip: 'Actual disk space used after compression. Typically 10–20x smaller than the ingested size.' },
              { label: 'Index Size', value: fmtMb(overview.indexSizeMb), icon: 'mdi:magnify', color: 'text-amber-500', tooltip: 'Size of the search index. Larger indexes improve query speed but use more storage.' },
            ].map((k, i) => (
              <div key={i} className="group relative rounded-xl p-4 flex items-center gap-3 bg-[rgba(0,0,0,0.02)]">
                <Icon icon={k.icon} className={`${k.color} text-lg w-5 text-center flex-shrink-0`} />
                <div className="min-w-0">
                  <p className="text-[11px] font-medium text-[rgba(0,0,0,0.5)]">
                    {k.label}
                  </p>
                  <p className="text-base font-bold leading-tight mt-0.5 text-[rgba(0,0,0,0.88)]">
                    {k.value}
                  </p>
                </div>
                {/* Tooltip */}
                <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-2 w-56 rounded-xl px-3 py-2 text-[11px] leading-snug shadow-lg z-50 opacity-0 group-hover:opacity-100 pointer-events-none transition-opacity duration-150 bg-gray-900 text-white">
                  {k.tooltip}
                  <div className="absolute top-full left-1/2 -translate-x-1/2 w-0 h-0 border-l-4 border-r-4 border-t-4 border-l-transparent border-r-transparent border-t-gray-900" />
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Connected Instances */}
      {instances.length > 0 && (
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
          <div className="px-5 py-3 border-b border-[rgba(0,0,0,0.06)] flex items-center justify-between">
            <div className="flex items-center gap-2">
              <Icon icon="mdi:server-outline" className="text-emerald-500 text-xs" />
              <span className="font-semibold text-sm text-[rgba(0,0,0,0.88)]">
                Connected Instances
              </span>
            </div>
            <span className="text-xs font-bold px-2 py-0.5 rounded-full bg-emerald-100 text-emerald-700">
              {instances.length}
            </span>
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 xl:grid-cols-4 gap-3 p-4">
            {instances.map(inst => {
              const short = inst.hostName.includes('.')
                ? inst.hostName.split('.')[0]
                : inst.hostName.length > 22 ? inst.hostName.slice(0, 20) + '…' : inst.hostName;
              const ageMins = (Date.now() - inst.lastSeenMs) / 60_000;
              const isOnline = ageMins < 15;
              return (
                <div key={inst.hostName}
                  className="flex items-center gap-3 rounded-xl border px-3 py-2.5 bg-[rgba(0,0,0,0.02)] border-[rgba(0,0,0,0.08)]">
                  <div className={`w-2 h-2 rounded-full flex-shrink-0 ${isOnline ? 'bg-emerald-500 shadow-[0_0_6px_2px_rgba(52,211,153,0.5)]' : 'bg-amber-400'}`} />
                  <div className="min-w-0 flex-1">
                    <p className="text-xs font-semibold truncate text-[rgba(0,0,0,0.88)]"
                       title={inst.hostName}>
                      {short}
                    </p>
                    <p className="text-[10px] mt-0.5 text-[rgba(0,0,0,0.4)]">
                      {isOnline ? 'Online' : 'Last seen'} · {relativeTime(inst.lastSeenMs)}
                    </p>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* Streams list */}
      {overview && overview.streams.length > 0 && (
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
          <div className="px-5 py-3 border-b border-[rgba(0,0,0,0.06)] flex items-center justify-between">
            <span className="font-semibold text-sm text-[rgba(0,0,0,0.88)]">
              Active Streams
            </span>
            <span className="text-xs font-medium text-[rgba(0,0,0,0.4)]">
              {overview.streams.length} total
            </span>
          </div>
          <div className="divide-y divide-[rgba(0,0,0,0.06)] max-h-96 overflow-y-auto">
            {overview.streams.slice(0, 50).map((s, i) => {
              const typeBadge: Record<string, string> = {
                logs:    'bg-violet-100 text-violet-600',
                metrics: 'bg-sky-100 text-sky-600',
                traces:  'bg-amber-100 text-amber-600',
              };
              const typeKey = s.stream_type ?? 'logs';
              const badgeCls = typeBadge[typeKey] ?? 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.5)]';
              return (
                <div key={i} className="flex items-center justify-between px-5 py-2.5 hover:bg-[rgba(0,0,0,0.025)]">
                  <div className="flex items-center gap-2.5 min-w-0">
                    <span className="w-2 h-2 flex-shrink-0 rounded-full bg-emerald-500" />
                    <span className="text-sm font-medium truncate text-[rgba(0,0,0,0.7)]">
                      {s.name}
                    </span>
                    <span className={`flex-shrink-0 text-[10px] font-semibold px-1.5 py-0.5 rounded-full uppercase tracking-wide ${badgeCls}`}>
                      {typeKey}
                    </span>
                  </div>
                  <span className="text-xs flex-shrink-0 ml-2 text-[rgba(0,0,0,0.4)]">
                    {s.stats?.doc_count ? `${s.stats.doc_count.toLocaleString()} events` : ''}
                  </span>
                </div>
              );
            })}
            {overview.streams.length > 50 && (
              <div className="px-5 py-3 text-xs text-center text-[rgba(0,0,0,0.4)]">
                +{overview.streams.length - 50} more streams in OpenObserve
              </div>
            )}
          </div>
        </div>
      )}

      {/* Empty state */}
      {(!overview || overview.streamCount === 0) && (
        <div className="rounded-2xl border border-dashed border-[rgba(0,0,0,0.12)] p-12 flex flex-col items-center gap-3 text-[rgba(0,0,0,0.4)]">
          <Icon icon="mdi:satellite-uplink" className="text-4xl opacity-30" />
          <p className="text-sm font-medium">No data yet</p>
          <p className="text-xs text-center max-w-xs">
            Go to the <strong>Setup</strong> tab to deploy a collector and start ingesting logs and metrics.
          </p>
        </div>
      )}
    </div>
  );
}

// ── Alerts Tab ────────────────────────────────────────────────────────────────

const ALERT_TEMPLATES: Array<{
  id: string; label: string; icon: string; color: string;
  severity: 'critical' | 'high' | 'medium';
  description: string;
  promql: (threshold: number) => string;
  defaultThreshold: number; unit: string;
}> = [
  {
    id: 'cpu', label: 'High CPU', icon: 'mdi:chip', color: 'text-red-500',
    severity: 'critical', description: 'Alert when average CPU utilization exceeds threshold',
    promql: (t) => `avg(system_cpu_utilization{state="user"}) by (host_name) > ${(t / 100).toFixed(2)}`,
    defaultThreshold: 80, unit: '%',
  },
  {
    id: 'memory', label: 'High Memory', icon: 'mdi:memory', color: 'text-orange-500',
    severity: 'high', description: 'Alert when memory usage exceeds threshold',
    promql: (t) => `(sum(system_memory_usage{state="used"}) by (host_name) / sum(system_memory_usage) by (host_name)) * 100 > ${t}`,
    defaultThreshold: 85, unit: '%',
  },
  {
    id: 'disk', label: 'High Disk', icon: 'mdi:harddisk', color: 'text-yellow-500',
    severity: 'high', description: 'Alert when disk usage exceeds threshold',
    promql: (t) => `(1 - (system_filesystem_usage{state="free"} / system_filesystem_usage{state="used"})) * 100 > ${t}`,
    defaultThreshold: 90, unit: '%',
  },
  {
    id: 'load', label: 'High Load', icon: 'mdi:speedometer', color: 'text-purple-500',
    severity: 'medium', description: 'Alert when 5-minute load average exceeds threshold',
    promql: (t) => `system_cpu_load_average_5m > ${t}`,
    defaultThreshold: 4, unit: '',
  },
];

const SEVERITY_STYLE: Record<string, string> = {
  critical: 'bg-red-100 text-red-700',
  high:     'bg-orange-100 text-orange-700',
  medium:   'bg-yellow-100 text-yellow-700',
};

function AlertsTab() {
  const { fetchAlerts, createAlert, deleteAlert, toggleAlert, fetchDestinations, saveDestination, deleteDestination } = useObservabilityData();

  const [alerts, setAlerts]             = useState<ObservabilityAlert[]>([]);
  const [destinations, setDestinations] = useState<AlertDestination[]>([]);
  const [loading, setLoading]           = useState(true);

  // Destination form
  const [showDestForm, setShowDestForm]   = useState(false);
  const [destName, setDestName]           = useState('');
  const [destUrl, setDestUrl]             = useState('');
  const [destMethod, setDestMethod]       = useState<'post' | 'get'>('post');
  const [savingDest, setSavingDest]       = useState(false);

  // Alert form (triggered from a template)
  const [activeTemplate, setActiveTemplate] = useState<typeof ALERT_TEMPLATES[0] | null>(null);
  const [threshold, setThreshold]           = useState(80);
  const [period, setPeriod]                 = useState(5);
  const [silence, setSilence]               = useState(30);
  const [selectedDest, setSelectedDest]     = useState('');
  const [savingAlert, setSavingAlert]       = useState(false);

  const card = 'rounded-2xl border border-[rgba(0,0,0,0.12)] bg-white shadow-sm p-5';
  const inputCls = 'w-full text-sm rounded-lg border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.04)] px-3 py-2 outline-none focus:border-[rgba(0,0,0,0.2)] text-[rgba(0,0,0,0.88)]';
  const labelCls = 'block text-xs font-medium mb-1 text-[rgba(0,0,0,0.5)]';

  const reload = async () => {
    setLoading(true);
    const [a, d] = await Promise.all([fetchAlerts(), fetchDestinations()]);
    setAlerts(a);
    setDestinations(d);
    setLoading(false);
  };

  useEffect(() => { reload(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const handleSaveDest = async () => {
    if (!destName.trim() || !destUrl.trim()) return;
    setSavingDest(true);
    await saveDestination({ name: destName.trim(), url: destUrl.trim(), method: destMethod });
    setSavingDest(false);
    setShowDestForm(false);
    setDestName(''); setDestUrl('');
    const d = await fetchDestinations();
    setDestinations(d);
  };

  const handleCreateAlert = async () => {
    if (!activeTemplate) return;
    setSavingAlert(true);
    const alertName = `${activeTemplate.id}_above_${threshold}`.replace(/[^a-z0-9_]/g, '_');
    const payload: ObservabilityAlert = {
      name: alertName,
      stream_type: 'metrics',
      stream_name: 'default',
      is_real_time: false,
      enabled: true,
      severity: activeTemplate.severity,
      description: `${activeTemplate.label} — threshold ${threshold}${activeTemplate.unit}`,
      destinations: selectedDest ? [selectedDest] : [],
      query_condition: { type: 'promql', promql: activeTemplate.promql(threshold) },
      trigger_condition: { period, frequency: 1, silence, threshold: 1, operator: '>=' },
    };
    await createAlert(payload);
    setSavingAlert(false);
    setActiveTemplate(null);
    const a = await fetchAlerts();
    setAlerts(a);
  };

  const handleDelete = async (a: ObservabilityAlert) => {
    await deleteAlert(a.stream_type, a.stream_name, a.name);
    setAlerts(prev => prev.filter(x => x.name !== a.name));
  };

  const handleToggle = async (a: ObservabilityAlert) => {
    await toggleAlert(a.stream_type, a.stream_name, a.name, !a.enabled);
    setAlerts(prev => prev.map(x => x.name === a.name ? { ...x, enabled: !x.enabled } : x));
  };

  return (
    <div className="space-y-6">

      {/* Destinations */}
      <div className={card}>
        <div className="flex items-center justify-between mb-4">
          <div>
            <p className="font-semibold text-sm text-[rgba(0,0,0,0.88)]">
              Notification Destinations
            </p>
            <p className="text-xs mt-0.5 text-[rgba(0,0,0,0.5)]">
              Where to send alerts — Slack webhook, PagerDuty, or any HTTP endpoint
            </p>
          </div>
          <button onClick={() => setShowDestForm(v => !v)}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-violet-600 hover:bg-violet-700 text-white text-xs font-medium transition-colors">
            <Icon icon="mdi:plus" className="text-[10px]" /> Add
          </button>
        </div>

        {showDestForm && (
          <div className="rounded-xl border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.02)] p-4 mb-4 space-y-3">
            <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
              <div>
                <label className={labelCls}>Name</label>
                <input className={inputCls} placeholder="e.g. slack-ops" value={destName} onChange={e => setDestName(e.target.value)} />
              </div>
              <div className="md:col-span-2">
                <label className={labelCls}>Webhook URL</label>
                <input className={inputCls} placeholder="https://hooks.slack.com/services/..." value={destUrl} onChange={e => setDestUrl(e.target.value)} />
              </div>
            </div>
            <div className="flex items-center gap-3">
              <div>
                <label className={labelCls}>Method</label>
                <select className={inputCls} style={{width:'auto'}} value={destMethod} onChange={e => setDestMethod(e.target.value as 'post'|'get')}>
                  <option value="post">POST</option>
                  <option value="get">GET</option>
                </select>
              </div>
              <div className="flex gap-2 mt-4">
                <button onClick={handleSaveDest} disabled={savingDest || !destName || !destUrl}
                  className="px-4 py-2 rounded-lg bg-violet-600 hover:bg-violet-700 text-white text-xs font-medium disabled:opacity-50 transition-colors">
                  {savingDest ? 'Saving…' : 'Save'}
                </button>
                <button onClick={() => setShowDestForm(false)}
                  className="px-4 py-2 rounded-lg text-xs font-medium border border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.7)] hover:bg-[rgba(0,0,0,0.04)] transition-colors">
                  Cancel
                </button>
              </div>
            </div>
          </div>
        )}

        {destinations.length === 0 ? (
          <p className="text-xs text-[rgba(0,0,0,0.4)]">
            No destinations yet. Add one to receive alert notifications.
          </p>
        ) : (
          <div className="space-y-2">
            {destinations.map(d => (
              <div key={d.name} className="flex items-center justify-between rounded-xl px-4 py-2.5 bg-[rgba(0,0,0,0.02)]">
                <div className="flex items-center gap-3">
                  <Icon icon="mdi:send-outline" className="text-violet-500 text-xs" />
                  <div>
                    <p className="text-sm font-medium text-[rgba(0,0,0,0.88)]">{d.name}</p>
                    <p className="text-[11px] truncate max-w-xs text-[rgba(0,0,0,0.5)]">{d.url}</p>
                  </div>
                </div>
                <button onClick={async () => { await deleteDestination(d.name); setDestinations(prev => prev.filter(x => x.name !== d.name)); }}
                  className="text-xs text-red-400 hover:text-red-600 transition-colors px-2">
                  <Icon icon="mdi:trash-can-outline" />
                </button>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Quick alert templates */}
      <div className={card}>
        <p className="font-semibold text-sm mb-1 text-[rgba(0,0,0,0.88)]">
          Quick Alert Templates
        </p>
        <p className="text-xs mb-4 text-[rgba(0,0,0,0.5)]">
          Click a template to configure and create an alert in seconds.
        </p>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          {ALERT_TEMPLATES.map(t => (
            <button key={t.id} onClick={() => { setActiveTemplate(t); setThreshold(t.defaultThreshold); }}
              className={
                activeTemplate?.id === t.id
                  ? 'rounded-xl border border-violet-500 bg-violet-50 p-4 text-left transition-all hover:shadow-md'
                  : 'rounded-xl border border-[rgba(0,0,0,0.12)] bg-white p-4 text-left transition-all hover:shadow-md hover:border-[rgba(0,0,0,0.2)]'
              }>
              <Icon icon={t.icon} className={`${t.color} text-lg mb-2 block`} />
              <p className="text-xs font-semibold text-[rgba(0,0,0,0.88)]">{t.label}</p>
              <p className="text-[10px] mt-0.5 leading-relaxed text-[rgba(0,0,0,0.5)]">{t.description}</p>
            </button>
          ))}
        </div>

        {/* Template config form */}
        {activeTemplate && (
          <div className="mt-4 rounded-xl border border-violet-200 bg-violet-50 p-4 space-y-4">
            <p className="text-sm font-semibold text-violet-700">
              Configure — {activeTemplate.label}
            </p>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
              <div>
                <label className={labelCls}>Threshold{activeTemplate.unit ? ` (${activeTemplate.unit})` : ''}</label>
                <input type="number" className={inputCls} value={threshold} onChange={e => setThreshold(Number(e.target.value))} />
              </div>
              <div>
                <label className={labelCls}>Evaluate over (min)</label>
                <input type="number" className={inputCls} value={period} onChange={e => setPeriod(Number(e.target.value))} min={1} />
              </div>
              <div>
                <label className={labelCls}>Re-alert silence (min)</label>
                <input type="number" className={inputCls} value={silence} onChange={e => setSilence(Number(e.target.value))} min={1} />
              </div>
              <div>
                <label className={labelCls}>Notify destination</label>
                <select className={inputCls} value={selectedDest} onChange={e => setSelectedDest(e.target.value)}>
                  <option value="">— none —</option>
                  {destinations.map(d => <option key={d.name} value={d.name}>{d.name}</option>)}
                </select>
              </div>
            </div>
            <div className="rounded-lg px-3 py-2 font-mono text-[11px] bg-white text-[rgba(0,0,0,0.7)] border border-[rgba(0,0,0,0.08)]">
              {activeTemplate.promql(threshold)}
            </div>
            <div className="flex gap-2">
              <button onClick={handleCreateAlert} disabled={savingAlert}
                className="px-4 py-2 rounded-lg bg-violet-600 hover:bg-violet-700 text-white text-xs font-medium disabled:opacity-50 transition-colors">
                {savingAlert ? 'Creating…' : 'Create Alert'}
              </button>
              <button onClick={() => setActiveTemplate(null)}
                className="px-4 py-2 rounded-lg text-xs font-medium border border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.7)] hover:bg-[rgba(0,0,0,0.04)] transition-colors">
                Cancel
              </button>
            </div>
          </div>
        )}
      </div>

      {/* Active alerts list */}
      <div className={card}>
        <div className="flex items-center justify-between mb-4">
          <p className="font-semibold text-sm text-[rgba(0,0,0,0.88)]">
            Active Alerts
          </p>
          <span className="text-xs font-medium px-2 py-0.5 rounded-full bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.7)]">
            {alerts.length}
          </span>
        </div>

        {loading ? (
          <div className="flex items-center gap-2 py-4">
            <div className="w-4 h-4 border-2 border-violet-600 border-t-transparent rounded-full animate-spin" />
            <span className="text-xs text-[rgba(0,0,0,0.4)]">Loading…</span>
          </div>
        ) : alerts.length === 0 ? (
          <div className="rounded-xl border-dashed border-2 border-[rgba(0,0,0,0.12)] p-8 flex flex-col items-center gap-2 text-[rgba(0,0,0,0.4)]">
            <Icon icon="mdi:bell-off-outline" className="text-3xl opacity-30" />
            <p className="text-sm">No alerts yet</p>
            <p className="text-xs">Use a template above to create your first alert.</p>
          </div>
        ) : (
          <div className="space-y-2">
            {alerts.map(a => (
              <div key={a.name} className="flex items-start gap-4 rounded-xl px-4 py-3 border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.02)]">
                <div className={`w-2 h-2 rounded-full mt-1.5 flex-shrink-0 ${a.enabled ? 'bg-emerald-500 shadow-[0_0_6px_2px_rgba(52,211,153,0.4)]' : 'bg-[rgba(0,0,0,0.2)]'}`} />
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="text-sm font-medium text-[rgba(0,0,0,0.88)]">
                      {a.name}
                    </span>
                    {a.severity && (
                      <span className={`text-[10px] font-bold uppercase px-1.5 py-0.5 rounded-full ${SEVERITY_STYLE[a.severity] ?? SEVERITY_STYLE.medium}`}>
                        {a.severity}
                      </span>
                    )}
                    <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.5)]">
                      {a.stream_type}
                    </span>
                  </div>
                  {a.description && (
                    <p className="text-xs mt-0.5 text-[rgba(0,0,0,0.5)]">
                      {a.description}
                    </p>
                  )}
                  {a.query_condition?.promql && (
                    <p className="text-[11px] font-mono mt-1 truncate text-[rgba(0,0,0,0.4)]">
                      {a.query_condition.promql}
                    </p>
                  )}
                </div>
                <div className="flex items-center gap-2 flex-shrink-0">
                  <button onClick={() => handleToggle(a)}
                    className={`text-xs px-2.5 py-1 rounded-lg border font-medium transition-colors ${
                      a.enabled
                        ? 'border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.7)] hover:bg-[rgba(0,0,0,0.04)]'
                        : 'border-emerald-500 text-emerald-600 hover:bg-emerald-50'
                    }`}>
                    {a.enabled ? 'Disable' : 'Enable'}
                  </button>
                  <button onClick={() => handleDelete(a)}
                    className="text-red-400 hover:text-red-600 transition-colors px-1">
                    <Icon icon="mdi:trash-can-outline" className="text-xs" />
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

    </div>
  );
}

// ── Traces Tab (placeholder) ──────────────────────────────────────────────────

function TracesTab() {
  return (
    <div className="rounded-2xl border border-dashed border-[rgba(0,0,0,0.12)] p-16 flex flex-col items-center gap-3 text-[rgba(0,0,0,0.4)]">
      <Icon icon="mdi:graph-outline" className="text-5xl opacity-30" />
      <p className="text-base font-semibold">Distributed Tracing</p>
      <p className="text-sm text-center max-w-sm">
        Instrument your services with OpenTelemetry SDK and deploy the OTel Collector
        to start seeing request traces across your infrastructure.
      </p>
      <span className="text-xs px-3 py-1 rounded-full bg-violet-100 text-violet-600 font-medium">
        Coming soon
      </span>
    </div>
  );
}

// ── Reusable code block with copy button ─────────────────────────────────────

function CodeBlock({ code, label }: { code: string; label?: string }) {
  const [copied, setCopied] = useState(false);

  const handleCopy = () => {
    navigator.clipboard.writeText(code);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="rounded-xl border border-[rgba(0,0,0,0.12)] overflow-hidden">
      {(label || true) && (
        <div className="flex items-center justify-between px-4 py-2 border-b border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.02)]">
          <span className="text-xs font-medium text-[rgba(0,0,0,0.5)]">
            {label || 'command'}
          </span>
          <button onClick={handleCopy}
            className={`text-xs px-2.5 py-1 rounded-lg border font-medium transition-colors flex items-center gap-1.5 ${
              copied
                ? 'bg-emerald-600 border-emerald-600 text-white'
                : 'border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.7)] hover:bg-[rgba(0,0,0,0.04)]'
            }`}>
            <Icon icon={copied ? 'mdi:check' : 'mdi:content-copy'} className="text-xs" />
            {copied ? 'Copied!' : 'Copy'}
          </button>
        </div>
      )}
      <pre className="rounded-b-xl p-4 font-mono text-xs leading-relaxed overflow-x-auto whitespace-pre bg-gray-900 border-t-0 text-slate-300">{code}</pre>
    </div>
  );
}

// ── Deploy Step ───────────────────────────────────────────────────────────────

function DeployStep({ num, title, children }: { num: number; title: string; children: React.ReactNode }) {
  return (
    <div className="flex gap-4">
      <div className="flex-shrink-0 w-7 h-7 rounded-full bg-violet-600 text-white text-xs font-bold flex items-center justify-center mt-0.5">
        {num}
      </div>
      <div className="flex-1 space-y-3">
        <p className="text-sm font-semibold text-[rgba(0,0,0,0.88)]">{title}</p>
        {children}
      </div>
    </div>
  );
}

// ── Setup Tab ─────────────────────────────────────────────────────────────────

type SetupType = 'eks-helm' | 'ec2' | 'gce' | 'eks-manual';

function SetupTab() {
  const { fetchSetupConfig, fetchClusters } = useObservabilityData();
  const [activeSetup, setActiveSetup] = useState<SetupType>('ec2');

  // EKS Helm
  const [helmCmd, setHelmCmd] = useState('');
  const [helmLoading, setHelmLoading] = useState(false);

  // EC2
  const [ec2Config, setEc2Config] = useState('');
  const [ec2Loading, setEc2Loading] = useState(false);

  // GCE
  const [gceConfig, setGceConfig] = useState('');
  const [gceLoading, setGceLoading] = useState(false);

  // EKS Manual
  const [agentSecret, setAgentSecret] = useState('');
  const [secretLoading, setSecretLoading] = useState(false);
  const [secretError, setSecretError] = useState(false);
  const [rbacYaml, setRbacYaml] = useState('');
  const [agentYaml, setAgentYaml] = useState('');

  // Cluster selector (EKS)
  const [clusters, setClusters] = useState<EksCluster[]>([]);
  const [selectedCluster, setSelectedCluster] = useState<EksCluster | null>(null);

  useEffect(() => {
    fetchClusters().then(list => {
      setClusters(list);
      if (list.length > 0) setSelectedCluster(list[0]);
    });
    fetchSetupConfig('agents/rbac').then(v => setRbacYaml(v || ''));
    fetchSetupConfig('agents/xamops-agent').then(v => setAgentYaml(v || ''));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (activeSetup === 'eks-helm' && !helmCmd) {
      setHelmLoading(true);
      fetchSetupConfig('helm-eks').then(v => { setHelmCmd(v || ''); setHelmLoading(false); });
    }
    if (activeSetup === 'ec2' && !ec2Config) {
      setEc2Loading(true);
      fetchSetupConfig('ec2-otel').then(v => { setEc2Config(v || ''); setEc2Loading(false); });
    }
    if (activeSetup === 'gce' && !gceConfig) {
      setGceLoading(true);
      fetchSetupConfig('gce-otel').then(v => { setGceConfig(v || ''); setGceLoading(false); });
    }
    if (activeSetup === 'eks-manual' && !agentSecret) {
      setSecretLoading(true);
      setSecretError(false);
      fetchSetupConfig('agent-secret').then(v => {
        setAgentSecret(v || '');
        setSecretError(!v);
        setSecretLoading(false);
      });
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeSetup]);

  const kubeconfigCmd = selectedCluster
    ? `aws eks update-kubeconfig --name ${selectedCluster.clusterName} --region ${selectedCluster.region}`
    : `aws eks update-kubeconfig --name <YOUR_CLUSTER_NAME> --region <YOUR_REGION>`;

  const setups: { id: SetupType; label: string; icon: string; badge?: string; comingSoon?: boolean; description: string }[] = [
    {
      id: 'ec2',
      label: 'EC2 / Linux VM',
      icon: 'mdi:server-outline',
      badge: 'Available',
      description: 'Install OTel Collector as a systemd service on any EC2 or Linux host',
    },
    {
      id: 'gce',
      label: 'GCE / Linux VM',
      icon: 'mdi:google',
      badge: 'Available',
      description: 'Install OTel Collector as a systemd service on any GCE or Linux host',
    },
    {
      id: 'eks-helm',
      label: 'EKS — Helm',
      icon: 'mdi:kubernetes',
      comingSoon: true,
      description: 'One Helm command deploys the full OpenObserve collector — logs + metrics + events',
    },
    {
      id: 'eks-manual',
      label: 'EKS — Manual',
      icon: 'mdi:file-code-outline',
      comingSoon: true,
      description: 'Step-by-step kubectl apply for advanced setups or air-gapped clusters',
    },
  ];

  const cardCls = 'rounded-2xl border border-[rgba(0,0,0,0.12)] bg-white shadow-sm p-5 space-y-4';
  const divider = <div className="border-t border-[rgba(0,0,0,0.06)]" />;

  const LoadingSpinner = () => (
    <div className="flex items-center gap-2 py-4">
      <div className="w-4 h-4 border-2 border-violet-600 border-t-transparent rounded-full animate-spin" />
      <span className="text-xs text-[rgba(0,0,0,0.4)]">Loading…</span>
    </div>
  );

  return (
    <div className="space-y-5">

      {/* Beta warning banner */}
      <div className="flex items-start gap-3 rounded-xl border px-4 py-3 bg-amber-50 border-amber-300 text-amber-800">
        <Icon icon="mdi:flask-outline" className="mt-0.5 text-sm flex-shrink-0" />
        <div className="text-xs leading-relaxed">
          <span className="font-bold uppercase tracking-wide mr-1.5">Beta</span>
          Observability is currently in beta. Features and data formats may change without notice. Use with caution in production environments.
        </div>
      </div>

      {/* Setup type selector */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-3">
        {setups.map(s => (
          <div key={s.id} className="relative">
            <button
              onClick={() => !s.comingSoon && setActiveSetup(s.id)}
              disabled={s.comingSoon}
              className={`w-full rounded-2xl border p-4 text-left transition-all ${
                s.comingSoon
                  ? 'cursor-not-allowed opacity-60 border-[rgba(0,0,0,0.12)] bg-white'
                  : activeSetup === s.id
                    ? 'border-violet-500 bg-violet-50 shadow-md'
                    : 'border-[rgba(0,0,0,0.12)] bg-white hover:border-[rgba(0,0,0,0.2)]'
              }`}>
              <div className="flex items-center gap-2 mb-1.5">
                <div className={`w-7 h-7 rounded-lg flex items-center justify-center text-xs flex-shrink-0 ${
                  s.comingSoon
                    ? 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.4)]'
                    : activeSetup === s.id ? 'bg-violet-600 text-white' : 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.5)]'
                }`}>
                  <Icon icon={s.icon} />
                </div>
                <span className={`font-semibold text-sm ${s.comingSoon ? 'text-[rgba(0,0,0,0.4)]' : 'text-[rgba(0,0,0,0.88)]'}`}>
                  {s.label}
                </span>
                {s.comingSoon && (
                  <span className="ml-auto text-[10px] font-bold px-1.5 py-0.5 rounded-full bg-amber-100 text-amber-700 whitespace-nowrap">
                    Coming Soon
                  </span>
                )}
                {s.badge && !s.comingSoon && (
                  <span className="ml-auto text-[10px] font-bold px-1.5 py-0.5 rounded-full bg-emerald-100 text-emerald-700">
                    {s.badge}
                  </span>
                )}
              </div>
              <p className={`text-xs leading-snug ${s.comingSoon ? 'text-[rgba(0,0,0,0.3)]' : 'text-[rgba(0,0,0,0.5)]'}`}>
                {s.description}
              </p>
            </button>
          </div>
        ))}
      </div>

      {/* ── Coming Soon overlay for EKS setups ───────────────────────────────── */}
      {(activeSetup === 'eks-helm' || activeSetup === 'eks-manual') && (
        <div className="rounded-2xl border border-[rgba(0,0,0,0.12)] bg-white p-10 flex flex-col items-center justify-center text-center gap-4">
          <div className="w-14 h-14 rounded-2xl flex items-center justify-center text-2xl bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.4)]">
            <Icon icon={activeSetup === 'eks-helm' ? 'mdi:kubernetes' : 'mdi:file-code-outline'} />
          </div>
          <div>
            <div className="flex items-center justify-center gap-2 mb-2">
              <span className="font-bold text-base text-[rgba(0,0,0,0.88)]">
                {activeSetup === 'eks-helm' ? 'EKS — Helm Setup' : 'EKS — Manual Setup'}
              </span>
              <span className="text-[11px] font-bold px-2 py-0.5 rounded-full bg-amber-100 text-amber-700">
                Coming Soon
              </span>
            </div>
            <p className="text-xs max-w-sm leading-relaxed text-[rgba(0,0,0,0.5)]">
              EKS observability setup is currently under development and has not been tested yet.
              In the meantime, use the <strong>EC2 / Linux VM</strong> setup for node-level observability.
            </p>
          </div>
          <button
            onClick={() => setActiveSetup('ec2')}
            className="mt-2 px-4 py-2 rounded-xl text-xs font-semibold bg-violet-600 hover:bg-violet-700 text-white transition-colors">
            Switch to EC2 Setup
          </button>
        </div>
      )}

      {/* ── EKS Helm (hidden until ready) ─────────────────────────────────────── */}
      {false && activeSetup === 'eks-helm' && (
        <div className={cardCls}>
          <div className="flex items-center gap-2">
            <Icon icon="mdi:kubernetes" className="text-violet-500" />
            <span className="font-semibold text-sm text-[rgba(0,0,0,0.88)]">
              Deploy via Helm — single command installs everything
            </span>
          </div>
          <p className="text-xs text-[rgba(0,0,0,0.5)]">
            The official <strong>openobserve-collector</strong> Helm chart deploys a DaemonSet (pod logs + node metrics)
            and a Deployment (cluster metrics + K8s events) with RBAC preconfigured.
            Your <strong>org ID and credentials are pre-filled</strong>.
          </p>

          {divider}

          <div className="space-y-6">
            <DeployStep num={1} title="Connect kubectl to your EKS cluster">
              {clusters.length > 1 && (
                <div className="flex items-center gap-2">
                  <span className="text-xs text-[rgba(0,0,0,0.5)]">Cluster:</span>
                  <select
                    value={selectedCluster?.clusterName ?? ''}
                    onChange={e => setSelectedCluster(clusters.find(c => c.clusterName === e.target.value) ?? null)}
                    className="text-xs rounded-lg border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.04)] px-2 py-1 text-[rgba(0,0,0,0.88)]">
                    {clusters.map(c => (
                      <option key={c.clusterName} value={c.clusterName}>{c.clusterName} ({c.region})</option>
                    ))}
                  </select>
                </div>
              )}
              <CodeBlock label="terminal" code={kubeconfigCmd} />
            </DeployStep>

            {divider}

            <DeployStep num={2} title="Run the Helm install (org ID + credentials auto-filled)">
              <p className="text-xs text-[rgba(0,0,0,0.5)]">
                This single command deploys the full observability stack. No manual YAML needed.
              </p>
              {helmLoading ? <LoadingSpinner /> : helmCmd
                ? <CodeBlock label="terminal" code={helmCmd} />
                : <p className="text-xs text-amber-600">Could not load command — try restarting the backend.</p>
              }
            </DeployStep>

            {divider}

            <DeployStep num={3} title="Verify pods are running">
              <CodeBlock label="terminal" code={`kubectl get pods -n xamops-system\n\n# Expected:\n# openobserve-collector-xxxxx  Running  (one per node — DaemonSet)\n# openobserve-cluster-xxxxx    Running  (one total — Deployment)`} />
            </DeployStep>
          </div>

          <div className="rounded-xl p-4 mt-2 bg-violet-50">
            <p className="text-xs font-semibold mb-2 text-violet-700">
              What flows into XamOps after deploy:
            </p>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-1.5 text-xs">
              {[
                ['Logs → kubernetes', 'Pod logs with namespace/pod/container metadata'],
                ['Logs → k8s-events', 'Cluster events (crashes, scheduling, scaling)'],
                ['Metrics → system_cpu_utilization', 'Node CPU usage per state'],
                ['Metrics → system_memory_utilization', 'Node memory usage'],
                ['Metrics → k8s_pod_cpu_utilization', 'Per-pod CPU utilization'],
                ['Metrics → k8s_deployment_*', 'Desired vs available replicas'],
              ].map(([label, desc]) => (
                <div key={label} className="flex gap-2">
                  <span className="font-mono font-medium flex-shrink-0 text-[11px] text-violet-700">{label}</span>
                  <span className="text-[11px] text-[rgba(0,0,0,0.5)]">— {desc}</span>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}

      {/* ── EC2 / Linux VM ────────────────────────────────────────────────────── */}
      {activeSetup === 'ec2' && (
        <div className={cardCls}>
          <div className="flex items-center gap-2">
            <Icon icon="mdi:server-outline" className="text-violet-500" />
            <span className="font-semibold text-sm text-[rgba(0,0,0,0.88)]">
              Install on EC2 / Linux VM
            </span>
          </div>
          <p className="text-xs text-[rgba(0,0,0,0.5)]">
            Installs <strong>OpenTelemetry Collector</strong> as a <code className="px-1 rounded bg-[rgba(0,0,0,0.04)]">systemd</code> service.
            Collects system logs (<code>/var/log/syslog</code>, auth logs, app logs) and host metrics (CPU, memory, disk, network).
            Your <strong>org ID and credentials are pre-filled</strong> in the config.
          </p>

          {divider}

          {ec2Loading ? (
            <LoadingSpinner />
          ) : ec2Config ? (
            <div className="space-y-4">
              <p className="text-xs font-medium text-[rgba(0,0,0,0.7)]">
                Copy the script below and run it on your EC2 instance as root / sudo:
              </p>
              <CodeBlock label="bash — run on EC2" code={ec2Config} />
              <div className="rounded-xl p-4 bg-sky-50">
                <p className="text-xs font-semibold mb-2 text-sky-700">
                  After install, data flows into:
                </p>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-1.5 text-xs">
                  {[
                    ['Logs → ec2-logs', '/var/log/syslog, auth.log, messages, your app logs'],
                    ['Metrics → system_cpu_utilization', 'EC2 CPU usage per state'],
                    ['Metrics → system_memory_utilization', 'EC2 memory usage'],
                    ['Metrics → system_disk_io', 'Disk read/write bytes'],
                    ['Metrics → system_network_io', 'Network receive/transmit bytes'],
                    ['Metrics → system_load_average', 'System load (1m, 5m, 15m)'],
                  ].map(([label, desc]) => (
                    <div key={label} className="flex gap-2">
                      <span className="font-mono font-medium flex-shrink-0 text-[11px] text-sky-700">{label}</span>
                      <span className="text-[11px] text-[rgba(0,0,0,0.5)]">— {desc}</span>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          ) : (
            <p className="text-xs text-amber-600">
              Could not load config — try restarting the backend.
            </p>
          )}
        </div>
      )}

      {/* ── GCE / Linux VM ───────────────────────────────────────────────────── */}
      {activeSetup === 'gce' && (
        <div className={cardCls}>
          <div className="flex items-center gap-2">
            <Icon icon="mdi:google" className="text-violet-500" />
            <span className="font-semibold text-sm text-[rgba(0,0,0,0.88)]">
              Install on GCE / Linux VM
            </span>
          </div>
          <p className="text-xs text-[rgba(0,0,0,0.5)]">
            Installs <strong>OpenTelemetry Collector</strong> as a <code className="px-1 rounded bg-[rgba(0,0,0,0.04)]">systemd</code> service.
            Collects system logs (<code>/var/log/syslog</code>, auth logs, app logs) and host metrics (CPU, memory, disk, network).
            GCE instance metadata (zone, project, machine-type) is auto-detected. Your <strong>org ID and credentials are pre-filled</strong> in the config.
          </p>

          {divider}

          {gceLoading ? (
            <LoadingSpinner />
          ) : gceConfig ? (
            <div className="space-y-4">
              <p className="text-xs font-medium text-[rgba(0,0,0,0.7)]">
                Copy the script below and run it on your GCE instance as root / sudo:
              </p>
              <CodeBlock label="bash — run on GCE" code={gceConfig} />
              <div className="rounded-xl p-4 bg-sky-50">
                <p className="text-xs font-semibold mb-2 text-sky-700">
                  After install, data flows into:
                </p>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-1.5 text-xs">
                  {[
                    ['Logs → gce-logs', '/var/log/syslog, auth.log, messages, your app logs'],
                    ['Metrics → system_cpu_utilization', 'GCE CPU usage per state'],
                    ['Metrics → system_memory_utilization', 'GCE memory usage'],
                    ['Metrics → system_disk_io', 'Disk read/write bytes'],
                    ['Metrics → system_network_io', 'Network receive/transmit bytes'],
                    ['Metrics → system_load_average', 'System load (1m, 5m, 15m)'],
                  ].map(([label, desc]) => (
                    <div key={label} className="flex gap-2">
                      <span className="font-mono font-medium flex-shrink-0 text-[11px] text-sky-700">{label}</span>
                      <span className="text-[11px] text-[rgba(0,0,0,0.5)]">— {desc}</span>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          ) : (
            <p className="text-xs text-amber-600">
              Could not load config — try restarting the backend.
            </p>
          )}
        </div>
      )}

      {/* ── EKS Manual (hidden until ready) ───────────────────────────────────── */}
      {false && activeSetup === 'eks-manual' && (
        <div className={cardCls}>
          <div className="flex items-center gap-2">
            <Icon icon="mdi:file-code-outline" className="text-violet-500" />
            <span className="font-semibold text-sm text-[rgba(0,0,0,0.88)]">
              Manual kubectl Deploy
            </span>
          </div>
          <p className="text-xs text-[rgba(0,0,0,0.5)]">
            For air-gapped clusters or when Helm is not available. Apply each manifest individually.
          </p>

          {divider}

          <div className="space-y-6">
            <DeployStep num={1} title="Connect kubectl to your EKS cluster">
              {clusters.length > 1 && (
                <select
                  value={selectedCluster?.clusterName ?? ''}
                  onChange={e => setSelectedCluster(clusters.find(c => c.clusterName === e.target.value) ?? null)}
                  className="text-xs rounded-lg border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.04)] px-2 py-1 text-[rgba(0,0,0,0.88)]">
                  {clusters.map(c => (
                    <option key={c.clusterName} value={c.clusterName}>{c.clusterName} ({c.region})</option>
                  ))}
                </select>
              )}
              <CodeBlock label="terminal" code={kubeconfigCmd} />
            </DeployStep>

            {divider}

            <DeployStep num={2} title="Apply credentials secret (org ID auto-filled)">
              {secretLoading ? <LoadingSpinner /> : secretError ? (
                <div className="rounded-xl border border-amber-200 bg-amber-50 p-4 flex items-center justify-between gap-3">
                  <p className="text-xs font-medium text-amber-700">
                    Could not load secret — restart the backend then retry.
                  </p>
                  <button
                    onClick={() => { setSecretLoading(true); setSecretError(false); fetchSetupConfig('agent-secret').then(v => { setAgentSecret(v || ''); setSecretError(!v); setSecretLoading(false); }); }}
                    className="flex-shrink-0 px-3 py-1.5 rounded-lg text-xs font-medium bg-amber-600 hover:bg-amber-700 text-white">
                    Retry
                  </button>
                </div>
              ) : agentSecret ? <CodeBlock label="agent-secret.yaml" code={agentSecret} /> : null}
              <CodeBlock label="terminal" code="kubectl apply -f agent-secret.yaml" />
            </DeployStep>

            {divider}

            <DeployStep num={3} title="Apply RBAC">
              {rbacYaml && <CodeBlock label="rbac.yaml" code={rbacYaml} />}
              <CodeBlock label="terminal" code="kubectl apply -f rbac.yaml" />
            </DeployStep>

            {divider}

            <DeployStep num={4} title="Deploy XamOps Agent (logs + metrics in one)">
              <p className="text-xs text-[rgba(0,0,0,0.5)]">
                Single OTel-based agent — DaemonSet (pod logs + host metrics) + Deployment (cluster metrics + events).
              </p>
              {agentYaml && <CodeBlock label="xamops-agent.yaml" code={agentYaml} />}
              <CodeBlock label="terminal" code="kubectl apply -f xamops-agent.yaml" />
            </DeployStep>

            {divider}

            <DeployStep num={5} title="Verify">
              <CodeBlock label="terminal" code={`kubectl get pods -n xamops-system\nkubectl logs -n xamops-system -l app=xamops-agent --tail=20`} />
            </DeployStep>
          </div>
        </div>
      )}

    </div>
  );
}

// ── Settings section inside Setup ─────────────────────────────────────────────

function SettingsPanel({ config }: { config: ObservabilityConfig | null }) {
  const { saveConfig } = useObservabilityData();
  const [retentionDays, setRetentionDays] = useState(config?.retentionDays ?? 30);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved]   = useState(false);

  useEffect(() => {
    if (config) setRetentionDays(config.retentionDays);
  }, [config]);

  const handleSave = async () => {
    setSaving(true);
    await saveConfig({ retentionDays });
    setSaving(false);
    setSaved(true);
    setTimeout(() => setSaved(false), 2000);
  };

  return (
    <div className="rounded-2xl border border-[rgba(0,0,0,0.12)] bg-white shadow-sm p-5 space-y-4">
      <p className="text-sm font-semibold text-[rgba(0,0,0,0.88)]">
        Settings
      </p>
      <div className="flex items-center justify-between">
        <div>
          <p className="text-sm text-[rgba(0,0,0,0.7)]">
            Data Retention
          </p>
          <p className="text-xs mt-0.5 text-[rgba(0,0,0,0.4)]">
            Applied to all streams in OpenObserve
          </p>
        </div>
        <select
          value={retentionDays}
          onChange={e => setRetentionDays(Number(e.target.value))}
          className="text-sm rounded-lg border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.04)] px-3 py-1.5 text-[rgba(0,0,0,0.88)] focus:outline-none focus:border-[rgba(0,0,0,0.2)]"
        >
          {[7, 15, 30, 60, 90].map(d => (
            <option key={d} value={d}>{d} days</option>
          ))}
        </select>
      </div>
      <button
        onClick={handleSave}
        disabled={saving}
        className={`px-4 py-2 rounded-lg text-sm font-medium transition-colors ${
          saved
            ? 'bg-emerald-600 text-white'
            : 'bg-violet-600 hover:bg-violet-700 text-white disabled:opacity-60'
        }`}
      >
        {saving ? 'Saving...' : saved ? '✓ Saved' : 'Save Settings'}
      </button>
    </div>
  );
}

// ── Main Page ─────────────────────────────────────────────────────────────────

const ObservabilityPage: React.FC = () => {
  const { setTitle } = useTopbar();
  const {
    config, overview, streams,
    configLoading,
    fetchConfig, fetchOverview, fetchStreams, fetchConnectedInstances,
  } = useObservabilityData();

  const [instances, setInstances] = useState<ConnectedInstance[]>([]);
  const activeTab = useActiveTab();

  useEffect(() => {
    setTitle('Observability');
    setInstances([]);
    fetchConfig().then(() => {
      fetchOverview();
      fetchStreams();
      fetchConnectedInstances().then(setInstances);
    });
  }, [setTitle, fetchConfig, fetchOverview, fetchStreams, fetchConnectedInstances]);

  return (
    <div className="px-6 pb-12 pt-6 space-y-6">

      {/* Loading state */}
      {configLoading && (
        <div className="flex items-center justify-center py-20">
          <div className="w-8 h-8 border-2 border-violet-600 border-t-transparent rounded-full animate-spin" />
        </div>
      )}

      {!configLoading && (
        <>
          {activeTab === 'overview' && (
            <OverviewTab config={config} overview={overview} instances={instances} />
          )}
          {activeTab === 'logs' && (
            <LogsExplorer streams={streams} />
          )}
          {activeTab === 'metrics' && (
            <MetricsDashboard />
          )}
          {activeTab === 'traces' && (
            <TracesTab />
          )}
          {activeTab === 'alerts' && (
            <AlertsTab />
          )}
          {activeTab === 'setup' && (
            <div className="space-y-6">
              <SetupTab />
              <SettingsPanel config={config} />
            </div>
          )}
        </>
      )}
    </div>
  );
};

export default ObservabilityPage;
