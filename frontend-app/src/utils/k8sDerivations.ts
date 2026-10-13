/**
 * What the CloudK8s sections derive from the dashboard and the vertical engine
 * (docs/cloudk8s/ui-revamp-design.md D8, D9, D10, §10, §17).
 *
 * Pure functions, memoized by their callers per dashboard object. The rule throughout is D10's:
 * unknown is not zero. A value nobody reported is `null`, rendered "—" with its source; only request
 * sums count an unset request as 0, because it reserves nothing, and they count those containers.
 */
import type { CapabilitiesCluster } from '../types/k8sAutomation';
import type { EksDashboard } from '../types/eksTypes';
import type { Resources, VerticalWorkload } from '../types/verticalRecommendations';
import { MEMORY_RATE_PER_GIB_HOUR, CPU_RATE_PER_VCPU_HOUR } from './k8sPricing';
import { parseCpuMillicores, parseMemoryMiB, toNumber } from './k8sQuantities';

type AnyRecord = Record<string, any>;

const list = (value: unknown): AnyRecord[] =>
  Array.isArray(value) ? value.filter((v): v is AnyRecord => typeof v === 'object' && v !== null) : [];

const text = (value: unknown): string | null => (typeof value === 'string' && value.trim() !== '' ? value : null);

// ── Monitoring state (§8.1, D10) ─────────────────────────────────────────────

/** M0 not monitored, M1 monitored, M2 stale (the XamOps agent's data is over 15 minutes old). */
export type MonitoringState = 'M0' | 'M1' | 'M2';

export interface MonitoringInput {
  capabilitiesStatus: 'loading' | 'ready' | 'error';
  /** The cluster's capabilities entry; null when capabilities don't list it. */
  cluster: CapabilitiesCluster | null;
  dashboard: EksDashboard | null | undefined;
}

export interface Monitoring {
  state: MonitoringState;
  /** Which source decided: capabilities when they loaded, else the dashboard. */
  basis: 'capabilities' | 'dashboard';
  /** The XamOps agent's last data, when capabilities report it. */
  lastDataAt: string | null;
}

/**
 * True for a dashboard that carries no cluster: no agent nodes and a node count of 0. That is the
 * backend's empty shell for a cluster nothing reports on, which still says `clusterHealthScore: 100`.
 */
export function isEmptyDashboard(dashboard: EksDashboard | null | undefined): boolean {
  if (!dashboard) return true;
  const agentNodes = list(dashboard.k8sNodes).length;
  const totalNodes = toNumber(dashboard.clusterOverview?.totalNodes) ?? 0;
  return agentNodes === 0 && totalNodes === 0;
}

/**
 * The monitoring axis (§8.1). Capabilities decide when they loaded: no entry or no XamOps agent is
 * M0, a stale agent M2. Otherwise (loading, failed, or an agent state of UNKNOWN) the dashboard
 * decides: an empty dashboard is M0, anything else M1.
 */
export function monitoringState({ capabilitiesStatus, cluster, dashboard }: MonitoringInput): Monitoring {
  if (capabilitiesStatus === 'ready') {
    if (!cluster || !cluster.registered || cluster.agent.status === 'NOT_INSTALLED') {
      return { state: 'M0', basis: 'capabilities', lastDataAt: null };
    }
    if (cluster.agent.status === 'STALE') return { state: 'M2', basis: 'capabilities', lastDataAt: cluster.agent.lastDataAt };
    if (cluster.agent.status === 'CONNECTED') return { state: 'M1', basis: 'capabilities', lastDataAt: cluster.agent.lastDataAt };
  }
  return { state: isEmptyDashboard(dashboard) ? 'M0' : 'M1', basis: 'dashboard', lastDataAt: cluster?.agent.lastDataAt ?? null };
}

// ── Nodes ────────────────────────────────────────────────────────────────────

export type CapacityType = 'on-demand' | 'spot';

export interface ClusterNode {
  name: string;
  /** Ready condition: true, false, or null when the node reports none. */
  ready: boolean | null;
  pressure: { memory: boolean; disk: boolean; pid: boolean };
  unschedulable: boolean;
  instanceType: string | null;
  /** From the capacity-type labels or the agent's field; null when nothing says. */
  capacityType: CapacityType | null;
  /** The raw value when it isn't on-demand or Spot, shown as is. */
  capacityTypeRaw: string | null;
  zone: string | null;
  labels: Record<string, string>;
  /** What the machine reports (Overview's "Provisioned"); null when the node doesn't say. */
  capacityCpuM: number | null;
  capacityMemMi: number | null;
  allocatableCpuM: number | null;
  allocatableMemMi: number | null;
  kubeletVersion: string | null;
  internalIp: string | null;
  age: string | null;
  podCount: number | null;
}

const NODE_GROUP_LABEL = 'eks.amazonaws.com/nodegroup';
const NODE_POOL_LABEL = 'karpenter.sh/nodepool';

function labelsOf(node: AnyRecord): Record<string, string> {
  const raw = node.labels;
  if (!raw || typeof raw !== 'object') return {};
  return Object.fromEntries(Object.entries(raw).filter(([, v]) => typeof v === 'string')) as Record<string, string>;
}

/** "ON_DEMAND", "on-demand", "SPOT", "spot" → on-demand or Spot; anything else stays raw. */
export function normalizeCapacityType(value: unknown): { type: CapacityType | null; raw: string | null } {
  const raw = text(value);
  if (!raw) return { type: null, raw: null };
  const v = raw.toLowerCase().replace(/_/g, '-');
  if (v === 'on-demand' || v === 'ondemand') return { type: 'on-demand', raw };
  if (v === 'spot') return { type: 'spot', raw };
  return { type: null, raw };
}

function conditionStatus(node: AnyRecord, type: string): boolean | null {
  const conditions = list(node.conditions);
  const found = conditions.find((c) => c.type === type);
  if (!found) return null;
  return found.status === 'True' || found.status === true;
}

function nodeReady(node: AnyRecord): boolean | null {
  const fromConditions = conditionStatus(node, 'Ready');
  if (fromConditions !== null) return fromConditions;
  if (typeof node.ready === 'boolean') return node.ready;
  if (node.condition === 'Ready' || node.condition === 'ready') return true;
  if (node.condition === 'NotReady' || node.condition === 'notReady') return false;
  return null;
}

