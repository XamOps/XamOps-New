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
  CallerRefusal,
  CodeView,
  ContainerView,
  EngineView,
  ManualApplyGate,
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
import { formatIST } from '../utils/timeUtils';

/** Rendered for anything the engine did not measure. Never `0`, never a guess. */
export const DASH = '—';

/**
 * The nine shared gate codes plus the engine-internal enums, as published. The engine emits them
 * without the `K8S_` prefix inside payload bodies and with the prefix in a refusal's `code`, so both
 * directions are parsed.
 */
const CODE_LABELS: Record<string, string> = {
  // shared gate codes (glossary 11.1, verbatim)
  READ_ONLY_CONNECTION: 'Read-only connection',
  NO_USER_PERMISSION: 'No permission to change automation',
  CONTROLLER_NOT_READY: 'Applying is off in this environment',
  DEMO: 'Demo workspace',
  CLUSTER_ACCESS_NOT_CONFIGURED: 'No cluster access for XamOps',
  CLUSTER_UNREACHABLE: 'Cluster API not reachable',
  NO_METRICS_SOURCE: 'No usage data',
  INSUFFICIENT_HISTORY: 'Not enough data yet',
  GITOPS_MANAGED: 'Managed by Argo CD or Flux',
  // non-gate codes the UI may still be handed
  APPLY_IN_PROGRESS: 'Another change is running',
  CLUSTER_NOT_REGISTERED: 'Cluster not connected to XamOps',
  NO_ACCOUNT_ACCESS: 'No access to this account',
  AGENT_NOT_INSTALLED: 'XamOps agent not connected',
  // skip reasons
  PROTECTED_NAMESPACE: 'System namespace',
  SINGLE_REPLICA: 'Only 1 replica',
  ROLLOUT_IN_PROGRESS: 'Rolling out',
  LOCKED: 'Locked',
  HPA_VOLATILE: 'HPA just scaled it',
  POLICY_OFF: 'Rightsizing off',
  // findings
  REQUESTS_UNSET: 'No requests set',
  IDLE: 'Idle',
  THROTTLED: 'CPU throttled',
  OOM_RISK: 'Ran out of memory',
  HPA_COEXISTENCE: 'Has an HPA',
  NODE_CAPACITY_CAPPED: 'Limited by node size',
  VPA_NOT_READABLE: 'VPA not readable',
  // container reasons
  HPA_CPU_MANAGED: 'CPU left to the HPA',
  HPA_MEMORY_MANAGED: 'Memory left to the HPA',
  FLOOR_CLAMPED: 'Set to the minimum',
  BELOW_FLOOR_NO_RAISE: 'Below the minimum, left as is',
  LIMIT_MOVED: 'Limit moves too',
  DEMAND_CENSORED_BY_LIMIT: 'Hits its CPU limit',
  QOS_PRESERVED_NO_CHANGE: 'Kept to protect its class',
  COOLDOWN: 'Changed recently',
  WITHIN_HYSTERESIS: 'Change too small',
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

/** The text to print for a code: the label when known, "Other: <raw>" when not (glossary rule 3). */
export const codeText = (code: CodeView): string =>
  code.kind === 'known' ? code.label : `Other: ${code.raw}`;

const toMetric = (raw: number | null | undefined, anomalies: string[], where: string): MetricValue => {
  if (raw === null || raw === undefined) return { kind: 'unset' };
  if (!Number.isFinite(raw)) return { kind: 'unset' };
  if (raw === 0) {
    anomalies.push(`XamOps sent 0 for ${where}, which Kubernetes doesn't allow, so it's shown as ${DASH}.`);
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

/** Gate code (prefix stripped) to its rule's detail; anything that is not a string pair is dropped. */
const adaptGateDetails = (raw: VerticalEngineResponseWire['gateDetails']): Record<string, string> => {
  const details: Record<string, string> = {};
  if (!raw || typeof raw !== 'object') return details;
  for (const [code, detail] of Object.entries(raw)) {
    if (typeof detail === 'string' && detail.trim() !== '') {
      details[code.trim().replace(/^K8S_/, '')] = detail.trim();
    }
  }
  return details;
};

export const adaptEngineResponse = (wire: VerticalEngineResponseWire): VerticalEngineView => {
  const anomalies: string[] = [];
  const gateReasons = parseCodes(wire?.gateReasons);
  return {
    engine: adaptEngine(wire?.engine),
    gateReasons,
    gateDetails: adaptGateDetails(wire?.gateDetails),
    // Only an explicit true counts: a build that never evaluated the caller's write gates sends no
    // flag, and its empty gateReasons must not read as "you may apply".
    writeGatesChecked: wire?.writeGatesChecked === true,
    workloads: (wire?.workloads ?? []).map((w) => adaptWorkload(w, gateReasons, anomalies)),
    anomalies,
  };
};

// ─── Formatting ──────────────────────────────────────────────────────────────

export const formatMillis = (value: MetricValue): string =>
  value.kind === 'value' ? `${value.value}m` : DASH;

export const formatMib = (value: MetricValue): string =>
  value.kind === 'value' ? formatMemoryMi(value.value) : DASH;

export const formatCoverage = (coverage: number | null): string =>
  coverage === null || coverage === undefined || !Number.isFinite(coverage)
    ? DASH
    : `${Math.round(coverage * 100)}%`;

export const formatCount = (value: number | null | undefined): string =>
  value === null || value === undefined || !Number.isFinite(value) ? DASH : String(value);

export const formatText = (value: string | null | undefined): string =>
  value === null || value === undefined || value.trim() === '' ? DASH : value;

/**
 * An engine timestamp (a UTC ISO-8601 instant, nanoseconds and all) in IST, the way the rest of the
 * product shows time. Fractional seconds are cut to milliseconds first, the precision a JS Date
 * holds and the only one every engine parses. A value that will not parse is shown verbatim, never
 * guessed at; a missing one is a dash.
 */
export const formatInstant = (value: string | null | undefined): string => {
  if (value === null || value === undefined || value.trim() === '') return DASH;
  const shown = formatIST(value.trim().replace(/(\.\d{3})\d+/, '$1'));
  return shown === '' ? value : shown;
};

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

// ─── Plain language (automation-v2/glossary.md) ──────────────────────────────
//
// What a buyer reads, verbatim from the CloudK8s glossary. Codes stay on the wire and in titles
// ("Code: FLOOR_CLAMPED") so support can match a screen to a log; they are never visible text.

/** Values the payload does not carry yet (glossary rule 8): the engine's defaults. */
const CPU_FLOOR = '25m';
const MEM_FLOOR = '250Mi';
export const COOLDOWN_MINUTES = 60;

/** Glossary 6.4: below 48 hours in hours, from 48 hours up in whole days. */
export const windowWords = (hours: number | null | undefined): string => {
  if (hours === null || hours === undefined || !Number.isFinite(hours)) return 'measurement window';
  if (hours >= 48 && hours % 24 === 0) return `${hours / 24} days`;
  return `${hours} hours`;
};

export const workloadWindowHours = (workload: WorkloadView): number | null => {
  if (workload.provenance?.windowHours != null) return workload.provenance.windowHours;
  const match = workload.policy?.window ? /^(\d+)h$/.exec(workload.policy.window.trim()) : null;
  return match ? Number(match[1]) : null;
};

const valueOf = (m: MetricValue | undefined): number | null => (m && m.kind === 'value' ? m.value : null);

const theHpa = (workload?: WorkloadView): string =>
  workload?.hpaName ? `the HPA ${workload.hpaName}` : 'an HPA';

const WITHIN_HYSTERESIS_TOOLTIP =
  "XamOps raises CPU by at least 15% and 25m, lowers it by at least 30% and 100m, raises memory by at least 15% and 32Mi, and lowers it by at least 30% and 128Mi. For small containers, the fixed amounts shrink to what's reachable.";

/** Glossary 3.1, with the per-resource variants where the payload shows which resource it is. */
export const reasonSentence = (code: CodeView, container?: ContainerView, workload?: WorkloadView): string => {
  if (code.kind !== 'known') return codeText(code);
  const window = workload ? windowWords(workloadWindowHours(workload)) : 'measurement window';
  const cur = container?.current;
  const rec = container?.recommended ?? null;
  switch (code.code) {
    case 'FLOOR_CLAMPED': {
      const cpu = valueOf(rec?.cpuRequestM) === 25;
      const mem = valueOf(rec?.memRequestMi) === 250;
      if (cpu && !mem) return `CPU set to the minimum XamOps allows (${CPU_FLOOR}).`;
      if (mem && !cpu) return `Memory set to the minimum XamOps allows (${MEM_FLOOR}).`;
      return `Set to the minimum XamOps allows (${CPU_FLOOR} CPU, ${MEM_FLOOR} memory). Usage is lower, but XamOps never goes below this.`;
    }
    case 'BELOW_FLOOR_NO_RAISE': {
      const memNow = valueOf(cur?.memRequestMi);
      const cpuNow = valueOf(cur?.cpuRequestM);
      const memHeld = memNow !== null && memNow < 250 && (rec === null || valueOf(rec.memRequestMi) === memNow);
      const cpuHeld = cpuNow !== null && cpuNow < 25 && (rec === null || valueOf(rec.cpuRequestM) === cpuNow);
      if (memHeld && !cpuHeld) return `Memory is already below the minimum (${MEM_FLOOR}), so it is left as is.`;
      if (cpuHeld && !memHeld) return `CPU is already below the minimum (${CPU_FLOOR}), so it is left as is.`;
      return `Already below the minimum (${CPU_FLOOR} CPU, ${MEM_FLOOR} memory), so it is left as is. XamOps raises a request only when usage needs it.`;
    }
    case 'LIMIT_MOVED':
      return "The limit moves with the request: to the same value when they are equal today, otherwise in the same ratio, and never above 4× today's limit.";
    case 'DEMAND_CENSORED_BY_LIMIT':
      return 'Hits its CPU limit most of the time, so real demand may be higher than measured. XamOps sized it from the limit instead.';
    case 'HPA_CPU_MANAGED':
      return `CPU left alone: ${theHpa(workload)} scales this workload on CPU, and changing the request would change how many replicas it runs.`;
    case 'HPA_MEMORY_MANAGED':
      return `Memory left alone: ${theHpa(workload)} scales this workload on memory, and changing the request would change how many replicas it runs.`;
    case 'WITHIN_HYSTERESIS': {
      if (rec !== null && cur) {
        const cpuSame = valueOf(rec.cpuRequestM) === valueOf(cur.cpuRequestM);
        const memSame = valueOf(rec.memRequestMi) === valueOf(cur.memRequestMi);
        if (memSame && !cpuSame) return 'Memory: change too small to be worth making.';
        if (cpuSame && !memSame) return 'CPU: change too small to be worth making.';
      }
      return 'Change too small to be worth making.';
    }
    case 'NODE_CAPACITY_CAPPED':
      return 'Capped so that the pod still fits on the largest node, using at most 90% of it.';
    case 'QOS_PRESERVED_NO_CHANGE':
      return 'Left unchanged so that the pod keeps its Kubernetes QoS class. Its requests equal its limits, and moving one without the other would change the class.';
    case 'COOLDOWN':
      return `Changed in the last ${COOLDOWN_MINUTES} minutes. XamOps waits before suggesting another change.`;
    case 'REQUESTS_UNSET':
      return "No CPU or memory request is set, so this is a suggested starting point. XamOps won't apply it.";
    case 'OOM_RISK':
      return `Killed for running out of memory in the last ${window}, so XamOps will only raise its memory, even right after a change.`;
    default:
      return codeText(code);
  }
};

/** Glossary 3.2: a finding's tooltip. Its tag is the CODE_LABELS text. */
export const findingSentence = (code: CodeView, workload?: WorkloadView): string => {
  if (code.kind !== 'known') return codeText(code);
  const window = workload ? windowWords(workloadWindowHours(workload)) : 'measurement window';
  const sentences: Record<string, string> = {
    IDLE: 'Uses almost nothing: CPU at or under 1% of its request, and memory under the minimum.',
    THROTTLED: `Held back by its CPU limit in at least 20% of scheduling periods over the last ${window}.`,
    OOM_RISK: `A container was killed for running out of memory in the last ${window}.`,
    HPA_COEXISTENCE: 'An HPA scales this workload on CPU or memory. XamOps leaves that resource alone.',
    REQUESTS_UNSET: "At least one container has no CPU or memory request. Kubernetes can't plan for it.",
    NODE_CAPACITY_CAPPED: 'A suggestion was capped to fit the largest node.',
    VPA_NOT_READABLE:
      "XamOps isn't allowed to read VerticalPodAutoscaler objects in this cluster, so the Kubernetes VPA comparison is blank.",
  };
  return sentences[code.code] ?? codeText(code);
};

/** Glossary 3.3: a skip reason's short text (after "Skipped: ") and its sentence. */
const SKIPPED: Record<string, { short: string; sentence: string }> = {
  PROTECTED_NAMESPACE: {
    short: 'system namespace',
    sentence: 'XamOps never resizes workloads in kube-system, monitoring, amazon-cloudwatch, vpa or xamops-system.',
  },
  SINGLE_REPLICA: {
    short: 'only 1 replica',
    sentence: 'It runs a single replica, so a problem during a resize would take it down. XamOps leaves it alone.',
  },
  ROLLOUT_IN_PROGRESS: {
    short: 'rolling out',
    sentence: 'A rollout is in progress, so its usage is unstable. XamOps will look again when the rollout ends.',
  },
  LOCKED: {
    short: 'locked',
    sentence: 'Locked with the annotation xamops.io/lock: active. Remove the annotation to let XamOps size it.',
  },
  HPA_VOLATILE: {
    short: 'HPA just scaled it',
    sentence: 'Its HPA changed the replica count in the last 5 minutes, so current usage is temporary.',
  },
};

export const skipSentence = (code: CodeView, workload?: WorkloadView): string => {
  if (code.kind !== 'known') return codeText(code);
  if (code.code === 'INSUFFICIENT_HISTORY') {
    const window = workload ? windowWords(workloadWindowHours(workload)) : 'measurement window';
    return `XamOps needs at least half of the last ${window} of usage, and at least 2 hours, before it suggests a size. Containers are measured from 2 minutes after they start.`;
  }
  if (code.code === 'POLICY_OFF') return "Rightsizing is off for this workload, so XamOps didn't analyse it.";
  return SKIPPED[code.code]?.sentence ?? codeText(code);
};

/** Glossary 4.2: the mode in words. */
export const modeText = (mode: string | null | undefined): string => {
  if (mode === 'RECOMMEND') return 'Suggest only';
  if (mode === 'AUTO') return 'Apply automatically';
  if (mode === 'OFF') return 'Off';
  return formatText(mode);
};

/** Glossary rule 6: memory below 1024 Mi in Mi, from 1024 Mi up in GiB with up to two decimals. */
export const formatMemoryMi = (mi: number): string =>
  mi < 1024 ? `${mi}Mi` : `${Number((mi / 1024).toFixed(2))} GiB`;

const hasCode = (codes: CodeView[], code: string): boolean =>
  codes.some((c) => c.kind === 'known' && c.code === code);

export interface WorkloadStatusText {
  /** The one status word (glossary section 2). */
  status: string;
  /** Its second line. */
  detail: string;
}

/**
 * Glossary section 2: one status per workload, decided by the first rule that matches. Direction
 * compares `recommended` with `current` on the CPU and memory requests, and leaves out containers
 * whose requests are unset.
 */
export const workloadStatus = (workload: WorkloadView): WorkloadStatusText => {
  const window = windowWords(workloadWindowHours(workload));
  const skip = workload.skipReason;
  if (skip && skip.kind === 'known' && skip.code === 'POLICY_OFF') {
    return { status: 'Off', detail: "Rightsizing is off for this workload, so XamOps didn't analyse it." };
  }
  if ((skip && skip.kind === 'known' && skip.code === 'INSUFFICIENT_HISTORY') || (!skip && workload.containers.length === 0)) {
    return {
      status: 'Not enough data yet',
      detail: `XamOps needs at least half of the last ${window} of usage, and at least 2 hours, before it suggests a size.`,
    };
  }
  if (skip) {
    const short = skip.kind === 'known' && SKIPPED[skip.code] ? SKIPPED[skip.code].short : codeText(skip);
    return { status: `Skipped: ${short}`, detail: skipSentence(skip, workload) };
  }
  const withSuggestion = workload.containers.filter((c) => c.recommended !== null);
  const unset = (c: ContainerView) => hasCode(c.reasons, 'REQUESTS_UNSET');
  if (withSuggestion.length > 0 && withSuggestion.every(unset)) {
    const first = withSuggestion[0].recommended!;
    const cpu = valueOf(first.cpuRequestM);
    const mem = valueOf(first.memRequestMi);
    return {
      status: 'Left alone: no requests set',
      detail: `Suggested starting values: ${cpu === null ? DASH : `${cpu}m`} CPU, ${
        mem === null ? DASH : formatMemoryMi(mem)
      } memory. Set them in the workload's manifest. XamOps doesn't add requests itself.`,
    };
  }
  let cpuUp = false;
  let cpuDown = false;
  let memUp = false;
  let memDown = false;
  let cpuFreed = 0;
  let memFreed = 0;
  let changing: ContainerView | null = null;
  for (const c of withSuggestion) {
    if (unset(c)) continue;
    const pairs: [number | null, number | null, 'cpu' | 'mem'][] = [
      [valueOf(c.current.cpuRequestM), valueOf(c.recommended!.cpuRequestM), 'cpu'],
      [valueOf(c.current.memRequestMi), valueOf(c.recommended!.memRequestMi), 'mem'],
    ];
    for (const [now, next, resource] of pairs) {
      if (now === null || next === null || now === next) continue;
      changing = changing ?? c;
      if (next > now) {
        if (resource === 'cpu') cpuUp = true;
        else memUp = true;
      } else if (resource === 'cpu') {
        cpuDown = true;
        cpuFreed += now - next;
      } else {
        memDown = true;
        memFreed += now - next;
      }
    }
  }
  if (cpuUp || memUp) {
    let status: string;
    if (cpuUp && memUp) status = 'Needs more CPU and memory';
    else if (cpuUp && memDown) status = 'Needs more CPU, less memory';
    else if (memUp && cpuDown) status = 'Needs more memory, less CPU';
    else if (cpuUp) status = 'Needs more CPU';
    else status = 'Needs more memory';
    if (cpuUp && hasCode(workload.findings, 'THROTTLED')) status += ' (throttled)';
    if (memUp && hasCode(workload.findings, 'OOM_RISK')) status += ' (ran out of memory)';
    const reasons = changing?.reasons ?? [];
    const lead =
      reasons.find((r) => r.kind === 'known' && (r.code === 'DEMAND_CENSORED_BY_LIMIT' || r.code === 'OOM_RISK')) ??
      reasons[0];
    return { status, detail: lead ? reasonSentence(lead, changing ?? undefined, workload) : '' };
  }
  if (cpuDown || memDown) {
    const replicas = workload.replicas ?? 1;
    const parts: string[] = [];
    if (cpuFreed > 0) parts.push(`${((cpuFreed * replicas) / 1000).toFixed(2)} vCPU`);
    if (memFreed > 0) parts.push(`${formatMemoryMi(memFreed * replicas)} memory`);
    return { status: 'Over-provisioned', detail: `Could free ${parts.join(' and ')}` };
  }
  const reasons = workload.containers.flatMap((c) => c.reasons);
  if (hasCode(reasons, 'COOLDOWN')) {
    return {
      status: 'Left alone: changed recently',
      detail: `XamOps waits ${COOLDOWN_MINUTES} minutes after a change before suggesting another.`,
    };
  }
  const cpuHpa = hasCode(reasons, 'HPA_CPU_MANAGED');
  const memHpa = hasCode(reasons, 'HPA_MEMORY_MANAGED');
  if (cpuHpa || memHpa) {
    const name = workload.hpaName ?? '';
    const ownReason = reasons.some(
      (r) =>
        r.kind === 'known' &&
        !['HPA_CPU_MANAGED', 'HPA_MEMORY_MANAGED', 'QOS_PRESERVED_NO_CHANGE', 'WITHIN_HYSTERESIS'].includes(r.code),
    );
    const first = cpuHpa ? `CPU is left to the HPA ${name}`.trim() + '.' : `Memory is left to the HPA ${name}`.trim() + '.';
    const second = cpuHpa ? 'Memory is already the right size.' : 'CPU is already the right size.';
    return {
      status: 'Left alone: an HPA scales it',
      detail: ownReason || (cpuHpa && memHpa) ? first : `${first} ${second}`,
    };
  }
  return { status: 'Right-sized', detail: 'Already the right size: no change is big enough to be worth making.' };
};

/** The title for a reason chip: the sentence, then the code (glossary 11.1). */
export const reasonTitle = (code: CodeView, container?: ContainerView, workload?: WorkloadView): string => {
  const sentence = reasonSentence(code, container, workload);
  const extra = code.kind === 'known' && code.code === 'WITHIN_HYSTERESIS' ? ` ${WITHIN_HYSTERESIS_TOOLTIP}` : '';
  return `${sentence}${extra} Code: ${code.kind === 'known' ? code.code : code.raw}`;
};

// ─── Caller-level write gates (glossary 7.1, 7.2, 7.4) ───────────────────────

/** The caller-level write gates, in the server's published refusal order. */
const WRITE_GATES = ['DEMO', 'NO_USER_PERMISSION', 'READ_ONLY_CONNECTION'] as const;

/**
 * Refusal codes that are about the caller rather than the workload, so one of them holds for every
 * workload on the page: the write gates, plus the account entitlement endpoint 3 re-asks.
 */
const CALLER_LEVEL = new Set<string>([...WRITE_GATES, 'NO_ACCOUNT_ACCESS']);

export const isCallerLevelRefusal = (code: CodeView): boolean =>
  code.kind === 'known' && CALLER_LEVEL.has(code.code);

/** Glossary 7.3 `UNKNOWN`: the answer when the server did not say whether the caller may apply. */
export const WRITE_GATES_NOT_CHECKED = "Couldn't check whether you can change automation.";

export interface WriteGateText {
  /** The reason shown at the control (exact glossary text). */
  reason: string;
  /** The next step, when the glossary gives one. */
  nextStep: string | null;
}

const TAB_GRANT_TEXT: WriteGateText = {
  reason: "View only: you don't have permission to change automation.",
  nextStep: 'Ask your XamOps administrator to allow automation changes for your user.',
};

/** Glossary 7.2: one NO_USER_PERMISSION code, four rules, four different remedies. */
const PERMISSION_TEXT: Record<string, WriteGateText> = {
  TAB_GRANT: TAB_GRANT_TEXT,
  ROLE: { reason: 'View only: your role can see automation but not change it.', nextStep: null },
  IMPERSONATION: {
    reason: "View only while you're signed in as another user.",
    nextStep: 'Return to your own session to change automation.',
  },
  NO_PRINCIPAL: { reason: 'Sign in again to change automation.', nextStep: null },
};

const DEMO_TEXT: WriteGateText = { reason: "Demo workspace. Changes aren't saved.", nextStep: null };

/**
 * The sentence (and next step) for a caller-level gate or refusal, by its code and the permission
 * rule's detail. A NO_USER_PERMISSION with no detail gets the TAB_GRANT wording, as glossary 7.2
 * says to until the detail is served; a refusal's own message is the caller's fallback for that.
 */
export const writeGateText = (code: string, detail: string | null | undefined): WriteGateText | null => {
  switch (code) {
    case 'DEMO':
      return DEMO_TEXT;
    case 'NO_USER_PERMISSION':
      if (detail === 'DEMO') return DEMO_TEXT;
      if (detail === 'ACCOUNT') return { reason: "You don't have access to this AWS account.", nextStep: null };
      return (detail && PERMISSION_TEXT[detail]) || TAB_GRANT_TEXT;
    case 'READ_ONLY_CONNECTION':
      return {
        reason: 'View only: this AWS account is connected read-only. Applying changes needs read-write access.',
        nextStep: null,
      };
    case 'NO_ACCOUNT_ACCESS':
      return { reason: "You don't have access to this AWS account.", nextStep: null };
    default:
      return null;
  }
};

// ─── Manual apply gate ───────────────────────────────────────────────────────

const PATH_GATES = ['CLUSTER_ACCESS_NOT_CONFIGURED', 'CLUSTER_UNREACHABLE'] as const;

/**
 * Every change moves memory up and nothing else: no CPU field, and each memory field above what the
 * container has now. The same rule endpoint 3 exempts from the cooldown.
 */
export const isMemoryOnlyIncrease = (workload: WorkloadView, changes: ApplyContainerRequest[]): boolean =>
  changes.length > 0 &&
  changes.every((change) => {
    if (change.cpuRequestM !== undefined || change.cpuLimitM !== undefined) return false;
    if (change.memRequestMi === undefined && change.memLimitMi === undefined) return false;
    const container = workload.containers.find((c) => c.name === change.name);
    if (!container) return false;
    const raises = (next: number | undefined, now: MetricValue): boolean =>
      next === undefined || (now.kind === 'value' && next > now.value);
    return raises(change.memRequestMi, container.current.memRequestMi) && raises(change.memLimitMi, container.current.memLimitMi);
  });

const findGate = (gateReasons: CodeView[], code: string): CodeView | undefined =>
  gateReasons.find((g) => g.kind === 'known' && g.code === code);

const label = (code: string): string => codeText(parseCode(code) ?? { kind: 'unknown', raw: code });

/**
 * Whether a user may submit a **one-time** apply for this workload.
 *
 * `autoApplyEligible` is deliberately not consulted: it answers "would the engine apply this on its
 * own", which needs policy `AUTO`. A user-initiated apply needs a real change and every gate the
 * server re-checks on endpoint 3, in its order: the write gates (demo, permission — which also
 * covers a Login-As session — and a read-write connection), a ready controller, an execution path,
 * then the workload's own refusals (a skip reason, GitOps-managed persistence, the cooldown, and
 * requests the engine would be inventing). The first that fails is the reason shown.
 *
 * Two rules make the caller's part fail closed rather than open:
 *  - `callerRefusal`, a caller-level refusal endpoint 3 already answered on this page, greys every
 *    workload: it is about the user, not the row, and a second click would only be refused again;
 *  - a payload that does not say `writeGatesChecked` never enables Apply, because "no write gate"
 *    from a server that never looked is not "permitted". That is what a frontend deployed ahead of
 *    its backend was shown on UAT on 2026-10-12, and every click came back 403.
 */
export const manualApplyGate = (
  view: VerticalEngineView,
  workload: WorkloadView,
  callerRefusal: CallerRefusal | null = null,
): ManualApplyGate => {
  const changes = toApplyContainers(workload);
  const blocked = (reason: string, nextStep: string | null = null): ManualApplyGate => ({
    enabled: false,
    reason,
    nextStep,
    changes,
  });
  const { engine, gateReasons } = view;

  if (callerRefusal) {
    const text = writeGateText(callerRefusal.code, callerRefusal.detail);
    if (text) return blocked(text.reason, text.nextStep);
  }
  for (const code of WRITE_GATES) {
    if (findGate(gateReasons, code)) {
      const text = writeGateText(code, view.gateDetails[code]);
      return blocked(text!.reason, text!.nextStep);
    }
  }
  if (!view.writeGatesChecked) return blocked(WRITE_GATES_NOT_CHECKED);
  if (!engine.enabled || !engine.ready || findGate(gateReasons, 'CONTROLLER_NOT_READY')) {
    return blocked(label('CONTROLLER_NOT_READY'));
  }
  if (engine.executionPath === null || engine.executionPath === 'NONE') {
    const pathGate = PATH_GATES.map((code) => findGate(gateReasons, code)).find((g) => g !== undefined);
    const reason = engine.directReason ?? pathGate;
    return blocked(reason ? codeText(reason) : 'No way to send changes to this cluster');
  }
  if (workload.skipReason) return blocked(codeText(workload.skipReason));
  if (workload.gitopsManaged === true || workload.policy?.applyMethod === 'IN_PLACE_AND_PERSIST') {
    return blocked(label('GITOPS_MANAGED'));
  }
  const reasons = workload.containers.flatMap((c) => c.reasons);
  // The cooldown holds everything except a memory-only raise, which the engine publishes for a
  // container that ran out of memory after the last change and endpoint 3 accepts (review-2 SF1).
  if (reasons.some((r) => r.kind === 'known' && r.code === 'COOLDOWN') && !isMemoryOnlyIncrease(workload, changes)) {
    return blocked(label('COOLDOWN'));
  }
  const changing = new Set(changes.map((c) => c.name));
  const invented = workload.containers.some(
    (c) => changing.has(c.name) && c.reasons.some((r) => r.kind === 'known' && r.code === 'REQUESTS_UNSET'),
  );
  if (invented) return blocked(label('REQUESTS_UNSET'));
  if (changes.length === 0) return blocked('No change suggested');
  return { enabled: true, reason: null, nextStep: null, changes };
};
