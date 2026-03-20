import { useState, useEffect, useRef, ReactElement } from 'react';
import ReactDOM from 'react-dom';
import apiClient from '../../utils/apiClient';
import { useAccounts } from '../../contexts/AccountContext';
import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip,
  ResponsiveContainer, ReferenceDot,
} from 'recharts';
import {
  DbInstance, DbMetrics, DbInstanceTimeseries, TimeseriesPoint, DbCost,
  AgentQueryStat, SlowQuery, AgentInstanceMetrics,
  DbIdleInstance, DbRightsizing, DbSecurityCheck, DbSnapshot,
  AgentTimeseries, AgentTableSize, AgentVacuumStat, AgentUserActivity,
  AgentIndexUsage, AgentTableActivity,
} from '../../hooks/useDbOpsData';

// ── Helpers ───────────────────────────────────────────────────────────────────

const fmt$ = (n: number) =>
  `$${n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

function fmtTime(ts: number) {
  return new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}
function fmtDate(ts: number) {
  return new Date(ts).toLocaleDateString([], { month: 'short', day: 'numeric' });
}
function fmtLabel(ts: number) {
  return `${fmtDate(ts)} ${fmtTime(ts)}`;
}
function round2(v: number) { return Math.round(v * 100) / 100; }

function toChartData(points: TimeseriesPoint[] | undefined) {
  if (!points?.length) return [];
  return points.map(p => ({ ts: p.ts, v: round2(p.v), label: fmtLabel(p.ts) }));
}

function fmtBytes(b: number) {
  if (b >= 1024 * 1024 * 1024) return `${round2(b / (1024 * 1024 * 1024))} GB`;
  if (b >= 1024 * 1024) return `${round2(b / (1024 * 1024))} MB`;
  if (b >= 1024) return `${round2(b / 1024)} KB`;
  return `${b} B`;
}

// ── Instant tooltip — portal-based so it escapes overflow:hidden/auto parents ──
function Tip({ text, children, wide }: { text: string; children: React.ReactNode; wide?: boolean }) {
  const ref    = useRef<HTMLSpanElement>(null);
  const [rect, setRect] = useState<DOMRect | null>(null);

  const show = () => {
    if (ref.current) setRect(ref.current.getBoundingClientRect());
  };
  const hide = () => setRect(null);

  // Tooltip node rendered into document.body via portal
  const tooltip = rect ? ReactDOM.createPortal(
    <div
      style={{
        position: 'fixed',
        // Centre horizontally over the trigger, clamp so it doesn't overflow viewport
        left: Math.min(
          Math.max(8, rect.left + rect.width / 2 - (wide ? 144 : 112)),
          window.innerWidth - (wide ? 296 : 232)
        ),
        top: rect.top - 8,
        transform: 'translateY(-100%)',
        zIndex: 99999,
        width: wide ? 288 : 224,
      }}
      className="pointer-events-none rounded-lg bg-gray-900 text-white text-[10px] leading-snug px-2.5 py-2 shadow-xl whitespace-normal"
    >
      {text}
      {/* Caret */}
      <span
        style={{
          position: 'absolute',
          top: '100%',
          left: Math.min(
            Math.max(12, rect.left + rect.width / 2 - Math.min(
              Math.max(8, rect.left + rect.width / 2 - (wide ? 144 : 112)),
              window.innerWidth - (wide ? 296 : 232)
            )),
            (wide ? 288 : 224) - 12
          ),
          transform: 'translateX(-50%)',
          borderWidth: 5,
          borderStyle: 'solid',
          borderColor: 'transparent',
          borderTopColor: '#111827',
        }}
      />
    </div>,
    document.body
  ) : null;

  return (
    <span
      ref={ref}
      className="inline-flex items-center cursor-help"
      onMouseEnter={show}
      onMouseLeave={hide}
    >
      {children}
      {tooltip}
    </span>
  );
}

// Agent snapshot → chart data for a scalar field (connections, bufferPoolHitPct)
function agentChartDirect(snapshots: AgentTimeseries['snapshots'], field: 'connections' | 'bufferPoolHitPct' | 'threadsRunning' | 'totalSlowQueries') {
  return snapshots.map(s => {
    const ms = new Date(s.ts).getTime();
    return { ts: ms, v: round2(Number(s[field])), label: fmtLabel(ms) };
  });
}

// Agent snapshot → per-second rate from cumulative counter (QPS)
function agentChartRate(snapshots: AgentTimeseries['snapshots'], field: 'totalQueries' | 'totalSlowQueries' | 'comSelect' | 'comInsert' | 'comUpdate' | 'comDelete' | 'innodbRowLockWaits' | 'innodbDeadlocks') {
  if (snapshots.length < 2) return [];
  const out: { ts: number; v: number; label: string }[] = [];
  for (let i = 1; i < snapshots.length; i++) {
    const prevMs = new Date(snapshots[i - 1].ts).getTime();
    const currMs = new Date(snapshots[i].ts).getTime();
    const elapsedSec = (currMs - prevMs) / 1000;
    if (elapsedSec <= 0) continue;
    const delta = Number(snapshots[i][field]) - Number(snapshots[i - 1][field]);
    out.push({ ts: currMs, v: round2(Math.max(0, delta) / elapsedSec), label: fmtLabel(currMs) });
  }
  return out;
}

// Agent snapshot → connection utilization % (currentConnections / maxConnections * 100)
function agentChartConnUtilPct(snapshots: AgentTimeseries['snapshots']) {
  return snapshots.map(s => {
    const ms = new Date(s.ts).getTime();
    const pct = s.maxConnections > 0 ? round2((s.connections / s.maxConnections) * 100) : 0;
    return { ts: ms, v: pct, label: fmtLabel(ms) };
  });
}

// ── Mini stat card ─────────────────────────────────────────────────────────────
function StatCard({ label, value, unit, color, tooltip }: { label: string; value: string | number; unit?: string; color?: string; tooltip?: string }) {
  return (
    <div className="bg-gray-50 dark:bg-gray-700/40 rounded-lg p-3 flex flex-col gap-0.5">
      <span className="text-xs text-gray-500 dark:text-gray-400 flex items-center gap-1">
        {label}
        {tooltip && (
          <span className="relative group inline-flex items-center">
            <i className="fas fa-circle-info text-[9px] text-gray-400 dark:text-gray-500 cursor-help" />
            <span className="pointer-events-none absolute top-full left-0 mt-1.5 w-56 rounded-lg bg-gray-900 dark:bg-gray-700 px-2.5 py-2 text-[11px] text-gray-100 leading-relaxed shadow-lg opacity-0 group-hover:opacity-100 transition-opacity duration-150 z-50 whitespace-normal">
              {tooltip}
              <span className="absolute bottom-full left-3 border-4 border-transparent border-b-gray-900 dark:border-b-gray-700" />
            </span>
          </span>
        )}
      </span>
      <span className={`text-lg font-semibold ${color ?? 'text-gray-900 dark:text-gray-100'}`}>
        {value}<span className="text-xs font-normal ml-1 text-gray-500">{unit}</span>
      </span>
    </div>
  );
}

// ── Perf Chart (futuristic, synced, anomaly-aware) ────────────────────────────
function PerfChart({
  title, data, color, unit, dataKey = 'v', domain, syncId,
}: {
  title: string;
  data: { ts: number; v: number; label: string }[];
  color: string;
  unit?: string;
  dataKey?: string;
  domain?: [number, number];
  syncId?: string;
}) {
  const [expanded, setExpanded] = useState(false);
  const chartHeight = expanded ? 200 : 110;
  const gradId = `pc_${title.replace(/\W+/g, '_')}`;

  // Compute current value & peak
  const peak = data.length ? Math.max(...data.map(d => d.v)) : 0;
  const peakPoint = data.find(d => d.v === peak);
  const current = data.length ? data[data.length - 1].v : 0;

  // Anomaly detection: flag points that are > mean × 2.5 (and non-zero)
  const mean = data.length ? data.reduce((s, d) => s + d.v, 0) / data.length : 0;
  const anomalies = mean > 0 ? data.filter(d => d.v > mean * 2.5) : [];

  // Generate evenly-spaced ticks from the domain so the X-axis labels are
  // always uniformly distributed regardless of snapshot collection irregularity
  const domainStart = domain ? domain[0] : (data[0]?.ts ?? 0);
  const domainEnd   = domain ? domain[1] : (data[data.length - 1]?.ts ?? 0);
  const TICK_COUNT  = 7;
  const tickStep    = (domainEnd - domainStart) / (TICK_COUNT - 1);
  const axisTicks   = Array.from({ length: TICK_COUNT }, (_, i) =>
    Math.round(domainStart + i * tickStep)
  );

  // Empty state with futuristic illustration
  if (!data.length) {
    return (
      <div
        className="relative rounded-xl overflow-hidden bg-white/60 dark:bg-gray-800/50 backdrop-blur-sm border border-gray-200/60 dark:border-gray-700/40 shadow-sm p-4"
        style={{ borderTop: `2px solid ${color}` }}
      >
        <p className="text-xs font-semibold text-gray-700 dark:text-gray-300 mb-3">{title}</p>
        <div className="h-28 flex flex-col items-center justify-center gap-2 text-gray-300 dark:text-gray-600">
          <svg width="72" height="44" viewBox="0 0 72 44" fill="none">
            <rect x="0" y="42" width="72" height="1" stroke="currentColor" strokeDasharray="4 3" strokeOpacity="0.5" />
            <path d="M0 34 Q18 34 36 34 Q54 34 72 34" stroke="currentColor" strokeWidth="1.5" strokeDasharray="4 3" strokeOpacity="0.5" fill="none" />
            <circle cx="36" cy="20" r="10" stroke="currentColor" strokeWidth="1.2" strokeOpacity="0.4" fill="none" strokeDasharray="3 2" />
            <line x1="31" y1="15" x2="41" y2="25" stroke="currentColor" strokeWidth="1.2" strokeOpacity="0.5" />
            <line x1="41" y1="15" x2="31" y2="25" stroke="currentColor" strokeWidth="1.2" strokeOpacity="0.5" />
          </svg>
          <span className="text-[11px] text-gray-400 dark:text-gray-500">No data available</span>
        </div>
      </div>
    );
  }

  return (
    <div
      className="relative rounded-xl overflow-hidden bg-white/60 dark:bg-gray-800/50 backdrop-blur-sm border border-gray-200/60 dark:border-gray-700/40 shadow-sm p-4 transition-all duration-300"
      style={{ borderTop: `2px solid ${color}` }}
    >
      {/* Header row: title | current value + peak + expand */}
      <div className="flex items-start justify-between mb-2">
        <p className="text-xs font-semibold text-gray-700 dark:text-gray-300">{title}</p>
        <div className="flex items-center gap-2 shrink-0 ml-2">
          <div className="text-right">
            <div className="text-sm font-bold leading-none" style={{ color }}>
              {round2(current)}<span className="text-[10px] font-normal text-gray-400 ml-0.5">{unit}</span>
            </div>
            <div className="text-[9px] text-gray-400 mt-0.5">
              peak: {round2(peak)}{unit}{peakPoint ? ` @ ${fmtTime(peakPoint.ts)}` : ''}
            </div>
          </div>
          <button
            onClick={() => setExpanded(e => !e)}
            className="text-gray-400 hover:text-gray-600 dark:hover:text-gray-200 transition-colors p-0.5 rounded"
            title={expanded ? 'Collapse' : 'Expand'}
          >
            <i className={`fas fa-${expanded ? 'compress-alt' : 'expand-alt'} text-[10px]`} />
          </button>
        </div>
      </div>

      {/* Chart */}
      <ResponsiveContainer width="100%" height={chartHeight}>
        <AreaChart data={data} syncId={syncId} margin={{ top: 4, right: 4, left: -28, bottom: 0 }}>
          <defs>
            <linearGradient id={gradId} x1="0" y1="0" x2="0" y2="1">
              <stop offset="5%" stopColor={color} stopOpacity={0.35} />
              <stop offset="95%" stopColor={color} stopOpacity={0} />
            </linearGradient>
          </defs>
          <CartesianGrid strokeDasharray="3 3" stroke="#6b7280" strokeOpacity={0.12} />
          <XAxis
            dataKey="ts" scale="time" type="number"
            domain={domain ?? ['dataMin', 'dataMax']}
            ticks={axisTicks}
            tick={{ fontSize: 8, fill: '#9ca3af' }}
            tickFormatter={(ts: number) => fmtTime(ts)}
            axisLine={false} tickLine={false}
          />
          <YAxis tick={{ fontSize: 8, fill: '#9ca3af' }} axisLine={false} tickLine={false} />
          <Tooltip
            labelFormatter={(ts) => fmtLabel(Number(ts))}
            formatter={(v) => [`${round2(Number(v))}${unit ? ' ' + unit : ''}`, title] as [string, string]}
            contentStyle={{
              background: 'rgba(15,23,42,0.92)',
              border: `1px solid ${color}55`,
              borderRadius: '8px',
              fontSize: 11,
              color: '#e2e8f0',
              boxShadow: `0 0 16px ${color}33`,
            }}
            labelStyle={{ color: '#94a3b8', marginBottom: 2 }}
            cursor={{ stroke: color, strokeWidth: 1, strokeDasharray: '4 3', strokeOpacity: 0.6 }}
          />
          {/* Anomaly spike markers */}
          {anomalies.map((pt, i) => (
            <ReferenceDot
              key={i} x={pt.ts} y={pt.v} r={4}
              fill="#ef4444" stroke="#fca5a5" strokeWidth={1.5}
              label={{ value: '↑', position: 'top', fontSize: 9, fill: '#ef4444' }}
            />
          ))}
          <Area
            type="monotone" dataKey={dataKey}
            stroke={color} fill={`url(#${gradId})`}
            strokeWidth={2} dot={false}
            activeDot={{ r: 4, strokeWidth: 2, stroke: color, fill: '#0f172a' }}
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}


// ── Agent Setup Wizard ────────────────────────────────────────────────────────

interface DsnEntry {
  engine: 'mysql' | 'postgres';
  host: string;
  port: string;
  dbName: string;
  user: string;
  pass: string;
  sslMode: 'disable' | 'require' | 'prefer';
}

function buildDsn(d: DsnEntry) {
  if (d.engine === 'postgres') {
    const ssl = d.sslMode !== 'prefer' ? `?sslmode=${d.sslMode}` : '';
    return `postgres://${d.user}:${d.pass || '<password>'}@${d.host || '<host>'}:${d.port}/${d.dbName || '<db>'}${ssl}`;
  }
  return `${d.user}:${d.pass || '<password>'}@tcp(${d.host || '<host>'}:${d.port})/${d.dbName || '<db>'}`;
}