function toClusterNode(node: AnyRecord): ClusterNode | null {
  const name = text(node.name) ?? text(node.nodeName);
  if (!name) return null;
  const labels = labelsOf(node);
  const capacity = normalizeCapacityType(
    labels['karpenter.sh/capacity-type'] ?? labels['eks.amazonaws.com/capacityType'] ?? labels['capacity-type'] ?? node.capacityType,
  );
  const pressure = (type: string, flag: unknown) => conditionStatus(node, type) === true || flag === true;
  return {
    name,
    ready: nodeReady(node),
    pressure: {
      memory: pressure('MemoryPressure', node.memoryPressure),
      disk: pressure('DiskPressure', node.diskPressure),
      pid: pressure('PIDPressure', node.pidPressure),
    },
    unschedulable: node.unschedulable === true,
    instanceType: text(node.instanceType) ?? labels['node.kubernetes.io/instance-type'] ?? null,
    capacityType: capacity.type,
    capacityTypeRaw: capacity.raw,
    zone: text(node.zone) ?? labels['topology.kubernetes.io/zone'] ?? null,
    labels,
    // Only Kubernetes quantity strings: the numeric fields mean cores on one backend path and
    // millicores on another, so a bare number isn't trusted as capacity or allocatable.
    capacityCpuM: typeof node.capacityCpu === 'string' ? parseCpuMillicores(node.capacityCpu) : null,
    capacityMemMi: typeof node.capacityMemory === 'string' ? parseMemoryMiB(node.capacityMemory) : null,
    allocatableCpuM: typeof node.allocatableCpu === 'string' ? parseCpuMillicores(node.allocatableCpu) : null,
    allocatableMemMi: typeof node.allocatableMemory === 'string' ? parseMemoryMiB(node.allocatableMemory) : null,
    kubeletVersion: text(node.kubeletVersion),
    internalIp: text(node.internalIp),
    age: text(node.age),
    podCount: toNumber(node.podCount ?? node.runningPodsCount),
  };
}

/**
 * The cluster's nodes from the best source: the agent's `k8sNodes`, else `nodeMetrics.nodes`, else
 * `resourceUtilization.nodes`; one entry per name.
 */
export function clusterNodes(dashboard: EksDashboard | null | undefined): ClusterNode[] {
  if (!dashboard) return [];
  const sources = [list(dashboard.k8sNodes), list(dashboard.nodeMetrics?.nodes), list(dashboard.resourceUtilization?.nodes)];
  const raw = sources.find((s) => s.length > 0) ?? [];
  const seen = new Set<string>();
  const nodes: ClusterNode[] = [];
  for (const n of raw) {
    const node = toClusterNode(n);
    if (node && !seen.has(node.name)) {
      seen.add(node.name);
      nodes.push(node);
    }
  }
  return nodes;
}

/** Per-node usage from the agent's Prometheus metrics, else the dashboard's node utilization; null when unreported. */
export interface NodeUsage {
  cpuPct: number | null;
  memPct: number | null;
}

export function nodeUsageByName(dashboard: EksDashboard | null | undefined): Map<string, NodeUsage> {
  const usage = new Map<string, NodeUsage>();
  if (!dashboard) return usage;
  for (const n of list(dashboard.resourceUtilization?.nodes)) {
    const name = text(n.nodeName) ?? text(n.name);
    if (!name) continue;
    usage.set(name, { cpuPct: toNumber(n.cpuUsagePercent), memPct: toNumber(n.memoryUsagePercent) });
  }
  // The agent's own per-node metrics win when present.
  for (const n of list(dashboard.nodeUtilization)) {
    const name = text(n.nodeName);
    if (!name) continue;
    usage.set(name, { cpuPct: toNumber(n.cpuUsagePct), memPct: toNumber(n.memUsagePct) });
  }
  return usage;
}

// ── Pods and containers ──────────────────────────────────────────────────────

export interface ClusterContainer {
  name: string;
  image: string | null;
  state: string | null;
  ready: boolean | null;
  restartCount: number;
  waitingReason: string | null;
  cpuRequestM: number | null;
  memRequestMi: number | null;
  cpuLimitM: number | null;
  memLimitMi: number | null;
}

export interface ClusterPod {
  name: string;
  namespace: string;
  phase: string | null;
  nodeName: string | null;
  qosClass: string | null;
  unschedulable: boolean;
  owner: { kind: string; name: string } | null;
  containers: ClusterContainer[];
  restarts: number;
  age: string | null;
  podIp: string | null;
}

function toContainer(c: AnyRecord): ClusterContainer {
  return {
    name: text(c.name) ?? 'container',
    image: text(c.image),
    state: text(c.state),
    ready: typeof c.ready === 'boolean' ? c.ready : null,
    restartCount: toNumber(c.restartCount) ?? 0,
    waitingReason: text(c.waitingReason),
    cpuRequestM: parseCpuMillicores(c.cpuRequest),
    memRequestMi: parseMemoryMiB(c.memoryRequest),
    cpuLimitM: parseCpuMillicores(c.cpuLimit),
    memLimitMi: parseMemoryMiB(c.memoryLimit),
  };
}

/** The cluster's pods: the agent's `k8sPods`, else `workloadHealth.pods`. */
export function clusterPods(dashboard: EksDashboard | null | undefined): ClusterPod[] {
  if (!dashboard) return [];
  const agent = list(dashboard.k8sPods);
  const raw = agent.length > 0 ? agent : list(dashboard.workloadHealth?.pods);
  return raw.flatMap((p) => {
    const name = text(p.name);
    if (!name) return [];
    const containers = list(p.containers ?? p.containerStatuses).map(toContainer);
    const ownerKind = text(p.controllerKind) ?? text(p.ownerKind);
    const ownerName = text(p.controllerName) ?? text(p.ownerName);
    return [{
      name,
      namespace: text(p.namespace) ?? 'default',
      phase: text(p.phase),
      nodeName: text(p.nodeName) ?? text(p.node),
      qosClass: text(p.qosClass),
      unschedulable: p.unschedulable === true,
      owner: ownerKind && ownerName ? { kind: ownerKind, name: ownerName } : null,
      containers,
      restarts: toNumber(p.restarts) ?? containers.reduce((sum, c) => sum + c.restartCount, 0),
      age: text(p.age) ?? text(p.startedAt),
      podIp: text(p.podIp),
    }];
  });
}

/** Requests summed over containers: an unset request adds 0 and is counted (D10). */
export interface RequestSum {
  cpuRequestM: number;
  memRequestMi: number;
  containers: number;
  cpuUnset: number;
  memUnset: number;
}

export function sumRequests(containers: readonly ClusterContainer[]): RequestSum {
  const sum: RequestSum = { cpuRequestM: 0, memRequestMi: 0, containers: 0, cpuUnset: 0, memUnset: 0 };
  for (const c of containers) {
    sum.containers += 1;
    if (c.cpuRequestM === null) sum.cpuUnset += 1;
    else sum.cpuRequestM += c.cpuRequestM;
    if (c.memRequestMi === null) sum.memUnset += 1;
    else sum.memRequestMi += c.memRequestMi;
  }
  return sum;
}

// ── Workloads (§10.4) ────────────────────────────────────────────────────────

export type WorkloadKind = 'Deployment' | 'StatefulSet' | 'DaemonSet';

export const workloadKey = (namespace: string, kind: string, name: string) => `${namespace}/${kind}/${name}`;

export interface WorkloadRequests extends RequestSum {
  pods: number;
  podNames: string[];
}

interface WorkloadRef {
  namespace: string;
  kind: WorkloadKind;
  name: string;
}

/** Deployments listed by the dashboard: the agent's, else the older workloadHealth list. */
function deploymentList(dashboard: EksDashboard): AnyRecord[] {
  const agent = list(dashboard.k8sDeployments);
  return agent.length > 0 ? agent : list(dashboard.workloadHealth?.deployments);
}

/** A pod-template hash: what a Deployment appends to its ReplicaSet's name. */
const TEMPLATE_HASH = /^[a-z0-9]{5,10}$/;

