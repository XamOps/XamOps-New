/**
 * DEMO DATA for XamPulse. Generated, deterministic sample data that is NOT from any AWS account.
 * Only reachable when isXpDemo() is true (dev server + localStorage xp:demo=1, or VITE_XAMPULSE_DEMO=true).
 * Never used as a fallback for failed requests. Every account returned here carries demo: true so the UI
 * shows the DEMO banner and pills. Hosts are named demo-* with instance ids i-demo*.
 */
import { XpApiError } from '../api/xampulseErrors';
import type {
  XpAccountRef, XpAlertRule, XpAttentionItem, XpBucketCandidate, XpChannel, XpChecklistCheck, XpContainerRow, XpEvent,
  XpForecast, XpHealth, XpHostRow, XpHostSummary, XpIncident, XpInsight, XpInstanceCandidate, XpLane, XpLogRow,
  XpLogSource, XpMetricCatalogEntry, XpMetricQueryRequest, XpMetricQueryResponse, XpMetricSeries, XpOverview,
  XpProcessRow, XpSeverity, XpSetupOverview, XpSilence, XpTrustPanel, XpUsage, XpScope, XpLogSearchRequest,
  XpLogFacetsRequest, XpLogHistogramRequest, XpLogPatternsRequest, XpLogPattern,
} from '../types/xampulse';

const ACCOUNT_ID = 1;
const MIN = 60_000;

