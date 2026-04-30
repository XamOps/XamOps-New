import React, { useState, useEffect, useCallback, useRef } from 'react';
import ReactDOM from 'react-dom';
import { useTheme } from '../../contexts/ThemeContext';
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import {
  useObservabilityData,
  LogEntry,
  LogStream,
} from '../../hooks/useObservabilityData';
import clsx from 'clsx';
import SreInvestigateButton from '../SreInvestigateButton';

// ── Constants ─────────────────────────────────────────────────────────────────

const LOG_LEVELS = ['ALL', 'ERROR', 'WARN', 'INFO', 'DEBUG', 'TRACE'];

const TIME_RANGES = [
  { label: 'Last 15 min', ms: 15 * 60 * 1000 },
  { label: 'Last 1 hour', ms: 60 * 60 * 1000 },
  { label: 'Last 6 hours', ms: 6 * 60 * 60 * 1000 },
  { label: 'Last 24 hours', ms: 24 * 60 * 60 * 1000 },
  { label: 'Last 7 days', ms: 7 * 24 * 60 * 60 * 1000 },
];

const LEVEL_COLORS: Record<string, string> = {
  ERROR: 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-400',
  WARN:  'bg-yellow-100 text-yellow-700 dark:bg-yellow-900/40 dark:text-yellow-400',
  INFO:  'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-400',
  DEBUG: 'bg-gray-100 text-gray-600 dark:bg-gray-700 dark:text-gray-400',
  TRACE: 'bg-purple-100 text-purple-700 dark:bg-purple-900/40 dark:text-purple-400',
};

const LEVEL_DOT: Record<string, string> = {
  ERROR: 'bg-red-500',
  WARN:  'bg-yellow-500',
  INFO:  'bg-blue-500',
  DEBUG: 'bg-gray-400',
  TRACE: 'bg-purple-500',
};

// ── Log Detail Drawer ─────────────────────────────────────────────────────────