/** The workload a pod belongs to: a ReplicaSet maps to its Deployment by name, the rest directly. */
export function podWorkload(pod: ClusterPod, deploymentsByNamespace: Map<string, string[]>): WorkloadRef | null {
  if (!pod.owner) return null;
  const { kind, name } = pod.owner;
  if (kind === 'StatefulSet' || kind === 'DaemonSet' || kind === 'Deployment') {
    return { namespace: pod.namespace, kind, name };
  }
  if (kind !== 'ReplicaSet') return null;
  const candidates = deploymentsByNamespace.get(pod.namespace) ?? [];
  const match = candidates
    .filter((d) => name.startsWith(`${d}-`) && TEMPLATE_HASH.test(name.slice(d.length + 1)))
    .sort((a, b) => b.length - a.length)[0];
  if (match) return { namespace: pod.namespace, kind: 'Deployment', name: match };
  const cut = name.lastIndexOf('-');
  if (cut > 0 && TEMPLATE_HASH.test(name.slice(cut + 1))) {
    return { namespace: pod.namespace, kind: 'Deployment', name: name.slice(0, cut) };
  }
  return null;
}

function deploymentsByNamespace(dashboard: EksDashboard): Map<string, string[]> {
  const byNs = new Map<string, string[]>();
  for (const d of deploymentList(dashboard)) {
    const ns = text(d.namespace);
    const name = text(d.name);
    if (!ns || !name) continue;
    byNs.set(ns, [...(byNs.get(ns) ?? []), name]);
  }
  return byNs;
}

/**
 * Requests per workload, summed from its pods' containers (deployments carry none, harness §6),
 * keyed by {@link workloadKey}. Unset requests add 0 and are counted separately.
 */
export function aggregateWorkloadRequests(dashboard: EksDashboard | null | undefined): Map<string, WorkloadRequests> {
  const out = new Map<string, WorkloadRequests>();
  if (!dashboard) return out;
  const byNs = deploymentsByNamespace(dashboard);
  for (const pod of clusterPods(dashboard)) {
    const ref = podWorkload(pod, byNs);
    if (!ref) continue;
    const key = workloadKey(ref.namespace, ref.kind, ref.name);
    const sum = sumRequests(pod.containers);
    const entry = out.get(key) ?? { cpuRequestM: 0, memRequestMi: 0, containers: 0, cpuUnset: 0, memUnset: 0, pods: 0, podNames: [] };
    entry.cpuRequestM += sum.cpuRequestM;
    entry.memRequestMi += sum.memRequestMi;
    entry.containers += sum.containers;
    entry.cpuUnset += sum.cpuUnset;
    entry.memUnset += sum.memUnset;
    entry.pods += 1;
    entry.podNames.push(pod.name);
    out.set(key, entry);
  }
  return out;
}

/** CPU usage per pod in millicores from `topCpuPods` (the top 20 pods only); summed over containers. */
export function topPodCpuUsage(dashboard: EksDashboard | null | undefined): Map<string, number> {
  const usage = new Map<string, number>();
  if (!dashboard) return usage;
  for (const p of list(dashboard.topCpuPods)) {
    const name = text(p.name) ?? text(p.podName);
    if (!name) continue;
    const ns = text(p.namespace) ?? '';
    const m = toNumber(p.cpuMillicores) ?? (toNumber(p.value) !== null ? (toNumber(p.value) as number) * 1000 : null);
    if (m === null) continue;
    const key = `${ns}/${name}`;
    usage.set(key, (usage.get(key) ?? 0) + m);
  }
  return usage;
}

/** Memory usage per pod in MiB from `topMemoryPods` (the top 20 pods only). */
export function topPodMemoryUsageMi(dashboard: EksDashboard | null | undefined): Map<string, number> {
  const usage = new Map<string, number>();
  if (!dashboard) return usage;
  for (const p of list(dashboard.topMemoryPods)) {
    const name = text(p.name) ?? text(p.podName);
    const mi = toNumber(p.memoryMB);
    if (!name || mi === null) continue;
    const key = `${text(p.namespace) ?? ''}/${name}`;
    usage.set(key, (usage.get(key) ?? 0) + mi);
  }
  return usage;
}

export const USAGE_TOP_PODS_ONLY = 'Usage is reported for the top 20 pods only';

export interface WorkloadRow {
  key: string;
  kind: WorkloadKind;
  name: string;
  namespace: string;
  ready: number | null;
  desired: number | null;
  /** Null when none of its pods is listed. */
  requests: WorkloadRequests | null;
  /** CPU usage of the workload's running pods, only when every one of them is in `topCpuPods`. */
  cpuUsageM: number | null;
  /** Usage ÷ requests, only when both are known and every container sets a CPU request. */
  efficiencyPct: number | null;
}

function firstNumber(record: AnyRecord, keys: string[]): number | null {
  for (const key of keys) {
    const n = toNumber(record[key]);
    if (n !== null) return n;
  }
  return null;
}

/** One row per Deployment, StatefulSet and DaemonSet the dashboard lists (§10.4). */
export function workloadRows(dashboard: EksDashboard | null | undefined): WorkloadRow[] {
  if (!dashboard) return [];
  const requests = aggregateWorkloadRequests(dashboard);
  const usage = topPodCpuUsage(dashboard);
  const runningByKey = new Map<string, string[]>();
  const byNs = deploymentsByNamespace(dashboard);
  for (const pod of clusterPods(dashboard)) {
    if (pod.phase !== 'Running') continue;
    const ref = podWorkload(pod, byNs);
    if (!ref) continue;
    const key = workloadKey(ref.namespace, ref.kind, ref.name);
    runningByKey.set(key, [...(runningByKey.get(key) ?? []), pod.name]);
  }

  const sources: Array<[WorkloadKind, AnyRecord[], string[], string[]]> = [
    ['Deployment', deploymentList(dashboard), ['readyReplicas', 'ready'], ['replicas', 'desired']],
    ['StatefulSet', list(dashboard.k8sStatefulSets), ['readyReplicas', 'ready'], ['replicas', 'desired']],
    ['DaemonSet', list(dashboard.k8sDaemonSets), ['numberReady', 'readyReplicas', 'ready'], ['desiredNumberScheduled', 'currentNumberScheduled', 'desired']],
  ];
  const rows: WorkloadRow[] = [];
  for (const [kind, items, readyKeys, desiredKeys] of sources) {
    for (const w of items) {
      const name = text(w.name);
      const namespace = text(w.namespace);
      if (!name || !namespace) continue;
      const key = workloadKey(namespace, kind, name);
      const req = requests.get(key) ?? null;
      const running = runningByKey.get(key) ?? [];
      const covered = running.length > 0 && running.every((p) => usage.has(`${namespace}/${p}`));
      const cpuUsageM = covered ? running.reduce((sum, p) => sum + (usage.get(`${namespace}/${p}`) ?? 0), 0) : null;
      const efficiencyPct = cpuUsageM !== null && req && req.cpuUnset === 0 && req.cpuRequestM > 0
        ? (cpuUsageM / req.cpuRequestM) * 100
        : null;
      rows.push({
        key, kind, name, namespace,
        ready: firstNumber(w, readyKeys),
        desired: firstNumber(w, desiredKeys),
        requests: req,
        cpuUsageM,
        efficiencyPct,
      });
    }
  }
  return rows;
}

