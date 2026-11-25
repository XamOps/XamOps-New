/**
 * DEMO DATA for XamPulse. Generated, deterministic sample data that is NOT from any AWS account.
 * Only reachable when isXpDemo() is true: the demo account's session (demo.xamops@xammer.in), or the local dev
 * server's screenshot-test toggle. Real users never see this data.
 * Never used as a fallback for failed requests. Every account returned here carries demo: true so the UI
 * shows the DEMO banner and pills. Hosts are named demo-* with instance ids i-demo*.
 */
import { XpApiError } from '../api/xampulseErrors';
import type {
  XpAccountRef, XpAlertRule, XpAttentionItem, XpBucketCandidate, XpChange, XpChannel, XpChecklistCheck, XpContainerRow, XpContainerSummary, XpEvent,
  XpForecast, XpHealth, XpHostLimits, XpHostRow, XpHostSummary, XpIncident, XpInsight, XpInstanceCandidate, XpLane, XpLimitFinding, XpLimitSignal, XpLogRow,
  XpLogSource, XpMetricCatalogEntry, XpMetricQueryRequest, XpMetricQueryResponse, XpMetricSeries, XpOverview,
  XpProcessRow, XpSeverity, XpSetupOverview, XpSilence, XpTrustPanel, XpUsage, XpScope, XpLogSearchRequest,
  XpLogFacetsRequest, XpLogHistogramRequest, XpLogPatternsRequest, XpLogPattern,
  XpAlertPreview, XpFiring, XpPeers, XpPeerMetric, XpInvestigation, XpHypothesis, XpExposure, XpCostPerf, XpRunbook,
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
      // The app runs as the "api" container on the api hosts and nginx as a container on web-1 (Container 360 logs).
      ...(svc === 'app' && (h.name === 'demo-api-1' || h.name === 'demo-api-2') ? { containerId: '8c41d07e9a13bb22' }
        : svc === 'nginx' && h.name === 'demo-web-1' ? { containerId: 'c3c7a1e0d2f4cc33' } : {}),
    });
  }
  rows.sort((a, b) => Date.parse(b.ts) - Date.parse(a.ts));
  logPool = rows;
  return rows;
}

const SEV_RANK: XpSeverity[] = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL'];

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
      const have = f === 'service' ? row.service : f === 'severity' || f === 'level' ? row.severity : f === 'instance' || f === 'instance_id' || f === 'host' ? row.instanceId : f === 'source' ? row.source : f === 'region' ? row.region : f === 'container' ? row.containerId ?? '' : '';
      const floor = (f === 'severity' || f === 'level') && m[2].startsWith('>=') ? SEV_RANK.indexOf(vals[0].toUpperCase() as XpSeverity) : -1;
      hit = floor >= 0 ? SEV_RANK.indexOf(row.severity) >= floor
        : vals.some((v) => have.toLowerCase() === v || (v.endsWith('*') && have.toLowerCase().startsWith(v.slice(0, -1))));
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
      firstSeen: i === 2 ? iso(nowMs() - 25 * MIN) : g.sample.ts, sampleId: g.sample.id, sampleAccountId: g.sample.accountId, severity: g.sample.severity,
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

/** Generator values (0..100) as megabytes per second, expressed in bytes per second. */
const mb = (pts: [number, number][]): [number, number][] => pts.map(([t, v]) => [t, v * 1024 * 1024]);

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
    // Disk and network are bytes per second, like the real API (the generator works in 0..100, so scale to MB/s).
    'system.disk.io{direction=read}': mb(series(270 + i, 20, 15, from, to, step)),
    'system.disk.io{direction=write}': mb(series(280 + i, 35, 20, from, to, step)),
    'system.network.io{direction=receive}': mb(series(290 + i, 40, 25, from, to, step)),
    'system.network.io{direction=transmit}': mb(series(300 + i, 28, 18, from, to, step)),
  };
  for (let c = 0; c < 4; c += 1) s[`system.cpu.utilization{cpu=${c}}`] = series(400 + i * 10 + c, h.cpu + (c - 1.5) * 6, 12, from, to, step);
  // Pressure (percent of time stalled) and AWS allowance packets per second, from the agent's xampulse_ec2 receiver.
  s['xp.pressure{resource=cpu,level=some}'] = series(500 + i, h.name === 'demo-web-3' ? 18 : 3, 2, from, to, step);
  s['xp.pressure{resource=memory,level=some}'] = series(510 + i, h.name === 'demo-api-2' ? 22 : 0.5, h.name === 'demo-api-2' ? 6 : 0.4, from, to, step);
  s['xp.pressure{resource=memory,level=full}'] = series(520 + i, h.name === 'demo-api-2' ? 12 : 0.1, h.name === 'demo-api-2' ? 4 : 0.1, from, to, step);
  s['xp.pressure{resource=io,level=some}'] = series(530 + i, h.name === 'demo-db-1' ? 21 : 1.5, 2, from, to, step);
  s['xp.pressure{resource=io,level=full}'] = series(540 + i, h.name === 'demo-db-1' ? 14 : 0.6, 1.5, from, to, step);
  if (h.name === 'demo-web-1') s['xp.ena.allowance_exceeded{allowance=pps}'] = series(550, 1.2, 1.1, from, to, step);
  return {
    host: hostRow(h, i), lanes: lanesFor(h), series: s,
    recentErrors: pool().filter((r) => r.instanceId === id && (r.severity === 'ERROR' || r.severity === 'WARN')).slice(0, 6),
    events: eventsFor(from, to).filter((e) => e.resource === id || e.resource === ''),
    openIncidents: h.health === 'CRIT' ? [incidents()[0]] : [],
  };
}