function LogDetailDrawer({ log, onClose }: { log: LogEntry | null; onClose: () => void }) {
  const { theme } = useTheme();
  if (!log) return null;

  return ReactDOM.createPortal(
    <div className="fixed inset-0 z-50 flex justify-end" onClick={onClose}>
      <div
        className={clsx(
          'w-full max-w-2xl h-full overflow-y-auto shadow-2xl border-l p-6',
          theme === 'dark' ? 'bg-slate-900 border-slate-700' : 'bg-white border-gray-200'
        )}
        onClick={e => e.stopPropagation()}
      >
        <div className="flex items-center justify-between mb-6">
          <h3 className={clsx('text-lg font-semibold', theme === 'dark' ? 'text-white' : 'text-gray-900')}>
            Log Details
          </h3>
          <div className="flex items-center gap-3">
            {(() => {
              const podName = String(
                log['k8s.pod.name'] || log['kubernetes_pod_name'] ||
                log['pod_name'] || log['kubernetes.pod.name'] || log['k8s_pod_name'] || ''
              );
              const hostId   = String(log['host.id'] || log['service.instance.id'] || '');
              const hostName = String(log['host.name'] || log['host_name'] || '');
              const ec2Id    = hostId || hostName;
              const serviceName = String(log.service || '');
              const source     = String(
                log['k8s.namespace.name'] || log['kubernetes_namespace_name'] ||
                log['namespace'] || log['k8s_namespace_name'] || log.namespace ||
                log['host.name'] || log['host_name'] || ''
              );
              const investigateId   = podName || ec2Id || serviceName;
              const investigateType: 'K8S_POD' | 'EC2' = podName ? 'K8S_POD' : ec2Id ? 'EC2' : 'K8S_POD';
              const investigateName = podName
                ? `${podName}${source ? ` (${source})` : ''}`
                : ec2Id || serviceName;
              const msg = String(log.message || log['log'] || '').slice(0, 200);
              const lvl = String(log.level || 'INFO').toUpperCase();
              if (!investigateId) return null;
              return (
                <SreInvestigateButton
                  resourceType={investigateType}
                  resourceId={investigateId}
                  resourceName={investigateName}
                  alertPayload={JSON.stringify({ level: lvl, message: msg, namespace: source, service: serviceName })}
                />
              );
            })()}
            <button onClick={onClose} className="text-gray-400 hover:text-gray-600 text-xl">✕</button>
          </div>
        </div>

        <div className="flex items-center gap-3 mb-4">
          <span className={clsx('text-xs px-2 py-1 rounded font-semibold',
            LEVEL_COLORS[String(log.level || log['severity_text'] || log['log.level'] || '').toUpperCase()] || LEVEL_COLORS['INFO'])}>
            {String(log.level || log['severity_text'] || log['log.level'] || 'INFO').toUpperCase()}
          </span>
          <span className={clsx('text-sm', theme === 'dark' ? 'text-slate-400' : 'text-gray-500')}>
            {log._timestamp ? new Date(log._timestamp / 1000).toLocaleString() : '—'}
          </span>
        </div>

        {log.message && (
          <div className={clsx('rounded-lg p-4 mb-4 font-mono text-sm break-all',
            theme === 'dark' ? 'bg-slate-800 text-slate-200' : 'bg-gray-50 text-gray-800')}>
            {String(log.message)}
          </div>
        )}

        <div className={clsx('rounded-lg border overflow-hidden',
          theme === 'dark' ? 'border-slate-700' : 'border-gray-200')}>
          {Object.entries(log)
            .filter(([k]) => k !== 'message')
            .map(([key, val]) => (
              <div key={key} className={clsx('flex border-b last:border-b-0 text-sm',
                theme === 'dark' ? 'border-slate-700' : 'border-gray-100')}>
                <div className={clsx('w-40 flex-shrink-0 px-3 py-2 font-medium',
                  theme === 'dark' ? 'bg-slate-800 text-slate-400' : 'bg-gray-50 text-gray-500')}>
                  {key}
                </div>
                <div className={clsx('flex-1 px-3 py-2 font-mono break-all',
                  theme === 'dark' ? 'text-slate-300' : 'text-gray-700')}>
                  {String(val ?? '')}
                </div>
              </div>
            ))}
        </div>
      </div>
    </div>,
    document.body
  );
}

// ── Log Row ───────────────────────────────────────────────────────────────────