/** Deployments per namespace, counted from the deployment list (never `namespaces[].deploymentCount`, always 0). */
export function namespaceDeploymentCounts(dashboard: EksDashboard | null | undefined): Map<string, number> {
  const counts = new Map<string, number>();
  if (!dashboard) return counts;
  for (const d of deploymentList(dashboard)) {
    const ns = text(d.namespace);
    if (ns) counts.set(ns, (counts.get(ns) ?? 0) + 1);
  }
  return counts;
}

// ── Cluster usage (D10) ──────────────────────────────────────────────────────

/**
 * The cluster's used CPU or memory %, only when the agent's Prometheus metrics reported usage: the
 * backend leaves 0 in place when nothing did, and an unreported value isn't a measured 0 (D10).
 */
export function clusterUsagePct(dashboard: EksDashboard | null | undefined, resource: 'cpu' | 'memory'): number | null {
  const overview = dashboard?.clusterOverview as AnyRecord | undefined;
  const pct = toNumber(resource === 'cpu' ? overview?.cpuUsagePercent : overview?.memoryUsagePercent);
  const reported = list(dashboard?.nodeUtilization).length > 0 || (pct ?? 0) > 0;
  return reported ? pct : null;
}

// ── Cluster request totals ───────────────────────────────────────────────────

export interface ClusterRequestTotals extends RequestSum {
  /** Sum over nodes, or null when any node doesn't report it. */
  cpuAllocatableM: number | null;
  memAllocatableMi: number | null;
  /** Requested % of allocatable; null without allocatable. */
  cpuRequestedPct: number | null;
  memRequestedPct: number | null;
}

/** Pod requests (scheduled pods) against node allocatable (§10.1). */
export function clusterRequestTotals(dashboard: EksDashboard | null | undefined): ClusterRequestTotals {
  // Pods that hold node capacity: not finished, and not waiting to be scheduled.
  const pods = clusterPods(dashboard).filter((p) =>
    p.phase !== 'Succeeded' && p.phase !== 'Failed' && !(p.phase === 'Pending' && !p.nodeName));
  const sum = sumRequests(pods.flatMap((p) => p.containers));
  const nodes = clusterNodes(dashboard);
  const total = (pick: (n: ClusterNode) => number | null) =>
    nodes.length > 0 && nodes.every((n) => pick(n) !== null) ? nodes.reduce((s, n) => s + (pick(n) as number), 0) : null;
  const cpuAllocatableM = total((n) => n.allocatableCpuM);
  const memAllocatableMi = total((n) => n.allocatableMemMi);
  return {
    ...sum,
    cpuAllocatableM,
    memAllocatableMi,
    cpuRequestedPct: cpuAllocatableM ? (sum.cpuRequestM / cpuAllocatableM) * 100 : null,
    memRequestedPct: memAllocatableMi ? (sum.memRequestMi / memAllocatableMi) * 100 : null,
  };
}

// ── Cluster capacity (Overview, glossary §9.1) ───────────────────────────────

/** One resource, CPU in millicores or memory in MiB; null wherever nobody reported it. */
export interface ResourceCapacity {
  /** Node capacity summed; null when any node doesn't report it. */
  provisioned: number | null;
  /** Node allocatable summed; null when any node doesn't report it. */
  allocatable: number | null;
  /** Provisioned minus allocatable: what the nodes keep for the system. */
  overhead: number | null;
  /** Requests of the pods holding node capacity (clusterRequestTotals). */
  requested: number;
  /** What Prometheus in the cluster measured; null when it reported nothing (never a measured 0). */
  used: number | null;
}

export interface ClusterCapacity {
  cpu: ResourceCapacity;
  memory: ResourceCapacity;
}

/** Provisioned, allocatable, overhead, requested and used for CPU and memory, each from its own source. */
export function clusterCapacity(dashboard: EksDashboard | null | undefined): ClusterCapacity {
  const nodes = clusterNodes(dashboard);
  const totals = clusterRequestTotals(dashboard);
  const sum = (pick: (n: ClusterNode) => number | null) =>
    nodes.length > 0 && nodes.every((n) => pick(n) !== null) ? nodes.reduce((s, n) => s + (pick(n) as number), 0) : null;
  const overview = dashboard?.clusterOverview as AnyRecord | undefined;
  // The backend fills the absolute usage only from the agent's per-node Prometheus metrics, and
  // leaves 0 when there were none: usage counts only when the agent reported it.
  const cpuUsed = clusterUsagePct(dashboard, 'cpu') === null ? null : positive(overview?.cpuUsageMillicores);
  const memBytes = clusterUsagePct(dashboard, 'memory') === null ? null : positive(overview?.memoryUsageBytes);
  const resource = (provisioned: number | null, allocatable: number | null, requested: number, used: number | null): ResourceCapacity => ({
    provisioned,
    allocatable,
    overhead: provisioned !== null && allocatable !== null ? Math.max(0, provisioned - allocatable) : null,
    requested,
    used,
  });
  return {
    cpu: resource(sum((n) => n.capacityCpuM), totals.cpuAllocatableM, totals.cpuRequestM, cpuUsed),
    memory: resource(sum((n) => n.capacityMemMi), totals.memAllocatableMi, totals.memRequestMi, memBytes === null ? null : memBytes / 1024 ** 2),
  };
}

/** Nodes by capacity type (glossary §9.2): a node without a capacity-type label, or another value, is Unknown. */
export function capacityTypeCounts(nodes: readonly ClusterNode[]): { onDemand: number; spot: number; unknown: number } {
  const onDemand = nodes.filter((n) => n.capacityType === 'on-demand').length;
  const spot = nodes.filter((n) => n.capacityType === 'spot').length;
  return { onDemand, spot, unknown: nodes.length - onDemand - spot };
}

export interface InstanceTypeCount {
  type: string;
  count: number;
  /** One node's capacity, as the first node of the type reports it. */
  cpuM: number | null;
  memMi: number | null;
}

/** Instance types by node count, most nodes first; nodes without an instance type are left out. */
export function instanceTypeCounts(nodes: readonly ClusterNode[]): InstanceTypeCount[] {
  const byType = new Map<string, InstanceTypeCount>();
  for (const n of nodes) {
    if (!n.instanceType) continue;
    const entry = byType.get(n.instanceType) ?? { type: n.instanceType, count: 0, cpuM: n.capacityCpuM, memMi: n.capacityMemMi };
    entry.count += 1;
    byType.set(n.instanceType, entry);
  }
  return [...byType.values()].sort((a, b) => b.count - a.count || a.type.localeCompare(b.type));
}

/** The most allocatable CPU and memory any one node has; null when no node reports it. */
export function largestNodeAllocatable(nodes: readonly ClusterNode[]): { cpuM: number | null; memMi: number | null } {
  const max = (values: Array<number | null>) => {
    const known = values.filter((v): v is number => v !== null);
    return known.length > 0 ? Math.max(...known) : null;
  };
  return { cpuM: max(nodes.map((n) => n.allocatableCpuM)), memMi: max(nodes.map((n) => n.allocatableMemMi)) };
}