/** Container 360 for the demo containers (api on api-1/api-2, nginx on web-1). */
function containerSummary(instanceId: string, name: string, from: number, to: number): XpContainerSummary {
  const i = hostIndex(instanceId);
  const h = HOSTS[i];
  const known = (h.name === 'demo-api-1' || h.name === 'demo-api-2') ? name === 'api' : h.name === 'demo-web-1' && name === 'nginx';
  if (!known) throw notFound();
  const step = Math.max(MIN, Math.floor((to - from) / 120));
  const api2 = h.name === 'demo-api-2';
  const limit = name === 'api' ? 2 * 1024 ** 3 : 512 * 1024 ** 2;
  const scale = (pts: [number, number][], f: number): [number, number][] => pts.map(([t, v]) => [t, v * f]);
  const memory: [number, number][] = series(700 + i, api2 ? 70 : 45, api2 ? 20 : 4, from, to, step, api2 ? 25 : 0).map(([t, v]) => [t, Math.min(1, v / 100) * limit]);
  const changes = changesFor(instanceId, from, to).filter((c) => c.subject === name);
  const deploy = changes.find((c) => c.kind === 'DEPLOY');
  const versions = deploy
    ? [{ containerId: '3f9a1c22b7e0aa11', image: 'demo/api:2.13.4', firstSeen: iso(from - 3 * 3_600_000), lastSeen: deploy.ts },
       { containerId: '8c41d07e9a13bb22', image: name === 'api' ? 'demo/api:2.14.0' : 'nginx:1.27', firstSeen: deploy.ts, lastSeen: iso(to) }]
    : [{ containerId: name === 'api' ? '8c41d07e9a13bb22' : 'c3c7a1e0d2f4cc33', image: name === 'api' ? 'demo/api:2.14.0' : 'nginx:1.27', firstSeen: iso(from - 3 * 3_600_000), lastSeen: iso(to) }];
  const findings: XpLimitFinding[] = api2 ? [
    { severity: 'CRITICAL', code: 'container_oom', source: 'CONTAINER', title: 'Restarted right after memory reached its limit (likely an OOM kill)',
      detail: "Memory was at 97% of the container's limit just before Docker restarted it.", fix: 'Raise the memory limit or find what grows (a leak shows as memory climbing between restarts).',
      evidence: { peak_share: 0.97, restarts: 1 } },
    { severity: 'WARNING', code: 'container_throttled', source: 'CONTAINER', title: 'CPU-throttled in 41% of its scheduling periods',
      detail: 'The container hit its CPU limit; requests were delayed even though the host may have idle CPU.', fix: "Raise the container's CPU limit (--cpus / cpu quota), or reduce its CPU use.",
      evidence: { throttled_ratio: 0.41, periods: 36000, throttled_periods: 14760 } },
  ] : [];
  return {
    instanceId, name, containerId: versions[versions.length - 1].containerId, image: versions[versions.length - 1].image,
    firstSeen: versions[0].firstSeen, lastSeen: iso(to - 15_000), memLimitBytes: limit, restarts: api2 ? 1 : 0, versions,
    series: {
      cpu: scale(series(710 + i, name === 'api' ? 60 : 12, 15, from, to, step), name === 'api' ? 1.8 : 1),
      memory, memory_limit: [[from, limit], [to, limit]], host_memory: [[to, 16 * 1024 ** 3]],
      uptime: [[to, api2 ? 47 * 60 : deploy ? (to - Date.parse(deploy.ts)) / 1000 : 6 * 86_400 + 4 * 3600]],
      throttled_pct: series(720 + i, api2 ? 41 : 2, api2 ? 12 : 1.5, from, to, step),
      network_rx: mb(scale(series(730 + i, 20, 10, from, to, step), 0.4)), network_tx: mb(scale(series(740 + i, 30, 12, from, to, step), 0.3)),
      disk_read: mb(scale(series(750 + i, 5, 4, from, to, step), 0.1)), disk_write: mb(scale(series(760 + i, 8, 5, from, to, step), 0.1)),
    },
    findings, changes,
    recentErrors: pool().filter((r) => r.instanceId === instanceId && (r.severity === 'ERROR' || r.severity === 'WARN')).slice(0, 6),
  };
}

/** Change Intelligence per demo host, placed inside the requested range and telling the demo's stories. */
function changesFor(id: string, from: number, to: number): XpChange[] {
  const h = HOSTS[hostIndex(id)];
  const at = (frac: number) => iso(from + (to - from) * frac);
  const ago = (min: number) => iso(Math.max(from + MIN, to - min * MIN));
  const out: XpChange[] = [];
  if (h.name === 'demo-api-1') {
    out.push({ ts: ago(27), instanceId: id, kind: 'DEPLOY', title: 'Container api deployed demo/api:2.14.0', detail: 'Was demo/api:2.13.4 (container 3f9a1c22b7e0 replaced by 8c41d07e9a13).',
      source: 'AGENT', subject: 'api', verdict: 'FOLLOWED_BY_CHANGE', overlapping: false,
      effects: [{ signal: 'errors', label: 'Errors per minute', unit: '/min', before: 0.3, after: 4, relative: 3.7, strength: 'STRONG' }] });
    out.push({ ts: ago(2), instanceId: id, kind: 'CONTAINER_START', title: 'Container worker started', detail: 'Image demo/worker:1.8.2, container 51be7c0d9f2a.',
      source: 'AGENT', subject: 'worker', verdict: 'TOO_RECENT', overlapping: false, effects: [] });
  }
  if (h.name === 'demo-api-2') {
    out.push({ ts: ago(20), instanceId: id, kind: 'RESTART', title: 'Container api restarted', detail: 'Docker restarted the container (restart count +1).',
      source: 'AGENT', subject: 'api', verdict: 'FOLLOWED_BY_CHANGE', overlapping: false,
      effects: [{ signal: 'container_memory', label: 'Memory of api', unit: 'bytes', before: 2.04e9, after: 7.5e8, relative: -0.63, strength: 'STRONG' }] });
    out.push({ ts: ago(50), instanceId: id, kind: 'DEPLOY', title: 'Container api deployed demo/api:2.14.0', detail: 'Was demo/api:2.13.4 (container a7d0e5f1c3b9 replaced by 0b6e2c9d4f71).',
      source: 'AGENT', subject: 'api', verdict: 'FOLLOWED_BY_CHANGE', overlapping: false,
      effects: [{ signal: 'memory', label: 'Memory', unit: '%', before: 64, after: 91, relative: 0.42, strength: 'STRONG' }] });
  }
  if (h.name === 'demo-web-1') {
    out.push({ ts: at(0.3), instanceId: id, kind: 'PACKAGE', title: '2 package changes', detail: 'openssl 3.0.2-0ubuntu1.10 → 3.0.2-0ubuntu1.12; libssl3 3.0.2-0ubuntu1.10 → 3.0.2-0ubuntu1.12',
      source: 'LOGS', verdict: 'NO_CLEAR_EFFECT', overlapping: false, effects: [] });
  }
  if (h.name === 'demo-web-2') {
    out.push({ ts: at(0.2), instanceId: id, kind: 'REBOOT', title: 'Host restarted', detail: "The kernel's CPU counters started again from zero, after 12 minutes without data.",
      source: 'AGENT', verdict: 'NO_CLEAR_EFFECT', overlapping: false, effects: [] });
  }
  if (h.name === 'demo-db-1') {
    out.push({ ts: at(0.6), instanceId: id, kind: 'CONFIG', title: 'postgresql.conf reloaded (work_mem 4MB → 64MB)', detail: 'Recorded by the platform.',
      source: 'PLATFORM', verdict: 'FOLLOWED_BY_CHANGE', overlapping: false,
      effects: [{ signal: 'memory', label: 'Memory', unit: '%', before: 61, after: 72, relative: 0.18, strength: 'MODERATE' }] });
  }
  return out.filter((c) => Date.parse(c.ts) >= from && Date.parse(c.ts) <= to).sort((a, b) => Date.parse(b.ts) - Date.parse(a.ts));
}