function LogRow({ log, onClick }: { log: LogEntry; onClick: () => void }) {
  const { theme } = useTheme();
  const lvl = String(log.level || log['severity_text'] || log['log.level'] || 'INFO').toUpperCase();
  const ts = log._timestamp ? new Date(log._timestamp / 1000).toLocaleTimeString() : '—';
  const msg = String(log.message || log['log'] || JSON.stringify(log)).slice(0, 200);
  // K8s: OTEL → k8s.namespace.name, Fluent Bit → kubernetes_namespace_name; EC2: host.name
  const source = String(
    log['k8s.namespace.name'] || log['kubernetes_namespace_name'] ||
    log['namespace'] || log['k8s_namespace_name'] || log.namespace ||
    log['host.name'] || log['host_name'] || ''
  );

  // Derive investigation target: pod > EC2 host > service
  // K8s: OTEL agent stores k8s.pod.name; Fluent Bit lifts to kubernetes_pod_name
  const podName = String(
    log['k8s.pod.name'] || log['kubernetes_pod_name'] ||
    log['pod_name'] || log['kubernetes.pod.name'] || log['k8s_pod_name'] || ''
  );
  // EC2: OTEL agent stores host.id (instance ID) and host.name (hostname)
  const hostId   = String(log['host.id'] || log['service.instance.id'] || '');
  const hostName = String(log['host.name'] || log['host_name'] || '');
  const ec2Id    = hostId || hostName;
  const serviceName = String(log.service || '');
  const investigateId   = podName || ec2Id || serviceName;
  const investigateType: 'K8S_POD' | 'EC2' = podName ? 'K8S_POD' : ec2Id ? 'EC2' : 'K8S_POD';
  const investigateName = podName
    ? `${podName}${source ? ` (${source})` : ''}`
    : ec2Id || serviceName;

  return (
    <div
      onClick={onClick}
      className={clsx(
        'flex items-center gap-3 px-4 py-2.5 cursor-pointer transition-colors text-sm',
        theme === 'dark' ? 'hover:bg-slate-700/50' : 'hover:bg-gray-50'
      )}
    >
      <div className="flex-shrink-0">
        <span className={clsx('inline-block w-2 h-2 rounded-full', LEVEL_DOT[lvl] || 'bg-gray-400')} />
      </div>
      <span className={clsx('flex-shrink-0 w-20 font-mono text-xs',
        theme === 'dark' ? 'text-slate-500' : 'text-gray-400')}>
        {ts}
      </span>
      <span className={clsx('flex-shrink-0 text-xs px-1.5 py-0.5 rounded font-semibold',
        LEVEL_COLORS[lvl] || LEVEL_COLORS['INFO'])}>
        {lvl}
      </span>
      {source && (
        <span className={clsx('flex-shrink-0 text-xs px-1.5 py-0.5 rounded font-mono max-w-[120px] truncate',
          theme === 'dark' ? 'bg-violet-900/40 text-violet-300' : 'bg-violet-50 text-violet-700')}>
          {source}
        </span>
      )}
      {log.service && (
        <span className={clsx('flex-shrink-0 text-xs px-1.5 py-0.5 rounded',
          theme === 'dark' ? 'bg-slate-700 text-slate-300' : 'bg-gray-100 text-gray-600')}>
          {String(log.service)}
        </span>
      )}
      <span className={clsx('flex-1 font-mono truncate',
        theme === 'dark' ? 'text-slate-300' : 'text-gray-700')}>
        {msg}
      </span>
      {investigateId && (
        <div className="flex-shrink-0" onClick={e => e.stopPropagation()}>
          <SreInvestigateButton
            resourceType={investigateType}
            resourceId={investigateId}
            resourceName={investigateName}
            alertPayload={JSON.stringify({ level: lvl, message: msg, namespace: source, service: serviceName })}
          />
        </div>
      )}
    </div>
  );
}

// ── Main LogsExplorer ─────────────────────────────────────────────────────────

interface LogsExplorerProps {
  streams: LogStream[];
}

