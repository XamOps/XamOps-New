/**
 * Wire → view adapter for the vertical rightsizer payload.
 *
 * Kept separate from the fetch client so the rules that matter can be tested without a network:
 *
 *  1. `null` in a resources field means **unset** and renders `—`.
 *  2. `0` in a resources field is a **backend bug**, not "no request". It renders `—` as well — a
 *     `0` on screen would read as "a request of zero", which Kubernetes does not express — and it is
 *     recorded in `anomalies` so the payload is not quietly believed.
 *  3. An unrecognised reason / finding / gate code renders verbatim, which the published contract
 *     allows so the backend's enums can grow without a frontend change.
 */

import type {
  ApplyContainerRequest,
  CodeView,
  ContainerView,
  EngineView,
  MetricValue,
  ProvenanceView,
  ResourcesView,
  UpstreamVpaView,
  VerticalContainerWire,
  VerticalEngineResponseWire,
  VerticalEngineView,
  VerticalResourcesWire,
  VerticalWorkloadWire,
  WorkloadView,
} from '../types/verticalRightsizer';

/** Rendered for anything the engine did not measure. Never `0`, never a guess. */
export const DASH = '—';

/**
 * The nine shared gate codes plus the engine-internal enums, as published. The engine emits them
 * without the `K8S_` prefix inside payload bodies and with the prefix in a refusal's `code`, so both
 * directions are parsed.
 */
const CODE_LABELS: Record<string, string> = {
  // shared gate codes
  READ_ONLY_CONNECTION: 'Read-only connection',
  NO_USER_PERMISSION: 'No permission',
  CONTROLLER_NOT_READY: 'Automation not enabled',
  DEMO: 'Demo mode',
  CLUSTER_ACCESS_NOT_CONFIGURED: 'Cluster access not configured',
  CLUSTER_UNREACHABLE: 'Cluster unreachable',
  NO_METRICS_SOURCE: 'No metrics source',
  INSUFFICIENT_HISTORY: 'Not enough history',
  GITOPS_MANAGED: 'GitOps managed',
  // non-gate codes the UI may still be handed
  CLUSTER_NOT_REGISTERED: 'Cluster not registered',
  NO_ACCOUNT_ACCESS: 'No access to this account',
  AGENT_NOT_INSTALLED: 'Agent not installed',
  // skip reasons
  PROTECTED_NAMESPACE: 'Protected namespace',
  SINGLE_REPLICA: 'Single replica',
  ROLLOUT_IN_PROGRESS: 'Rollout in progress',
  LOCKED: 'Locked',
  HPA_VOLATILE: 'HPA volatile',
  POLICY_OFF: 'Policy off',
  // findings
  REQUESTS_UNSET: 'Requests unset',
  IDLE: 'Idle',
  THROTTLED: 'Throttled',
  OOM_RISK: 'OOM risk',
  HPA_COEXISTENCE: 'HPA present',
  NODE_CAPACITY_CAPPED: 'Capped by node capacity',
  VPA_NOT_READABLE: 'VPA not readable',
  // container reasons
  HPA_CPU_MANAGED: 'CPU managed by HPA',
  HPA_MEMORY_MANAGED: 'Memory managed by HPA',
  FLOOR_CLAMPED: 'Clamped to floor',
  BELOW_FLOOR_NO_RAISE: 'Below floor, not raised',
  LIMIT_MOVED: 'Limit moved',
  DEMAND_CENSORED_BY_LIMIT: 'Demand censored by limit',
  QOS_PRESERVED_NO_CHANGE: 'QoS preserved',
  COOLDOWN: 'Waiting out cooldown',
  WITHIN_HYSTERESIS: 'Already the right size',
};

/** Strips the refusal prefix and resolves a label, or keeps the raw string verbatim. */
export const parseCode = (raw: string | null | undefined): CodeView | null => {
  if (!raw) return null;
  const trimmed = raw.trim();
  if (!trimmed) return null;
  const code = trimmed.startsWith('K8S_') ? trimmed.slice('K8S_'.length) : trimmed;
  const label = CODE_LABELS[code];
  return label ? { kind: 'known', code, label } : { kind: 'unknown', raw: trimmed };
};