function AgentSetupWizard({ engine, instanceId, endpoint, onConnected }: { engine: string; instanceId: string; endpoint?: string; onConnected?: (inst: DrawerInstance) => void }) {
  const { selectedAccountIds } = useAccounts();
  const [step, setStep] = useState(1);
  const [copied, setCopied] = useState('');

  const detectedPostgres = /postgres|aurora-postgresql/i.test(engine);
  const [sqlEngine, setSqlEngine] = useState<'mysql' | 'postgres'>(detectedPostgres ? 'postgres' : 'mysql');
  const isPostgres = sqlEngine === 'postgres';

  const isRds = !!endpoint;
  const [dsns, setDsns] = useState<DsnEntry[]>([{
    engine: detectedPostgres ? 'postgres' : 'mysql',
    host: endpoint?.split(':')[0] ?? '',
    port: detectedPostgres ? '5432' : '3306',
    dbName: '',
    user: 'xamops_reader',
    pass: '',
    sslMode: isRds ? 'prefer' : 'disable',
  }]);
  const [showPass, setShowPass] = useState<Record<number, boolean>>({});

  const updateDsn = (idx: number, field: keyof DsnEntry, value: string) =>
    setDsns(prev => prev.map((d, i) => i === idx ? { ...d, [field]: value } : d));

  const addDsn = () => setDsns(prev => [...prev, { engine: 'mysql', host: '', port: '3306', dbName: '', user: 'xamops_reader', pass: '', sslMode: 'disable' }]);
  const removeDsn = (idx: number) => setDsns(prev => prev.filter((_, i) => i !== idx));

  // XamOps server config (auto-populated)
  // Use window.location.origin in production (same domain); in dev the user must set the tunnel URL
  const [serverUrl, setServerUrl] = useState(() => {
    const origin = window.location.origin;
    // In dev (port 5173), the origin is the Vite dev server — not the backend.
    // Default to empty so the user fills in the tunnel/public URL.
    return origin.includes('localhost') ? '' : origin;
  });
  const [apiKey, setApiKey] = useState('xamops_SecureAgentKey2024XamOps');
  const [cloudAccountId, setCloudAccountId] = useState(selectedAccountIds[0] ?? '');
  const [clusterName, setClusterName] = useState(instanceId);
  const [queryLimit, setQueryLimit] = useState(200);

  const FALLBACK_KEY = 'xamops_SecureAgentKey2024XamOps';

  // View mode: loading → wizard | connected | disconnected
  const [viewMode, setViewMode] = useState<'loading' | 'wizard' | 'connected' | 'disconnected'>('loading');
  const [lastCheckin, setLastCheckin] = useState<string | null>(null);
  const [agentIp, setAgentIp] = useState<string>('');
  const [dataCounts, setDataCounts] = useState<Record<string, { itemCount: number; collectedAt: string }>>({});
  const [saveStatus, setSaveStatus] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle');
  const [disconnecting, setDisconnecting] = useState(false);

  const handleDisconnect = () => {
    if (!clusterName || !cloudAccountId) return;
    if (!window.confirm(`Remove agent "${clusterName}" and all its cached data? The Docker container will keep running until you stop it manually.`)) return;
    setDisconnecting(true);
    apiClient.delete(`/api/v1/agent/db/register?agentName=${encodeURIComponent(clusterName)}&cloudAccountId=${encodeURIComponent(cloudAccountId)}`)
      .then(() => { setViewMode('wizard'); setStep(1); })
      .catch(() => setDisconnecting(false));
  };

  useEffect(() => {
    apiClient.get<{ apiKey: string }>('/api/v1/agent/install-info')
      .then(res => setApiKey(res.apiKey || FALLBACK_KEY))
      .catch(() => setApiKey(FALLBACK_KEY));
  }, []);

  // On mount: load config + check status to decide view mode
  useEffect(() => {
    if (!clusterName || !cloudAccountId) { setViewMode('wizard'); return; }

    const applyConfig = (res: { found: boolean; dbEngine?: string; dbHost?: string; dbPort?: string; dbName?: string; dbUser?: string; dbPassword?: string; serverUrl?: string }) => {
      if (!res.found) return false;
      if (res.dbEngine) setSqlEngine(res.dbEngine as 'mysql' | 'postgres');
      setDsns(prev => {
        const first: DsnEntry = { ...prev[0] };
        if (res.dbEngine) first.engine = res.dbEngine as 'mysql' | 'postgres';
        if (res.dbHost)   first.host = res.dbHost!;
        if (res.dbPort)   first.port = res.dbPort!;
        if (res.dbName)   first.dbName = res.dbName!;
        if (res.dbUser)   first.user = res.dbUser!;
        if (res.dbPassword) first.pass = res.dbPassword!;
        return [first, ...prev.slice(1)];
      });
      if (res.serverUrl) setServerUrl(res.serverUrl!);
      return true;
    };

    Promise.all([
      apiClient.get<{ found: boolean; dbEngine?: string; dbHost?: string; dbPort?: string; dbName?: string; dbUser?: string; dbPassword?: string; serverUrl?: string }>(
        `/api/v1/agent/db/config?agentName=${encodeURIComponent(clusterName)}&cloudAccountId=${encodeURIComponent(cloudAccountId)}`
      ).catch(() => ({ found: false })),
      apiClient.get<{ registered: boolean; isActive: boolean; lastCheckin: string | null; agentIp: string; dataCounts?: Record<string, { itemCount: number; collectedAt: string }> }>(
        `/api/v1/agent/db/status?agentName=${encodeURIComponent(clusterName)}&cloudAccountId=${encodeURIComponent(cloudAccountId)}`
      ).catch(() => ({ registered: false, isActive: false, lastCheckin: null, agentIp: '' })),
    ]).then(([cfg, status]) => {
      applyConfig(cfg);
      setLastCheckin(status.lastCheckin ?? null);
      setAgentIp(status.agentIp ?? '');
      setDataCounts((status as { dataCounts?: Record<string, { itemCount: number; collectedAt: string }> }).dataCounts ?? {});
      if (status.isActive) {
        setViewMode('connected');
      } else if (status.registered) {
        setViewMode('disconnected');
      } else {
        setViewMode('wizard');
        if (cfg.found) setStep(3); // config saved — skip to run step
      }
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Auto-register + poll when in wizard step 3 or disconnected
  useEffect(() => {
    if ((viewMode !== 'wizard' || step !== 3) && viewMode !== 'disconnected') return;
    if (!clusterName || !cloudAccountId) return;

    apiClient.post('/api/v1/agent/db/register', { agentName: clusterName, cloudAccountId })
      .catch(() => { });

    const poll = () => {
      apiClient.get<{ registered: boolean; isActive: boolean; lastCheckin: string | null; agentIp: string; dataCounts?: Record<string, { itemCount: number; collectedAt: string }> }>(
        `/api/v1/agent/db/status?agentName=${encodeURIComponent(clusterName)}&cloudAccountId=${encodeURIComponent(cloudAccountId)}`
      ).then(res => {
        setLastCheckin(res.lastCheckin ?? null);
        setAgentIp(res.agentIp ?? '');
        setDataCounts(res.dataCounts ?? {});
        if (res.isActive) setViewMode('connected');
      }).catch(() => { });
    };

    poll();
    const interval = setInterval(poll, 10000);
    return () => clearInterval(interval);
  }, [viewMode, step, clusterName, cloudAccountId]);

  // When connected: poll every 30s to detect disconnection
  useEffect(() => {
    if (viewMode !== 'connected' || !clusterName || !cloudAccountId) return;
    const poll = () => {
      apiClient.get<{ registered: boolean; isActive: boolean; lastCheckin: string | null; agentIp: string; dataCounts?: Record<string, { itemCount: number; collectedAt: string }> }>(
        `/api/v1/agent/db/status?agentName=${encodeURIComponent(clusterName)}&cloudAccountId=${encodeURIComponent(cloudAccountId)}`
      ).then(res => {
        setLastCheckin(res.lastCheckin ?? null);
        setAgentIp(res.agentIp ?? '');
        setDataCounts(res.dataCounts ?? {});
        if (!res.isActive) setViewMode('disconnected');
      }).catch(() => { });
    };
    const interval = setInterval(poll, 30000);
    return () => clearInterval(interval);
  }, [viewMode, clusterName, cloudAccountId]);

  const mysqlSql = `-- Create read-only monitoring user
CREATE USER 'xamops_reader'@'%' IDENTIFIED BY '<strong_password>';
GRANT PROCESS, REPLICATION CLIENT ON *.* TO 'xamops_reader'@'%';
GRANT SELECT ON performance_schema.* TO 'xamops_reader'@'%';
GRANT SELECT ON sys.* TO 'xamops_reader'@'%';
-- Grant SELECT on the databases you want to monitor:
GRANT SELECT ON <your_database>.* TO 'xamops_reader'@'%';
FLUSH PRIVILEGES;
-- Note: RDS requires performance_schema=1 in a custom parameter group (requires reboot)`;

  const postgresSql = `-- Create read-only monitoring user
CREATE USER xamops_reader WITH PASSWORD '<strong_password>';
GRANT CONNECT ON DATABASE <your_db> TO xamops_reader;
GRANT USAGE ON SCHEMA public TO xamops_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO xamops_reader;
-- Optional: enable slow query analysis
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;`;

  const stopCmd = 'docker stop xamops-agent 2>/dev/null; docker rm xamops-agent 2>/dev/null';

  const runCmd = [
    'docker run -d \\',
    '  --name xamops-agent \\',
    '  --restart unless-stopped \\',
    '  --network host \\',
    `  -e XAMOPS_SERVER_URL="${serverUrl || '<your-xamops-url>'}" \\`,
    `  -e XAMOPS_API_KEY="${apiKey || '<your-api-key>'}" \\`,
    `  -e CLUSTER_NAME="${clusterName || '<agent-name>'}" \\`,
    `  -e CLOUD_ACCOUNT_ID="${cloudAccountId || '<account-id>'}" \\`,
    '  -e COLLECTOR_DB=true \\',
    `  -e DB_QUERY_LIMIT=${queryLimit} \\`,
    ...dsns.map((d, i) => `  -e DB_DSN_${i}='${buildDsn(d)}' \\`),
    '  xamops/agent:latest',
  ].join('\n');

  const copy = (text: string, key: string) => {
    navigator.clipboard.writeText(text).then(() => {
      setCopied(key);
      setTimeout(() => setCopied(''), 2000);
    });
  };

  const stepLabels = ['Create DB User', 'Configure Connection', 'Run Agent'];

  // ── Connected view ──────────────────────────────────────────────────────────
  if (viewMode === 'connected') {
    const queryCount = dataCounts['db-query-stats']?.itemCount ?? 0;
    const metricCount = dataCounts['db-instance-metrics']?.itemCount ?? 0;
    const lastSeen = lastCheckin ? new Date(lastCheckin).toLocaleTimeString() : '—';
    return (
      <div className="space-y-4">
        <div className="flex items-center gap-3 bg-emerald-50 dark:bg-emerald-900/20 border border-emerald-200 dark:border-emerald-700 rounded-xl p-4">
          <span className="relative flex h-3 w-3 shrink-0">
            <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75" />
            <span className="relative inline-flex rounded-full h-3 w-3 bg-emerald-500" />
          </span>
          <div className="flex-1">
            <p className="text-sm font-semibold text-emerald-700 dark:text-emerald-300">Agent Connected</p>
            <p className="text-xs text-emerald-600 dark:text-emerald-400">
              {agentIp && <span className="mr-3">IP: {agentIp}</span>}
              Last seen: {lastSeen}
            </p>
          </div>
          <div className="flex items-center gap-1">
            <button
              onClick={() => { setViewMode('wizard'); setStep(3); }}
              className="text-xs text-gray-400 hover:text-gray-600 dark:hover:text-gray-300 px-2 py-1 rounded hover:bg-gray-100 dark:hover:bg-gray-700 transition-colors"
            >
              <i className="fas fa-cog mr-1" />Setup
            </button>
            <button
              onClick={handleDisconnect}
              disabled={disconnecting}
              className="text-xs text-red-400 hover:text-red-600 dark:hover:text-red-300 px-2 py-1 rounded hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors disabled:opacity-50"
            >
              {disconnecting ? <><i className="fas fa-spinner fa-spin mr-1" />Removing…</> : <><i className="fas fa-unlink mr-1" />Disconnect</>}
            </button>
          </div>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <div className="bg-gray-50 dark:bg-gray-700/40 rounded-lg p-3">
            <p className="text-xs text-gray-500 dark:text-gray-400">Queries tracked</p>
            <p className="text-2xl font-bold text-gray-900 dark:text-gray-100">{queryCount}</p>
            <p className="text-[11px] text-gray-400">from performance_schema</p>
          </div>
          <div className="bg-gray-50 dark:bg-gray-700/40 rounded-lg p-3">
            <p className="text-xs text-gray-500 dark:text-gray-400">Metric snapshots</p>
            <p className="text-2xl font-bold text-gray-900 dark:text-gray-100">{metricCount}</p>
            <p className="text-[11px] text-gray-400">connections, QPS, buffer pool</p>
          </div>
        </div>
      {onConnected && dsns.filter(d => d.host).length > 0 ? (
        <div className="space-y-2">
          <p className="text-xs font-semibold text-gray-500 dark:text-gray-400 uppercase tracking-wider">Connected Databases</p>
          {dsns.filter(d => d.host).map((d, i) => (
            <button
              key={i}
              onClick={() => onConnected({ instanceId: `${d.host}:${d.port}`, source: 'agent', agentHost: `${d.host}:${d.port}`, engine: d.engine })}
              className="w-full flex items-center gap-3 p-3 bg-white dark:bg-gray-800 border border-gray-200 dark:border-gray-700 rounded-lg hover:border-indigo-400 dark:hover:border-indigo-500 hover:bg-indigo-50 dark:hover:bg-indigo-900/20 transition-colors text-left"
            >
              <i className="fas fa-database text-indigo-500 text-sm" />
              <div className="flex-1 min-w-0">
                <p className="text-sm font-medium text-gray-800 dark:text-gray-200 truncate">
                  {d.host}:{d.port}{d.dbName ? `/${d.dbName}` : ''}
                </p>
                <p className="text-xs text-gray-500">{d.engine === 'postgres' ? 'PostgreSQL' : 'MySQL'} · {queryCount} queries tracked</p>
              </div>
              <i className="fas fa-arrow-right text-gray-400 text-xs" />
            </button>
          ))}
        </div>
      ) : (
        <p className="text-xs text-gray-500 dark:text-gray-400 text-center">
          Data is available in the <strong>Query Stats</strong> and <strong>Performance</strong> tabs above.
        </p>
      )}
      </div>
    );
  }

  // ── Disconnected view ───────────────────────────────────────────────────────
  if (viewMode === 'disconnected') {
    const lastSeen = lastCheckin
      ? `Last seen ${new Date(lastCheckin).toLocaleString()}`
      : 'Agent has not checked in recently';
    return (
      <div className="space-y-4">
        <div className="flex items-center gap-3 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-700 rounded-xl p-4">
          <span className="relative flex h-3 w-3 shrink-0">
            <span className="relative inline-flex rounded-full h-3 w-3 bg-red-500" />
          </span>
          <div className="flex-1">
            <p className="text-sm font-semibold text-red-700 dark:text-red-300">Connection Lost</p>
            <p className="text-xs text-red-500 dark:text-red-400">{lastSeen}</p>
          </div>
        </div>
        <p className="text-xs text-gray-500 dark:text-gray-400">
          The agent hasn't checked in. Make sure the Docker container is running on your EC2 instance.
        </p>
        <button
          onClick={() => { setViewMode('wizard'); setStep(3); }}
          className="w-full py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-lg text-xs font-medium transition-colors"
        >
          <i className="fas fa-redo mr-1" />Connect Again
        </button>
        <button
          onClick={() => { setViewMode('wizard'); setStep(1); }}
          className="w-full py-2 bg-gray-100 dark:bg-gray-700 text-gray-600 dark:text-gray-300 rounded-lg text-xs font-medium hover:bg-gray-200 dark:hover:bg-gray-600 transition-colors"
        >
          View Setup Steps
        </button>
        <button
          onClick={handleDisconnect}
          disabled={disconnecting}
          className="w-full py-2 border border-red-200 dark:border-red-800 text-red-500 dark:text-red-400 rounded-lg text-xs font-medium hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors disabled:opacity-50"
        >
          {disconnecting ? <><i className="fas fa-spinner fa-spin mr-1" />Removing…</> : <><i className="fas fa-trash mr-1" />Remove Agent</>}
        </button>
      </div>
    );
  }

  // ── Loading skeleton ────────────────────────────────────────────────────────
  if (viewMode === 'loading') {
    return (
      <div className="flex items-center justify-center py-10 text-gray-400">
        <i className="fas fa-spinner fa-spin mr-2" /> Checking agent status…
      </div>
    );
  }

  return (
    <div className="space-y-5">
      {/* Step indicator */}
      <div className="flex items-center gap-0">
        {stepLabels.map((label, idx) => {
          const n = idx + 1;
          const active = step === n;
          const done = step > n;
          return (
            <div key={n} className="flex items-center flex-1 last:flex-none">
              <button
                onClick={() => setStep(n)}
                className={`flex items-center gap-2 px-3 py-2 rounded-lg text-xs font-medium transition-colors ${active ? 'bg-indigo-600 text-white' : done ? 'bg-emerald-100 dark:bg-emerald-900/30 text-emerald-700 dark:text-emerald-400 hover:bg-emerald-200 dark:hover:bg-emerald-900/50' : 'text-gray-400 hover:text-gray-600'
                  }`}
              >
                <span className={`w-5 h-5 rounded-full flex items-center justify-center text-[10px] font-bold shrink-0 ${active ? 'bg-white/20' : done ? 'bg-emerald-500 text-white' : 'bg-gray-200 dark:bg-gray-600 text-gray-500'
                  }`}>
                  {done ? <i className="fas fa-check" /> : n}
                </span>
                {label}
              </button>
              {idx < stepLabels.length - 1 && <div className="flex-1 h-px bg-gray-200 dark:bg-gray-700 mx-2" />}
            </div>
          );
        })}
      </div>

      {/* Step 1: Create DB user */}
      {step === 1 && (
        <div className="space-y-3">
          <div className="bg-purple-50 dark:bg-purple-900/20 border border-purple-100 dark:border-purple-800 rounded-xl p-4">
            <p className="text-xs font-semibold text-purple-800 dark:text-purple-300 mb-1 flex items-center gap-2">
              <i className="fas fa-user-shield" /> Step 1 — Create a read-only monitoring user
            </p>
            <p className="text-xs text-purple-600 dark:text-purple-400">Run this SQL on your {sqlEngine === 'postgres' ? 'PostgreSQL' : 'MySQL/MariaDB'} instance:</p>
          </div>
          {/* Engine toggle */}
          <div className="flex gap-2">
            {(['mysql', 'postgres'] as const).map(e => (
              <button
                key={e}
                onClick={() => setSqlEngine(e)}
                className={`px-3 py-1 text-xs rounded-md font-medium transition-colors ${
                  sqlEngine === e
                    ? 'bg-indigo-600 text-white'
                    : 'bg-gray-100 dark:bg-gray-700 text-gray-500 dark:text-gray-400 hover:bg-gray-200 dark:hover:bg-gray-600'
                  }`}
              >
                {e === 'mysql' ? 'MySQL / MariaDB' : 'PostgreSQL'}
              </button>
            ))}
          </div>

          {/* Permissions explanation */}
          <div className="bg-slate-50 dark:bg-slate-800/40 border border-slate-200 dark:border-slate-700/50 rounded-xl p-4 space-y-2">
            <p className="text-xs font-semibold text-slate-700 dark:text-slate-300 flex items-center gap-2">
              <i className="fas fa-shield-alt text-slate-500" /> What access does this user get?
            </p>
            {isPostgres ? (
              <ul className="space-y-1.5 text-xs text-slate-600 dark:text-slate-400">
                <li className="flex gap-2"><span className="font-mono bg-slate-200 dark:bg-slate-700 px-1 rounded shrink-0">CONNECT</span><span>Allows the agent to open a connection to the database. No data access on its own.</span></li>
                <li className="flex gap-2"><span className="font-mono bg-slate-200 dark:bg-slate-700 px-1 rounded shrink-0">USAGE ON SCHEMA public</span><span>Lets the agent see the list of schema objects (tables, views). Does <strong>not</strong> grant access to row data.</span></li>
                <li className="flex gap-2"><span className="font-mono bg-slate-200 dark:bg-slate-700 px-1 rounded shrink-0">SELECT ON ALL TABLES</span><span>Reads system catalog views like <code className="bg-slate-200 dark:bg-slate-700 px-0.5 rounded">pg_stat_user_tables</code> and <code className="bg-slate-200 dark:bg-slate-700 px-0.5 rounded">pg_stats</code> for table sizes, index usage, and row counts. We <strong>never</strong> read your business data rows.</span></li>
                <li className="flex gap-2"><span className="font-mono bg-slate-200 dark:bg-slate-700 px-1 rounded shrink-0">pg_stat_statements</span><span>Extension that exposes aggregated query statistics (call counts, avg/max time, rows). Query text is normalized — literals and parameter values are <strong>stripped out</strong>.</span></li>
              </ul>
            ) : (
              <ul className="space-y-1.5 text-xs text-slate-600 dark:text-slate-400">
                <li className="flex gap-2"><span className="font-mono bg-slate-200 dark:bg-slate-700 px-1 rounded shrink-0">PROCESS</span><span>Allows the agent to see active query threads via <code className="bg-slate-200 dark:bg-slate-700 px-0.5 rounded">SHOW PROCESSLIST</code>. Does <strong>not</strong> access table data.</span></li>
                <li className="flex gap-2"><span className="font-mono bg-slate-200 dark:bg-slate-700 px-1 rounded shrink-0">REPLICATION CLIENT</span><span>Allows reading binary log position via <code className="bg-slate-200 dark:bg-slate-700 px-0.5 rounded">SHOW MASTER/REPLICA STATUS</code>. Used to detect replication lag. Read-only metadata only.</span></li>
                <li className="flex gap-2"><span className="font-mono bg-slate-200 dark:bg-slate-700 px-1 rounded shrink-0">SELECT ON performance_schema</span><span>Reads query digest statistics (<code className="bg-slate-200 dark:bg-slate-700 px-0.5 rounded">events_statements_summary_by_digest</code>) — call counts, avg/max time, rows examined. Literals are normalized; <strong>no actual query values</strong> are captured.</span></li>
                <li className="flex gap-2"><span className="font-mono bg-slate-200 dark:bg-slate-700 px-1 rounded shrink-0">SELECT ON sys</span><span>Reads I/O and table usage summaries from the sys schema helper views (e.g., <code className="bg-slate-200 dark:bg-slate-700 px-0.5 rounded">sys.schema_table_statistics</code>). No row data.</span></li>
                <li className="flex gap-2"><span className="font-mono bg-slate-200 dark:bg-slate-700 px-1 rounded shrink-0">SELECT ON &lt;your_database&gt;</span><span>Reads table and column metadata from <code className="bg-slate-200 dark:bg-slate-700 px-0.5 rounded">information_schema</code> for schema analysis (table names, column types, index definitions). We <strong>never</strong> select from your application tables.</span></li>
              </ul>
            )}
            <p className="text-[11px] text-slate-500 dark:text-slate-500 pt-1 border-t border-slate-200 dark:border-slate-700">
              <i className="fas fa-lock mr-1" /> This user is read-only. XamOps cannot modify, insert, or delete any data in your database.
            </p>
          </div>

          {/* Parameter requirements — RDS vs self-hosted */}
          <div className="bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-700/50 rounded-xl p-4 space-y-2">
            {endpoint ? (
              /* ── RDS: use Parameter Group ── */
              <>
                <p className="text-xs font-semibold text-amber-800 dark:text-amber-300 flex items-center gap-2">
                  <i className="fas fa-cogs" /> AWS RDS — Required Parameter Group Settings
                </p>
                {isPostgres ? (
                  <div className="space-y-2">
                    <p className="text-xs text-amber-700 dark:text-amber-400">
                      To enable slow query analysis, add <code className="bg-amber-100 dark:bg-amber-800/40 px-1 rounded font-mono">pg_stat_statements</code> to your RDS parameter group:
                    </p>
                    <ol className="text-xs text-amber-700 dark:text-amber-400 space-y-1 list-decimal pl-4">
                      <li>AWS Console → RDS → Parameter Groups → create or edit a group</li>
                      <li>Set <code className="bg-amber-100 dark:bg-amber-800/40 px-1 rounded font-mono">shared_preload_libraries</code> = <code className="bg-amber-100 dark:bg-amber-800/40 px-1 rounded font-mono">pg_stat_statements</code></li>
                      <li>Assign the parameter group to your instance → <strong>Reboot</strong></li>
                      <li>Run once on your DB: <code className="bg-amber-100 dark:bg-amber-800/40 px-1 rounded font-mono">CREATE EXTENSION IF NOT EXISTS pg_stat_statements;</code></li>
                    </ol>
                    <p className="text-[11px] text-amber-600 dark:text-amber-500">
                      Without this, instance metrics (connections, table sizes, deadlocks) still work — only slow query analysis is disabled.
                    </p>
                  </div>
                ) : (
                  <div className="space-y-2">
                    <p className="text-xs text-amber-700 dark:text-amber-400">
                      Enable <code className="bg-amber-100 dark:bg-amber-800/40 px-1 rounded font-mono">performance_schema</code> in your RDS parameter group:
                    </p>
                    <ol className="text-xs text-amber-700 dark:text-amber-400 space-y-1 list-decimal pl-4">
                      <li>AWS Console → RDS → Parameter Groups → create or edit a group</li>
                      <li>Set <code className="bg-amber-100 dark:bg-amber-800/40 px-1 rounded font-mono">performance_schema</code> = <code className="bg-amber-100 dark:bg-amber-800/40 px-1 rounded font-mono">1</code></li>
                      <li>Assign the parameter group to your instance → <strong>Reboot</strong></li>
                    </ol>
                    <p className="text-[11px] text-amber-600 dark:text-amber-500">
                      Without this, instance metrics (connections, table sizes, lock waits) still work — only slow query analysis is disabled.
                    </p>
                  </div>
                )}
              </>
            ) : (
              /* ── Self-hosted (EC2 / on-prem): edit postgresql.conf or my.cnf ── */
              <>
                <p className="text-xs font-semibold text-amber-800 dark:text-amber-300 flex items-center gap-2">
                  <i className="fas fa-server" /> Self-hosted — Required Config Changes
                </p>
                {isPostgres ? (
                  <div className="space-y-2">
                    <p className="text-xs text-amber-700 dark:text-amber-400">
                      To enable slow query analysis, add <code className="bg-amber-100 dark:bg-amber-800/40 px-1 rounded font-mono">pg_stat_statements</code> to <code className="bg-amber-100 dark:bg-amber-800/40 px-1 rounded font-mono">postgresql.conf</code>:
                    </p>
                    <ol className="text-xs text-amber-700 dark:text-amber-400 space-y-1 list-decimal pl-4">
                      <li>Edit <code className="bg-amber-100 dark:bg-amber-800/40 px-1 rounded font-mono">/etc/postgresql/*/main/postgresql.conf</code></li>
                      <li>Add: <code className="bg-amber-100 dark:bg-amber-800/40 px-1 rounded font-mono">shared_preload_libraries = 'pg_stat_statements'</code></li>
                      <li>Restart: <code className="bg-amber-100 dark:bg-amber-800/40 px-1 rounded font-mono">sudo systemctl restart postgresql</code></li>
                      <li>Run once in psql: <code className="bg-amber-100 dark:bg-amber-800/40 px-1 rounded font-mono">CREATE EXTENSION IF NOT EXISTS pg_stat_statements;</code></li>
                    </ol>
                    <p className="text-[11px] text-amber-600 dark:text-amber-500">
                      Without this, instance metrics (connections, table sizes, deadlocks) still work — only slow query analysis is disabled.
                    </p>
                  </div>
                ) : (
                  <div className="space-y-2">
                    <p className="text-xs text-amber-700 dark:text-amber-400">
                      Enable <code className="bg-amber-100 dark:bg-amber-800/40 px-1 rounded font-mono">performance_schema</code> in <code className="bg-amber-100 dark:bg-amber-800/40 px-1 rounded font-mono">my.cnf</code>:
                    </p>
                    <ol className="text-xs text-amber-700 dark:text-amber-400 space-y-1 list-decimal pl-4">
                      <li>Edit <code className="bg-amber-100 dark:bg-amber-800/40 px-1 rounded font-mono">/etc/mysql/my.cnf</code> (or <code className="bg-amber-100 dark:bg-amber-800/40 px-1 rounded font-mono">my.ini</code> on Windows)</li>
                      <li>Under <code className="bg-amber-100 dark:bg-amber-800/40 px-1 rounded font-mono">[mysqld]</code> add: <code className="bg-amber-100 dark:bg-amber-800/40 px-1 rounded font-mono">performance_schema=ON</code></li>
                      <li>Restart: <code className="bg-amber-100 dark:bg-amber-800/40 px-1 rounded font-mono">sudo systemctl restart mysql</code></li>
                    </ol>
                    <p className="text-[11px] text-amber-600 dark:text-amber-500">
                      Without this, instance metrics (connections, table sizes, lock waits) still work — only slow query analysis is disabled.
                    </p>
                  </div>
                )}
              </>
            )}
          </div>

          <div className="relative">
            <pre className="bg-gray-900 text-gray-100 rounded-xl p-4 text-xs overflow-x-auto leading-relaxed">
              {isPostgres ? postgresSql : mysqlSql}
            </pre>
            <button
              onClick={() => copy(isPostgres ? postgresSql : mysqlSql, 'sql')}
              className="absolute top-2 right-2 px-2 py-1 bg-gray-700 hover:bg-gray-600 text-gray-200 rounded text-[10px] transition-colors"
            >
              {copied === 'sql' ? <><i className="fas fa-check mr-1" />Copied</> : <><i className="fas fa-copy mr-1" />Copy</>}
            </button>
          </div>
          <button onClick={() => setStep(2)} className="w-full py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-lg text-xs font-medium transition-colors">
            Next: Configure Connection <i className="fas fa-arrow-right ml-1" />
          </button>
        </div>
      )}

      {/* Step 2: Configure connections */}
      {step === 2 && (
        <div className="space-y-3">
          <div className="bg-sky-50 dark:bg-sky-900/20 border border-sky-100 dark:border-sky-800 rounded-xl p-4">
            <p className="text-xs font-semibold text-sky-800 dark:text-sky-300 mb-1 flex items-center gap-2">
              <i className="fas fa-plug" /> Step 2 — Configure database connections
            </p>
            <p className="text-xs text-sky-600 dark:text-sky-400">One agent can monitor multiple databases. Add each one below.</p>
          </div>

          {/* Per-DSN cards */}
          {dsns.map((d, idx) => (
            <div key={idx} className="border border-gray-200 dark:border-gray-600 rounded-xl p-3 space-y-2">
              <div className="flex items-center justify-between">
                <span className="text-xs font-semibold text-gray-600 dark:text-gray-400">
                  Database {idx + 1} <span className="ml-1 text-gray-400 font-mono">DB_DSN_{idx}</span>
                </span>
                {idx > 0 && (
                  <button onClick={() => removeDsn(idx)} className="text-xs text-red-400 hover:text-red-600 transition-colors">
                    <i className="fas fa-trash-alt mr-1" />Remove
                  </button>
                )}
              </div>
              {/* Engine toggle + SSL mode */}
              <div className="flex items-center gap-1.5 flex-wrap">
                {(['mysql', 'postgres'] as const).map(e => (
                  <button key={e}
                    onClick={() => updateDsn(idx, 'engine', e)}
                    className={`px-2 py-0.5 text-xs rounded font-medium transition-colors ${d.engine === e ? 'bg-indigo-600 text-white' : 'bg-gray-100 dark:bg-gray-700 text-gray-500 dark:text-gray-400 hover:bg-gray-200 dark:hover:bg-gray-600'}`}>
                    {e === 'mysql' ? 'MySQL' : 'PostgreSQL'}
                  </button>
                ))}
                {d.engine === 'postgres' && (
                  <div className="flex items-center gap-1 ml-2 border-l border-gray-200 dark:border-gray-600 pl-2">
                    <span className="text-[10px] text-gray-400 mr-1">SSL:</span>
                    {(['disable', 'require', 'prefer'] as const).map(m => (
                      <button key={m} onClick={() => updateDsn(idx, 'sslMode', m)}
                        className={`px-1.5 py-0.5 text-[10px] rounded font-mono transition-colors ${d.sslMode === m ? 'bg-indigo-600 text-white' : 'bg-gray-100 dark:bg-gray-700 text-gray-500 dark:text-gray-400 hover:bg-gray-200 dark:hover:bg-gray-600'}`}>
                        {m}
                      </button>
                    ))}
                  </div>
                )}
              </div>
              <div className="grid grid-cols-2 gap-2">
                {([
                  { label: 'Host / Endpoint', field: 'host' as const, placeholder: 'mydb.abc.rds.amazonaws.com' },
                  { label: 'Port',            field: 'port' as const, placeholder: d.engine === 'postgres' ? '5432' : '3306' },
                  { label: 'Database Name',   field: 'dbName' as const, placeholder: 'mydb' },
                  { label: 'Username',        field: 'user' as const, placeholder: 'xamops_reader' },
                ]).map(f => (
                  <div key={f.field}>
                    <label className="block text-xs font-medium text-gray-600 dark:text-gray-400 mb-0.5">{f.label}</label>
                    <input type="text" value={d[f.field]} onChange={e => updateDsn(idx, f.field, e.target.value)}
                      placeholder={f.placeholder}
                      className="w-full px-2 py-1.5 text-xs rounded-lg border border-gray-200 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-sky-500" />
                  </div>
                ))}
                <div>
                  <label className="block text-xs font-medium text-gray-600 dark:text-gray-400 mb-0.5">Password</label>
                  <div className="relative">
                    <input type={showPass[idx] ? 'text' : 'password'} value={d.pass}
                      onChange={e => updateDsn(idx, 'pass', e.target.value)}
                      placeholder="••••••••"
                      className="w-full px-2 py-1.5 text-xs rounded-lg border border-gray-200 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-sky-500 pr-7" />
                    <button type="button" onClick={() => setShowPass(p => ({ ...p, [idx]: !p[idx] }))}
                      className="absolute right-2 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600 dark:hover:text-gray-300">
                      <svg xmlns="http://www.w3.org/2000/svg" className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                        {showPass[idx]
                          ? <path strokeLinecap="round" strokeLinejoin="round" d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l3.59 3.59m0 0A9.953 9.953 0 0112 5c4.478 0 8.268 2.943 9.543 7a10.025 10.025 0 01-4.132 5.411m0 0L21 21" />
                          : <><path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" /><path strokeLinecap="round" strokeLinejoin="round" d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" /></>}
                      </svg>
                    </button>
                  </div>
                </div>
              </div>
              <div className="bg-gray-50 dark:bg-gray-700/30 rounded-lg px-2 py-1.5">
                <code className="text-[11px] text-indigo-700 dark:text-indigo-300 break-all">{buildDsn(d)}</code>
              </div>
            </div>
          ))}

          <button onClick={addDsn}
            className="w-full py-2 border border-dashed border-gray-300 dark:border-gray-600 rounded-xl text-xs text-gray-500 dark:text-gray-400 hover:border-indigo-400 hover:text-indigo-600 dark:hover:text-indigo-400 transition-colors">
            <i className="fas fa-plus mr-1" /> Add another database
          </button>

          <p className="text-[10px] font-bold text-gray-500 uppercase tracking-wider pt-1">XamOps Server Config</p>
          <div className="grid grid-cols-2 gap-3">
            {[
              { label: 'XamOps Server URL', val: serverUrl, setter: setServerUrl, placeholder: 'https://your-tunnel-or-public-url' },
              { label: 'Cloud Account ID', val: cloudAccountId, setter: setCloudAccountId, placeholder: '015764906357' },
              { label: 'Cluster / Agent Name', val: clusterName, setter: setClusterName, placeholder: instanceId },
            ].map(f => (
              <div key={f.label}>
                <label className="block text-xs font-medium text-gray-600 dark:text-gray-400 mb-1">{f.label}</label>
                <input type="text" value={f.val} onChange={e => f.setter(e.target.value)} placeholder={f.placeholder}
                  className="w-full px-3 py-1.5 text-xs rounded-lg border border-gray-200 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-sky-500" />
              </div>
            ))}
            <div>
              <label className="block text-xs font-medium text-gray-600 dark:text-gray-400 mb-1">
                Query Limit
                <span className="text-[10px] text-gray-400 ml-1">top N queries by total time</span>
              </label>
              <select value={queryLimit} onChange={e => setQueryLimit(Number(e.target.value))}
                className="w-full px-3 py-1.5 text-xs rounded-lg border border-gray-200 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100 focus:outline-none focus:ring-2 focus:ring-sky-500">
                <option value={50}>50 queries</option>
                <option value={100}>100 queries</option>
                <option value={200}>200 queries</option>
                <option value={500}>500 queries</option>
              </select>
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-600 dark:text-gray-400 mb-1">
                API Key <span className="text-[10px] text-emerald-600 dark:text-emerald-400 ml-1">auto-filled</span>
              </label>
              <input type="password" value={apiKey} readOnly disabled
                className="w-full px-3 py-1.5 text-xs rounded-lg border border-gray-200 dark:border-gray-600 bg-gray-100 dark:bg-gray-700 text-gray-500 dark:text-gray-400 cursor-not-allowed" />
            </div>
          </div>

          <div className="flex gap-2">
            <button onClick={() => setStep(1)} className="flex-none px-4 py-2 bg-gray-100 dark:bg-gray-700 text-gray-600 dark:text-gray-300 rounded-lg text-xs font-medium hover:bg-gray-200 dark:hover:bg-gray-600 transition-colors">
              <i className="fas fa-arrow-left mr-1" />Back
            </button>
            <button
              onClick={() => {
                setSaveStatus('saving');
                const d0 = dsns[0];
                apiClient.post('/api/v1/agent/db/config', {
                  agentName: clusterName, cloudAccountId,
                  dbEngine: d0.engine, dbHost: d0.host, dbPort: d0.port,
                  dbName: d0.dbName, dbUser: d0.user, dbPassword: d0.pass, serverUrl,
                })
                  .then(() => { setSaveStatus('saved'); setTimeout(() => setSaveStatus('idle'), 2000); })
                  .catch(() => { setSaveStatus('error'); setTimeout(() => setSaveStatus('idle'), 2000); });
              }}
              className="flex-none px-4 py-2 bg-gray-100 dark:bg-gray-700 text-gray-600 dark:text-gray-300 rounded-lg text-xs font-medium hover:bg-gray-200 dark:hover:bg-gray-600 transition-colors"
            >
              {saveStatus === 'saving' && <><i className="fas fa-spinner fa-spin mr-1" />Saving…</>}
              {saveStatus === 'saved' && <><i className="fas fa-check mr-1 text-emerald-500" />Saved</>}
              {saveStatus === 'error' && <><i className="fas fa-times mr-1 text-red-500" />Error</>}
              {saveStatus === 'idle' && <><i className="fas fa-save mr-1" />Save Config</>}
            </button>
            <button onClick={() => setStep(3)} className="flex-1 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-lg text-xs font-medium transition-colors">
              Next: Run Agent <i className="fas fa-arrow-right ml-1" />
            </button>
          </div>
        </div>
      )}

      {/* Step 3: Run agent */}
      {step === 3 && (
        <div className="space-y-3">
          <div className="bg-emerald-50 dark:bg-emerald-900/20 border border-emerald-100 dark:border-emerald-800 rounded-xl p-4">
            <p className="text-xs font-semibold text-emerald-800 dark:text-emerald-300 mb-1 flex items-center gap-2">
              <i className="fas fa-rocket" /> Step 3 — Deploy the XamOps DB agent
            </p>
            <p className="text-xs text-emerald-600 dark:text-emerald-400">Run this command in the same VPC as your database (e.g. on an EC2 instance or container):</p>
          </div>
          {/* Stop existing container first */}
          <div>
            <p className="text-[10px] font-bold text-gray-500 uppercase tracking-wider mb-1">
              1. Remove existing container (if any)
            </p>
            <div className="relative">
              <pre className="bg-gray-900 text-gray-100 rounded-xl p-3 text-xs overflow-x-auto">
                {stopCmd}
              </pre>
              <button
                onClick={() => copy(stopCmd, 'stop')}
                className="absolute top-2 right-2 px-2 py-1 bg-gray-700 hover:bg-gray-600 text-gray-200 rounded text-[10px] transition-colors"
              >
                {copied === 'stop' ? <><i className="fas fa-check mr-1" />Copied</> : <><i className="fas fa-copy mr-1" />Copy</>}
              </button>
            </div>
          </div>
          {/* Run command */}
          <div>
            <p className="text-[10px] font-bold text-gray-500 uppercase tracking-wider mb-1">
              2. Start the agent
            </p>
            <div className="relative">
              <pre className="bg-gray-900 text-gray-100 rounded-xl p-4 text-xs overflow-x-auto leading-relaxed whitespace-pre-wrap break-all">
                {runCmd}
              </pre>
              <button
                onClick={() => copy(runCmd.replace(/\\\n\s*/g, ' '), 'cmd')}
                className="absolute top-2 right-2 px-2 py-1 bg-gray-700 hover:bg-gray-600 text-gray-200 rounded text-[10px] transition-colors"
              >
                {copied === 'cmd' ? <><i className="fas fa-check mr-1" />Copied</> : <><i className="fas fa-copy mr-1" />Copy</>}
              </button>
            </div>
          </div>
          {/* Live agent connection status */}
          <div className="flex items-center gap-3 bg-amber-50 dark:bg-amber-900/20 border border-amber-100 dark:border-amber-800 rounded-lg p-3">
            <span className="relative flex h-3 w-3">
              <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-amber-400 opacity-75" />
              <span className="relative inline-flex rounded-full h-3 w-3 bg-amber-400" />
            </span>
            <div className="flex-1">
              <p className="text-xs font-semibold text-amber-700 dark:text-amber-300">Waiting for agent to connect…</p>
              <p className="text-[11px] text-amber-600 dark:text-amber-400">Run the command above on your EC2 instance. Status updates automatically.</p>
            </div>
          </div>
          <button onClick={() => setStep(2)} className="px-4 py-2 bg-gray-100 dark:bg-gray-700 text-gray-600 dark:text-gray-300 rounded-lg text-xs font-medium hover:bg-gray-200 dark:hover:bg-gray-600 transition-colors">
            <i className="fas fa-arrow-left mr-1" />Back
          </button>
        </div>
      )}
    </div>
  );
}

// ── Health Score ──────────────────────────────────────────────────────────────
export function computeHealthScore(
  metrics: DbMetrics | undefined,
  agentMetrics: AgentInstanceMetrics | undefined,
  slowQueriesCount: number,
  failedSecurityChecks: number,
  vacuumStats: AgentVacuumStat[]
): { score: number; status: 'healthy' | 'warning' | 'critical'; issues: string[] } {
  let score = 100;
  const issues: string[] = [];

  if (metrics) {
    if (metrics.avgCpuPercent > 80) { score -= 20; issues.push(`High CPU (${metrics.avgCpuPercent}%)`); }
    else if (metrics.avgCpuPercent > 60) { score -= 10; issues.push(`Elevated CPU (${metrics.avgCpuPercent}%)`); }
    if (metrics.avgSwapUsageMb > 100) { score -= 15; issues.push('Swap usage detected'); }
    if (metrics.replicaLagSeconds > 30) { score -= 10; issues.push('High replica lag'); }
    if (metrics.burstBalance > 0 && metrics.burstBalance < 20) { score -= 10; issues.push('Low burst balance'); }
  }
  if (agentMetrics) {
    const connPct = agentMetrics.maxConnections > 0 ? (agentMetrics.currentConnections / agentMetrics.maxConnections) * 100 : 0;
    if (connPct > 80) { score -= 20; issues.push(`Connection pool near limit (${Math.round(connPct)}%)`); }
    else if (connPct > 60) { score -= 10; issues.push(`Connection pool elevated (${Math.round(connPct)}%)`); }
    if (agentMetrics.innodbBufferPoolHitPct > 0 && agentMetrics.innodbBufferPoolHitPct < 90) {
      score -= 10; issues.push(`Low buffer pool hit (${Math.round(agentMetrics.innodbBufferPoolHitPct)}%)`);
    }
  }
  if (slowQueriesCount > 20) { score -= 20; issues.push(`${slowQueriesCount} slow queries detected`); }
  else if (slowQueriesCount > 5) { score -= 10; issues.push(`${slowQueriesCount} slow queries`); }
  if (failedSecurityChecks > 0) { score -= Math.min(failedSecurityChecks * 5, 20); issues.push(`${failedSecurityChecks} security failure${failedSecurityChecks > 1 ? 's' : ''}`); }
  const highBloatTables = vacuumStats.filter(v => (v.deadTupRatio ?? 0) > 0.30).length;
  if (highBloatTables > 0) { score -= Math.min(highBloatTables * 3, 15); issues.push(`${highBloatTables} table${highBloatTables > 1 ? 's' : ''} with high bloat`); }

  score = Math.max(0, Math.min(100, score));
  const status = score >= 80 ? 'healthy' : score >= 50 ? 'warning' : 'critical';
  return { score, status, issues };
}

// ── Props ─────────────────────────────────────────────────────────────────────

export interface DrawerInstance {
  instanceId: string;
  source: 'rds' | 'agent';
  rdsData?: DbInstance;
  agentHost?: string;
  engine?: string;
}

interface Props {
  instance: DrawerInstance | null;
  metrics?: DbMetrics;
  agentStats: AgentQueryStat[];
  agentInstanceMetrics: AgentInstanceMetrics[];
  slowQueries: SlowQuery[];
  idle: DbIdleInstance[];
  rightsizing: DbRightsizing[];
  security: DbSecurityCheck[];
  allSnapshots: DbSnapshot[];
  costs: DbCost[];
  fetchTimeseries: (id: string, hours: number) => Promise<DbInstanceTimeseries | null>;
  fetchAgentTimeseries: (hours: number) => Promise<AgentTimeseries[]>;
  fetchAgentTableSizes: () => Promise<AgentTableSize[]>;
  fetchAgentVacuumStats: () => Promise<AgentVacuumStat[]>;
  fetchAgentUserActivity: () => Promise<AgentUserActivity[]>;
  fetchAgentIndexUsage: () => Promise<AgentIndexUsage[]>;
  fetchAgentTableActivity: () => Promise<AgentTableActivity[]>;
  onConnected?: (inst: DrawerInstance) => void;
  onHealthChange?: (health: { score: number; status: 'healthy' | 'warning' | 'critical'; issues: string[] }) => void;
}

type DetailTab = 'overview' | 'performance' | 'slowqueries' | 'queries' | 'maintenance' | 'users' | 'cost' | 'security' | 'snapshots';

// ── SQL syntax highlight ──────────────────────────────────────────────────────
const SQL_KW_RE = /\b(SELECT|DISTINCT|FROM|WHERE|JOIN|LEFT|RIGHT|INNER|OUTER|FULL|CROSS|ON|AND|OR|NOT|IN|LIKE|ILIKE|BETWEEN|IS|NULL|AS|BY|ASC|DESC|GROUP|ORDER|HAVING|LIMIT|OFFSET|INSERT|INTO|VALUES|UPDATE|SET|DELETE|CREATE|DROP|ALTER|TABLE|INDEX|UNION|ALL|WITH|CASE|WHEN|THEN|ELSE|END|EXISTS|COUNT|SUM|AVG|MAX|MIN|COALESCE|CAST|IF|CONCAT|TRIM|LENGTH|NOW|DATE|INTERVAL|EXTRACT|TO_CHAR|RETURNING)\b/gi;

function SqlHighlight({ sql, truncate }: { sql: string; truncate?: number }) {
  const display = truncate && sql.length > truncate ? sql.slice(0, truncate) + '…' : sql;
  const nodes: ReactElement[] = [];
  let last = 0, k = 0;
  const re = new RegExp(SQL_KW_RE.source, 'gi');
  let m: RegExpExecArray | null;
  while ((m = re.exec(display)) !== null) {
    if (m.index > last)
      nodes.push(<span key={k++} className="text-slate-500 dark:text-slate-400">{display.slice(last, m.index)}</span>);
    nodes.push(<span key={k++} className="text-violet-600 dark:text-violet-400 font-semibold">{m[0].toUpperCase()}</span>);
    last = m.index + m[0].length;
  }
  if (last < display.length)
    nodes.push(<span key={k++} className="text-slate-500 dark:text-slate-400">{display.slice(last)}</span>);
  return <code className="font-mono text-[11px] break-all leading-relaxed">{nodes}</code>;
}

// Severity level helper
function sqSeverity(avgMs: number) {
  if (avgMs >= 500) return { label: 'Critical', bg: 'bg-red-100 dark:bg-red-900/30',   text: 'text-red-700 dark:text-red-400',   border: 'border-red-200/70 dark:border-red-700/40',   dot: 'bg-red-500',  left: '#ef4444' };
  if (avgMs >= 100) return { label: 'Warning',  bg: 'bg-amber-100 dark:bg-amber-900/30', text: 'text-amber-700 dark:text-amber-400', border: 'border-amber-200/70 dark:border-amber-700/40', dot: 'bg-amber-400', left: '#f59e0b' };
  return               { label: 'Info',     bg: 'bg-sky-100 dark:bg-sky-900/30',   text: 'text-sky-700 dark:text-sky-400',   border: 'border-sky-200/70 dark:border-sky-700/40',   dot: 'bg-sky-400',  left: '#0ea5e9' };
}

// Normalised shape shared by both CloudWatch and agent slow-query sources
interface NormSQ {
  query: string; avgMs: number; maxMs: number; calls: number | null;
  lockMs: number; rowsExamined: number; rowsSent: number;
  noIndexUsed?: boolean; cacheHitPct?: number; timestamp?: string;
  source: 'cloudwatch' | 'agent';
}

// ── Slow Queries Tab (sub-component, owns its own UI state) ────────────────────
function SlowQueriesTab({ cloudwatchQueries, agentQueries, isPostgres }: {
  cloudwatchQueries: SlowQuery[];
  agentQueries: AgentQueryStat[];
  isPostgres: boolean;
}) {
  const [search,          setSearch]          = useState('');
  const [sevFilter,       setSevFilter]       = useState<'all'|'critical'|'warning'|'info'>('all');
  const [minAvgMs,        setMinAvgMs]        = useState('');
  const [sortKey,         setSortKey]         = useState<'avg'|'max'|'calls'|'lock'|'rows'>('avg');
  const [sortDir,         setSortDir]         = useState<'desc'|'asc'>('desc');
  const [page,            setPage]            = useState(0);
  const [expanded,        setExpanded]        = useState<Set<number>>(new Set());
  const [copied,          setCopied]          = useState<number|null>(null);
  const [explainQuery,    setExplainQuery]    = useState<string|null>(null);
  const [dismissCallout,  setDismissCallout]  = useState(false);

  const PAGE_SIZE = 8;
  const isAgent = cloudwatchQueries.length === 0 && agentQueries.length > 0;

  // Normalise both sources into a unified shape
  const allQueries: NormSQ[] = cloudwatchQueries.length > 0
    ? cloudwatchQueries.map(q => ({
        query: q.query, avgMs: q.queryTimeSeconds * 1000, maxMs: q.queryTimeSeconds * 1000,
        calls: null, lockMs: q.lockTimeSeconds * 1000,
        rowsExamined: q.rowsExamined, rowsSent: q.rowsSent,
        timestamp: q.queryTimestamp, source: 'cloudwatch' as const,
      }))
    : agentQueries
        .filter(s => s.avgTimeSeconds > 0.01)
        .sort((a, b) => b.avgTimeSeconds - a.avgTimeSeconds)
        .slice(0, 50)
        .map(s => {
          const hits = s.sharedBlksHit + s.sharedBlksRead;
          return {
            query: s.queryDigest, avgMs: s.avgTimeSeconds * 1000, maxMs: s.maxTimeSeconds * 1000,
            calls: s.callCount, lockMs: s.lockTimeSeconds * 1000,
            rowsExamined: s.rowsExamined, rowsSent: s.rowsSent,
            noIndexUsed: s.noIndexUsed,
            cacheHitPct: hits > 0 ? Math.round((s.sharedBlksHit / hits) * 100) : undefined,
            source: 'agent' as const,
          };
        });

  const critCount = allQueries.filter(q => q.avgMs >= 500).length;
  const warnCount = allQueries.filter(q => q.avgMs >= 100 && q.avgMs < 500).length;
  const infoCount = allQueries.filter(q => q.avgMs < 100).length;

  // Filter → sort inline (≤50 items, no memoisation needed)
  let filtered = allQueries;
  if (search)         filtered = filtered.filter(q => q.query.toLowerCase().includes(search.toLowerCase()));
  if (sevFilter !== 'all') filtered = filtered.filter(q => {
    const s = q.avgMs >= 500 ? 'critical' : q.avgMs >= 100 ? 'warning' : 'info';
    return s === sevFilter;
  });
  const minMs = parseFloat(minAvgMs);
  if (!isNaN(minMs) && minMs > 0) filtered = filtered.filter(q => q.avgMs >= minMs);
  const dir = sortDir === 'desc' ? -1 : 1;
  filtered = [...filtered].sort((a, b) => {
    switch (sortKey) {
      case 'avg':   return dir * (b.avgMs   - a.avgMs);
      case 'max':   return dir * (b.maxMs   - a.maxMs);
      case 'calls': return dir * ((b.calls ?? 0) - (a.calls ?? 0));
      case 'lock':  return dir * (b.lockMs  - a.lockMs);
      case 'rows':  return dir * (b.rowsSent - a.rowsSent);
      default: return 0;
    }
  });

  const totalPages = Math.ceil(filtered.length / PAGE_SIZE);
  const safePage   = Math.min(page, Math.max(0, totalPages - 1));
  const pageItems  = filtered.slice(safePage * PAGE_SIZE, (safePage + 1) * PAGE_SIZE);

  // Reset page on any filter/sort change
  useEffect(() => { setPage(0); }, [search, sevFilter, minAvgMs, sortKey, sortDir]);

  const handleSort = (k: typeof sortKey) => {
    if (k === sortKey) setSortDir(d => d === 'desc' ? 'asc' : 'desc');
    else { setSortKey(k); setSortDir('desc'); }
  };

  const handleCopy = (q: NormSQ, idx: number) => {
    navigator.clipboard.writeText(q.query).then(() => {
      setCopied(idx); setTimeout(() => setCopied(null), 2000);
    });
  };

  // ── Empty state ──────────────────────────────────────────────────────────────
  if (allQueries.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-14 gap-3">
        <svg width="80" height="64" viewBox="0 0 80 64" fill="none" className="text-gray-300 dark:text-gray-600">
          <rect x="8" y="8" width="64" height="48" rx="4" stroke="currentColor" strokeWidth="1.5" strokeDasharray="4 3" />
          <line x1="18" y1="22" x2="44" y2="22" stroke="currentColor" strokeWidth="1.5" />
          <line x1="18" y1="32" x2="54" y2="32" stroke="currentColor" strokeWidth="1.5" strokeOpacity="0.6" />
          <line x1="18" y1="42" x2="36" y2="42" stroke="currentColor" strokeWidth="1.5" strokeOpacity="0.4" />
          <circle cx="60" cy="12" r="8" fill="none" stroke="currentColor" strokeWidth="1.5" />
          <line x1="57" y1="12" x2="63" y2="12" stroke="currentColor" strokeWidth="1.5" />
        </svg>
        <p className="text-sm font-medium text-gray-400 dark:text-gray-500">No slow queries detected</p>
        <p className="text-[11px] text-gray-400 dark:text-gray-500 text-center max-w-xs leading-relaxed">
          Deploy the XamOps DB agent to collect performance_schema data, or enable slow query log on RDS and publish to CloudWatch Logs.
        </p>
      </div>
    );
  }

  // ── Full UI ──────────────────────────────────────────────────────────────────
  return (
    <div className="space-y-4">

      {/* ── Explain Query overlay ── */}
      {explainQuery !== null && (
        <div
          className="fixed inset-0 z-[120] flex items-end sm:items-center justify-center p-4 bg-black/50 backdrop-blur-sm"
          onClick={() => setExplainQuery(null)}
        >
          <div
            className="w-full max-w-xl bg-white dark:bg-gray-900 rounded-2xl shadow-2xl border border-gray-200/60 dark:border-gray-700/40 overflow-hidden"
            onClick={e => e.stopPropagation()}
          >
            {/* Modal header */}
            <div className="flex items-center justify-between px-4 py-3 border-b border-gray-200/60 dark:border-gray-700/40 bg-gray-50/60 dark:bg-gray-800/40">
              <div className="flex items-center gap-2">
                <span className="w-7 h-7 rounded-lg bg-indigo-100 dark:bg-indigo-900/40 flex items-center justify-center shrink-0">
                  <i className="fas fa-terminal text-indigo-500 text-[11px]" />
                </span>
                <span className="text-sm font-semibold text-gray-800 dark:text-gray-100">Explain Query</span>
              </div>
              <button
                onClick={() => setExplainQuery(null)}
                className="w-7 h-7 flex items-center justify-center rounded-lg hover:bg-gray-200 dark:hover:bg-gray-700 text-gray-400 hover:text-gray-600 dark:hover:text-gray-200 transition-colors"
              >
                <i className="fas fa-times text-xs" />
              </button>
            </div>

            <div className="p-4 space-y-3">
              {/* Original query */}
              <div>
                <p className="text-[10px] text-gray-400 mb-1.5 uppercase tracking-wider font-semibold">Original Query</p>
                <div className="bg-gray-50 dark:bg-gray-800/60 rounded-xl p-3 max-h-28 overflow-y-auto border border-gray-200/60 dark:border-gray-700/40">
                  <SqlHighlight sql={explainQuery} />
                </div>
              </div>

              {/* Explain statement */}
              <div>
                <p className="text-[10px] text-gray-400 mb-1.5 uppercase tracking-wider font-semibold">Explain Statement</p>
                <div className="bg-slate-900 dark:bg-slate-950 rounded-xl p-3 relative group border border-slate-700/40">
                  <code className="font-mono text-[11px] break-all leading-relaxed">
                    <span className="text-sky-400 font-semibold">
                      {isPostgres ? 'EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) ' : 'EXPLAIN ANALYZE '}
                    </span>
                    <span className="text-slate-400">{explainQuery}</span>
                  </code>
                  <button
                    onClick={() => navigator.clipboard.writeText(
                      (isPostgres ? 'EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) ' : 'EXPLAIN ANALYZE ') + explainQuery
                    )}
                    className="absolute top-2 right-2 opacity-0 group-hover:opacity-100 transition-opacity px-2 py-1 bg-indigo-600 hover:bg-indigo-500 text-white text-[10px] rounded-lg font-medium"
                  >
                    <i className="fas fa-copy mr-1 text-[9px]" />Copy
                  </button>
                </div>
              </div>

              {/* Warning */}
              <div className="flex items-start gap-2 px-3 py-2.5 bg-amber-50 dark:bg-amber-900/20 rounded-xl border border-amber-200/50 dark:border-amber-700/30 text-[11px] text-amber-700 dark:text-amber-300">
                <i className="fas fa-triangle-exclamation mt-0.5 shrink-0 text-amber-500" />
                <span>Run <strong>EXPLAIN ANALYZE</strong> on a replica or dev environment — it executes the query and can impact production.</span>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ── Summary strip (clickable severity filters) ── */}
      <div className="flex items-center gap-2 flex-wrap">
        <span className="text-xs text-gray-500 dark:text-gray-400 mr-1">
          {filtered.length !== allQueries.length
            ? `${filtered.length} / ${allQueries.length}`
            : allQueries.length}{' '}{allQueries.length === 1 ? 'query' : 'queries'}
        </span>
        {([
          { key: 'critical' as const, count: critCount, dot: 'bg-red-500',  on: 'bg-red-600 text-white border-red-600',    off: 'bg-red-50 dark:bg-red-900/20 text-red-700 dark:text-red-400 border-red-200/70 dark:border-red-700/40 hover:bg-red-100 dark:hover:bg-red-900/30' },
          { key: 'warning'  as const, count: warnCount, dot: 'bg-amber-400', on: 'bg-amber-500 text-white border-amber-500', off: 'bg-amber-50 dark:bg-amber-900/20 text-amber-700 dark:text-amber-400 border-amber-200/70 dark:border-amber-700/40 hover:bg-amber-100 dark:hover:bg-amber-900/30' },
          { key: 'info'     as const, count: infoCount, dot: 'bg-sky-400',   on: 'bg-sky-500 text-white border-sky-500',     off: 'bg-sky-50 dark:bg-sky-900/20 text-sky-700 dark:text-sky-400 border-sky-200/70 dark:border-sky-700/40 hover:bg-sky-100 dark:hover:bg-sky-900/30' },
        ].filter(b => b.count > 0).map(b => (
          <button
            key={b.key}
            onClick={() => setSevFilter(s => (s === b.key ? 'all' : b.key))}
            className={`flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[11px] font-medium border transition-all duration-200 ${sevFilter === b.key ? b.on : b.off}`}
          >
            <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${sevFilter === b.key ? 'bg-white' : b.dot}`} />
            {b.count} {b.key.charAt(0).toUpperCase() + b.key.slice(1)}
          </button>
        )))}
        {sevFilter !== 'all' && (
          <button onClick={() => setSevFilter('all')} className="text-[10px] text-gray-400 hover:text-gray-600 dark:hover:text-gray-200 underline">
            Clear
          </button>
        )}
      </div>

      {/* ── Dismissible performance_schema callout ── */}
      {isAgent && !dismissCallout && (
        <div className="flex items-start gap-3 px-3.5 py-3 rounded-xl bg-indigo-50/80 dark:bg-indigo-950/30 border border-indigo-200/60 dark:border-indigo-800/30">
          <div className="shrink-0 w-7 h-7 rounded-lg bg-indigo-100 dark:bg-indigo-900/40 flex items-center justify-center mt-0.5">
            <i className="fas fa-database text-indigo-500 text-[11px]" />
          </div>
          <div className="flex-1 min-w-0">
            <p className="text-[11px] font-semibold text-indigo-700 dark:text-indigo-300 mb-0.5">Aggregated query statistics</p>
            <p className="text-[11px] text-indigo-600/80 dark:text-indigo-400/80 leading-relaxed">
              Showing queries with avg execution &gt;10ms from{' '}
              <strong>{isPostgres ? 'pg_stat_statements' : 'performance_schema'}</strong>.{' '}
              Counters accumulate since last restart — not a real-time window. For per-query timestamps enable slow query log on RDS and publish to CloudWatch Logs.
            </p>
          </div>
          <button
            onClick={() => setDismissCallout(true)}
            className="shrink-0 text-indigo-400 hover:text-indigo-600 dark:hover:text-indigo-300 mt-0.5 w-5 h-5 flex items-center justify-center"
          >
            <i className="fas fa-times text-xs" />
          </button>
        </div>
      )}

      {/* ── Filter + sort row ── */}
      <div className="flex items-center gap-2 flex-wrap">
        {/* Keyword search */}
        <div className="relative flex-1 min-w-[140px]">
          <i className="fas fa-search absolute left-2.5 top-1/2 -translate-y-1/2 text-gray-400 text-[10px] pointer-events-none" />
          <input
            type="text" value={search} onChange={e => setSearch(e.target.value)}
            placeholder="Filter queries…"
            className="w-full pl-7 pr-3 py-1.5 text-xs rounded-lg border border-gray-200/80 dark:border-gray-700/60 bg-white/80 dark:bg-gray-800/60 text-gray-800 dark:text-gray-200 placeholder-gray-400 focus:outline-none focus:ring-1 focus:ring-indigo-500/50"
          />
        </div>
        {/* Min avg ms threshold */}
        <div className="relative">
          <i className="fas fa-stopwatch absolute left-2.5 top-1/2 -translate-y-1/2 text-gray-400 text-[10px] pointer-events-none" />
          <input
            type="number" value={minAvgMs} onChange={e => setMinAvgMs(e.target.value)}
            placeholder="≥ ms"
            className="w-20 pl-7 pr-2 py-1.5 text-xs rounded-lg border border-gray-200/80 dark:border-gray-700/60 bg-white/80 dark:bg-gray-800/60 text-gray-800 dark:text-gray-200 placeholder-gray-400 focus:outline-none focus:ring-1 focus:ring-indigo-500/50"
          />
        </div>
        {/* Sort pill group */}
        <div className="flex items-center gap-0.5 bg-gray-100/80 dark:bg-gray-800/60 rounded-lg p-0.5 border border-gray-200/50 dark:border-gray-700/40">
          <span className="text-[9px] text-gray-400 px-1.5 font-medium shrink-0">Sort:</span>
          {(['avg', 'max', 'calls', 'lock', 'rows'] as const).map(k => (
            <button key={k} onClick={() => handleSort(k)}
              className={`px-2 py-1 text-[10px] font-medium rounded transition-all duration-150 flex items-center gap-0.5 ${
                sortKey === k
                  ? 'bg-white dark:bg-gray-700 text-indigo-600 dark:text-indigo-400 shadow-sm'
                  : 'text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-200'
              }`}>
              {k === 'avg' ? 'Avg' : k === 'max' ? 'Max' : k === 'calls' ? 'Calls' : k === 'lock' ? 'Lock' : 'Rows'}
              {sortKey === k && <i className={`fas fa-arrow-${sortDir === 'desc' ? 'down' : 'up'} text-[8px] ml-0.5`} />}
            </button>
          ))}
        </div>
      </div>

      {/* ── Query cards ── */}
      {pageItems.length === 0 ? (
        <div className="text-center py-10 text-xs text-gray-400">No queries match your filters.</div>
      ) : (
        <div className="space-y-2.5">
          {pageItems.map((q, i) => {
            const gi  = safePage * PAGE_SIZE + i;           // global index for expand / copy state
            const sev = sqSeverity(q.avgMs);
            const isExpanded = expanded.has(gi);
            const TRUNC = 160;
            const isTruncated = q.query.length > TRUNC;

            return (
              <div
                key={gi}
                className="rounded-xl bg-white/70 dark:bg-gray-800/50 backdrop-blur-sm border border-gray-200/60 dark:border-gray-700/40 shadow-sm hover:shadow-md transition-shadow duration-200 overflow-hidden"
                style={{ borderLeft: `3px solid ${sev.left}` }}
              >
                <div className="px-3.5 pt-3 pb-3">

                  {/* Row 1: severity badge + flags + actions */}
                  <div className="flex items-center gap-1.5 mb-2.5 flex-wrap">
                    {/* Severity badge */}
                    <span className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-[10px] font-semibold border ${sev.bg} ${sev.text} ${sev.border}`}>
                      <span className={`w-1.5 h-1.5 rounded-full ${sev.dot}`} />
                      {sev.label}
                    </span>
                    {/* Source tag */}
                    {q.source === 'cloudwatch' && (
                      <Tip text="This query was captured from AWS CloudWatch Logs (slow query log). Timing data comes from the log entry.">
                        <span className="px-1.5 py-0.5 rounded-md text-[10px] bg-orange-50 dark:bg-orange-900/20 text-orange-600 dark:text-orange-400 border border-orange-200/50 dark:border-orange-700/30 font-medium">
                          CW Log
                        </span>
                      </Tip>
                    )}
                    {/* No-index flag */}
                    {q.noIndexUsed && (
                      <Tip text="Full table scan — no suitable index was used. This can cause severe slowness on large tables. Consider adding an index on the filtered/joined columns." wide>
                        <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded-md text-[10px] bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400 border border-red-200/50 dark:border-red-700/30 font-medium">
                          <i className="fas fa-triangle-exclamation text-[8px]" /> No Index
                        </span>
                      </Tip>
                    )}
                    <span className="flex-1" />
                    {/* Copy SQL */}
                    <button
                      onClick={() => handleCopy(q, gi)}
                      className={`inline-flex items-center gap-1 px-2 py-1 rounded-lg text-[10px] font-medium border transition-all duration-200 ${
                        copied === gi
                          ? 'bg-emerald-100 dark:bg-emerald-900/30 text-emerald-700 dark:text-emerald-400 border-emerald-300/60 dark:border-emerald-700/40'
                          : 'bg-gray-100/80 dark:bg-gray-700/50 text-gray-600 dark:text-gray-300 border-gray-200/60 dark:border-gray-600/40 hover:bg-gray-200 dark:hover:bg-gray-700'
                      }`}
                    >
                      <i className={`fas fa-${copied === gi ? 'check' : 'copy'} text-[9px]`} />
                      {copied === gi ? 'Copied!' : 'Copy SQL'}
                    </button>
                    {/* Explain */}
                    <button
                      onClick={() => setExplainQuery(q.query)}
                      className="inline-flex items-center gap-1 px-2 py-1 rounded-lg text-[10px] font-medium border bg-indigo-50/80 dark:bg-indigo-900/20 text-indigo-600 dark:text-indigo-400 border-indigo-200/60 dark:border-indigo-700/40 hover:bg-indigo-100 dark:hover:bg-indigo-900/30 transition-colors"
                    >
                      <i className="fas fa-terminal text-[9px]" /> Explain
                    </button>
                  </div>

                  {/* SQL block with syntax highlighting */}
                  <div className="bg-slate-50 dark:bg-slate-900/60 rounded-lg px-3 py-2.5 mb-2.5 border border-slate-200/50 dark:border-slate-700/30">
                    <SqlHighlight sql={q.query} truncate={isExpanded ? undefined : TRUNC} />
                    {isTruncated && (
                      <button
                        onClick={() => setExpanded(s => {
                          const n = new Set(s);
                          n.has(gi) ? n.delete(gi) : n.add(gi);
                          return n;
                        })}
                        className="mt-1.5 flex items-center gap-1 text-[10px] text-indigo-500 dark:text-indigo-400 hover:text-indigo-600 dark:hover:text-indigo-300 font-medium transition-colors"
                      >
                        <i className={`fas fa-chevron-${isExpanded ? 'up' : 'down'} text-[8px]`} />
                        {isExpanded ? 'Collapse' : 'Show full query'}
                      </button>
                    )}
                  </div>

                  {/* Stats grid: Avg | Max | Calls | Lock | Examined | Rows Sent */}
                  <div className="grid grid-cols-6 gap-1.5">
                    {[
                      { label: 'Avg',      tip: 'Average execution time across all recorded calls of this query pattern.',                                                                    val: `${Math.round(q.avgMs)}ms`,           color: q.avgMs >= 500 ? 'text-red-600 dark:text-red-400' : q.avgMs >= 100 ? 'text-amber-600 dark:text-amber-400' : 'text-emerald-600 dark:text-emerald-400' },
                      { label: 'Max',      tip: 'Slowest single execution of this query pattern — worst-case latency seen.',                                                                  val: `${Math.round(q.maxMs)}ms`,           color: q.maxMs >= 1000 ? 'text-red-600 dark:text-red-400' : q.maxMs >= 500 ? 'text-orange-600 dark:text-orange-400' : 'text-gray-700 dark:text-gray-300' },
                      { label: 'Calls',    tip: 'Total number of times this query pattern was executed.',                                                                                     val: q.calls != null ? q.calls.toLocaleString() : '—', color: 'text-gray-800 dark:text-gray-200' },
                      { label: 'Lock',     tip: 'Time spent waiting to acquire a database lock. High lock wait often means contention with concurrent writes or long transactions.',           val: q.lockMs > 0 ? `${Math.round(q.lockMs)}ms` : '—', color: q.lockMs > 100 ? 'text-red-500 dark:text-red-400' : q.lockMs > 10 ? 'text-orange-500 dark:text-orange-400' : 'text-gray-500 dark:text-gray-400' },
                      { label: 'Examined', tip: 'Rows scanned by the DB engine to produce the result. A much higher value than Rows Sent indicates an inefficient query or missing index.',   val: q.rowsExamined.toLocaleString(),       color: 'text-gray-600 dark:text-gray-400' },
                      { label: 'Rows Sent',tip: 'Rows actually returned to the client. Compare with Examined — a large gap means the DB is doing unnecessary work.',                          val: q.rowsSent.toLocaleString(),           color: 'text-gray-800 dark:text-gray-200' },
                    ].map(stat => (
                      <div key={stat.label} className="bg-gray-50/80 dark:bg-gray-700/30 rounded-lg py-1.5 px-1 text-center">
                        <Tip text={stat.tip} wide>
                          <div className="text-[9px] text-gray-400 mb-0.5 leading-none">{stat.label}</div>
                        </Tip>
                        <div className={`text-[11px] font-semibold leading-tight ${stat.color}`}>{stat.val}</div>
                      </div>
                    ))}
                  </div>

                  {/* Cache hit progress bar (Postgres pg_stat_statements) */}
                  {q.cacheHitPct !== undefined && (
                    <div className="mt-2 flex items-center gap-2 text-[10px]">
                      <Tip text="% of block reads served from the shared buffer cache (memory) vs. disk. Below 90% means frequent disk I/O — consider increasing shared_buffers or adding indexes." wide>
                        <span className="text-gray-400 shrink-0">Cache hit:</span>
                      </Tip>
                      <span className={`font-semibold shrink-0 ${q.cacheHitPct >= 90 ? 'text-emerald-600 dark:text-emerald-400' : q.cacheHitPct >= 50 ? 'text-amber-600 dark:text-amber-400' : 'text-red-500 dark:text-red-400'}`}>
                        {q.cacheHitPct}%
                      </span>
                      <div className="flex-1 h-1.5 bg-gray-200 dark:bg-gray-700 rounded-full overflow-hidden">
                        <div
                          className={`h-full rounded-full transition-all duration-500 ${q.cacheHitPct >= 90 ? 'bg-emerald-500' : q.cacheHitPct >= 50 ? 'bg-amber-400' : 'bg-red-500'}`}
                          style={{ width: `${q.cacheHitPct}%` }}
                        />
                      </div>
                    </div>
                  )}

                  {/* Timestamp (CloudWatch) */}
                  {q.timestamp && (
                    <div className="mt-2 text-[10px] text-gray-400 flex items-center gap-1">
                      <i className="fas fa-clock text-[8px]" />
                      {new Date(q.timestamp).toLocaleString()}
                    </div>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* ── Pagination ── */}
      {totalPages > 1 && (
        <div className="flex items-center justify-between pt-1">
          <span className="text-[11px] text-gray-400">
            {safePage * PAGE_SIZE + 1}–{Math.min((safePage + 1) * PAGE_SIZE, filtered.length)} of {filtered.length}
          </span>
          <div className="flex items-center gap-1">
            <button
              onClick={() => setPage(p => Math.max(0, p - 1))} disabled={safePage === 0}
              className="w-7 h-7 flex items-center justify-center rounded-lg border border-gray-200/80 dark:border-gray-700/60 text-gray-500 hover:bg-gray-100 dark:hover:bg-gray-700/50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
            >
              <i className="fas fa-chevron-left text-[10px]" />
            </button>
            {Array.from({ length: Math.min(totalPages, 5) }, (_, i) => {
              const pg = totalPages <= 5 ? i
                : safePage < 3 ? i
                : safePage > totalPages - 3 ? totalPages - 5 + i
                : safePage - 2 + i;
              return (
                <button key={pg} onClick={() => setPage(pg)}
                  className={`w-7 h-7 flex items-center justify-center rounded-lg text-[11px] font-medium border transition-colors ${
                    pg === safePage ? 'bg-indigo-600 text-white border-indigo-600' : 'border-gray-200/80 dark:border-gray-700/60 text-gray-600 dark:text-gray-400 hover:bg-gray-100 dark:hover:bg-gray-700/50'
                  }`}
                >
                  {pg + 1}
                </button>
              );
            })}
            <button
              onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))} disabled={safePage === totalPages - 1}
              className="w-7 h-7 flex items-center justify-center rounded-lg border border-gray-200/80 dark:border-gray-700/60 text-gray-500 hover:bg-gray-100 dark:hover:bg-gray-700/50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
            >
              <i className="fas fa-chevron-right text-[10px]" />
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

// ── Query Stats Tab ───────────────────────────────────────────────────────────
function QueryStatsTab({ stats, engine, instanceId, endpoint }: {
  stats: AgentQueryStat[];
  engine: string;
  instanceId: string;
  endpoint?: string;
}) {
  const [search,       setSearch]       = useState('');
  const [cacheFilter,  setCacheFilter]  = useState<'all'|'imperfect'|'low'>('all');
  const [sortKey,      setSortKey]      = useState<'calls'|'avg'|'max'|'total'|'lock'|'rows'|'cache'>('total');
  const [sortDir,      setSortDir]      = useState<'desc'|'asc'>('desc');
  const [page,         setPage]         = useState(0);
  const [expanded,     setExpanded]     = useState<Set<number>>(new Set());
  const [dismissInfo,  setDismissInfo]  = useState(false);

  const PAGE_SIZE  = 20;
  const isPostgres = engine.toLowerCase().includes('postgres');
  const collectedAt = stats[0]?.collectedAt;

  // ── Aggregate KPIs (over all stats, unaffected by filters) ──
  const totalCalls      = stats.reduce((s, q) => s + q.callCount, 0);
  const overallAvgMs    = stats.length > 0 ? stats.reduce((s, q) => s + q.avgTimeSeconds * 1000, 0) / stats.length : 0;
  const overallMaxMs    = stats.length > 0 ? Math.max(...stats.map(q => q.maxTimeSeconds * 1000)) : 0;
  const noIndexCount    = stats.filter(q => q.noIndexUsed).length;
  const totalTimeS      = stats.reduce((s, q) => s + q.totalTimeSeconds, 0);

  // ── Filter + sort ──
  let filtered = stats;
  if (search) filtered = filtered.filter(s => s.queryDigest?.toLowerCase().includes(search.toLowerCase()));
  if (cacheFilter !== 'all') filtered = filtered.filter(s => {
    const hits = s.sharedBlksHit + s.sharedBlksRead;
    if (hits === 0) return false;
    const ratio = s.sharedBlksHit / hits;
    return cacheFilter === 'imperfect' ? ratio < 1 : ratio < 0.9;
  });
  const dir = sortDir === 'desc' ? -1 : 1;
  filtered = [...filtered].sort((a, b) => {
    switch (sortKey) {
      case 'calls': return dir * (b.callCount        - a.callCount);
      case 'avg':   return dir * (b.avgTimeSeconds   - a.avgTimeSeconds);
      case 'max':   return dir * (b.maxTimeSeconds   - a.maxTimeSeconds);
      case 'total': return dir * (b.totalTimeSeconds - a.totalTimeSeconds);
      case 'lock':  return dir * (b.lockTimeSeconds  - a.lockTimeSeconds);
      case 'rows':  return dir * (b.rowsSent         - a.rowsSent);
      case 'cache': {
        const ra = (a.sharedBlksHit + a.sharedBlksRead) > 0 ? a.sharedBlksHit / (a.sharedBlksHit + a.sharedBlksRead) : 1;
        const rb = (b.sharedBlksHit + b.sharedBlksRead) > 0 ? b.sharedBlksHit / (b.sharedBlksHit + b.sharedBlksRead) : 1;
        return dir * (rb - ra);
      }
      default: return 0;
    }
  });

  const totalPages = Math.ceil(filtered.length / PAGE_SIZE);
  const safePage   = Math.min(page, Math.max(0, totalPages - 1));
  const pageItems  = filtered.slice(safePage * PAGE_SIZE, (safePage + 1) * PAGE_SIZE);

  useEffect(() => { setPage(0); }, [search, cacheFilter, sortKey, sortDir]);

  const handleSort = (k: typeof sortKey) => {
    if (k === sortKey) setSortDir(d => d === 'desc' ? 'asc' : 'desc');
    else { setSortKey(k); setSortDir('desc'); }
  };

  const handleExportCsv = () => {
    const header = ['Query Digest', 'Calls', 'Avg (ms)', 'Max (ms)', 'Total (s)', 'Lock (ms)', 'Rows Examined', 'Rows Sent', 'Cache Hit %', 'No Index'];
    const rows = filtered.map(s => {
      const hits = s.sharedBlksHit + s.sharedBlksRead;
      const ch   = hits > 0 ? Math.round((s.sharedBlksHit / hits) * 100) : 100;
      return [
        `"${(s.queryDigest ?? '').replace(/"/g, '""')}"`,
        s.callCount, round2(s.avgTimeSeconds * 1000), round2(s.maxTimeSeconds * 1000),
        round2(s.totalTimeSeconds), round2(s.lockTimeSeconds * 1000),
        s.rowsExamined, s.rowsSent, ch, s.noIndexUsed ? 'YES' : 'NO',
      ].join(',');
    });
    const blob = new Blob([[header.join(','), ...rows].join('\n')], { type: 'text/csv' });
    const url  = URL.createObjectURL(blob);
    const a    = document.createElement('a');
    a.href = url; a.download = `query_stats_${Date.now()}.csv`; a.click();
    URL.revokeObjectURL(url);
  };

  // ── No-data: show agent setup wizard ──
  if (stats.length === 0) {
    return <AgentSetupWizard engine={engine} instanceId={instanceId} endpoint={endpoint} />;
  }

  // Sortable column header (inline render function, not a nested React component)
  const th = (col: typeof sortKey, label: string, tip?: string) => (
    <th
      key={col}
      onClick={() => handleSort(col)}
      className="px-3 py-2.5 text-right text-[11px] font-semibold text-gray-500 dark:text-gray-400 cursor-pointer select-none whitespace-nowrap hover:text-indigo-600 dark:hover:text-indigo-400 transition-colors"
    >
      <span className="inline-flex items-center justify-end gap-1">
        {tip ? <Tip text={tip} wide>{label}</Tip> : label}
        {sortKey === col
          ? <i className={`fas fa-arrow-${sortDir === 'desc' ? 'down' : 'up'} text-indigo-500 text-[9px]`} />
          : <i className="fas fa-sort text-gray-300 dark:text-gray-600 text-[9px]" />}
      </span>
    </th>
  );

  return (
    <div className="space-y-4">

      {/* ── Summary KPI strip ── */}
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        {[
          { icon: 'fa-layer-group',          label: 'Total Calls',   val: totalCalls.toLocaleString(),    sub: `${stats.length} digests`,          color: 'text-indigo-600 dark:text-indigo-400',  tip: 'Sum of all query executions across every pattern since stats were last reset.' },
          { icon: 'fa-gauge-high',            label: 'Avg Latency',   val: `${round2(overallAvgMs)}ms`,    sub: 'mean across all queries',          color: overallAvgMs >= 500 ? 'text-red-600 dark:text-red-400' : overallAvgMs >= 100 ? 'text-amber-600 dark:text-amber-400' : 'text-emerald-600 dark:text-emerald-400', tip: 'Weighted average execution time across all query patterns. Red ≥ 500 ms, amber ≥ 100 ms.' },
          { icon: 'fa-arrow-trend-up',        label: 'Peak Latency',  val: `${round2(overallMaxMs)}ms`,    sub: 'single-query maximum',             color: overallMaxMs >= 1000 ? 'text-red-600 dark:text-red-400' : overallMaxMs >= 500 ? 'text-orange-600 dark:text-orange-400' : 'text-gray-800 dark:text-gray-200', tip: 'Slowest single query execution recorded across all patterns — the absolute worst-case latency.' },
          { icon: 'fa-triangle-exclamation',  label: 'No Index',      val: noIndexCount.toString(),        sub: `${round2(totalTimeS)}s total exec`, color: noIndexCount > 0 ? 'text-red-600 dark:text-red-400' : 'text-emerald-600 dark:text-emerald-400', tip: 'Number of query patterns running full table scans with no index. The sub-value shows total CPU time wasted on these scans.' },
        ].map(k => (
          <div key={k.label} className="bg-white/60 dark:bg-gray-800/50 backdrop-blur-sm rounded-xl p-3 border border-gray-200/60 dark:border-gray-700/40 shadow-sm">
            <div className="flex items-center gap-1.5 mb-1">
              <i className={`fas ${k.icon} text-[10px] text-gray-400`} />
              <Tip text={k.tip} wide>
                <span className="text-[10px] text-gray-400 font-medium truncate">{k.label}</span>
              </Tip>
            </div>
            <div className={`text-sm font-bold leading-tight ${k.color}`}>{k.val}</div>
            <div className="text-[9px] text-gray-400 mt-0.5 leading-none truncate">{k.sub}</div>
          </div>
        ))}
      </div>

      {/* ── Info callout with last-collected pill ── */}
      {!dismissInfo && (
        <div className="flex items-start gap-3 px-3.5 py-3 rounded-xl bg-sky-50/80 dark:bg-sky-950/30 border border-sky-200/60 dark:border-sky-800/30">
          <div className="shrink-0 w-7 h-7 rounded-lg bg-sky-100 dark:bg-sky-900/40 flex items-center justify-center mt-0.5">
            <i className="fas fa-circle-info text-sky-500 text-[11px]" />
          </div>
          <div className="flex-1 min-w-0">
            <p className="text-[11px] text-sky-700 dark:text-sky-300 leading-relaxed">
              <strong>{filtered.length}</strong> query digests ranked by total exec time from{' '}
              <strong>{isPostgres ? 'pg_stat_statements' : 'performance_schema'}</strong>.{' '}
              Counters accumulate since last restart. Literals are normalized out.
            </p>
          </div>
          {collectedAt && (
            <span className="shrink-0 flex items-center gap-1.5 px-2 py-1 rounded-lg bg-sky-100 dark:bg-sky-900/40 text-sky-600 dark:text-sky-400 text-[10px] font-medium border border-sky-200/60 dark:border-sky-700/40 whitespace-nowrap">
              <i className="fas fa-clock text-[9px]" />
              {new Date(collectedAt).toLocaleString()}
            </span>
          )}
          <button onClick={() => setDismissInfo(true)} className="shrink-0 text-sky-400 hover:text-sky-600 dark:hover:text-sky-300 w-5 h-5 flex items-center justify-center mt-0.5">
            <i className="fas fa-times text-xs" />
          </button>
        </div>
      )}

      {/* ── Filter / sort / export row ── */}
      <div className="flex items-center gap-2 flex-wrap">
        {/* Keyword search */}
        <div className="relative flex-1 min-w-[140px]">
          <i className="fas fa-search absolute left-2.5 top-1/2 -translate-y-1/2 text-gray-400 text-[10px] pointer-events-none" />
          <input
            type="text" value={search} onChange={e => setSearch(e.target.value)}
            placeholder="Filter queries…"
            className="w-full pl-7 pr-3 py-1.5 text-xs rounded-lg border border-gray-200/80 dark:border-gray-700/60 bg-white/80 dark:bg-gray-800/60 text-gray-800 dark:text-gray-200 placeholder-gray-400 focus:outline-none focus:ring-1 focus:ring-indigo-500/50"
          />
        </div>

        {/* Cache hit filter (Postgres only) */}
        {isPostgres && (
          <div className="flex items-center gap-0.5 bg-gray-100/80 dark:bg-gray-800/60 rounded-lg p-0.5 border border-gray-200/50 dark:border-gray-700/40">
            {([
              { k: 'all'       as const, l: 'All',           tip: 'Show all query patterns regardless of cache performance.' },
              { k: 'imperfect' as const, l: 'Cache < 100%',  tip: 'Show queries with any cache misses — at least some reads hit disk.' },
              { k: 'low'       as const, l: 'Cache < 90%',   tip: 'Show queries with poor cache hit rate (< 90%). These are frequently reading from disk and are good candidates for index or memory tuning.' },
            ]).map(f => (
              <Tip key={f.k} text={f.tip} wide>
                <button onClick={() => setCacheFilter(f.k)}
                  className={`px-2 py-1 text-[10px] font-medium rounded transition-all duration-150 ${
                    cacheFilter === f.k
                      ? 'bg-white dark:bg-gray-700 text-indigo-600 dark:text-indigo-400 shadow-sm'
                      : 'text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-200'
                  }`}>
                  {f.l}
                </button>
              </Tip>
            ))}
          </div>
        )}

        {/* Result count */}
        <span className="text-[11px] text-gray-400 shrink-0">
          {search || cacheFilter !== 'all' ? `${filtered.length} / ${stats.length}` : `${stats.length}`} queries
        </span>

        {/* Export CSV */}
        <Tip text="Download the filtered query stats as a CSV file — includes query text, calls, avg/max/total latency, lock time, rows, and cache hit %." wide>
          <button
            onClick={handleExportCsv}
            className="flex items-center gap-1.5 px-2.5 py-1.5 text-[11px] font-medium rounded-lg bg-white/80 dark:bg-gray-800/60 border border-gray-200/80 dark:border-gray-700/60 text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-700/50 transition-colors shadow-sm ml-auto"
          >
            <i className="fas fa-download text-[10px]" /> Export CSV
          </button>
        </Tip>
      </div>

      {/* ── Data table ── */}
      <div className="overflow-x-auto rounded-xl border border-gray-200/60 dark:border-gray-700/40 shadow-sm">
        <table className="w-full text-xs min-w-[660px]">
          <thead className="bg-gray-50/90 dark:bg-gray-800/70 border-b border-gray-200/60 dark:border-gray-700/40">
            <tr>
              {/* Query column — not sortable, takes remaining space */}
              <th className="px-3 py-2.5 text-left text-[11px] font-semibold text-gray-500 dark:text-gray-400 min-w-[200px]">
                Query
              </th>
              {th('calls', 'Calls', 'Total executions of this query pattern since last stats reset.')}
              {th('avg',   'Avg',   'Mean execution time per call. Red ≥ 500 ms, amber ≥ 100 ms.')}
              {th('max',   'Max',   'Worst single execution time — useful for spotting outliers.')}
              {th('total', 'Total', 'Cumulative CPU + I/O time across all calls. Highest-impact queries top this list.')}
              {th('lock',  'Lock',  'Cumulative time spent waiting for database locks. Spikes here indicate write contention or long-running transactions.')}
              {th('rows',  'Rows',  'Average rows returned to the client per execution.')}
              {isPostgres && th('cache', 'Cache', '% of block reads served from shared buffer cache (memory). Below 90% means the query is hitting disk frequently.')}
            </tr>
          </thead>

          <tbody className="divide-y divide-gray-100/70 dark:divide-gray-700/30">
            {pageItems.length === 0 ? (
              <tr>
                <td colSpan={isPostgres ? 8 : 7} className="px-3 py-10 text-center text-xs text-gray-400">
                  No queries match your filters.
                </td>
              </tr>
            ) : pageItems.map((s, i) => {
              const gi      = safePage * PAGE_SIZE + i;
              const isExp   = expanded.has(gi);
              const TRUNC   = 72;
              const hits    = s.sharedBlksHit + s.sharedBlksRead;
              const cacheHit = hits > 0 ? Math.round((s.sharedBlksHit / hits) * 100) : null;
              const avgMs   = round2(s.avgTimeSeconds  * 1000);
              const maxMs2  = round2(s.maxTimeSeconds  * 1000);
              const lockMs  = round2(s.lockTimeSeconds * 1000);
              const colSpan = isPostgres ? 8 : 7;

              return (
                <>
                  <tr
                    key={gi}
                    className="hover:bg-indigo-50/40 dark:hover:bg-indigo-900/10 transition-colors"
                  >
                    {/* Query cell */}
                    <td className="px-3 py-2 max-w-[260px]">
                      <div className="flex items-center gap-1.5">
                        <button
                          onClick={() => setExpanded(prev => {
                            const n = new Set(prev);
                            n.has(gi) ? n.delete(gi) : n.add(gi);
                            return n;
                          })}
                          className="shrink-0 w-4 h-4 flex items-center justify-center rounded text-gray-400 hover:text-indigo-500 transition-colors"
                        >
                          <i className={`fas fa-chevron-${isExp ? 'down' : 'right'} text-[8px]`} />
                        </button>
                        <div className="min-w-0 flex-1">
                          <span className="font-mono text-[11px] text-slate-600 dark:text-slate-300">
                            {(s.queryDigest ?? '').slice(0, TRUNC)}
                            {(s.queryDigest?.length ?? 0) > TRUNC ? '…' : ''}
                          </span>
                          {s.noIndexUsed && (
                            <span className="ml-1.5 inline-flex items-center gap-0.5 px-1 py-0.5 rounded text-[9px] bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400 border border-red-200/50 dark:border-red-700/30 font-medium align-middle">
                              <i className="fas fa-triangle-exclamation text-[8px]" /> No Idx
                            </span>
                          )}
                        </div>
                      </div>
                    </td>

                    {/* Calls */}
                    <td className="px-3 py-2 text-right font-medium text-gray-700 dark:text-gray-300 tabular-nums whitespace-nowrap">
                      {s.callCount.toLocaleString()}
                    </td>

                    {/* Avg */}
                    <td className={`px-3 py-2 text-right font-semibold tabular-nums whitespace-nowrap ${avgMs >= 500 ? 'text-red-600 dark:text-red-400' : avgMs >= 100 ? 'text-amber-600 dark:text-amber-400' : 'text-emerald-600 dark:text-emerald-400'}`}>
                      {avgMs}ms
                    </td>

                    {/* Max */}
                    <td className={`px-3 py-2 text-right font-medium tabular-nums whitespace-nowrap ${maxMs2 >= 1000 ? 'text-red-600 dark:text-red-400' : maxMs2 >= 500 ? 'text-orange-600 dark:text-orange-400' : 'text-gray-600 dark:text-gray-400'}`}>
                      {maxMs2}ms
                    </td>

                    {/* Total */}
                    <td className="px-3 py-2 text-right text-gray-600 dark:text-gray-400 tabular-nums whitespace-nowrap">
                      {round2(s.totalTimeSeconds)}s
                    </td>

                    {/* Lock */}
                    <td className={`px-3 py-2 text-right tabular-nums whitespace-nowrap ${lockMs > 100 ? 'text-red-500 dark:text-red-400' : lockMs > 10 ? 'text-orange-500 dark:text-orange-400' : 'text-gray-500 dark:text-gray-400'}`}>
                      {lockMs > 0 ? `${lockMs}ms` : '—'}
                    </td>

                    {/* Rows sent */}
                    <td className="px-3 py-2 text-right text-gray-600 dark:text-gray-400 tabular-nums whitespace-nowrap">
                      {s.rowsSent.toLocaleString()}
                    </td>

                    {/* Cache hit badge (Postgres only) */}
                    {isPostgres && (
                      <td className="px-3 py-2 text-right">
                        {cacheHit !== null ? (
                          <span className={`inline-flex items-center gap-1 px-1.5 py-0.5 rounded-md text-[10px] font-semibold border ${
                            cacheHit >= 99
                              ? 'bg-emerald-50 dark:bg-emerald-900/20 text-emerald-700 dark:text-emerald-400 border-emerald-200/60 dark:border-emerald-700/40'
                              : cacheHit >= 80
                              ? 'bg-amber-50 dark:bg-amber-900/20 text-amber-700 dark:text-amber-400 border-amber-200/60 dark:border-amber-700/40'
                              : 'bg-red-50 dark:bg-red-900/20 text-red-700 dark:text-red-400 border-red-200/60 dark:border-red-700/40'
                          }`}>
                            <i className={`fas fa-${cacheHit >= 99 ? 'check' : 'exclamation'} text-[8px]`} />
                            {cacheHit}%
                          </span>
                        ) : <span className="text-gray-400 text-[10px]">—</span>}
                      </td>
                    )}
                  </tr>

                  {/* Expandable detail row: full highlighted SQL */}
                  {isExp && (
                    <tr key={`${gi}_exp`} className="bg-slate-50/60 dark:bg-slate-900/40">
                      <td colSpan={colSpan} className="px-5 py-3 border-b border-slate-200/60 dark:border-slate-700/30">
                        <p className="text-[10px] text-gray-400 uppercase tracking-wider font-semibold mb-1.5">Full Query</p>
                        <div className="bg-white dark:bg-slate-900 rounded-lg px-3 py-2.5 border border-slate-200/60 dark:border-slate-700/30">
                          <SqlHighlight sql={s.queryDigest ?? ''} />
                        </div>
                        <div className="flex flex-wrap gap-4 mt-2 text-[10px] text-gray-400">
                          <span>Rows examined: <strong className="text-gray-600 dark:text-gray-300">{s.rowsExamined.toLocaleString()}</strong></span>
                          <span>Rows sent: <strong className="text-gray-600 dark:text-gray-300">{s.rowsSent.toLocaleString()}</strong></span>
                          {s.noIndexUsed && (
                            <span className="text-red-500 font-medium flex items-center gap-1">
                              <i className="fas fa-triangle-exclamation text-[9px]" /> Full table scan — no index used
                            </span>
                          )}
                        </div>
                      </td>
                    </tr>
                  )}
                </>
              );
            })}
          </tbody>
        </table>
      </div>

      {/* ── Pagination ── */}
      {totalPages > 1 && (
        <div className="flex items-center justify-between pt-1">
          <span className="text-[11px] text-gray-400">
            {safePage * PAGE_SIZE + 1}–{Math.min((safePage + 1) * PAGE_SIZE, filtered.length)} of {filtered.length}
          </span>
          <div className="flex items-center gap-1">
            <button
              onClick={() => setPage(p => Math.max(0, p - 1))} disabled={safePage === 0}
              className="w-7 h-7 flex items-center justify-center rounded-lg border border-gray-200/80 dark:border-gray-700/60 text-gray-500 hover:bg-gray-100 dark:hover:bg-gray-700/50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
            >
              <i className="fas fa-chevron-left text-[10px]" />
            </button>
            {Array.from({ length: Math.min(totalPages, 7) }, (_, i) => {
              const pg = totalPages <= 7 ? i
                : safePage < 4 ? i
                : safePage > totalPages - 4 ? totalPages - 7 + i
                : safePage - 3 + i;
              return (
                <button key={pg} onClick={() => setPage(pg)}
                  className={`w-7 h-7 flex items-center justify-center rounded-lg text-[11px] font-medium border transition-colors ${
                    pg === safePage ? 'bg-indigo-600 text-white border-indigo-600' : 'border-gray-200/80 dark:border-gray-700/60 text-gray-600 dark:text-gray-400 hover:bg-gray-100 dark:hover:bg-gray-700/50'
                  }`}
                >
                  {pg + 1}
                </button>
              );
            })}
            <button
              onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))} disabled={safePage === totalPages - 1}
              className="w-7 h-7 flex items-center justify-center rounded-lg border border-gray-200/80 dark:border-gray-700/60 text-gray-500 hover:bg-gray-100 dark:hover:bg-gray-700/50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
            >
              <i className="fas fa-chevron-right text-[10px]" />
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

// ── Maintenance Tab ───────────────────────────────────────────────────────────
function MaintenanceTab({ vacuumStats, indexUsage, tableActivity, agentHost, rdsEndpoint }: {
  vacuumStats:   AgentVacuumStat[];
  indexUsage:    AgentIndexUsage[];
  tableActivity: AgentTableActivity[];
  agentHost:     string | undefined;
  rdsEndpoint?:  string;
}) {
  const [sevFilter,    setSevFilter]    = useState<'all'|'critical'|'warning'|'healthy'>('all');
  const [sortKey,      setSortKey]      = useState<'bloat'|'live'|'dead'|'vacuum'|'analyze'>('bloat');
  const [sortDir,      setSortDir]      = useState<'desc'|'asc'>('desc');
  const [copied,       setCopied]       = useState<number|null>(null);
  const [infoOpen,     setInfoOpen]     = useState(false);
  const [vacPage,      setVacPage]      = useState(0);
  const [idxPage,      setIdxPage]      = useState(0);
  const [actPage,      setActPage]      = useState(0);

  const matchHost = (dbHost: string | undefined) => {
    if (!dbHost) return false;
    if (dbHost === agentHost) return true;
    const h = dbHost.split(':')[0];
    return !!(h && rdsEndpoint?.includes(h));
  };

  // Filter to current instance
  const stats = vacuumStats.filter(v => matchHost(v.dbHost));

  // Severity thresholds: critical >50%, warning 20–50%, healthy <20%
  const sev = (ratio: number) => ratio > 0.50 ? 'critical' : ratio >= 0.20 ? 'warning' : 'healthy';

  const critCount    = stats.filter(v => sev(v.deadTupRatio ?? 0) === 'critical').length;
  const warnCount    = stats.filter(v => sev(v.deadTupRatio ?? 0) === 'warning').length;
  const healthyCount = stats.filter(v => sev(v.deadTupRatio ?? 0) === 'healthy').length;

  // Filter + sort
  let filtered = sevFilter === 'all' ? stats : stats.filter(v => sev(v.deadTupRatio ?? 0) === sevFilter);
  const d = sortDir === 'desc' ? -1 : 1;
  const ds = (s?: string) => s ? new Date(s).getTime() : 0;
  filtered = [...filtered].sort((a, b) => {
    switch (sortKey) {
      case 'bloat':   return d * ((b.deadTupRatio ?? 0) - (a.deadTupRatio ?? 0));
      case 'live':    return d * ((b.nLiveTup ?? 0) - (a.nLiveTup ?? 0));
      case 'dead':    return d * ((b.nDeadTup ?? 0) - (a.nDeadTup ?? 0));
      case 'vacuum':  return d * (ds(b.lastAutoVacuum || b.lastVacuum) - ds(a.lastAutoVacuum || a.lastVacuum));
      case 'analyze': return d * (ds(b.lastAutoAnalyze || b.lastAnalyze) - ds(a.lastAutoAnalyze || a.lastAnalyze));
      default: return 0;
    }
  });

  const handleSort = (k: typeof sortKey) => {
    if (k === sortKey) setSortDir(p => p === 'desc' ? 'asc' : 'desc');
    else { setSortKey(k); setSortDir('desc'); }
  };

  const handleCopyVacuum = (v: AgentVacuumStat, idx: number) => {
    navigator.clipboard.writeText(`VACUUM ANALYZE "${v.schemaName}"."${v.tableName}";`)
      .then(() => { setCopied(idx); setTimeout(() => setCopied(null), 2000); });
  };

  // Relative date helper  (e.g. "3d ago", "Today")
  const rel = (s?: string) => {
    if (!s) return '—';
    const days = Math.floor((Date.now() - new Date(s).getTime()) / 86400000);
    if (days === 0) return 'Today';
    if (days === 1) return '1d ago';
    if (days < 60)  return `${days}d ago`;
    return new Date(s).toLocaleDateString();
  };

  // Sortable th render function
  const th = (col: typeof sortKey, label: string, tip?: string) => (
    <th key={col} onClick={() => handleSort(col)}
      className="px-3 py-2.5 text-right text-[11px] font-semibold text-gray-500 dark:text-gray-400 cursor-pointer select-none whitespace-nowrap hover:text-indigo-600 dark:hover:text-indigo-400 transition-colors"
    >
      <span className="inline-flex items-center justify-end gap-1">
        {tip ? <Tip text={tip} wide>{label}</Tip> : label}
        {sortKey === col
          ? <i className={`fas fa-arrow-${sortDir === 'desc' ? 'down' : 'up'} text-indigo-500 text-[9px]`} />
          : <i className="fas fa-sort text-gray-300 dark:text-gray-600 text-[9px]" />}
      </span>
    </th>
  );

  // ── Pagination constants + helpers ───────────────────────────────────────────
  const VAC_PAGE_SIZE = 15;
  const IDX_PAGE_SIZE = 20;
  const ACT_PAGE_SIZE = 15;

  const vacTotalPages = Math.max(1, Math.ceil(filtered.length / VAC_PAGE_SIZE));
  const vacPg         = Math.min(vacPage, vacTotalPages - 1);
  const vacRows       = filtered.slice(vacPg * VAC_PAGE_SIZE, (vacPg + 1) * VAC_PAGE_SIZE);

  const filteredIdx   = indexUsage.filter(x => matchHost(x.dbHost));
  const idxTotalPages = Math.max(1, Math.ceil(filteredIdx.length / IDX_PAGE_SIZE));
  const idxPg         = Math.min(idxPage, idxTotalPages - 1);
  const idxRows       = filteredIdx.slice(idxPg * IDX_PAGE_SIZE, (idxPg + 1) * IDX_PAGE_SIZE);

  const filteredAct   = tableActivity.filter(t => matchHost(t.dbHost));
  const actTotalPages = Math.max(1, Math.ceil(filteredAct.length / ACT_PAGE_SIZE));
  const actPg         = Math.min(actPage, actTotalPages - 1);
  const actRows       = filteredAct.slice(actPg * ACT_PAGE_SIZE, (actPg + 1) * ACT_PAGE_SIZE);

  const paginator = (pg: number, total: number, pageSize: number, onPage: (p: number) => void) => {
    const pages = Math.max(1, Math.ceil(total / pageSize));
    if (pages <= 1) return null;
    const start = pg * pageSize + 1;
    const end   = Math.min((pg + 1) * pageSize, total);
    return (
      <div className="flex items-center justify-between pt-2.5 px-1 border-t border-gray-100/60 dark:border-gray-700/30 mt-1">
        <span className="text-[10px] text-gray-400 tabular-nums">{start}–{end} of {total}</span>
        <div className="flex items-center gap-1">
          <button
            onClick={() => onPage(Math.max(0, pg - 1))}
            disabled={pg === 0}
            className="px-2 py-1 rounded text-[10px] font-medium text-gray-500 dark:text-gray-400 hover:bg-gray-100 dark:hover:bg-gray-700/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors inline-flex items-center gap-1 border border-gray-200/50 dark:border-gray-700/40"
          >
            <i className="fas fa-chevron-left text-[8px]" /> Prev
          </button>
          <span className="text-[10px] text-gray-500 dark:text-gray-400 px-1.5 tabular-nums">{pg + 1} / {pages}</span>
          <button
            onClick={() => onPage(Math.min(pages - 1, pg + 1))}
            disabled={pg >= pages - 1}
            className="px-2 py-1 rounded text-[10px] font-medium text-gray-500 dark:text-gray-400 hover:bg-gray-100 dark:hover:bg-gray-700/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors inline-flex items-center gap-1 border border-gray-200/50 dark:border-gray-700/40"
          >
            Next <i className="fas fa-chevron-right text-[8px]" />
          </button>
        </div>
      </div>
    );
  };

  // ── Empty state ──────────────────────────────────────────────────────────────
  if (stats.length === 0) {
    return (
      <div className="space-y-4">
        {/* Info banner even when empty */}
        <div className="flex items-start gap-3 px-3.5 py-3 rounded-xl bg-sky-50/80 dark:bg-sky-950/30 border border-sky-200/60 dark:border-sky-800/30">
          <div className="shrink-0 w-7 h-7 rounded-lg bg-sky-100 dark:bg-sky-900/40 flex items-center justify-center mt-0.5">
            <i className="fas fa-circle-info text-sky-500 text-[11px]" />
          </div>
          <p className="text-[11px] text-sky-700 dark:text-sky-300 leading-relaxed flex-1">
            PostgreSQL only. Sourced from <strong>pg_stat_user_tables</strong>. Dead tuple ratio above 20% means autovacuum may not be keeping up.
          </p>
        </div>
        <div className="flex flex-col items-center justify-center py-12 gap-3">
          <svg width="72" height="64" viewBox="0 0 72 64" fill="none" className="text-gray-300 dark:text-gray-600">
            <rect x="8" y="8" width="56" height="48" rx="4" stroke="currentColor" strokeWidth="1.5" strokeDasharray="4 3" />
            <path d="M20 32 Q36 20 52 32" stroke="currentColor" strokeWidth="1.5" strokeDasharray="4 3" fill="none" />
            <circle cx="36" cy="36" r="8" stroke="currentColor" strokeWidth="1.5" fill="none" strokeDasharray="3 2" />
            <line x1="33" y1="33" x2="39" y2="39" stroke="currentColor" strokeWidth="1.2" strokeOpacity="0.5" />
            <line x1="39" y1="33" x2="33" y2="39" stroke="currentColor" strokeWidth="1.2" strokeOpacity="0.5" />
          </svg>
          <p className="text-sm font-medium text-gray-400 dark:text-gray-500">No vacuum data yet</p>
          <p className="text-[11px] text-gray-400 dark:text-gray-500 text-center max-w-xs leading-relaxed">
            Deploy or upgrade the XamOps DB agent — vacuum stats require agent v1.1+.
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-5">

      {/* ── Health summary chips + collapsible info ── */}
      <div className="flex items-start gap-2 flex-wrap">
        {/* Clickable severity filter chips */}
        {([
          { key: 'critical' as const, count: critCount,    dot: 'bg-red-500',     on: 'bg-red-600 text-white border-red-600',      off: 'bg-red-50 dark:bg-red-900/20 text-red-700 dark:text-red-400 border-red-200/70 dark:border-red-700/40 hover:bg-red-100 dark:hover:bg-red-900/30',    icon: 'fa-circle-dot',          tip: 'Dead tuple ratio > 50% — table is severely bloated. Autovacuum cannot keep up. Run VACUUM ANALYZE immediately.' },
          { key: 'warning'  as const, count: warnCount,    dot: 'bg-amber-400',   on: 'bg-amber-500 text-white border-amber-500',  off: 'bg-amber-50 dark:bg-amber-900/20 text-amber-700 dark:text-amber-400 border-amber-200/70 dark:border-amber-700/40 hover:bg-amber-100 dark:hover:bg-amber-900/30', icon: 'fa-triangle-exclamation', tip: 'Dead tuple ratio 20–50% — vacuum is falling behind. Consider tuning autovacuum_vacuum_cost_limit or running manual VACUUM.' },
          { key: 'healthy'  as const, count: healthyCount, dot: 'bg-emerald-400',  on: 'bg-emerald-600 text-white border-emerald-600', off: 'bg-emerald-50 dark:bg-emerald-900/20 text-emerald-700 dark:text-emerald-400 border-emerald-200/70 dark:border-emerald-700/40 hover:bg-emerald-100 dark:hover:bg-emerald-900/30', icon: 'fa-circle-check',         tip: 'Dead tuple ratio < 20% — table bloat is under control. Autovacuum is keeping up.' },
        ].filter(b => b.count > 0).map(b => (
          <Tip key={b.key} text={b.tip} wide>
            <button
              onClick={() => setSevFilter(s => s === b.key ? 'all' : b.key)}
              className={`flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[11px] font-medium border transition-all duration-200 ${sevFilter === b.key ? b.on : b.off}`}
            >
              <i className={`fas ${b.icon} text-[9px] ${sevFilter === b.key ? '' : ''}`} />
              {b.count} {b.key.charAt(0).toUpperCase() + b.key.slice(1)}
            </button>
          </Tip>
        )))}
        {sevFilter !== 'all' && (
          <button onClick={() => setSevFilter('all')} className="text-[10px] text-gray-400 hover:text-gray-600 dark:hover:text-gray-200 underline self-center">
            Clear
          </button>
        )}
        {/* Collapsible info toggle */}
        <button
          onClick={() => setInfoOpen(o => !o)}
          className="ml-auto flex items-center gap-1 px-2 py-1 rounded-lg text-[10px] text-gray-500 dark:text-gray-400 hover:bg-gray-100 dark:hover:bg-gray-700/50 border border-gray-200/60 dark:border-gray-700/40 transition-colors"
        >
          <i className="fas fa-circle-info text-[9px]" />
          {infoOpen ? 'Hide info' : 'About vacuum stats'}
          <i className={`fas fa-chevron-${infoOpen ? 'up' : 'down'} text-[8px] ml-0.5`} />
        </button>
      </div>

      {/* Collapsible info panel */}
      {infoOpen && (
        <div className="flex items-start gap-3 px-3.5 py-3 rounded-xl bg-sky-50/80 dark:bg-sky-950/30 border border-sky-200/60 dark:border-sky-800/30 text-[11px] text-sky-700 dark:text-sky-300 leading-relaxed">
          <i className="fas fa-database text-sky-500 mt-0.5 shrink-0" />
          <div className="space-y-1">
            <p><strong>Source:</strong> <code className="bg-sky-100 dark:bg-sky-900/40 px-1 rounded">pg_stat_user_tables</code> (PostgreSQL only).</p>
            <p><strong>Dead tuple ratio</strong> above 20% means autovacuum may not be keeping up with writes. Run <code className="bg-sky-100 dark:bg-sky-900/40 px-1 rounded">VACUUM ANALYZE</code> manually or tune <code className="bg-sky-100 dark:bg-sky-900/40 px-1 rounded">autovacuum_vacuum_cost_limit</code>.</p>
            <p><strong>Thresholds:</strong> Critical &gt;50% · Warning 20–50% · Healthy &lt;20%.</p>
          </div>
        </div>
      )}

      {/* ── Filter / sort row ── */}
      <div className="flex items-center gap-2 flex-wrap">
        <span className="text-[11px] text-gray-400 mr-auto">
          {filtered.length !== stats.length ? `${filtered.length} / ${stats.length}` : stats.length} tables
        </span>
        <div className="flex items-center gap-0.5 bg-gray-100/80 dark:bg-gray-800/60 rounded-lg p-0.5 border border-gray-200/50 dark:border-gray-700/40">
          <span className="text-[9px] text-gray-400 px-1.5 font-medium shrink-0">Sort:</span>
          {([
            { k: 'bloat'   as const, l: '% Dead'  },
            { k: 'dead'    as const, l: 'Dead Rows' },
            { k: 'live'    as const, l: 'Live Rows' },
            { k: 'vacuum'  as const, l: 'Vacuum'   },
            { k: 'analyze' as const, l: 'Analyze'  },
          ]).map(s => (
            <button key={s.k} onClick={() => handleSort(s.k)}
              className={`px-2 py-1 text-[10px] font-medium rounded transition-all duration-150 flex items-center gap-0.5 ${
                sortKey === s.k
                  ? 'bg-white dark:bg-gray-700 text-indigo-600 dark:text-indigo-400 shadow-sm'
                  : 'text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-200'
              }`}
            >
              {s.l}
              {sortKey === s.k && <i className={`fas fa-arrow-${sortDir === 'desc' ? 'down' : 'up'} text-[8px] ml-0.5`} />}
            </button>
          ))}
        </div>
      </div>

      {/* ── Vacuum stats table ── */}
      <div className="overflow-x-auto rounded-xl border border-gray-200/60 dark:border-gray-700/40 shadow-sm">
        <table className="w-full text-xs min-w-[660px]">
          <thead className="bg-gray-50/90 dark:bg-gray-800/70 border-b border-gray-200/60 dark:border-gray-700/40">
            <tr>
              <th className="px-3 py-2.5 text-left text-[11px] font-semibold text-gray-500 dark:text-gray-400 min-w-[160px]">
                Table
              </th>
              {th('bloat',   '% Dead',       'Percentage of dead (deleted/updated) rows still taking space. Above 20% = warning, above 50% = critical. Run VACUUM ANALYZE to reclaim.')}
              {th('live',    'Live Rows',    'Count of valid, readable rows currently in the table.')}
              {th('dead',    'Dead Rows',    'Rows marked deleted but not yet reclaimed by vacuum. High dead rows inflate table size and slow down sequential scans.')}
              {th('vacuum',  'Last Vacuum',  'When VACUUM last ran on this table. Stale vacuum (>7 days) can lead to table bloat and transaction ID wraparound risk.')}
              {th('analyze', 'Last Analyze', 'When ANALYZE last ran. Stale stats cause the query planner to make poor index/join decisions.')}
              <th className="px-3 py-2.5 text-center text-[11px] font-semibold text-gray-500 dark:text-gray-400 whitespace-nowrap">
                Action
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100/70 dark:divide-gray-700/30">
            {vacRows.length === 0 ? (
              <tr>
                <td colSpan={7} className="px-3 py-10 text-center text-xs text-gray-400">
                  No tables match the current filter.
                </td>
              </tr>
            ) : vacRows.map((v, i) => {
              const bloatPct = Math.round((v.deadTupRatio ?? 0) * 100);
              const level    = sev(v.deadTupRatio ?? 0);
              const lastVac  = v.lastAutoVacuum || v.lastVacuum;
              const lastAnz  = v.lastAutoAnalyze || v.lastAnalyze;

              // Severity colors
              const sevIcon  = level === 'critical' ? { icon: 'fa-circle-dot',       cls: 'text-red-500' }
                             : level === 'warning'  ? { icon: 'fa-triangle-exclamation', cls: 'text-amber-500' }
                             :                        { icon: 'fa-circle-check',      cls: 'text-emerald-500' };
              const barColor = level === 'critical' ? 'bg-red-500'
                             : level === 'warning'  ? 'bg-amber-400'
                             :                        'bg-emerald-500';
              const pctColor = level === 'critical' ? 'text-red-600 dark:text-red-400'
                             : level === 'warning'  ? 'text-amber-600 dark:text-amber-400'
                             :                        'text-emerald-600 dark:text-emerald-400';

              // Stale vacuum warning (>7 days or never)
              const vacStale = !lastVac || (Date.now() - new Date(lastVac).getTime()) > 7 * 86400000;

              return (
                <tr key={i} className={`hover:bg-indigo-50/30 dark:hover:bg-indigo-900/10 transition-colors ${level === 'critical' ? 'bg-red-50/20 dark:bg-red-900/5' : ''}`}>

                  {/* Table name + severity icon */}
                  <td className="px-3 py-2.5">
                    <div className="flex items-start gap-1.5">
                      <i className={`fas ${sevIcon.icon} ${sevIcon.cls} text-[10px] mt-0.5 shrink-0`} />
                      <div className="min-w-0">
                        <span className="font-mono text-[11px] text-slate-600 dark:text-slate-300">
                          <span className="text-slate-400 dark:text-slate-500">{v.schemaName}.</span>{v.tableName}
                        </span>
                        {v.lastAutoVacuum && (
                          <Tip text="Autovacuum ran on this table. PostgreSQL's background autovacuum daemon automatically reclaims dead rows.">
                            <div className="text-[9px] text-emerald-500 dark:text-emerald-400 mt-0.5 flex items-center gap-0.5">
                              <i className="fas fa-robot text-[8px]" /> auto
                            </div>
                          </Tip>
                        )}
                      </div>
                    </div>
                  </td>

                  {/* % Dead with gradient bar */}
                  <td className="px-3 py-2.5">
                    <div className="flex flex-col items-end gap-1 min-w-[72px]">
                      <span className={`text-xs font-bold tabular-nums ${pctColor}`}>{bloatPct}%</span>
                      <div className="w-full h-1.5 bg-gray-200/80 dark:bg-gray-700/60 rounded-full overflow-hidden">
                        <div
                          className={`h-full rounded-full transition-all duration-500 ${barColor} ${level === 'critical' ? 'shadow-[0_0_4px_currentColor]' : ''}`}
                          style={{ width: `${Math.min(bloatPct, 100)}%` }}
                        />
                      </div>
                    </div>
                  </td>

                  {/* Live rows */}
                  <td className="px-3 py-2.5 text-right text-gray-700 dark:text-gray-300 tabular-nums whitespace-nowrap font-medium">
                    {(v.nLiveTup ?? 0).toLocaleString()}
                  </td>

                  {/* Dead rows */}
                  <td className={`px-3 py-2.5 text-right tabular-nums whitespace-nowrap font-semibold ${pctColor}`}>
                    {(v.nDeadTup ?? 0).toLocaleString()}
                  </td>

                  {/* Last vacuum */}
                  <td className={`px-3 py-2.5 text-right text-[11px] tabular-nums whitespace-nowrap ${vacStale && level !== 'healthy' ? 'text-amber-600 dark:text-amber-400' : 'text-gray-500 dark:text-gray-400'}`}>
                    <Tip text={lastVac ? `Last vacuum: ${new Date(lastVac).toLocaleString()}${v.lastAutoVacuum ? ' (autovacuum)' : ' (manual)'}` : 'Never vacuumed — run VACUUM ANALYZE to reclaim bloat.'} wide>
                      <span className="flex items-center justify-end gap-1">
                        {vacStale && level !== 'healthy' && <i className="fas fa-clock text-[8px]" />}
                        {rel(lastVac)}
                      </span>
                    </Tip>
                  </td>

                  {/* Last analyze */}
                  <td className="px-3 py-2.5 text-right text-[11px] text-gray-500 dark:text-gray-400 tabular-nums whitespace-nowrap">
                    <Tip text={lastAnz ? `Last analyze: ${new Date(lastAnz).toLocaleString()}${v.lastAutoAnalyze ? ' (auto)' : ' (manual)'}` : 'Never analyzed — planner stats may be stale.'} wide>
                      <span>{rel(lastAnz)}</span>
                    </Tip>
                  </td>

                  {/* Action: copy VACUUM ANALYZE command */}
                  <td className="px-3 py-2.5 text-center">
                    <Tip text={`Copies: VACUUM ANALYZE "${v.schemaName}"."${v.tableName}"; — run in psql or your DB client to reclaim dead rows immediately.`} wide>
                      <button
                        onClick={() => handleCopyVacuum(v, i)}
                        className={`inline-flex items-center gap-1 px-2 py-1 rounded-lg text-[10px] font-medium border transition-all duration-200 ${
                          copied === i
                            ? 'bg-emerald-100 dark:bg-emerald-900/30 text-emerald-700 dark:text-emerald-400 border-emerald-300/60 dark:border-emerald-700/40'
                            : level === 'critical'
                            ? 'bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400 border-red-200/60 dark:border-red-700/40 hover:bg-red-100 dark:hover:bg-red-900/30'
                            : 'bg-gray-100/80 dark:bg-gray-700/50 text-gray-600 dark:text-gray-300 border-gray-200/60 dark:border-gray-600/40 hover:bg-gray-200 dark:hover:bg-gray-700'
                        }`}
                      >
                        <i className={`fas fa-${copied === i ? 'check' : 'broom'} text-[9px]`} />
                        {copied === i ? 'Copied' : 'VACUUM'}
                      </button>
                    </Tip>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      {paginator(vacPg, filtered.length, VAC_PAGE_SIZE, setVacPage)}

      {/* ── Index Usage (enhanced) ── */}
      {filteredIdx.length > 0 && (
        <div className="space-y-2">
          <div className="flex items-center gap-2">
            <h3 className="text-[11px] font-semibold text-gray-500 dark:text-gray-400 uppercase tracking-wider flex-1 flex items-center gap-1.5">
              <i className="fas fa-sitemap text-indigo-500 text-[10px]" /> Index Usage
            </h3>
            {filteredIdx.filter(x => x.isUnused).length > 0 && (
              <Tip text="Indexes with 0 scans waste storage and slow writes. Consider DROP INDEX CONCURRENTLY after confirming they are not needed." wide>
                <span className="px-2 py-0.5 rounded-full text-[10px] bg-red-100 dark:bg-red-900/30 text-red-600 dark:text-red-400 border border-red-200/60 dark:border-red-700/40 font-medium">
                  <i className="fas fa-triangle-exclamation text-[8px] mr-1" />
                  {filteredIdx.filter(x => x.isUnused).length} unused
                </span>
              </Tip>
            )}
          </div>
          <div className="overflow-x-auto rounded-xl border border-gray-200/60 dark:border-gray-700/40 shadow-sm">
            <table className="w-full text-xs min-w-[420px]">
              <thead className="bg-gray-50/90 dark:bg-gray-800/70 border-b border-gray-200/60 dark:border-gray-700/40">
                <tr>
                  <th className="px-3 py-2 text-left text-[11px] font-semibold text-gray-500 dark:text-gray-400">Index</th>
                  <th className="px-3 py-2 text-right text-[11px] font-semibold text-gray-500 dark:text-gray-400 whitespace-nowrap">
                    <Tip text="Total index scans since last stats reset. 0 scans means this index is never used — it wastes storage and adds write overhead." wide>Scans</Tip>
                  </th>
                  <th className="px-3 py-2 text-right text-[11px] font-semibold text-gray-500 dark:text-gray-400 whitespace-nowrap">
                    <Tip text="Disk space consumed by this index. Unused indexes with large sizes are prime candidates for removal.">Size</Tip>
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100/70 dark:divide-gray-700/30">
                {idxRows.map((idx, i) => (
                  <tr key={i} className={`transition-colors ${idx.isUnused ? 'bg-red-50/40 dark:bg-red-900/10 hover:bg-red-50/60 dark:hover:bg-red-900/20' : 'hover:bg-gray-50/60 dark:hover:bg-gray-700/20'}`}>
                    <td className="px-3 py-2.5">
                      <div className="flex items-start gap-1.5">
                        {idx.isUnused
                          ? <i className="fas fa-triangle-exclamation text-red-500 text-[10px] mt-0.5 shrink-0" />
                          : <i className="fas fa-sitemap text-indigo-400 text-[10px] mt-0.5 shrink-0" />}
                        <div className="min-w-0">
                          <code className={`break-all text-xs font-medium ${idx.isUnused ? 'text-red-700 dark:text-red-400' : 'text-indigo-700 dark:text-indigo-300'}`}>
                            {idx.indexName}
                          </code>
                          <div className="text-[11px] text-gray-400 mt-0.5 font-mono">
                            <span className="text-gray-300 dark:text-gray-600">{idx.schemaName}.</span>{idx.tableName}
                          </div>
                        </div>
                      </div>
                    </td>
                    <td className="px-3 py-2.5 text-right whitespace-nowrap">
                      {idx.isUnused
                        ? <span className="text-red-500 font-semibold text-xs">0 — unused</span>
                        : <span className="text-gray-600 dark:text-gray-300 font-medium tabular-nums text-xs">{(idx.idxScan ?? 0).toLocaleString()}</span>}
                    </td>
                    <td className="px-3 py-2.5 text-right text-gray-400 tabular-nums whitespace-nowrap text-xs">
                      {idx.indexSizeBytes > 0 ? fmtBytes(idx.indexSizeBytes) : '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {paginator(idxPg, indexUsage.length, IDX_PAGE_SIZE, setIdxPage)}
        </div>
      )}

      {/* ── Table Activity (enhanced) ── */}
      {filteredAct.length > 0 && (
        <div className="space-y-2">
          <h3 className="text-[11px] font-semibold text-gray-500 dark:text-gray-400 uppercase tracking-wider flex items-center gap-1.5">
            <i className="fas fa-table text-sky-500 text-[10px]" /> Table Activity
          </h3>
          <div className="overflow-x-auto rounded-xl border border-gray-200/60 dark:border-gray-700/40 shadow-sm">
            <table className="w-full text-xs min-w-[520px]">
              <thead className="bg-gray-50/90 dark:bg-gray-800/70 border-b border-gray-200/60 dark:border-gray-700/40">
                <tr>
                  <th className="px-3 py-2 text-left text-[11px] font-semibold text-gray-500 dark:text-gray-400">Table</th>
                  <th className="px-3 py-2 text-right text-[11px] font-semibold text-gray-500 dark:text-gray-400 whitespace-nowrap">
                    <Tip text="Full sequential table scans — DB reads every row. High seq scans on large tables usually mean a missing index.">Seq Scans</Tip>
                  </th>
                  <th className="px-3 py-2 text-right text-[11px] font-semibold text-gray-500 dark:text-gray-400 whitespace-nowrap">
                    <Tip text="Index scans — efficient targeted reads via an index. More index scans vs seq scans means indexes are effective.">Idx Scans</Tip>
                  </th>
                  <th className="px-3 py-2 text-right text-[11px] font-semibold text-gray-500 dark:text-gray-400 whitespace-nowrap">
                    <Tip text="Total write operations: INSERTs + UPDATEs + DELETEs. High writes with many indexes can add overhead.">Writes</Tip>
                  </th>
                  <th className="px-3 py-2 text-right text-[11px] font-semibold text-gray-500 dark:text-gray-400 whitespace-nowrap">
                    <Tip text="% of all scans that are sequential. Above 50% on a busy table often signals a missing index." wide>Seq %</Tip>
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100/70 dark:divide-gray-700/30">
                {actRows.map((t, i) => {
                  const total  = (t.seqScan ?? 0) + (t.idxScan ?? 0);
                  const seqPct = total > 0 ? Math.round(((t.seqScan ?? 0) / total) * 100) : 0;
                  const writes = (t.nTupIns ?? 0) + (t.nTupUpd ?? 0) + (t.nTupDel ?? 0);
                  return (
                    <tr key={i} className="hover:bg-gray-50/60 dark:hover:bg-gray-700/20 transition-colors">
                      <td className="px-3 py-2.5">
                        <div className="min-w-0">
                          <code className="text-xs font-medium text-indigo-700 dark:text-indigo-300">
                            {t.tableName}
                          </code>
                          <div className="text-[11px] text-gray-400 mt-0.5 font-mono">
                            <span className="text-gray-300 dark:text-gray-600">{t.schemaName}.</span>{t.tableName}
                          </div>
                        </div>
                      </td>
                      <td className={`px-3 py-2.5 text-right font-medium tabular-nums whitespace-nowrap text-xs ${seqPct > 50 ? 'text-orange-600 dark:text-orange-400' : 'text-gray-600 dark:text-gray-400'}`}>
                        {(t.seqScan ?? 0).toLocaleString()}
                      </td>
                      <td className="px-3 py-2.5 text-right text-emerald-600 dark:text-emerald-400 font-medium tabular-nums whitespace-nowrap text-xs">
                        {(t.idxScan ?? 0).toLocaleString()}
                      </td>
                      <td className="px-3 py-2.5 text-right text-gray-600 dark:text-gray-400 tabular-nums whitespace-nowrap text-xs">
                        {writes.toLocaleString()}
                      </td>
                      <td className="px-3 py-2.5 text-right">
                        <span className={`inline-flex items-center gap-1 px-1.5 py-0.5 rounded-md text-[10px] font-semibold border ${
                          seqPct > 80 ? 'bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400 border-red-200/60 dark:border-red-700/40'
                          : seqPct > 50 ? 'bg-amber-50 dark:bg-amber-900/20 text-amber-700 dark:text-amber-400 border-amber-200/60 dark:border-amber-700/40'
                          : 'bg-gray-50 dark:bg-gray-700/30 text-gray-600 dark:text-gray-400 border-gray-200/60 dark:border-gray-600/40'
                        }`}>
                          {seqPct > 50 && <i className="fas fa-exclamation text-[8px]" />}
                          {seqPct}%
                        </span>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          {paginator(actPg, filteredAct.length, ACT_PAGE_SIZE, setActPage)}
        </div>
      )}
    </div>
  );
}

// ── Snapshots Tab ─────────────────────────────────────────────────────────────
function SnapshotsTab({ snapshots, rd, instanceId }: {
  snapshots:  DbSnapshot[];
  rd:         DbInstance | undefined;
  instanceId: string;
}) {
  const [sortKey,     setSortKey]     = useState<'age'|'size'|'cost'>('age');
  const [sortDir,     setSortDir]     = useState<'desc'|'asc'>('desc');
  const [compareMode, setCompareMode] = useState(false);
  const [selected,    setSelected]    = useState<Set<string>>(new Set());
  const [copied,      setCopied]      = useState<string|null>(null);
  const [page,        setPage]        = useState(0);
  const PAGE_SIZE = 10;

  // ── Helpers ───────────────────────────────────────────────────────────────
  const isAuto = (id: string) =>
    id.startsWith('rds:') || id.startsWith('awsbackup:') || id.toLowerCase().includes('auto');

  const ageTier = (d: number) =>
    d > 90  ? { cls: 'text-red-600 dark:text-red-400',   bar: 'bg-red-500',    border: 'border-l-red-400',   dot: 'bg-red-500 shadow-[0_0_4px_#ef4444]' }
    : d > 30 ? { cls: 'text-amber-600 dark:text-amber-400', bar: 'bg-amber-400', border: 'border-l-amber-400', dot: 'bg-amber-400' }
    :           { cls: 'text-emerald-600 dark:text-emerald-400', bar: 'bg-emerald-500', border: 'border-l-emerald-400', dot: 'bg-emerald-500' };

  const approxDate = (ageDays: number) =>
    new Date(Date.now() - ageDays * 86400000).toLocaleDateString([], { month: 'short', day: 'numeric', year: 'numeric' });

  const copyCmd = (cmd: string, key: string) => {
    navigator.clipboard.writeText(cmd).then(() => {
      setCopied(key); setTimeout(() => setCopied(null), 2200);
    });
  };

  const restoreCmd = (s: DbSnapshot) =>
    `aws rds restore-db-instance-from-db-snapshot \\\n  --db-instance-identifier ${instanceId}-restored \\\n  --db-snapshot-identifier ${s.snapshotId} \\\n  --region ${s.region}`;

  const deleteCmd = (s: DbSnapshot) =>
    `aws rds delete-db-snapshot \\\n  --db-snapshot-identifier ${s.snapshotId} \\\n  --region ${s.region}`;

  const takeSnapshotCmd =
    `aws rds create-db-snapshot \\\n  --db-instance-identifier ${instanceId} \\\n  --db-snapshot-identifier ${instanceId}-manual-$(date +%Y%m%d%H%M%S) \\\n  --region ${rd?.region ?? 'us-east-1'}`;

  // ── Sort ──────────────────────────────────────────────────────────────────
  const d = sortDir === 'desc' ? -1 : 1;
  const sorted = [...snapshots].sort((a, b) => {
    if (sortKey === 'age')  return d * (b.ageDays - a.ageDays);
    if (sortKey === 'size') return d * (b.sizeGb - a.sizeGb);
    return d * (b.estimatedMonthlyCost - a.estimatedMonthlyCost);
  });

  const totalPages = Math.max(1, Math.ceil(sorted.length / PAGE_SIZE));
  const pg   = Math.min(page, totalPages - 1);
  const rows = sorted.slice(pg * PAGE_SIZE, (pg + 1) * PAGE_SIZE);

  // ── Derived totals ────────────────────────────────────────────────────────
  const totalCost  = snapshots.reduce((s, n) => s + n.estimatedMonthlyCost, 0);
  const totalSizeGb = snapshots.reduce((s, n) => s + n.sizeGb, 0);
  const oldestDays = snapshots.length ? Math.max(...snapshots.map(s => s.ageDays)) : 0;

  // ── Compare ───────────────────────────────────────────────────────────────
  const toggleSelect = (id: string) => {
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(id)) { next.delete(id); return next; }
      if (next.size >= 2) return prev; // max 2
      next.add(id); return next;
    });
  };
  const [idA, idB] = [...selected];
  const snapA = snapshots.find(s => s.snapshotId === idA);
  const snapB = snapshots.find(s => s.snapshotId === idB);

  // ── Empty state ───────────────────────────────────────────────────────────
  if (snapshots.length === 0) {
    return (
      <div className="space-y-5">
        {/* Illustrated hero */}
        <div className="relative overflow-hidden rounded-2xl border border-emerald-200/50 dark:border-emerald-800/20 bg-gradient-to-br from-emerald-50/60 via-white to-teal-50/40 dark:from-emerald-950/30 dark:via-gray-900 dark:to-teal-950/20 p-6">
          <div className="absolute -top-8 -right-8 w-36 h-36 rounded-full bg-emerald-200/20 dark:bg-emerald-800/10 blur-2xl pointer-events-none" />
          <div className="flex items-start gap-5">
            <svg width="64" height="64" viewBox="0 0 64 64" fill="none" className="shrink-0 drop-shadow-sm">
              <rect x="6" y="18" width="52" height="36" rx="5" stroke="url(#camGrad)" strokeWidth="1.5" fill="url(#camFill)" />
              <circle cx="32" cy="36" r="10" stroke="url(#camGrad)" strokeWidth="1.5" fill="none" />
              <circle cx="32" cy="36" r="5" fill="url(#camGrad)" opacity="0.3" />
              <path d="M22 18V14a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v4" stroke="url(#camGrad)" strokeWidth="1.5" fill="none" />
              <circle cx="52" cy="26" r="3" fill="url(#camGrad)" opacity="0.5" />
              <defs>
                <linearGradient id="camGrad" x1="6" y1="12" x2="58" y2="54" gradientUnits="userSpaceOnUse">
                  <stop stopColor="#10b981" /><stop offset="1" stopColor="#0ea5e9" />
                </linearGradient>
                <linearGradient id="camFill" x1="6" y1="18" x2="58" y2="54" gradientUnits="userSpaceOnUse">
                  <stop stopColor="#10b981" stopOpacity="0.06" /><stop offset="1" stopColor="#0ea5e9" stopOpacity="0.04" />
                </linearGradient>
              </defs>
            </svg>
            <div className="flex-1">
              <p className="text-sm font-bold text-gray-800 dark:text-gray-100 mb-1">No old manual snapshots</p>
              <p className="text-[11px] text-gray-500 dark:text-gray-400 leading-relaxed mb-4">
                This view shows <strong>manual snapshots older than 30 days</strong> that may be accumulating storage costs.
                Automated daily backups (configured via RDS) are separate and managed by AWS.
              </p>
              <div className="flex items-center gap-2 flex-wrap">
                <button
                  onClick={() => copyCmd(takeSnapshotCmd, 'take')}
                  className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold transition-all duration-200 shadow-sm ${
                    copied === 'take'
                      ? 'bg-emerald-600 text-white shadow-emerald-200 dark:shadow-emerald-900/30'
                      : 'bg-indigo-600 text-white hover:bg-indigo-700 shadow-indigo-200 dark:shadow-indigo-900/30'
                  }`}
                >
                  <i className={`fas fa-${copied === 'take' ? 'check' : 'camera'} text-[10px]`} />
                  {copied === 'take' ? 'CLI copied!' : 'Copy Take-Snapshot CLI'}
                  {copied !== 'take' && <i className="fas fa-terminal text-[9px] opacity-70" />}
                </button>
                <span className="text-[10px] text-gray-400 dark:text-gray-500">Creates a manual point-in-time snapshot of the full DB</span>
              </div>
            </div>
          </div>
        </div>

        {/* What snapshots capture */}
        <div className="grid grid-cols-2 gap-2.5">
          {[
            { icon: 'fa-database',        color: 'text-indigo-500',  bg: 'bg-indigo-50 dark:bg-indigo-900/20',  title: 'Full DB state',       desc: 'All tables, indexes, stored procedures, and data at the moment of capture.' },
            { icon: 'fa-clock-rotate-left',color: 'text-sky-500',    bg: 'bg-sky-50 dark:bg-sky-900/20',        title: 'Point-in-time restore',desc: 'Restore to any second within your retention window (5 min granularity).' },
            { icon: 'fa-copy',            color: 'text-violet-500',  bg: 'bg-violet-50 dark:bg-violet-900/20',  title: 'Cross-region copy',    desc: 'Copy a snapshot to another region for DR. Share with another AWS account.' },
            { icon: 'fa-shield-halved',   color: 'text-emerald-500', bg: 'bg-emerald-50 dark:bg-emerald-900/20',title: 'Encrypted by default',  desc: 'Snapshots inherit the instance encryption key. Cannot be disabled after creation.' },
          ].map(c => (
            <div key={c.title} className={`flex items-start gap-2.5 p-3 rounded-xl border border-gray-200/50 dark:border-gray-700/30 ${c.bg}`}>
              <i className={`fas ${c.icon} ${c.color} text-[11px] mt-0.5 shrink-0`} />
              <div>
                <p className="text-[11px] font-semibold text-gray-700 dark:text-gray-300">{c.title}</p>
                <p className="text-[10px] text-gray-400 leading-snug mt-0.5">{c.desc}</p>
              </div>
            </div>
          ))}
        </div>

        {/* Scheduled backup config */}
        {rd && (
          <div className="rounded-xl border border-gray-200/50 dark:border-gray-700/30 overflow-hidden">
            <div className="flex items-center gap-2 px-3.5 py-2.5 bg-gray-50/80 dark:bg-gray-800/50 border-b border-gray-200/50 dark:border-gray-700/30">
              <i className="fas fa-calendar-check text-indigo-400 text-[11px]" />
              <span className="text-[11px] font-semibold text-gray-600 dark:text-gray-300 uppercase tracking-wider">Scheduled Backups</span>
            </div>
            <div className="grid grid-cols-3 divide-x divide-gray-100 dark:divide-gray-700/40">
              {[
                { label: 'Automated Backups', value: rd.backupEnabled ? 'Enabled' : 'Disabled', ok: rd.backupEnabled },
                { label: 'Retention',         value: rd.backupRetentionDays ? `${rd.backupRetentionDays} days` : '—', ok: Number(rd.backupRetentionDays) >= 7 },
                { label: 'Multi-AZ',          value: rd.multiAz === 'true' || rd.multiAz === 'Yes' ? 'Yes' : 'No', ok: rd.multiAz === 'true' || rd.multiAz === 'Yes' },
              ].map(r => (
                <div key={r.label} className="px-4 py-3 text-center">
                  <div className="text-[9px] text-gray-400 uppercase tracking-wider mb-1">{r.label}</div>
                  <div className={`text-xs font-bold ${r.ok ? 'text-emerald-600 dark:text-emerald-400' : 'text-amber-600 dark:text-amber-400'}`}>{r.value}</div>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    );
  }

  // ── Data present ──────────────────────────────────────────────────────────
  return (
    <div className="space-y-4">

      {/* ── Waste alert banner ── */}
      {totalCost > 1 && (
        <div className="relative overflow-hidden flex items-center gap-3 px-4 py-3 rounded-xl border border-amber-200/60 dark:border-amber-800/30 bg-gradient-to-r from-amber-50/80 to-white dark:from-amber-950/30 dark:to-gray-900">
          <div className="w-8 h-8 rounded-lg bg-amber-100 dark:bg-amber-900/40 flex items-center justify-center shrink-0">
            <i className="fas fa-triangle-exclamation text-amber-500 text-xs" />
          </div>
          <div className="flex-1">
            <p className="text-xs font-semibold text-amber-800 dark:text-amber-300">
              {snapshots.length} old manual snapshot{snapshots.length !== 1 ? 's' : ''} costing {fmt$(totalCost)}/mo
            </p>
            <p className="text-[10px] text-amber-600 dark:text-amber-500 mt-0.5">
              Manual snapshots older than 30 days. Review and delete those no longer needed.
            </p>
          </div>
          <span className="text-lg font-black text-amber-600 dark:text-amber-400 tabular-nums shrink-0">{fmt$(totalCost)}<span className="text-[10px] font-normal text-gray-400">/mo</span></span>
        </div>
      )}

      {/* ── KPI strip ── */}
      <div className="grid grid-cols-4 gap-2">
        {[
          { icon: 'fa-camera',         label: 'Snapshots',    value: snapshots.length.toString(),      color: 'text-violet-600 dark:text-violet-400',  tip: 'Total number of DB snapshots for this instance (automated + manual).' },
          { icon: 'fa-hard-drive',     label: 'Total Size',   value: `${round2(totalSizeGb)} GB`,      color: 'text-sky-600 dark:text-sky-400',         tip: 'Combined storage used by all snapshots. AWS charges ~$0.095/GB/month for snapshot storage.' },
          { icon: 'fa-dollar-sign',    label: 'Cost / mo',    value: fmt$(totalCost),                  color: 'text-amber-600 dark:text-amber-400',     tip: 'Estimated monthly cost for all snapshot storage. Delete old manual snapshots to reduce this.' },
          { icon: 'fa-clock',          label: 'Oldest',       value: `${oldestDays}d`,                 color: oldestDays > 90 ? 'text-red-600 dark:text-red-400' : 'text-gray-600 dark:text-gray-400', tip: 'Age of the oldest snapshot in days. Snapshots older than 90 days are likely no longer needed.' },
        ].map(k => (
          <div key={k.label} className="bg-white/80 dark:bg-gray-800/60 backdrop-blur-sm rounded-xl border border-gray-100/80 dark:border-gray-700/40 px-3 py-2.5 shadow-sm">
            <div className="flex items-center gap-1.5 mb-1">
              <i className={`fas ${k.icon} text-[10px] ${k.color}`} />
              <Tip text={k.tip} wide>
                <span className="text-[9px] text-gray-400 uppercase tracking-wide">{k.label}</span>
              </Tip>
            </div>
            <div className={`text-base font-bold tabular-nums ${k.color}`}>{k.value}</div>
          </div>
        ))}
      </div>

      {/* ── Compare mode panel ── */}
      {compareMode && selected.size === 2 && snapA && snapB && (
        <div className="rounded-xl border border-violet-200/60 dark:border-violet-800/30 bg-gradient-to-br from-violet-50/60 via-white to-indigo-50/40 dark:from-violet-950/30 dark:via-gray-900 dark:to-indigo-950/20 p-4 space-y-3">
          <div className="flex items-center gap-2 mb-1">
            <i className="fas fa-code-compare text-violet-500 text-xs" />
            <span className="text-[11px] font-bold text-violet-700 dark:text-violet-300 uppercase tracking-wider">Snapshot Comparison</span>
            <button onClick={() => { setSelected(new Set()); }} className="ml-auto text-[9px] text-gray-400 hover:text-gray-600 dark:hover:text-gray-300 transition-colors">
              <i className="fas fa-xmark mr-1" />Clear
            </button>
          </div>
          <div className="grid grid-cols-2 gap-3">
            {[{ snap: snapA, label: 'Snapshot A' }, { snap: snapB, label: 'Snapshot B' }].map(({ snap, label }) => {
              const t = ageTier(snap.ageDays);
              return (
                <div key={snap.snapshotId} className="bg-white/70 dark:bg-gray-800/50 rounded-lg p-3 border border-gray-200/50 dark:border-gray-700/30">
                  <p className="text-[9px] font-bold text-gray-400 uppercase tracking-widest mb-1.5">{label}</p>
                  <p className="font-mono text-[10px] text-indigo-700 dark:text-indigo-300 truncate mb-2">{snap.snapshotId}</p>
                  <div className="space-y-1 text-[11px]">
                    <div className="flex justify-between"><span className="text-gray-400">Age</span><span className={`font-semibold ${t.cls}`}>{snap.ageDays}d</span></div>
                    <div className="flex justify-between"><span className="text-gray-400">Size</span><span className="font-semibold text-gray-700 dark:text-gray-300">{snap.sizeGb} GB</span></div>
                    <div className="flex justify-between"><span className="text-gray-400">Cost/mo</span><span className="font-semibold text-gray-700 dark:text-gray-300">{fmt$(snap.estimatedMonthlyCost)}</span></div>
                  </div>
                </div>
              );
            })}
          </div>
          {/* Delta row */}
          <div className="grid grid-cols-3 gap-2 pt-1">
            {(() => {
              const ageDelta  = snapA.ageDays - snapB.ageDays;
              const sizeDelta = snapA.sizeGb - snapB.sizeGb;
              const costDelta = snapA.estimatedMonthlyCost - snapB.estimatedMonthlyCost;
              return [
                { label: 'Age diff',  val: `${Math.abs(ageDelta)}d`,            dir: ageDelta,  fmt: (v: number) => `${Math.abs(v)}d` },
                { label: 'Size diff', val: `${Math.abs(round2(sizeDelta))} GB`,  dir: sizeDelta, fmt: (v: number) => `${Math.abs(round2(v))} GB` },
                { label: 'Cost diff', val: fmt$(Math.abs(costDelta)),             dir: costDelta, fmt: (v: number) => fmt$(Math.abs(v)) },
              ].map(r => (
                <div key={r.label} className="text-center bg-white/60 dark:bg-gray-800/40 rounded-lg px-2 py-2">
                  <div className="text-[9px] text-gray-400 uppercase tracking-wider mb-0.5">{r.label}</div>
                  <div className={`text-xs font-bold tabular-nums flex items-center justify-center gap-1 ${r.dir > 0 ? 'text-red-500' : r.dir < 0 ? 'text-emerald-500' : 'text-gray-400'}`}>
                    {r.dir > 0 && <i className="fas fa-arrow-up text-[9px]" />}
                    {r.dir < 0 && <i className="fas fa-arrow-down text-[9px]" />}
                    {r.fmt(r.dir)}
                  </div>
                </div>
              ));
            })()}
          </div>
          <p className="text-[9px] text-violet-400 dark:text-violet-500 text-center">
            A is {snapA.ageDays > snapB.ageDays ? 'older' : snapA.ageDays < snapB.ageDays ? 'newer' : 'same age'} and {snapA.sizeGb > snapB.sizeGb ? 'larger' : snapA.sizeGb < snapB.sizeGb ? 'smaller' : 'same size'} than B
          </p>
        </div>
      )}
      {compareMode && selected.size === 1 && (
        <div className="text-center py-2 text-[11px] text-violet-500 dark:text-violet-400 flex items-center justify-center gap-1.5">
          <i className="fas fa-circle-dot text-[9px] animate-pulse" /> Select one more snapshot to compare
        </div>
      )}

      {/* ── Controls row ── */}
      <div className="flex items-center gap-2 flex-wrap">
        {/* Take snapshot CTA */}
        <button
          onClick={() => copyCmd(takeSnapshotCmd, 'take-main')}
          className={`inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-[10px] font-semibold border transition-all duration-200 ${
            copied === 'take-main'
              ? 'bg-emerald-100 dark:bg-emerald-900/30 text-emerald-700 dark:text-emerald-400 border-emerald-300/60 dark:border-emerald-700/40'
              : 'bg-indigo-50 dark:bg-indigo-900/20 text-indigo-600 dark:text-indigo-400 border-indigo-200/60 dark:border-indigo-700/40 hover:bg-indigo-100 dark:hover:bg-indigo-900/30'
          }`}
        >
          <i className={`fas fa-${copied === 'take-main' ? 'check' : 'camera'} text-[9px]`} />
          {copied === 'take-main' ? 'CLI Copied!' : 'Take Snapshot CLI'}
        </button>

        {/* Compare toggle */}
        <button
          onClick={() => { setCompareMode(v => !v); setSelected(new Set()); }}
          className={`inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-[10px] font-semibold border transition-all duration-200 ${
            compareMode
              ? 'bg-violet-600 text-white border-violet-600 shadow-sm'
              : 'text-violet-600 dark:text-violet-400 border-violet-200/60 dark:border-violet-700/40 hover:bg-violet-50 dark:hover:bg-violet-900/20'
          }`}
        >
          <i className="fas fa-code-compare text-[9px]" />
          {compareMode ? `Compare (${selected.size}/2)` : 'Compare'}
        </button>

        <span className="ml-auto text-[10px] text-gray-400">{snapshots.length} snapshot{snapshots.length !== 1 ? 's' : ''}</span>

        {/* Sort */}
        <div className="flex items-center gap-0.5 bg-gray-100/80 dark:bg-gray-800/60 rounded-lg p-0.5 border border-gray-200/50 dark:border-gray-700/40">
          <span className="text-[9px] text-gray-400 px-1.5 font-medium">Sort:</span>
          {([
            { k: 'age'  as const, l: 'Age'  },
            { k: 'size' as const, l: 'Size' },
            { k: 'cost' as const, l: 'Cost' },
          ]).map(s => (
            <button key={s.k}
              onClick={() => { if (s.k === sortKey) setSortDir(p => p === 'desc' ? 'asc' : 'desc'); else { setSortKey(s.k); setSortDir('desc'); } }}
              className={`px-2 py-1 text-[10px] font-medium rounded transition-all flex items-center gap-0.5 ${sortKey === s.k ? 'bg-white dark:bg-gray-700 text-indigo-600 dark:text-indigo-400 shadow-sm' : 'text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-200'}`}
            >
              {s.l}
              {sortKey === s.k && <i className={`fas fa-arrow-${sortDir === 'desc' ? 'down' : 'up'} text-[8px] ml-0.5`} />}
            </button>
          ))}
        </div>
      </div>

      {/* ── Snapshot timeline table ── */}
      <div className="space-y-2">
        {rows.map((s, i) => {
          const t       = ageTier(s.ageDays);
          const auto    = isAuto(s.snapshotId);
          const absIdx  = pg * PAGE_SIZE + i;
          const isSel   = selected.has(s.snapshotId);
          const copyKey = (cmd: string) => `${cmd}-${absIdx}`;

          return (
            <div
              key={s.snapshotId}
              className={`relative rounded-xl border-l-2 border border-gray-200/50 dark:border-gray-700/30 ${t.border} transition-all duration-200 ${isSel ? 'ring-2 ring-violet-400/40 dark:ring-violet-600/30 bg-violet-50/30 dark:bg-violet-900/10' : 'bg-white/70 dark:bg-gray-800/40 hover:bg-gray-50/80 dark:hover:bg-gray-700/30'}`}
            >
              <div className="flex items-center gap-3 px-3.5 py-3">
                {/* Compare checkbox */}
                {compareMode && (
                  <button
                    onClick={() => toggleSelect(s.snapshotId)}
                    disabled={!isSel && selected.size >= 2}
                    className={`w-5 h-5 rounded-md border-2 flex items-center justify-center shrink-0 transition-all ${isSel ? 'bg-violet-600 border-violet-600' : 'border-gray-300 dark:border-gray-600 hover:border-violet-400 disabled:opacity-30'}`}
                  >
                    {isSel && <i className="fas fa-check text-white text-[9px]" />}
                  </button>
                )}

                {/* Age dot */}
                <Tip text={s.ageDays > 90 ? 'Old snapshot (>90 days) — red dot. Consider deleting if no longer needed.' : s.ageDays > 30 ? 'Aging snapshot (>30 days) — orange dot. Review if still required.' : 'Recent snapshot (<30 days) — green dot. Likely still relevant.'} wide>
                  <div className="relative shrink-0">
                    <span className={`w-2.5 h-2.5 rounded-full block ${t.dot}`} />
                  </div>
                </Tip>

                {/* Snapshot ID */}
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <code className="text-[11px] font-medium text-indigo-700 dark:text-indigo-300 truncate">{s.snapshotId}</code>
                    <Tip text={auto ? 'Automatically created by RDS automated backup or AWS Backup. Cannot be manually deleted while retention policy is active.' : 'Manually created snapshot. You have full control — safe to delete when no longer needed.'} wide>
                      <span className={`shrink-0 inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[9px] font-semibold border ${auto ? 'bg-sky-50 dark:bg-sky-900/20 text-sky-600 dark:text-sky-400 border-sky-200/50 dark:border-sky-800/30' : 'bg-violet-50 dark:bg-violet-900/20 text-violet-600 dark:text-violet-400 border-violet-200/50 dark:border-violet-800/30'}`}>
                        <i className={`fas ${auto ? 'fa-robot' : 'fa-hand'} text-[8px]`} />
                        {auto ? 'Auto' : 'Manual'}
                      </span>
                    </Tip>
                  </div>
                  <div className="flex items-center gap-3 mt-0.5">
                    <span className="text-[10px] text-gray-400 flex items-center gap-1">
                      <i className="fas fa-location-dot text-[8px]" />{s.region}
                    </span>
                    <span className="text-[10px] text-gray-400">~{approxDate(s.ageDays)}</span>
                  </div>
                </div>

                {/* Age */}
                <div className="text-center shrink-0">
                  <Tip text="Days since this snapshot was taken. Older snapshots cost the same to store but are less likely to be useful for recent recovery.">
                    <div className="text-[9px] text-gray-400 mb-0.5">Age</div>
                  </Tip>
                  <span className={`text-xs font-bold tabular-nums ${t.cls}`}>{s.ageDays}d</span>
                </div>

                {/* Size with mini bar */}
                <div className="text-center shrink-0 min-w-[56px]">
                  <Tip text="Storage consumed by this snapshot in GB. AWS charges ~$0.095/GB/month for RDS snapshot storage.">
                    <div className="text-[9px] text-gray-400 mb-0.5">Size</div>
                  </Tip>
                  <span className="text-xs font-semibold text-gray-700 dark:text-gray-300 tabular-nums">{s.sizeGb} GB</span>
                </div>

                {/* Cost */}
                <div className="text-center shrink-0">
                  <Tip text="Estimated monthly cost to store this snapshot (size × $0.095/GB). Delete old unneeded snapshots to save on storage costs.">
                    <div className="text-[9px] text-gray-400 mb-0.5">Cost/mo</div>
                  </Tip>
                  <span className="text-xs font-semibold text-violet-600 dark:text-violet-400 tabular-nums">{fmt$(s.estimatedMonthlyCost)}</span>
                </div>

                {/* Actions */}
                <div className="flex items-center gap-1 shrink-0">
                  <Tip text={`Copy: aws rds restore-db-instance-from-db-snapshot`} wide>
                    <button
                      onClick={() => copyCmd(restoreCmd(s), copyKey('restore'))}
                      className={`inline-flex items-center gap-1 px-2 py-1 rounded-lg text-[9px] font-medium border transition-all duration-200 ${copied === copyKey('restore') ? 'bg-emerald-100 dark:bg-emerald-900/30 text-emerald-700 dark:text-emerald-400 border-emerald-300/50' : 'bg-sky-50 dark:bg-sky-900/15 text-sky-600 dark:text-sky-400 border-sky-200/50 dark:border-sky-800/30 hover:bg-sky-100 dark:hover:bg-sky-900/30'}`}
                    >
                      <i className={`fas fa-${copied === copyKey('restore') ? 'check' : 'clock-rotate-left'} text-[8px]`} />
                      {copied === copyKey('restore') ? 'Copied' : 'Restore'}
                    </button>
                  </Tip>
                  <Tip text={`Copy: aws rds delete-db-snapshot --db-snapshot-identifier ${s.snapshotId}`} wide>
                    <button
                      onClick={() => copyCmd(deleteCmd(s), copyKey('delete'))}
                      className={`inline-flex items-center gap-1 px-2 py-1 rounded-lg text-[9px] font-medium border transition-all duration-200 ${copied === copyKey('delete') ? 'bg-emerald-100 dark:bg-emerald-900/30 text-emerald-700 dark:text-emerald-400 border-emerald-300/50' : 'bg-red-50 dark:bg-red-900/15 text-red-500 dark:text-red-400 border-red-200/50 dark:border-red-800/30 hover:bg-red-100 dark:hover:bg-red-900/30'}`}
                    >
                      <i className={`fas fa-${copied === copyKey('delete') ? 'check' : 'trash'} text-[8px]`} />
                      {copied === copyKey('delete') ? 'Copied' : 'Delete'}
                    </button>
                  </Tip>
                </div>
              </div>

              {/* Recommendation strip */}
              {s.recommendation && (
                <div className="mx-3.5 mb-3 flex items-start gap-1.5 px-2.5 py-1.5 rounded-lg bg-amber-50/70 dark:bg-amber-900/15 border border-amber-200/40 dark:border-amber-800/20">
                  <i className="fas fa-lightbulb text-amber-400 text-[9px] mt-0.5 shrink-0" />
                  <p className="text-[10px] text-amber-700 dark:text-amber-400 leading-snug">{s.recommendation}</p>
                </div>
              )}
            </div>
          );
        })}
      </div>

      {/* ── Pagination ── */}
      {totalPages > 1 && (() => {
        const start = pg * PAGE_SIZE + 1;
        const end   = Math.min((pg + 1) * PAGE_SIZE, sorted.length);
        return (
          <div className="flex items-center justify-between pt-2.5 px-1 border-t border-gray-100/60 dark:border-gray-700/30">
            <span className="text-[10px] text-gray-400 tabular-nums">{start}–{end} of {sorted.length}</span>
            <div className="flex items-center gap-1">
              <button onClick={() => setPage(p => Math.max(0, p - 1))} disabled={pg === 0}
                className="px-2 py-1 rounded text-[10px] font-medium text-gray-500 dark:text-gray-400 hover:bg-gray-100 dark:hover:bg-gray-700/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors inline-flex items-center gap-1 border border-gray-200/50 dark:border-gray-700/40"
              >
                <i className="fas fa-chevron-left text-[8px]" /> Prev
              </button>
              <span className="text-[10px] text-gray-500 dark:text-gray-400 px-1.5 tabular-nums">{pg + 1} / {totalPages}</span>
              <button onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))} disabled={pg >= totalPages - 1}
                className="px-2 py-1 rounded text-[10px] font-medium text-gray-500 dark:text-gray-400 hover:bg-gray-100 dark:hover:bg-gray-700/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors inline-flex items-center gap-1 border border-gray-200/50 dark:border-gray-700/40"
              >
                Next <i className="fas fa-chevron-right text-[8px]" />
              </button>
            </div>
          </div>
        );
      })()}

      {/* ── Scheduled Backups ── */}
      {rd && (
        <div className="space-y-2">
          <h3 className="text-[11px] font-semibold text-gray-400 dark:text-gray-500 uppercase tracking-widest flex items-center gap-1.5">
            <i className="fas fa-calendar-check text-indigo-400 text-[10px]" /> Scheduled Backups
          </h3>
          <div className="rounded-xl border border-gray-200/50 dark:border-gray-700/30 overflow-hidden">
            <div className="grid grid-cols-3 divide-x divide-gray-100 dark:divide-gray-700/40">
              {[
                { label: 'Automated Backups', value: rd.backupEnabled ? 'Enabled' : 'Disabled', ok: rd.backupEnabled, icon: 'fa-circle-check', tip: 'RDS automated backups create daily snapshots during the backup window. Required for point-in-time recovery.' },
                { label: 'Retention Period',  value: rd.backupRetentionDays ? `${rd.backupRetentionDays} days` : '—', ok: Number(rd.backupRetentionDays) >= 7, icon: 'fa-clock', tip: 'How long automated backups are kept before deletion. AWS recommends at least 7 days. 0 = backups disabled.' },
                { label: 'Multi-AZ',          value: rd.multiAz === 'true' || rd.multiAz === 'Yes' ? 'Yes' : 'No', ok: rd.multiAz === 'true' || rd.multiAz === 'Yes', icon: 'fa-layer-group', tip: 'Multi-AZ keeps a synchronous standby replica in another Availability Zone. Provides automatic failover — highly recommended for production.' },
              ].map(r => (
                <div key={r.label} className="px-4 py-3 text-center bg-gray-50/50 dark:bg-gray-800/30">
                  <i className={`fas ${r.icon} text-[10px] ${r.ok ? 'text-emerald-500' : 'text-amber-400'} mb-1 block`} />
                  <Tip text={r.tip} wide>
                    <div className="text-[9px] text-gray-400 uppercase tracking-wider mb-0.5">{r.label}</div>
                  </Tip>
                  <div className={`text-xs font-bold ${r.ok ? 'text-emerald-600 dark:text-emerald-400' : 'text-amber-600 dark:text-amber-400'}`}>{r.value}</div>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

// ── Security Tab ──────────────────────────────────────────────────────────────
const SECURITY_CATALOGUE = [
  {
    category: 'Access Control',
    icon: 'fa-key',
    color: 'text-rose-500',
    bg: 'bg-rose-50 dark:bg-rose-900/20',
    checks: [
      { name: 'Public Accessibility',         desc: 'DB reachable from the public internet.' },
      { name: 'IAM Database Authentication',  desc: 'Password-free login via AWS IAM tokens.' },
      { name: 'Default Port Usage',            desc: 'Using default port increases attack surface.' },
    ],
  },
  {
    category: 'Encryption',
    icon: 'fa-lock',
    color: 'text-violet-500',
    bg: 'bg-violet-50 dark:bg-violet-900/20',
    checks: [
      { name: 'Encryption at Rest',   desc: 'Storage encrypted with AWS KMS.' },
      { name: 'SSL/TLS in Transit',   desc: 'Connections require SSL/TLS.' },
      { name: 'Certificate Rotation', desc: 'SSL certificate is current and unexpired.' },
    ],
  },
  {
    category: 'Availability',
    icon: 'fa-shield-halved',
    color: 'text-sky-500',
    bg: 'bg-sky-50 dark:bg-sky-900/20',
    checks: [
      { name: 'Automated Backups',    desc: 'Daily snapshots retained ≥7 days.' },
      { name: 'Multi-AZ Deployment',  desc: 'Standby replica for failover.' },
      { name: 'Deletion Protection',  desc: 'Guards against accidental instance deletion.' },
    ],
  },
  {
    category: 'Monitoring',
    icon: 'fa-binoculars',
    color: 'text-emerald-500',
    bg: 'bg-emerald-50 dark:bg-emerald-900/20',
    checks: [
      { name: 'Enhanced Monitoring',    desc: 'OS-level metrics at 60 s intervals.' },
      { name: 'Performance Insights',   desc: 'Query-level performance profiling.' },
      { name: 'CloudWatch Alarms',      desc: 'Alerts on CPU, connections, and storage.' },
    ],
  },
] as const;

// Remediation hints keyed by lowercase keyword patterns in checkName
const REMEDIATION: { pattern: RegExp; fix: string }[] = [
  { pattern: /public/i,       fix: 'In the RDS console → Connectivity & security → set "Publicly accessible" to No. Ensure your app can still reach the DB via VPC/private subnet.' },
  { pattern: /iam/i,          fix: 'Enable IAM DB authentication in RDS settings, create an IAM policy with rds-db:connect, and update your app to use IAM token-based auth instead of a static password.' },
  { pattern: /ssl|tls/i,      fix: 'Set the DB parameter `rds.force_ssl=1` (PostgreSQL) or `require_secure_transport=ON` (MySQL). Update connection strings to include `sslmode=require`.' },
  { pattern: /encrypt/i,      fix: 'RDS encryption must be enabled at creation. Migrate by taking an unencrypted snapshot, copying it with encryption enabled, then restoring from the encrypted copy.' },
  { pattern: /backup/i,       fix: 'In RDS settings, set "Backup retention period" to 7 days or more and enable automated backups. Verify the backup window does not overlap peak traffic.' },
  { pattern: /multi.?az/i,    fix: 'Enable Multi-AZ in RDS Modify. AWS will provision a synchronous standby replica in a different AZ for automatic failover with minimal downtime.' },
  { pattern: /deletion/i,     fix: 'Enable Deletion Protection in RDS Modify → Additional configuration. This prevents the instance from being deleted without first disabling protection.' },
  { pattern: /monitor/i,      fix: 'Enable Enhanced Monitoring (60 s granularity) in RDS Modify → Monitoring. Assign an IAM role with the AmazonRDSEnhancedMonitoringRole policy.' },
  { pattern: /insight|performance/i, fix: 'Enable Performance Insights in RDS Modify → Performance Insights. It provides query-level profiling and database load analysis with no performance overhead.' },
  { pattern: /cert/i,         fix: 'Rotate the RDS SSL certificate via the AWS console: Actions → "Rotate SSL certificate". Update your app trust store to include the new CA bundle.' },
  { pattern: /port|default/i, fix: 'Change the DB port in RDS Modify → Connectivity. Choose a non-default port (not 3306 or 5432) and update security group rules and connection strings accordingly.' },
  { pattern: /password|credential/i, fix: 'Rotate credentials and store them in AWS Secrets Manager. Enable automatic rotation with the built-in RDS rotation Lambda function.' },
];

function getRemediation(checkName: string): string | null {
  const hit = REMEDIATION.find(r => r.pattern.test(checkName));
  return hit?.fix ?? null;
}

function SecurityTab({ checks, instanceSource }: {
  checks:         DbSecurityCheck[];
  instanceSource: 'rds' | 'agent';
}) {
  const [statusFilter, setStatusFilter] = useState<'ALL'|'FAIL'|'WARN'|'PASS'>('ALL');
  const [expandedIdx,  setExpandedIdx]  = useState<Set<number>>(new Set());

  const failCount = checks.filter(c => c.status === 'FAIL').length;
  const warnCount = checks.filter(c => c.status === 'WARN').length;
  const passCount = checks.filter(c => c.status === 'PASS').length;
  const highCount = checks.filter(c => c.severity === 'HIGH').length;

  // Security posture score
  const total = checks.length;
  const score = total === 0 ? null
    : Math.round(((passCount + warnCount * 0.5) / total) * 100);
  const scoreColor = score === null ? ''
    : score >= 80 ? 'text-emerald-600 dark:text-emerald-400'
    : score >= 50 ? 'text-amber-600 dark:text-amber-400'
    :               'text-red-600 dark:text-red-400';
  const scoreLabel = score === null ? '' : score >= 80 ? 'Strong' : score >= 50 ? 'Fair' : 'At Risk';

  const filtered = statusFilter === 'ALL' ? checks
    : checks.filter(c => c.status === statusFilter);

  const toggle = (i: number) => setExpandedIdx(prev => {
    const next = new Set(prev);
    next.has(i) ? next.delete(i) : next.add(i);
    return next;
  });

  // ── Empty state ─────────────────────────────────────────────────────────────
  if (checks.length === 0) {
    return (
      <div className="space-y-5">

        {/* Illustrated empty state */}
        <div className="relative overflow-hidden rounded-2xl border border-dashed border-gray-300/60 dark:border-gray-600/40 bg-gradient-to-br from-gray-50/80 via-white to-slate-50/80 dark:from-gray-800/50 dark:via-gray-900 dark:to-slate-800/30 p-6">
          <div className="absolute -top-8 -right-8 w-36 h-36 rounded-full bg-indigo-100/40 dark:bg-indigo-900/10 blur-2xl pointer-events-none" />
          <div className="flex items-start gap-5">
            {/* Illustrated shield */}
            <div className="shrink-0 relative">
              <svg width="64" height="72" viewBox="0 0 64 72" fill="none" className="drop-shadow-sm">
                <path d="M32 4L8 14V34C8 50 20 64 32 68C44 64 56 50 56 34V14L32 4Z" fill="url(#shieldGrad)" opacity="0.15" />
                <path d="M32 4L8 14V34C8 50 20 64 32 68C44 64 56 50 56 34V14L32 4Z" stroke="url(#shieldStroke)" strokeWidth="1.5" fill="none" strokeDasharray="4 3" />
                <defs>
                  <linearGradient id="shieldGrad" x1="8" y1="4" x2="56" y2="68" gradientUnits="userSpaceOnUse">
                    <stop stopColor="#6366f1" /><stop offset="1" stopColor="#0ea5e9" />
                  </linearGradient>
                  <linearGradient id="shieldStroke" x1="8" y1="4" x2="56" y2="68" gradientUnits="userSpaceOnUse">
                    <stop stopColor="#6366f1" /><stop offset="1" stopColor="#0ea5e9" />
                  </linearGradient>
                </defs>
                <circle cx="32" cy="36" r="10" stroke="#6366f1" strokeWidth="1.2" strokeDasharray="3 2" fill="none" opacity="0.5" />
                <line x1="28" y1="32" x2="36" y2="40" stroke="#6366f1" strokeWidth="1.5" strokeLinecap="round" opacity="0.4" />
                <line x1="36" y1="32" x2="28" y2="40" stroke="#0ea5e9" strokeWidth="1.5" strokeLinecap="round" opacity="0.4" />
              </svg>
            </div>
            <div className="flex-1">
              <p className="text-sm font-semibold text-gray-700 dark:text-gray-200 mb-1">
                No security checks available
              </p>
              <p className="text-[11px] text-gray-500 dark:text-gray-400 leading-relaxed mb-3">
                Security checks run automatically for <strong>RDS instances</strong> connected to your AWS account.
                {instanceSource === 'agent'
                  ? ' This is an agent-monitored instance — AWS-level security checks (IAM auth, encryption, public access) are not available for it.'
                  : ' Checks may take a few minutes to populate after initial connection.'}
              </p>
              <button className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold bg-indigo-600 text-white hover:bg-indigo-700 transition-colors shadow-sm shadow-indigo-200 dark:shadow-indigo-900/30">
                <i className="fas fa-gear text-[10px]" />
                Configure Security Checks
                <i className="fas fa-arrow-right text-[9px]" />
              </button>
            </div>
          </div>
        </div>

        {/* What we check catalogue */}
        <div className="space-y-3">
          <p className="text-[11px] font-semibold text-gray-400 dark:text-gray-500 uppercase tracking-widest">
            What we check
          </p>
          <div className="grid grid-cols-2 gap-2.5">
            {SECURITY_CATALOGUE.map(cat => (
              <div key={cat.category} className={`rounded-xl border border-gray-200/50 dark:border-gray-700/30 ${cat.bg} p-3`}>
                <div className="flex items-center gap-1.5 mb-2">
                  <i className={`fas ${cat.icon} ${cat.color} text-[11px]`} />
                  <span className="text-[10px] font-bold text-gray-600 dark:text-gray-300 uppercase tracking-wider">{cat.category}</span>
                </div>
                <ul className="space-y-1.5">
                  {cat.checks.map(chk => (
                    <li key={chk.name} className="flex items-start gap-1.5">
                      <i className="fas fa-circle-dot text-[7px] text-gray-400 mt-1 shrink-0" />
                      <div>
                        <span className="text-[10px] font-medium text-gray-700 dark:text-gray-300">{chk.name}</span>
                        <p className="text-[9px] text-gray-400 leading-snug mt-0.5">{chk.desc}</p>
                      </div>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        </div>
      </div>
    );
  }

  // ── Data present state ──────────────────────────────────────────────────────
  return (
    <div className="space-y-4">

      {/* ── Security posture header ── */}
      <div className="relative overflow-hidden rounded-2xl border border-indigo-200/50 dark:border-indigo-800/20 bg-gradient-to-br from-slate-50/90 via-white to-indigo-50/60 dark:from-slate-900/60 dark:via-gray-900 dark:to-indigo-950/30 p-4 shadow-sm">
        <div className="absolute -top-6 -right-6 w-28 h-28 rounded-full bg-indigo-300/10 blur-2xl pointer-events-none" />
        <div className="flex items-center gap-4">
          {/* Circular score */}
          {score !== null && (
            <Tip text={`Security Posture Score out of 100. Calculated as: (PASS + WARN×0.5) ÷ total checks × 100. Strong ≥ 80, Fair 50–79, At Risk < 50.`} wide>
              <div className="relative shrink-0 w-16 h-16">
                <svg viewBox="0 0 36 36" className="w-full h-full -rotate-90">
                  <circle cx="18" cy="18" r="15" fill="none" stroke="currentColor" strokeWidth="2.5" className="text-gray-200 dark:text-gray-700" />
                  <circle cx="18" cy="18" r="15" fill="none" strokeWidth="2.5"
                    stroke={score >= 80 ? '#10b981' : score >= 50 ? '#f59e0b' : '#ef4444'}
                    strokeDasharray={`${(score / 100) * 94.25} 94.25`}
                    strokeLinecap="round"
                    style={{ filter: `drop-shadow(0 0 3px ${score >= 80 ? '#10b981' : score >= 50 ? '#f59e0b' : '#ef4444'}40)` }}
                  />
                </svg>
                <div className="absolute inset-0 flex flex-col items-center justify-center">
                  <span className={`text-sm font-black tabular-nums ${scoreColor}`}>{score}</span>
                  <span className="text-[8px] text-gray-400 font-medium">/ 100</span>
                </div>
              </div>
            </Tip>
          )}
          <div className="flex-1">
            <div className="flex items-center gap-2 mb-1">
              <p className="text-sm font-bold text-gray-800 dark:text-gray-100">Security Posture</p>
              {score !== null && (
                <span className={`text-[10px] font-semibold px-1.5 py-0.5 rounded-md ${
                  score >= 80 ? 'bg-emerald-100 dark:bg-emerald-900/30 text-emerald-700 dark:text-emerald-400'
                  : score >= 50 ? 'bg-amber-100 dark:bg-amber-900/30 text-amber-700 dark:text-amber-400'
                  : 'bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-400'
                }`}>{scoreLabel}</span>
              )}
            </div>
            <div className="flex items-center gap-3 text-xs">
              {failCount > 0 && (
                <span className="flex items-center gap-1 text-red-600 dark:text-red-400 font-semibold">
                  <i className="fas fa-circle-xmark text-[10px]" />{failCount} failed
                </span>
              )}
              {warnCount > 0 && (
                <span className="flex items-center gap-1 text-amber-600 dark:text-amber-400 font-semibold">
                  <i className="fas fa-triangle-exclamation text-[10px]" />{warnCount} warnings
                </span>
              )}
              {passCount > 0 && (
                <span className="flex items-center gap-1 text-emerald-600 dark:text-emerald-400">
                  <i className="fas fa-circle-check text-[10px]" />{passCount} passed
                </span>
              )}
              {highCount > 0 && (
                <span className="ml-auto flex items-center gap-1 text-[10px] font-semibold text-red-500 dark:text-red-400 bg-red-50 dark:bg-red-900/20 px-1.5 py-0.5 rounded-md border border-red-200/50 dark:border-red-800/30">
                  <i className="fas fa-bolt text-[8px]" />{highCount} high severity
                </span>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* ── Status filter pills ── */}
      <div className="flex items-center gap-2 flex-wrap">
        {([
          { k: 'ALL'  as const, label: `All (${total})`,        cls: 'text-gray-600 dark:text-gray-300 border-gray-300/60 dark:border-gray-600/50', active: 'bg-gray-700 dark:bg-gray-600 text-white border-gray-700',   tip: 'Show all security checks regardless of status.' },
          { k: 'FAIL' as const, label: `Failed (${failCount})`, cls: 'text-red-600 dark:text-red-400 border-red-200/60 dark:border-red-800/40 bg-red-50/60 dark:bg-red-900/10', active: 'bg-red-600 text-white border-red-600',   tip: 'Check failed — the issue was detected and immediate remediation is recommended.' },
          { k: 'WARN' as const, label: `Warn (${warnCount})`,   cls: 'text-amber-600 dark:text-amber-400 border-amber-200/60 dark:border-amber-800/40 bg-amber-50/60 dark:bg-amber-900/10', active: 'bg-amber-500 text-white border-amber-500', tip: 'Warning — the check raised a concern that should be reviewed. Not immediately critical but worth addressing.' },
          { k: 'PASS' as const, label: `Passed (${passCount})`, cls: 'text-emerald-600 dark:text-emerald-400 border-emerald-200/60 dark:border-emerald-800/40 bg-emerald-50/60 dark:bg-emerald-900/10', active: 'bg-emerald-600 text-white border-emerald-600', tip: 'Check passed — no issue detected. No action required.' },
        ]).map(f => (
          <Tip key={f.k} text={f.tip} wide>
            <button onClick={() => setStatusFilter(f.k)}
              className={`px-2.5 py-1 rounded-full text-[10px] font-semibold border transition-all duration-150 ${statusFilter === f.k ? f.active : f.cls}`}
            >
              {f.label}
            </button>
          </Tip>
        ))}
      </div>

      {/* ── Audit checklist ── */}
      <div className="space-y-2">
        {filtered.length === 0 ? (
          <div className="text-center py-8 text-xs text-gray-400">No checks match the current filter.</div>
        ) : filtered.map((c, i) => {
          const isExpanded = expandedIdx.has(i);
          const remediation = getRemediation(c.checkName);

          const statusCfg =
            c.status === 'FAIL' ? { icon: 'fa-circle-xmark',        iconCls: 'text-red-500',              rowCls: 'border-red-200/40 dark:border-red-800/20 bg-red-50/20 dark:bg-red-900/5',     badge: 'bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-400 border-red-200/60 dark:border-red-700/40',   glow: 'shadow-[0_0_6px_#ef444430]' }
            : c.status === 'WARN' ? { icon: 'fa-triangle-exclamation', iconCls: 'text-amber-500',           rowCls: 'border-amber-200/40 dark:border-amber-800/20 bg-amber-50/20 dark:bg-amber-900/5', badge: 'bg-amber-100 dark:bg-amber-900/30 text-amber-700 dark:text-amber-400 border-amber-200/60 dark:border-amber-700/40', glow: '' }
            :                       { icon: 'fa-circle-check',          iconCls: 'text-emerald-500',         rowCls: 'border-gray-200/40 dark:border-gray-700/30 bg-white/60 dark:bg-gray-800/30',   badge: 'bg-emerald-100 dark:bg-emerald-900/30 text-emerald-700 dark:text-emerald-400 border-emerald-200/60 dark:border-emerald-700/40', glow: '' };

          const sevCfg =
            c.severity === 'HIGH'   ? { cls: 'bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-400 border-red-200/60 dark:border-red-700/40',         icon: 'fa-bolt' }
            : c.severity === 'MEDIUM' ? { cls: 'bg-orange-100 dark:bg-orange-900/30 text-orange-700 dark:text-orange-400 border-orange-200/60 dark:border-orange-700/40', icon: 'fa-fire-flame-simple' }
            :                           { cls: 'bg-blue-100 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400 border-blue-200/60 dark:border-blue-700/40',       icon: 'fa-circle-info' };

          return (
            <div
              key={i}
              className={`rounded-xl border transition-all duration-200 ${statusCfg.rowCls} ${c.status === 'FAIL' ? statusCfg.glow : ''}`}
            >
              {/* Row header — always visible */}
              <button
                onClick={() => toggle(i)}
                className="w-full flex items-center gap-3 px-3.5 py-3 text-left"
              >
                {/* Status icon */}
                <i className={`fas ${statusCfg.icon} ${statusCfg.iconCls} text-sm shrink-0`} />

                {/* Check name */}
                <span className="flex-1 text-xs font-semibold text-gray-800 dark:text-gray-100 min-w-0 truncate">
                  {c.checkName}
                </span>

                {/* Severity badge */}
                <Tip text={c.severity === 'HIGH' ? 'High severity — exploit is readily available or the risk is critical. Address immediately.' : c.severity === 'MEDIUM' ? 'Medium severity — exploitation requires specific conditions. Review and plan remediation.' : 'Low severity — risk is minor or mitigated by other controls. Address when convenient.'} wide>
                  <span className={`inline-flex items-center gap-1 px-1.5 py-0.5 rounded-md text-[9px] font-semibold border shrink-0 ${sevCfg.cls}`}>
                    <i className={`fas ${sevCfg.icon} text-[8px]`} />{c.severity}
                  </span>
                </Tip>

                {/* Status badge */}
                <Tip text={c.status === 'FAIL' ? 'Check failed — issue confirmed. Immediate remediation recommended.' : c.status === 'WARN' ? 'Warning — potential issue detected. Review the details and monitor.' : 'Check passed — no issue found. No action required.'} wide>
                  <span className={`inline-flex items-center gap-1 px-1.5 py-0.5 rounded-md text-[9px] font-semibold border shrink-0 ${statusCfg.badge}`}>
                    {c.status}
                  </span>
                </Tip>

                {/* Expand chevron */}
                <i className={`fas fa-chevron-${isExpanded ? 'up' : 'down'} text-[9px] text-gray-400 shrink-0 transition-transform duration-200`} />
              </button>

              {/* Expanded detail + remediation */}
              {isExpanded && (
                <div className="px-3.5 pb-3.5 space-y-2.5 border-t border-gray-100/60 dark:border-gray-700/30 pt-2.5">
                  {/* Details */}
                  {c.details && (
                    <div className="flex items-start gap-2">
                      <i className="fas fa-circle-info text-gray-400 text-[10px] mt-0.5 shrink-0" />
                      <p className="text-[11px] text-gray-600 dark:text-gray-400 leading-snug">{c.details}</p>
                    </div>
                  )}

                  {/* Remediation steps */}
                  {remediation && c.status !== 'PASS' && (
                    <div className="flex items-start gap-2 px-3 py-2.5 rounded-lg bg-indigo-50/80 dark:bg-indigo-950/30 border border-indigo-200/50 dark:border-indigo-800/20">
                      <i className="fas fa-screwdriver-wrench text-indigo-500 text-[10px] mt-0.5 shrink-0" />
                      <div>
                        <p className="text-[9px] font-bold text-indigo-500 dark:text-indigo-400 uppercase tracking-wider mb-1">Remediation</p>
                        <p className="text-[11px] text-indigo-700 dark:text-indigo-300 leading-snug">{remediation}</p>
                      </div>
                    </div>
                  )}

                  {/* Region tag */}
                  {c.region && (
                    <span className="inline-flex items-center gap-1 text-[9px] text-gray-400 bg-gray-100/70 dark:bg-gray-700/40 px-1.5 py-0.5 rounded">
                      <i className="fas fa-location-dot text-[8px]" />{c.region}
                    </span>
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>

      {/* "What we check" compact catalogue — always shown at bottom */}
      <details className="group">
        <summary className="flex items-center gap-1.5 text-[10px] font-semibold text-gray-400 dark:text-gray-500 uppercase tracking-widest cursor-pointer list-none hover:text-gray-600 dark:hover:text-gray-300 transition-colors select-none">
          <i className="fas fa-chevron-right text-[8px] group-open:rotate-90 transition-transform duration-200" />
          What we check ({SECURITY_CATALOGUE.reduce((s, c) => s + c.checks.length, 0)} checks)
        </summary>
        <div className="mt-3 grid grid-cols-2 gap-2">
          {SECURITY_CATALOGUE.map(cat => (
            <div key={cat.category} className={`rounded-xl border border-gray-200/50 dark:border-gray-700/30 ${cat.bg} p-3`}>
              <div className="flex items-center gap-1.5 mb-2">
                <i className={`fas ${cat.icon} ${cat.color} text-[10px]`} />
                <span className="text-[10px] font-bold text-gray-600 dark:text-gray-300 uppercase tracking-wider">{cat.category}</span>
              </div>
              <ul className="space-y-1">
                {cat.checks.map(chk => (
                  <li key={chk.name} className="flex items-center gap-1.5 text-[10px] text-gray-600 dark:text-gray-400">
                    <i className="fas fa-circle-dot text-[7px] text-gray-400 shrink-0" />
                    {chk.name}
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
      </details>
    </div>
  );
}

// ── Cost Tab ──────────────────────────────────────────────────────────────────
function CostTab({ rd, instanceIdle, instanceRightsizing, costs, agentMetrics, instanceSource }: {
  rd:                  DbInstance | undefined;
  instanceIdle:        DbIdleInstance | undefined;
  instanceRightsizing: DbRightsizing | undefined;
  costs:               DbCost[];
  agentMetrics:        AgentInstanceMetrics | undefined;
  instanceSource:      'rds' | 'agent';
}) {
  const [riYears, setRiYears] = useState<1|3>(1);

  // ── Cost figures ──────────────────────────────────────────────────────────
  const monthlyRds     = rd?.estimatedMonthlyCost ?? 0;
  const hourlyRds      = monthlyRds / 730;
  const ec2Hourly      = agentMetrics?.ec2HourlyPrice ?? 0;
  const ec2Monthly     = ec2Hourly * 730;

  // Use RDS cost if available, else EC2 agent cost
  const hasRealCost    = monthlyRds > 0;
  const hasEc2Cost     = ec2Hourly > 0 && !hasRealCost;
  const monthlyCost    = hasRealCost ? monthlyRds : ec2Monthly;
  const hourlyCost     = hasRealCost ? hourlyRds  : ec2Hourly;
  const dailyCost      = hourlyCost * 24;

  // Reserved instance estimated savings
  const riDiscount     = riYears === 1 ? 0.30 : 0.45;
  const riSavings      = monthlyCost * riDiscount;
  const riMonthlyCost  = monthlyCost * (1 - riDiscount);

  // Savings opportunities total
  const idleSavings    = instanceIdle?.potentialSavings ?? 0;
  const rsSavings      = instanceRightsizing?.estimatedSavings ?? 0;
  const totalSavings   = idleSavings + rsSavings;

  // Cost breakdown bar colours
  const barGradients = [
    'from-indigo-500 to-violet-500',
    'from-sky-500 to-cyan-400',
    'from-emerald-500 to-teal-400',
    'from-amber-500 to-orange-400',
    'from-pink-500 to-rose-400',
  ];

  return (
    <div className="space-y-5">

      {/* ── Cost Intelligence Hero ── */}
      {(hasRealCost || hasEc2Cost) ? (
        <div className="relative overflow-hidden rounded-2xl border border-indigo-200/50 dark:border-indigo-800/30 bg-gradient-to-br from-indigo-50 via-white to-sky-50 dark:from-indigo-950/40 dark:via-gray-900 dark:to-sky-950/30 p-5 shadow-sm">
          {/* Decorative glow */}
          <div className="absolute -top-6 -right-6 w-32 h-32 rounded-full bg-indigo-400/10 dark:bg-indigo-400/5 blur-2xl pointer-events-none" />

          <div className="flex items-start justify-between gap-4 mb-4">
            <div>
              <p className="text-[10px] font-semibold text-indigo-400 dark:text-indigo-500 uppercase tracking-widest mb-1">
                {hasRealCost ? 'RDS · Estimated Cost' : 'EC2 · Estimated Cost'}
              </p>
              <div className="flex items-baseline gap-2">
                <span className="text-3xl font-black text-indigo-700 dark:text-indigo-300 tabular-nums">
                  {fmt$(monthlyCost)}
                </span>
                <span className="text-sm text-indigo-400 dark:text-indigo-500 font-medium">/mo</span>
              </div>
              <div className="flex items-center gap-3 mt-1.5">
                <span className="text-[11px] text-indigo-500 dark:text-indigo-400 tabular-nums">
                  {fmt$(hourlyCost)}/hr
                </span>
                <span className="w-1 h-1 rounded-full bg-indigo-300 dark:bg-indigo-700" />
                <span className="text-[11px] text-indigo-500 dark:text-indigo-400 tabular-nums">
                  {fmt$(dailyCost)}/day
                </span>
                <span className="w-1 h-1 rounded-full bg-indigo-300 dark:bg-indigo-700" />
                <span className="text-[11px] text-indigo-500 dark:text-indigo-400 tabular-nums">
                  {fmt$(monthlyCost * 12)}/yr
                </span>
              </div>
            </div>
            {rd && (
              <div className="flex flex-col gap-1.5 text-right shrink-0">
                <span className="text-[10px] text-gray-400 uppercase tracking-wider">Instance</span>
                <code className="text-xs font-semibold text-gray-700 dark:text-gray-300">{rd.instanceClass}</code>
                <span className="text-[10px] text-gray-400">{rd.engine} {rd.engineVersion}</span>
                <span className="text-[10px] text-gray-400">{rd.allocatedStorage} GB · {rd.storageType}</span>
              </div>
            )}
            {hasEc2Cost && agentMetrics && (
              <div className="flex flex-col gap-1.5 text-right shrink-0">
                <span className="text-[10px] text-gray-400 uppercase tracking-wider">EC2 Host</span>
                <code className="text-xs font-semibold text-gray-700 dark:text-gray-300">{agentMetrics.ec2InstanceType}</code>
                {agentMetrics.ec2InstanceId && (
                  <span className="text-[10px] font-mono text-gray-400">{agentMetrics.ec2InstanceId}</span>
                )}
                {agentMetrics.region && (
                  <span className="text-[10px] text-gray-400">{agentMetrics.region}</span>
                )}
              </div>
            )}
          </div>

          {/* Hourly / Daily / Monthly cost bars — proportional */}
          <div className="space-y-2">
            {[
              { label: 'Hourly',  val: hourlyCost,       max: hourlyCost,      pct: 100 / 24 },
              { label: 'Daily',   val: dailyCost,         max: dailyCost,       pct: 100 / 31 },
              { label: 'Monthly', val: monthlyCost,       max: monthlyCost,     pct: 100 },
            ].map(r => (
              <div key={r.label} className="flex items-center gap-2">
                <span className="text-[10px] text-indigo-400 dark:text-indigo-500 w-12 shrink-0">{r.label}</span>
                <div className="flex-1 h-1.5 bg-indigo-100 dark:bg-indigo-900/40 rounded-full overflow-hidden">
                  <div
                    className="h-full rounded-full bg-gradient-to-r from-indigo-500 to-sky-400 transition-all duration-700"
                    style={{ width: `${r.pct}%` }}
                  />
                </div>
                <span className="text-[11px] font-semibold text-indigo-700 dark:text-indigo-300 tabular-nums w-16 text-right shrink-0">{fmt$(r.val)}</span>
              </div>
            ))}
          </div>
        </div>
      ) : (
        /* ── No cost data explainer ── */
        <div className="rounded-2xl border border-dashed border-gray-300/60 dark:border-gray-600/40 bg-gray-50/60 dark:bg-gray-800/30 p-5 space-y-4">
          <div className="flex items-start gap-3">
            <div className="w-9 h-9 rounded-xl bg-gray-100 dark:bg-gray-700/50 flex items-center justify-center shrink-0">
              <i className="fas fa-circle-question text-gray-400 text-sm" />
            </div>
            <div>
              <p className="text-sm font-semibold text-gray-700 dark:text-gray-200 mb-1">Why is cost data unavailable?</p>
              <p className="text-xs text-gray-500 dark:text-gray-400 leading-relaxed">
                This is an <strong>agent-monitored</strong> instance. Cost estimates come from the AWS RDS Pricing API and require an active RDS connection in XamOps.
                Agent-monitored databases (EC2, on-prem, Aurora Serverless) do not have an RDS cost stream.
              </p>
            </div>
          </div>
          <div className="grid grid-cols-2 gap-2">
            {[
              { icon: 'fa-cloud',          label: 'Connect AWS account', desc: 'Link an AWS account with Cost Explorer enabled to pull RDS pricing automatically.' },
              { icon: 'fa-tag',            label: 'Tag your resources',  desc: 'Add aws:cost-center tags to your instances so XamOps can attribute costs per service.' },
            ].map(c => (
              <div key={c.label} className="flex items-start gap-2.5 px-3 py-2.5 rounded-xl bg-white/70 dark:bg-gray-700/30 border border-gray-200/50 dark:border-gray-600/30">
                <i className={`fas ${c.icon} text-indigo-400 text-[11px] mt-0.5 shrink-0`} />
                <div>
                  <p className="text-[11px] font-semibold text-gray-700 dark:text-gray-300">{c.label}</p>
                  <p className="text-[10px] text-gray-400 mt-0.5 leading-snug">{c.desc}</p>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* ── Savings Opportunities ── */}
      {(instanceIdle || instanceRightsizing || (hasRealCost && monthlyCost > 0)) && (
        <div className="space-y-3">
          <div className="flex items-center gap-2">
            <h3 className="text-[11px] font-semibold text-gray-500 dark:text-gray-400 uppercase tracking-wider flex-1">
              <i className="fas fa-piggy-bank text-emerald-500 mr-1.5" /> Savings Opportunities
            </h3>
            {totalSavings > 0 && (
              <span className="px-2 py-0.5 rounded-full text-[10px] font-semibold bg-emerald-100 dark:bg-emerald-900/30 text-emerald-700 dark:text-emerald-400 border border-emerald-200/60 dark:border-emerald-700/40">
                Up to {fmt$(totalSavings)}/mo
              </span>
            )}
          </div>

          {/* RI card */}
          {hasRealCost && monthlyCost > 0 && (
            <div className="relative overflow-hidden rounded-xl border border-violet-200/60 dark:border-violet-800/30 bg-gradient-to-br from-violet-50/80 to-white dark:from-violet-950/30 dark:to-gray-900 p-4 shadow-sm">
              <div className="absolute -bottom-4 -right-4 w-20 h-20 rounded-full bg-violet-400/8 blur-xl pointer-events-none" />
              <div className="flex items-start gap-3 mb-3">
                <div className="w-8 h-8 rounded-lg bg-violet-100 dark:bg-violet-900/40 flex items-center justify-center shrink-0">
                  <i className="fas fa-calendar-check text-violet-500 text-xs" />
                </div>
                <div className="flex-1">
                  <p className="text-xs font-semibold text-violet-800 dark:text-violet-300">Reserved Instance</p>
                  <p className="text-[10px] text-violet-500 dark:text-violet-400 mt-0.5">Pre-commit for 1 or 3 years and save up to 45%</p>
                </div>
                <div className="text-right shrink-0">
                  <div className="text-base font-black text-emerald-600 dark:text-emerald-400 tabular-nums">{fmt$(riSavings)}/mo</div>
                  <Tip text={`AWS Reserved Instance pricing reduces On-Demand cost by ~${Math.round(riDiscount * 100)}% in exchange for a ${riYears}-year commitment. No upfront payment required with Standard No-Upfront RI.`} wide>
                    <div className="text-[10px] text-gray-400">{Math.round(riDiscount * 100)}% discount</div>
                  </Tip>
                </div>
              </div>
              {/* Year toggle */}
              <div className="flex items-center gap-2 mb-3">
                <span className="text-[10px] text-gray-500 dark:text-gray-400">Term:</span>
                {([1, 3] as const).map(y => (
                  <Tip key={y} text={y === 1 ? '1-year Reserved Instance: ~30% discount vs On-Demand. Lower commitment, lower savings.' : '3-year Reserved Instance: ~45% discount vs On-Demand. Best savings if the instance will run long-term.'} wide>
                    <button onClick={() => setRiYears(y)}
                      className={`px-2.5 py-1 rounded-lg text-[10px] font-semibold border transition-all duration-150 ${
                        riYears === y
                          ? 'bg-violet-600 text-white border-violet-600 shadow-sm'
                          : 'text-violet-600 dark:text-violet-400 border-violet-200/60 dark:border-violet-700/40 hover:bg-violet-50 dark:hover:bg-violet-900/20'
                      }`}
                    >
                      {y}yr {y === 1 ? '(~30%)' : '(~45%)'}
                    </button>
                  </Tip>
                ))}
              </div>
              <div className="grid grid-cols-3 gap-2 text-center">
                {[
                  { label: 'Current',            val: fmt$(monthlyCost),              sub: '/mo',  cls: 'text-gray-700 dark:text-gray-300',               tip: 'Current On-Demand monthly cost — you pay this rate with no commitment.' },
                  { label: 'With RI',             val: fmt$(riMonthlyCost),            sub: '/mo',  cls: 'text-violet-700 dark:text-violet-300 font-bold',  tip: `Estimated monthly cost after applying the ${Math.round(riDiscount * 100)}% Reserved Instance discount.` },
                  { label: `Save (${riYears}yr)`, val: fmt$(riSavings * 12 * riYears), sub: ' total', cls: 'text-emerald-700 dark:text-emerald-400 font-bold', tip: `Total savings over the full ${riYears}-year RI term compared to staying On-Demand.` },
                ].map(c => (
                  <div key={c.label} className="bg-white/70 dark:bg-gray-800/50 rounded-lg px-2 py-2">
                    <Tip text={c.tip} wide>
                      <div className="text-[9px] text-gray-400 uppercase tracking-wider mb-1">{c.label}</div>
                    </Tip>
                    <div className={`text-sm tabular-nums ${c.cls}`}>{c.val}<span className="text-[9px] text-gray-400 ml-0.5">{c.sub}</span></div>
                  </div>
                ))}
              </div>
              <p className="text-[9px] text-gray-400 mt-2.5 leading-snug">
                <i className="fas fa-circle-info mr-1" />Estimate based on standard RI discounts. Actual savings depend on instance type, region, and AWS pricing at purchase time.
              </p>
            </div>
          )}

          {/* Idle detection result */}
          {instanceIdle ? (
            <div className="relative overflow-hidden rounded-xl border border-amber-200/60 dark:border-amber-800/30 bg-gradient-to-br from-amber-50/80 to-white dark:from-amber-950/30 dark:to-gray-900 p-4">
              <div className="flex items-start gap-3 mb-3">
                <div className="w-8 h-8 rounded-lg bg-amber-100 dark:bg-amber-900/40 flex items-center justify-center shrink-0">
                  <i className="fas fa-moon text-amber-500 text-xs" />
                </div>
                <div className="flex-1">
                  <p className="text-xs font-semibold text-amber-800 dark:text-amber-300">Idle Instance Detected</p>
                  <p className="text-[10px] text-amber-600 dark:text-amber-400 mt-0.5">Low usage sustained over 7 days</p>
                </div>
                <div className="text-right shrink-0">
                  <div className="text-base font-black text-emerald-600 dark:text-emerald-400 tabular-nums">{fmt$(idleSavings)}/mo</div>
                  <div className="text-[10px] text-gray-400">potential savings</div>
                </div>
              </div>
              <div className="grid grid-cols-2 gap-2 mb-3">
                <div className="bg-white/70 dark:bg-gray-800/50 rounded-lg px-3 py-2 text-center">
                  <Tip text="Average CPU utilization over the last 7 days. Instances below 5% are classified as idle — they are likely over-provisioned or unused." wide>
                    <div className="text-[9px] text-gray-400 uppercase tracking-wider mb-0.5">Avg CPU</div>
                  </Tip>
                  <div className="text-sm font-bold text-amber-600 dark:text-amber-400 tabular-nums">{instanceIdle.avgCpuPercent.toFixed(1)}%</div>
                  <div className="text-[9px] text-gray-400">threshold: &lt;5%</div>
                </div>
                <div className="bg-white/70 dark:bg-gray-800/50 rounded-lg px-3 py-2 text-center">
                  <Tip text="Average number of database connections over the last 7 days. Fewer than 2 average connections suggests the instance is rarely used." wide>
                    <div className="text-[9px] text-gray-400 uppercase tracking-wider mb-0.5">Avg Connections</div>
                  </Tip>
                  <div className="text-sm font-bold text-amber-600 dark:text-amber-400 tabular-nums">{instanceIdle.avgConnections.toFixed(1)}</div>
                  <div className="text-[9px] text-gray-400">threshold: &lt;2</div>
                </div>
              </div>
              {instanceIdle.recommendation && (
                <div className="flex items-start gap-2 px-3 py-2 rounded-lg bg-amber-100/70 dark:bg-amber-900/20 border border-amber-200/60 dark:border-amber-700/30">
                  <i className="fas fa-lightbulb text-amber-500 text-[10px] mt-0.5 shrink-0" />
                  <p className="text-[11px] text-amber-700 dark:text-amber-300 leading-snug">{instanceIdle.recommendation}</p>
                </div>
              )}
            </div>
          ) : (
            <div className="flex items-center gap-3 px-4 py-3 rounded-xl bg-emerald-50/60 dark:bg-emerald-950/20 border border-emerald-200/50 dark:border-emerald-800/20">
              <div className="w-7 h-7 rounded-lg bg-emerald-100 dark:bg-emerald-900/40 flex items-center justify-center shrink-0">
                <i className="fas fa-check text-emerald-500 text-[10px]" />
              </div>
              <div>
                <p className="text-xs font-semibold text-emerald-700 dark:text-emerald-400">Instance is Active</p>
                <p className="text-[10px] text-emerald-600/70 dark:text-emerald-500/70 mt-0.5">
                  Evaluated: avg CPU &gt;5% or connections &gt;2 over last 7 days. No idle savings available.
                </p>
              </div>
            </div>
          )}

          {/* Right-sizing result */}
          {instanceRightsizing ? (
            <div className="relative overflow-hidden rounded-xl border border-pink-200/60 dark:border-pink-800/30 bg-gradient-to-br from-pink-50/80 to-white dark:from-pink-950/30 dark:to-gray-900 p-4">
              <div className="flex items-start gap-3 mb-3">
                <div className="w-8 h-8 rounded-lg bg-pink-100 dark:bg-pink-900/40 flex items-center justify-center shrink-0">
                  <i className="fas fa-arrows-left-right text-pink-500 text-xs" />
                </div>
                <div className="flex-1">
                  <p className="text-xs font-semibold text-pink-800 dark:text-pink-300">Right-sizing Opportunity</p>
                  <p className="text-[10px] text-pink-500 dark:text-pink-400 mt-0.5">Instance appears over-provisioned</p>
                </div>
                <div className="text-right shrink-0">
                  <div className="text-base font-black text-emerald-600 dark:text-emerald-400 tabular-nums">{fmt$(rsSavings)}/mo</div>
                  <div className="text-[10px] text-gray-400">estimated savings</div>
                </div>
              </div>
              {/* Class migration arrow */}
              <div className="flex items-center gap-2 mb-3 bg-white/70 dark:bg-gray-800/50 rounded-lg px-4 py-3">
                <div className="flex-1 text-center">
                  <div className="text-[9px] text-gray-400 uppercase tracking-wider mb-1">Current</div>
                  <code className="text-sm font-bold text-gray-700 dark:text-gray-300">{instanceRightsizing.currentInstanceClass}</code>
                  <div className="text-[10px] text-gray-500 mt-0.5">{fmt$(instanceRightsizing.currentMonthlyCost)}/mo</div>
                </div>
                <div className="flex flex-col items-center gap-0.5 px-2">
                  <i className="fas fa-arrow-right text-pink-400 text-sm" />
                  <span className="text-[8px] text-pink-400 font-semibold whitespace-nowrap">{instanceRightsizing.avgCpuPercent.toFixed(1)}% avg CPU</span>
                </div>
                <div className="flex-1 text-center">
                  <div className="text-[9px] text-gray-400 uppercase tracking-wider mb-1">Recommended</div>
                  <code className="text-sm font-bold text-emerald-600 dark:text-emerald-400">{instanceRightsizing.recommendedInstanceClass}</code>
                  <div className="text-[10px] text-emerald-600 dark:text-emerald-400 mt-0.5">{fmt$(instanceRightsizing.recommendedMonthlyCost)}/mo</div>
                </div>
              </div>
              {instanceRightsizing.reason && (
                <div className="flex items-start gap-2 px-3 py-2 rounded-lg bg-pink-100/70 dark:bg-pink-900/20 border border-pink-200/60 dark:border-pink-700/30">
                  <i className="fas fa-lightbulb text-pink-500 text-[10px] mt-0.5 shrink-0" />
                  <p className="text-[11px] text-pink-700 dark:text-pink-300 leading-snug">{instanceRightsizing.reason}</p>
                </div>
              )}
            </div>
          ) : (
            <div className="flex items-center gap-3 px-4 py-3 rounded-xl bg-emerald-50/60 dark:bg-emerald-950/20 border border-emerald-200/50 dark:border-emerald-800/20">
              <div className="w-7 h-7 rounded-lg bg-emerald-100 dark:bg-emerald-900/40 flex items-center justify-center shrink-0">
                <i className="fas fa-check text-emerald-500 text-[10px]" />
              </div>
              <div>
                <p className="text-xs font-semibold text-emerald-700 dark:text-emerald-400">Instance Class Looks Right</p>
                <p className="text-[10px] text-emerald-600/70 dark:text-emerald-500/70 mt-0.5">
                  Evaluated: CPU utilisation vs available vCPUs. No right-sizing savings available at this time.
                </p>
              </div>
            </div>
          )}
        </div>
      )}

      {/* ── Account Cost Breakdown ── */}
      {costs.length > 0 && (
        <div className="space-y-3">
          <h3 className="text-[11px] font-semibold text-gray-500 dark:text-gray-400 uppercase tracking-wider flex items-center gap-1.5">
            <i className="fas fa-chart-bar text-indigo-500 text-[10px]" /> Account Cost Breakdown
            <span className="ml-auto text-[10px] font-normal text-gray-400 normal-case tracking-normal">Month-to-date</span>
          </h3>
          <div className="rounded-xl border border-gray-200/60 dark:border-gray-700/40 overflow-hidden shadow-sm">
            <table className="w-full text-xs">
              <thead className="bg-gray-50/90 dark:bg-gray-800/70 border-b border-gray-200/60 dark:border-gray-700/40">
                <tr>
                  <th className="px-3 py-2 text-left text-[11px] font-semibold text-gray-500 dark:text-gray-400">
                    <Tip text="AWS service name (e.g. Amazon RDS, Amazon EC2, Data Transfer). Sourced from AWS Cost Explorer.">Service</Tip>
                  </th>
                  <th className="px-3 py-2 text-left text-[11px] font-semibold text-gray-500 dark:text-gray-400">
                    <Tip text="AWS region where this cost was incurred (e.g. us-east-1). Multi-region accounts will show multiple rows per service.">Region</Tip>
                  </th>
                  <th className="px-3 py-2 text-right text-[11px] font-semibold text-gray-500 dark:text-gray-400">
                    <Tip text="Month-to-date cost for this service in this region. Refreshed daily from AWS Cost Explorer.">Cost</Tip>
                  </th>
                  <th className="px-3 py-2 text-right text-[11px] font-semibold text-gray-500 dark:text-gray-400">
                    <Tip text="This service's share of your total AWS spend shown here. Helps identify which service is driving the most cost.">Share</Tip>
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100/70 dark:divide-gray-700/30">
                {costs.map((c, i) => (
                  <tr key={i} className="hover:bg-gray-50/60 dark:hover:bg-gray-700/20 transition-colors">
                    <td className="px-3 py-2.5">
                      <div className="flex items-center gap-2">
                        <div className={`w-2 h-2 rounded-full bg-gradient-to-br ${barGradients[i % barGradients.length]} shrink-0`} />
                        <span className="font-medium text-gray-700 dark:text-gray-300">{c.service}</span>
                      </div>
                    </td>
                    <td className="px-3 py-2.5 text-gray-500 dark:text-gray-400">{c.region || '—'}</td>
                    <td className="px-3 py-2.5 text-right font-semibold text-gray-800 dark:text-gray-200 tabular-nums">{fmt$(c.cost)}</td>
                    <td className="px-3 py-2.5">
                      <div className="flex items-center justify-end gap-2">
                        <div className="w-16 h-1.5 bg-gray-200/80 dark:bg-gray-700/60 rounded-full overflow-hidden">
                          <div
                            className={`h-full rounded-full bg-gradient-to-r ${barGradients[i % barGradients.length]} transition-all duration-500`}
                            style={{ width: `${Math.min(c.percentOfTotal, 100)}%` }}
                          />
                        </div>
                        <span className="text-[11px] font-medium text-gray-600 dark:text-gray-400 tabular-nums w-8 text-right">{round2(c.percentOfTotal)}%</span>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="px-3 py-2 bg-gray-50/70 dark:bg-gray-800/40 border-t border-gray-100/60 dark:border-gray-700/30">
              <p className="text-[10px] text-gray-400 flex items-center gap-1.5">
                <i className="fas fa-circle-info text-[9px]" />
                Sourced from AWS Cost Explorer — all DB-related services for this account, month-to-date.
              </p>
            </div>
          </div>
        </div>
      )}

      {/* ── No cost data at all (not RDS, no EC2 price, no cost rows) ── */}
      {!hasRealCost && !hasEc2Cost && costs.length === 0 && instanceSource === 'agent' && (
        <div className="rounded-xl border border-gray-200/50 dark:border-gray-700/30 bg-gray-50/60 dark:bg-gray-800/30 px-4 py-3 flex items-center gap-3">
          <i className="fas fa-circle-info text-gray-400 text-xs" />
          <p className="text-xs text-gray-500 dark:text-gray-400 leading-relaxed">
            No cost estimate available. Connect an AWS account or ensure the DB agent reports EC2 pricing to surface cost data here.
          </p>
        </div>
      )}
    </div>
  );
}

// ── Users Tab ─────────────────────────────────────────────────────────────────
function UsersTab({ userActivity, agentHost, rdsEndpoint }: {
  userActivity:   AgentUserActivity[];
  agentHost:      string | undefined;
  rdsEndpoint?:  string;
}) {
  const [search,   setSearch]   = useState('');
  const [sortKey,  setSortKey]  = useState<'username'|'total'|'active'|'idle'|'share'|'queries'|'time'>('total');
  const [sortDir,  setSortDir]  = useState<'desc'|'asc'>('desc');
  const [page,     setPage]     = useState(0);
  const [copied,   setCopied]   = useState<number|null>(null);

  const PAGE_SIZE = 15;

  const matchHost = (dbHost: string | undefined) => {
    if (!dbHost) return false;
    if (dbHost === agentHost) return true;
    const h = dbHost.split(':')[0];
    return !!(h && rdsEndpoint?.includes(h));
  };

  // Filter to current instance
  const all = userActivity.filter(u => matchHost(u.dbHost));

  // Total connections across all users (for share %)
  const totalConnsAll = all.reduce((s, u) => s + u.totalConnections, 0);
  const maxConns      = Math.max(...all.map(u => u.totalConnections), 1);

  // Search
  const searched = search.trim()
    ? all.filter(u => u.username.toLowerCase().includes(search.toLowerCase()))
    : all;

  // Sort
  const d = sortDir === 'desc' ? -1 : 1;
  const sorted = [...searched].sort((a, b) => {
    switch (sortKey) {
      case 'username': return d * a.username.localeCompare(b.username);
      case 'total':    return d * (b.totalConnections - a.totalConnections);
      case 'active':   return d * (b.activeConnections - a.activeConnections);
      case 'idle':     return d * (b.idleConnections - a.idleConnections);
      case 'share':    return d * (b.totalConnections - a.totalConnections);
      case 'queries':  return d * ((b.callCount ?? 0) - (a.callCount ?? 0));
      case 'time':     return d * (b.totalTimeSeconds - a.totalTimeSeconds);
      default: return 0;
    }
  });

  const totalPages = Math.max(1, Math.ceil(sorted.length / PAGE_SIZE));
  const pg         = Math.min(page, totalPages - 1);
  const rows       = sorted.slice(pg * PAGE_SIZE, (pg + 1) * PAGE_SIZE);

  const handleSort = (k: typeof sortKey) => {
    if (k === sortKey) setSortDir(p => p === 'desc' ? 'asc' : 'desc');
    else { setSortKey(k); setSortDir('desc'); }
  };

  // Format cumulative time
  const fmtTime = (s: number) =>
    s >= 3600 ? `${round2(s / 3600)}h`
    : s >= 60  ? `${round2(s / 60)}m`
    : `${round2(s)}s`;

  // Status
  const status = (u: AgentUserActivity) =>
    u.activeConnections > 0           ? { label: 'Active',  cls: 'bg-emerald-100 dark:bg-emerald-900/30 text-emerald-700 dark:text-emerald-400 border-emerald-200/60 dark:border-emerald-700/40', dot: 'bg-emerald-500 shadow-[0_0_4px_#10b981]' }
    : u.idleConnections > 0           ? { label: 'Idle',    cls: 'bg-gray-100 dark:bg-gray-700/50 text-gray-600 dark:text-gray-400 border-gray-200/60 dark:border-gray-600/40',                  dot: 'bg-gray-400' }
    :                                   { label: 'Dormant', cls: 'bg-slate-50 dark:bg-slate-800/40 text-slate-400 dark:text-slate-500 border-slate-200/40 dark:border-slate-700/30',              dot: 'bg-slate-300 dark:bg-slate-600' };

  // Kill SQL
  const killSql = (u: AgentUserActivity) =>
    (u.dbEngine ?? '').toLowerCase().includes('mysql')
      ? `-- Find PID:\nSHOW PROCESSLIST;\n-- Then terminate:\nKILL <pid>;`
      : `-- Terminate all connections for this user:\nSELECT pg_terminate_backend(pid)\nFROM pg_stat_activity\nWHERE usename = '${u.username}'\n  AND pid <> pg_backend_pid();`;

  // Sortable th helper
  const th = (col: typeof sortKey, label: string, right = false, tip?: string) => (
    <th
      key={col}
      onClick={() => handleSort(col)}
      className={`px-3 py-2.5 text-[11px] font-semibold text-gray-500 dark:text-gray-400 cursor-pointer select-none whitespace-nowrap hover:text-indigo-600 dark:hover:text-indigo-400 transition-colors ${right ? 'text-right' : 'text-left'}`}
    >
      <span className={`inline-flex items-center gap-1 ${right ? 'justify-end' : ''}`}>
        {tip ? <Tip text={tip} wide>{label}</Tip> : label}
        {sortKey === col
          ? <i className={`fas fa-arrow-${sortDir === 'desc' ? 'down' : 'up'} text-indigo-500 text-[9px]`} />
          : <i className="fas fa-sort text-gray-300 dark:text-gray-600 text-[9px]" />}
      </span>
    </th>
  );

  // KPI summary values
  const totalActive   = all.reduce((s, u) => s + u.activeConnections, 0);
  const totalIdle     = all.reduce((s, u) => s + u.idleConnections, 0);
  const topUser       = [...all].sort((a, b) => b.totalConnections - a.totalConnections)[0];

  // ── Empty state ───────────────────────────────────────────────────────────────
  if (all.length === 0) {
    return (
      <div className="space-y-4">
        <div className="grid grid-cols-3 gap-2">
          {[
            { icon: 'fa-database',  label: 'Source',  detail: 'pg_stat_activity' },
            { icon: 'fa-plug',      label: 'Engine',  detail: 'PostgreSQL / MySQL' },
            { icon: 'fa-clock',     label: 'Mode',    detail: 'Snapshot at collection' },
          ].map(b => (
            <div key={b.label} className="flex items-center gap-2 px-3 py-2.5 rounded-xl bg-gray-50/80 dark:bg-gray-800/50 border border-gray-200/50 dark:border-gray-700/30">
              <i className={`fas ${b.icon} text-sky-400 text-[11px] shrink-0`} />
              <div>
                <p className="text-[9px] text-gray-400 uppercase tracking-wider">{b.label}</p>
                <p className="text-[11px] font-medium text-gray-600 dark:text-gray-300">{b.detail}</p>
              </div>
            </div>
          ))}
        </div>
        <div className="flex flex-col items-center justify-center py-14 gap-3">
          <svg width="72" height="64" viewBox="0 0 72 64" fill="none" className="text-gray-300 dark:text-gray-600">
            <circle cx="36" cy="22" r="12" stroke="currentColor" strokeWidth="1.5" strokeDasharray="4 3" />
            <path d="M14 54c0-12.15 9.85-22 22-22s22 9.85 22 22" stroke="currentColor" strokeWidth="1.5" strokeDasharray="4 3" fill="none" />
            <circle cx="52" cy="16" r="7" stroke="currentColor" strokeWidth="1.2" strokeDasharray="3 2" fill="none" />
            <circle cx="20" cy="16" r="7" stroke="currentColor" strokeWidth="1.2" strokeDasharray="3 2" fill="none" />
          </svg>
          <p className="text-sm font-medium text-gray-400 dark:text-gray-500">No user activity data yet</p>
          <p className="text-[11px] text-gray-400 dark:text-gray-500 text-center max-w-xs leading-relaxed">
            Deploy or upgrade the XamOps DB agent to collect per-user connection stats.
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-4">

      {/* ── Source info chips ── */}
      <div className="grid grid-cols-3 gap-2">
        {[
          { icon: 'fa-database',  label: 'PG source',   detail: 'pg_stat_activity + pg_stat_statements' },
          { icon: 'fa-plug',      label: 'MySQL source', detail: 'PROCESSLIST + perf_schema.accounts' },
          { icon: 'fa-clock',     label: 'Mode',         detail: 'Snapshot — not real-time' },
        ].map(b => (
          <div key={b.label} className="flex items-center gap-2 px-2.5 py-2 rounded-xl bg-sky-50/60 dark:bg-sky-950/20 border border-sky-200/40 dark:border-sky-800/20">
            <i className={`fas ${b.icon} text-sky-400 text-[10px] shrink-0`} />
            <div className="min-w-0">
              <p className="text-[9px] text-sky-500 dark:text-sky-400 uppercase tracking-wider truncate">{b.label}</p>
              <p className="text-[10px] font-medium text-sky-700 dark:text-sky-300 truncate">{b.detail}</p>
            </div>
          </div>
        ))}
      </div>

      {/* ── KPI strip ── */}
      <div className="grid grid-cols-4 gap-2">
        {[
          { icon: 'fa-users',        label: 'Users',          value: all.length,                   color: 'text-indigo-600 dark:text-indigo-400',   glow: 'shadow-indigo-100 dark:shadow-indigo-900/20' },
          { icon: 'fa-plug',         label: 'Total Conn',     value: totalConnsAll.toLocaleString(), color: 'text-sky-600 dark:text-sky-400',         glow: 'shadow-sky-100 dark:shadow-sky-900/20' },
          { icon: 'fa-circle-dot',   label: 'Active Sessions',value: totalActive.toLocaleString(),  color: 'text-emerald-600 dark:text-emerald-400', glow: 'shadow-emerald-100 dark:shadow-emerald-900/20' },
          { icon: 'fa-moon',         label: 'Idle Conn',      value: totalIdle.toLocaleString(),    color: 'text-gray-500 dark:text-gray-400',       glow: '' },
        ].map(k => (
          <div key={k.label} className={`bg-white/80 dark:bg-gray-800/60 backdrop-blur-sm rounded-xl border border-gray-100/80 dark:border-gray-700/40 px-3 py-2.5 shadow-sm ${k.glow}`}>
            <div className="flex items-center gap-1.5 mb-1">
              <i className={`fas ${k.icon} text-[10px] ${k.color}`} />
              <span className="text-[10px] text-gray-400 dark:text-gray-500 uppercase tracking-wide">{k.label}</span>
            </div>
            <div className={`text-lg font-bold tabular-nums ${k.color}`}>{k.value}</div>
          </div>
        ))}
      </div>

      {/* ── Top user bar ── */}
      {topUser && (
        <div className="flex items-center gap-3 px-3.5 py-2.5 rounded-xl bg-indigo-50/60 dark:bg-indigo-950/20 border border-indigo-200/40 dark:border-indigo-800/20">
          <i className="fas fa-crown text-amber-400 text-xs shrink-0" />
          <span className="text-[11px] text-indigo-700 dark:text-indigo-300">
            <strong>{topUser.username}</strong> holds the most connections
            ({topUser.totalConnections} — {totalConnsAll > 0 ? Math.round((topUser.totalConnections / totalConnsAll) * 100) : 0}% of total)
          </span>
        </div>
      )}

      {/* ── Search + sort row ── */}
      <div className="flex items-center gap-2">
        <div className="relative flex-1">
          <i className="fas fa-magnifying-glass absolute left-2.5 top-1/2 -translate-y-1/2 text-gray-400 text-[10px]" />
          <input
            value={search}
            onChange={e => { setSearch(e.target.value); setPage(0); }}
            placeholder="Search by username…"
            className="w-full pl-7 pr-3 py-1.5 text-xs rounded-lg border border-gray-200/70 dark:border-gray-700/50 bg-white/80 dark:bg-gray-800/60 text-gray-700 dark:text-gray-300 placeholder-gray-400 focus:outline-none focus:ring-1 focus:ring-indigo-400 focus:border-indigo-400 transition-colors"
          />
        </div>
        <span className="text-[10px] text-gray-400 shrink-0">
          {sorted.length !== all.length ? `${sorted.length} / ${all.length}` : all.length} users
        </span>
      </div>

      {/* ── Main table ── */}
      <div className="overflow-x-auto rounded-xl border border-gray-200/60 dark:border-gray-700/40 shadow-sm">
        <table className="w-full text-xs min-w-[720px]">
          <thead className="bg-gray-50/90 dark:bg-gray-800/70 border-b border-gray-200/60 dark:border-gray-700/40">
            <tr>
              {th('username', 'User')}
              <th className="px-3 py-2.5 text-left text-[11px] font-semibold text-gray-500 dark:text-gray-400 whitespace-nowrap">
                <Tip text="Active = executing a query now. Idle = connected but waiting for commands. Dormant = no open connections." wide>Status</Tip>
              </th>
              {th('total',   'Total Conn', true, 'Total open connections (active + idle). Each PostgreSQL connection consumes ~5–10 MB RAM.')}
              {th('active',  'Active',     true, 'Connections currently executing a query. Many concurrent active connections can saturate CPU and I/O.')}
              {th('idle',    'Idle',       true, 'Open but inactive connections. High idle count wastes memory — consider PgBouncer or connection pooling.')}
              {th('share',   'Share %',    true, 'This user\'s share of the total connection pool. A single user holding >50% may starve other applications.')}
              {th('queries', 'Queries',    true, 'Cumulative query count since pg_stat_statements reset (PostgreSQL) or server start (MySQL).')}
              {th('time',    'Cumul. Time',true, 'Total CPU + I/O time spent executing all queries for this user. Highest value = heaviest workload.')}
              <th className="px-3 py-2.5 text-center text-[11px] font-semibold text-gray-500 dark:text-gray-400 whitespace-nowrap">Action</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100/70 dark:divide-gray-700/30">
            {rows.length === 0 ? (
              <tr>
                <td colSpan={9} className="px-3 py-10 text-center text-xs text-gray-400">
                  No users match the current search.
                </td>
              </tr>
            ) : rows.map((u, i) => {
              const sharePct = totalConnsAll > 0 ? Math.round((u.totalConnections / totalConnsAll) * 100) : 0;
              const barWidth = maxConns > 0 ? (u.totalConnections / maxConns) * 100 : 0;
              const st = status(u);
              const absIdx = pg * PAGE_SIZE + i;
              return (
                <tr key={absIdx} className="hover:bg-indigo-50/20 dark:hover:bg-indigo-900/10 transition-colors">

                  {/* User */}
                  <td className="px-3 py-2.5">
                    <div className="flex items-center gap-2">
                      <div className="w-6 h-6 rounded-full bg-gradient-to-br from-indigo-400 to-violet-500 flex items-center justify-center shrink-0">
                        <span className="text-[9px] font-bold text-white uppercase">{u.username.charAt(0)}</span>
                      </div>
                      <div>
                        <div className="font-semibold text-xs text-gray-800 dark:text-gray-100">{u.username}</div>
                        {u.dbEngine && (
                          <div className="text-[9px] text-gray-400 dark:text-gray-500 mt-0.5">{u.dbEngine}</div>
                        )}
                      </div>
                    </div>
                  </td>

                  {/* Status badge */}
                  <td className="px-3 py-2.5">
                    <Tip text={st.label === 'Active' ? 'Currently executing a query — connections are busy right now.' : st.label === 'Idle' ? 'Connected but waiting for commands. Idle connections still consume RAM.' : 'No open connections. Account exists but is not currently connected.'} wide>
                      <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-medium border ${st.cls}`}>
                        <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${st.dot}`} />
                        {st.label}
                      </span>
                    </Tip>
                  </td>

                  {/* Total connections */}
                  <td className="px-3 py-2.5 text-right">
                    <Tip text="Total open connections (active + idle). Each connection consumes ~5–10 MB RAM in PostgreSQL.">
                      <span className="font-bold text-gray-800 dark:text-gray-100 tabular-nums">{u.totalConnections}</span>
                    </Tip>
                  </td>

                  {/* Active */}
                  <td className="px-3 py-2.5 text-right">
                    <Tip text="Connections currently executing a query. Many slow active connections can saturate the DB.">
                      <span className={`font-semibold tabular-nums ${u.activeConnections > 0 ? 'text-emerald-600 dark:text-emerald-400' : 'text-gray-400'}`}>
                        {u.activeConnections}
                      </span>
                    </Tip>
                  </td>

                  {/* Idle */}
                  <td className="px-3 py-2.5 text-right">
                    <Tip text="Open but inactive connections. High idle count? Consider PgBouncer or connection pooling." wide>
                      <span className={`font-medium tabular-nums ${u.idleConnections > 5 ? 'text-amber-600 dark:text-amber-400' : 'text-gray-500 dark:text-gray-400'}`}>
                        {u.idleConnections}
                      </span>
                    </Tip>
                  </td>

                  {/* Share % with proportional bar */}
                  <td className="px-3 py-2.5">
                    <div className="flex flex-col items-end gap-1 min-w-[72px]">
                      <span className="text-[11px] font-semibold text-sky-600 dark:text-sky-400 tabular-nums">{sharePct}%</span>
                      <div className="w-full h-1.5 bg-gray-200/80 dark:bg-gray-700/60 rounded-full overflow-hidden">
                        <div
                          className="h-full rounded-full bg-gradient-to-r from-sky-400 to-indigo-500 transition-all duration-500"
                          style={{ width: `${barWidth}%` }}
                        />
                      </div>
                    </div>
                  </td>

                  {/* Total Queries */}
                  <td className="px-3 py-2.5 text-right">
                    <Tip text="Cumulative query count since last pg_stat_statements reset (PG) or server start (MySQL).">
                      <span className="text-gray-700 dark:text-gray-300 tabular-nums font-medium">
                        {(u.callCount ?? 0) > 0 ? (u.callCount ?? 0).toLocaleString() : <span className="text-gray-400">—</span>}
                      </span>
                    </Tip>
                  </td>

                  {/* Cumulative time */}
                  <td className="px-3 py-2.5 text-right">
                    <Tip text="Total CPU + I/O time spent on all queries (cumulative). High relative to peers = heaviest workload user." wide>
                      <span className="text-gray-700 dark:text-gray-300 tabular-nums font-medium">
                        {u.totalTimeSeconds > 0 ? fmtTime(u.totalTimeSeconds) : <span className="text-gray-400">—</span>}
                      </span>
                    </Tip>
                  </td>

                  {/* Action: copy terminate SQL */}
                  <td className="px-3 py-2.5 text-center">
                    <Tip text={`Copies a SQL snippet to terminate all sessions for "${u.username}". Run in psql or your DB client. Always verify before executing.`} wide>
                      <button
                        onClick={() => {
                          navigator.clipboard.writeText(killSql(u))
                            .then(() => { setCopied(absIdx); setTimeout(() => setCopied(null), 2000); });
                        }}
                        className={`inline-flex items-center gap-1 px-2 py-1 rounded-lg text-[10px] font-medium border transition-all duration-200 ${
                          copied === absIdx
                            ? 'bg-emerald-100 dark:bg-emerald-900/30 text-emerald-700 dark:text-emerald-400 border-emerald-300/60 dark:border-emerald-700/40'
                            : 'bg-red-50 dark:bg-red-900/15 text-red-500 dark:text-red-400 border-red-200/60 dark:border-red-800/40 hover:bg-red-100 dark:hover:bg-red-900/30'
                        }`}
                      >
                        <i className={`fas fa-${copied === absIdx ? 'check' : 'skull-crossbones'} text-[9px]`} />
                        {copied === absIdx ? 'Copied' : 'Kill SQL'}
                      </button>
                    </Tip>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {/* ── Pagination ── */}
      {totalPages > 1 && (() => {
        const start = pg * PAGE_SIZE + 1;
        const end   = Math.min((pg + 1) * PAGE_SIZE, sorted.length);
        return (
          <div className="flex items-center justify-between pt-2.5 px-1 border-t border-gray-100/60 dark:border-gray-700/30">
            <span className="text-[10px] text-gray-400 tabular-nums">{start}–{end} of {sorted.length}</span>
            <div className="flex items-center gap-1">
              <button onClick={() => setPage(p => Math.max(0, p - 1))} disabled={pg === 0}
                className="px-2 py-1 rounded text-[10px] font-medium text-gray-500 dark:text-gray-400 hover:bg-gray-100 dark:hover:bg-gray-700/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors inline-flex items-center gap-1 border border-gray-200/50 dark:border-gray-700/40"
              >
                <i className="fas fa-chevron-left text-[8px]" /> Prev
              </button>
              <span className="text-[10px] text-gray-500 dark:text-gray-400 px-1.5 tabular-nums">{pg + 1} / {totalPages}</span>
              <button onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))} disabled={pg >= totalPages - 1}
                className="px-2 py-1 rounded text-[10px] font-medium text-gray-500 dark:text-gray-400 hover:bg-gray-100 dark:hover:bg-gray-700/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors inline-flex items-center gap-1 border border-gray-200/50 dark:border-gray-700/40"
              >
                Next <i className="fas fa-chevron-right text-[8px]" />
              </button>
            </div>
          </div>
        );
      })()}
    </div>
  );
}

// ── Main component ─────────────────────────────────────────────────────────────
export default function DbInstanceDetailDrawer({
  instance, metrics, agentStats, agentInstanceMetrics, slowQueries,
  idle, rightsizing, security, allSnapshots, costs,
  fetchTimeseries, fetchAgentTimeseries, fetchAgentTableSizes,
  fetchAgentVacuumStats, fetchAgentUserActivity,
  fetchAgentIndexUsage, fetchAgentTableActivity, onConnected, onHealthChange,
}: Props) {
  const [tab, setTab] = useState<DetailTab>('overview');
  const [timeseries, setTimeseries] = useState<DbInstanceTimeseries | null>(null);
  const [agentTs, setAgentTs] = useState<AgentTimeseries[]>([]);
  const [tableSizes, setTableSizes] = useState<AgentTableSize[]>([]);
  const [vacuumStats, setVacuumStats] = useState<AgentVacuumStat[]>([]);
  const [userActivity, setUserActivity] = useState<AgentUserActivity[]>([]);
  const [indexUsage, setIndexUsage] = useState<AgentIndexUsage[]>([]);
  const [tableActivity, setTableActivity] = useState<AgentTableActivity[]>([]);
  const [tsLoading, setTsLoading] = useState(false);
  const [tsHours, setTsHours] = useState(24);
const { selectedAccountIds } = useAccounts();
  const [agentDisconnecting, setAgentDisconnecting] = useState(false);
  const [agentMgmtOpen, setAgentMgmtOpen] = useState(false);
  const [copiedTopQuery, setCopiedTopQuery] = useState(false);

  useEffect(() => {
    if (!instance || instance.source !== 'rds') return;
    setTsLoading(true);
    fetchTimeseries(instance.instanceId, tsHours)
      .then(data => setTimeseries(data))
      .finally(() => setTsLoading(false));
  }, [instance, tsHours, fetchTimeseries]);

  // Fetch agent timeseries whenever the Performance tab is active or hours change
  useEffect(() => {
    if (tab !== 'performance') return;
    fetchAgentTimeseries(tsHours).then(data => setAgentTs(data));
    fetchAgentTableSizes().then(data => setTableSizes(data));
  }, [tab, tsHours, fetchAgentTimeseries, fetchAgentTableSizes]);

  useEffect(() => {
    if (tab !== 'maintenance') return;
    fetchAgentVacuumStats().then(data => setVacuumStats(data));
    fetchAgentIndexUsage().then(data => setIndexUsage(data));
    fetchAgentTableActivity().then(data => setTableActivity(data));
  }, [tab, fetchAgentVacuumStats, fetchAgentIndexUsage, fetchAgentTableActivity]);

  useEffect(() => {
    if (tab !== 'users') return;
    fetchAgentUserActivity().then(data => setUserActivity(data));
  }, [tab, fetchAgentUserActivity]);

  useEffect(() => {
    setTab('overview');
    setTimeseries(null);
    setAgentTs([]);
    setTableSizes([]);
  }, [instance?.instanceId]);

  if (!instance) return null;

  // "Connect a Database" mode — blank instanceId, show wizard-only layout (no tabs)
  if (!instance.instanceId && instance.source === 'agent') {
    return (
      <div className="flex flex-col flex-1 bg-white dark:bg-gray-900 min-h-0">
        <div className="flex-1 overflow-y-auto px-6 py-6">
          <div className="mb-6">
            <h2 className="text-lg font-semibold text-gray-800 dark:text-gray-100 mb-1">
              <i className="fas fa-plug text-indigo-500 mr-2" />Connect a Database
            </h2>
            <p className="text-sm text-gray-500 dark:text-gray-400">
              Deploy the XamOps DB agent on any EC2 instance or server to monitor MySQL or PostgreSQL databases.
            </p>
          </div>
          <AgentSetupWizard
            engine={instance.engine ?? 'mysql'}
            instanceId=""
            onConnected={onConnected}
          />
        </div>
      </div>
    );
  }

  const rd = instance.rdsData;

  const disconnectAgent = (agentName: string) => {
    const accountId = selectedAccountIds[0] ?? '';
    if (!agentName || !accountId) return;
    if (!window.confirm(`Remove agent "${agentName}" and all its cached data?\n\nThe Docker container will keep running until you stop it manually on the EC2.`)) return;
    setAgentDisconnecting(true);
    apiClient.delete(`/api/v1/agent/db/register?agentName=${encodeURIComponent(agentName)}&cloudAccountId=${encodeURIComponent(accountId)}`)
      .then(() => window.location.reload())
      .catch(() => setAgentDisconnecting(false));
  };

  // Per-instance filtered data
  const instanceAgentStats = agentStats.filter(s => {
    if (s.dbHost === instance.agentHost) return true;
    if (instance.instanceId && s.dsnAlias?.includes(instance.instanceId)) return true;
    // Match RDS instances by endpoint hostname (agent dbHost includes port, endpoint may not)
    const agentHostname = s.dbHost?.split(':')[0] ?? '';
    if (agentHostname && instance.rdsData?.endpoint?.includes(agentHostname)) return true;
    if (agentHostname && instance.instanceId && agentHostname.includes(instance.instanceId)) return true;
    return false;
  });
  const agentMetrics = agentInstanceMetrics.find(m =>
    m.dbHost === instance.agentHost ||
    (instance.rdsData?.endpoint && instance.rdsData.endpoint.includes(m.dbHost))
  );
  const instanceAgentTs = agentTs.find(a =>
    (agentMetrics && a.agentName === agentMetrics.agentName) ||
    (instance.instanceId && a.agentName === instance.instanceId) ||
    (instance.agentHost && a.dbHost === instance.agentHost) ||
    (instance.rdsData?.endpoint && instance.rdsData.endpoint.includes(a.dbHost?.split(':')[0] ?? ''))
  );
  const instanceTableSizes = tableSizes.filter(t =>
    t.dbHost === instance.agentHost ||
    (instance.rdsData?.endpoint && instance.rdsData.endpoint.includes(t.dbHost?.split(':')[0] ?? ''))
  ).sort((a, b) => b.totalSizeBytes - a.totalSizeBytes).slice(0, 20);
  const instanceSlowQueries = slowQueries.filter(q => q.instanceId === instance.instanceId);

  const instanceIdle = idle.find(i => i.instanceId === instance.instanceId);
  const instanceRightsizing = rightsizing.find(r => r.instanceId === instance.instanceId);
  const instanceSecurity = security.filter(c => c.instanceId === instance.instanceId);
  const instanceSnapshots = allSnapshots.filter(s => s.instanceId === instance.instanceId);

  // Queries averaging >10ms — used for both the KPI card and the Slow Queries tab count
  const agentSlowQueriesCount = instanceAgentStats.filter(s => s.avgTimeSeconds > 0.01).length;
  const slowQueriesTabCount = instanceSlowQueries.length || agentSlowQueriesCount;

  const healthScore = computeHealthScore(
    metrics,
    agentMetrics,
    slowQueriesTabCount,
    instanceSecurity.filter(c => c.status === 'FAIL').length,
    vacuumStats
  );

  // Notify parent (topbar) whenever health score changes
  useEffect(() => {
    onHealthChange?.(healthScore);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [healthScore.score, healthScore.status, healthScore.issues.join(',')]);

  const securityFailCount = instanceSecurity.filter(c => c.status === 'FAIL').length;
  const instanceMatchesHost = (dbHost: string | undefined) => {
    if (!dbHost) return false;
    if (dbHost === instance.agentHost) return true;
    const h = dbHost.split(':')[0];
    return !!(h && instance.rdsData?.endpoint?.includes(h));
  };
  const maintenanceAlertCount = vacuumStats.filter(v => instanceMatchesHost(v.dbHost) && (v.deadTupRatio ?? 0) > 0.10).length;

  const tabs: { id: DetailTab; label: string; icon: string; badgeCount?: number; badgeColor?: string; dot?: string }[] = [
    { id: 'overview',    label: 'Overview',      icon: 'fa-gauge-high' },
    { id: 'performance', label: 'Performance',   icon: 'fa-chart-line' },
    {
      id: 'slowqueries', label: 'Slow Queries',  icon: 'fa-hourglass-half',
      badgeCount: slowQueriesTabCount > 0 ? slowQueriesTabCount : undefined,
      badgeColor: 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-400',
      dot: slowQueriesTabCount > 0 ? 'bg-red-500' : undefined,
    },
    {
      id: 'queries',     label: 'Query Stats',   icon: 'fa-magnifying-glass-chart',
      badgeCount: instanceAgentStats.length > 0 ? instanceAgentStats.length : undefined,
      badgeColor: 'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-400',
    },
    {
      id: 'maintenance', label: 'Maintenance',   icon: 'fa-wrench',
      dot: maintenanceAlertCount > 0 ? 'bg-yellow-400' : undefined,
      badgeCount: maintenanceAlertCount > 0 ? maintenanceAlertCount : undefined,
      badgeColor: 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900/40 dark:text-yellow-400',
    },
    { id: 'users',       label: 'Users',         icon: 'fa-users' },
    { id: 'cost',        label: 'Cost & Savings', icon: 'fa-dollar-sign' },
    {
      id: 'security',    label: 'Security',      icon: 'fa-shield-halved',
      badgeCount: securityFailCount > 0 ? securityFailCount : undefined,
      badgeColor: 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-400',
      dot: securityFailCount > 0 ? 'bg-red-500' : undefined,
    },
    {
      id: 'snapshots',   label: 'Snapshots',     icon: 'fa-camera',
      badgeCount: instanceSnapshots.length > 0 ? instanceSnapshots.length : undefined,
      badgeColor: 'bg-violet-100 text-violet-700 dark:bg-violet-900/40 dark:text-violet-400',
    },
  ];

  return (
    <div className="flex flex-col flex-1 bg-white dark:bg-gray-900 min-h-0">

      {/* ── Tab bar ── sticky, with icons + badge pills */}
      <div className="flex gap-0 px-4 border-b border-gray-100 dark:border-gray-700 shrink-0 bg-white dark:bg-gray-900 overflow-x-auto sticky top-0 z-10">
        {tabs.map(t => (
          <button
            key={t.id}
            onClick={() => setTab(t.id)}
            className={`relative flex items-center gap-1.5 px-3 py-2.5 text-xs font-medium transition-all whitespace-nowrap border-b-2 ${
              tab === t.id
                ? 'text-indigo-600 dark:text-indigo-400 border-indigo-500 dark:border-indigo-400'
                : 'text-gray-500 dark:text-gray-400 border-transparent hover:text-gray-700 dark:hover:text-gray-300 hover:border-gray-200 dark:hover:border-gray-600'
            }`}
          >
            {/* Alert dot (shown when not active) */}
            {t.dot && tab !== t.id && (
              <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${t.dot}`} />
            )}
            <i className={`fas ${t.icon} text-[10px] ${tab === t.id ? 'text-indigo-500 dark:text-indigo-400' : 'text-gray-400 dark:text-gray-500'}`} />
            <span>{t.label}</span>
            {/* Badge pill */}
            {t.badgeCount !== undefined && (
              <span className={`ml-0.5 px-1.5 py-0.5 rounded-full text-[9px] font-bold leading-none ${t.badgeColor ?? 'bg-gray-100 text-gray-600 dark:bg-gray-700 dark:text-gray-400'}`}>
                {t.badgeCount}
              </span>
            )}
          </button>
        ))}
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto px-6 py-4">

        {/* ── OVERVIEW ── */}
        {tab === 'overview' && (
          <div className="space-y-5">
            {/* New / empty agent instance — show wizard directly */}
            {instance.source === 'agent' && !agentMetrics && instanceAgentStats.length === 0 && (
              <AgentSetupWizard
                engine={instance.engine ?? 'mysql'}
                instanceId={instance.instanceId}
                endpoint={instance.rdsData?.endpoint}
              />
            )}

            {/* ── Health Score Widget ── */}
            {(metrics || agentMetrics) && (
              <div className={`rounded-2xl p-4 border flex items-center gap-4 ${
                healthScore.status === 'healthy' ? 'bg-emerald-50 dark:bg-emerald-900/10 border-emerald-100 dark:border-emerald-800/40' :
                healthScore.status === 'warning'  ? 'bg-amber-50 dark:bg-amber-900/10 border-amber-100 dark:border-amber-800/40' :
                'bg-red-50 dark:bg-red-900/10 border-red-100 dark:border-red-800/40'
              }`}>
                {/* SVG ring */}
                <div className="shrink-0 relative w-16 h-16">
                  <svg className="w-16 h-16 -rotate-90" viewBox="0 0 64 64">
                    <circle cx="32" cy="32" r="26" fill="none" stroke="currentColor" strokeWidth="6"
                      className="text-gray-200 dark:text-gray-700" />
                    <circle cx="32" cy="32" r="26" fill="none" stroke="currentColor" strokeWidth="6"
                      strokeDasharray={`${2 * Math.PI * 26}`}
                      strokeDashoffset={`${2 * Math.PI * 26 * (1 - healthScore.score / 100)}`}
                      strokeLinecap="round"
                      className={
                        healthScore.status === 'healthy' ? 'text-emerald-500' :
                        healthScore.status === 'warning'  ? 'text-amber-500' : 'text-red-500'
                      }
                      style={{ transition: 'stroke-dashoffset 0.6s ease' }}
                    />
                  </svg>
                  <div className="absolute inset-0 flex items-center justify-center">
                    <span className={`text-lg font-bold leading-none ${
                      healthScore.status === 'healthy' ? 'text-emerald-600 dark:text-emerald-400' :
                      healthScore.status === 'warning'  ? 'text-amber-600 dark:text-amber-400' :
                      'text-red-600 dark:text-red-400'
                    }`}>{healthScore.score}</span>
                  </div>
                </div>
                <div className="flex-1 min-w-0">
                  <p className={`text-sm font-semibold ${
                    healthScore.status === 'healthy' ? 'text-emerald-800 dark:text-emerald-200' :
                    healthScore.status === 'warning'  ? 'text-amber-800 dark:text-amber-200' :
                    'text-red-800 dark:text-red-200'
                  }`}>
                    {healthScore.status === 'healthy' ? 'All Systems Healthy' :
                     healthScore.status === 'warning'  ? 'Attention Needed' : 'Critical Issues Detected'}
                  </p>
                  {healthScore.issues.length > 0 ? (
                    <ul className="mt-1.5 space-y-0.5">
                      {healthScore.issues.slice(0, 3).map((issue, i) => (
                        <li key={i} className="text-xs text-gray-600 dark:text-gray-400 flex items-center gap-1.5">
                          <i className={`fas fa-circle-exclamation text-[9px] ${healthScore.status === 'critical' ? 'text-red-500' : 'text-amber-500'}`} />
                          {issue}
                        </li>
                      ))}
                      {healthScore.issues.length > 3 && (
                        <li className="text-[10px] text-gray-400">+{healthScore.issues.length - 3} more issues</li>
                      )}
                    </ul>
                  ) : (
                    <p className="text-xs text-emerald-600 dark:text-emerald-400 mt-1">No issues detected</p>
                  )}
                </div>
                <div className="text-right shrink-0 text-xs text-gray-400">
                  <div className="font-semibold text-lg text-gray-600 dark:text-gray-300">{healthScore.score}/100</div>
                  <div>Health Score</div>
                </div>
              </div>
            )}
            {metrics && (
              <div>
                <p className="text-xs font-semibold text-gray-500 dark:text-gray-400 uppercase tracking-wider mb-2">Live Metrics (7d avg)</p>
                <div className="grid grid-cols-3 gap-2">
                  <StatCard label="CPU" value={`${metrics.avgCpuPercent}%`}
                    color={metrics.avgCpuPercent > 80 ? 'text-red-600' : metrics.avgCpuPercent > 60 ? 'text-yellow-600' : 'text-gray-900 dark:text-gray-100'}
                    tooltip="7-day average CPU utilization from CloudWatch. Above 80% is critical — consider scaling up or optimizing queries. Above 60% is elevated." />
                  <StatCard label="Connections" value={metrics.avgConnections}
                    tooltip="7-day average number of database connections. High values relative to max_connections (RDS) may indicate connection pool exhaustion." />
                  <StatCard label="Free Memory" value={round2(metrics.avgFreeableMemoryMb)} unit="MB"
                    tooltip="Average freeable RAM over 7 days (CloudWatch FreeableMemory). Low values cause the OS to swap memory to disk, degrading query performance." />
                  <StatCard label="Read IOPS" value={metrics.avgReadIops}
                    tooltip="Average read I/O operations per second over 7 days. High read IOPS on gp2 volumes may exhaust burst credits — consider switching to gp3 for predictable performance." />
                  <StatCard label="Write IOPS" value={metrics.avgWriteIops}
                    tooltip="Average write I/O operations per second over 7 days. High write IOPS combined with high latency may indicate write contention or insufficient provisioned IOPS." />
                  <StatCard label="Read Latency" value={metrics.avgReadLatencyMs} unit="ms"
                    tooltip="Average read I/O latency in milliseconds over 7 days. Above 1ms may indicate disk I/O saturation or insufficient IOPS for your workload." />
                  <StatCard label="Write Latency" value={metrics.avgWriteLatencyMs} unit="ms"
                    tooltip="Average write I/O latency in milliseconds over 7 days. High write latency may indicate I/O saturation, slow commit flush, or storage bottleneck." />
                  <StatCard label="Free Storage" value={metrics.freeStorageSpaceGb} unit="GB"
                    tooltip="Remaining free storage space. Below 10% of total storage triggers a CloudWatch alarm. Running out of storage will cause the instance to become read-only." />
                  <StatCard label="Network In" value={metrics.avgNetworkReceiveMbps} unit="Mbps"
                    tooltip="Average inbound network throughput over 7 days. High inbound traffic may indicate large bulk imports or heavy replication." />
                  <StatCard label="Network Out" value={metrics.avgNetworkTransmitMbps} unit="Mbps"
                    tooltip="Average outbound network throughput over 7 days. High outbound traffic may indicate large result sets being returned to clients — consider pagination or projection." />
                  {metrics.avgSwapUsageMb > 0 && (
                    <StatCard label="Swap Usage" value={`${round2(metrics.avgSwapUsageMb)} MB`}
                      color={metrics.avgSwapUsageMb > 100 ? 'text-orange-600' : 'text-yellow-600'}
                      tooltip="Swap usage indicates the OS is paging memory to disk. Any swap activity degrades DB performance — increase instance memory or reduce buffer pool size." />
                  )}
                  {metrics.replicaLagSeconds > 0 && (
                    <StatCard label="Replica Lag" value={`${round2(metrics.replicaLagSeconds)}s`}
                      color={metrics.replicaLagSeconds > 30 ? 'text-red-600' : metrics.replicaLagSeconds > 5 ? 'text-orange-600' : 'text-yellow-600'}
                      tooltip="Average replication lag over the last 7 days (CloudWatch ReplicaLag). High lag means read replicas are falling behind — queries hitting replicas may return stale data." />
                  )}
                  {metrics.burstBalance > 0 && (
                    <StatCard label="Burst Balance" value={`${Math.round(metrics.burstBalance)}%`}
                      color={metrics.burstBalance < 20 ? 'text-red-600' : metrics.burstBalance < 50 ? 'text-yellow-600' : 'text-green-600'}
                      tooltip="EBS burst I/O balance (gp2 volumes). Below 20% means credits are nearly exhausted — DB will be throttled to baseline IOPS. Consider upgrading to gp3 for consistent I/O." />
                  )}
                </div>
              </div>
            )}
            {!metrics && instance.source === 'rds' && (
              <div className="text-sm text-gray-400 py-4 text-center">No CloudWatch metrics available</div>
            )}

            {rd && (
              <div>
                <p className="text-xs font-semibold text-gray-500 dark:text-gray-400 uppercase tracking-wider mb-2">Instance Details</p>
                <div className="bg-gray-50 dark:bg-gray-700/30 rounded-lg divide-y divide-gray-100 dark:divide-gray-700">
                  {[
                    ['Engine Version', rd.engineVersion],
                    ['Endpoint', rd.endpoint],
                    ['Storage', `${rd.allocatedStorage} GB (${rd.storageType})`],
                    ['Multi-AZ', rd.multiAz],
                    ['Encrypted', rd.storageEncrypted ? '✓ Yes' : '✗ No'],
                    ['Backups', rd.backupEnabled ? `${rd.backupRetentionDays}d retention` : 'Disabled'],
                    ['Publicly Accessible', rd.publiclyAccessible ? '⚠ Yes' : 'No'],
                    ['Est. Monthly Cost', fmt$(rd.estimatedMonthlyCost)],
                  ].map(([k, v]) => (
                    <div key={k} className="flex items-center justify-between px-4 py-2 text-sm">
                      <span className="text-gray-500 dark:text-gray-400">{k}</span>
                      <span className="text-gray-800 dark:text-gray-200 font-medium text-right max-w-[60%] truncate">{v}</span>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {agentMetrics && (
              <div>
                <p className="text-xs font-semibold text-gray-500 dark:text-gray-400 uppercase tracking-wider mb-2">
                  Live Server Metrics <span className="text-purple-500 ml-1">(via Agent)</span>
                </p>
                <div className="grid grid-cols-3 gap-2">
                  <StatCard label="Connections" value={`${agentMetrics.currentConnections} / ${agentMetrics.maxConnections}`}
                    color={agentMetrics.currentConnections / Math.max(agentMetrics.maxConnections, 1) > 0.8 ? 'text-red-600' : 'text-gray-900 dark:text-gray-100'}
                    tooltip="Current open connections vs. max_connections limit. Above 80% risks connection exhaustion — your app will start seeing 'too many connections' errors." />
                  <StatCard label="Peak Connections" value={agentMetrics.maxUsedConnections}
                    tooltip={agentMetrics.dbEngine === 'postgres'
                      ? "Current connections at collection time. PostgreSQL has no built-in historical peak counter — use this as a snapshot to right-size your HikariCP / connection pool."
                      : "Highest simultaneous connections since the server last started (max_used_connections). Use this to right-size your connection pool (e.g. HikariCP maxPoolSize)."} />
                  <StatCard label="Total Queries" value={agentMetrics.totalQueries.toLocaleString()}
                    tooltip={agentMetrics.dbEngine === 'postgres'
                      ? "Sum of all query executions tracked by pg_stat_statements since the extension was last reset. Covers every query pattern the agent monitors."
                      : "Cumulative queries executed since server startup. Sourced from Com_* status variables (MySQL). Resets on server restart."} />
                  <StatCard label="Slow Queries" value={agentMetrics.dbEngine === 'postgres' ? agentSlowQueriesCount : agentMetrics.totalSlowQueries}
                    color={(agentMetrics.dbEngine === 'postgres' ? agentSlowQueriesCount : agentMetrics.totalSlowQueries) > 0 ? 'text-orange-600' : 'text-gray-900 dark:text-gray-100'}
                    tooltip={agentMetrics.dbEngine === 'postgres'
                      ? "Queries averaging >10ms in pg_stat_statements. These are the same queries shown in the Slow Queries tab — patterns with high average execution time worth optimizing."
                      : "Queries exceeding long_query_time threshold from MySQL Slow_queries counter. Enable slow_query_log to populate this."} />
                  {agentMetrics.dbSizeBytes != null && agentMetrics.dbSizeBytes > 0 && (
                    <StatCard label="DB Size" value={fmtBytes(agentMetrics.dbSizeBytes)}
                      tooltip="Total size of all databases on this server, including data and index files." />
                  )}
                  {agentMetrics.innodbBufferPoolHitPct > 0 && (
                    <StatCard label="Buffer Pool Hit" value={`${Math.round(agentMetrics.innodbBufferPoolHitPct)}%`}
                      color={agentMetrics.innodbBufferPoolHitPct < 90 ? 'text-orange-600' : 'text-green-600'}
                      tooltip="% of data reads served from RAM (InnoDB buffer pool) vs. disk. Below 90% means frequent disk I/O — increase innodb_buffer_pool_size to fit your working set in memory." />
                  )}
                  {agentMetrics.innodbBufferPoolReads > 0 && agentMetrics.dbEngine !== 'postgres' && (
                    <StatCard label="Pool Disk Reads" value={agentMetrics.innodbBufferPoolReads.toLocaleString()}
                      color={agentMetrics.innodbBufferPoolReads > 10000 ? 'text-orange-600' : 'text-gray-900 dark:text-gray-100'}
                      tooltip="Cumulative InnoDB reads that had to fetch from disk (buffer pool misses). High values mean your working set doesn't fit in innodb_buffer_pool_size — a primary cause of DB slowness." />
                  )}
                  <StatCard label="Uptime" value={agentMetrics.uptime > 86400
                    ? `${Math.floor(agentMetrics.uptime / 86400)}d`
                    : `${Math.floor(agentMetrics.uptime / 3600)}h`}
                    tooltip="Time since the database server last restarted. Frequent restarts indicate crashes or OOM kills. Note: cumulative counters (Total Queries, Slow Queries) reset on restart." />
                </div>
                {agentMetrics.maxConnections > 0 && (
                  <div className="mt-2 bg-gray-50 dark:bg-gray-700/30 rounded-lg p-3">
                    <div className="flex items-center justify-between text-xs mb-1.5">
                      <Tip text="Current open connections as a percentage of max_connections. Above 60% = elevated (yellow zone), above 80% = critical (red zone) — risk of connection exhaustion." wide>
                        <span className="text-gray-500 font-medium">Connection Pool Usage</span>
                      </Tip>
                      {(() => {
                        const pct = Math.round(agentMetrics.currentConnections / agentMetrics.maxConnections * 100);
                        return (
                          <span className={`font-semibold px-2 py-0.5 rounded-full text-[10px] ${
                            pct > 80 ? 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400' :
                            pct > 60 ? 'bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-400' :
                            'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-400'
                          }`}>
                            {agentMetrics.currentConnections} / {agentMetrics.maxConnections} ({pct}%)
                          </span>
                        );
                      })()}
                    </div>
                    {/* Bar with threshold zone markers */}
                    {(() => {
                      const pct = Math.min(agentMetrics.currentConnections / agentMetrics.maxConnections * 100, 100);
                      const barColor = pct > 80 ? 'bg-red-500' : pct > 60 ? 'bg-orange-400' : 'bg-emerald-500';
                      return (
                        <>
                          <div className="relative h-3 rounded-full bg-gray-200 dark:bg-gray-600 overflow-hidden">
                            <div className={`h-full rounded-full ${barColor} transition-all duration-500`} style={{ width: `${pct}%` }} />
                            {/* Threshold markers */}
                            <div className="absolute top-0 left-[60%] w-px h-full bg-yellow-500/70" title="60% warning threshold" />
                            <div className="absolute top-0 left-[80%] w-px h-full bg-red-500/70" title="80% critical threshold" />
                          </div>
                          <div className="flex justify-between text-[9px] text-gray-400 mt-1">
                            <span>0</span>
                            <span className="text-yellow-600 dark:text-yellow-400" style={{ marginLeft: '55%' }}>60%</span>
                            <span className="text-red-600 dark:text-red-400">80%</span>
                            <span>{agentMetrics.maxConnections}</span>
                          </div>
                        </>
                      );
                    })()}
                    {agentMetrics.maxUsedConnections > agentMetrics.currentConnections && (
                      <p className="text-[11px] text-gray-400 mt-1.5 flex items-center gap-1">
                        <i className="fas fa-arrow-up text-[8px]" /> Peak: {agentMetrics.maxUsedConnections} connections
                      </p>
                    )}
                  </div>
                )}
                {agentMetrics.comSelect + agentMetrics.comInsert + agentMetrics.comUpdate + agentMetrics.comDelete > 0 && (
                  <div className="mt-2 bg-gray-50 dark:bg-gray-700/30 rounded-lg p-3">
                    <p className="text-xs text-gray-500 font-medium mb-2">Query Type Breakdown</p>
                    {(() => {
                      const total = agentMetrics.comSelect + agentMetrics.comInsert + agentMetrics.comUpdate + agentMetrics.comDelete;
                      const items = [
                        { label: 'SELECT', val: agentMetrics.comSelect, barColor: 'bg-blue-500',   textColor: 'text-blue-600 dark:text-blue-400',   dotColor: 'bg-blue-500' },
                        { label: 'INSERT', val: agentMetrics.comInsert, barColor: 'bg-emerald-500', textColor: 'text-emerald-600 dark:text-emerald-400', dotColor: 'bg-emerald-500' },
                        { label: 'UPDATE', val: agentMetrics.comUpdate, barColor: 'bg-amber-500',  textColor: 'text-amber-600 dark:text-amber-400',  dotColor: 'bg-amber-500' },
                        { label: 'DELETE', val: agentMetrics.comDelete, barColor: 'bg-red-500',    textColor: 'text-red-600 dark:text-red-400',    dotColor: 'bg-red-500' },
                      ];
                      return (
                        <div className="space-y-2">
                          {/* Stacked proportion bar */}
                          <div className="flex h-2 rounded-full overflow-hidden gap-px">
                            {items.map(({ label, val, barColor }) => {
                              const pct = total > 0 ? (val / total) * 100 : 0;
                              return pct > 0 ? (
                                <div key={label} className={`${barColor} transition-all duration-500`}
                                  style={{ width: `${pct}%` }} title={`${label}: ${pct.toFixed(1)}%`} />
                              ) : null;
                            })}
                          </div>
                          <div className="grid grid-cols-4 gap-2 text-xs text-center">
                            {items.map(({ label, val, textColor, dotColor }) => {
                              const pct = total > 0 ? Math.round((val / total) * 100) : 0;
                              return (
                                <div key={label}>
                                  <div className="flex items-center justify-center gap-1 mb-0.5">
                                    <span className={`w-2 h-2 rounded-full ${dotColor}`} />
                                    <span className="text-gray-400">{label}</span>
                                  </div>
                                  <div className={`font-semibold ${textColor}`}>{val.toLocaleString()}</div>
                                  <div className="text-gray-400 text-[10px]">{pct}%</div>
                                </div>
                              );
                            })}
                          </div>
                        </div>
                      );
                    })()}
                  </div>
                )}
                {(agentMetrics.bytesReceived > 0 || agentMetrics.bytesSent > 0) && (
                  <div className="mt-2 bg-gray-50 dark:bg-gray-700/30 rounded-lg p-3">
                    <p className="text-xs text-gray-500 mb-2">Network I/O <span className="text-gray-400 font-normal">(cumulative since restart)</span></p>
                    <div className="grid grid-cols-2 gap-2 text-xs text-center">
                      <div>
                        <div className="text-gray-400">Received</div>
                        <div className="font-semibold text-cyan-600 dark:text-cyan-400">{fmtBytes(agentMetrics.bytesReceived)}</div>
                      </div>
                      <div>
                        <div className="text-gray-400">Sent</div>
                        <div className="font-semibold text-violet-600 dark:text-violet-400">{fmtBytes(agentMetrics.bytesSent)}</div>
                      </div>
                    </div>
                  </div>
                )}
                {agentMetrics.version && (
                  <p className="text-xs text-gray-400 mt-2">Version: {agentMetrics.version}</p>
                )}
                {(agentMetrics.ec2InstanceType || agentMetrics.region) && (
                  <div className="mt-3 flex flex-wrap gap-2">
                    {agentMetrics.region && (
                      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs bg-blue-50 dark:bg-blue-900/30 text-blue-700 dark:text-blue-300">
                        <i className="fas fa-location-dot text-[9px]" /> {agentMetrics.region}
                      </span>
                    )}
                    {agentMetrics.ec2InstanceType && (
                      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs bg-orange-50 dark:bg-orange-900/30 text-orange-700 dark:text-orange-300">
                        <i className="fab fa-aws text-[9px]" /> {agentMetrics.ec2InstanceType}
                      </span>
                    )}
                    {agentMetrics.ec2InstanceId && (
                      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs bg-gray-100 dark:bg-gray-700 text-gray-600 dark:text-gray-400 font-mono">
                        <i className="fas fa-server text-[9px]" /> {agentMetrics.ec2InstanceId}
                      </span>
                    )}
                    {agentMetrics.ec2HourlyPrice != null && agentMetrics.ec2HourlyPrice > 0 && (
                      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs bg-green-50 dark:bg-green-900/30 text-green-700 dark:text-green-300">
                        <i className="fas fa-dollar-sign text-[9px]" />
                        ${agentMetrics.ec2HourlyPrice.toFixed(4)}/hr · ${(agentMetrics.ec2HourlyPrice * 730).toFixed(0)}/mo
                      </span>
                    )}
                  </div>
                )}
              </div>
            )}

            {instance.source === 'agent' && instanceAgentStats.length > 0 && (
              <div>
                <div className="flex items-center justify-between mb-2">
                  <p className="text-xs font-semibold text-gray-500 dark:text-gray-400 uppercase tracking-wider">Top Query (by total time)</p>
                  <button
                    onClick={() => setTab('queries')}
                    className="text-xs text-indigo-600 dark:text-indigo-400 hover:text-indigo-700 dark:hover:text-indigo-300 flex items-center gap-1 transition-colors"
                  >
                    View all in Query Stats <i className="fas fa-arrow-right text-[9px]" />
                  </button>
                </div>
                <div className="bg-gray-50 dark:bg-gray-700/30 rounded-lg p-3 relative group">
                  <button
                    onClick={() => {
                      navigator.clipboard.writeText(instanceAgentStats[0].queryDigest ?? '');
                      setCopiedTopQuery(true);
                      setTimeout(() => setCopiedTopQuery(false), 2000);
                    }}
                    className="absolute top-2 right-2 px-2 py-1 bg-gray-200 dark:bg-gray-600 hover:bg-gray-300 dark:hover:bg-gray-500 rounded text-[10px] text-gray-600 dark:text-gray-300 transition-colors opacity-0 group-hover:opacity-100 flex items-center gap-1"
                  >
                    <i className={`fas ${copiedTopQuery ? 'fa-check text-emerald-500' : 'fa-copy'} text-[9px]`} />
                    {copiedTopQuery ? 'Copied' : 'Copy'}
                  </button>
                  <code className="text-xs text-indigo-700 dark:text-indigo-300 break-all pr-16 leading-relaxed">{instanceAgentStats[0].queryDigest}</code>
                  <div className="flex flex-wrap gap-3 mt-2 text-xs">
                    <span className="flex items-center gap-1 text-gray-500">
                      <i className="fas fa-repeat text-[9px] text-gray-400" />
                      Calls: <strong className="text-gray-700 dark:text-gray-300">{instanceAgentStats[0].callCount.toLocaleString()}</strong>
                    </span>
                    <span className="flex items-center gap-1 text-gray-500">
                      <i className="fas fa-clock text-[9px] text-gray-400" />
                      Avg: <strong className={`${round2(instanceAgentStats[0].avgTimeSeconds * 1000) > 100 ? 'text-orange-600' : 'text-gray-700 dark:text-gray-300'}`}>{round2(instanceAgentStats[0].avgTimeSeconds * 1000)}ms</strong>
                    </span>
                    <span className="flex items-center gap-1 text-gray-500">
                      <i className="fas fa-arrow-up text-[9px] text-gray-400" />
                      Max: <strong className="text-gray-700 dark:text-gray-300">{round2(instanceAgentStats[0].maxTimeSeconds * 1000)}ms</strong>
                    </span>
                  </div>
                </div>
              </div>
            )}

            {/* Agent management — collapsible disconnect */}
            {instance.source === 'agent' && (agentMetrics || instanceAgentStats.length > 0) && (
              <div className="border-t border-gray-100 dark:border-gray-700 pt-4">
                <button
                  onClick={() => setAgentMgmtOpen(v => !v)}
                  className="flex items-center justify-between w-full text-xs font-semibold text-gray-400 dark:text-gray-500 uppercase tracking-wider hover:text-gray-600 dark:hover:text-gray-400 transition-colors"
                >
                  <span className="flex items-center gap-1.5">
                    <i className="fas fa-gear" />
                    Agent Management
                  </span>
                  <i className={`fas fa-chevron-${agentMgmtOpen ? 'up' : 'down'} text-[10px]`} />
                </button>
                {agentMgmtOpen && (
                  <div className="mt-2 flex items-center justify-between bg-gray-50 dark:bg-gray-700/30 rounded-lg px-4 py-3">
                    <div>
                      <p className="text-xs font-medium text-gray-700 dark:text-gray-300">{instance.instanceId}</p>
                      <p className="text-[11px] text-gray-400 mt-0.5">Removes registration and cached data. Docker container keeps running.</p>
                    </div>
                    <button
                      onClick={() => disconnectAgent(agentMetrics?.agentName ?? instanceAgentTs?.agentName ?? instance.instanceId)}
                      disabled={agentDisconnecting}
                      className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-red-500 dark:text-red-400 border border-red-200 dark:border-red-800 rounded-lg hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors disabled:opacity-50 shrink-0 ml-4"
                    >
                      {agentDisconnecting
                        ? <><i className="fas fa-spinner fa-spin" /> Removing…</>
                        : <><i className="fas fa-unlink" /> Disconnect</>}
                    </button>
                  </div>
                )}
              </div>
            )}
          </div>
        )}

        {/* ── PERFORMANCE CHARTS ── */}
        {tab === 'performance' && (
          <div className="space-y-5">

            {/* Header row: title + pill time-range selector */}
            <div className="flex items-center justify-between gap-3">
              <div>
                <p className="text-sm font-semibold text-gray-800 dark:text-gray-100 leading-tight">Performance Metrics</p>
                <p className="text-[11px] text-gray-400 dark:text-gray-500 mt-0.5">
                  {instance.source === 'rds' ? 'CloudWatch' : 'DB Agent'} telemetry
                  {instanceAgentTs ? ' · agent overlay' : ''}
                </p>
              </div>
              <div className="flex items-center gap-1 bg-gray-100/80 dark:bg-gray-800/60 rounded-xl p-0.5 border border-gray-200/50 dark:border-gray-700/40 shrink-0">
                {([6, 24, 48, 168] as const).map(h => (
                  <button key={h} onClick={() => setTsHours(h)}
                    className={`px-2.5 py-1 text-[11px] font-medium rounded-lg transition-all duration-200 ${
                      tsHours === h
                        ? 'bg-white dark:bg-gray-700 text-indigo-600 dark:text-indigo-400 shadow-sm'
                        : 'text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-200'
                    }`}>
                    {h < 24 ? `${h}h` : h === 168 ? '7d' : `${h / 24}d`}
                  </button>
                ))}
                {tsLoading && <i className="fas fa-spinner fa-spin text-indigo-400 text-[10px] mx-1" />}
              </div>
            </div>

            {/* ── CloudWatch charts (RDS only) ── */}
            {instance.source === 'rds' && (() => {
              const cpuData   = toChartData(timeseries?.cpu);
              const connData  = toChartData(timeseries?.connections);
              const rIopsData = toChartData(timeseries?.readIops);
              const wIopsData = toChartData(timeseries?.writeIops);
              const rLatData  = toChartData(timeseries?.readLatencyMs);
              const wLatData  = toChartData(timeseries?.writeLatencyMs);
              const storData  = toChartData(timeseries?.freeStorageGb);
              const memData   = toChartData(timeseries?.freeableMemoryMb);
              const netInData = toChartData(timeseries?.networkReceiveMbps);
              const netOutData= toChartData(timeseries?.networkTransmitMbps);
              const repData   = toChartData(timeseries?.replicaLagSeconds);

              // Summary bar: find the peaks
              const peakCpu  = cpuData.length  ? cpuData.reduce((a, b)  => a.v > b.v ? a : b) : null;
              const peakConn = connData.length ? connData.reduce((a, b) => a.v > b.v ? a : b) : null;
              const peakRLat = rLatData.length ? rLatData.reduce((a, b) => a.v > b.v ? a : b) : null;

              return (
                <div className="space-y-3">
                  {/* Summary bar */}
                  {(peakCpu || peakConn || peakRLat) && (
                    <div className="flex flex-wrap items-center gap-x-5 gap-y-1 px-3 py-2.5 rounded-xl bg-indigo-50/70 dark:bg-indigo-950/30 border border-indigo-200/50 dark:border-indigo-800/30 text-[11px]">
                      <span className="flex items-center gap-1 font-semibold text-indigo-500 dark:text-indigo-400 shrink-0">
                        <i className="fas fa-bolt text-[10px]" /> Peaks
                      </span>
                      {peakCpu && (
                        <span className="text-gray-600 dark:text-gray-300">
                          CPU <span className="font-semibold text-indigo-600 dark:text-indigo-400">{round2(peakCpu.v)}%</span>
                          <span className="text-gray-400 ml-1">@ {fmtTime(peakCpu.ts)}</span>
                        </span>
                      )}
                      {peakConn && (
                        <span className="text-gray-600 dark:text-gray-300">
                          Connections <span className="font-semibold text-sky-600 dark:text-sky-400">{Math.round(peakConn.v)}</span>
                          <span className="text-gray-400 ml-1">@ {fmtTime(peakConn.ts)}</span>
                        </span>
                      )}
                      {peakRLat && (
                        <span className="text-gray-600 dark:text-gray-300">
                          Read latency <span className="font-semibold text-violet-600 dark:text-violet-400">{round2(peakRLat.v)} ms</span>
                          <span className="text-gray-400 ml-1">@ {fmtTime(peakRLat.ts)}</span>
                        </span>
                      )}
                    </div>
                  )}

                  <div className="grid grid-cols-2 gap-3">
                    <PerfChart title="CPU Utilization"   data={cpuData}   color="#6366f1" unit="%"    syncId="rds_perf" />
                    <PerfChart title="Connections"        data={connData}  color="#0ea5e9"             syncId="rds_perf" />
                    <PerfChart title="Read IOPS"          data={rIopsData} color="#10b981" unit="IOPS" syncId="rds_perf" />
                    <PerfChart title="Write IOPS"         data={wIopsData} color="#f59e0b" unit="IOPS" syncId="rds_perf" />
                    <PerfChart title="Read Latency"       data={rLatData}  color="#8b5cf6" unit="ms"   syncId="rds_perf" />
                    <PerfChart title="Write Latency"      data={wLatData}  color="#ef4444" unit="ms"   syncId="rds_perf" />
                    <PerfChart title="Free Storage"       data={storData}  color="#14b8a6" unit="GB"   syncId="rds_perf" />
                    <PerfChart title="Freeable Memory"    data={memData}   color="#f97316" unit="MB"   syncId="rds_perf" />
                    <PerfChart title="Network In"         data={netInData} color="#06b6d4" unit="Mbps" syncId="rds_perf" />
                    <PerfChart title="Network Out"        data={netOutData}color="#a78bfa" unit="Mbps" syncId="rds_perf" />
                    {repData.length > 0 && (
                      <PerfChart title="Replication Lag" data={repData}   color="#a855f7" unit="s"    syncId="rds_perf" />
                    )}
                  </div>
                </div>
              );
            })()}

            {/* ── IOPS Analysis (RDS) ── */}
            {instance.source === 'rds' && rd && metrics && (() => {
              const storType = (rd.storageType ?? '').toLowerCase();
              const allocGb  = rd.allocatedStorage ?? 0;
              const isGp2    = storType === 'gp2';
              const isGp3    = storType === 'gp3';

              // Baseline IOPS quota (sustained, guaranteed)
              const baselineIops = isGp2
                ? Math.max(100, Math.min(allocGb * 3, 3000))
                : isGp3 ? 3000 : 0;

              const totalIops    = (metrics.avgReadIops ?? 0) + (metrics.avgWriteIops ?? 0);
              const iopsUsagePct = baselineIops > 0 ? Math.min(Math.round(totalIops / baselineIops * 100), 200) : 0;

              const storBadgeClass = isGp2
                ? 'bg-yellow-50 text-yellow-700 border-yellow-200 dark:bg-yellow-900/20 dark:text-yellow-400 dark:border-yellow-700/40'
                : isGp3
                  ? 'bg-green-50 text-green-700 border-green-200 dark:bg-green-900/20 dark:text-green-400 dark:border-green-700/40'
                  : 'bg-blue-50 text-blue-700 border-blue-200 dark:bg-blue-900/20 dark:text-blue-400 dark:border-blue-700/40';

              return (
                <div className="space-y-2">
                  <div className="flex items-center gap-2">
                    <p className="text-[11px] font-semibold text-gray-400 dark:text-gray-500 uppercase tracking-wider flex-1">IOPS Analysis</p>
                    <span className={`px-2 py-0.5 rounded-full text-[10px] font-medium border ${storBadgeClass}`}>
                      {rd.storageType?.toUpperCase() ?? 'Unknown'}
                    </span>
                  </div>

                  <div className="rounded-xl border border-gray-200 dark:border-gray-700 overflow-hidden divide-y divide-gray-100 dark:divide-gray-700">
                    {/* Current IOPS vs Baseline Quota */}
                    {baselineIops > 0 && (
                      <div className="px-3 py-2.5">
                        <div className="flex items-center justify-between text-[11px] mb-1.5">
                          <Tip text={`Baseline (quota) is the guaranteed sustained IOPS for your storage type. gp2: 3 IOPS/GB (min 100, max 3,000). gp3: flat 3,000. Your DB is using ${totalIops} combined read+write IOPS on average. Exceeding baseline on gp2 drains burst credits.`} wide>
                            <span className="text-gray-600 dark:text-gray-300 underline decoration-dotted cursor-help">IOPS Usage vs Baseline Quota</span>
                          </Tip>
                          <span className={`font-semibold tabular-nums ${iopsUsagePct > 100 ? 'text-red-600' : iopsUsagePct > 80 ? 'text-orange-600' : 'text-gray-700 dark:text-gray-200'}`}>
                            {totalIops} / {baselineIops} IOPS
                          </span>
                        </div>
                        <div className="h-2 rounded-full bg-gray-100 dark:bg-gray-700 overflow-hidden">
                          <div
                            className={`h-full rounded-full ${iopsUsagePct > 100 ? 'bg-red-500' : iopsUsagePct > 80 ? 'bg-orange-500' : 'bg-emerald-500'}`}
                            style={{ width: `${Math.min(iopsUsagePct, 100)}%` }}
                          />
                        </div>
                        {iopsUsagePct > 100 && isGp2 && (
                          <p className="text-[10px] text-red-600 dark:text-red-400 mt-1">⚠ Avg IOPS exceeds baseline — burst credits are draining. Upgrade to gp3 for guaranteed IOPS.</p>
                        )}
                      </div>
                    )}

                    {/* Burst Credit Balance (gp2 only) */}
                    {isGp2 && (metrics.burstBalance ?? 0) > 0 && (
                      <div className="px-3 py-2.5">
                        <div className="flex items-center justify-between text-[11px] mb-1.5">
                          <Tip text="gp2 burst credit bucket. Earns 3 credits/GB/s when IOPS is below baseline; spends credits above baseline (up to 3,000 IOPS). At 0% you are throttled to baseline IOPS with no burst. gp3 has no credit system — baseline is always guaranteed." wide>
                            <span className="text-gray-600 dark:text-gray-300 underline decoration-dotted cursor-help">Burst Credit Balance</span>
                          </Tip>
                          <span className={`font-semibold ${(metrics.burstBalance ?? 0) < 20 ? 'text-red-600' : (metrics.burstBalance ?? 0) < 50 ? 'text-orange-500' : 'text-green-600'}`}>
                            {Math.round(metrics.burstBalance ?? 0)}%
                          </span>
                        </div>
                        <div className="h-2 rounded-full bg-gray-100 dark:bg-gray-700 overflow-hidden">
                          <div
                            className={`h-full rounded-full ${(metrics.burstBalance ?? 0) < 20 ? 'bg-red-500' : (metrics.burstBalance ?? 0) < 50 ? 'bg-orange-500' : 'bg-emerald-500'}`}
                            style={{ width: `${Math.round(metrics.burstBalance ?? 0)}%` }}
                          />
                        </div>
                        {(metrics.burstBalance ?? 0) < 20 && (
                          <p className="text-[10px] text-red-600 dark:text-red-400 mt-1">⚠ Critical — upgrade to gp3 storage for consistent IOPS without burst limits.</p>
                        )}
                      </div>
                    )}

                    {/* Disk Queue Depth */}
                    {(metrics.avgDiskQueueDepth ?? 0) > 0 && (
                      <div className="px-3 py-2.5 flex items-center justify-between text-[11px]">
                        <Tip text="Average pending I/O requests waiting for disk. Above 1 means the disk cannot keep up — requests are queuing. This causes latency spikes regardless of IOPS quota. Consider provisioned IOPS (io1/io2) or increasing gp3 IOPS allocation." wide>
                          <span className="text-gray-600 dark:text-gray-300 underline decoration-dotted cursor-help">Disk Queue Depth (avg 7d)</span>
                        </Tip>
                        <span className={`font-semibold tabular-nums ${(metrics.avgDiskQueueDepth ?? 0) > 1 ? 'text-orange-600' : 'text-gray-700 dark:text-gray-200'}`}>
                          {round2(metrics.avgDiskQueueDepth ?? 0)}{(metrics.avgDiskQueueDepth ?? 0) > 1 && ' ⚠'}
                        </span>
                      </div>
                    )}

                    {/* Swap Usage */}
                    {(metrics.avgSwapUsageMb ?? 0) > 0 && (
                      <div className="px-3 py-2.5 flex items-center justify-between text-[11px]">
                        <Tip text="Average swap memory used. Any swap activity degrades DB performance — the OS is spilling data from RAM to disk. Increase instance memory or reduce buffer pool size." wide>
                          <span className="text-gray-600 dark:text-gray-300 underline decoration-dotted cursor-help">Swap Usage (avg 7d)</span>
                        </Tip>
                        <span className={`font-semibold tabular-nums ${(metrics.avgSwapUsageMb ?? 0) > 100 ? 'text-orange-600' : 'text-yellow-600'}`}>
                          {round2(metrics.avgSwapUsageMb ?? 0)} MB
                        </span>
                      </div>
                    )}
                  </div>
                </div>
              );
            })()}

            {/* ── Agent timeseries charts ── */}
            {instanceAgentTs && instanceAgentTs.snapshots.length >= 2 && (() => {
              const isPostgres = agentMetrics?.dbEngine === 'postgres';
              const snaps = instanceAgentTs.snapshots;
              const domainMin = new Date(snaps[1].ts).getTime();
              const domainMax = new Date(snaps[snaps.length - 1].ts).getTime();
              const sharedDomain: [number, number] = [domainMin, domainMax];

              const qpsData  = agentChartRate(snaps, 'totalQueries');
              const connData = agentChartDirect(snaps, 'connections');

              const peakQps  = qpsData.length  ? qpsData.reduce((a, b)  => a.v > b.v ? a : b) : null;
              const peakConn = connData.length ? connData.reduce((a, b) => a.v > b.v ? a : b) : null;

              return (
                <div className="space-y-3">
                  {/* Section header */}
                  <div className="flex items-center gap-2">
                    <p className="text-[11px] font-semibold text-gray-500 dark:text-gray-400 uppercase tracking-wider flex-1">
                      Agent Metrics
                    </p>
                    <span className="px-2 py-0.5 rounded-full text-[10px] bg-purple-100 dark:bg-purple-900/30 text-purple-600 dark:text-purple-400 border border-purple-200/50 dark:border-purple-700/40 flex items-center gap-1">
                      <i className="fas fa-circle text-[6px] animate-pulse" /> Live · {snaps.length} snapshots
                    </span>
                  </div>

                  {/* Summary bar */}
                  {(peakConn || peakQps) && (
                    <div className="flex flex-wrap items-center gap-x-5 gap-y-1 px-3 py-2.5 rounded-xl bg-purple-50/70 dark:bg-purple-950/30 border border-purple-200/50 dark:border-purple-800/30 text-[11px]">
                      <span className="flex items-center gap-1 font-semibold text-purple-500 dark:text-purple-400 shrink-0">
                        <i className="fas fa-bolt text-[10px]" /> Peaks
                      </span>
                      {peakConn && (
                        <span className="text-gray-600 dark:text-gray-300">
                          Connections <span className="font-semibold text-sky-600 dark:text-sky-400">{Math.round(peakConn.v)}</span>
                          <span className="text-gray-400 ml-1">@ {fmtTime(peakConn.ts)}</span>
                        </span>
                      )}
                      {peakQps && (
                        <span className="text-gray-600 dark:text-gray-300">
                          QPS spike <span className="font-semibold text-indigo-600 dark:text-indigo-400">{round2(peakQps.v)}/s</span>
                          <span className="text-gray-400 ml-1">@ {fmtTime(peakQps.ts)}</span>
                        </span>
                      )}
                    </div>
                  )}

                  <div className="grid grid-cols-2 gap-3">
                    <PerfChart title="Active Connections"      data={connData}                                  color="#0ea5e9"             domain={sharedDomain} syncId="agent_perf" />
                    <PerfChart title="Connection Utilization"  data={agentChartConnUtilPct(snaps)}              color="#f43f5e" unit="%"    domain={sharedDomain} syncId="agent_perf" />
                    {!isPostgres && (
                      <PerfChart title="Buffer Pool Hit %"     data={agentChartDirect(snaps, 'bufferPoolHitPct')} color="#10b981" unit="%" domain={sharedDomain} syncId="agent_perf" />
                    )}
                    <PerfChart title="Query Throughput (QPS)"  data={qpsData}                                   color="#6366f1" unit="/s"   domain={sharedDomain} syncId="agent_perf" />
                    {isPostgres ? (
                      <PerfChart title="Slow Query Patterns"   data={agentChartDirect(snaps, 'totalSlowQueries')} color="#ef4444" unit=" patterns" domain={sharedDomain} syncId="agent_perf" />
                    ) : (
                      <PerfChart title="Slow Query Rate"       data={agentChartRate(snaps, 'totalSlowQueries')} color="#ef4444" unit="/s"   domain={sharedDomain} syncId="agent_perf" />
                    )}
                    <PerfChart title="SELECT/s"               data={agentChartRate(snaps, 'comSelect')}        color="#3b82f6" unit="/s"   domain={sharedDomain} syncId="agent_perf" />
                    <PerfChart title="INSERT/s"               data={agentChartRate(snaps, 'comInsert')}        color="#22c55e" unit="/s"   domain={sharedDomain} syncId="agent_perf" />
                    <PerfChart title="UPDATE/s"               data={agentChartRate(snaps, 'comUpdate')}        color="#f59e0b" unit="/s"   domain={sharedDomain} syncId="agent_perf" />
                    <PerfChart title="DELETE/s"               data={agentChartRate(snaps, 'comDelete')}        color="#f43f5e" unit="/s"   domain={sharedDomain} syncId="agent_perf" />
                    <PerfChart title="Active Threads"         data={agentChartDirect(snaps, 'threadsRunning')} color="#a78bfa"             domain={sharedDomain} syncId="agent_perf" />
                    {!isPostgres && (
                      <PerfChart title="Row Lock Waits/s"     data={agentChartRate(snaps, 'innodbRowLockWaits')} color="#fb923c" unit="/s" domain={sharedDomain} syncId="agent_perf" />
                    )}
                    <PerfChart title="Deadlocks/s"            data={agentChartRate(snaps, 'innodbDeadlocks')}  color="#ef4444" unit="/s"   domain={sharedDomain} syncId="agent_perf" />
                  </div>
                </div>
              );
            })()}

            {/* Table Sizes (from DB agent) */}
            {instanceTableSizes.length > 0 && (
              <div className="space-y-2">
                <p className="text-[11px] font-semibold text-gray-400 dark:text-gray-500 uppercase tracking-wider">
                  Table Sizes
                  <span className="ml-1.5 normal-case font-normal text-purple-500">
                    (from DB agent · top {instanceTableSizes.length} tables)
                  </span>
                </p>
                <div className="overflow-x-auto rounded-xl border border-gray-200/60 dark:border-gray-700/40 shadow-sm">
                  <table className="w-full text-xs">
                    <thead className="bg-gray-50/80 dark:bg-gray-700/50">
                      <tr>
                        <th className="px-3 py-2 text-left text-gray-500 dark:text-gray-400 font-medium">Table</th>
                        <th className="px-3 py-2 text-right text-gray-500 dark:text-gray-400 font-medium">Data</th>
                        <th className="px-3 py-2 text-right text-gray-500 dark:text-gray-400 font-medium">Indexes</th>
                        <th className="px-3 py-2 text-right text-gray-500 dark:text-gray-400 font-medium">Total</th>
                        <th className="px-3 py-2 text-right text-gray-500 dark:text-gray-400 font-medium">Rows</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100/80 dark:divide-gray-700/40">
                      {instanceTableSizes.map((t, i) => (
                        <tr key={i} className="hover:bg-gray-50/60 dark:hover:bg-gray-700/20 transition-colors">
                          <td className="px-3 py-2 font-mono text-gray-800 dark:text-gray-200">
                            <span className="text-gray-400 dark:text-gray-500">{t.schemaName}.</span>{t.tableName}
                          </td>
                          <td className="px-3 py-2 text-right text-gray-600 dark:text-gray-400">{fmtBytes(t.dataSizeBytes)}</td>
                          <td className="px-3 py-2 text-right text-gray-600 dark:text-gray-400">{fmtBytes(t.indexSizeBytes)}</td>
                          <td className="px-3 py-2 text-right font-medium text-gray-800 dark:text-gray-200">{fmtBytes(t.totalSizeBytes)}</td>
                          <td className="px-3 py-2 text-right text-gray-600 dark:text-gray-400">{t.rows.toLocaleString()}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            )}

            {/* Empty state — futuristic illustration */}
            {instance.source !== 'rds' && !instanceAgentTs && (
              <div className="flex flex-col items-center justify-center py-14 gap-3">
                <svg width="96" height="60" viewBox="0 0 96 60" fill="none" className="text-gray-300 dark:text-gray-600">
                  <rect x="0" y="58" width="96" height="1" stroke="currentColor" strokeDasharray="4 3" />
                  <polyline points="0,50 14,42 28,46 42,30 56,35 70,18 84,24 96,12"
                    stroke="currentColor" strokeWidth="1.5" strokeDasharray="5 3" fill="none" />
                  <circle cx="42" cy="30" r="5" stroke="currentColor" strokeWidth="1.5" fill="none" strokeDasharray="3 2" />
                  <circle cx="70" cy="18" r="5" stroke="currentColor" strokeWidth="1.5" fill="none" strokeDasharray="3 2" />
                  <line x1="39" y1="4" x2="45" y2="10" stroke="currentColor" strokeWidth="1" strokeOpacity="0.5" />
                  <line x1="45" y1="4" x2="39" y2="10" stroke="currentColor" strokeWidth="1" strokeOpacity="0.5" />
                </svg>
                <p className="text-sm font-medium text-gray-400 dark:text-gray-500">No performance data</p>
                <p className="text-[11px] text-gray-400 dark:text-gray-500 text-center max-w-xs leading-relaxed">
                  Connect the DB agent and wait for a few pushes — charts appear after 2+ snapshots are collected.
                </p>
              </div>
            )}
            {instanceAgentTs && instanceAgentTs.snapshots.length < 2 && (
              <div className="flex items-center justify-center gap-2 py-5 text-[11px] text-gray-400">
                <i className="fas fa-circle-notch fa-spin text-indigo-400" />
                Agent connected — collecting snapshots. Charts appear after 2+ data points.
              </div>
            )}
          </div>
        )}

        {/* ── SLOW QUERIES ── */}
        {tab === 'slowqueries' && (
          <SlowQueriesTab
            cloudwatchQueries={instanceSlowQueries}
            agentQueries={instanceAgentStats}
            isPostgres={agentMetrics?.dbEngine === 'postgres'}
          />
        )}

        {/* ── QUERY STATS (Agent) ── */}
        {tab === 'queries' && (
          <QueryStatsTab
            stats={instanceAgentStats}
            engine={instance.engine ?? 'mysql'}
            instanceId={instance.instanceId}
            endpoint={instance.rdsData?.endpoint}
          />
        )}

        {/* ── MAINTENANCE (Vacuum / Table Bloat) ── */}
        {tab === 'maintenance' && (
          <MaintenanceTab
            vacuumStats={vacuumStats}
            indexUsage={indexUsage}
            tableActivity={tableActivity}
            agentHost={instance.agentHost}
            rdsEndpoint={instance.rdsData?.endpoint}
          />
        )}

        {/* ── USERS ── */}
        {tab === 'users' && (
          <UsersTab
            userActivity={userActivity}
            agentHost={instance.agentHost}
            rdsEndpoint={instance.rdsData?.endpoint}
          />
        )}

        {/* ── COST & SAVINGS ── */}
        {tab === 'cost' && (
          <CostTab
            rd={rd}
            instanceIdle={instanceIdle}
            instanceRightsizing={instanceRightsizing}
            costs={costs}
            agentMetrics={agentMetrics}
            instanceSource={instance.source}
          />
        )}

        {/* ── SECURITY ── */}
        {tab === 'security' && (
          <SecurityTab
            checks={instanceSecurity}
            instanceSource={instance.source}
          />
        )}

        {/* ── SNAPSHOTS ── */}
        {tab === 'snapshots' && (
          <SnapshotsTab
            snapshots={instanceSnapshots}
            rd={rd}
            instanceId={instance.instanceId}
          />
        )}

      </div>
    </div>
  );
}