/** Throttle Radar per demo host, telling the same stories as the demo charts and events. */
function limits(id: string, from: number, to: number): XpHostLimits {
  const h = HOSTS[hostIndex(id)];
  const secs = Math.max(60, (to - from) / 1000);
  const findings: XpLimitFinding[] = [];
  const limited = new Set<string>();
  if (h.name === 'demo-web-1') {
    limited.add('ena');
    findings.push({ severity: 'WARNING', code: 'ena_pps', source: 'AWS', title: 'AWS is capping the network: packets-per-second allowance exceeded 4,210 times',
      detail: `The ENA driver counted 4,210 packets queued or dropped because ${h.type} went over its packets-per-second allowance. The OS and its CPU look fine; the limit is the instance's.`,
      fix: 'Use a larger size or a network-optimised type, or send fewer, larger packets.', evidence: { packets: 4210, per_second: 4210 / secs } });
  }
  if (h.name === 'demo-web-3') {
    limited.add('steal');
    findings.push({ severity: 'WARNING', code: 'cpu_steal', source: 'AWS', title: 'CPU steal averaged 14%',
      detail: 'This is a burstable instance in standard mode: steal this high usually means its CPU credits ran out and AWS is holding it at its baseline.',
      fix: 'Refresh CloudWatch to confirm the CPU credit balance; switch to unlimited mode or a non-burstable type for sustained load.', evidence: { steal_avg: 0.14 } });
  }
  if (h.name === 'demo-api-2') {
    limited.add('psi');
    limited.add('container_cpu');
    findings.push({ severity: 'CRITICAL', code: 'psi_memory_full', source: 'KERNEL', title: 'Every task was stalled on memory (reclaim or swap) 12% of the time',
      detail: 'Linux pressure stall information (memory, full): measured, not estimated from utilisation.',
      fix: 'The host is short of memory: reduce what runs on it or move to a size with more memory.', evidence: { stalled_share: 0.12, stalled_seconds: 0.12 * secs, window_seconds: secs } });
    findings.push({ severity: 'WARNING', code: 'container_throttled', source: 'CONTAINER', title: 'Container api was CPU-throttled in 41% of its scheduling periods',
      detail: 'The container hit its CPU limit (--cpus / cpu quota); requests were delayed even though the host may have idle CPU.',
      fix: "Raise the container's CPU limit, or reduce its CPU use; the host's own CPU chart will not show this.", evidence: { throttled_ratio: 0.41, periods: Math.round(secs * 10), throttled_periods: Math.round(secs * 4.1) } });
  }
  if (h.name === 'demo-db-1') {
    limited.add('psi');
    findings.push({ severity: 'WARNING', code: 'psi_io_full', source: 'KERNEL', title: 'Every task was stalled on disk I/O 14% of the time',
      detail: 'Linux pressure stall information (io, full): measured, not estimated from utilisation.',
      fix: 'Storage is the bottleneck: check the EBS volume and instance limits below (Refresh CloudWatch for the exceeded checks).', evidence: { stalled_share: 0.14, stalled_seconds: 0.14 * secs, window_seconds: secs } });
  }
  const sig = (code: string, label: string): XpLimitSignal => ({ code, label, state: limited.has(code) ? 'LIMITED' : 'OK' });
  const signals: XpLimitSignal[] = [
    sig('ena', 'AWS network allowances'), sig('psi', 'CPU, memory and I/O pressure'), sig('steal', 'CPU steal'),
    h.name.startsWith('demo-db') ? { code: 'container_cpu', label: 'Container CPU limits', state: 'NOT_MEASURED', note: 'No container throttling data: no containers with a CPU limit, Docker not enabled in Setup, or the agent predates this check.' } : sig('container_cpu', 'Container CPU limits'),
    sig('conntrack_table', 'Connection-tracking table'), sig('spot', 'Spot interruption notices'),
    { code: 'ebs', label: 'EBS volume and instance limits', state: 'NOT_MEASURED', note: 'Not fetched for this range: press Refresh in the AWS section to read the EBS exceeded checks (Nitro instances).' },
  ];
  findings.sort((a, b) => (a.severity === 'CRITICAL' ? 0 : 1) - (b.severity === 'CRITICAL' ? 0 : 1));
  return { instanceId: id, from: iso(from), to: iso(to), findings, signals };
}

function processes(id: string): XpProcessRow[] {
  const r = rng(hostIndex(id) + 55);
  const names = ['java', 'nginx', 'postgres', 'node', 'python3', 'containerd', 'amazon-ssm-agent', 'xampulse-agent', 'sshd', 'systemd'];
  return names.map((name, i) => ({ pid: 1000 + i * 137, name, user: i % 3 === 0 ? 'root' : 'app', cpuPct: Number((r() * (30 - i * 2)).toFixed(1)), memPct: Number((r() * (22 - i)).toFixed(1)), memBytes: Math.round(r() * 2e9), threads: 4 + Math.floor(r() * 60) }))
    .sort((a, b) => b.cpuPct - a.cpuPct);
}