export const parseCodes = (raw: string[] | null | undefined): CodeView[] =>
  (raw ?? []).map(parseCode).filter((c): c is CodeView => c !== null);

/** The text to print for a code: the label when known, the raw value when not. */
export const codeText = (code: CodeView): string =>
  code.kind === 'known' ? code.label : code.raw;

const toMetric = (raw: number | null | undefined, anomalies: string[], where: string): MetricValue => {
  if (raw === null || raw === undefined) return { kind: 'unset' };
  if (!Number.isFinite(raw)) return { kind: 'unset' };
  if (raw === 0) {
    anomalies.push(`${where} was 0; Kubernetes cannot express a request of zero, so this value is unusable`);
    return { kind: 'bug', raw };
  }
  return { kind: 'value', value: raw };
};

const adaptResources = (
  wire: VerticalResourcesWire | null | undefined,
  anomalies: string[],
  where: string,
): ResourcesView => ({
  cpuRequestM: toMetric(wire?.cpuRequestM, anomalies, `${where} cpuRequestM`),
  memRequestMi: toMetric(wire?.memRequestMi, anomalies, `${where} memRequestMi`),
  cpuLimitM: toMetric(wire?.cpuLimitM, anomalies, `${where} cpuLimitM`),
  memLimitMi: toMetric(wire?.memLimitMi, anomalies, `${where} memLimitMi`),
});

const adaptUpstreamVpa = (
  container: VerticalContainerWire,
  anomalies: string[],
  where: string,
): UpstreamVpaView | null => {
  const vpa = container.upstreamVpa;
  if (!vpa) return null;
  return {
    cpuRequestM: toMetric(vpa.cpuRequestM, anomalies, `${where} upstreamVpa.cpuRequestM`),
    memRequestMi: toMetric(vpa.memRequestMi, anomalies, `${where} upstreamVpa.memRequestMi`),
    source: vpa.source ?? null,
    observedAt: vpa.observedAt ?? null,
  };
};

const adaptContainer = (
  container: VerticalContainerWire,
  anomalies: string[],
  where: string,
): ContainerView => ({
  name: container.name,
  qosClass: container.qosClass ?? null,
  current: adaptResources(container.current, anomalies, `${where} current`),
  recommended: container.recommended
    ? adaptResources(container.recommended, anomalies, `${where} recommended`)
    : null,
  reasons: parseCodes(container.reasons),
  upstreamVpa: adaptUpstreamVpa(container, anomalies, where),
});

const adaptProvenance = (workload: VerticalWorkloadWire): ProvenanceView | null => {
  const p = workload.provenance;
  if (!p) return null;
  return {
    percentile: p.percentile ?? null,
    windowHours: p.windowHours ?? null,
    windowStart: p.windowStart ?? null,
    windowEnd: p.windowEnd ?? null,
    sampleCoverage: p.sampleCoverage ?? null,
    usageSource: p.usageSource ?? null,
    policySource: p.policySource ?? null,
  };
};

export const workloadId = (workload: VerticalWorkloadWire): string =>
  `${workload.ref.namespace}/${workload.ref.kind}/${workload.ref.name}`;

const adaptWorkload = (
  workload: VerticalWorkloadWire,
  gateReasons: CodeView[],
  anomalies: string[],
): WorkloadView => {
  const id = workloadId(workload);
  const skipReason = parseCode(workload.skipReason);
  return {
    id,
    ref: workload.ref,
    policy: workload.policy ?? null,
    replicas: workload.replicas ?? null,
    hpaName: workload.hpa?.name ?? null,
    hpaPresent: workload.hpa?.present ?? false,
    gitopsManaged: workload.gitopsManaged ?? null,
    skipReason,
    findings: parseCodes(workload.findings),
    containers: (workload.containers ?? []).map((c) => adaptContainer(c, anomalies, `${id}/${c.name}`)),
    lastAction: workload.lastAction ?? null,
    autoApplyEligible: workload.autoApplyEligible === true,
    // The most useful single reason Apply is unavailable: this workload's own gate first, then the
    // cluster-wide one. Null when the payload gives no reason — the UI then says exactly that
    // rather than inventing one.
    applyBlockedBy: workload.autoApplyEligible === true ? null : (skipReason ?? gateReasons[0] ?? null),
    provenance: adaptProvenance(workload),
    horizontal: workload.horizontal ?? null,
  };
};