export interface PodPlacement {
  total: number;
  /** Scheduled and not finished: "running on a node" (glossary §9.2). */
  onNode: number;
  /** Pending without a node: "waiting for a node". */
  waiting: number;
  failed: number;
  completed: number;
  /** Pending pods the scheduler reported as Unschedulable, largest CPU ask first. */
  unschedulable: ClusterPod[];
}

/** Where the pods are, from the one pod list; null when the dashboard carries no pods. */
export function podPlacement(dashboard: EksDashboard | null | undefined): PodPlacement | null {
  const pods = clusterPods(dashboard);
  if (pods.length === 0) return null;
  const finished = (p: ClusterPod) => p.phase === 'Succeeded' || p.phase === 'Failed';
  const cpuAsk = (p: ClusterPod) => sumRequests(p.containers).cpuRequestM;
  return {
    total: pods.length,
    onNode: pods.filter((p) => p.nodeName && !finished(p)).length,
    waiting: pods.filter((p) => p.phase === 'Pending' && !p.nodeName).length,
    failed: pods.filter((p) => p.phase === 'Failed').length,
    completed: pods.filter((p) => p.phase === 'Succeeded').length,
    unschedulable: pods.filter((p) => p.unschedulable).sort((a, b) => cpuAsk(b) - cpuAsk(a)),
  };
}

/**
 * Workloads that have pods, none Running, all Pending and reported Unschedulable: the glossary's
 * "Not running" (§2 rule 1a), keyed by {@link workloadKey}.
 */
export function notRunningWorkloads(dashboard: EksDashboard | null | undefined): Set<string> {
  const out = new Set<string>();
  if (!dashboard) return out;
  const byNs = deploymentsByNamespace(dashboard);
  const byWorkload = new Map<string, ClusterPod[]>();
  for (const pod of clusterPods(dashboard)) {
    const ref = podWorkload(pod, byNs);
    if (!ref) continue;
    const key = workloadKey(ref.namespace, ref.kind, ref.name);
    byWorkload.set(key, [...(byWorkload.get(key) ?? []), pod]);
  }
  for (const [key, pods] of byWorkload) {
    if (pods.every((p) => p.phase === 'Pending' && p.unschedulable)) out.add(key);
  }
  return out;
}

export interface NodeProvisioners {
  /** Karpenter's own data was reported, or nodes carry its NodePool label. */
  karpenter: 'reported' | 'labels' | null;
  /** A Deployment named cluster-autoscaler is running in the cluster. */
  clusterAutoscaler: boolean;
}

/** Who adds and removes nodes in this cluster, as far as the agent's data shows. */
export function nodeProvisioners(dashboard: EksDashboard | null | undefined): NodeProvisioners {
  if (!dashboard) return { karpenter: null, clusterAutoscaler: false };
  const karpenter = dashboard.karpenter ? 'reported' : nodeGroups(dashboard).some((g) => g.type === 'Karpenter NodePool') ? 'labels' : null;
  const clusterAutoscaler = deploymentList(dashboard).some((d) => /(^|-)cluster-autoscaler($|-)/.test(text(d.name) ?? ''));
  return { karpenter, clusterAutoscaler };
}

export interface HpaState {
  name: string;
  namespace: string;
  current: number | null;
  max: number | null;
  /** Running its maximum number of replicas. */
  atMax: boolean;
}

/** The HPAs the agent reported; null when it reported no HPA list at all (not the same as none). */
export function hpaStates(dashboard: EksDashboard | null | undefined): HpaState[] | null {
  if (!Array.isArray(dashboard?.k8sHpas)) return null;
  return list(dashboard?.k8sHpas).flatMap((h) => {
    const name = text(h.name);
    if (!name) return [];
    const current = toNumber(h.currentReplicas);
    const max = toNumber(h.maxReplicas);
    return [{ name, namespace: text(h.namespace) ?? 'default', current, max, atMax: current !== null && max !== null && current >= max }];
  });
}

// ── Node groups (§10.5) ──────────────────────────────────────────────────────

export type NodeGroupType = 'Managed node group' | 'Karpenter NodePool' | 'Unmanaged';

export interface NodeGroup {
  key: string;
  name: string;
  type: NodeGroupType;
  nodes: number;
  ready: number;
  instanceTypes: string[];
  onDemand: number;
  spot: number;
  /** Nodes whose capacity type nothing reports. */
  capacityUnknown: number;
  zones: string[];
  allocatableCpuM: number | null;
  allocatableMemMi: number | null;
  cpuRequestedPct: number | null;
  memRequestedPct: number | null;
}

export const UNMANAGED_GROUP = 'Unmanaged';

/**
 * Nodes grouped by `eks.amazonaws.com/nodegroup`, then `karpenter.sh/nodepool`, else "Unmanaged",
 * with requests from the pods scheduled on each group's nodes. No AWS call: labels only.
 */
export function nodeGroups(dashboard: EksDashboard | null | undefined): NodeGroup[] {
  const nodes = clusterNodes(dashboard);
  const requestsByNode = new Map<string, RequestSum>();
  for (const pod of clusterPods(dashboard)) {
    if (!pod.nodeName || pod.phase === 'Succeeded' || pod.phase === 'Failed') continue;
    const prev = requestsByNode.get(pod.nodeName);
    const sum = sumRequests(pod.containers);
    requestsByNode.set(pod.nodeName, prev
      ? { cpuRequestM: prev.cpuRequestM + sum.cpuRequestM, memRequestMi: prev.memRequestMi + sum.memRequestMi,
          containers: prev.containers + sum.containers, cpuUnset: prev.cpuUnset + sum.cpuUnset, memUnset: prev.memUnset + sum.memUnset }
      : sum);
  }

  const groups = new Map<string, { name: string; type: NodeGroupType; members: ClusterNode[] }>();
  for (const node of nodes) {
    const managed = node.labels[NODE_GROUP_LABEL];
    const pool = node.labels[NODE_POOL_LABEL];
    const [type, name]: [NodeGroupType, string] = managed
      ? ['Managed node group', managed]
      : pool ? ['Karpenter NodePool', pool] : ['Unmanaged', UNMANAGED_GROUP];
    const key = `${type}/${name}`;
    const group = groups.get(key) ?? { name, type, members: [] };
    group.members.push(node);
    groups.set(key, group);
  }

  const order: Record<NodeGroupType, number> = { 'Managed node group': 0, 'Karpenter NodePool': 1, Unmanaged: 2 };
  return [...groups.entries()]
    .map(([key, g]) => {
      const sumOf = (pick: (n: ClusterNode) => number | null) =>
        g.members.every((n) => pick(n) !== null) ? g.members.reduce((s, n) => s + (pick(n) as number), 0) : null;
      const allocatableCpuM = sumOf((n) => n.allocatableCpuM);
      const allocatableMemMi = sumOf((n) => n.allocatableMemMi);
      const requested = g.members.reduce(
        (acc, n) => {
          const r = requestsByNode.get(n.name);
          return r ? { cpu: acc.cpu + r.cpuRequestM, mem: acc.mem + r.memRequestMi } : acc;
        },
        { cpu: 0, mem: 0 },
      );
      return {
        key,
        name: g.name,
        type: g.type,
        nodes: g.members.length,
        ready: g.members.filter((n) => n.ready === true).length,
        instanceTypes: [...new Set(g.members.map((n) => n.instanceType).filter((t): t is string => !!t))].sort(),
        onDemand: g.members.filter((n) => n.capacityType === 'on-demand').length,
        spot: g.members.filter((n) => n.capacityType === 'spot').length,
        capacityUnknown: g.members.filter((n) => n.capacityType === null).length,
        zones: [...new Set(g.members.map((n) => n.zone).filter((z): z is string => !!z))].sort(),
        allocatableCpuM,
        allocatableMemMi,
        cpuRequestedPct: allocatableCpuM ? (requested.cpu / allocatableCpuM) * 100 : null,
        memRequestedPct: allocatableMemMi ? (requested.mem / allocatableMemMi) * 100 : null,
      };
    })
    .sort((a, b) => order[a.type] - order[b.type] || a.name.localeCompare(b.name));
}