/** Demo runbooks: the server's catalogue, with canned output for the demo hosts. DEMO output. */
const DEMO_RUNBOOKS: XpRunbook[] = [
  { id: 'top', title: 'Top processes', description: 'Processes by CPU, with memory, user and run time.', script: 'ps -eo pid,user,%cpu,%mem,rss,etime,comm --sort=-%cpu | head -n 25' },
  { id: 'disk', title: 'Disk usage', description: 'Filesystems, then the largest directories under /var.', script: 'df -hT -x tmpfs -x devtmpfs -x squashfs -x overlay; echo; du -xh --max-depth=1 /var 2>/dev/null | sort -rh | head -n 15' },
  { id: 'sockets', title: 'Listening sockets', description: 'What listens on which port, with the owning process.', script: 'ss -tulpn 2>/dev/null || netstat -tulpn' },
  { id: 'kernel', title: 'Kernel warnings', description: 'Recent kernel errors and warnings (OOM kills, disk and network errors).', script: 'journalctl -k -p warning -n 60 --no-pager 2>/dev/null || dmesg -T --level=err,warn | tail -n 60' },
  { id: 'failed', title: 'Failed services', description: 'Failed systemd units and the latest error-level journal lines.', script: 'systemctl --failed --no-pager; echo; journalctl -p err -n 30 --no-pager' },
  { id: 'oom', title: 'Out-of-memory kills', description: 'OOM kills in the last 24 hours.', script: "journalctl -k --since '-24h' --no-pager 2>/dev/null | grep -iE 'out of memory|oom-kill|killed process' | tail -n 30; true" },
  { id: 'containers', title: 'Containers', description: 'Docker containers with image and status.', script: "docker ps -a --format 'table {{.Names}}\\t{{.Image}}\\t{{.Status}}' 2>/dev/null || echo 'Docker is not installed or not running.'" },
];
const DEMO_COMMANDS = new Map<string, { runbook: string; id: string; polls: number }>();
function runbookOutput(runbook: string, id: string): string {
  const h = HOSTS[hostIndex(id)];
  switch (runbook) {
    case 'top': return ['    PID USER     %CPU %MEM   RSS     ELAPSED COMMAND', ...processes(id).slice(0, 8).map((p) => `${String(p.pid).padStart(7)} ${(p.user ?? 'app').padEnd(8)} ${p.cpuPct.toFixed(1).padStart(4)} ${p.memPct.toFixed(1).padStart(4)} ${String(Math.round((p.memBytes ?? 0) / 1024)).padStart(7)}  3-04:11:09 ${p.name}`)].join('\n');
    case 'disk': return `Filesystem     Type  Size  Used Avail Use% Mounted on\n/dev/nvme0n1p1 xfs    50G   ${Math.round(h.disk / 2)}G   ${50 - Math.round(h.disk / 2)}G  ${h.disk}% /\n\n12G\t/var\n7.9G\t/var/lib\n3.1G\t/var/log\n420M\t/var/cache`;
    case 'oom': return h.health === 'CRIT' ? 'Nov 13 09:41:12 demo kernel: Out of memory: Killed process 4121 (java) total-vm:9120432kB, anon-rss:3801224kB\nNov 13 09:58:40 demo kernel: oom-kill:constraint=CONSTRAINT_NONE,task=java,pid=5530' : '';
    case 'failed': return '  UNIT LOAD ACTIVE SUB DESCRIPTION\n0 loaded units listed.';
    default: return 'DEMO output.';
  }
}

/** Demo exposure: web hosts serve 80/443 with SSH open to the world; demo-api-2 has Redis open to the internet. */
function exposureFor(id: string): XpExposure {
  const h = HOSTS[hostIndex(id)];
  const web = h.name.includes('web');
  const sg = web ? 'demo-web (sg-0a1b2c3d)' : h.name.includes('db') ? 'demo-db (sg-0d4e5f6a)' : 'demo-api (sg-0b7c8d9e)';
  const sockets: XpExposure['sockets'] = [
    { protocol: 'tcp', port: 22, bind: 'all', address: '0.0.0.0', service: 'SSH', exposure: web ? 'INTERNET' : 'PRIVATE', via: `${sg} allows ${web ? '0.0.0.0/0' : '10.0.0.0/16'} on port 22` },
    { protocol: 'tcp', port: 9100, bind: 'all', address: '0.0.0.0', service: 'node exporter', exposure: 'BLOCKED' },
    { protocol: 'tcp', port: 13133, bind: 'loopback', address: '127.0.0.1', service: 'collector health', exposure: 'LOCAL' },
  ];
  const findings: XpLimitFinding[] = [];
  if (web) {
    sockets.push({ protocol: 'tcp', port: 80, bind: 'all', address: '0.0.0.0', service: 'HTTP', exposure: 'INTERNET', via: `${sg} allows 0.0.0.0/0 on port 80` },
      { protocol: 'tcp', port: 443, bind: 'all', address: '0.0.0.0', service: 'HTTPS', exposure: 'INTERNET', via: `${sg} allows 0.0.0.0/0 on port 443` });
    findings.push({ severity: 'WARNING', code: 'exposed_ssh', source: 'AWS', title: 'SSH (22)/tcp is reachable from the internet', detail: `${sg} allows 0.0.0.0/0 on port 22.`, fix: 'Use SSM Session Manager instead of SSH, or limit the rule to your own addresses.', evidence: { port: 22 } });
  } else if (h.name.includes('db')) {
    sockets.push({ protocol: 'tcp', port: 5432, bind: 'all', address: '0.0.0.0', service: 'PostgreSQL', exposure: 'PRIVATE', via: `${sg} allows sg-0b7c8d9e on port 5432` });
  } else {
    sockets.push({ protocol: 'tcp', port: 8080, bind: 'all', address: '0.0.0.0', service: 'HTTP (alt)', exposure: 'PRIVATE', via: `${sg} allows sg-0a1b2c3d on port 8080` });
    if (id === 'i-demo0005') {
      sockets.push({ protocol: 'tcp', port: 6379, bind: 'all', address: '0.0.0.0', service: 'Redis', exposure: 'INTERNET', via: `${sg} allows 0.0.0.0/0 on ports 6000-7000` });
      findings.push({ severity: 'CRITICAL', code: 'exposed_sensitive', source: 'AWS', title: 'Redis (6379)/tcp is reachable from the internet', detail: `${sg} allows 0.0.0.0/0 on ports 6000-7000, and it listens on all interfaces with public IP 203.0.113.25.`, fix: 'Remove the 0.0.0.0/0 (and ::/0) rule, or limit it to the hosts that need it; bind the service to a private address.', evidence: { port: 6379 } });
    }
  }
  return { instanceId: id, publicIp: web || id === 'i-demo0005' ? '203.0.113.25' : undefined, measured: true, securityGroups: [sg], sockets, findings,
    note: 'DEMO data. Network ACLs, load balancers, the host’s own firewall and the instance’s IPv6 addresses are not included.' };
}

