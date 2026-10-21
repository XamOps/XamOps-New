/**
 * Demo fixtures for the Phase-2 time series (FEAT-004), in the shapes `UsageSeriesDto`,
 * `SparklinesDto` and `HpaHistoryDto` serialise to:
 *
 *   GET /api/eks/automation/usage/{cluster}?scope=cluster|namespace|workload&resource&window[&namespace&kind&name][&from&to&step]
 *   GET /api/eks/automation/usage/{cluster}/sparklines?resource&window=72h
 *   GET /api/eks/automation/hpa/{cluster}/{namespace}/{name}/history?window
 *
 * Demo-only and never reachable outside a demo session. The numbers are generated, deterministic in
 * time, and plausible for prod-eks-cluster as the rest of the demo describes it: its workloads' sizes
 * from endpoint 1's fixture (grafana's request stepped down 26 hours ago, the user's applied change
 * in the activity feed), its nodes' allocatable and capacity from the agent's fixture, and its HPAs
 * (worker-hpa held at its maximum of 4 until a user raised it to 5, then load fell; frontend-hpa
 * never scaled). The demo Prometheus keeps 5 days, so a 7-day window reads as partial, and
 * api-staging had no running pod for a few hours two days ago (a gap, not zeros). eu-eks-cluster has
 * no usage metrics (as endpoint 1 says), so its answers carry `unavailable`; any other cluster isn't
 * registered. Every time below is in epoch seconds.
 */

const HOUR = 3600;
const DAY = 24 * HOUR;
const MIB = 1024 ** 2;

/** The demo Prometheus' history: 5 days. */
const RETENTION_SECONDS = 5 * DAY;

const SOURCE = { type: 'PROMETHEUS_PROXY', service: 'monitoring/prometheus-server:80' } as const;

type Point = [number, number | null];
type Resource = 'cpu' | 'memory';

/** A refusal in the server's shape. */
const refusal = (code: string, message: string, field: string | null = null) => ({ success: false, code, message, field, detail: null });

export interface DemoAnswer {
  status: number;
  body: unknown;
}

const isDemoCluster = (cluster: string) => cluster === 'prod-eks-cluster' || cluster === 'eu-eks-cluster';

const NOT_REGISTERED: DemoAnswer = { status: 404, body: refusal('K8S_CLUSTER_NOT_REGISTERED', "This cluster isn't registered with XamOps.") };

const NO_METRICS = { code: 'NO_METRICS_SOURCE', message: 'No Prometheus answered in this cluster.' };

/** A deterministic value in [0, 1) for a time and a seed. */
function noise(t: number, seed: number): number {
  const x = Math.sin(t * 0.0137 + seed * 78.233) * 43758.5453;
  return x - Math.floor(x);
}

/** 0..1 over the IST day: lowest around 4 AM, highest around 4 PM. */
function diurnal(t: number): number {
  const hourIst = ((((t + 5.5 * HOUR) % DAY) + DAY) % DAY) / HOUR;
  return 0.5 - 0.5 * Math.cos(((hourIst - 4) / 24) * 2 * Math.PI);
}

const round = (v: number, digits = 4) => Number(v.toFixed(digits));

// ─── The demo workloads (prod-eks-cluster), per pod ──────────────────────────

interface DemoWorkload {
  namespace: string;
  kind: 'Deployment' | 'StatefulSet';
  name: string;
  pods: number;
  /** CPU per pod, cores: the mean use and its daily swing. */
  cpu: number;
  cpuSwing: number;
  /** Memory working set per pod, bytes. */
  mem: number;
  /** Requests and limits per pod (cores, bytes); null when not declared. */
  cpuRequest: number | null;
  cpuLimit: number | null;
  memRequest: number | null;
  memLimit: number | null;
  seed: number;
}

/**
 * Each workload's use agrees with what the rest of the demo says about it: grafana is right-sized at
 * 100m / 256Mi (endpoint 1: within hysteresis), so its busiest pod stays under both, before and after
 * the change a user applied; worker's pods run at about 45% of their 100m request, the CPU
 * utilisation worker-hpa reports.
 */