// ── Node list (§10.5) ────────────────────────────────────────────────────────

/** Ready, Not ready, Cordoned (unschedulable, counted apart even when Ready), or Unknown. */
export type NodeStatus = 'ready' | 'not-ready' | 'cordoned' | 'unknown';

export interface NodeRow extends ClusterNode {
  status: NodeStatus;
  /** The node's managed node group or Karpenter NodePool, from its labels; null when neither. */
  group: string | null;
  /** Requests of the pods scheduled on the node that haven't finished. */
  cpuRequestM: number;
  memRequestMi: number;
  pods: number;
  /** Requested % of allocatable; null when the node doesn't report allocatable. */
  cpuRequestedPct: number | null;
  memRequestedPct: number | null;
}

export function nodeStatus(node: Pick<ClusterNode, 'ready' | 'unschedulable'>): NodeStatus {
  if (node.unschedulable) return 'cordoned';
  if (node.ready === true) return 'ready';
  if (node.ready === false) return 'not-ready';
  return 'unknown';
}

/** One row per node with the requests of the pods on it, against its allocatable. */
export function nodeRows(dashboard: EksDashboard | null | undefined): NodeRow[] {
  const byNode = new Map<string, { cpu: number; mem: number; pods: number }>();
  for (const pod of clusterPods(dashboard)) {
    if (!pod.nodeName || pod.phase === 'Succeeded' || pod.phase === 'Failed') continue;
    const sum = sumRequests(pod.containers);
    const prev = byNode.get(pod.nodeName) ?? { cpu: 0, mem: 0, pods: 0 };
    byNode.set(pod.nodeName, { cpu: prev.cpu + sum.cpuRequestM, mem: prev.mem + sum.memRequestMi, pods: prev.pods + 1 });
  }
  return clusterNodes(dashboard).map((node) => {
    const r = byNode.get(node.name) ?? { cpu: 0, mem: 0, pods: 0 };
    return {
      ...node,
      status: nodeStatus(node),
      group: node.labels[NODE_GROUP_LABEL] ?? node.labels[NODE_POOL_LABEL] ?? null,
      cpuRequestM: r.cpu,
      memRequestMi: r.mem,
      pods: r.pods,
      cpuRequestedPct: node.allocatableCpuM ? (r.cpu / node.allocatableCpuM) * 100 : null,
      memRequestedPct: node.allocatableMemMi ? (r.mem / node.allocatableMemMi) * 100 : null,
    };
  });
}

// ── Health signals (D10) ─────────────────────────────────────────────────────

export type HealthSignalId = 'nodes-ready' | 'node-pressure' | 'pods' | 'unschedulable';

export interface HealthSignal {
  id: HealthSignalId;
  label: string;
  /** The count shown, e.g. "6 / 6" or "None"; "—" when the dashboard reports nothing for it. */
  value: string;
  attention: boolean;
  detail: string | null;
  known: boolean;
}

const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;

export interface PodCounts {
  running: number;
  total: number;
  pending: number;
  failed: number;
  /** The pod list, or the overview's own counts when the dashboard carries no pods. */
  basis: 'pods' | 'overview';
}

/**
 * Running, pending and failed pods against the total, all from one source so running can never
 * exceed the total: the pod list when there is one, else the overview's own counts (the agent
 * derives both from one pod list). Null when neither reports running and total. Mixing the two, the
 * overview's running count over the pod list's length, is how the old page showed "52/18".
 */
export function podCounts(dashboard: EksDashboard | null | undefined): PodCounts | null {
  const pods = clusterPods(dashboard);
  if (pods.length > 0) {
    const phase = (p: string) => pods.filter((pod) => pod.phase === p).length;
    return { running: phase('Running'), total: pods.length, pending: phase('Pending'), failed: phase('Failed'), basis: 'pods' };
  }
  const overview = dashboard?.clusterOverview as AnyRecord | undefined;
  const running = toNumber(overview?.runningPods);
  const total = toNumber(overview?.totalPods);
  if (running === null || total === null) return null;
  return {
    running, total, pending: toNumber(overview?.pendingPods) ?? 0, failed: toNumber(overview?.failedPods) ?? 0, basis: 'overview',
  };
}

/** Rule-based signals, each from data with its own count; never a score (D10). */
export function healthSignals(dashboard: EksDashboard | null | undefined): HealthSignal[] {
  const nodes = clusterNodes(dashboard);
  const pods = clusterPods(dashboard);

  const notReady = nodes.filter((n) => n.ready === false).length;
  const readyCount = nodes.filter((n) => n.ready === true).length;
  const nodesSignal: HealthSignal = nodes.length === 0
    ? { id: 'nodes-ready', label: 'Nodes ready', value: '—', attention: false, detail: null, known: false }
    : {
        id: 'nodes-ready', label: 'Nodes ready', value: `${readyCount} / ${nodes.length}`,
        attention: notReady > 0, detail: notReady > 0 ? `${plural(notReady, 'node')} not ready` : null, known: true,
      };

  const pressured = nodes.filter((n) => n.pressure.memory || n.pressure.disk || n.pressure.pid);
  const kinds = [
    pressured.some((n) => n.pressure.memory) && 'memory',
    pressured.some((n) => n.pressure.disk) && 'disk',
    pressured.some((n) => n.pressure.pid) && 'PID',
  ].filter(Boolean).join(', ');
  const pressureSignal: HealthSignal = nodes.length === 0
    ? { id: 'node-pressure', label: 'Node pressure', value: '—', attention: false, detail: null, known: false }
    : {
        id: 'node-pressure', label: 'Node pressure', value: pressured.length ? plural(pressured.length, 'node') : 'None',
        attention: pressured.length > 0, detail: pressured.length ? `Under ${kinds} pressure` : null, known: true,
      };

  const counts = podCounts(dashboard);
  const failed = counts?.failed ?? 0;
  const crashLooping = pods.flatMap((p) => p.containers).filter((c) => c.waitingReason === 'CrashLoopBackOff').length;
  const podDetail = [failed > 0 && `${failed} failed`, crashLooping > 0 && `${plural(crashLooping, 'container')} crash-looping`]
    .filter(Boolean).join(' · ');
  const podsSignal: HealthSignal = counts === null
    ? { id: 'pods', label: 'Pods running', value: '—', attention: false, detail: null, known: false }
    : {
        id: 'pods', label: 'Pods running', value: `${counts.running} / ${counts.total}`,
        attention: failed > 0 || crashLooping > 0, detail: podDetail || null, known: true,
      };

  const unschedulable = pods.filter((p) => p.unschedulable).length;
  const pendingSchedulable = pods.filter((p) => p.phase === 'Pending' && !p.unschedulable).length;
  const unschedulableSignal: HealthSignal = pods.length === 0
    ? { id: 'unschedulable', label: 'Unschedulable pods', value: '—', attention: false, detail: null, known: false }
    : {
        id: 'unschedulable', label: 'Unschedulable pods', value: unschedulable ? String(unschedulable) : 'None',
        attention: unschedulable > 0,
        detail: pendingSchedulable > 0 ? `${pendingSchedulable} pending, schedulable` : null,
        known: true,
      };

  return [nodesSignal, pressureSignal, podsSignal, unschedulableSignal];
}

