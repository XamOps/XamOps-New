import { useState, useEffect } from 'react';
import apiClient from '../../utils/apiClient';
import { useAccounts } from '../../contexts/AccountContext';
import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip,
  ResponsiveContainer,
} from 'recharts';
import {
  DbInstance, DbMetrics, DbInstanceTimeseries, TimeseriesPoint,
  AgentQueryStat, SlowQuery, AgentInstanceMetrics,
  DbIdleInstance, DbRightsizing, DbSecurityCheck, DbSnapshot,
  AgentTimeseries, AgentTableSize,
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

// ── Metric chart ──────────────────────────────────────────────────────────────
function MetricChart({
  title, data, color, unit, dataKey = 'v', domain,
}: {
  title: string;
  data: { ts: number; v: number; label: string }[];
  color: string;
  unit?: string;
  dataKey?: string;
  domain?: [number, number];
}) {
  if (!data.length) {
    return (
      <div className="bg-gray-50 dark:bg-gray-700/30 rounded-lg p-4">
        <p className="text-xs font-medium text-gray-600 dark:text-gray-400 mb-2">{title}</p>
        <div className="h-28 flex items-center justify-center text-xs text-gray-400">No data</div>
      </div>
    );
  }
  return (
    <div className="bg-gray-50 dark:bg-gray-700/30 rounded-lg p-4">
      <p className="text-xs font-medium text-gray-600 dark:text-gray-400 mb-2">{title}</p>
      <ResponsiveContainer width="100%" height={110}>
        <AreaChart data={data} margin={{ top: 2, right: 4, left: -20, bottom: 0 }}>
          <defs>
            <linearGradient id={`grad_${title.replace(/\W+/g, '_')}`} x1="0" y1="0" x2="0" y2="1">
              <stop offset="5%" stopColor={color} stopOpacity={0.3} />
              <stop offset="95%" stopColor={color} stopOpacity={0} />
            </linearGradient>
          </defs>
          <CartesianGrid strokeDasharray="3 3" stroke="#e5e7eb" strokeOpacity={0.5} />
          <XAxis dataKey="ts" scale="time" type="number" domain={domain ?? ['dataMin', 'dataMax']}
            tick={{ fontSize: 9 }} tickFormatter={(ts: number) => fmtTime(ts)} />
          <YAxis tick={{ fontSize: 9 }} />
          <Tooltip
            labelFormatter={(ts) => fmtLabel(Number(ts))}
            formatter={(v) => [`${round2(Number(v))}${unit ? ' ' + unit : ''}`, title] as [string, string]}
            contentStyle={{ fontSize: 11 }}
          />
          <Area type="monotone" dataKey={dataKey} stroke={color} fill={`url(#grad_${title.replace(/\W+/g, '_')})`}
            strokeWidth={1.5} dot={false} />
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
    '  adityamehta3003108/xamops-agent:2',
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

// ── Status/Severity Badges ────────────────────────────────────────────────────
function StatusBadge({ status }: { status: string }) {
  const map: Record<string, string> = {
    PASS: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400',
    FAIL: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400',
    WARN: 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-400',
  };
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-semibold ${map[status] ?? 'bg-gray-100 text-gray-600'}`}>
      {status === 'PASS' && <i className="fas fa-check mr-1" />}
      {status === 'FAIL' && <i className="fas fa-times mr-1" />}
      {status === 'WARN' && <i className="fas fa-exclamation mr-1" />}
      {status}
    </span>
  );
}

function SeverityBadge({ severity }: { severity: string }) {
  const map: Record<string, string> = {
    HIGH: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400',
    MEDIUM: 'bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-400',
    INFO: 'bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400',
  };
  return (
    <span className={`inline-flex px-2 py-0.5 rounded text-xs font-medium ${map[severity] ?? 'bg-gray-100 text-gray-600'}`}>
      {severity}
    </span>
  );
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
  fetchTimeseries: (id: string, hours: number) => Promise<DbInstanceTimeseries | null>;
  fetchAgentTimeseries: (hours: number) => Promise<AgentTimeseries[]>;
  fetchAgentTableSizes: () => Promise<AgentTableSize[]>;
  onConnected?: (inst: DrawerInstance) => void;
}

type DetailTab = 'overview' | 'performance' | 'slowqueries' | 'queries' | 'cost' | 'security' | 'snapshots';

// ── Main component ─────────────────────────────────────────────────────────────
export default function DbInstanceDetailDrawer({
  instance, metrics, agentStats, agentInstanceMetrics, slowQueries,
  idle, rightsizing, security, allSnapshots,
  fetchTimeseries, fetchAgentTimeseries, fetchAgentTableSizes, onConnected,
}: Props) {
  const [tab, setTab] = useState<DetailTab>('overview');
  const [timeseries, setTimeseries] = useState<DbInstanceTimeseries | null>(null);
  const [agentTs, setAgentTs] = useState<AgentTimeseries[]>([]);
  const [tableSizes, setTableSizes] = useState<AgentTableSize[]>([]);
  const [tsLoading, setTsLoading] = useState(false);
  const [tsHours, setTsHours] = useState(24);
  const [querySearch, setQuerySearch] = useState('');

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
    setTab('overview');
    setTimeseries(null);
    setAgentTs([]);
    setTableSizes([]);
    setQuerySearch('');
  }, [instance?.instanceId]);

  if (!instance) return null;

  // "Connect a Database" mode — blank instanceId, show wizard-only layout (no tabs)
  if (!instance.instanceId && instance.source === 'agent') {
    return (
      <div className="flex flex-col flex-1 bg-white dark:bg-gray-900 min-h-0">
        <div className="flex-1 overflow-y-auto px-6 py-6">
          <div className="max-w-lg mx-auto">
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
      </div>
    );
  }

  const rd = instance.rdsData;

  const { selectedAccountIds } = useAccounts();
  const [agentDisconnecting, setAgentDisconnecting] = useState(false);
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

  const filteredAgentStats = instanceAgentStats.filter(s =>
    !querySearch || s.queryDigest?.toLowerCase().includes(querySearch.toLowerCase())
  );

  const tabs: { id: DetailTab; label: string; badge?: number }[] = [
    { id: 'overview', label: 'Overview' },
    { id: 'performance', label: 'Performance' },
    { id: 'slowqueries', label: `Slow Queries${slowQueriesTabCount ? ` (${slowQueriesTabCount})` : ''}` },
    { id: 'queries',     label: `Query Stats${instanceAgentStats.length ? ` (${instanceAgentStats.length})` : ''}` },
    { id: 'cost',        label: 'Cost & Savings' },
    { id: 'security',    label: `Security${instanceSecurity.filter(c => c.status === 'FAIL').length ? ` (${instanceSecurity.filter(c => c.status === 'FAIL').length})` : ''}` },
    { id: 'snapshots',   label: `Snapshots${instanceSnapshots.length ? ` (${instanceSnapshots.length})` : ''}` },
  ];

  return (
    <div className="flex flex-col flex-1 bg-white dark:bg-gray-900 min-h-0">

      {/* Tab bar */}
      <div className="flex gap-0 px-6 pt-3 border-b border-gray-100 dark:border-gray-700 shrink-0 bg-white dark:bg-gray-900 overflow-x-auto">
        {tabs.map(t => (
          <button
            key={t.id}
            onClick={() => setTab(t.id)}
            className={`px-3 py-1.5 text-xs font-medium rounded-t-md transition-colors whitespace-nowrap ${tab === t.id
                ? 'bg-white dark:bg-gray-800 text-indigo-600 dark:text-indigo-400 border border-b-white dark:border-gray-700 dark:border-b-gray-800 border-b-transparent -mb-px'
                : 'text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-300'
              }`}
          >{t.label}</button>
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
            {metrics && (
              <div>
                <p className="text-xs font-semibold text-gray-500 dark:text-gray-400 uppercase tracking-wider mb-2">Live Metrics (7d avg)</p>
                <div className="grid grid-cols-3 gap-2">
                  <StatCard label="CPU" value={`${metrics.avgCpuPercent}%`}
                    color={metrics.avgCpuPercent > 80 ? 'text-red-600' : metrics.avgCpuPercent > 60 ? 'text-yellow-600' : 'text-gray-900 dark:text-gray-100'} />
                  <StatCard label="Connections" value={metrics.avgConnections} />
                  <StatCard label="Free Memory" value={round2(metrics.avgFreeableMemoryMb)} unit="MB" />
                  <StatCard label="Read IOPS" value={metrics.avgReadIops} />
                  <StatCard label="Write IOPS" value={metrics.avgWriteIops} />
                  <StatCard label="Read Latency" value={metrics.avgReadLatencyMs} unit="ms" />
                  <StatCard label="Write Latency" value={metrics.avgWriteLatencyMs} unit="ms" />
                  <StatCard label="Free Storage" value={metrics.freeStorageSpaceGb} unit="GB" />
                  <StatCard label="Network In" value={metrics.avgNetworkReceiveMbps} unit="MB/s" />
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
                  {agentMetrics.innodbBufferPoolHitPct > 0 && (
                    <StatCard label="Buffer Pool Hit" value={`${Math.round(agentMetrics.innodbBufferPoolHitPct)}%`}
                      color={agentMetrics.innodbBufferPoolHitPct < 90 ? 'text-orange-600' : 'text-green-600'}
                      tooltip="% of data reads served from RAM (InnoDB buffer pool) vs. disk. Below 90% means frequent disk I/O — increase innodb_buffer_pool_size to fit your working set in memory." />
                  )}
                  <StatCard label="Uptime" value={agentMetrics.uptime > 86400
                    ? `${Math.floor(agentMetrics.uptime / 86400)}d`
                    : `${Math.floor(agentMetrics.uptime / 3600)}h`}
                    tooltip="Time since the database server last restarted. Frequent restarts indicate crashes or OOM kills. Note: cumulative counters (Total Queries, Slow Queries) reset on restart." />
                </div>
                {agentMetrics.comSelect + agentMetrics.comInsert + agentMetrics.comUpdate + agentMetrics.comDelete > 0 && (
                  <div className="mt-2 bg-gray-50 dark:bg-gray-700/30 rounded-lg p-3">
                    <p className="text-xs text-gray-500 mb-2">Query Type Breakdown</p>
                    <div className="grid grid-cols-4 gap-2 text-xs text-center">
                      {[
                        { label: 'SELECT', val: agentMetrics.comSelect, color: 'text-blue-600' },
                        { label: 'INSERT', val: agentMetrics.comInsert, color: 'text-green-600' },
                        { label: 'UPDATE', val: agentMetrics.comUpdate, color: 'text-yellow-600' },
                        { label: 'DELETE', val: agentMetrics.comDelete, color: 'text-red-600' },
                      ].map(({ label, val, color }) => (
                        <div key={label}>
                          <div className="text-gray-400">{label}</div>
                          <div className={`font-semibold ${color}`}>{val.toLocaleString()}</div>
                        </div>
                      ))}
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
                <p className="text-xs font-semibold text-gray-500 dark:text-gray-400 uppercase tracking-wider mb-2">Top Query (by total time)</p>
                <div className="bg-gray-50 dark:bg-gray-700/30 rounded-lg p-3">
                  <code className="text-xs text-indigo-700 dark:text-indigo-300 break-all">{instanceAgentStats[0].queryDigest}</code>
                  <div className="flex gap-4 mt-2 text-xs text-gray-500">
                    <span>Calls: <strong>{instanceAgentStats[0].callCount}</strong></span>
                    <span>Avg: <strong>{round2(instanceAgentStats[0].avgTimeSeconds * 1000)}ms</strong></span>
                    <span>Max: <strong>{round2(instanceAgentStats[0].maxTimeSeconds * 1000)}ms</strong></span>
                  </div>
                </div>
              </div>
            )}

            {/* Agent management — disconnect */}
            {instance.source === 'agent' && (agentMetrics || instanceAgentStats.length > 0) && (
              <div className="border-t border-gray-100 dark:border-gray-700 pt-4">
                <p className="text-xs font-semibold text-gray-500 dark:text-gray-400 uppercase tracking-wider mb-2">Agent Management</p>
                <div className="flex items-center justify-between bg-gray-50 dark:bg-gray-700/30 rounded-lg px-4 py-3">
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
              </div>
            )}
          </div>
        )}

        {/* ── PERFORMANCE CHARTS ── */}
        {tab === 'performance' && (
          <div className="space-y-4">
            {/* Time range selector */}
            <div className="flex items-center gap-2">
              <span className="text-xs text-gray-500">Time range:</span>
              {[6, 24, 48, 168].map(h => (
                <button key={h} onClick={() => setTsHours(h)}
                  className={`px-2 py-1 text-xs rounded-md ${tsHours === h ? 'bg-indigo-600 text-white' : 'bg-gray-100 dark:bg-gray-700 text-gray-600 dark:text-gray-300 hover:bg-gray-200 dark:hover:bg-gray-600'}`}>
                  {h < 24 ? `${h}h` : h === 168 ? '7d' : `${h / 24}d`}
                </button>
              ))}
              {tsLoading && <i className="fas fa-spinner fa-spin text-indigo-400 text-xs ml-2" />}
            </div>

            {/* CloudWatch charts (RDS instances only) */}
            {instance.source === 'rds' && (
              <div className="grid grid-cols-2 gap-3">
                <MetricChart title="CPU Utilization" data={toChartData(timeseries?.cpu)} color="#6366f1" unit="%" />
                <MetricChart title="Connections" data={toChartData(timeseries?.connections)} color="#0ea5e9" />
                <MetricChart title="Read IOPS" data={toChartData(timeseries?.readIops)} color="#10b981" unit="IOPS" />
                <MetricChart title="Write IOPS" data={toChartData(timeseries?.writeIops)} color="#f59e0b" unit="IOPS" />
                <MetricChart title="Read Latency" data={toChartData(timeseries?.readLatencyMs)} color="#8b5cf6" unit="ms" />
                <MetricChart title="Write Latency" data={toChartData(timeseries?.writeLatencyMs)} color="#ef4444" unit="ms" />
                <MetricChart title="Free Storage" data={toChartData(timeseries?.freeStorageGb)} color="#14b8a6" unit="GB" />
                <MetricChart title="Freeable Memory" data={toChartData(timeseries?.freeableMemoryMb)} color="#f97316" unit="MB" />
                {(timeseries?.replicaLagSeconds?.length ?? 0) > 0 && (
                  <MetricChart title="Replication Lag" data={toChartData(timeseries?.replicaLagSeconds)} color="#a855f7" unit="s" />
                )}
              </div>
            )}

            {/* Agent timeseries charts (shown for agent instances, or alongside CloudWatch for RDS) */}
            {instanceAgentTs && instanceAgentTs.snapshots.length >= 2 && (
              <div className="space-y-3">
                <p className="text-xs font-semibold text-gray-500 dark:text-gray-400 uppercase tracking-wider">
                  Agent Metrics <span className="text-purple-500 ml-1 normal-case font-normal">(from DB agent · {instanceAgentTs.snapshots.length} snapshots)</span>
                </p>
                {(() => {
                  const isPostgres = agentMetrics?.dbEngine === 'postgres';
                  // Shared x-axis domain: start from 2nd snapshot so direct + rate charts align
                  const snaps = instanceAgentTs.snapshots;
                  const domainMin = snaps.length >= 2 ? new Date(snaps[1].ts).getTime() : new Date(snaps[0].ts).getTime();
                  const domainMax = new Date(snaps[snaps.length - 1].ts).getTime();
                  const sharedDomain: [number, number] = [domainMin, domainMax];
                  return (
                    <div className="grid grid-cols-2 gap-3">
                      <MetricChart title="Active Connections" data={agentChartDirect(snaps, 'connections')} color="#0ea5e9" domain={sharedDomain} />
                      <MetricChart title="Connection Utilization" data={agentChartConnUtilPct(snaps)} color="#f43f5e" unit="%" domain={sharedDomain} />
                      {!isPostgres && (
                        <MetricChart title="Buffer Pool Hit %" data={agentChartDirect(snaps, 'bufferPoolHitPct')} color="#10b981" unit="%" domain={sharedDomain} />
                      )}
                      <MetricChart title="Query Throughput (QPS)" data={agentChartRate(snaps, 'totalQueries')} color="#6366f1" unit="/s" domain={sharedDomain} />
                      {isPostgres ? (
                        <MetricChart title="Slow Query Patterns" data={agentChartDirect(snaps, 'totalSlowQueries')} color="#ef4444" unit=" patterns" domain={sharedDomain} />
                      ) : (
                        <MetricChart title="Slow Query Rate" data={agentChartRate(snaps, 'totalSlowQueries')} color="#ef4444" unit="/s" domain={sharedDomain} />
                      )}
                      <MetricChart title="SELECT/s" data={agentChartRate(snaps, 'comSelect')} color="#3b82f6" unit="/s" domain={sharedDomain} />
                      <MetricChart title="INSERT/s" data={agentChartRate(snaps, 'comInsert')} color="#22c55e" unit="/s" domain={sharedDomain} />
                      <MetricChart title="UPDATE/s" data={agentChartRate(snaps, 'comUpdate')} color="#f59e0b" unit="/s" domain={sharedDomain} />
                      <MetricChart title="DELETE/s" data={agentChartRate(snaps, 'comDelete')} color="#f43f5e" unit="/s" domain={sharedDomain} />
                      <MetricChart title="Active Threads" data={agentChartDirect(snaps, 'threadsRunning')} color="#a78bfa" domain={sharedDomain} />
                      {!isPostgres && (
                        <MetricChart title="Row Lock Waits/s" data={agentChartRate(snaps, 'innodbRowLockWaits')} color="#fb923c" unit="/s" domain={sharedDomain} />
                      )}
                      <MetricChart title="Deadlocks/s" data={agentChartRate(snaps, 'innodbDeadlocks')} color="#ef4444" unit="/s" domain={sharedDomain} />
                    </div>
                  );
                })()}
              </div>
            )}

            {/* Table Sizes (from DB agent) */}
            {instanceTableSizes.length > 0 && (
              <div className="space-y-2">
                <p className="text-xs font-semibold text-gray-500 dark:text-gray-400 uppercase tracking-wider">
                  Table Sizes <span className="text-purple-500 ml-1 normal-case font-normal">(from DB agent · top {instanceTableSizes.length} tables)</span>
                </p>
                <div className="overflow-x-auto rounded-lg border border-gray-200 dark:border-gray-700">
                  <table className="w-full text-xs">
                    <thead className="bg-gray-50 dark:bg-gray-700/50">
                      <tr>
                        <th className="px-3 py-2 text-left text-gray-500 dark:text-gray-400 font-medium">Table</th>
                        <th className="px-3 py-2 text-right text-gray-500 dark:text-gray-400 font-medium">Data</th>
                        <th className="px-3 py-2 text-right text-gray-500 dark:text-gray-400 font-medium">Indexes</th>
                        <th className="px-3 py-2 text-right text-gray-500 dark:text-gray-400 font-medium">Total</th>
                        <th className="px-3 py-2 text-right text-gray-500 dark:text-gray-400 font-medium">Rows</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100 dark:divide-gray-700/50">
                      {instanceTableSizes.map((t, i) => (
                        <tr key={i} className="hover:bg-gray-50 dark:hover:bg-gray-700/20">
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

            {/* Empty state */}
            {instance.source !== 'rds' && !instanceAgentTs && (
              <div className="text-center py-8 text-sm text-gray-400">
                No performance data yet. Connect the DB agent and wait for a few pushes — charts appear after 2+ snapshots are collected.
              </div>
            )}
            {instanceAgentTs && instanceAgentTs.snapshots.length < 2 && (
              <div className="text-xs text-gray-400 text-center py-2">
                Agent connected — collecting snapshots. Charts appear after 2+ data points (agent pushes every ~1 min).
              </div>
            )}
          </div>
        )}

        {/* ── SLOW QUERIES ── */}
        {tab === 'slowqueries' && (() => {
          // Agent-derived slow queries: top queries by avg_time_sec > 10ms, sorted slowest first
          const agentSlowQueries = instanceAgentStats
            .filter(s => s.avgTimeSeconds > 0.01)
            .sort((a, b) => b.avgTimeSeconds - a.avgTimeSeconds)
            .slice(0, 25);

          if (instanceSlowQueries.length > 0) {
            // CloudWatch slow query log data
            return (
              <div className="space-y-2">
                <p className="text-xs text-gray-400">From RDS slow query log (CloudWatch Logs)</p>
                {instanceSlowQueries.map((q, i) => (
                  <div key={i} className="bg-gray-50 dark:bg-gray-700/30 rounded-lg p-3">
                    <code className="text-xs text-red-700 dark:text-red-400 break-all block mb-2">{q.query}</code>
                    <div className="grid grid-cols-4 gap-2 text-xs">
                      <div className="text-center"><div className="text-gray-400">Query Time</div><div className="font-semibold text-red-600">{round2(q.queryTimeSeconds)}s</div></div>
                      <div className="text-center"><div className="text-gray-400">Lock Time</div><div className="font-semibold text-gray-800 dark:text-gray-200">{round2(q.lockTimeSeconds)}s</div></div>
                      <div className="text-center"><div className="text-gray-400">Rows Examined</div><div className="font-semibold text-gray-800 dark:text-gray-200">{q.rowsExamined}</div></div>
                      <div className="text-center"><div className="text-gray-400">Rows Sent</div><div className="font-semibold text-gray-800 dark:text-gray-200">{q.rowsSent}</div></div>
                    </div>
                    {q.queryTimestamp && <div className="text-xs text-gray-400 mt-1">{new Date(q.queryTimestamp).toLocaleString()}</div>}
                  </div>
                ))}
              </div>
            );
          }

          if (agentSlowQueries.length > 0) {
            // Fallback: derive slow queries from agent performance_schema data
            return (
              <div className="space-y-3">
                <div className="flex items-center gap-2 px-3 py-2 bg-blue-50 dark:bg-blue-900/20 border border-blue-100 dark:border-blue-800 rounded-lg">
                  <i className="fas fa-info-circle text-blue-500 text-xs" />
                  <p className="text-xs text-blue-700 dark:text-blue-300">
                    Showing queries with avg execution &gt;10ms from <strong>performance_schema</strong>.
                    For per-query timestamps, enable slow query log on RDS and publish to CloudWatch.
                  </p>
                </div>
                <div className="space-y-2">
                  {agentSlowQueries.map((s, i) => (
                    <div key={i} className="bg-gray-50 dark:bg-gray-700/30 rounded-lg p-3">
                      <div className="flex items-start justify-between gap-2 mb-2">
                        <code className="text-xs text-red-700 dark:text-red-400 break-all flex-1">{s.queryDigest}</code>
                        {s.noIndexUsed && (
                          <span className="shrink-0 text-[10px] bg-red-100 dark:bg-red-900/30 text-red-600 dark:text-red-400 px-1.5 py-0.5 rounded font-medium">NO INDEX</span>
                        )}
                      </div>
                      <div className="grid grid-cols-4 gap-2 text-xs">
                        <div className="text-center"><div className="text-gray-400">Avg</div><div className="font-semibold text-red-600">{round2(s.avgTimeSeconds * 1000)}ms</div></div>
                        <div className="text-center"><div className="text-gray-400">Max</div><div className="font-semibold text-orange-600">{round2(s.maxTimeSeconds * 1000)}ms</div></div>
                        <div className="text-center"><div className="text-gray-400">Calls</div><div className="font-semibold text-gray-800 dark:text-gray-200">{s.callCount}</div></div>
                        <div className="text-center"><div className="text-gray-400">Lock</div><div className="font-semibold text-gray-800 dark:text-gray-200">{round2(s.lockTimeSeconds * 1000)}ms</div></div>
                      </div>
                      <div className="flex gap-3 mt-1.5 text-xs text-gray-400">
                        <span>Rows examined: {s.rowsExamined}</span>
                        <span>Rows sent: {s.rowsSent}</span>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            );
          }

          return (
            <div className="text-center py-12">
              <i className="fas fa-search text-3xl text-gray-300 dark:text-gray-600 mb-3 block" />
              <p className="text-sm text-gray-500 dark:text-gray-400">No slow queries detected.</p>
              <p className="text-xs text-gray-400 dark:text-gray-500 mt-1">
                Deploy the XamOps DB agent (Query Stats tab) to collect performance_schema data,<br />
                or enable slow query log on RDS and publish to CloudWatch Logs.
              </p>
            </div>
          );
        })()}

        {/* ── QUERY STATS (Agent) ── */}
        {tab === 'queries' && (
          <div className="space-y-3">
            {instanceAgentStats.length === 0 ? (
              <AgentSetupWizard
                engine={instance.engine ?? 'mysql'}
                instanceId={instance.instanceId}
                endpoint={instance.rdsData?.endpoint}
              />
            ) : (
              <>
                <input
                  type="text" placeholder="Search queries..." value={querySearch}
                  onChange={e => setQuerySearch(e.target.value)}
                  className="w-full px-3 py-2 text-sm rounded-lg border border-gray-200 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100 placeholder-gray-400"
                />
                <div className="text-xs text-gray-400">{filteredAgentStats.length} queries from performance_schema</div>
                <div className="space-y-2">
                  {filteredAgentStats.map((s, i) => (
                    <div key={i} className="bg-gray-50 dark:bg-gray-700/30 rounded-lg p-3">
                      <code className="text-xs text-indigo-700 dark:text-indigo-300 break-all block mb-2">{s.queryDigest}</code>
                      <div className="grid grid-cols-4 gap-2 text-xs">
                        <div className="text-center"><div className="text-gray-400">Calls</div><div className="font-semibold text-gray-800 dark:text-gray-200">{s.callCount}</div></div>
                        <div className="text-center"><div className="text-gray-400">Avg</div><div className="font-semibold text-gray-800 dark:text-gray-200">{round2(s.avgTimeSeconds * 1000)}ms</div></div>
                        <div className="text-center"><div className="text-gray-400">Max</div><div className="font-semibold text-orange-600 dark:text-orange-400">{round2(s.maxTimeSeconds * 1000)}ms</div></div>
                        <div className="text-center"><div className="text-gray-400">Total</div><div className="font-semibold text-gray-800 dark:text-gray-200">{round2(s.totalTimeSeconds)}s</div></div>
                      </div>
                      <div className="flex gap-3 mt-2 text-xs text-gray-400">
                        <span>Rows examined: {s.rowsExamined}</span>
                        <span>Rows sent: {s.rowsSent}</span>
                        {s.noIndexUsed && <span className="text-red-500">⚠ No index used</span>}
                      </div>
                    </div>
                  ))}
                </div>
              </>
            )}
          </div>
        )}

        {/* ── COST & SAVINGS ── */}
        {tab === 'cost' && (
          <div className="space-y-4">
            {/* Current cost */}
            {rd && (
              <div className="bg-white/90 dark:bg-gray-800/80 rounded-2xl border border-gray-100 dark:border-gray-700/50 p-5">
                <h3 className="text-sm font-semibold text-gray-800 dark:text-gray-100 mb-3 flex items-center gap-2">
                  <i className="fas fa-dollar-sign text-sky-500" /> Current Cost
                </h3>
                <div className="grid grid-cols-3 gap-3">
                  <StatCard label="Est. Monthly Cost" value={fmt$(rd.estimatedMonthlyCost)} color="text-sky-600 dark:text-sky-400" />
                  <StatCard label="Instance Class" value={rd.instanceClass} />
                  <StatCard label="Storage" value={`${rd.allocatedStorage} GB`} />
                </div>
              </div>
            )}

            {/* Idle status */}
            {instanceIdle ? (
              <div className="bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800 rounded-2xl p-5">
                <div className="flex items-center gap-3 mb-3">
                  <i className="fas fa-moon text-amber-500 text-lg" />
                  <div>
                    <p className="font-semibold text-amber-800 dark:text-amber-300 text-sm">Idle Instance Detected</p>
                    <p className="text-xs text-amber-600 dark:text-amber-400">Low usage over the last 7 days</p>
                  </div>
                  <span className="ml-auto text-lg font-bold text-emerald-600 dark:text-emerald-400">{fmt$(instanceIdle.potentialSavings)}/mo</span>
                </div>
                <div className="grid grid-cols-3 gap-3">
                  <StatCard label="Avg CPU" value={`${instanceIdle.avgCpuPercent.toFixed(1)}%`} color="text-amber-600" />
                  <StatCard label="Avg Connections" value={instanceIdle.avgConnections.toFixed(1)} />
                  <StatCard label="Potential Savings" value={fmt$(instanceIdle.potentialSavings)} color="text-emerald-600" />
                </div>
                {instanceIdle.recommendation && (
                  <p className="text-xs text-amber-700 dark:text-amber-300 mt-3 bg-amber-100 dark:bg-amber-900/30 rounded-lg px-3 py-2">
                    <i className="fas fa-lightbulb mr-1" />{instanceIdle.recommendation}
                  </p>
                )}
              </div>
            ) : (
              <div className="bg-green-50 dark:bg-green-900/10 border border-green-100 dark:border-green-800 rounded-2xl p-4 flex items-center gap-3">
                <i className="fas fa-check-circle text-green-500" />
                <p className="text-sm text-green-700 dark:text-green-400">Instance is not idle — normal usage detected.</p>
              </div>
            )}

            {/* Right-sizing */}
            {instanceRightsizing ? (
              <div className="bg-pink-50 dark:bg-pink-900/20 border border-pink-200 dark:border-pink-800 rounded-2xl p-5">
                <div className="flex items-center gap-3 mb-3">
                  <i className="fas fa-compress-alt text-pink-500 text-lg" />
                  <div>
                    <p className="font-semibold text-pink-800 dark:text-pink-300 text-sm">Right-sizing Opportunity</p>
                    <p className="text-xs text-pink-600 dark:text-pink-400">Instance is over-provisioned</p>
                  </div>
                  <span className="ml-auto text-lg font-bold text-emerald-600 dark:text-emerald-400">{fmt$(instanceRightsizing.estimatedSavings)}/mo</span>
                </div>
                <div className="bg-white dark:bg-gray-800 rounded-lg p-3 flex items-center justify-between">
                  <div className="text-center">
                    <p className="text-xs text-gray-500 mb-1">Current</p>
                    <p className="font-mono text-sm font-semibold text-gray-800 dark:text-gray-200">{instanceRightsizing.currentInstanceClass}</p>
                    <p className="text-xs text-gray-500">{fmt$(instanceRightsizing.currentMonthlyCost)}/mo</p>
                  </div>
                  <i className="fas fa-arrow-right text-gray-400 mx-4" />
                  <div className="text-center">
                    <p className="text-xs text-gray-500 mb-1">Recommended</p>
                    <p className="font-mono text-sm font-semibold text-emerald-600 dark:text-emerald-400">{instanceRightsizing.recommendedInstanceClass}</p>
                    <p className="text-xs text-gray-500">{fmt$(instanceRightsizing.recommendedMonthlyCost)}/mo</p>
                  </div>
                  <div className="text-center ml-4">
                    <p className="text-xs text-gray-500 mb-1">Avg CPU</p>
                    <p className="text-sm font-semibold text-pink-600 dark:text-pink-400">{instanceRightsizing.avgCpuPercent.toFixed(1)}%</p>
                  </div>
                </div>
                {instanceRightsizing.reason && (
                  <p className="text-xs text-pink-700 dark:text-pink-300 mt-3 bg-pink-100 dark:bg-pink-900/30 rounded-lg px-3 py-2">
                    <i className="fas fa-lightbulb mr-1" />{instanceRightsizing.reason}
                  </p>
                )}
              </div>
            ) : (
              <div className="bg-green-50 dark:bg-green-900/10 border border-green-100 dark:border-green-800 rounded-2xl p-4 flex items-center gap-3">
                <i className="fas fa-check-circle text-green-500" />
                <p className="text-sm text-green-700 dark:text-green-400">No right-sizing recommendation — instance class looks appropriate.</p>
              </div>
            )}

            {!rd && (
              <div className="text-center py-8 text-sm text-gray-400">Cost data is only available for RDS instances.</div>
            )}
          </div>
        )}

        {/* ── SECURITY ── */}
        {tab === 'security' && (
          <div className="space-y-4">
            {instanceSecurity.length === 0 ? (
              <div className="text-center py-12 bg-white/90 dark:bg-gray-800/80 rounded-2xl border border-gray-100 dark:border-gray-700/50">
                <i className="fas fa-shield-alt text-3xl text-gray-300 dark:text-gray-600 mb-3 block" />
                <p className="text-sm text-gray-500 dark:text-gray-400">No security checks available for this instance.</p>
              </div>
            ) : (
              <>
                <div className="flex gap-3 flex-wrap">
                  {[
                    { label: 'Failures', count: instanceSecurity.filter(c => c.status === 'FAIL').length, color: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400', icon: 'fas fa-times-circle' },
                    { label: 'Warnings', count: instanceSecurity.filter(c => c.status === 'WARN').length, color: 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-400', icon: 'fas fa-exclamation-triangle' },
                    { label: 'Passed', count: instanceSecurity.filter(c => c.status === 'PASS').length, color: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400', icon: 'fas fa-check-circle' },
                  ].map(s => (
                    <div key={s.label} className={`flex items-center gap-2 px-4 py-2 rounded-lg ${s.color} text-sm font-semibold`}>
                      <i className={s.icon} /> {s.count} {s.label}
                    </div>
                  ))}
                </div>
                <div className="bg-white/90 dark:bg-gray-800/80 rounded-2xl border border-gray-100 dark:border-gray-700/50 overflow-hidden">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b border-gray-100 dark:border-gray-700/50 bg-gray-50/80 dark:bg-gray-700/30">
                        {['Check', 'Severity', 'Status', 'Details'].map(h => (
                          <th key={h} className="text-left px-4 py-3 text-[10px] font-bold text-gray-700 dark:text-gray-400 uppercase tracking-wider">{h}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {instanceSecurity.map((c, i) => (
                        <tr key={i} className={`border-b border-gray-50 dark:border-gray-700/50 ${c.status === 'FAIL' ? 'bg-red-50/40 dark:bg-red-900/10' : c.status === 'WARN' ? 'bg-yellow-50/40 dark:bg-yellow-900/10' : ''
                          }`}>
                          <td className="px-4 py-3 text-gray-700 dark:text-gray-300 font-medium">{c.checkName}</td>
                          <td className="px-4 py-3"><SeverityBadge severity={c.severity} /></td>
                          <td className="px-4 py-3"><StatusBadge status={c.status} /></td>
                          <td className="px-4 py-3 text-gray-600 dark:text-gray-400 text-xs">{c.details}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </>
            )}
          </div>
        )}

        {/* ── SNAPSHOTS ── */}
        {tab === 'snapshots' && (
          <div className="space-y-4">
            {instanceSnapshots.length === 0 ? (
              <div className="text-center py-12 bg-white/90 dark:bg-gray-800/80 rounded-2xl border border-gray-100 dark:border-gray-700/50">
                <i className="fas fa-check-circle text-green-500 text-3xl mb-3 block" />
                <p className="text-sm text-gray-500 dark:text-gray-400">No old manual snapshots for this instance.</p>
              </div>
            ) : (
              <>
                <div className="flex items-center gap-3 p-4 bg-violet-50 dark:bg-violet-900/20 border border-violet-100 dark:border-violet-800 rounded-xl">
                  <i className="fas fa-camera text-violet-500 text-lg" />
                  <div>
                    <p className="text-sm font-semibold text-violet-800 dark:text-violet-300">
                      {instanceSnapshots.length} old manual snapshot{instanceSnapshots.length !== 1 ? 's' : ''} — {fmt$(instanceSnapshots.reduce((s, n) => s + n.estimatedMonthlyCost, 0))}/mo
                    </p>
                    <p className="text-xs text-violet-600 dark:text-violet-400">Manual snapshots older than 30 days.</p>
                  </div>
                </div>
                <div className="bg-white/90 dark:bg-gray-800/80 rounded-2xl border border-gray-100 dark:border-gray-700/50 overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b border-gray-100 dark:border-gray-700/50 bg-gray-50/80 dark:bg-gray-700/30">
                        {['Snapshot ID', 'Region', 'Age (days)', 'Size (GB)', 'Cost/mo', 'Recommendation'].map(h => (
                          <th key={h} className="text-left px-4 py-3 text-[10px] font-bold text-gray-700 dark:text-gray-400 uppercase tracking-wider whitespace-nowrap">{h}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {instanceSnapshots.map(s => (
                        <tr key={s.snapshotId} className="border-b border-gray-50 dark:border-gray-700/50 hover:bg-gray-50/50 dark:hover:bg-gray-700/30">
                          <td className="px-4 py-3 font-mono text-xs text-gray-800 dark:text-gray-200">{s.snapshotId}</td>
                          <td className="px-4 py-3 text-gray-700 dark:text-gray-400">{s.region}</td>
                          <td className="px-4 py-3">
                            <span className={`font-semibold ${s.ageDays > 90 ? 'text-red-600 dark:text-red-400' : 'text-amber-600 dark:text-amber-400'}`}>{s.ageDays}</span>
                          </td>
                          <td className="px-4 py-3 text-gray-800 dark:text-gray-300">{s.sizeGb}</td>
                          <td className="px-4 py-3 font-semibold text-violet-600 dark:text-violet-400">{fmt$(s.estimatedMonthlyCost)}</td>
                          <td className="px-4 py-3 text-xs text-gray-700 dark:text-gray-400">{s.recommendation}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </>
            )}
          </div>
        )}

      </div>
    </div>
  );
}
