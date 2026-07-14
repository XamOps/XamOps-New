import React, { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import ReactDOM from 'react-dom';
import { useNavigate } from 'react-router-dom';
import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, ReferenceArea } from 'recharts';
import {
  useObservabilityData,
  LogEntry,
  LogStream,
  HistogramBucket,
} from '../../hooks/useObservabilityData';
import { Icon } from '@iconify/react';
import SreInvestigateButton from '../SreInvestigateButton';

// ── Constants ─────────────────────────────────────────────────────────────────

const LOG_LEVELS = ['ALL', 'ERROR', 'WARN', 'INFO', 'DEBUG', 'TRACE'];

const TIPS_STORAGE_KEY = 'xamops.observability.logs.tips.v1';

const TIME_RANGES = [
  { label: 'Last 15 min', ms: 15 * 60 * 1000 },
  { label: 'Last 1 hour', ms: 60 * 60 * 1000 },
  { label: 'Last 6 hours', ms: 6 * 60 * 60 * 1000 },
  { label: 'Last 24 hours', ms: 24 * 60 * 60 * 1000 },
  { label: 'Last 7 days', ms: 7 * 24 * 60 * 60 * 1000 },
  { label: 'Last 30 days', ms: 30 * 24 * 60 * 60 * 1000 },
];

const LEVEL_COLORS: Record<string, string> = {
  ERROR: 'obs-lvl obs-lvl-error',
  WARN:  'obs-lvl obs-lvl-warn',
  INFO:  'obs-lvl obs-lvl-info',
  DEBUG: 'obs-lvl obs-lvl-debug',
  TRACE: 'obs-lvl obs-lvl-trace',
};

const LEVEL_DOT: Record<string, string> = {
  ERROR: 'bg-[#ef4444]',
  WARN:  'bg-[#f59e0b]',
  INFO:  'bg-[#3b82f6]',
  DEBUG: 'bg-[#94a3b8]',
  TRACE: 'bg-[#8b5cf6]',
};

// The human-readable log line. OTel (journald/docker/filelog) puts it in `body`,
// Fluent Bit in `log`, app-level shippers in `message`/`msg`.
function messageOf(log: LogEntry): string {
  return String(log.message || log['body'] || log['log'] || log['msg'] || '');
}

// OTel severity_number bands (spec): 1–4 TRACE, 5–8 DEBUG, 9–12 INFO, 13–16 WARN, 17+ ERROR/FATAL.
function levelFromSeverityNumber(n: number): string | null {
  if (n >= 17) return 'ERROR';
  if (n >= 13) return 'WARN';
  if (n >= 9)  return 'INFO';
  if (n >= 5)  return 'DEBUG';
  if (n >= 1)  return 'TRACE';
  return null; // 0 = UNSPECIFIED
}

const LEVEL_IN_BODY_RE = /\b(FATAL|SEVERE|ERROR|EXCEPTION|WARNING|WARN|INFO|DEBUG|TRACE)\b/;

// Normalise a log entry's level. Order: explicit field → OTel severity number →
// sniffed from the message text (journald/docker logs carry it only there).
function levelOf(log: LogEntry): string {
  const explicit = String(log.level || log['severity_text'] || log['log.level'] || '').trim().toUpperCase();
  if (explicit && explicit !== 'UNSPECIFIED') {
    return explicit === 'WARNING' ? 'WARN' : explicit === 'FATAL' || explicit === 'SEVERE' ? 'ERROR' : explicit;
  }
  const sevNum = Number(log['severity_number'] ?? log['severity']);
  if (Number.isFinite(sevNum)) {
    const mapped = levelFromSeverityNumber(sevNum);
    if (mapped) return mapped;
  }
  const m = LEVEL_IN_BODY_RE.exec(messageOf(log).slice(0, 400));
  if (m) {
    const found = m[1];
    if (found === 'FATAL' || found === 'SEVERE' || found === 'EXCEPTION') return 'ERROR';
    if (found === 'WARNING') return 'WARN';
    return found;
  }
  return 'INFO';
}

// Histogram bucket width scaled to the queried window, keeping bucket counts
// under OpenObserve's 288-row cap so the chart always spans the full range.
function histIntervalFor(spanMs: number): { sql: string; ms: number } {
  const HOUR = 3600_000, DAY = 24 * HOUR;
  if (spanMs <= HOUR)      return { sql: '30 second', ms: 30_000 };
  if (spanMs <= 3 * HOUR)  return { sql: '1 minute',  ms: 60_000 };
  if (spanMs <= 12 * HOUR) return { sql: '5 minute',  ms: 5 * 60_000 };
  if (spanMs <= 2 * DAY)   return { sql: '10 minute', ms: 10 * 60_000 };
  if (spanMs <= 8 * DAY)   return { sql: '1 hour',    ms: HOUR };
  return { sql: '4 hour', ms: 4 * HOUR };
}