const LogsExplorer: React.FC<LogsExplorerProps> = ({ streams }) => {
  const { theme } = useTheme();
  const { logs, logTotal, histogram, logsLoading, searchLogs, fetchHistogram } = useObservabilityData();

  const [mode, setMode] = useState<'filter' | 'query'>('filter');
  const [selectedStream, setSelectedStream] = useState('default');
  const [keyword, setKeyword]               = useState('');
  const [level, setLevel]                   = useState('ALL');
  const [sourceFilter, setSourceFilter]     = useState('');
  const [timeRange, setTimeRange]           = useState(TIME_RANGES[3]); // Default: Last 24 hours
  const [sqlQuery, setSqlQuery]             = useState('');
  const [selectedLog, setSelectedLog]       = useState<LogEntry | null>(null);
  const [page, setPage]                     = useState(0);
  const [pageSize, setPageSize]             = useState(250);
  const [liveRefresh, setLiveRefresh]       = useState(false);
  const [liveInterval, setLiveInterval]     = useState(10);
  const liveTimerRef                        = useRef<ReturnType<typeof setInterval> | null>(null);

  const now = useCallback(() => Date.now(), []);

  const doSearch = useCallback(async (resetPage = true, pageOverride?: number) => {
    const end = now();
    const start = end - timeRange.ms;
    const currentPage = resetPage ? 0 : (pageOverride ?? page);
    if (resetPage) setPage(0);

    if (mode === 'query' && sqlQuery.trim()) {
      await searchLogs({ stream: selectedStream, rawSql: sqlQuery.trim(), startTime: start, endTime: end, size: pageSize, from: currentPage * pageSize });
    } else {
      await searchLogs({
        stream: selectedStream,
        keyword: keyword || undefined,
        level: level !== 'ALL' ? level : undefined,
        sourceFilter: sourceFilter || undefined,
        startTime: start,
        endTime: end,
        size: pageSize,
        from: currentPage * pageSize,
      });
    }
    await fetchHistogram(selectedStream, start, end);
  }, [selectedStream, keyword, level, sourceFilter, timeRange, mode, sqlQuery, page, pageSize, searchLogs, fetchHistogram, now]);

  useEffect(() => {
    doSearch(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedStream, level, timeRange, pageSize]);

  // Set first available stream when streams load
  useEffect(() => {
    if (streams.length > 0 && !streams.find(s => s.name === selectedStream)) {
      setSelectedStream(streams[0].name);
    }
  }, [streams, selectedStream]);

  // Update default SQL when stream changes
  useEffect(() => {
    if (sqlQuery === '' || sqlQuery.startsWith('SELECT * FROM')) {
      setSqlQuery(`SELECT * FROM "${selectedStream}" ORDER BY _timestamp DESC LIMIT 100`);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedStream]);

  // Live refresh — only in filter mode on page 0
  useEffect(() => {
    if (liveTimerRef.current) clearInterval(liveTimerRef.current);
    if (liveRefresh && mode === 'filter' && page === 0) {
      liveTimerRef.current = setInterval(() => doSearch(false, 0), liveInterval * 1000);
    }
    return () => { if (liveTimerRef.current) clearInterval(liveTimerRef.current); };
  }, [liveRefresh, liveInterval, mode, page, doSearch]);

  const handleFilterSearch = (e: React.FormEvent) => { e.preventDefault(); doSearch(true); };
  const handleQueryRun = (e: React.FormEvent) => { e.preventDefault(); doSearch(true); };

  const cardCls = clsx(
    'rounded-2xl border shadow-sm',
    theme === 'dark' ? 'bg-slate-800/80 border-slate-700' : 'bg-white border-gray-200'
  );
  const inputCls = clsx(
    'text-sm rounded-lg border px-3 py-2 focus:outline-none focus:ring-2 focus:ring-violet-500',
    theme === 'dark'
      ? 'bg-slate-700 border-slate-600 text-white placeholder-slate-400'
      : 'bg-white border-gray-300 text-gray-900 placeholder-gray-400'
  );

  return (
    <div className="space-y-4">

      {/* Mode toggle */}
      <div className="flex gap-2">
        <button
          onClick={() => setMode('filter')}
          className={clsx(
            'flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-medium border transition-colors',
            mode === 'filter'
              ? 'bg-violet-600 border-violet-600 text-white'
              : theme === 'dark'
                ? 'border-slate-600 text-slate-300 hover:bg-slate-700'
                : 'border-gray-200 text-gray-600 hover:bg-gray-50'
          )}
        >
          <i className="fas fa-filter text-xs" /> Filter
        </button>
        <button
          onClick={() => setMode('query')}
          className={clsx(
            'flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-medium border transition-colors',
            mode === 'query'
              ? 'bg-violet-600 border-violet-600 text-white'
              : theme === 'dark'
                ? 'border-slate-600 text-slate-300 hover:bg-slate-700'
                : 'border-gray-200 text-gray-600 hover:bg-gray-50'
          )}
        >
          <i className="fas fa-code text-xs" /> Query Editor
        </button>
      </div>

      {/* Filter Bar */}
      {mode === 'filter' && (
        <div className={clsx(cardCls, 'p-4')}>
          <form onSubmit={handleFilterSearch} className="flex flex-wrap gap-3 items-end">
            <div className="flex flex-col gap-1">
              <label className={clsx('text-xs font-medium', theme === 'dark' ? 'text-slate-400' : 'text-gray-500')}>Stream</label>
              <select value={selectedStream} onChange={e => setSelectedStream(e.target.value)} className={clsx(inputCls, 'pr-8')}>
                {streams.length === 0 && <option value="default">default</option>}
                {streams.map(s => <option key={s.name} value={s.name}>{s.name}</option>)}
              </select>
            </div>
            <div className="flex flex-col gap-1">
              <label className={clsx('text-xs font-medium', theme === 'dark' ? 'text-slate-400' : 'text-gray-500')}>Level</label>
              <select value={level} onChange={e => setLevel(e.target.value)} className={clsx(inputCls, 'pr-8')}>
                {LOG_LEVELS.map(l => <option key={l} value={l}>{l}</option>)}
              </select>
            </div>
            <div className="flex flex-col gap-1">
              <label className={clsx('text-xs font-medium', theme === 'dark' ? 'text-slate-400' : 'text-gray-500')}>Time Range</label>
              <select
                value={timeRange.label}
                onChange={e => setTimeRange(TIME_RANGES.find(t => t.label === e.target.value) || TIME_RANGES[1])}
                className={clsx(inputCls, 'pr-8')}
              >
                {TIME_RANGES.map(t => <option key={t.label} value={t.label}>{t.label}</option>)}
              </select>
            </div>
            <div className="flex flex-col gap-1 min-w-[160px]">
              <label className={clsx('text-xs font-medium', theme === 'dark' ? 'text-slate-400' : 'text-gray-500')}>Host / Source</label>
              <input
                type="text"
                value={sourceFilter}
                onChange={e => setSourceFilter(e.target.value)}
                placeholder="e.g. ip-172-31-0-224"
                className={clsx(inputCls, 'w-full')}
              />
            </div>
            <div className="flex flex-col gap-1 flex-1 min-w-[200px]">
              <label className={clsx('text-xs font-medium', theme === 'dark' ? 'text-slate-400' : 'text-gray-500')}>Search</label>
              <input
                type="text"
                value={keyword}
                onChange={e => setKeyword(e.target.value)}
                placeholder="Search logs..."
                className={clsx(inputCls, 'w-full')}
              />
            </div>
            <button type="submit" disabled={logsLoading}
              className="px-4 py-2 rounded-lg bg-violet-600 hover:bg-violet-700 text-white text-sm font-medium disabled:opacity-60 transition-colors">
              {logsLoading ? 'Searching...' : 'Search'}
            </button>
          </form>
        </div>
      )}

      {/* SQL Query Editor */}
      {mode === 'query' && (
        <div className={clsx(cardCls, 'p-4 space-y-3')}>
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <i className="fas fa-code text-violet-500 text-sm" />
              <span className={clsx('text-sm font-semibold', theme === 'dark' ? 'text-white' : 'text-gray-800')}>
                SQL Query Editor
              </span>
              <span className={clsx('text-xs px-2 py-0.5 rounded-full',
                theme === 'dark' ? 'bg-slate-700 text-slate-400' : 'bg-gray-100 text-gray-500')}>
                OpenObserve SQL
              </span>
            </div>
            <div className="flex gap-2 items-center">
              <select
                value={timeRange.label}
                onChange={e => setTimeRange(TIME_RANGES.find(t => t.label === e.target.value) || TIME_RANGES[1])}
                className={clsx(inputCls, 'pr-8 text-xs py-1')}
              >
                {TIME_RANGES.map(t => <option key={t.label} value={t.label}>{t.label}</option>)}
              </select>
            </div>
          </div>
          <form onSubmit={handleQueryRun} className="space-y-3">
            <textarea
              value={sqlQuery}
              onChange={e => setSqlQuery(e.target.value)}
              rows={5}
              spellCheck={false}
              placeholder={`SELECT * FROM "kubernetes" WHERE level = 'ERROR' ORDER BY _timestamp DESC LIMIT 50`}
              className={clsx(
                'w-full font-mono text-sm rounded-xl border p-3 focus:outline-none focus:ring-2 focus:ring-violet-500 resize-y',
                theme === 'dark'
                  ? 'bg-slate-900 border-slate-600 text-slate-200 placeholder-slate-600'
                  : 'bg-gray-50 border-gray-200 text-gray-800 placeholder-gray-400'
              )}
            />
            <div className="flex items-center justify-between">
              <div className={clsx('text-xs', theme === 'dark' ? 'text-slate-500' : 'text-gray-400')}>
                Tip: Use <code className="px-1 rounded bg-gray-100 dark:bg-slate-700">match_all('keyword')</code> for full-text search
              </div>
              <div className="flex gap-2">
                <button type="button"
                  onClick={() => setSqlQuery(`SELECT * FROM "${selectedStream}" ORDER BY _timestamp DESC LIMIT 100`)}
                  className={clsx('text-xs px-3 py-1.5 rounded-lg border transition-colors',
                    theme === 'dark'
                      ? 'border-slate-600 text-slate-400 hover:bg-slate-700'
                      : 'border-gray-200 text-gray-500 hover:bg-gray-50')}>
                  Reset
                </button>
                <button type="submit" disabled={logsLoading || !sqlQuery.trim()}
                  className="flex items-center gap-1.5 px-4 py-1.5 rounded-lg bg-violet-600 hover:bg-violet-700 text-white text-sm font-medium disabled:opacity-60 transition-colors">
                  <i className="fas fa-play text-xs" />
                  {logsLoading ? 'Running...' : 'Run Query'}
                </button>
              </div>
            </div>
          </form>

          {/* Quick query templates */}
          <div className="flex flex-wrap gap-2">
            {[
              { label: 'All errors', sql: `SELECT * FROM "${selectedStream}" WHERE level = 'ERROR' ORDER BY _timestamp DESC LIMIT 50` },
              { label: 'Last 100', sql: `SELECT * FROM "${selectedStream}" ORDER BY _timestamp DESC LIMIT 100` },
              { label: 'Count by level', sql: `SELECT level, COUNT(*) as count FROM "${selectedStream}" GROUP BY level ORDER BY count DESC` },
              { label: 'Count by service', sql: `SELECT service, COUNT(*) as count FROM "${selectedStream}" GROUP BY service ORDER BY count DESC` },
            ].map(t => (
              <button key={t.label}
                onClick={() => setSqlQuery(t.sql)}
                className={clsx(
                  'text-xs px-2.5 py-1 rounded-full border transition-colors',
                  theme === 'dark'
                    ? 'border-slate-600 text-slate-400 hover:bg-slate-700 hover:text-slate-200'
                    : 'border-gray-200 text-gray-500 hover:bg-violet-50 hover:border-violet-300 hover:text-violet-700'
                )}>
                {t.label}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Volume Histogram */}
      {histogram.length > 0 && (
        <div className={clsx(cardCls, 'p-4')}>
          <p className={clsx('text-xs font-semibold mb-2 uppercase tracking-wide',
            theme === 'dark' ? 'text-slate-400' : 'text-gray-500')}>
            Log Volume
          </p>
          <ResponsiveContainer width="100%" height={60}>
            <BarChart data={histogram} margin={{ top: 0, right: 0, bottom: 0, left: 0 }}>
              <XAxis dataKey="key" hide />
              <YAxis hide />
              <Tooltip
                contentStyle={{
                  background: theme === 'dark' ? '#1e293b' : '#fff',
                  border: '1px solid #6d28d9',
                  borderRadius: 8,
                  fontSize: 12,
                }}
                formatter={(v: any) => [v, 'Logs']}
                labelFormatter={(l: any) => new Date(l).toLocaleTimeString()}
              />
              <Bar dataKey="num" fill="#7c3aed" radius={[2, 2, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}

      {/* Log Table */}
      <div className={clsx(cardCls, 'overflow-hidden')}>
        <div className={clsx('flex items-center justify-between px-4 py-3 border-b',
          theme === 'dark' ? 'border-slate-700' : 'border-gray-100')}>
          <span className={clsx('text-sm font-semibold', theme === 'dark' ? 'text-white' : 'text-gray-800')}>
            {logsLoading ? 'Running...' : `${logTotal.toLocaleString()} result${logTotal !== 1 ? 's' : ''}`}
          </span>
          <div className="flex items-center gap-2">
            <span className={clsx('text-xs', theme === 'dark' ? 'text-slate-400' : 'text-gray-500')}>Rows:</span>
            <select
              value={pageSize}
              onChange={e => { setPage(0); setPageSize(Number(e.target.value)); }}
              className={clsx('text-xs rounded border px-2 py-1',
                theme === 'dark' ? 'bg-slate-700 border-slate-600 text-slate-200' : 'bg-white border-gray-200 text-gray-700')}>
              {[100, 250, 500, 1000].map(n => <option key={n} value={n}>{n}</option>)}
            </select>
          </div>
          <div className="flex items-center gap-2">
            {liveRefresh && mode === 'filter' && page === 0 && (
              <span className="flex items-center gap-1 text-xs text-emerald-500 font-medium">
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse inline-block" />
                Live
              </span>
            )}
            <select
              value={liveInterval}
              onChange={e => setLiveInterval(Number(e.target.value))}
              disabled={!liveRefresh}
              className={clsx('text-xs rounded border px-2 py-1 disabled:opacity-40',
                theme === 'dark' ? 'bg-slate-700 border-slate-600 text-slate-200' : 'bg-white border-gray-200 text-gray-700')}>
              {[5, 10, 30, 60].map(s => <option key={s} value={s}>{s}s</option>)}
            </select>
            <button
              onClick={() => setLiveRefresh(v => !v)}
              className={clsx('text-xs px-3 py-1 rounded-lg border transition-colors font-medium',
                liveRefresh
                  ? 'bg-emerald-600 border-emerald-600 text-white'
                  : theme === 'dark'
                    ? 'border-slate-600 text-slate-300 hover:bg-slate-700'
                    : 'border-gray-200 text-gray-600 hover:bg-gray-50')}>
              {liveRefresh ? '⏸ Pause' : '▶ Live'}
            </button>
            <button onClick={() => doSearch(true)}
              className={clsx('text-xs px-3 py-1 rounded-lg border transition-colors',
                theme === 'dark'
                  ? 'border-slate-600 text-slate-300 hover:bg-slate-700'
                  : 'border-gray-200 text-gray-600 hover:bg-gray-50')}>
              ↻
            </button>
          </div>
        </div>

        {logsLoading ? (
          <div className="flex items-center justify-center py-16">
            <div className="w-8 h-8 border-2 border-violet-600 border-t-transparent rounded-full animate-spin" />
          </div>
        ) : logs.length === 0 ? (
          <div className={clsx('flex flex-col items-center justify-center py-16 gap-2',
            theme === 'dark' ? 'text-slate-500' : 'text-gray-400')}>
            <span className="text-3xl">📭</span>
            <span className="text-sm">No logs found</span>
          </div>
        ) : (
          <div className="divide-y divide-gray-100 dark:divide-slate-700/50">
            {logs.map((log, idx) => (
              <LogRow key={idx} log={log} onClick={() => setSelectedLog(log)} />
            ))}
          </div>
        )}

        {logTotal > pageSize && (
          <div className={clsx('flex items-center justify-between px-4 py-3 border-t',
            theme === 'dark' ? 'border-slate-700' : 'border-gray-100')}>
            <span className={clsx('text-xs', theme === 'dark' ? 'text-slate-400' : 'text-gray-500')}>
              Page {page + 1} of {Math.ceil(logTotal / pageSize)} · showing {Math.min(pageSize, logTotal - page * pageSize)} of {logTotal.toLocaleString()}
            </span>
            <div className="flex gap-2">
              <button disabled={page === 0}
                onClick={() => { const np = page - 1; setPage(np); doSearch(false, np); }}
                className="text-xs px-3 py-1 rounded border border-gray-200 dark:border-slate-600 disabled:opacity-40">
                ← Prev
              </button>
              <button disabled={(page + 1) * pageSize >= logTotal}
                onClick={() => { const np = page + 1; setPage(np); doSearch(false, np); }}
                className="text-xs px-3 py-1 rounded border border-gray-200 dark:border-slate-600 disabled:opacity-40">
                Next →
              </button>
            </div>
          </div>
        )}
      </div>

      <LogDetailDrawer log={selectedLog} onClose={() => setSelectedLog(null)} />
    </div>
  );
};

export default LogsExplorer;