const WORKLOADS: DemoWorkload[] = [
  { namespace: 'production', kind: 'Deployment', name: 'api-server', pods: 3, cpu: 0.3, cpuSwing: 0.18, mem: 500 * MIB, cpuRequest: 0.256, cpuLimit: 0.512, memRequest: 512 * MIB, memLimit: 1024 * MIB, seed: 1 },
  { namespace: 'production', kind: 'Deployment', name: 'worker', pods: 2, cpu: 0.047, cpuSwing: 0.4, mem: 250 * MIB, cpuRequest: 0.1, cpuLimit: 0.4, memRequest: 256 * MIB, memLimit: 512 * MIB, seed: 2 },
  { namespace: 'production', kind: 'Deployment', name: 'frontend', pods: 2, cpu: 0.09, cpuSwing: 0.3, mem: 176 * MIB, cpuRequest: 0.05, cpuLimit: 0.2, memRequest: 128 * MIB, memLimit: 256 * MIB, seed: 3 },
  { namespace: 'monitoring', kind: 'Deployment', name: 'grafana', pods: 1, cpu: 0.065, cpuSwing: 0.15, mem: 190 * MIB, cpuRequest: 0.1, cpuLimit: 0.3, memRequest: 256 * MIB, memLimit: 512 * MIB, seed: 4 },
  { namespace: 'staging', kind: 'Deployment', name: 'api-staging', pods: 1, cpu: 0.008, cpuSwing: 0.4, mem: 200 * MIB, cpuRequest: 0.1, cpuLimit: 0.3, memRequest: 256 * MIB, memLimit: 512 * MIB, seed: 5 },
  { namespace: 'staging', kind: 'Deployment', name: 'worker-staging', pods: 1, cpu: 0.07, cpuSwing: 0.2, mem: 128 * MIB, cpuRequest: null, cpuLimit: 0.2, memRequest: null, memLimit: 256 * MIB, seed: 6 },
  { namespace: 'kube-system', kind: 'Deployment', name: 'coredns', pods: 2, cpu: 0.016, cpuSwing: 0.2, mem: 29 * MIB, cpuRequest: 0.1, cpuLimit: null, memRequest: 70 * MIB, memLimit: 170 * MIB, seed: 7 },
  { namespace: 'monitoring', kind: 'StatefulSet', name: 'prometheus', pods: 1, cpu: 0.42, cpuSwing: 0.12, mem: 1024 * MIB, cpuRequest: 0.5, cpuLimit: 1, memRequest: 2048 * MIB, memLimit: 4096 * MIB, seed: 8 },
  { namespace: 'monitoring', kind: 'StatefulSet', name: 'alertmanager', pods: 1, cpu: 0.05, cpuSwing: 0.1, mem: 96 * MIB, cpuRequest: 0.05, cpuLimit: 0.2, memRequest: 128 * MIB, memLimit: 256 * MIB, seed: 9 },
];

/** Pods outside the analysed workloads (the kube-system daemons): use and requests. */
const SYSTEM: Record<Resource, { used: number; request: number }> = {
  cpu: { used: 0.013, request: 0.125 },
  memory: { used: 62 * MIB, request: 0 },
};

/** grafana's request before a user applied 100m / 256Mi, 26 h 13 min ago (the demo activity feed). */
const GRAFANA_CHANGE_AGO = 26 * HOUR + 13 * 60;
const GRAFANA_BEFORE: Record<Resource, number> = { cpu: 0.25, memory: 384 * MIB };

/** api-staging had no running pod in this span (seconds before now): no sample, a gap. */
const STAGING_GAP_AGO: [number, number] = [2 * DAY + 3 * HOUR, 2 * DAY];

/** The nodes' totals (the agent fixture's six nodes). */
const NODES: Record<Resource, { allocatable: number; capacity: number }> = {
  cpu: { allocatable: 25.52, capacity: 26 },
  memory: { allocatable: 91_101_364 * 1024, capacity: 96_685_700 * 1024 },
};

interface Clock {
  now: number;
}

function running(w: DemoWorkload, t: number, c: Clock): boolean {
  return !(w.name === 'api-staging' && t >= c.now - STAGING_GAP_AGO[0] && t < c.now - STAGING_GAP_AGO[1]);
}

function requestOf(w: DemoWorkload, r: Resource, t: number, c: Clock): number | null {
  if (w.name === 'grafana' && t < c.now - GRAFANA_CHANGE_AGO) return GRAFANA_BEFORE[r];
  return r === 'cpu' ? w.cpuRequest : w.memRequest;
}