/** Demo cost: AWS list prices for the demo types (Linux, us-east-1), use from the demo series. DEMO figures. */
function costFor(id: string, from: number, to: number): XpCostPerf {
  const h = HOSTS[hostIndex(id)];
  const spec: Record<string, [number, number, number, boolean]> = { 't3.large': [0.0832, 2, 8, true], 'm5.xlarge': [0.192, 4, 16, false], 'r5.xlarge': [0.252, 4, 32, false] };
  const [hourly, vcpus, mem, burstable] = spec[h.type] ?? [0.1, 2, 8, false];
  const cpu = series(hostIndex(id) + 1, h.cpu, 8, from, to, 5 * MIN).map((p) => p[1]).sort((a, b) => a - b);
  const memv = series(hostIndex(id) + 2, h.mem, 8, from, to, 5 * MIN).map((p) => p[1]).sort((a, b) => a - b);
  const p95 = (v: number[]) => v[Math.max(0, Math.ceil(0.95 * v.length) - 1)];
  const avg = (v: number[]) => v.reduce((a, b) => a + b, 0) / Math.max(1, v.length);
  const r1 = (v: number) => Math.round(v * 10) / 10;
  const cpuP95 = r1(p95(cpu));
  const memP95 = r1(p95(memv));
  const verdict: XpCostPerf['verdict'] = cpuP95 >= 90 || memP95 >= 90 ? 'UNDERSIZED' : cpuP95 < 40 && memP95 < 50 ? (burstable ? 'BURSTABLE' : 'OVERSIZED') : 'FITS';
  const text = verdict === 'UNDERSIZED' ? `Busy: 95% of the time CPU was at or below ${cpuP95.toFixed(0)}% and memory at or below ${memP95.toFixed(0)}%; it runs near its limits.`
    : verdict === 'FITS' ? `Sized for its load: CPU p95 ${cpuP95.toFixed(0)}%, memory p95 ${memP95.toFixed(0)}%.`
      : `Mostly idle: CPU p95 ${cpuP95.toFixed(0)}%, memory p95 ${memP95.toFixed(0)}%.`;
  const used = r1(vcpus * r1(avg(cpu)) / 100);
  return {
    instanceId: id, instanceType: h.type, vcpus, memoryGiB: mem, lifecycle: 'on-demand', platform: 'Linux/UNIX', hourlyUsd: hourly, monthlyUsd: Math.round(hourly * 730 * 100) / 100,
    priceBasis: 'ON_DEMAND_LIST', cpuAvgPct: r1(avg(cpu)), cpuP95Pct: cpuP95, memAvgPct: r1(avg(memv)), memP95Pct: memP95, usedVcpusAvg: used,
    costPerUsedVcpuHour: used >= 0.01 ? Math.round((hourly / used) * 10000) / 10000 : undefined, verdict, verdictText: text,
    notes: ['DEMO figures.', 'AWS on-demand list price; Savings Plans, Reserved Instances, EBS and data transfer are not included.', 'p95 is of 5-minute averages; short spikes inside a step are not in it.'],
  };
}

/** Demo peers: hosts of the same type and name pattern (demo-web-*, demo-api-*), compared like the server does. */
function peersFor(id: string, from: number, to: number): XpPeers {
  const me = HOSTS[hostIndex(id)];
  const pattern = me.name.replace(/[-_.]?\d+$/, '');
  const twins = HOSTS.filter((h) => h.id !== id && h.type === me.type && h.name.replace(/[-_.]?\d+$/, '') === pattern);
  if (twins.length === 0) return { instanceId: id, basis: 'NONE', peers: [], metrics: [], note: 'No other host shares its Auto Scaling group, or its instance type and name pattern.' };
  const avg = (h: DemoHost, k: 'cpu' | 'mem') => {
    const pts = series(hostIndex(h.id) + (k === 'cpu' ? 1 : 2), h[k], 8, from, to, 5 * MIN);
    return pts.reduce((a, p) => a + p[1], 0) / Math.max(1, pts.length);
  };
  const metric = (key: string, label: string, unit: string, pick: (h: DemoHost) => number, floor: number): XpPeerMetric => {
    const value = pick(me);
    const pv = twins.map(pick).sort((a, b) => a - b);
    const med = pv.length % 2 ? pv[(pv.length - 1) / 2] : (pv[pv.length / 2 - 1] + pv[pv.length / 2]) / 2;
    const spread = pv.length === 2 ? Math.abs(pv[1] - pv[0]) : 1.4826 * [...pv.map((x) => Math.abs(x - med))].sort((a, b) => a - b)[Math.floor(pv.length / 2)];
    const d = value - med;
    const z = d / Math.max(spread, floor / 3);
    const verdict = pv.length < 2 ? 'TOO_FEW' : Math.abs(z) >= 3.5 && Math.abs(d) >= floor ? (d > 0 ? 'HIGHER' : 'LOWER') : 'IN_LINE';
    return { key, label, unit, value, peerMedian: med, peerMin: pv[0], peerMax: pv[pv.length - 1], z: Math.round(z * 10) / 10, verdict, indicative: pv.length === 2,
      peerValues: Object.fromEntries(twins.map((h) => [h.id, pick(h)])) };
  };
  return {
    instanceId: id, basis: 'TYPE_NAME', basisLabel: `${me.type} named ${pattern}-*`, peers: twins.map((h) => ({ instanceId: h.id, name: h.name })),
    metrics: [
      metric('cpu', 'CPU busy', '%', (h) => avg(h, 'cpu'), 5),
      metric('memory', 'Memory used', '%', (h) => avg(h, 'mem'), 5),
      metric('steal', 'CPU steal', '%', (h) => (h.reason?.includes('steal') ? 14 : 0.4), 2),
    ],
  };
}