export type OverallHealthKind = 'healthy' | 'attention' | 'stale' | 'not-monitored' | 'unknown';

export interface OverallHealth {
  kind: OverallHealthKind;
  /** "Healthy", "Needs attention (2)", "Data stale", "Not monitored", "Unknown". */
  label: string;
  attentionCount: number;
}

/** The status strip's health (D10): monitoring first, then the attention signals. */
export function overallHealth(monitoring: MonitoringState | null, signals: readonly HealthSignal[]): OverallHealth {
  const attentionCount = signals.filter((s) => s.attention).length;
  if (monitoring === 'M0') return { kind: 'not-monitored', label: 'Not monitored', attentionCount: 0 };
  if (monitoring === 'M2') return { kind: 'stale', label: 'Data stale', attentionCount };
  if (attentionCount > 0) return { kind: 'attention', label: `Needs attention (${attentionCount})`, attentionCount };
  if (!signals.some((s) => s.known)) return { kind: 'unknown', label: 'Unknown', attentionCount: 0 };
  return { kind: 'healthy', label: 'Healthy', attentionCount: 0 };
}

// ── Cost (D9) ────────────────────────────────────────────────────────────────

export type CostSourceKind = 'opencost' | 'agent-exporter' | 'prometheus-exporter' | 'none';

export const COST_SOURCE_LABELS: Record<CostSourceKind, string> = {
  opencost: 'OpenCost (agent)',
  'agent-exporter': 'Cost exporter (agent)',
  'prometheus-exporter': 'XamOps cost exporter (Prometheus)',
  none: 'No cost source',
};

export const MONTHLY_FROM_DAILY = 'Monthly = daily × 30';

export interface ClusterCost {
  kind: CostSourceKind;
  label: string;
  dailyCost: number | null;
  monthlyCost: number | null;
  /** The backend sent no monthly figure, so it is daily × 30 (its own convention). */
  monthlyFromDaily: boolean;
}

const positive = (value: unknown): number | null => {
  const n = toNumber(value);
  return n !== null && n > 0 ? n : null;
};

/** The cluster cost and the source beside it, first match wins (D9). */
export function clusterCostSource(dashboard: EksDashboard | null | undefined): ClusterCost {
  const openCost = dashboard?.openCost;
  const agentCost = dashboard?.agentCostMetrics as AnyRecord | null | undefined;
  const cost = dashboard?.costMetrics;
  const monthly = (daily: number, reported: number | null): Pick<ClusterCost, 'monthlyCost' | 'monthlyFromDaily'> =>
    reported !== null ? { monthlyCost: reported, monthlyFromDaily: false } : { monthlyCost: daily * 30, monthlyFromDaily: true };

  const openDaily = openCost?.enabled === true ? positive(openCost.summary?.totalDailyCost) : null;
  if (openDaily !== null) {
    return { kind: 'opencost', label: COST_SOURCE_LABELS.opencost, dailyCost: openDaily, ...monthly(openDaily, positive(openCost?.summary?.totalMonthlyCost)) };
  }
  const hourly = positive(agentCost?.totalHourlyCost);
  if (hourly !== null) {
    const daily = positive(cost?.dailyCost) ?? hourly * 24;
    return { kind: 'agent-exporter', label: COST_SOURCE_LABELS['agent-exporter'], dailyCost: daily, ...monthly(daily, positive(cost?.monthlyCost)) };
  }
  const exporterDaily = positive(cost?.dailyCost);
  if (exporterDaily !== null) {
    return {
      kind: 'prometheus-exporter', label: COST_SOURCE_LABELS['prometheus-exporter'], dailyCost: exporterDaily,
      ...monthly(exporterDaily, positive(cost?.monthlyCost)),
    };
  }
  return { kind: 'none', label: COST_SOURCE_LABELS.none, dailyCost: null, monthlyCost: null, monthlyFromDaily: false };
}

export interface UnallocatedCost {
  daily: number;
  cpuIdleFraction: number;
  memIdleFraction: number;
}

export const UNALLOCATED_TOOLTIP =
  'Cost of CPU and memory no pod has requested (OpenCost-style idle). Source: cost exporter + pod requests.';

/**
 * Unallocated capacity cost (D9): only when the cost exporter reports the CPU/RAM split and both
 * requests and allocatable are known. `cpuHourly × (1 − requested/allocatable) + memHourly × (…)`, × 24.
 */
export function unallocatedCapacityCost(
  dashboard: EksDashboard | null | undefined,
  totals: Pick<ClusterRequestTotals, 'cpuRequestM' | 'memRequestMi' | 'cpuAllocatableM' | 'memAllocatableMi'> = clusterRequestTotals(dashboard),
): UnallocatedCost | null {
  const agentCost = dashboard?.agentCostMetrics as AnyRecord | null | undefined;
  const cpuHourly = toNumber(agentCost?.cpuHourlyCost);
  const memHourly = toNumber(agentCost?.memoryHourlyCost);
  if (cpuHourly === null || memHourly === null || cpuHourly < 0 || memHourly < 0) return null;
  if (!totals.cpuAllocatableM || !totals.memAllocatableMi) return null;
  const idle = (requested: number, allocatable: number) => Math.min(1, Math.max(0, 1 - requested / allocatable));
  const cpuIdleFraction = idle(totals.cpuRequestM, totals.cpuAllocatableM);
  const memIdleFraction = idle(totals.memRequestMi, totals.memAllocatableMi);
  return { daily: (cpuHourly * cpuIdleFraction + memHourly * memIdleFraction) * 24, cpuIdleFraction, memIdleFraction };
}

// ── Vertical recommendations: HPA conflicts, QoS, savings (D8, D9, D16) ──────

export interface HpaConflict {
  hpaName: string;
  cpuTargetPct: number;
  message: string;
}

/** HPAs that target a workload, matched on namespace, kind and name. */
export function hpasFor(dashboard: EksDashboard | null | undefined, ref: { namespace: string; kind: string; name: string }): AnyRecord[] {
  return list(dashboard?.k8sHpas).filter((h) =>
    h.namespace === ref.namespace
    && (text(h.targetKind) ?? 'Deployment') === ref.kind
    && (text(h.targetName) ?? text(h.targetRef)) === ref.name);
}

/**
 * The UI's second line of defence for HPAs (D8): a workload whose HPA scales on CPU, while the
 * recommendation changes the CPU request without the engine's `HPA_CPU_MANAGED` hold.
 */