/** One pod's use at t (cores or bytes); the pod index shifts the noise, so the busiest pod moves. */
function podUse(w: DemoWorkload, r: Resource, t: number, pod: number): number {
  if (r === 'cpu') {
    const v = w.cpu * (1 - w.cpuSwing / 2 + w.cpuSwing * diurnal(t)) * (0.9 + 0.2 * noise(t, w.seed * 10 + pod));
    // A container can't use more than its limit.
    return w.cpuLimit !== null ? Math.min(v, w.cpuLimit) : v;
  }
  return w.mem * (0.95 + 0.1 * noise(t, w.seed * 10 + pod + 5));
}

/** The busiest pod's use (the engine's selection: each pod's selected containers summed, the max). */
function busiest(w: DemoWorkload, r: Resource, t: number, c: Clock): number | null {
  if (!running(w, t, c)) return null;
  let max = 0;
  for (let p = 0; p < w.pods; p += 1) max = Math.max(max, podUse(w, r, t, p));
  return round(max);
}

const totalUse = (ws: readonly DemoWorkload[], r: Resource, t: number, c: Clock): number =>
  ws.reduce((sum, w) => {
    if (!running(w, t, c)) return sum;
    let s = 0;
    for (let p = 0; p < w.pods; p += 1) s += podUse(w, r, t, p);
    return sum + s;
  }, 0);

const totalRequest = (ws: readonly DemoWorkload[], r: Resource, t: number, c: Clock): number =>
  ws.reduce((sum, w) => sum + (running(w, t, c) ? (requestOf(w, r, t, c) ?? 0) * w.pods : 0), 0);

// ─── The time grid ───────────────────────────────────────────────────────────

const WINDOWS: Record<string, { seconds: number; step: number }> = {
  '24h': { seconds: DAY, step: 600 },
  '72h': { seconds: 3 * DAY, step: 1800 },
  '168h': { seconds: 7 * DAY, step: 3600 },
};

interface Grid {
  start: number;
  end: number;
  step: number;
  times: number[];
}

function grid(start: number, end: number, step: number): Grid {
  const times: number[] = [];
  for (let t = start; t <= end; t += step) times.push(t);
  return { start, end, step, times };
}

/** A fixed window ending at the current whole minute, as the server does. */
function fixedGrid(window: string, c: Clock): Grid {
  const w = WINDOWS[window];
  const end = Math.floor(c.now / 60) * 60;
  return grid(end - w.seconds, end, w.step);
}

const iso = (sec: number) => new Date(sec * 1000).toISOString().replace('.000Z', 'Z');

/** Points before the demo Prometheus' oldest sample are null: "no data before", never 0. */
function points(g: Grid, c: Clock, value: (t: number) => number | null): Point[] {
  const oldest = c.now - RETENTION_SECONDS;
  return g.times.map((t) => [t, t < oldest ? null : value(t)]);
}

function coverage(used: Point[]) {
  const measured = used.filter((p) => p[1] !== null);
  return { firstSampleAt: measured.length > 0 ? iso(measured[0][0]) : null, covered: used.length > 0 ? round(measured.length / used.length) : 0 };
}

const USED_QUERY: Record<Resource, string> = {
  cpu: 'rate(container_cpu_usage_seconds_total{container!="",pod!=""}[5m])',
  memory: 'container_memory_working_set_bytes{container!="",pod!=""}',
};
const RUNNING = '* on(namespace,pod) group_left() (max by (namespace,pod) (kube_pod_status_phase{phase="Running"}) == 1)';
const requestQuery = (r: Resource) => `kube_pod_container_resource_requests{resource="${r}"} ${RUNNING}`;

// ─── Usage ───────────────────────────────────────────────────────────────────

export interface DemoUsageQuery {
  scope: string;
  resource: string;
  window: string;
  namespace: string;
  kind: string;
  name: string;
  from: string;
  to: string;
  step: string;
}

const invalid = (message: string, field: string): DemoAnswer => ({ status: 400, body: refusal('K8S_INVALID_REQUEST', message, field) });