// Format an epoch-ms value for a datetime-local input (local timezone).
function toLocalInput(ms: number): string {
  const d = new Date(ms);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

// Mask timestamps/numbers so "disconnected at 11:54:13" == "… at 11:54:14".
function maskMessage(msg: string): string {
  return msg
    .replace(/\d{4}-\d{2}-\d{2}[T ]\d{1,2}:\d{2}:\d{2}(?:[.,]\d+)?/g, '⏱')
    .replace(/\b\d{1,2}:\d{2}:\d{2}(?:[.,]\d+)?\b/g, '⏱')
    .replace(/\b\d+\b/g, '#')
    .slice(0, 240);
}

// Fingerprint for collapsing consecutive repeats: level + host + masked message.
function fingerprintOf(log: LogEntry): string {
  const host = String(log['host.name'] || log['host_name'] || '');
  return `${levelOf(log)}|${host}|${maskMessage(messageOf(log))}`;
}

// Pattern key for the Patterns view: host-agnostic, so the same message shape
// groups across the whole fleet.
function patternKeyOf(log: LogEntry): string {
  return `${levelOf(log)}|${maskMessage(messageOf(log))}`;
}

function tsMsOf(log: LogEntry): number {
  const t = Number(log._timestamp);
  return Number.isFinite(t) && t > 0 ? Math.round(t / 1000) : 0; // µs → ms
}

interface GroupedRow {
  log: LogEntry;     // representative (newest of the run — results are DESC)
  count: number;
  newestTs: number;  // ms
  oldestTs: number;  // ms
}

// Read shareable filter state from the URL once, on mount.
function parseUrlState() {
  const p = new URLSearchParams(window.location.search);
  const from = Number(p.get('from'));
  const to = Number(p.get('to'));
  return {
    stream: p.get('stream') || undefined,
    level: p.get('level') || undefined,
    q: p.get('q') || undefined,
    host: p.get('host') || undefined,
    range: p.get('range') || undefined,
    custom: Number.isFinite(from) && Number.isFinite(to) && from > 0 && to > from
      ? { start: from, end: to } : null,
  };
}

// OpenObserve histogram bucket keys arrive either as epoch numbers or as UTC
// datetime strings without a timezone suffix ("2026-07-14T11:00:00") — parse both to epoch ms.
function parseBucketTs(key: string): number {
  if (/^\d+$/.test(key)) {
    const n = Number(key);
    if (n > 1e15) return Math.round(n / 1000); // µs
    if (n > 1e12) return n;                    // ms
    return n * 1000;                           // s
  }
  let s = key.replace(' ', 'T');
  if (!/(?:Z|[+-]\d{2}:?\d{2})$/i.test(s)) s += 'Z';
  const t = Date.parse(s);
  return Number.isNaN(t) ? 0 : t;
}

// ── Summary stat card ─────────────────────────────────────────────────────────

interface SummaryStat {
  key: string;
  label: string;
  value: number;
  icon: string;
  accent: string;     // text/icon accent colour
  bg: string;         // icon chip background
  ring: string;       // active ring colour
  filterLevel?: string;
}

function fmtCount(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M';
  if (n >= 1_000)     return (n / 1_000).toFixed(1) + 'K';
  return n.toLocaleString();
}

function SummaryCard({ stat, active, onClick, index }: {
  stat: SummaryStat; active: boolean; onClick?: () => void; index: number;
}) {
  const clickable = !!stat.filterLevel;
  return (
    <button
      type="button"
      onClick={clickable ? onClick : undefined}
      disabled={!clickable}
      style={{ animationDelay: `${0.04 + index * 0.06}s` }}
      className={`group relative text-left obs-card obs-fade-up p-4 flex items-center gap-3 overflow-hidden ${
        clickable ? 'obs-card-click' : 'cursor-default'
      } ${active ? '!border-transparent' : ''}`}
    >
      {active && <span className="absolute inset-0 pointer-events-none rounded-[14px]" style={{ boxShadow: `inset 0 0 0 2px ${stat.ring}` }} />}
      <div className="w-9 h-9 rounded-lg flex items-center justify-center flex-shrink-0" style={{ backgroundColor: stat.bg }}>
        <Icon icon={stat.icon} width={18} style={{ color: stat.accent }} />
      </div>
      <div className="min-w-0">
        <p className="obs-eyebrow truncate">{stat.label}</p>
        <p className="text-[20px] font-bold leading-7 text-[#0b1220]" style={{ fontVariantNumeric: 'tabular-nums' }}>
          {fmtCount(stat.value)}
        </p>
      </div>
      {clickable && (
        <Icon icon="mdi:filter-variant" width={13}
          className="absolute top-2.5 right-2.5 text-[rgba(15,23,42,0.15)] group-hover:text-[rgba(15,23,42,0.4)] transition-colors" />
      )}
    </button>
  );
}

// ── Log Detail Drawer ─────────────────────────────────────────────────────────

function LogDetailDrawer({ log, onClose, onFilterField, onShowContext }: {
  log: LogEntry | null;
  onClose: () => void;
  onFilterField: (key: string, value: string) => void;
  onShowContext: (log: LogEntry) => void;
}) {
  if (!log) return null;

  return ReactDOM.createPortal(
    <div className="obs-wrap obs-drawer-backdrop fixed inset-0 z-50 flex justify-end" onClick={onClose}>
      <div
        className="obs-drawer w-full max-w-2xl h-full overflow-y-auto border-l border-[rgba(15,23,42,0.08)] bg-white p-6"
        onClick={e => e.stopPropagation()}
      >
        <div className="flex items-center justify-between mb-6">
          <h3 className="obs-h2 text-[16px]">
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
              const msg = messageOf(log).slice(0, 200);
              const lvl = levelOf(log);
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
            <button onClick={onClose} className="p-1.5 rounded-md text-[#94a3b8] hover:text-[#0b1220] hover:bg-[rgba(15,23,42,0.05)] transition-colors">
              <Icon icon="mdi:close" width={18} />
            </button>
          </div>
        </div>

        <div className="flex items-center gap-3 mb-4">
          <span className={LEVEL_COLORS[levelOf(log)] || LEVEL_COLORS['INFO']}>
            {levelOf(log)}
          </span>
          <span className="text-[13px] text-[#64748b]" style={{ fontVariantNumeric: 'tabular-nums' }}>
            {log._timestamp ? new Date(log._timestamp / 1000).toLocaleString() : '—'}
          </span>
          <button
            type="button"
            onClick={() => onShowContext(log)}
            title="Show all logs from this host in the two minutes around this event"
            className="obs-btn obs-btn-soft obs-btn-sm ml-auto">
            <Icon icon="mdi:target" width={13} /> Context ±2 min
          </button>
        </div>

        {messageOf(log) && (
          <div className="rounded-lg p-4 mb-4 font-mono text-[13px] leading-relaxed break-all whitespace-pre-wrap bg-[#f7f8fa] border border-[rgba(15,23,42,0.08)] text-[#0b1220]">
            {messageOf(log)}
          </div>
        )}

        {(() => {
          const RESOURCE_KEY_RE = /^(cloud[._]|host[._]|k8s[._]|kubernetes[._]|os[._]|service[._]|process[._]|container[._]|deployment[._]|log_file|source$|stream$)/;
          const entries = Object.entries(log).filter(([k]) => !['message', 'body', 'log', 'msg'].includes(k));
          const eventFields    = entries.filter(([k]) => !RESOURCE_KEY_RE.test(k));
          const resourceFields = entries.filter(([k]) => RESOURCE_KEY_RE.test(k));

          const FieldTable = ({ title, fields }: { title: string; fields: [string, unknown][] }) => (
            fields.length === 0 ? null : (
              <div className="mb-4">
                <p className="obs-eyebrow mb-1.5">{title}</p>
                <div className="rounded-lg border border-[rgba(15,23,42,0.08)] overflow-hidden">
                  {fields.map(([key, val]) => (
                    <div key={key} className="group flex border-b border-[rgba(15,23,42,0.06)] last:border-b-0 text-[13px]">
                      <div className="w-48 flex-shrink-0 px-3 py-2 font-medium break-words bg-[#f7f8fa] text-[#64748b]">
                        {key}
                      </div>
                      <div className="flex-1 min-w-0 px-3 py-2 font-mono break-all text-[#334155] flex items-start gap-2">
                        <span className="flex-1 min-w-0">{String(val ?? '')}</span>
                        {String(val ?? '') !== '' && (
                          <button
                            type="button"
                            title={`Filter logs by ${key}`}
                            onClick={() => onFilterField(key, String(val))}
                            className="flex-shrink-0 p-0.5 rounded text-transparent group-hover:text-[#94a3b8] hover:!text-[#0b1220] hover:bg-[rgba(15,23,42,0.05)] transition-colors">
                            <Icon icon="mdi:filter-variant" width={13} />
                          </button>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )
          );

          return (
            <>
              <FieldTable title="Event" fields={eventFields} />
              <FieldTable title="Resource / Origin" fields={resourceFields} />
            </>
          );
        })()}
      </div>
    </div>,
    document.body
  );
}

// ── Log Row ───────────────────────────────────────────────────────────────────

function LogRow({ log, count = 1, oldestTs = 0, expanded, onToggle, onOpenDetails, onFilterLevel, onFilterSource, onShowContext }: {
  log: LogEntry;
  count?: number;
  oldestTs?: number;
  expanded: boolean;
  onToggle: () => void;
  onOpenDetails: () => void;
  onFilterLevel: (lvl: string) => void;
  onFilterSource: (src: string) => void;
  onShowContext: () => void;
}) {
  const lvl = levelOf(log);
  const ts = log._timestamp ? new Date(log._timestamp / 1000).toLocaleTimeString() : '—';
  const fullMsg = messageOf(log) || JSON.stringify(log, null, 2);
  const msg = fullMsg.slice(0, 300);
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
    <div className={expanded ? 'bg-[#f7f8fa]' : ''}>
      <div
        onClick={onToggle}
        className="flex items-center gap-3 px-4 py-2.5 cursor-pointer transition-colors text-sm hover:bg-[rgba(15,23,42,0.025)]"
      >
        <Icon icon="mdi:chevron-right" width={14}
          className={`flex-shrink-0 text-[#94a3b8] transition-transform ${expanded ? 'rotate-90' : ''}`} />
        <div className="flex-shrink-0">
          <span className={`inline-block w-2 h-2 rounded-full ${LEVEL_DOT[lvl] || 'bg-[#94a3b8]'}`} />
        </div>
        <span className="flex-shrink-0 w-20 font-mono text-xs text-[#94a3b8]" style={{ fontVariantNumeric: 'tabular-nums' }}>
          {ts}
        </span>
        <button
          type="button"
          title={`Filter by ${lvl}`}
          onClick={e => { e.stopPropagation(); onFilterLevel(lvl); }}
          className={`flex-shrink-0 ${LEVEL_COLORS[lvl] || LEVEL_COLORS['INFO']}`}>
          {lvl}
        </button>
        {count > 1 && (
          <span
            title={`${count} similar consecutive messages collapsed — expand for the time span`}
            className="flex-shrink-0 text-[10px] font-bold px-1.5 py-0.5 rounded-full bg-[rgba(15,23,42,0.05)] border border-[rgba(15,23,42,0.1)] text-[#334155]"
            style={{ fontVariantNumeric: 'tabular-nums' }}>
            ×{count}
          </span>
        )}
        {source && (
          <button
            type="button"
            title={`Filter by ${source}`}
            onClick={e => { e.stopPropagation(); onFilterSource(source); }}
            className="flex-shrink-0 text-xs px-1.5 py-0.5 rounded font-mono max-w-[120px] truncate bg-[rgba(15,23,42,0.04)] border border-[rgba(15,23,42,0.06)] text-[#64748b] hover:border-[rgba(15,23,42,0.25)] hover:text-[#0b1220] transition-colors">
            {source}
          </button>
        )}
        {log.service && (
          <span className="flex-shrink-0 text-xs px-1.5 py-0.5 rounded bg-[rgba(15,23,42,0.04)] text-[#64748b]">
            {String(log.service)}
          </span>
        )}
        <span className="flex-1 font-mono truncate text-[#334155]">
          {msg}
        </span>
        <button
          type="button"
          title="All fields"
          onClick={e => { e.stopPropagation(); onOpenDetails(); }}
          className="flex-shrink-0 p-1 rounded-md text-[#94a3b8] hover:text-[#0b1220] hover:bg-[rgba(15,23,42,0.05)] transition-colors">
          <Icon icon="mdi:text-box-search-outline" width={15} />
        </button>
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

      {/* Inline expansion — full message + quick context, without losing scroll position */}
      {expanded && (
        <div className="px-12 pb-3 space-y-2" onClick={e => e.stopPropagation()}>
          <pre className="rounded-lg border border-[rgba(15,23,42,0.08)] bg-white p-3 font-mono text-[12px] leading-relaxed whitespace-pre-wrap break-all text-[#0b1220] max-h-64 overflow-y-auto">
            {fullMsg}
          </pre>
          <div className="flex items-center gap-3 flex-wrap text-[11px] text-[#94a3b8]">
            <span style={{ fontVariantNumeric: 'tabular-nums' }}>
              {log._timestamp ? new Date(log._timestamp / 1000).toLocaleString() : '—'}
            </span>
            {count > 1 && (
              <span className="font-medium text-[#334155]" style={{ fontVariantNumeric: 'tabular-nums' }}>
                repeated ×{count}{oldestTs > 0 ? ` · ${new Date(oldestTs).toLocaleTimeString()} – ${new Date(log._timestamp ? log._timestamp / 1000 : oldestTs).toLocaleTimeString()}` : ''} (showing latest)
              </span>
            )}
            {hostName && <span className="font-mono">{hostName}</span>}
            {serviceName && <span className="font-mono">{serviceName}</span>}
            <button
              type="button"
              onClick={onShowContext}
              title="Show all logs from this host in the two minutes around this event"
              className="ml-auto flex items-center gap-1 text-[11px] font-semibold text-[#64748b] hover:text-[#0b1220] transition-colors">
              <Icon icon="mdi:target" width={12} /> Show context ±2 min
            </button>
            <button
              type="button"
              onClick={onOpenDetails}
              className="flex items-center gap-1 text-[11px] font-semibold text-[#64748b] hover:text-[#0b1220] transition-colors">
              View all fields <Icon icon="mdi:arrow-right" width={12} />
            </button>
          </div>
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
  const { logs, logTotal, histogram, logsLoading, searchLogs, fetchHistogram } = useObservabilityData();

  // One-time URL hydration so filtered views survive refresh and can be shared.
  const [urlInit] = useState(parseUrlState);

  const [mode, setMode] = useState<'filter' | 'query'>('filter');
  const [selectedStream, setSelectedStream] = useState(urlInit.stream ?? 'default');
  const [keyword, setKeyword]               = useState(urlInit.q ?? '');
  const [level, setLevel]                   = useState(urlInit.level && LOG_LEVELS.includes(urlInit.level) ? urlInit.level : 'ALL');
  const [sourceFilter, setSourceFilter]     = useState(urlInit.host ?? '');
  const [timeRange, setTimeRange]           = useState(TIME_RANGES.find(t => t.label === urlInit.range) ?? TIME_RANGES[3]); // Default: Last 24 hours
  const [sqlQuery, setSqlQuery]             = useState('');
  const [selectedLog, setSelectedLog]       = useState<LogEntry | null>(null);
  const [page, setPage]                     = useState(0);
  const [pageSize, setPageSize]             = useState(250);
  const [liveRefresh, setLiveRefresh]       = useState(false);
  const [liveInterval, setLiveInterval]     = useState(10);
  const liveTimerRef                        = useRef<ReturnType<typeof setInterval> | null>(null);

  // Inline row expansion (index into current page of logs)
  const [expandedIdx, setExpandedIdx]       = useState<number | null>(null);

  // Absolute time window set by dragging on the histogram; overrides the preset range.
  const [customRange, setCustomRange]       = useState<{ start: number; end: number } | null>(urlInit.custom);

  const navigate = useNavigate();

  // Collapse consecutive repeats of the same message into one row (default on).
  const [groupRepeats, setGroupRepeats]     = useState(true);

  // Results presentation: chronological list or aggregated message patterns.
  const [viewMode, setViewMode]             = useState<'list' | 'patterns'>('list');
  const [expandedPattern, setExpandedPattern] = useState<string | null>(null);

  // Error-only histogram buckets, overlaid in red on the volume chart.
  const [errBuckets, setErrBuckets]         = useState<HistogramBucket[]>([]);

  // "Copy link" feedback
  const [copiedLink, setCopiedLink]         = useState(false);

  // Bumped by click-to-filter so the auto-search effect re-runs with fresh filter state.
  const [searchNonce, setSearchNonce]       = useState(0);

  // Discoverability tips — shown until dismissed, remembered across sessions.
  const [showTips, setShowTips] = useState<boolean>(() => {
    try { return localStorage.getItem(TIPS_STORAGE_KEY) !== 'dismissed'; } catch { return true; }
  });
  const dismissTips = () => { setShowTips(false); try { localStorage.setItem(TIPS_STORAGE_KEY, 'dismissed'); } catch { /* private mode */ } };
  const reopenTips  = () => { setShowTips(true);  try { localStorage.removeItem(TIPS_STORAGE_KEY); } catch { /* private mode */ } };

  // Histogram drag-selection state
  const [refLeft, setRefLeft]               = useState<number | null>(null);
  const [refRight, setRefRight]             = useState<number | null>(null);
  // Width (ms) of the buckets currently on screen — used to pad drag-zoom selections.
  const histBucketMsRef                     = useRef(5 * 60_000);

  // Custom From/To picker (Time Range → "Custom…")
  const [showCustom, setShowCustom]         = useState(false);
  const [customFrom, setCustomFrom]         = useState('');
  const [customTo, setCustomTo]             = useState('');

  // Keep the picker inputs in sync when a chart drag sets the window.
  useEffect(() => {
    if (customRange) {
      setCustomFrom(toLocalInput(customRange.start));
      setCustomTo(toLocalInput(customRange.end));
    }
  }, [customRange]);

  const now = useCallback(() => Date.now(), []);

  // Reflect the active filters in the URL (replace, not push) so any view can be
  // shared by copying the address, and refresh doesn't reset the page.
  useEffect(() => {
    const p = new URLSearchParams();
    if (selectedStream !== 'default') p.set('stream', selectedStream);
    if (level !== 'ALL') p.set('level', level);
    if (keyword) p.set('q', keyword);
    if (sourceFilter) p.set('host', sourceFilter);
    if (customRange) {
      p.set('from', String(customRange.start));
      p.set('to', String(customRange.end));
    } else if (timeRange.label !== TIME_RANGES[3].label) {
      p.set('range', timeRange.label);
    }
    const qs = p.toString();
    window.history.replaceState(null, '', qs ? `${window.location.pathname}?${qs}` : window.location.pathname);
  }, [selectedStream, level, keyword, sourceFilter, customRange, timeRange]);

  // Collapse consecutive rows with the same fingerprint into one row with a ×N badge.
  const groupedRows = useMemo<GroupedRow[]>(() => {
    if (!groupRepeats) {
      return logs.map(l => ({ log: l, count: 1, newestTs: tsMsOf(l), oldestTs: tsMsOf(l) }));
    }
    const out: GroupedRow[] = [];
    let prevKey = '';
    for (const l of logs) {
      const hasMsg = messageOf(l) !== '';
      const key = hasMsg ? fingerprintOf(l) : `__unique_${out.length}`;
      if (hasMsg && out.length > 0 && key === prevKey) {
        const g = out[out.length - 1];
        g.count++;
        const t = tsMsOf(l);
        if (t > 0) g.oldestTs = t; // results are DESC — later entries are older
      } else {
        out.push({ log: l, count: 1, newestTs: tsMsOf(l), oldestTs: tsMsOf(l) });
        prevKey = key;
      }
    }
    return out;
  }, [logs, groupRepeats]);

  // Level breakdown across the currently loaded rows — drives the interactive summary cards.
  const levelCounts = useMemo(() => {
    const counts = { ERROR: 0, WARN: 0, INFO: 0, DEBUG: 0, TRACE: 0, OTHER: 0 };
    for (const log of logs) {
      const lvl = levelOf(log);
      if (lvl in counts) counts[lvl as keyof typeof counts]++;
      else counts.OTHER++;
    }
    return counts;
  }, [logs]);

  // Histogram buckets with parsed epoch-ms timestamps (backend sends datetime-string keys).
  const histData  = useMemo(
    () => histogram
      .map(h => ({ ts: parseBucketTs(h.key), num: h.num || 0 }))
      .filter(h => h.ts > 0)
      .sort((a, b) => a.ts - b.ts),
    [histogram],
  );
  // Total ingested volume in window, from the histogram buckets.
  const histTotal = useMemo(() => histData.reduce((s, h) => s + h.num, 0), [histData]);
  const histPeak  = useMemo(() => histData.reduce((m, h) => Math.max(m, h.num), 0), [histData]);
  // When the buckets span more than a day, axis ticks need the date, not just the time.
  const histSpansDays = useMemo(
    () => histData.length > 1 && (histData[histData.length - 1].ts - histData[0].ts) > 24 * 3600_000,
    [histData],
  );
  // Merge the error-only buckets into the total series for the stacked-look overlay.
  const histChartData = useMemo(() => {
    const errMap = new Map(errBuckets.map(b => [parseBucketTs(b.key), b.num || 0]));
    return histData.map(h => ({ ...h, err: errMap.get(h.ts) ?? 0 }));
  }, [histData, errBuckets]);
  const histHasErrors = useMemo(() => histChartData.some(h => h.err > 0), [histChartData]);

  const summaryStats: SummaryStat[] = useMemo(() => [
    { key: 'total', label: 'Total Results', value: logTotal, icon: 'mdi:database-outline',
      accent: '#0b8f5a', bg: 'rgba(27,230,139,0.12)', ring: '#0b8f5a' },
    { key: 'error', label: 'Errors', value: levelCounts.ERROR, icon: 'mdi:alert-circle-outline',
      accent: '#DC2626', bg: '#DC262614', ring: '#DC2626', filterLevel: 'ERROR' },
    { key: 'warn', label: 'Warnings', value: levelCounts.WARN, icon: 'mdi:alert-outline',
      accent: '#D97706', bg: '#D9770614', ring: '#D97706', filterLevel: 'WARN' },
    { key: 'info', label: 'Info', value: levelCounts.INFO, icon: 'mdi:information-outline',
      accent: '#2563EB', bg: '#2563EB14', ring: '#2563EB', filterLevel: 'INFO' },
    { key: 'debug', label: 'Debug / Trace', value: levelCounts.DEBUG + levelCounts.TRACE, icon: 'mdi:bug-outline',
      accent: '#64748B', bg: '#64748B14', ring: '#64748B', filterLevel: 'DEBUG' },
  ], [logTotal, levelCounts]);

  const doSearch = useCallback(async (resetPage = true, pageOverride?: number) => {
    const end = customRange ? customRange.end : now();
    const start = customRange ? customRange.start : end - timeRange.ms;
    const currentPage = resetPage ? 0 : (pageOverride ?? page);
    if (resetPage) setPage(0);
    setExpandedIdx(null);

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
    const interval = histIntervalFor(end - start);
    histBucketMsRef.current = interval.ms;
    // Total volume + error-only overlay, fetched in parallel.
    await Promise.all([
      fetchHistogram(selectedStream, start, end, interval.sql),
      fetchHistogram(selectedStream, start, end, interval.sql, 'error').then(setErrBuckets),
    ]);
  }, [selectedStream, keyword, level, sourceFilter, timeRange, customRange, mode, sqlQuery, page, pageSize, searchLogs, fetchHistogram, now]);

  useEffect(() => {
    doSearch(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedStream, level, timeRange, pageSize, customRange, searchNonce]);

  // ── Click-to-filter ─────────────────────────────────────────────────────────
  // Any value in a row or the detail drawer becomes a filter with one click.

  const filterByLevel = useCallback((lvl: string) => {
    setSelectedLog(null);
    setLevel(prev => (prev === lvl ? 'ALL' : LOG_LEVELS.includes(lvl) ? lvl : 'ALL'));
  }, []);

  const filterBySource = useCallback((src: string) => {
    setSelectedLog(null);
    setSourceFilter(src);
    setSearchNonce(n => n + 1);
  }, []);

  // Route a clicked drawer field to the most precise filter available.
  const filterByField = useCallback((key: string, value: string) => {
    if (!value) return;
    if (/^(level|severity_text|log[._]level)$/.test(key)) {
      const lvl = value.toUpperCase() === 'WARNING' ? 'WARN' : value.toUpperCase();
      if (LOG_LEVELS.includes(lvl)) { filterByLevel(lvl); return; }
    }
    if (/^(host[._](name|id)|k8s[._]namespace[._]name|kubernetes_namespace_name|namespace|service([._]name)?|k8s[._]pod[._]name|kubernetes_pod_name|pod_name)$/.test(key)) {
      filterBySource(value);
      return;
    }
    setSelectedLog(null);
    setKeyword(value);
    setSearchNonce(n => n + 1);
  }, [filterByLevel, filterBySource]);

  // ── Histogram drag-to-zoom ──────────────────────────────────────────────────

  const handleZoomEnd = useCallback(() => {
    if (refLeft != null && refRight != null && refLeft !== refRight) {
      const [start, end] = refLeft < refRight ? [refLeft, refRight] : [refRight, refLeft];
      // Pad by one bucket so the last selected bucket is fully included.
      setCustomRange({ start, end: end + histBucketMsRef.current });
      setLiveRefresh(false);
    }
    setRefLeft(null);
    setRefRight(null);
  }, [refLeft, refRight]);

  // ── Show context: jump to ±2 minutes around a log, same host, no other filters ──
  const showContext = useCallback((log: LogEntry) => {
    const ts = tsMsOf(log);
    if (!ts) return;
    const host = String(log['host.name'] || log['host_name'] || '');
    setSelectedLog(null);
    setKeyword('');
    setLevel('ALL');
    setSourceFilter(host);
    setCustomRange({ start: ts - 2 * 60_000, end: ts + 2 * 60_000 });
    setLiveRefresh(false);
    setViewMode('list');
    setSearchNonce(n => n + 1);
  }, []);

  // ── Create alert from the current search — hands the filters to the Alerts tab ──
  const goCreateAlert = useCallback(() => {
    const p = new URLSearchParams({ fromLogs: '1', stream: selectedStream });
    if (keyword) p.set('q', keyword);
    if (level !== 'ALL') p.set('level', level);
    if (sourceFilter) p.set('host', sourceFilter);
    navigate(`/observability/alerts?${p.toString()}`);
  }, [navigate, selectedStream, keyword, level, sourceFilter]);

  // ── Message patterns across the loaded page of results ─────────────────────
  const patterns = useMemo(() => {
    const map = new Map<string, { key: string; count: number; level: string; sample: LogEntry }>();
    for (const l of logs) {
      if (!messageOf(l)) continue;
      const key = patternKeyOf(l);
      const p = map.get(key);
      if (p) p.count++;
      else map.set(key, { key, count: 1, level: levelOf(l), sample: l });
    }
    return Array.from(map.values()).sort((a, b) => b.count - a.count);
  }, [logs]);

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

  const cardCls = 'obs-card';
  const inputCls = 'obs-input';

  return (
    <div className="obs-wrap space-y-4">

      {/* Interactive summary cards */}
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-3">
        {summaryStats.map((stat, i) => (
          <SummaryCard
            key={stat.key}
            stat={stat}
            index={i}
            active={!!stat.filterLevel && level === stat.filterLevel}
            onClick={stat.filterLevel ? () => {
              // Toggle: click active card to clear, click another to apply
              setMode('filter');
              setLevel(prev => prev === stat.filterLevel ? 'ALL' : stat.filterLevel!);
            } : undefined}
          />
        ))}
      </div>

      {/* Mode toggle */}
      <div className="obs-seg">
        <button onClick={() => setMode('filter')} className={`obs-seg-btn ${mode === 'filter' ? 'active' : ''}`}>
          <Icon icon="mdi:magnify" width={13} /> Filter
        </button>
        <button onClick={() => setMode('query')} className={`obs-seg-btn ${mode === 'query' ? 'active' : ''}`}>
          <Icon icon="mdi:file-code-outline" width={13} /> Query Editor
        </button>
      </div>

      {/* Filter Bar */}
      {mode === 'filter' && (
        <div className={`${cardCls} p-4`}>
          <form onSubmit={handleFilterSearch} className="flex flex-wrap gap-3 items-end">
            <div className="flex flex-col gap-1">
              <label className="obs-label">Stream</label>
              <select value={selectedStream} onChange={e => setSelectedStream(e.target.value)} className={`${inputCls} !pr-8`}>
                {streams.length === 0 && <option value="default">default</option>}
                {streams.map(s => <option key={s.name} value={s.name}>{s.name}</option>)}
              </select>
            </div>
            <div className="flex flex-col gap-1">
              <label className="obs-label">Level</label>
              <select value={level} onChange={e => setLevel(e.target.value)} className={`${inputCls} !pr-8`}>
                {LOG_LEVELS.map(l => <option key={l} value={l}>{l}</option>)}
              </select>
            </div>
            <div className="flex flex-col gap-1">
              <label className="obs-label">Time Range</label>
              <select
                value={showCustom || customRange ? '__custom__' : timeRange.label}
                onChange={e => {
                  if (e.target.value === '__custom__') {
                    setShowCustom(true);
                    const end = Date.now();
                    if (!customFrom) setCustomFrom(toLocalInput(end - 24 * 3600_000));
                    if (!customTo) setCustomTo(toLocalInput(end));
                  } else {
                    setShowCustom(false);
                    setCustomRange(null);
                    setTimeRange(TIME_RANGES.find(t => t.label === e.target.value) || TIME_RANGES[1]);
                  }
                }}
                className={`${inputCls} !pr-8`}
              >
                {TIME_RANGES.map(t => <option key={t.label} value={t.label}>{t.label}</option>)}
                <option value="__custom__">Custom…</option>
              </select>
            </div>
            {(showCustom || customRange) && (
              <>
                <div className="flex flex-col gap-1">
                  <label className="obs-label">From</label>
                  <input
                    type="datetime-local"
                    value={customFrom}
                    max={customTo || undefined}
                    onChange={e => setCustomFrom(e.target.value)}
                    className={inputCls}
                  />
                </div>
                <div className="flex flex-col gap-1">
                  <label className="obs-label">To</label>
                  <input
                    type="datetime-local"
                    value={customTo}
                    min={customFrom || undefined}
                    onChange={e => setCustomTo(e.target.value)}
                    className={inputCls}
                  />
                </div>
                <button
                  type="button"
                  disabled={!customFrom || !customTo}
                  onClick={() => {
                    const a = new Date(customFrom).getTime();
                    const b = new Date(customTo).getTime();
                    if (!Number.isFinite(a) || !Number.isFinite(b) || a === b) return;
                    setCustomRange({ start: Math.min(a, b), end: Math.max(a, b) });
                    setLiveRefresh(false);
                  }}
                  className="obs-btn obs-btn-soft">
                  Apply
                </button>
              </>
            )}
            <div className="flex flex-col gap-1 min-w-[160px]">
              <label className="obs-label">Host / Source</label>
              <input
                type="text"
                value={sourceFilter}
                onChange={e => setSourceFilter(e.target.value)}
                placeholder="e.g. ip-172-31-0-224"
                className={`${inputCls} w-full`}
              />
            </div>
            <div className="flex flex-col gap-1 flex-1 min-w-[200px]">
              <label className="obs-label">Search</label>
              <input
                type="text"
                value={keyword}
                onChange={e => setKeyword(e.target.value)}
                placeholder="Search logs..."
                className={`${inputCls} w-full`}
              />
            </div>
            <button type="submit" disabled={logsLoading}
              className="obs-btn obs-btn-primary px-4">
              {logsLoading ? 'Searching…' : 'Search'}
            </button>
            <button type="button" onClick={goCreateAlert}
              title="Create an alert rule from the current filters"
              className="obs-btn obs-btn-ghost">
              <Icon icon="mdi:bell-plus-outline" width={14} /> Alert
            </button>
          </form>
        </div>
      )}

      {/* SQL Query Editor */}
      {mode === 'query' && (
        <div className={`${cardCls} p-4 space-y-3`}>
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <Icon icon="mdi:file-code-outline" width={14} className="text-[#64748b]" />
              <span className="obs-h2 text-[14px]">
                SQL Query Editor
              </span>
              <span className="text-[11px] px-2 py-0.5 rounded-full bg-[rgba(15,23,42,0.04)] border border-[rgba(15,23,42,0.06)] text-[#64748b]">
                OpenObserve SQL
              </span>
            </div>
            <div className="flex gap-2 items-center">
              <select
                value={timeRange.label}
                onChange={e => { setCustomRange(null); setTimeRange(TIME_RANGES.find(t => t.label === e.target.value) || TIME_RANGES[1]); }}
                className={`${inputCls} !pr-8 text-xs !py-1`}
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
              className="obs-input w-full font-mono !p-3 resize-y"
            />
            <div className="flex items-center justify-between">
              <div className="text-[11px] text-[#94a3b8]">
                Tip: Use <code className="px-1 rounded bg-[rgba(15,23,42,0.04)] text-[#334155]">match_all('keyword')</code> for full-text search
              </div>
              <div className="flex gap-2">
                <button type="button"
                  onClick={() => setSqlQuery(`SELECT * FROM "${selectedStream}" ORDER BY _timestamp DESC LIMIT 100`)}
                  className="obs-btn obs-btn-soft obs-btn-sm">
                  Reset
                </button>
                <button type="submit" disabled={logsLoading || !sqlQuery.trim()}
                  className="obs-btn obs-btn-primary px-4">
                  <Icon icon="mdi:play" width={12} />
                  {logsLoading ? 'Running…' : 'Run Query'}
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
                className="text-[11px] font-medium px-2.5 py-1 rounded-full border border-[rgba(15,23,42,0.1)] text-[#64748b] hover:border-[#0b8f5a] hover:text-[#0b8f5a] hover:bg-[rgba(27,230,139,0.08)] transition-colors">
                {t.label}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Volume Histogram */}
      {histogram.length > 0 && (
        <div className={`${cardCls} p-5`}>
          <div className="flex items-start justify-between mb-3 flex-wrap gap-3">
            <div className="flex items-center gap-3">
              <div className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0 bg-[rgba(27,230,139,0.12)]">
                <Icon icon="mdi:chart-timeline-variant" width={17} color="#0b8f5a" />
              </div>
              <div>
                <p className="obs-h2 text-[14px] leading-tight">Log Volume</p>
                <p className="text-[11px] text-[#94a3b8]">
                  {customRange
                    ? `${new Date(customRange.start).toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })} – ${new Date(customRange.end).toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })} · drag to zoom further`
                    : `Events over ${timeRange.label.toLowerCase()} · drag to zoom`}
                </p>
              </div>
              {customRange && (
                <button
                  type="button"
                  onClick={() => { setCustomRange(null); setShowCustom(false); }}
                  className="obs-btn obs-btn-soft obs-btn-sm">
                  <Icon icon="mdi:magnify-minus-outline" width={12} /> Reset zoom
                </button>
              )}
            </div>
            <div className="flex items-center gap-5">
              <div className="flex items-center gap-3 text-[11px] text-[#64748b]">
                <span className="flex items-center gap-1.5">
                  <span className="w-2 h-2 rounded-full flex-shrink-0" style={{ backgroundColor: '#0b8f5a' }} /> All
                </span>
                <span className="flex items-center gap-1.5">
                  <span className="w-2 h-2 rounded-full flex-shrink-0" style={{ backgroundColor: '#ef4444' }} /> Errors
                </span>
              </div>
              <div className="text-right">
                <p className="obs-eyebrow text-[10px]">Total</p>
                <p className="text-[16px] font-bold text-[#0b1220] leading-tight" style={{ fontVariantNumeric: 'tabular-nums' }}>
                  {fmtCount(histTotal)}
                </p>
              </div>
              <div className="text-right">
                <p className="obs-eyebrow text-[10px]">Peak / bucket</p>
                <p className="text-[16px] font-bold text-[#0b1220] leading-tight" style={{ fontVariantNumeric: 'tabular-nums' }}>
                  {fmtCount(histPeak)}
                </p>
              </div>
            </div>
          </div>
          <ResponsiveContainer width="100%" height={110}>
            <AreaChart
              data={histChartData}
              margin={{ top: 4, right: 4, bottom: 0, left: 0 }}
              onMouseDown={(e: any) => { if (e?.activeLabel != null) setRefLeft(Number(e.activeLabel)); }}
              onMouseMove={(e: any) => { if (refLeft != null && e?.activeLabel != null) setRefRight(Number(e.activeLabel)); }}
              onMouseUp={handleZoomEnd}
              onMouseLeave={() => { setRefLeft(null); setRefRight(null); }}
              style={{ cursor: 'crosshair', userSelect: 'none' }}>
              <defs>
                <linearGradient id="logVolGrad" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="#1be68b" stopOpacity={0.32} />
                  <stop offset="60%" stopColor="#1be68b" stopOpacity={0.07} />
                  <stop offset="100%" stopColor="#1be68b" stopOpacity={0} />
                </linearGradient>
                <linearGradient id="logErrGrad" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="#ef4444" stopOpacity={0.35} />
                  <stop offset="100%" stopColor="#ef4444" stopOpacity={0.04} />
                </linearGradient>
              </defs>
              <CartesianGrid strokeDasharray="3 3" stroke="rgba(15,23,42,0.05)" vertical={false} />
              <XAxis dataKey="ts"
                tickFormatter={(ts: number) => histSpansDays
                  ? new Date(ts).toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
                  : new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                tick={{ fontSize: 10, fill: '#94a3b8' }} axisLine={false} tickLine={false} minTickGap={histSpansDays ? 72 : 40} />
              <YAxis tick={{ fontSize: 10, fill: '#94a3b8' }} axisLine={false} tickLine={false} width={34}
                tickFormatter={(v: number) => fmtCount(v)} />
              <Tooltip
                contentStyle={{ background: '#fff', border: '1px solid rgba(15,23,42,0.14)', borderRadius: 8, fontSize: 12, color: '#0b1220', boxShadow: '0 12px 28px -12px rgba(15,23,42,0.25)' }}
                formatter={(v: any, name: any) => [Number(v).toLocaleString(), name]}
                labelFormatter={(ts: any) => new Date(Number(ts)).toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })}
              />
              <Area type="monotone" dataKey="num" name="All events" stroke="#0b8f5a" strokeWidth={2.25}
                strokeLinecap="round" strokeLinejoin="round" fill="url(#logVolGrad)"
                dot={false} activeDot={{ r: 4, strokeWidth: 2, stroke: '#fff' }} />
              {histHasErrors && (
                <Area type="monotone" dataKey="err" name="Errors" stroke="#ef4444" strokeWidth={2}
                  strokeLinecap="round" strokeLinejoin="round" fill="url(#logErrGrad)"
                  dot={false} activeDot={{ r: 4, strokeWidth: 2, stroke: '#fff' }} />
              )}
              {refLeft != null && refRight != null && (
                <ReferenceArea x1={refLeft} x2={refRight} stroke="#0b8f5a" strokeOpacity={0.3} fill="#1be68b" fillOpacity={0.1} />
              )}
            </AreaChart>
          </ResponsiveContainer>
        </div>
      )}

      {/* Discoverability tips */}
      {showTips && (
        <div className="rounded-xl border border-[rgba(15,23,42,0.08)] bg-[#f4f5f7] px-4 py-3 flex items-start gap-3">
          <Icon icon="mdi:lightbulb-on-outline" width={15} className="text-[#0b8f5a] mt-0.5 flex-shrink-0" />
          <div className="flex-1 grid grid-cols-1 md:grid-cols-3 gap-x-6 gap-y-1.5 text-[12px] text-[#64748b]">
            <span className="flex items-center gap-1.5">
              <Icon icon="mdi:unfold-more-horizontal" width={13} className="text-[#94a3b8] flex-shrink-0" />
              <span><strong className="font-semibold text-[#334155]">Click a row</strong> to expand the full message</span>
            </span>
            <span className="flex items-center gap-1.5">
              <Icon icon="mdi:filter-variant" width={13} className="text-[#94a3b8] flex-shrink-0" />
              <span><strong className="font-semibold text-[#334155]">Click a level or host chip</strong> to filter instantly</span>
            </span>
            <span className="flex items-center gap-1.5">
              <Icon icon="mdi:select-drag" width={13} className="text-[#94a3b8] flex-shrink-0" />
              <span><strong className="font-semibold text-[#334155]">Drag on the chart</strong> to zoom a time window</span>
            </span>
          </div>
          <button
            type="button"
            onClick={dismissTips}
            title="Dismiss tips"
            className="flex-shrink-0 p-1 rounded-md text-[#94a3b8] hover:text-[#0b1220] hover:bg-[rgba(15,23,42,0.05)] transition-colors">
            <Icon icon="mdi:close" width={13} />
          </button>
        </div>
      )}

      {/* Log Table */}
      <div className={`${cardCls} overflow-hidden`}>
        <div className="flex items-center justify-between px-4 py-3 border-b border-[rgba(15,23,42,0.06)]">
          <span className="text-[13px] font-semibold text-[#0b1220]" style={{ fontVariantNumeric: 'tabular-nums' }}>
            {logsLoading ? 'Running…' : `${logTotal.toLocaleString()} result${logTotal !== 1 ? 's' : ''}`}
            {!logsLoading && groupRepeats && groupedRows.length < logs.length && (
              <span className="ml-1.5 font-normal text-[11px] text-[#94a3b8]">
                · {groupedRows.length} after grouping
              </span>
            )}
          </span>
          <div className="flex items-center gap-2">
            <span className="text-[11px] text-[#94a3b8]">Rows:</span>
            <select
              value={pageSize}
              onChange={e => { setPage(0); setPageSize(Number(e.target.value)); }}
              className="obs-input !px-2 !py-1 text-xs">
              {[100, 250, 500, 1000].map(n => <option key={n} value={n}>{n}</option>)}
            </select>
            <button
              type="button"
              onClick={() => { setGroupRepeats(v => !v); setExpandedIdx(null); }}
              disabled={viewMode === 'patterns'}
              title="Collapse consecutive repeats of the same message"
              className={`obs-toggle ${groupRepeats ? 'on' : ''}`}>
              <Icon icon="mdi:layers-outline" width={12} /> Group repeats
            </button>
            <div className="obs-seg">
              {(['list', 'patterns'] as const).map(v => (
                <button
                  key={v}
                  type="button"
                  onClick={() => { setViewMode(v); setExpandedIdx(null); setExpandedPattern(null); }}
                  title={v === 'patterns' ? 'Aggregate the loaded results by message shape' : 'Chronological log list'}
                  className={`obs-seg-btn capitalize ${viewMode === v ? 'active' : ''}`}>
                  {v}
                </button>
              ))}
            </div>
          </div>
          <div className="flex items-center gap-2">
            {liveRefresh && mode === 'filter' && page === 0 && (
              <span className="flex items-center gap-1.5 text-xs text-[#0b8f5a] font-semibold">
                <span className="obs-live-dot inline-block" />
                Live
              </span>
            )}
            <select
              value={liveInterval}
              onChange={e => setLiveInterval(Number(e.target.value))}
              disabled={!liveRefresh}
              className="obs-input !px-2 !py-1 text-xs">
              {[5, 10, 30, 60].map(s => <option key={s} value={s}>{s}s</option>)}
            </select>
            <button
              onClick={() => setLiveRefresh(v => !v)}
              className={`obs-toggle ${liveRefresh ? 'on' : ''}`}>
              <Icon icon={liveRefresh ? 'mdi:pause' : 'mdi:play'} width={12} />
              {liveRefresh ? 'Pause' : 'Live'}
            </button>
            <button onClick={() => doSearch(true)} title="Refresh results"
              className="obs-btn obs-btn-soft obs-btn-sm">
              <Icon icon="mdi:refresh" width={13} />
            </button>
            <button
              type="button"
              onClick={() => {
                navigator.clipboard.writeText(window.location.href);
                setCopiedLink(true);
                setTimeout(() => setCopiedLink(false), 1500);
              }}
              title="Copy a link to this exact view (filters + time window)"
              className={copiedLink ? 'obs-toggle on' : 'obs-btn obs-btn-soft obs-btn-sm'}>
              <Icon icon={copiedLink ? 'mdi:check' : 'mdi:link-variant'} width={12} />
              {copiedLink ? 'Copied' : 'Share'}
            </button>
            {!showTips && (
              <button onClick={reopenTips} title="Show usage tips"
                className="text-[#94a3b8] hover:text-[#0b1220] p-1 rounded-md hover:bg-[rgba(15,23,42,0.05)] transition-colors">
                <Icon icon="mdi:help-circle-outline" width={15} />
              </button>
            )}
          </div>
        </div>

        {logsLoading ? (
          <div className="flex items-center justify-center py-16">
            <div className="obs-spinner" />
          </div>
        ) : logs.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-16 gap-2 text-[#94a3b8]">
            <Icon icon="mdi:text-box-search-outline" width={30} className="opacity-50" />
            <span className="text-[13px] font-medium text-[#334155]">No logs found</span>
            <span className="text-[12px] text-[#64748b]">Try a wider time range, or loosen the filters.</span>
            {(keyword || sourceFilter || level !== 'ALL' || customRange) && (
              <button
                type="button"
                onClick={() => {
                  setKeyword(''); setSourceFilter(''); setLevel('ALL'); setCustomRange(null);
                  setSearchNonce(n => n + 1);
                }}
                className="obs-btn obs-btn-ghost obs-btn-sm mt-1">
                <Icon icon="mdi:filter-off-outline" width={13} /> Clear all filters
              </button>
            )}
          </div>
        ) : viewMode === 'patterns' ? (
          <div className="divide-y divide-[rgba(15,23,42,0.06)]">
            <div className="px-4 py-2 text-[11px] text-[#94a3b8] bg-[#f7f8fa]">
              {patterns.length} message shapes across the {logs.length} loaded results — timestamps and numbers are ignored when grouping.
            </div>
            {patterns.map(p => {
              const isOpen = expandedPattern === p.key;
              const sampleMsg = messageOf(p.sample);
              const occurrences = isOpen ? logs.filter(l => patternKeyOf(l) === p.key).slice(0, 5) : [];
              return (
                <div key={p.key} className={isOpen ? 'bg-[#f7f8fa]' : ''}>
                  <div
                    onClick={() => setExpandedPattern(prev => (prev === p.key ? null : p.key))}
                    className="flex items-center gap-3 px-4 py-2.5 cursor-pointer text-sm hover:bg-[rgba(15,23,42,0.025)]">
                    <Icon icon="mdi:chevron-right" width={14}
                      className={`flex-shrink-0 text-[#94a3b8] transition-transform ${isOpen ? 'rotate-90' : ''}`} />
                    <span className="flex-shrink-0 w-14 text-right text-[13px] font-bold text-[#0b1220]" style={{ fontVariantNumeric: 'tabular-nums' }}>
                      {p.count.toLocaleString()}
                    </span>
                    <span className="flex-shrink-0 w-10 text-right text-[11px] text-[#94a3b8]" style={{ fontVariantNumeric: 'tabular-nums' }}>
                      {logs.length > 0 ? Math.round((p.count / logs.length) * 100) : 0}%
                    </span>
                    <span className={`flex-shrink-0 ${LEVEL_COLORS[p.level] || LEVEL_COLORS['INFO']}`}>
                      {p.level}
                    </span>
                    <span className="flex-1 font-mono truncate text-[#334155]">
                      {sampleMsg.slice(0, 300)}
                    </span>
                  </div>
                  {isOpen && (
                    <div className="px-14 pb-3 space-y-1.5">
                      {occurrences.map((l, i) => (
                        <div key={i} className="flex items-center gap-3 text-[11px] text-[#64748b]">
                          <span className="font-mono flex-shrink-0" style={{ fontVariantNumeric: 'tabular-nums' }}>
                            {l._timestamp ? new Date(l._timestamp / 1000).toLocaleString() : '—'}
                          </span>
                          <span className="font-mono truncate">{String(l['host.name'] || l['host_name'] || '')}</span>
                          <button
                            type="button"
                            onClick={() => setSelectedLog(l)}
                            className="ml-auto flex-shrink-0 text-[11px] font-semibold text-[#64748b] hover:text-[#0b1220] transition-colors">
                            Details →
                          </button>
                        </div>
                      ))}
                      {p.count > occurrences.length && (
                        <p className="text-[11px] text-[#94a3b8]">…and {p.count - occurrences.length} more in the loaded results</p>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        ) : (
          <div className="divide-y divide-[rgba(15,23,42,0.06)]">
            {groupedRows.map((row, idx) => (
              <LogRow
                key={idx}
                log={row.log}
                count={row.count}
                oldestTs={row.oldestTs}
                expanded={expandedIdx === idx}
                onToggle={() => setExpandedIdx(prev => (prev === idx ? null : idx))}
                onOpenDetails={() => setSelectedLog(row.log)}
                onFilterLevel={filterByLevel}
                onFilterSource={filterBySource}
                onShowContext={() => showContext(row.log)}
              />
            ))}
          </div>
        )}

        {viewMode === 'list' && logTotal > pageSize && (
          <div className="flex items-center justify-between px-4 py-3 border-t border-[rgba(15,23,42,0.06)]">
            <span className="text-[11px] text-[#94a3b8]" style={{ fontVariantNumeric: 'tabular-nums' }}>
              Page {page + 1} of {Math.ceil(logTotal / pageSize)} · showing {Math.min(pageSize, logTotal - page * pageSize)} of {logTotal.toLocaleString()}
            </span>
            <div className="flex gap-2">
              <button disabled={page === 0}
                onClick={() => { const np = page - 1; setPage(np); doSearch(false, np); }}
                className="obs-btn obs-btn-ghost obs-btn-sm">
                <Icon icon="mdi:arrow-left" width={12} /> Prev
              </button>
              <button disabled={(page + 1) * pageSize >= logTotal}
                onClick={() => { const np = page + 1; setPage(np); doSearch(false, np); }}
                className="obs-btn obs-btn-ghost obs-btn-sm">
                Next <Icon icon="mdi:arrow-right" width={12} />
              </button>
            </div>
          </div>
        )}
      </div>

      <LogDetailDrawer log={selectedLog} onClose={() => setSelectedLog(null)} onFilterField={filterByField} onShowContext={showContext} />
    </div>
  );
};

export default LogsExplorer;