/** Demo Investigate, built from the demo's own radar, changes and series with the server's rules. DEMO figures. */
function investigationFor(id: string, from: number, to: number): XpInvestigation {
  const lim = limits(id, from, to);
  const chg = changesFor(id, from, to);
  const sum = hostSummary(id, from, to);
  const peers = peersFor(id, from, to);
  const hs: XpHypothesis[] = [];
  const radar = (code: string, title: string, signals: string[], mine: (f: XpLimitFinding) => boolean, section: string, crit: number, warn: number) => {
    const found = lim.findings.filter(mine);
    if (found.length) {
      hs.push({ code, title, status: 'SUPPORTED', confidence: found.some((f) => f.severity === 'CRITICAL') ? (crit >= 75 ? 'HIGH' : 'MEDIUM') : (warn >= 75 ? 'HIGH' : 'MEDIUM'),
        weight: found.some((f) => f.severity === 'CRITICAL') ? crit : warn, evidence: found.map((f) => f.title), section });
      return;
    }
    const sig = lim.signals.filter((s) => signals.includes(s.code));
    const measured = sig.filter((s) => s.state !== 'NOT_MEASURED');
    if (!measured.length) hs.push({ code, title, status: 'NOT_MEASURABLE', weight: 0, evidence: [sig.map((s) => `${s.label}: ${s.note ?? 'not measured'}`).join(' ') || 'Not measured on this host.'], section });
    else hs.push({ code, title, status: 'RULED_OUT', weight: 0, evidence: sig.map((s) => (s.state === 'NOT_MEASURED' ? `${s.label}: not measured${s.note ? ` (${s.note})` : ''}` : `${s.label}: no limit hit`)), section });
  };
  radar('aws_limit', 'AWS capped the instance', ['ena', 'ebs', 'spot'], (f) => f.source === 'AWS', 'aws', 100, 80);
  radar('kernel_pressure', 'Tasks stalled waiting for CPU, memory or I/O', ['psi', 'conntrack_table'], (f) => f.code.startsWith('psi_') || f.code === 'conntrack_table', 'cpu', 85, 65);
  const peak = (k: string) => Math.max(...(sum.series[k] ?? [[0, 0]]).map((p) => p[1]));
  const sat: string[] = [];
  if (peak('system.cpu.utilization') >= 95) sat.push(`CPU reached ${peak('system.cpu.utilization').toFixed(0)}%`);
  if (peak('system.memory.utilization') >= 95) sat.push(`Memory used reached ${peak('system.memory.utilization').toFixed(0)}%`);
  const FS_PREFIX = 'system.filesystem.utilization{mountpoint=';
  const fs = Object.keys(sum.series).filter((k) => k.startsWith(FS_PREFIX)).map((k) => [k.slice(FS_PREFIX.length, -1), sum.series[k][sum.series[k].length - 1]?.[1] ?? 0] as const)
    .sort((a, b) => b[1] - a[1])[0];
  if (fs && fs[1] >= 85) sat.push(`${fs[0]} is ${fs[1].toFixed(0)}% full`);
  hs.push(sat.length ? { code: 'saturation', title: 'CPU, memory or disk space ran out', status: 'SUPPORTED', confidence: 'HIGH', weight: 75, evidence: sat, section: fs && fs[1] >= 85 && sat.length === 1 ? 'storage' : 'memory' }
    : { code: 'saturation', title: 'CPU, memory or disk space ran out', status: 'RULED_OUT', weight: 0, evidence: [`CPU peak ${peak('system.cpu.utilization').toFixed(0)}%, memory peak ${peak('system.memory.utilization').toFixed(0)}%`], section: 'cpu' });
  radar('noisy_neighbor', 'The hypervisor took CPU time (steal)', ['steal'], (f) => f.code === 'cpu_steal', 'cpu', 60, 60);
  const followed = chg.filter((c) => c.verdict === 'FOLLOWED_BY_CHANGE' && c.effects.length > 0);
  const real = chg.filter((c) => c.kind !== 'DATA_GAP' && c.kind !== 'AGENT');
  hs.push(followed.length ? { code: 'change', title: 'Something changed just before', status: 'SUPPORTED', confidence: followed.some((c) => c.effects.some((e) => e.strength === 'STRONG')) ? 'HIGH' : 'MEDIUM',
    weight: 78, evidence: followed.slice(0, 3).map((c) => `${c.title}: followed by ${c.effects[0].label} ${c.effects[0].before.toFixed(0)} → ${c.effects[0].after.toFixed(0)}${c.effects[0].unit === '%' ? '%' : ` ${c.effects[0].unit}`}`), section: 'changes' }
    : { code: 'change', title: 'Something changed just before', status: 'RULED_OUT', weight: 0, evidence: [real.length ? `${real.length} changes in this range, none followed by a clear shift in its measurements` : 'No deploys, restarts, reboots, package updates or config changes recorded in this range'], section: 'changes' });
  const failing = HOSTS[hostIndex(id)].health === 'CRIT';
  hs.push(failing ? { code: 'errors', title: 'The application started failing', status: 'SUPPORTED', confidence: 'MEDIUM', weight: 72,
    evidence: ['Errors: 6.4/min, up from 0.3/min in the same length of time before', 'New error: java.lang.OutOfMemoryError: Java heap space (212×)'], section: 'activity', logsQuery: `instance:${id} severity:>=error` }
    : { code: 'errors', title: 'The application started failing', status: 'RULED_OUT', weight: 0, evidence: ['Errors steady: 0.2/min (0.2/min just before)'], section: 'activity' });
  const odd = peers.metrics.filter((m) => m.verdict === 'HIGHER' || m.verdict === 'LOWER');
  const who = `${peers.peers.length} peers (${peers.basisLabel})`;
  hs.push(peers.peers.length === 0 ? { code: 'host_specific', title: 'Only this host is affected', status: 'NOT_MEASURABLE', weight: 0, evidence: [peers.note ?? 'No peers to compare with.'], section: 'peers' }
    : odd.length ? { code: 'host_specific', title: 'Only this host is affected', status: 'SUPPORTED', confidence: 'LOW', weight: 40,
      evidence: odd.map((m) => `${m.label} ${(m.value ?? 0).toFixed(0)}% vs ${(m.peerMedian ?? 0).toFixed(0)}% on its ${who}${m.indicative ? ', indicative' : ''}`), section: 'peers' }
      : { code: 'host_specific', title: 'Only this host is affected', status: 'RULED_OUT', weight: 0, evidence: [`In line with its ${who} on every compared signal: a shared cause (traffic, a dependency, a fleet-wide change) is more likely than this host`], section: 'peers' });
  hs.push({ code: 'data_gap', title: 'The agent stopped reporting', status: 'RULED_OUT', weight: 0, evidence: ['Data arrived throughout the range'], section: 'changes' });
  const rank = (s: string) => (s === 'SUPPORTED' ? 0 : s === 'RULED_OUT' ? 1 : 2);
  hs.sort((a, b) => rank(a.status) - rank(b.status) || b.weight - a.weight);
  const sup = hs.filter((h) => h.status === 'SUPPORTED');
  const lower = (s: string) => (s.length > 1 && s[1] === s[1].toUpperCase() && /[A-Z]/.test(s[1]) ? s : s[0].toLowerCase() + s.slice(1));
  const parts = [sup.length ? `Strongest lead: ${lower(sup[0].title)}. ${sup[0].evidence[0]}.` : 'Nothing stands out in this range.'];
  if (sup.length > 1) parts.push(`Also seen: ${sup.slice(1).map((h) => lower(h.title)).join('; ')}.`);
  const out = hs.filter((h) => h.status === 'RULED_OUT');
  if (out.length) parts.push(`Ruled out: ${out.map((h) => lower(h.title)).join('; ')}.`);
  const unk = hs.filter((h) => h.status === 'NOT_MEASURABLE');
  if (unk.length) parts.push(`Cannot tell here: ${unk.map((h) => lower(h.title)).join('; ')}.`);
  return { instanceId: id, from: iso(from), to: iso(to), summary: parts.join(' ').replace('..', '.'), hypotheses: hs, peers };
}