/** The grid a usage query asks for, or the 400 the server answers. */
function usageGrid(q: DemoUsageQuery, c: Clock): Grid | DemoAnswer {
  if (q.window === 'custom') {
    if (q.scope !== 'workload') return invalid('A custom window is only available with scope=workload.', 'window');
    const from = Date.parse(q.from);
    const to = Date.parse(q.to);
    const step = Number(q.step);
    if (!Number.isFinite(from)) return invalid('from must be an ISO-8601 time, like 2026-10-14T10:00:00Z.', 'from');
    if (!Number.isFinite(to)) return invalid('to must be an ISO-8601 time, like 2026-10-14T10:00:00Z.', 'to');
    if (![60, 300, 1800, 3600].includes(step)) return invalid('step must be 60, 300, 1800 or 3600.', 'step');
    if (from >= to) return invalid('from must be before to.', 'from');
    if (to - from > 7 * DAY * 1000) return invalid('A custom window can span at most 7 days.', 'from');
    if (to > (c.now + 60) * 1000) return invalid('to cannot be in the future.', 'to');
    if (Math.floor((to - from) / 1000 / step) + 1 > 600) return invalid('That window has more than 600 points per series. Use a larger step.', 'step');
    return grid(Math.floor(from / 1000), Math.floor(to / 1000), step);
  }
  if (!WINDOWS[q.window]) return invalid('window must be 24h, 72h, 168h or custom.', 'window');
  if (q.from || q.to || q.step) return invalid('from, to and step are only accepted with window=custom.', q.from ? 'from' : q.to ? 'to' : 'step');
  return fixedGrid(q.window, c);
}