export function hpaConflict(workload: VerticalWorkload, hpas: readonly AnyRecord[]): HpaConflict | null {
  const changesCpu = workload.containers.some((c) =>
    c.recommended !== null && c.recommended.cpuRequestM !== c.current.cpuRequestM && !c.reasons.includes('HPA_CPU_MANAGED'));
  if (!changesCpu) return null;
  for (const h of hpas) {
    const target = toNumber(h.cpuTargetPct) ?? toNumber(h.targetCPUUtilization) ?? toNumber(h.targetCPUPercent);
    if (target !== null && target > 0) {
      const hpaName = text(h.name) ?? 'HPA';
      return {
        hpaName,
        cpuTargetPct: target,
        message: `HPA ${hpaName} scales on CPU at ${target}% of requests. Changing requests moves its scaling point.`,
      };
    }
  }
  return null;
}

export type QosClass = 'Guaranteed' | 'Burstable' | 'BestEffort';

/** A container's QoS from its resources: Guaranteed when both requests are set and equal their limits. */
export function qosClassOf(r: Resources): QosClass {
  const values = [r.cpuRequestM, r.memRequestMi, r.cpuLimitM, r.memLimitMi];
  if (values.every((v) => v === null)) return 'BestEffort';
  if (r.cpuRequestM !== null && r.memRequestMi !== null && r.cpuRequestM === r.cpuLimitM && r.memRequestMi === r.memLimitMi) {
    return 'Guaranteed';
  }
  return 'Burstable';
}

/** "Guaranteed → Burstable" and the like when the recommendation changes the class; null otherwise. */
export function qosChange(qosClass: string | null, current: Resources, recommended: Resources | null): { from: string; to: QosClass } | null {
  if (!recommended) return null;
  const from = qosClass ?? qosClassOf(current);
  const to = qosClassOf(recommended);
  return from === to ? null : { from, to };
}

export type SavingsExclusion = 'skipped' | 'requests-unset' | 'invalid-value' | 'below-floor' | 'hpa-conflict' | 'replicas-unknown';

export const SAVINGS_EXCLUSION_LABELS: Record<SavingsExclusion, string> = {
  skipped: 'skipped',
  'requests-unset': 'requests unset',
  'invalid-value': 'invalid value reported',
  'below-floor': 'below safe floor',
  'hpa-conflict': 'HPA conflict',
  'replicas-unknown': 'replica count unknown',
};

export interface WorkloadSavings {
  eligible: boolean;
  exclusion: SavingsExclusion | null;
  replicas: number | null;
  /** Estimated monthly USD from decreases only; 0 when nothing decreases. */
  monthlyUsd: number;
  /** A recommendation raises a request: listed as "Needs more", never netted against savings. */
  needsMore: boolean;
  /** Containers that contributed a decrease. */
  contributing: number;
}

const HOURS_PER_MONTH = 24 * 30;

/**
 * Estimated savings from one engine recommendation (D9). Excluded: skipped, requests unset, an
 * invalid value, below the safe floor, an HPA conflict (the engine's or the dashboard's), or no
 * known replica count (the engine's, else the dashboard's). Per container, a set current request
 * and a non-null recommendation; `recommended: null` contributes nothing.
 */
export function workloadSavings(
  workload: VerticalWorkload,
  ctx: { replicasFallback?: number | null; hpaConflict?: boolean } = {},
): WorkloadSavings {
  const replicas = workload.replicas ?? ctx.replicasFallback ?? null;
  const excluded = (exclusion: SavingsExclusion): WorkloadSavings =>
    ({ eligible: false, exclusion, replicas, monthlyUsd: 0, needsMore: false, contributing: 0 });
  if (workload.skipReason !== null) return excluded('skipped');
  if (workload.findings.includes('REQUESTS_UNSET')) return excluded('requests-unset');
  if (workload.invalidValue) return excluded('invalid-value');
  if (workload.belowFloor) return excluded('below-floor');
  if (workload.hpaConflict || ctx.hpaConflict) return excluded('hpa-conflict');
  if (replicas === null) return excluded('replicas-unknown');

  let hourly = 0;
  let needsMore = false;
  let contributing = 0;
  for (const c of workload.containers) {
    const cur = c.current;
    const rec = c.recommended;
    if (!rec || cur.cpuRequestM === null || cur.memRequestMi === null) continue;
    const cpuDown = rec.cpuRequestM !== null ? Math.max(0, cur.cpuRequestM - rec.cpuRequestM) : 0;
    const memDown = rec.memRequestMi !== null ? Math.max(0, cur.memRequestMi - rec.memRequestMi) : 0;
    if ((rec.cpuRequestM ?? 0) > cur.cpuRequestM || (rec.memRequestMi ?? 0) > cur.memRequestMi) needsMore = true;
    if (cpuDown > 0 || memDown > 0) contributing += 1;
    hourly += (cpuDown / 1000) * CPU_RATE_PER_VCPU_HOUR + (memDown / 1024) * MEMORY_RATE_PER_GIB_HOUR;
  }
  return { eligible: true, exclusion: null, replicas, monthlyUsd: hourly * replicas * HOURS_PER_MONTH, needsMore, contributing };
}

export interface FreedCapacity {
  cpuM: number;
  memMi: number;
  /** Workloads that contributed. */
  workloads: number;
  /** Workloads left out because neither the engine nor the dashboard knows their replicas. */
  replicasUnknown: number;
}

/**
 * Capacity, not dollars, that the given workloads' suggestions would free (glossary §2 rule 6,
 * §9.3 "Freed capacity"): per container, each request that goes down, times the replicas.
 */
export function freedCapacity(workloads: readonly VerticalWorkload[], dashboard: EksDashboard | null | undefined): FreedCapacity {
  const out: FreedCapacity = { cpuM: 0, memMi: 0, workloads: 0, replicasUnknown: 0 };
  for (const w of workloads) {
    const replicas = w.replicas ?? dashboardReplicas(dashboard, w.ref);
    if (replicas === null) {
      out.replicasUnknown += 1;
      continue;
    }
    let cpu = 0;
    let mem = 0;
    for (const c of w.containers) {
      const rec = c.recommended;
      if (!rec) continue;
      if (c.current.cpuRequestM !== null && rec.cpuRequestM !== null) cpu += Math.max(0, c.current.cpuRequestM - rec.cpuRequestM);
      if (c.current.memRequestMi !== null && rec.memRequestMi !== null) mem += Math.max(0, c.current.memRequestMi - rec.memRequestMi);
    }
    if (cpu > 0 || mem > 0) out.workloads += 1;
    out.cpuM += cpu * replicas;
    out.memMi += mem * replicas;
  }
  return out;
}

/** Replicas the dashboard reports for a workload, for savings when the engine sends none. */
export function dashboardReplicas(dashboard: EksDashboard | null | undefined, ref: { namespace: string; kind: string; name: string }): number | null {
  if (!dashboard) return null;
  const source = ref.kind === 'StatefulSet' ? list(dashboard.k8sStatefulSets)
    : ref.kind === 'Deployment' ? deploymentList(dashboard) : [];
  const found = source.find((w) => w.namespace === ref.namespace && w.name === ref.name);
  return found ? firstNumber(found, ['replicas', 'desired']) : null;
}