/**
 * Backtest in demo mode: a replay over generated CPU-like values for three hosts, with the same rules the server
 * applies ("for", recovery, repeat window), so the panel shows realistic counts. DEMO figures.
 */
function backtest(rule: XpAlertRule): XpAlertPreview {
  const end = Math.floor(nowMs() / MIN) * MIN;
  const start = end - 24 * 60 * MIN;
  const op = rule.condition.op;
  const threshold = rule.condition.value ?? 90;
  const holdMin = Math.max(0, parseInt(rule.condition.for, 10) || 0) * (rule.condition.for.endsWith('h') ? 60 : 1);
  const hosts = ['i-demo0005', 'i-demo0003', 'i-demo0004'];
  const firings: XpFiring[] = [];
  let resolved = 0;
  const shown = hosts.map((id, k) => {
    const pts = series(hostIndex(id) * 31 + 7, [88, 70, 46][k], [9, 14, 8][k], start, end, MIN);
    let since: number | null = null;
    let fired: number | null = null;
    let worst = -Infinity;
    pts.forEach(([t, v]) => {
      const breach = op.startsWith('>') ? (op === '>' ? v > threshold : v >= threshold) : (op === '<' ? v < threshold : v <= threshold);
      if (breach) {
        if (since === null) { since = t; worst = v; }
        worst = op.startsWith('>') ? Math.max(worst, v) : Math.min(worst, v);
        if (fired === null && t - since >= holdMin * MIN) fired = t;
      } else {
        if (fired !== null && since !== null) { firings.push({ from: iso(since), firedAt: iso(fired), to: iso(t), group: { group: id }, peak: worst, ongoing: false, notified: true }); resolved++; }
        since = null; fired = null;
      }
    });
    if (fired !== null && since !== null) firings.push({ from: iso(since), firedAt: iso(fired), to: iso(end), group: { group: id }, peak: worst, ongoing: true, notified: true });
    return { group: id, points: pts };
  });
  firings.sort((a, b) => Date.parse(a.firedAt ?? a.from) - Date.parse(b.firedAt ?? b.from));
  return {
    firings, evaluatedPoints: hosts.length * 1440, wouldNotify: firings.length, from: iso(start), to: iso(end), op, threshold, groups: hosts.length,
    resolved, suppressedRepeats: 0, quietReopens: 0, series: shown,
    notes: ['DEMO: generated values, not from any account.', 'Replayed once a minute with live alerting’s queries, “for”, flap guard, missing-data policy and repeat window.'],
  };
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
    archive: { state: 'OK', failures: 0, watermark: iso(nowMs() - 5 * MIN) },
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
type DemoScenario = 'populated' | 'loading' | 'empty' | 'partial' | 'error' | 'setup-required';
function demoScenario(): DemoScenario {
  try {
    const value = window.localStorage.getItem('xp:demo:scenario');
    return value === 'loading' || value === 'empty' || value === 'partial' || value === 'error' || value === 'setup-required' ? value : 'populated';
  } catch {
    return 'populated';
  }
}

/** Routes an XpApi call to generated demo data. */
export async function demoRequest<T>(method: string, path: string, query?: Record<string, unknown>, body?: unknown): Promise<T> {
  const scenario = demoScenario();
  const gateCall = path === '/status' || path === '/accounts';
  await delay(scenario === 'loading' && !gateCall ? 8_000 : 140);
  const key = `${method} ${path}`;
  const q = query ?? {};
  const b = (body ?? {}) as Record<string, unknown>;

  if (scenario === 'error' && !gateCall) {
    throw new XpApiError('XP_STORE_UNAVAILABLE', 'Demo scenario: telemetry store unavailable.', 503, 'demo-error');
  }
  if (key === 'GET /status') return { enabled: true, demoAvailable: true } as T;
  if (key === 'GET /accounts') {
    const state = scenario === 'setup-required' ? 'NOT_SET_UP' : scenario === 'partial' ? 'DEGRADED' : 'CONNECTED';
    return [{ cloudAccountId: ACCOUNT_ID, awsAccountId: '111122223333', name: 'DEMO Production', state, demo: true }] as XpAccountRef[] as T;
  }
  if (scenario === 'setup-required' && key === 'GET /accounts/1/setup') {
    return { state: 'NOT_SET_UP', demo: true, homeRegion: 'us-east-1', regions: [], instances: { total: 6, ssmManaged: 5, inScope: 0, enrolled: 0, reporting: 0, notCovered: 6 }, coveragePct: 0, lastDataAt: null, archive: { state: 'OFF', failures: 0 } } as T;
  }
  if (scenario === 'empty') {
    if (key === 'GET /overview') return { demo: true, state: 'CONNECTED', lastDataAt: null, healthScore: null, kpis: { hostsReporting: 0, hostsTotal: 0, errorsPerMin: 0, ingestBytes24h: 0, openIncidents: 0, coveragePct: 0 }, narrative: [], attention: [], tiles: [] } as T;
    if (key === 'GET /hosts' || key === 'GET /containers' || key === 'GET /alerts/incidents' || key === 'GET /audit') return { items: [], page: Number(q.page ?? 0), size: Number(q.size ?? 50), total: 0 } as T;
    if (key === 'POST /logs/search') return { rows: [], scanned: { rows: 0, bytes: 0 }, tookMs: 1, truncated: false, parsed: { normalised: String(b.query ?? '') } } as T;
    if (key === 'POST /logs/histogram') return { bucketSec: 60, buckets: [] } as T;
    if (key === 'POST /logs/facets') return { approx: false, facets: [] } as T;
    if (key === 'POST /logs/patterns') return { patterns: [] } as T;
    if (key === 'GET /events') return [] as T;
    if (key === 'GET /insights/feed' || key === 'GET /insights/forecasts' || key === 'GET /alerts/rules' || key === 'GET /alerts/silences') return [] as T;
    if (key === 'POST /metrics/query') return { stepSec: 60, series: [], tookMs: 1 } as T;
  }
  if (scenario === 'partial' && key === 'GET /overview') {
    const value = overview();
    return { ...value, state: 'DEGRADED', lastDataAt: iso(nowMs() - 45 * MIN), healthScore: null, kpis: { ...value.kpis, hostsReporting: 3, coveragePct: 50 }, tiles: value.tiles.map((tile, index) => index < 3 ? tile : { ...tile, health: 'NO_DATA', score: null, cpuPct: null, memPct: null }) } as T;
  }
  if (scenario === 'partial' && key === 'GET /accounts/1/setup') {
    const value = setupOverview();
    return { ...value, state: 'DEGRADED', lastDataAt: iso(nowMs() - 45 * MIN), archive: { state: 'FAILING', failures: 3, lastError: 'S3Exception', watermark: iso(nowMs() - 2 * 3_600_000) }, instances: { ...value.instances, reporting: 3, notCovered: 3 }, coveragePct: 50 } as T;
  }

  const container = /^GET \/containers\/([^/]+)\/([^/]+)\/summary$/.exec(key);
  if (container) {
    return containerSummary(decodeURIComponent(container[1]), decodeURIComponent(container[2]), Date.parse(String(q.from)), Date.parse(String(q.to))) as T;
  }
  if (key === 'GET /runbooks') return { enabled: true, runbooks: DEMO_RUNBOOKS } as T;
  const rbRun = /^POST \/hosts\/([^/]+)\/runbooks\/([a-z]+)$/.exec(key);
  if (rbRun) {
    const commandId = `0de0${String(DEMO_COMMANDS.size).padStart(4, '0')}-0000-4000-8000-${Date.now().toString(16).padStart(12, '0').slice(-12)}`;
    DEMO_COMMANDS.set(commandId, { runbook: rbRun[2], id: decodeURIComponent(rbRun[1]), polls: 0 });
    return { commandId, runbook: rbRun[2] } as T;
  }
  const rbResult = /^GET \/hosts\/([^/]+)\/runbooks\/commands\/([^/]+)$/.exec(key);
  if (rbResult) {
    const c = DEMO_COMMANDS.get(decodeURIComponent(rbResult[2]));
    if (!c) throw notFound();
    c.polls += 1;
    return (c.polls < 2 ? { commandId: rbResult[2], runbook: c.runbook, status: 'IN_PROGRESS', output: '', error: '', truncated: false }
      : { commandId: rbResult[2], runbook: c.runbook, status: 'SUCCESS', output: runbookOutput(c.runbook, c.id), error: '', truncated: false }) as T;
  }
  const host = /^GET \/hosts\/([^/]+)\/(summary|processes|limits|changes|investigate|peers|exposure|cost)$/.exec(key);
  if (host) {
    const id = decodeURIComponent(host[1]);
    if (!HOSTS.some((h) => h.id === id)) throw notFound();
    if (host[2] === 'investigate') return investigationFor(id, Date.parse(String(q.from)), Date.parse(String(q.to))) as T;
    if (host[2] === 'peers') return peersFor(id, Date.parse(String(q.from)), Date.parse(String(q.to))) as T;
    if (host[2] === 'exposure') return exposureFor(id) as T;
    if (host[2] === 'cost') return costFor(id, Date.parse(String(q.from)), Date.parse(String(q.to))) as T;
    if (host[2] === 'limits') return limits(id, Date.parse(String(q.from)), Date.parse(String(q.to))) as T;
    if (host[2] === 'changes') return changesFor(id, Date.parse(String(q.from)), Date.parse(String(q.to))) as T;
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
      const all = HOSTS.map(hostRow).filter((r) => !term || r.name.toLowerCase().includes(term) || r.instanceId.includes(term));
      const wanted = String(q.state ?? '').toUpperCase();
      const filteredRows = wanted ? all.filter((row) => row.health === wanted) : all;
      const page = Number(q.page ?? 0); const size = Number(q.size ?? 50);
      const items = filteredRows.slice(page * size, (page + 1) * size);
      return { items, page, size, total: filteredRows.length } as T;
    }
    case 'GET /containers': {
      const items: XpContainerRow[] = [
        { accountId: ACCOUNT_ID, instanceId: 'i-demo0004', containerId: 'c1a', name: 'api', image: 'demo/api:2.14.0', cpuPct: 31, memBytes: 1.2e9, memLimitBytes: 2.147e9, memLimitIsHost: false, restarts: 0, errors: 0, lastSeenAt: iso(nowMs() - 15_000) },
        { accountId: ACCOUNT_ID, instanceId: 'i-demo0005', containerId: 'c2b', name: 'api', image: 'demo/api:2.14.0', cpuPct: 148, memBytes: 3.9e9, memLimitBytes: 4.295e9, memLimitIsHost: false, restarts: 3, errors: 19, lastSeenAt: iso(nowMs() - 15_000) },
        { accountId: ACCOUNT_ID, instanceId: 'i-demo0001', containerId: 'c3c', name: 'nginx', image: 'nginx:1.27', cpuPct: 4, memBytes: 6e7, memLimitBytes: 5.37e8, memLimitIsHost: false, restarts: 0, errors: 0, lastSeenAt: iso(nowMs() - 15_000) },
      ];
      const term = String(q.q ?? '').toLowerCase();
      const filteredItems = items.filter((item) => !term || `${item.name} ${item.image ?? ''}`.toLowerCase().includes(term));
      const requestedSort = String(q.sort ?? '-restarts');
      const descending = requestedSort.startsWith('-');
      const sortKey = (descending ? requestedSort.slice(1) : requestedSort) as keyof Pick<XpContainerRow, 'name' | 'cpuPct' | 'memBytes' | 'restarts' | 'lastSeenAt'>;
      const allowedSorts = ['name', 'cpuPct', 'memBytes', 'restarts', 'lastSeenAt'];
      if (allowedSorts.includes(sortKey)) {
        filteredItems.sort((a, b) => {
          const left = a[sortKey]; const right = b[sortKey];
          if (left == null) return right == null ? a.containerId.localeCompare(b.containerId) : 1;
          if (right == null) return -1;
          const compared = typeof left === 'number' && typeof right === 'number' ? left - right : String(left).localeCompare(String(right));
          return compared === 0 ? a.containerId.localeCompare(b.containerId) : compared * (descending ? -1 : 1);
        });
      }
      const page = Number(q.page ?? 0); const size = Number(q.size ?? 50);
      return { items: filteredItems.slice(page * size, (page + 1) * size), page, size, total: filteredItems.length } as T;
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
    case 'POST /alerts/rules/preview': return backtest(b as unknown as XpAlertRule) as T;
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