/** `GET /api/eks/automation/usage/{cluster}`, validated the way the server validates it. */
export function demoUsage(cluster: string, q: DemoUsageQuery, nowMs: number = Date.now()): DemoAnswer {
  if (!isDemoCluster(cluster)) return NOT_REGISTERED;
  if (!['cluster', 'namespace', 'workload'].includes(q.scope)) return invalid('scope must be cluster, namespace or workload.', 'scope');
  if (q.resource !== 'cpu' && q.resource !== 'memory') return invalid('resource must be cpu or memory.', 'resource');
  const workloadScope = q.scope === 'workload';
  if (!workloadScope && (q.namespace || q.kind || q.name)) {
    return invalid('namespace, kind and name are only accepted with scope=workload.', q.namespace ? 'namespace' : q.kind ? 'kind' : 'name');
  }
  if (workloadScope && (!q.namespace || !q.kind || !q.name)) {
    return invalid(`${!q.namespace ? 'namespace' : !q.kind ? 'kind' : 'name'} is required with scope=workload.`, !q.namespace ? 'namespace' : !q.kind ? 'kind' : 'name');
  }
  const c: Clock = { now: Math.floor(nowMs / 1000) };
  const g = usageGrid(q, c);
  if (!('times' in g)) return g;

  const r: Resource = q.resource;
  const ref = workloadScope ? { namespace: q.namespace, kind: q.kind, name: q.name } : null;
  const base = {
    schemaVersion: 1, scope: q.scope, resource: r, unit: r === 'cpu' ? 'cores' : 'bytes', workloadRef: ref, window: q.window,
    stepSeconds: g.step, start: iso(g.start), end: iso(g.end), source: SOURCE,
  };
  if (cluster === 'eu-eks-cluster') {
    return { status: 200, body: { ...base, series: [], namespaces: q.scope === 'namespace' ? [] : null, coverage: null, unavailable: NO_METRICS } };
  }

  if (q.scope === 'cluster') {
    const used = points(g, c, (t) => round(totalUse(WORKLOADS, r, t, c) + SYSTEM[r].used));
    return {
      status: 200,
      body: {
        ...base,
        series: [
          { key: 'used', kind: 'used', label: 'Used', points: used, query: `sum(${USED_QUERY[r]})` },
          { key: 'request', kind: 'request', label: 'Requested', points: points(g, c, (t) => round(totalRequest(WORKLOADS, r, t, c) + SYSTEM[r].request)), query: `sum(${requestQuery(r)})` },
          { key: 'allocatable', kind: 'allocatable', label: 'Allocatable', points: points(g, c, () => NODES[r].allocatable), query: `sum(kube_node_status_allocatable{resource="${r}"})` },
          { key: 'capacity', kind: 'capacity', label: 'Provisioned', points: points(g, c, () => NODES[r].capacity), query: `sum(kube_node_status_capacity{resource="${r}"})` },
        ],
        namespaces: null,
        coverage: coverage(used),
        unavailable: null,
      },
    };
  }

  if (q.scope === 'namespace') {
    const names = [...new Set(WORKLOADS.map((w) => w.namespace))].sort();
    const namespaces = names.map((ns) => {
      const inNs = WORKLOADS.filter((w) => w.namespace === ns);
      const system = ns === 'kube-system' ? SYSTEM[r] : { used: 0, request: 0 };
      const used = points(g, c, (t) => round(totalUse(inNs, r, t, c) + system.used));
      const request = points(g, c, (t) => round(totalRequest(inNs, r, t, c) + system.request));
      const measured = used.map((p) => p[1]).filter((v): v is number => v !== null);
      return {
        namespace: ns,
        requestedNow: request[request.length - 1][1],
        usedAvg: measured.length > 0 ? round(measured.reduce((a, b) => a + b, 0) / measured.length) : null,
        series: [
          { key: 'used', kind: 'used', label: 'Used', points: used, query: `sum by (namespace) (${USED_QUERY[r]})` },
          { key: 'request', kind: 'request', label: 'Requested', points: request, query: `sum by (namespace) (${requestQuery(r)})` },
        ],
      };
    });
    return { status: 200, body: { ...base, series: [], namespaces, coverage: coverage(points(g, c, () => 1)), unavailable: null } };
  }

  // scope=workload: the busiest pod, as the engine selects it.
  const w = WORKLOADS.find((x) => x.namespace === q.namespace && x.kind === q.kind && x.name === q.name);
  if (!w) return { status: 404, body: refusal('K8S_INVALID_REQUEST', `${q.namespace}/${q.kind}/${q.name} isn't a workload XamOps analyses on this cluster.`, 'name') };
  const sel = `namespace="${w.namespace}",pod=~"${w.name}-.*"`;
  const used = points(g, c, (t) => busiest(w, r, t, c));
  const limit = r === 'cpu' ? w.cpuLimit : w.memLimit;
  return {
    status: 200,
    body: {
      ...base,
      series: [
        {
          key: 'used', kind: 'used', label: 'Used', points: used,
          query: r === 'cpu' ? `max(sum by (pod) (rate(container_cpu_usage_seconds_total{${sel}}[5m])))` : `max(sum by (pod) (container_memory_working_set_bytes{${sel}}))`,
        },
        {
          key: 'request', kind: 'request', label: 'Request now', points: points(g, c, (t) => (running(w, t, c) ? requestOf(w, r, t, c) : null)),
          query: `max(sum by (pod) (max by (pod, container) (kube_pod_container_resource_requests{resource="${r}",${sel}})))`,
        },
        {
          key: 'limit', kind: 'limit', label: 'Limit', points: points(g, c, (t) => (running(w, t, c) ? limit : null)),
          query: `max(sum by (pod) (max by (pod, container) (kube_pod_container_resource_limits{resource="${r}",${sel}})))`,
        },
      ],
      namespaces: null,
      coverage: coverage(used),
      unavailable: null,
    },
  };
}

/** `GET /api/eks/automation/usage/{cluster}/sparklines`: 72 h at 1 h, every analysed workload. */
export function demoSparklines(cluster: string, resource: string, window: string, nowMs: number = Date.now()): DemoAnswer {
  if (!isDemoCluster(cluster)) return NOT_REGISTERED;
  if (resource !== 'cpu' && resource !== 'memory') return invalid('resource must be cpu or memory.', 'resource');
  if (window !== '72h') return invalid('The sparklines have one window: 72h.', 'window');
  const c: Clock = { now: Math.floor(nowMs / 1000) };
  const end = Math.floor(c.now / 60) * 60;
  const g = grid(end - 3 * DAY, end, HOUR);
  const r: Resource = resource;
  const base = {
    schemaVersion: 1, resource: r, unit: r === 'cpu' ? 'cores' : 'bytes', window: '72h', stepSeconds: HOUR,
    start: iso(g.start), end: iso(g.end), source: SOURCE,
  };
  if (cluster === 'eu-eks-cluster') return { status: 200, body: { ...base, queries: null, workloads: [], unavailable: NO_METRICS } };
  const workloads = [...WORKLOADS]
    .sort((a, b) => a.namespace.localeCompare(b.namespace) || a.kind.localeCompare(b.kind) || a.name.localeCompare(b.name))
    .map((w) => ({
      ref: { namespace: w.namespace, kind: w.kind, name: w.name },
      used: points(g, c, (t) => busiest(w, r, t, c)),
      request: points(g, c, (t) => (running(w, t, c) ? requestOf(w, r, t, c) : null)),
    }));
  return {
    status: 200,
    body: {
      ...base,
      queries: {
        used: `sum by (namespace, pod, container) (${USED_QUERY[r]})`,
        request: `max by (namespace, pod, container) (kube_pod_container_resource_requests{resource="${r}"})`,
      },
      workloads,
      unavailable: null,
    },
  };
}