function rng(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

interface DemoHost { id: string; name: string; type: string; cpu: number; mem: number; disk: number; health: XpHealth; reason?: string }
const HOSTS: DemoHost[] = [
  { id: 'i-demo0001', name: 'demo-web-1', type: 't3.large', cpu: 34, mem: 52, disk: 41, health: 'OK' },
  { id: 'i-demo0002', name: 'demo-web-2', type: 't3.large', cpu: 38, mem: 55, disk: 43, health: 'OK' },
  { id: 'i-demo0003', name: 'demo-web-3', type: 't3.large', cpu: 78, mem: 61, disk: 44, health: 'WARN', reason: 'CPU steal is 14%, above the 10% baseline' },
  { id: 'i-demo0004', name: 'demo-api-1', type: 'm5.xlarge', cpu: 45, mem: 63, disk: 38, health: 'OK' },
  { id: 'i-demo0005', name: 'demo-api-2', type: 'm5.xlarge', cpu: 52, mem: 91, disk: 40, health: 'CRIT', reason: 'Out-of-memory kills in the last 30 minutes' },
  { id: 'i-demo0006', name: 'demo-db-1', type: 'r5.xlarge', cpu: 29, mem: 72, disk: 86, health: 'WARN', reason: '/var is 86% full and growing' },
];

const nowMs = (): number => Date.now();
const iso = (ms: number): string => new Date(ms).toISOString();

function series(seed: number, base: number, amp: number, from: number, to: number, stepMs: number, drift = 0): [number, number][] {
  const r = rng(seed);
  const out: [number, number][] = [];
  const total = Math.max(1, Math.floor((to - from) / stepMs));
  for (let i = 0; i <= total; i += 1) {
    const t = from + i * stepMs;
    const wave = Math.sin((t / 3_600_000) * Math.PI / 6) * amp;
    const v = base + wave + (r() - 0.5) * amp * 0.6 + drift * (i / total);
    out.push([t, Math.max(0, Math.min(100, Number(v.toFixed(2))))]);
  }
  return out;
}

const hostIndex = (id: string): number => Math.max(0, HOSTS.findIndex((h) => h.id === id));

function hostRow(h: DemoHost, i: number): XpHostRow {
  const to = nowMs();
  return {
    accountId: ACCOUNT_ID, instanceId: h.id, name: h.name, region: 'us-east-1', platform: 'Linux', instanceType: h.type, state: 'running',
    health: h.health, score: h.health === 'OK' ? 92 - i : h.health === 'WARN' ? 68 : 41, reason: h.reason,
    cpuPct: h.cpu, memPct: h.mem, diskPct: h.disk, netInBps: 120_000 * (i + 1), netOutBps: 80_000 * (i + 1), load1: Number((h.cpu / 40).toFixed(2)),
    cpuSpark: series(100 + i, h.cpu, 8, to - 60 * MIN, to, 5 * MIN).map((p) => p[1]), lastSeenAt: iso(to - 20_000), agentStatus: 'ACTIVE',
    collectionLevel: 'T1', tags: { env: 'demo', role: h.name.split('-')[1] },
  };
}

// ---- logs ----
const SERVICES: Record<string, string[]> = {
  nginx: ['GET /api/v1/orders 200 12ms', 'GET /healthz 200 1ms', 'POST /api/v1/login 401 8ms', 'GET /static/app.js 304 2ms', 'upstream timed out (110: Connection timed out) while reading response header'],
  app: ['order 4821 processed in 233ms', 'cache miss for key user:9921', 'retrying payment provider call attempt 2', 'user 5521 signed in', 'slow query detected: 1840ms'],
  auth: ['Accepted publickey for ec2-user from 10.0.4.12', 'Failed password for invalid user admin from 203.0.113.9', 'session opened for user ec2-user'],
  syslog: ['systemd[1]: Started Daily apt download activities.', 'kernel: TCP: request_sock_TCP: Possible SYN flooding on port 443', 'CRON[2211]: (root) CMD (/usr/local/bin/rotate-logs)'],
  oom: ['Out of memory: Killed process 8123 (java) total-vm:6021432kB', 'java.lang.OutOfMemoryError: Java heap space'],
};

let logPool: XpLogRow[] | null = null;
function pool(): XpLogRow[] {
  const now = nowMs();
  if (logPool && now - Date.parse(logPool[0].ts) < 30 * MIN) return logPool;
  const r = rng(42);
  const rows: XpLogRow[] = [];
  const names = Object.keys(SERVICES);
  for (let i = 0; i < 1800; i += 1) {
    const h = HOSTS[Math.floor(r() * HOSTS.length)];
    let svc = names[Math.floor(r() * (names.length - 1))];
    if (h.name === 'demo-api-2' && r() < 0.18) svc = 'oom';
    const msgs = SERVICES[svc];
    const body = msgs[Math.floor(r() * msgs.length)];
    const sev: XpSeverity = svc === 'oom' ? 'ERROR' : /timed out|Failed|slow query|retrying/.test(body) ? (r() < 0.5 ? 'WARN' : 'ERROR') : r() < 0.06 ? 'DEBUG' : 'INFO';
    const ts = now - Math.floor(r() * r() * 24 * 60 * MIN);
    rows.push({
      id: `demo-${i}`, ts: iso(ts), accountId: ACCOUNT_ID, region: 'us-east-1', instanceId: h.id, service: svc === 'oom' ? 'kernel' : svc,
      source: svc === 'nginx' ? '/var/log/nginx/access.log' : svc === 'auth' ? '/var/log/auth.log' : '/var/log/syslog', severity: sev, body,
      attrs: { host: h.name, 'xp.demo': 'true' },
    });
  }
  rows.sort((a, b) => Date.parse(b.ts) - Date.parse(a.ts));
  logPool = rows;
  return rows;
}

function matches(row: XpLogRow, query: string): boolean {
  const tokens = query.match(/-?[\w.]+:"[^"]*"|-?[\w.]+:\([^)]*\)|"[^"]*"|\S+/g) ?? [];
  for (const raw of tokens) {
    if (raw === 'AND' || raw === 'OR' || raw === '(' || raw === ')') continue;
    let neg = false;
    let tok = raw;
    if (tok.startsWith('-')) { neg = true; tok = tok.slice(1); }
    let hit: boolean;
    const m = /^([\w.]+):(.*)$/.exec(tok);
    if (m) {
      const vals = m[2].replace(/^\(|\)$/g, '').split(/\s+OR\s+/).map((v) => v.replace(/^[><=]+/, '').replace(/^"|"$/g, '').toLowerCase());
      const f = m[1].toLowerCase();
      const have = f === 'service' ? row.service : f === 'severity' || f === 'level' ? row.severity : f === 'instance' || f === 'instance_id' || f === 'host' ? row.instanceId : f === 'source' ? row.source : f === 'region' ? row.region : '';
      hit = vals.some((v) => have.toLowerCase() === v || (v.endsWith('*') && have.toLowerCase().startsWith(v.slice(0, -1))));
    } else {
      hit = row.body.toLowerCase().includes(tok.replace(/^"|"$/g, '').toLowerCase());
    }
    if (neg ? hit : !hit) return false;
  }
  return true;
}

function filtered(scope: XpScope, query: string): XpLogRow[] {
  const from = Date.parse(scope.from);
  const to = Date.parse(scope.to);
  const inst = scope.resource?.instanceIds;
  return pool().filter((r) => {
    const t = Date.parse(r.ts);
    return t >= from && t <= to && (!inst || inst.length === 0 || inst.includes(r.instanceId)) && matches(r, query);
  });
}

function logSearch(req: XpLogSearchRequest) {
  const rows = filtered(req.scope, req.query);
  const limit = req.limit ?? 200;
  const offset = req.cursor ? Number(req.cursor) || 0 : 0;
  const page = rows.slice(offset, offset + limit);
  const next = offset + limit < rows.length ? String(offset + limit) : undefined;
  return { rows: page, nextCursor: next, scanned: { rows: rows.length, bytes: rows.length * 180 }, tookMs: 12, truncated: false, parsed: { normalised: req.query.trim() } };
}

function logHistogram(req: XpLogHistogramRequest) {
  const rows = filtered(req.scope, req.query);
  const from = Date.parse(req.scope.from);
  const to = Date.parse(req.scope.to);
  const n = Math.min(req.buckets ?? 60, 120);
  const step = Math.max(1000, Math.floor((to - from) / n));
  const buckets = Array.from({ length: n }, (_, i) => ({ t: iso(from + i * step), counts: {} as Partial<Record<XpSeverity, number>> }));
  rows.forEach((r) => {
    const idx = Math.min(n - 1, Math.floor((Date.parse(r.ts) - from) / step));
    if (idx >= 0) buckets[idx].counts[r.severity] = (buckets[idx].counts[r.severity] ?? 0) + 1;
  });
  return { bucketSec: Math.round(step / 1000), buckets };
}

function logFacets(req: XpLogFacetsRequest) {
  const rows = filtered(req.scope, req.query);
  const pick = (f: string, r: XpLogRow): string => (f === 'service' ? r.service : f === 'severity' ? r.severity : f === 'instance_id' ? r.instanceId : f === 'source' ? r.source : f === 'region' ? r.region : r.containerId ?? '');
  return {
    approx: false,
    facets: req.fields.map((field) => {
      const m = new Map<string, number>();
      rows.forEach((r) => { const v = pick(field, r); if (v) m.set(v, (m.get(v) ?? 0) + 1); });
      return { field, values: Array.from(m.entries()).sort((a, b) => b[1] - a[1]).slice(0, req.topN ?? 8).map(([value, count]) => ({ value, count })) };
    }),
  };
}

function logPatterns(req: XpLogPatternsRequest): { patterns: XpLogPattern[] } {
  const rows = filtered(req.scope, req.query);
  const groups = new Map<string, { n: number; sample: XpLogRow }>();
  rows.forEach((r) => {
    const tpl = r.body.replace(/\d+/g, '<N>');
    const g = groups.get(tpl);
    if (g) g.n += 1; else groups.set(tpl, { n: 1, sample: r });
  });
  const r = rng(7);
  return {
    patterns: Array.from(groups.entries()).sort((a, b) => b[1].n - a[1].n).slice(0, req.limit ?? 20).map(([template, g], i) => ({
      patternId: `p${i}`, template, count: g.n, deltaPct: i === 2 ? null : Math.round((r() - 0.4) * 120), isNew: i === 2, trend: Array.from({ length: 12 }, () => Math.round(r() * g.n / 6)),
      firstSeen: i === 2 ? iso(nowMs() - 25 * MIN) : g.sample.ts, sampleId: g.sample.id, severity: g.sample.severity,
    })),
  };
}

// ---- hosts ----
function lanesFor(h: DemoHost): XpLane[] {
  const lane = (l: XpLane['lane'], health: XpHealth, reason: string): XpLane => ({ lane: l, health, reason });
  return [
    lane('CPU', h.name === 'demo-web-3' ? 'WARN' : 'OK', h.name === 'demo-web-3' ? 'Steal 14% for 40 minutes (baseline 2%)' : `Average ${h.cpu}% over the range`),
    lane('MEMORY', h.mem > 85 ? 'CRIT' : 'OK', h.mem > 85 ? `Memory ${h.mem}%, swapping` : `Memory ${h.mem}%, no swapping`),
    lane('DISK', h.disk > 80 ? 'WARN' : 'OK', h.disk > 80 ? '/var 86% full, about 6 days left' : `Disks under ${h.disk + 5}%`),
    lane('NETWORK', 'OK', 'No errors or drops'),
    lane('ERRORS', h.name === 'demo-api-2' ? 'CRIT' : 'OK', h.name === 'demo-api-2' ? '38 errors in 30 minutes, mostly OOM kills' : 'Error rate normal'),
    lane('FRESHNESS', 'OK', 'Last data 20 seconds ago'),
  ];
}

function hostSummary(id: string, from: number, to: number): XpHostSummary {
  const i = hostIndex(id);
  const h = HOSTS[i];
  const step = Math.max(MIN, Math.floor((to - from) / 120));
  const s: Record<string, [number, number][]> = {
    'system.cpu.utilization': series(200 + i, h.cpu, 10, from, to, step, h.name === 'demo-web-3' ? 25 : 0),
    'system.cpu.utilization{state=steal}': series(210 + i, h.name === 'demo-web-3' ? 9 : 1, 3, from, to, step, h.name === 'demo-web-3' ? 6 : 0),
    'system.cpu.utilization{state=iowait}': series(220 + i, 3, 2, from, to, step),
    'system.memory.utilization': series(230 + i, h.mem, 4, from, to, step, h.name === 'demo-api-2' ? 12 : 0),
    'system.paging.utilization': series(240 + i, h.name === 'demo-api-2' ? 40 : 2, 3, from, to, step),
    'system.filesystem.utilization{mountpoint=/}': series(250 + i, 41, 0.5, from, to, step, 1),
    'system.filesystem.utilization{mountpoint=/var}': series(260 + i, h.disk, 0.5, from, to, step, h.name === 'demo-db-1' ? 4 : 1),
    'system.disk.io{direction=read}': series(270 + i, 20, 15, from, to, step),
    'system.disk.io{direction=write}': series(280 + i, 35, 20, from, to, step),
    'system.network.io{direction=receive}': series(290 + i, 40, 25, from, to, step),
    'system.network.io{direction=transmit}': series(300 + i, 28, 18, from, to, step),
  };
  for (let c = 0; c < 4; c += 1) s[`system.cpu.utilization{cpu=${c}}`] = series(400 + i * 10 + c, h.cpu + (c - 1.5) * 6, 12, from, to, step);
  return {
    host: hostRow(h, i), lanes: lanesFor(h), series: s,
    recentErrors: pool().filter((r) => r.instanceId === id && (r.severity === 'ERROR' || r.severity === 'WARN')).slice(0, 6),
    events: eventsFor(from, to).filter((e) => e.resource === id || e.resource === ''),
    openIncidents: h.health === 'CRIT' ? [incidents()[0]] : [],
    rightsizing: h.cpu < 40 ? { note: `P95 CPU is ${Math.round(h.cpu * 1.4)}% and P95 memory ${Math.round(h.mem * 1.1)}% over 30 days. A smaller size would likely fit.`, observedP95Cpu: Math.round(h.cpu * 1.4), observedP95Mem: Math.round(h.mem * 1.1), link: '/rightsizing' } : undefined,
  };
}

function processes(id: string): XpProcessRow[] {
  const r = rng(hostIndex(id) + 55);
  const names = ['java', 'nginx', 'postgres', 'node', 'python3', 'containerd', 'amazon-ssm-agent', 'xampulse-agent', 'sshd', 'systemd'];
  return names.map((name, i) => ({ pid: 1000 + i * 137, name, user: i % 3 === 0 ? 'root' : 'app', cpuPct: Number((r() * (30 - i * 2)).toFixed(1)), memPct: Number((r() * (22 - i)).toFixed(1)), memBytes: Math.round(r() * 2e9), threads: 4 + Math.floor(r() * 60) }))
    .sort((a, b) => b.cpuPct - a.cpuPct);
}

function eventsFor(from: number, to: number): XpEvent[] {
  const now = nowMs();
  const all: XpEvent[] = [
    { ts: iso(now - 25 * MIN), kind: 'ANOMALY', accountId: ACCOUNT_ID, resource: 'i-demo0005', title: 'New ERROR pattern: OutOfMemoryError', detail: 'First seen 25 minutes ago' },
    { ts: iso(now - 3 * 3_600_000), kind: 'DEPLOY', accountId: ACCOUNT_ID, resource: '', title: 'Deploy api v2.14.0 finished', detail: 'Detected from logs on demo-api-1 and demo-api-2' },
    { ts: iso(now - 5 * 3_600_000), kind: 'EC2_STATE', accountId: ACCOUNT_ID, resource: 'i-demo0003', title: 'demo-web-3 restarted', detail: 'running to stopped to running' },
    { ts: iso(now - 40 * MIN), kind: 'ALERT', accountId: ACCOUNT_ID, resource: 'i-demo0005', title: 'Alert fired: Memory above 90%' },
  ];
  return all.filter((e) => { const t = Date.parse(e.ts); return t >= from && t <= to; });
}

function incidents(): XpIncident[] {
  const now = nowMs();
  return [{
    id: 1, accountId: ACCOUNT_ID, title: 'Memory above 90% on demo-api-2', severity: 'HIGH', state: 'OPEN', openedAt: iso(now - 38 * MIN), ruleIds: [2], instanceIds: ['i-demo0005'],
    summary: 'Memory has stayed above 90% since the api v2.14.0 deploy and the kernel started killing java.',
    timeline: [{ ts: iso(now - 38 * MIN), kind: 'OPENED', text: 'Rule "Memory above 90%" fired' }, { ts: iso(now - 25 * MIN), kind: 'PATTERN', text: 'New log pattern: OutOfMemoryError' }],
  }];
}

function rules(): XpAlertRule[] {
  return [
    { id: 1, version: 1, name: 'CPU above 90%', enabled: true, severity: 'HIGH', scope: { accountIds: [ACCOUNT_ID] }, condition: { type: 'METRIC_THRESHOLD', metric: 'system.cpu.utilization', agg: 'avg', window: '5m', op: '>', value: 90, for: '10m', noData: 'KEEP' }, grouping: { by: ['instance_id'], dedupeWindow: '30m' }, routing: { channelIds: [1], createTicket: false } },
    { id: 2, version: 1, name: 'Memory above 90%', enabled: true, severity: 'HIGH', scope: { accountIds: [ACCOUNT_ID] }, condition: { type: 'METRIC_THRESHOLD', metric: 'system.memory.utilization', agg: 'avg', window: '5m', op: '>', value: 90, for: '5m', noData: 'KEEP' }, grouping: { by: ['instance_id'], dedupeWindow: '30m' }, routing: { channelIds: [1], createTicket: false } },
    { id: 3, version: 1, name: 'Host stopped reporting', enabled: true, severity: 'CRITICAL', scope: { accountIds: [ACCOUNT_ID] }, condition: { type: 'HEARTBEAT_MISSING', window: '5m', op: '>', for: '0m', noData: 'ALERT' }, grouping: { by: ['instance_id'], dedupeWindow: '1h' }, routing: { channelIds: [1], createTicket: false } },
  ];
}

// ---- metrics ----
const CATALOG: XpMetricCatalogEntry[] = [
  { name: 'system.cpu.utilization', unit: '%', kind: 'GAUGE', labelKeys: ['state', 'instance_id'] },
  { name: 'system.cpu.load_average.1m', unit: '', kind: 'GAUGE', labelKeys: ['instance_id'] },
  { name: 'system.memory.utilization', unit: '%', kind: 'GAUGE', labelKeys: ['state', 'instance_id'] },
  { name: 'system.paging.utilization', unit: '%', kind: 'GAUGE', labelKeys: ['instance_id'] },
  { name: 'system.filesystem.utilization', unit: '%', kind: 'GAUGE', labelKeys: ['mountpoint', 'device', 'instance_id'] },
  { name: 'system.disk.io', unit: 'By/s', kind: 'COUNTER', labelKeys: ['direction', 'instance_id'] },
  { name: 'system.network.io', unit: 'By/s', kind: 'COUNTER', labelKeys: ['direction', 'device', 'instance_id'] },
  { name: 'system.network.errors', unit: '', kind: 'COUNTER', labelKeys: ['instance_id'] },
  { name: 'process.cpu.utilization', unit: '%', kind: 'GAUGE', labelKeys: ['process', 'instance_id'] },
  { name: 'container.cpu.utilization', unit: '%', kind: 'GAUGE', labelKeys: ['container', 'instance_id'] },
];

function metricQuery(req: XpMetricQueryRequest): XpMetricQueryResponse {
  const from = Date.parse(req.scope.from);
  const to = Date.parse(req.scope.to);
  const step = Math.max(MIN, Math.floor((to - from) / 150));
  const out: XpMetricSeries[] = [];
  req.series.forEach((s, si) => {
    const grouped = (s.groupBy ?? []).includes('instance_id');
    const targets = grouped ? HOSTS : [HOSTS[0]];
    targets.forEach((h, hi) => {
      const base = s.metric.includes('memory') ? h.mem : s.metric.includes('filesystem') ? h.disk : s.metric.includes('io') ? 30 + hi * 4 : h.cpu;
      const pts = series(500 + si * 31 + hi, base, 9, from, to, step);
      const item: XpMetricSeries = { metric: s.metric, labels: grouped ? { instance_id: h.id, host: h.name } : { scope: 'all hosts (avg)' }, points: pts };
      if (req.band === 'ANOMALY') item.band = pts.map(([t, v]): [number, number, number] => [t, Math.max(0, v - 12), Math.min(100, v + 12)]);
      if (req.compareOffsetSec) item.compare = series(600 + hi, base - 3, 9, from, to, step).map(([t, v]): [number, number] => [t + req.compareOffsetSec! * 1000, v]);
      out.push(item);
    });
  });
  return { stepSec: Math.round(step / 1000), series: out, tookMs: 18 };
}

// ---- overview ----
function overview(): XpOverview {
  const rows = HOSTS.map(hostRow);
  const attention: XpAttentionItem[] = [
    { id: 'a1', severity: 'CRITICAL', title: 'demo-api-2 is running out of memory', detail: 'Memory is at 91% and the kernel killed java 3 times since the 14:02 deploy. A new OutOfMemoryError pattern appeared 25 minutes ago.', accountId: ACCOUNT_ID, instanceId: 'i-demo0005', since: iso(nowMs() - 38 * MIN), confidence: 'HIGH',
      evidence: [{ kind: 'METRIC', label: 'Memory 91%', deepLink: '/xampulse/hosts/i-demo0005' }, { kind: 'LOG', label: '38 OOM errors', deepLink: '/xampulse/logs?q=instance%3Ai-demo0005%20severity%3Aerror&range=1h' }] },
    { id: 'a2', severity: 'MEDIUM', title: 'demo-db-1 disk is filling', detail: '/var is 86% full. At the current rate it will be full in about 6 days (range 4 to 9).', accountId: ACCOUNT_ID, instanceId: 'i-demo0006', since: iso(nowMs() - 6 * 3_600_000), confidence: 'MEDIUM',
      evidence: [{ kind: 'METRIC', label: '/var 86%', deepLink: '/xampulse/hosts/i-demo0006' }] },
    { id: 'a3', severity: 'MEDIUM', title: 'demo-web-3 has CPU steal', detail: 'Steal time is 14% versus a 2% baseline. A noisy neighbour or an undersized burstable instance is likely.', accountId: ACCOUNT_ID, instanceId: 'i-demo0003', since: iso(nowMs() - 40 * MIN), confidence: 'MEDIUM',
      evidence: [{ kind: 'METRIC', label: 'Steal 14%', deepLink: '/xampulse/hosts/i-demo0003' }] },
  ];
  return {
    demo: true, state: 'CONNECTED', lastDataAt: iso(nowMs() - 20_000), healthScore: 74,
    kpis: { hostsReporting: 6, hostsTotal: 6, errorsPerMin: 3.4, ingestBytes24h: 1.8e9, openIncidents: 1, coveragePct: 100 },
    narrative: ['Your fleet is mostly healthy, but 1 of 6 hosts needs attention now.', 'demo-api-2 ran out of memory after the 14:02 deploy; two more hosts show early warning signs.'],
    attention, tiles: rows.map((r) => ({ accountId: r.accountId, instanceId: r.instanceId, name: r.name, health: r.health, score: r.score, cpuPct: r.cpuPct, memPct: r.memPct })),
  };
}

function insights(): XpInsight[] {
  return [
    { id: 'i1', kind: 'WHAT_CHANGED', severity: 'HIGH', title: 'Error rate tripled after the api v2.14.0 deploy', body: 'Errors went from 1.1 to 3.4 per minute within 10 minutes of the deploy on demo-api-1 and demo-api-2.', accountId: ACCOUNT_ID, evidence: [{ kind: 'EVENT', label: 'Deploy api v2.14.0', deepLink: '/xampulse/logs?q=severity%3Aerror&range=6h' }], confidence: 'HIGH', asOf: iso(nowMs()) },
    { id: 'i2', kind: 'NEW_PATTERN', severity: 'MEDIUM', title: 'New log pattern: OutOfMemoryError', body: 'Seen 38 times on demo-api-2 since 25 minutes ago. It did not appear in the previous 7 days.', accountId: ACCOUNT_ID, instanceId: 'i-demo0005', evidence: [{ kind: 'LOG', label: 'Open pattern', deepLink: '/xampulse/logs?q=OutOfMemoryError&range=1h' }], confidence: 'HIGH', asOf: iso(nowMs()) },
    { id: 'i3', kind: 'RIGHTSIZING', severity: 'LOW', title: 'demo-web-1 and demo-web-2 look oversized', body: 'P95 CPU is below 55% and P95 memory below 60% over 30 days.', accountId: ACCOUNT_ID, evidence: [], confidence: 'MEDIUM', asOf: iso(nowMs()) },
    { id: 'i4', kind: 'COST', severity: 'INFO', title: 'nginx access logs are 61% of your log volume', body: 'Health-check requests (GET /healthz) make up 40% of those lines. You can drop them at the source.', accountId: ACCOUNT_ID, evidence: [], confidence: 'HIGH', asOf: iso(nowMs()) },
  ];
}

function forecasts(): XpForecast[] {
  return [{ accountId: ACCOUNT_ID, instanceId: 'i-demo0006', resource: '/var', metric: 'system.filesystem.utilization', daysToFull: 6, intervalDays: [4, 9], r2: 0.94, message: 'Disk /var full in 6 days (4 to 9)' }];
}

function usage(): XpUsage {
  const days: XpUsage['byDay'] = [];
  const r = rng(3);
  for (let d = 6; d >= 0; d -= 1) {
    const day = new Date(nowMs() - d * 86_400_000).toISOString().slice(0, 10);
    days.push({ day, signal: 'LOGS', bytesIn: Math.round(1.1e9 + r() * 4e8), bytesStored: Math.round(2.3e8 + r() * 5e7) });
    days.push({ day, signal: 'METRICS', bytesIn: Math.round(2.5e8 + r() * 4e7), bytesStored: Math.round(5e7 + r() * 1e7) });
  }
  return { range: '7d', byDay: days, bySource: [{ source: 'nginx', bytes: 6.1e9, pct: 61 }, { source: 'app', bytes: 2.4e9, pct: 24 }, { source: 'syslog', bytes: 1.0e9, pct: 10 }, { source: 'auth', bytes: 5e8, pct: 5 }], hotBytes: 3.2e9, archiveBytes: 18e9, projectedMonthlyCustomerAwsUsd: 6.4, quotaGbPerDay: 10 };
}

// ---- setup ----
const CANDIDATES: XpInstanceCandidate[] = HOSTS.map((h) => ({ instanceId: h.id, name: h.name, region: 'us-east-1', platform: 'LINUX', state: 'running', tags: { env: 'demo' }, ssmManaged: true, inScope: true, collectionLevel: 'T1', agent: { version: '1.0.0', lastSeenAt: iso(nowMs() - 20_000), status: 'ACTIVE' } }));

function setupOverview(): XpSetupOverview {
  return {
    state: 'CONNECTED', demo: true, homeRegion: 'us-east-1', bucket: { name: 'demo-xampulse-archive', region: 'us-east-1', mode: 'CREATE' },
    regions: [{ region: 'us-east-1', candidateInstances: 6, stackState: 'OK', targetMode: 'ALL_MANAGED', covered: 6, reporting: 6 }],
    instances: { total: 6, ssmManaged: 6, inScope: 6, enrolled: 6, reporting: 6, notCovered: 0 }, coveragePct: 100, lastDataAt: iso(nowMs() - 20_000),
  };
}

const BUCKETS: XpBucketCandidate[] = [
  { name: 'demo-xampulse-archive', region: 'us-east-1', encryption: 'SSE_KMS', versioning: true, publicAccessBlocked: true, lifecycleHasXamPulseRule: true, recommended: true, issues: [] },
  { name: 'demo-app-assets', region: 'us-east-1', encryption: 'SSE_S3', versioning: false, publicAccessBlocked: true, lifecycleHasXamPulseRule: false, recommended: false, issues: [{ code: 'NO_VERSIONING', level: 'WARN', message: 'Versioning is off. Archived logs could be overwritten.' }] },
  { name: 'xamops-cur-111122223333', region: 'us-east-1', encryption: 'SSE_S3', versioning: true, publicAccessBlocked: true, lifecycleHasXamPulseRule: false, recommended: false, issues: [{ code: 'CUR_BUCKET', level: 'WARN', message: 'This bucket holds cost reports. Use a separate bucket for logs.' }] },
  { name: 'demo-public-site', region: 'us-west-2', encryption: 'NONE', versioning: false, publicAccessBlocked: false, lifecycleHasXamPulseRule: false, recommended: false, issues: [{ code: 'PUBLIC', level: 'BLOCK', message: 'Public access is not blocked. XamPulse will not write logs here.' }] },
];

const TRUST: XpTrustPanel = {
  collected: ['System metrics: CPU, memory, disk, network, processes', 'Log lines from the paths you choose', 'Docker container metrics and logs if enabled'],
  neverCollected: ['File contents outside chosen log paths', 'Environment variables and secrets', 'Database contents', 'Your AWS credentials'],
  redaction: [{ rule: 'Bearer tokens', example: 'Authorization: Bearer ****' }, { rule: 'AWS access keys', example: 'AKIA************' }, { rule: 'Card numbers', example: '**** **** **** 4242' }],
  where: { hotRegion: 'us-east-1', hotDays: 14, archiveBucket: 'demo-xampulse-archive', archiveRegion: 'us-east-1', kms: 'AWS managed key' },
  access: ['Only members of your organisation can read this data', 'XamOps staff cannot read your logs'], controls: ['Disconnect at any time', 'Optional purge of hot data on disconnect'],
  permissionsDiff: { archiveRole: ['s3:PutObject on your archive bucket prefix', 's3:ListBucket on your archive bucket'], connectionRoleNote: 'The connection role gains read-only discovery of S3 buckets and SSM-managed instances.', connectionRoleGrants: ['s3:ListAllMyBuckets', 'ssm:DescribeInstanceInformation'], noRunCommand: 'XamPulse never runs commands on your instances after install.' },
  templateSha256: 'demo-0000000000000000000000000000000000000000000000000000000000000000',
};

const CHANNELS: XpChannel[] = [{ id: 1, accountId: ACCOUNT_ID, kind: 'SLACK', name: 'DEMO #ops-alerts', configured: true, summary: 'hooks.slack.com/\u2026/\u2022\u2022\u2022\u2022' }];
const SOURCES: XpLogSource[] = [
  { id: 1, kind: 'SYSTEM', paths: ['/var/log/syslog', '/var/log/auth.log'], enabled: true },
  { id: 2, kind: 'CUSTOM', paths: ['/var/log/nginx/*.log'], parser: 'nginx', enabled: true },
];

const SILENCES: XpSilence[] = [];

const delay = (ms: number): Promise<void> => new Promise((res) => setTimeout(res, ms));
const notFound = (): XpApiError => new XpApiError('XP_NOT_FOUND', 'Not found in demo data.', 404, 'demo');

/** Routes an XpApi call to generated demo data. */
export async function demoRequest<T>(method: string, path: string, query?: Record<string, unknown>, body?: unknown): Promise<T> {
  await delay(140);
  const key = `${method} ${path}`;
  const q = query ?? {};
  const b = (body ?? {}) as Record<string, unknown>;
  const host = /^GET \/hosts\/([^/]+)\/(summary|processes)$/.exec(key);
  if (host) {
    const id = decodeURIComponent(host[1]);
    if (!HOSTS.some((h) => h.id === id)) throw notFound();
    return (host[2] === 'summary' ? hostSummary(id, Date.parse(String(q.from)), Date.parse(String(q.to))) : processes(id)) as T;
  }
  const inc = /^POST \/alerts\/incidents\/(\d+)\/(ack|snooze|resolve|comment)$/.exec(key);
  if (inc) {
    const i = incidents()[0];
    const state = inc[2] === 'ack' ? 'ACKED' : inc[2] === 'resolve' ? 'RESOLVED' : inc[2] === 'snooze' ? 'SNOOZED' : i.state;
    return { ...i, state } as T;
  }
  if (/^(GET|PUT|POST|DELETE) \/accounts\/\d+\//.test(key)) {
    const sub = path.replace(/^\/accounts\/\d+/, '');
    if (sub === '/setup') return setupOverview() as T;
    if (sub === '/instances') return { items: CANDIDATES, page: 0, size: 50, total: CANDIDATES.length } as T;
    if (sub === '/buckets') return BUCKETS as T;
    if (sub === '/sources') return SOURCES as T;
    if (sub === '/setup-plan') {
      const bk = (b.bucket ?? {}) as { name?: string; region?: string };
      return { planId: 'demo-plan', preview: { resources: ['S3 bucket (archive)', 'IAM role xampulse-archive', 'SSM association for the agent'], iamActions: ['s3:PutObject', 's3:ListBucket'], neverCollected: TRUST.neverCollected, customerCostEstimate: { s3Usd: 3.1, kmsUsd: 1.0, notes: ['Estimate for 10 GB per day. DEMO figure.'] }, connectionRoleGrants: TRUST.permissionsDiff.connectionRoleGrants, templateSha256: TRUST.templateSha256 ?? '', templateUrl: 'https://example.invalid/demo-template.yml' }, stacks: [{ region: bk.region ?? 'us-east-1', kind: 'GLOBAL', stackName: 'demo-xampulse', quickCreateUrl: 'https://example.invalid/demo-quick-create' }], warnings: ['DEMO: this link does not create anything.'] } as T;
    }
    if (sub === '/verify') {
      const checks: XpChecklistCheck[] = [{ id: 'stack', label: 'CloudFormation stack complete', state: 'OK' }, { id: 'bucket', label: 'Archive bucket writable', state: 'OK' }, { id: 'agents', label: 'Agents enrolled', state: 'OK' }, { id: 'data', label: 'First data received', state: 'OK' }];
      return { checks } as T;
    }
    if (sub === '/stack-status') return [{ region: 'us-east-1', kind: 'GLOBAL', stackName: 'demo-xampulse', status: 'CREATE_COMPLETE', events: [] }] as T;
    if (sub === '/disconnect') return { jobId: 'demo-job', stackDeletionLinks: [] } as T;
    if (sub.startsWith('/demo')) return { demo: true, hosts: 6, message: 'Demo data is always on in demo mode.' } as T;
    if (method === 'PUT') return (Array.isArray(body) ? body : CANDIDATES[0]) as T;
    if (sub.startsWith('/regions')) return { region: 'us-east-1', kind: 'REGIONAL', stackName: 'demo-xampulse-region', quickCreateUrl: 'https://example.invalid/demo-quick-create' } as T;
  }
  switch (key) {
    case 'GET /status': return { enabled: true, demoAvailable: true } as T;
    case 'GET /accounts': return [{ cloudAccountId: ACCOUNT_ID, awsAccountId: '111122223333', name: 'DEMO Production', state: 'CONNECTED', demo: true }] as XpAccountRef[] as T;
    case 'GET /overview': return overview() as T;
    case 'GET /hosts': {
      const term = String(q.q ?? '').toLowerCase();
      const items = HOSTS.map(hostRow).filter((r) => !term || r.name.toLowerCase().includes(term) || r.instanceId.includes(term));
      return { items, page: 0, size: 50, total: items.length } as T;
    }
    case 'GET /containers': {
      const items: XpContainerRow[] = [
        { accountId: ACCOUNT_ID, instanceId: 'i-demo0004', containerId: 'c1a', name: 'api', image: 'demo/api:2.14.0', state: 'running', cpuPct: 31, memBytes: 1.2e9, restarts: 0, lastSeenAt: iso(nowMs() - 15_000) },
        { accountId: ACCOUNT_ID, instanceId: 'i-demo0005', containerId: 'c2b', name: 'api', image: 'demo/api:2.14.0', state: 'running', cpuPct: 48, memBytes: 3.9e9, restarts: 3, lastSeenAt: iso(nowMs() - 15_000) },
        { accountId: ACCOUNT_ID, instanceId: 'i-demo0001', containerId: 'c3c', name: 'nginx', image: 'nginx:1.27', state: 'running', cpuPct: 4, memBytes: 6e7, restarts: 0, lastSeenAt: iso(nowMs() - 15_000) },
      ];
      return { items, page: 0, size: 50, total: items.length } as T;
    }
    case 'POST /logs/search': return logSearch(b as unknown as XpLogSearchRequest) as T;
    case 'POST /logs/histogram': return logHistogram(b as unknown as XpLogHistogramRequest) as T;
    case 'POST /logs/facets': return logFacets(b as unknown as XpLogFacetsRequest) as T;
    case 'POST /logs/patterns': return logPatterns(b as unknown as XpLogPatternsRequest) as T;
    case 'GET /logs/context': {
      const all = pool();
      const i = all.findIndex((r) => r.id === q.id);
      if (i < 0) throw notFound();
      return { before: all.slice(Math.max(0, i - 20), i).reverse(), anchor: all[i], after: all.slice(i + 1, i + 21) } as T;
    }
    case 'POST /logs/tail/tickets': return { ticket: 'demo', url: 'demo:tail', expiresInSec: 60 } as T;
    case 'POST /archive-searches': return { bytes: 4.2e9, requests: 380, usd: 0.07, storageClasses: ['STANDARD_IA', 'GLACIER_IR'], state: 'ESTIMATED' } as T;
    case 'POST /metrics/query': return metricQuery(b as unknown as XpMetricQueryRequest) as T;
    case 'GET /metrics/catalog': {
      const term = String(q.q ?? '').toLowerCase();
      return CATALOG.filter((c) => c.name.includes(term)) as T;
    }
    case 'GET /events': return eventsFor(Date.parse(String(q.from)), Date.parse(String(q.to))) as T;
    case 'GET /usage': return usage() as T;
    case 'GET /insights/feed': return insights() as T;
    case 'GET /insights/forecasts': return forecasts() as T;
    case 'GET /health-score': return { score: 74, lanes: lanesFor(HOSTS[4]), asOf: iso(nowMs()), explanation: ['1 host critical (memory)', '2 hosts warning (CPU steal, disk)'] } as T;
    case 'GET /trust': return TRUST as T;
    case 'GET /views': return [] as T;
    case 'POST /views': return b as T;
    case 'GET /alerts/rules': return rules() as T;
    case 'POST /alerts/rules': return { ...b, id: 99, version: 1 } as T;
    case 'POST /alerts/rules/preview': return { firings: [{ from: iso(nowMs() - 90 * MIN), to: iso(nowMs() - 60 * MIN), group: { instance_id: 'i-demo0005' }, peak: 93 }], evaluatedPoints: 288, wouldNotify: 1 } as T;
    case 'GET /alerts/incidents': return { items: incidents(), page: 0, size: 50, total: 1 } as T;
    case 'GET /alerts/silences': return SILENCES as T;
    case 'POST /alerts/silences': return { ...b, id: 5 } as T;
    case 'GET /alerts/channels': return CHANNELS as T;
    case 'POST /alerts/channels': return CHANNELS[0] as T;
    case 'GET /audit': return { items: [{ ts: iso(nowMs() - 2 * 3_600_000), userId: 1, accountId: ACCOUNT_ID, action: 'DEMO_VIEWED', target: 'DEMO' }], page: 0, size: 50, total: 1 } as T;
    default: break;
  }
  if (method === 'DELETE' || key.endsWith('/test')) return {} as T;
  throw notFound();
}

/** DEMO live-tail generator: a small batch of fresh rows for the simulated socket in useLogTail. */
export function demoTailBatch(seq: number): XpLogRow[] {
  const r = rng(seq + 1000);
  const n = 1 + Math.floor(r() * 4);
  const names = Object.keys(SERVICES);
  return Array.from({ length: n }, (_, k) => {
    const h = HOSTS[Math.floor(r() * HOSTS.length)];
    const svc = names[Math.floor(r() * (names.length - 1))];
    const msgs = SERVICES[svc];
    const body = msgs[Math.floor(r() * msgs.length)];
    const severity: XpSeverity = /timed out|Failed|slow query/.test(body) ? 'WARN' : 'INFO';
    return { id: `demo-live-${seq}-${k}`, ts: iso(nowMs()), accountId: ACCOUNT_ID, region: 'us-east-1', instanceId: h.id, service: svc, source: '/var/log/syslog', severity, body, attrs: { host: h.name, 'xp.demo': 'true' } };
  });
}