const adaptEngine = (wire: VerticalEngineResponseWire['engine']): EngineView => ({
  enabled: wire?.enabled === true,
  ready: wire?.ready === true,
  clusterAccess: wire?.clusterAccess ?? null,
  executionPath: wire?.executionPath ?? null,
  directAvailable: wire?.executionPathDetail?.direct?.available === true,
  directReason: parseCode(wire?.executionPathDetail?.direct?.reason),
  agentAvailable: wire?.executionPathDetail?.agent?.available === true,
  agentReason: parseCode(wire?.executionPathDetail?.agent?.reason),
  agentLastSeenAt: wire?.executionPathDetail?.agent?.lastSeenAt ?? null,
  metricsReachable: wire?.metricsSource?.reachable === true,
  metricsName: wire?.metricsSource?.name ?? null,
  lastCycleAt: wire?.lastCycle?.at ?? null,
  lastCycleEvaluated: wire?.lastCycle?.evaluated ?? null,
  lastCycleError: wire?.lastCycle?.error ?? null,
});

export const adaptEngineResponse = (wire: VerticalEngineResponseWire): VerticalEngineView => {
  const anomalies: string[] = [];
  const gateReasons = parseCodes(wire?.gateReasons);
  return {
    engine: adaptEngine(wire?.engine),
    gateReasons,
    workloads: (wire?.workloads ?? []).map((w) => adaptWorkload(w, gateReasons, anomalies)),
    anomalies,
  };
};

// ─── Formatting ──────────────────────────────────────────────────────────────

export const formatMillis = (value: MetricValue): string =>
  value.kind === 'value' ? `${value.value}m` : DASH;

export const formatMib = (value: MetricValue): string =>
  value.kind === 'value' ? `${value.value}Mi` : DASH;

export const formatCoverage = (coverage: number | null): string =>
  coverage === null || coverage === undefined || !Number.isFinite(coverage)
    ? DASH
    : `${Math.round(coverage * 100)}%`;

export const formatCount = (value: number | null | undefined): string =>
  value === null || value === undefined || !Number.isFinite(value) ? DASH : String(value);

export const formatText = (value: string | null | undefined): string =>
  value === null || value === undefined || value.trim() === '' ? DASH : value;

// ─── Apply body ──────────────────────────────────────────────────────────────

const changedField = (current: MetricValue, recommended: MetricValue): number | undefined => {
  if (recommended.kind !== 'value') return undefined;
  if (current.kind === 'value' && current.value === recommended.value) return undefined;
  return recommended.value;
};

/**
 * The apply body for one container: only the fields that actually move.
 *
 * `recommended` is a complete envelope, not a change set — a resource the engine deliberately froze
 * carries its current value — so copying it in would patch fields the engine refused to touch. The
 * backend's own decision-to-command conversion diffs the same way.
 *
 * Returns null when nothing changes, so the caller never submits an empty apply.
 */
export const toApplyContainer = (container: ContainerView): ApplyContainerRequest | null => {
  if (!container.recommended) return null;
  const body: ApplyContainerRequest = { name: container.name };
  const cpuRequestM = changedField(container.current.cpuRequestM, container.recommended.cpuRequestM);
  const memRequestMi = changedField(container.current.memRequestMi, container.recommended.memRequestMi);
  const cpuLimitM = changedField(container.current.cpuLimitM, container.recommended.cpuLimitM);
  const memLimitMi = changedField(container.current.memLimitMi, container.recommended.memLimitMi);
  if (cpuRequestM !== undefined) body.cpuRequestM = cpuRequestM;
  if (memRequestMi !== undefined) body.memRequestMi = memRequestMi;
  if (cpuLimitM !== undefined) body.cpuLimitM = cpuLimitM;
  if (memLimitMi !== undefined) body.memLimitMi = memLimitMi;
  return Object.keys(body).length > 1 ? body : null;
};

export const toApplyContainers = (workload: WorkloadView): ApplyContainerRequest[] =>
  workload.containers
    .map(toApplyContainer)
    .filter((c): c is ApplyContainerRequest => c !== null);