// ─── HPA history ─────────────────────────────────────────────────────────────

interface DemoHpa {
  namespace: string;
  name: string;
  min: number;
  target: number;
  /** maxReplicas over time: [seconds before now it took effect, value], oldest first. */
  max: Array<[number, number]>;
  /** The running replica count over time, oldest first. */
  replicas: Array<[number, number]>;
  /** CPU use, % of request: when below the maximum, and while held at it. */
  cpu: number;
  cpuAtMax: number;
  seed: number;
}

/** worker-hpa: held at its maximum of 4 until a user raised it to 5 (the activity feed's HPA change). */
const WORKER_RAISED_AGO = 5 * HOUR + 4 * 60;

const HPAS: DemoHpa[] = [
  {
    namespace: 'production', name: 'api-server-hpa', min: 2, target: 70, max: [[10 * DAY, 10]],
    replicas: [[10 * DAY, 2], [30 * HOUR, 3]], cpu: 58, cpuAtMax: 58, seed: 21,
  },
  {
    namespace: 'production', name: 'worker-hpa', min: 1, target: 60, max: [[10 * DAY, 4], [WORKER_RAISED_AGO, 5]],
    replicas: [[10 * DAY, 2], [14 * HOUR, 3], [10 * HOUR, 4], [WORKER_RAISED_AGO - 6 * 60, 5], [4 * HOUR, 3], [3 * HOUR, 2]],
    cpu: 45, cpuAtMax: 78, seed: 22,
  },
  {
    namespace: 'production', name: 'frontend-hpa', min: 2, target: 65, max: [[10 * DAY, 8]],
    replicas: [[10 * DAY, 2]], cpu: 32, cpuAtMax: 32, seed: 23,
  },
];

/** The value of a step function (oldest first) at `ago` seconds before now. */
function stepAt(steps: Array<[number, number]>, ago: number): number {
  let value = steps[0][1];
  for (const [since, v] of steps) if (ago <= since) value = v;
  return value;
}

const HPA_REF = /^[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?\/[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?$/;

/**
 * `GET /api/eks/automation/hpa/{cluster}/history?hpas=ns/name,…`, the HPA list's one read: each HPA
 * answered as its own read answers it, an HPA the demo cluster doesn't have listed in `missing`.
 */
export function demoHpaHistories(cluster: string, hpas: string, window: string, nowMs: number = Date.now()): DemoAnswer {
  const refs = [...new Set(hpas.split(',').map((ref) => ref.trim()))];
  if (hpas.trim() === '' || refs.length > 20 || refs.some((ref) => !HPA_REF.test(ref))) {
    return invalid('The hpas parameter is missing or malformed.', 'hpas');
  }
  const histories: unknown[] = [];
  const missing: Array<{ namespace: string; name: string }> = [];
  for (const ref of refs) {
    const [namespace, name] = ref.split('/');
    const one = demoHpaHistory(cluster, namespace, name, window, nowMs);
    if (one.status === 200) histories.push(one.body);
    else if (one.status === 404 && (one.body as { field?: string | null }).field === 'name') missing.push({ namespace, name });
    // Not registered, or a bad window: the whole read answers so, as on the server.
    else return one;
  }
  return { status: 200, body: { schemaVersion: 1, window, histories, missing } };
}

/** `GET /api/eks/automation/hpa/{cluster}/{namespace}/{name}/history`. */
export function demoHpaHistory(cluster: string, namespace: string, name: string, window: string, nowMs: number = Date.now()): DemoAnswer {
  if (!isDemoCluster(cluster)) return NOT_REGISTERED;
  if (!WINDOWS[window]) return invalid('window must be 24h, 72h or 168h.', 'window');
  const c: Clock = { now: Math.floor(nowMs / 1000) };
  const g = fixedGrid(window, c);
  const base = { schemaVersion: 1, namespace, name, window, stepSeconds: g.step, start: iso(g.start), end: iso(g.end), source: SOURCE };
  if (cluster === 'eu-eks-cluster') {
    return { status: 200, body: { ...base, series: [], atMax: null, changes: [], changesTruncated: false, firstSampleAt: null, unavailable: NO_METRICS } };
  }
  const hpa = HPAS.find((h) => h.namespace === namespace && h.name === name);
  if (!hpa) return { status: 404, body: refusal('K8S_INVALID_REQUEST', `${namespace}/${name} isn't an HPA on this cluster.`, 'name') };

  const replicasAt = (t: number) => stepAt(hpa.replicas, c.now - t);
  const maxAt = (t: number) => stepAt(hpa.max, c.now - t);
  const atMax = (t: number) => replicasAt(t) >= maxAt(t);
  const cpuAt = (t: number) => round((atMax(t) ? hpa.cpuAtMax : hpa.cpu) * (0.9 + 0.2 * noise(t, hpa.seed)), 1);
  const sel = `namespace="${namespace}",horizontalpodautoscaler="${name}"`;
  const cpuSel = `${sel},metric_name="cpu",metric_target_type="utilization"`;
  const series = [
    { key: 'currentReplicas', label: 'Replicas running', unit: 'replicas', points: points(g, c, replicasAt), query: `max(kube_horizontalpodautoscaler_status_current_replicas{${sel}})` },
    { key: 'minReplicas', label: 'Minimum', unit: 'replicas', points: points(g, c, () => hpa.min), query: `max(kube_horizontalpodautoscaler_spec_min_replicas{${sel}})` },
    { key: 'maxReplicas', label: 'Maximum', unit: 'replicas', points: points(g, c, maxAt), query: `max(kube_horizontalpodautoscaler_spec_max_replicas{${sel}})` },
    { key: 'cpuUtilization', label: 'CPU used', unit: 'percent', points: points(g, c, cpuAt), query: `max(kube_horizontalpodautoscaler_status_target_metric{${cpuSel}})` },
    { key: 'cpuTarget', label: 'Target', unit: 'percent', points: points(g, c, () => hpa.target), query: `max(kube_horizontalpodautoscaler_spec_target_metric{${cpuSel}})` },
  ];

  // The 60 s history over the same span: spells at the maximum, and every replica change.
  const oldest = c.now - RETENTION_SECONDS;
  const minutes = grid(g.start, g.end, 60).times.filter((t) => t >= oldest);
  const runs: Array<{ from: string; to: string | null }> = [];
  const changes: Array<{ at: string; from: number; to: number }> = [];
  let runStart: number | null = null;
  let minutesAtMax = 0;
  let previous: number | null = null;
  for (const t of minutes) {
    const held = atMax(t);
    if (held) minutesAtMax += 1;
    if (held && runStart === null) runStart = t;
    if (!held && runStart !== null) {
      runs.push({ from: iso(runStart), to: iso(t) });
      runStart = null;
    }
    const n = replicasAt(t);
    if (previous !== null && n !== previous) changes.push({ at: iso(t), from: previous, to: n });
    previous = n;
  }
  if (runStart !== null) runs.push({ from: iso(runStart), to: null });
  return {
    status: 200,
    body: {
      ...base,
      series,
      atMax: {
        share: minutes.length > 0 ? round(minutesAtMax / minutes.length) : null,
        minutesAtMax,
        minutesMeasured: minutes.length,
        runs,
        runsTruncated: false,
        since: runStart !== null ? iso(runStart) : null,
        stepSeconds: 60,
        query: `max(kube_horizontalpodautoscaler_status_current_replicas{${sel}}) >= bool max(kube_horizontalpodautoscaler_spec_max_replicas{${sel}})`,
      },
      changes,
      changesTruncated: false,
      firstSampleAt: minutes.length > 0 ? iso(minutes[0]) : null,
      unavailable: null,
    },
  };
}
