/**
 * Vertical engine wire → view model, and the engine's share of the capability model
 * (docs/cloudk8s/ui-revamp-design.md D16, §6.2 step 2, §6.3, §11.5; design-review NIT 1, NIT 2).
 */
import type { ActionId, Gate, ReasonCode, VerticalActionId } from '../types/k8sAutomation';
import { isReasonCode, isVerticalAction, stripCodePrefix } from '../types/k8sAutomation';
import {
  ACTION_OUTCOMES,
  APPLY_METHODS,
  CONTAINER_REASONS,
  DIRECT_PATH_REASONS,
  EXECUTION_PATHS,
  FINDINGS,
  PERCENTILES,
  POLICY_WINDOWS,
  SKIP_REASONS,
  SUBMISSION_STATES,
  VERTICAL_MODES,
  type ActionsPageWire,
  type ApplySubmissionView,
  type ApplySubmissionWire,
  type CostSummaryView,
  type CostSummaryWire,
  type Known,
  type Resources,
  type Unknown,
  type VerticalActionView,
  type VerticalContainer,
  type VerticalContainerWire,
  type VerticalEngineView,
  type VerticalProvenanceWire,
  type VerticalStatusWire,
  type VerticalWorkload,
  type VerticalWorkloadWire,
} from '../types/verticalRecommendations';
import type { K8sApiError } from './k8sAutomationApi';

/**
 * Whether the engine's server-side write checks are confirmed (R1 and R2, D16).
 *
 * A code constant, not a server flag, because the fact it records is about code: the engine's write
 * endpoints (2 and 3) have no handler yet, so offering their controls would make the UI the only gate.
 * Flip it only in a change where both endpoints have handlers, the reviewer has read the engine's code
 * and tests and confirmed R1 and R2, and R10 is answered. A test pins it to `false` until then.
 */
export const VERTICAL_WRITES_CONFIRMED = false as boolean;

/** Shown on every `vertical.*` control while the contract block holds (§6.1). */
export const CONTRACT_BLOCK_MESSAGE =
  "Workload autoscaling changes are off until the vertical rightsizer's access checks are confirmed.";

/** Safe floors below which a recommendation is not applied (engine contract §2.3). */
export const CPU_FLOOR_M = 25;
export const MEM_FLOOR_MI = 250;

// ── Enum wrapping ────────────────────────────────────────────────────────────

export function unknown(raw: string): Unknown {
  return { kind: 'unknown', raw };
}

export function isUnknown(value: unknown): value is Unknown {
  return typeof value === 'object' && value !== null && (value as Unknown).kind === 'unknown';
}

/** A known value, or `{ kind: 'unknown', raw }` for anything the contract adds later. */
export function wrap<T extends string>(value: string | number | null | undefined, known: readonly T[]): Known<T> | null {
  if (value === null || value === undefined) return null;
  const text = String(value);
  return (known as readonly string[]).includes(text) ? (text as T) : unknown(text);
}

function wrapList<T extends string>(values: unknown, known: readonly T[]): Array<Known<T>> {
  if (!Array.isArray(values)) return [];
  return values.filter((v) => typeof v === 'string').map((v) => wrap(v, known) as Known<T>);
}

function reasonOrUnknown(value: string | null | undefined): ReasonCode | Unknown | null {
  if (value === null || value === undefined) return null;
  const code = stripCodePrefix(value);
  return isReasonCode(code) ? code : unknown(value);
}

// ── Resources ────────────────────────────────────────────────────────────────

const RESOURCE_KEYS = ['cpuRequestM', 'memRequestMi', 'cpuLimitM', 'memLimitMi'] as const;

/** Integers or null; a `0` breaks the contract ("a 0 must be treated as a bug") and becomes null. */
function cleanResources(wire: Partial<Resources> | null | undefined): { value: Resources; invalid: boolean } {
  let invalid = false;
  const value = {} as Resources;
  for (const key of RESOURCE_KEYS) {
    const raw = wire?.[key];
    if (typeof raw !== 'number' || !Number.isFinite(raw)) {
      value[key] = null;
    } else if (raw === 0) {
      value[key] = null;
      invalid = true;
    } else {
      value[key] = raw;
    }
  }
  return { value, invalid };
}

/** Guaranteed means every request equals its limit; a recommendation that breaks that changes QoS. */
function breaksGuaranteed(qosClass: string | null, recommended: Resources | null): boolean {
  if (qosClass !== 'Guaranteed' || !recommended) return false;
  return (
    recommended.cpuRequestM === null || recommended.memRequestMi === null ||
    recommended.cpuRequestM !== recommended.cpuLimitM || recommended.memRequestMi !== recommended.memLimitMi
  );
}

function belowFloor(recommended: Resources | null): boolean {
  if (!recommended) return false;
  return (
    (recommended.cpuRequestM !== null && recommended.cpuRequestM < CPU_FLOOR_M) ||
    (recommended.memRequestMi !== null && recommended.memRequestMi < MEM_FLOOR_MI)
  );
}

function toContainer(wire: VerticalContainerWire): VerticalContainer {
  const current = cleanResources(wire.current);
  const recommended = wire.recommended ? cleanResources(wire.recommended) : null;
  const rec = recommended ? recommended.value : null;
  return {
    name: wire.name,
    qosClass: wire.qosClass ?? null,
    current: current.value,
    recommended: rec,
    reasons: wrapList(wire.reasons, CONTAINER_REASONS),
    invalidValue: current.invalid || (recommended?.invalid ?? false),
    qosChange: breaksGuaranteed(wire.qosClass ?? null, rec),
    belowFloor: belowFloor(rec),
  };
}

function toWorkload(wire: VerticalWorkloadWire): VerticalWorkload {
  const containers = Array.isArray(wire.containers) ? wire.containers.map(toContainer) : [];
  const findings = wrapList(wire.findings, FINDINGS);
  return {
    ref: wire.ref,
    policy: {
      mode: wrap(wire.policy?.mode ?? 'OFF', VERTICAL_MODES) as Known<(typeof VERTICAL_MODES)[number]>,
      headroomPct: typeof wire.policy?.headroomPct === 'number' ? wire.policy.headroomPct : null,
      percentile: wrap(wire.policy?.percentile, PERCENTILES),
      window: wrap(wire.policy?.window, POLICY_WINDOWS),
      applyMethod: wrap(wire.policy?.applyMethod ?? 'IN_PLACE', APPLY_METHODS) as Known<(typeof APPLY_METHODS)[number]>,
    },
    replicas: typeof wire.replicas === 'number' ? wire.replicas : null,
    hpa: wire.hpa ?? null,
    gitopsManaged: typeof wire.gitopsManaged === 'boolean' ? wire.gitopsManaged : null,
    skipReason: wrap(wire.skipReason, SKIP_REASONS),
    findings,
    containers,
    lastAction: wire.lastAction
      ? {
          at: wire.lastAction.at,
          dryRun: wire.lastAction.dryRun,
          outcome: wrap(wire.lastAction.outcome, ACTION_OUTCOMES) as Known<(typeof ACTION_OUTCOMES)[number]>,
        }
      : null,
    provenance: wire.provenance ?? null,
    horizontal: Array.isArray(wire.horizontal?.reasons)
      ? { reasons: wire.horizontal.reasons.filter((r) => typeof r === 'string') }
      : undefined,
    invalidValue: containers.some((c) => c.invalidValue),
    qosChange: containers.some((c) => c.qosChange),
    belowFloor: containers.some((c) => c.belowFloor),
    hpaConflict: findings.includes('HPA_COEXISTENCE'),
  };
}

// ── Cost roll-up (FEAT-002) ──────────────────────────────────────────────────

/** A money field kept as-is when a real finite number (incl. a true 0), else null. */
const costMoney = (raw: number | null | undefined): number | null =>
  typeof raw === 'number' && Number.isFinite(raw) ? raw : null;

/** The cluster cost roll-up, or null when the wire carried none (feature off / older build). */
export function toCostSummary(wire: CostSummaryWire | null | undefined): CostSummaryView | null {
  if (!wire) return null;
  return {
    currency: wire.currency ?? 'USD',
    hoursPerMonth: typeof wire.hoursPerMonth === 'number' && Number.isFinite(wire.hoursPerMonth) ? wire.hoursPerMonth : 0,
    monthlySavings: costMoney(wire.monthlySavings),
    monthlySavingsUnavailableReason: wire.monthlySavingsUnavailableReason ?? null,
    clusterMonthlyCost: costMoney(wire.clusterMonthlyCost),
    pricedWorkloads: typeof wire.pricedWorkloads === 'number' ? wire.pricedWorkloads : 0,
    unpricedWorkloads: typeof wire.unpricedWorkloads === 'number' ? wire.unpricedWorkloads : 0,
  };
}

// ── Engine status → view ─────────────────────────────────────────────────────

/** The §8.1 state from a `200`, evaluated top to bottom: V7, V0, V2, V3, else V4. */
export function toVerticalEngineView(wire: VerticalStatusWire): VerticalEngineView {
  const engine = wire.engine;
  const gateReasons = Array.isArray(wire.gateReasons)
    ? wire.gateReasons.filter((g) => typeof g === 'string').map((g) => reasonOrUnknown(g) as ReasonCode | Unknown)
    : [];
  const executionPath = (wrap(engine?.executionPath, EXECUTION_PATHS) ?? unknown('missing')) as VerticalEngineView['executionPath'];
  const has = (code: ReasonCode) => gateReasons.includes(code);

  let state: VerticalEngineView['state'];
  if (engine?.enabled !== true) state = 'V7';
  else if (executionPath === 'NONE') state = 'V0';
  else if (has('NO_METRICS_SOURCE')) state = 'V2';
  else if (has('INSUFFICIENT_HISTORY')) state = 'V3';
  else state = 'V4';

  const workloads = Array.isArray(wire.workloads) ? wire.workloads.map(toWorkload) : [];
  return {
    state,
    enabled: engine?.enabled === true,
    ready: engine?.ready === true,
    executionPath,
    directPath: {
      available: engine?.executionPathDetail?.direct?.available === true,
      reason: wrap(engine?.executionPathDetail?.direct?.reason
        ? stripCodePrefix(engine.executionPathDetail.direct.reason) : null, DIRECT_PATH_REASONS),
    },
    agentPath: {
      available: engine?.executionPathDetail?.agent?.available === true,
      reason: reasonOrUnknown(engine?.executionPathDetail?.agent?.reason),
      lastSeenAt: engine?.executionPathDetail?.agent?.lastSeenAt ?? null,
    },
    metricsSource: {
      reachable: engine?.metricsSource?.reachable === true,
      name: engine?.metricsSource?.name ?? null,
    },
    lastCycle: {
      at: engine?.lastCycle?.at ?? null,
      evaluated: engine?.lastCycle?.evaluated ?? null,
      error: engine?.lastCycle?.error ?? null,
    },
    gateReasons,
    workloads,
    allWorkloadsOff: workloads.length > 0 && workloads.every((w) => w.skipReason === 'POLICY_OFF'),
    cost: toCostSummary(wire.cost),
  };
}

export function toSubmissionView(wire: ApplySubmissionWire): ApplySubmissionView {
  return {
    correlationId: wire.correlationId,
    state: wrap(wire.state, SUBMISSION_STATES) as ApplySubmissionView['state'],
    dryRun: wire.dryRun === true,
  };
}

export function toActionViews(page: ActionsPageWire): VerticalActionView[] {
  const entries = Array.isArray(page?.entries) ? page.entries : [];
  return entries.map((e) => ({
    at: e.at,
    correlationId: e.correlationId,
    workloadRef: e.workloadRef,
    containerChanges: Array.isArray(e.containerChanges) ? e.containerChanges : [],
    method: wrap(e.method, APPLY_METHODS) as VerticalActionView['method'],
    // dryRun is mandatory on every entry (contract §4); anything but a boolean true reads as false.
    dryRun: e.dryRun === true,
    outcome: wrap(e.outcome, ACTION_OUTCOMES) as VerticalActionView['outcome'],
    attempt: typeof e.attempt === 'number' ? e.attempt : 1,
    triggeredBy: e.triggeredBy,
  }));
}

// ── Labels ───────────────────────────────────────────────────────────────────

/**
 * "p95 over 24h · 94% sample coverage · usage from <source>" (NIT 2): sampleCoverage is a fraction,
 * and "usage from …" appears only when a source is named.
 */
export function formatProvenance(p: VerticalProvenanceWire | null | undefined): string | null {
  if (!p) return null;
  const parts: string[] = [];
  if (typeof p.percentile === 'number' && typeof p.windowHours === 'number') {
    parts.push(`p${p.percentile} over ${p.windowHours}h`);
  }
  if (typeof p.sampleCoverage === 'number' && Number.isFinite(p.sampleCoverage)) {
    parts.push(`${Math.round(p.sampleCoverage * 100)}% sample coverage`);
  }
  if (p.usageSource && p.usageSource !== 'none') {
    parts.push(`usage from ${p.usageSource}`);
  }
  return parts.length ? parts.join(' · ') : null;
}

/** "No change recommended" (recommended: null) is a verdict; "No recommendation" means not evaluated. */
export function recommendationLabel(workload: VerticalWorkload, container: VerticalContainer): string {
  if (workload.skipReason !== null) return 'No recommendation';
  return container.recommended === null ? 'No change recommended' : 'Recommended';
}

// ── Gates (§6.2 step 2, §6.3) ────────────────────────────────────────────────

/** What the capability model knows about the engine for one call of `can()`. */
export interface VerticalGateInput {
  /** Endpoint 1 view, or null when it hasn't loaded. */
  view?: VerticalEngineView | null;
  /** Endpoint 1 failed (V5): every vertical write fails closed with UNKNOWN. */
  failed?: boolean;
  workload?: VerticalWorkload | null;
}

const PATH_ONLY_CODES: ReadonlySet<string> = new Set([
  'CLUSTER_ACCESS_NOT_CONFIGURED', 'CLUSTER_UNREACHABLE', 'AGENT_NOT_INSTALLED',
]);

const needsExecutionPath = (id: VerticalActionId) => id === 'vertical.policy.auto' || id === 'vertical.apply';
const needsMetrics = needsExecutionPath;

function persists(id: VerticalActionId, workload: VerticalWorkload | null | undefined): boolean {
  return id === 'vertical.method.persist'
    || (id === 'vertical.apply' && workload?.policy.applyMethod === 'IN_PLACE_AND_PERSIST');
}

/**
 * The engine's gates for one vertical action: the contract block (D16), the execution path, the
 * cluster-level `gateReasons` scoped by the §6.3 table, and the workload's own fields. Non-vertical
 * actions get none.
 */
export function verticalGates(input: VerticalGateInput, actionId: ActionId): Gate[] {
  if (!isVerticalAction(actionId)) return [];
  const gates: Gate[] = [];
  if (!VERTICAL_WRITES_CONFIRMED) {
    gates.push({ reason: 'CONTROLLER_NOT_READY', message: CONTRACT_BLOCK_MESSAGE });
  }
  if (input.failed) {
    gates.push({ reason: 'UNKNOWN' });
    return gates;
  }
  const view = input.view;
  if (!view) return gates;

  // Execution path (§6.2 step 2): Auto and apply only; never clusterAccess.
  if (needsExecutionPath(actionId)) {
    const path = view.executionPath;
    if (isUnknown(path)) {
      gates.push({ reason: 'UNKNOWN', raw: path.raw });
    } else if (path === 'NONE') {
      const agentReason = view.agentPath.reason;
      if (agentReason === 'AGENT_NOT_INSTALLED') {
        const direct = view.directPath.reason;
        gates.push({
          reason: 'AGENT_NOT_INSTALLED',
          detail: 'EXECUTION_PATH',
          directPath: direct === 'CLUSTER_ACCESS_NOT_CONFIGURED' || direct === 'CLUSTER_UNREACHABLE' ? direct : null,
        });
      } else if (agentReason !== null && !isUnknown(agentReason)) {
        gates.push({ reason: agentReason });
      } else {
        gates.push({ reason: 'UNKNOWN', raw: agentReason ? agentReason.raw : 'NONE' });
      }
    }
  }

  // Cluster-level gateReasons, scoped per action (§6.3).
  for (const code of view.gateReasons) {
    if (isUnknown(code)) {
      gates.push({ reason: 'UNKNOWN', raw: code.raw });
    } else if (code === 'CONTROLLER_NOT_READY') {
      gates.push({ reason: 'CONTROLLER_NOT_READY' });
    } else if (code === 'NO_METRICS_SOURCE' || code === 'INSUFFICIENT_HISTORY') {
      if (needsMetrics(actionId)) gates.push({ reason: code });
    } else if (code === 'GITOPS_MANAGED') {
      if (persists(actionId, input.workload)) gates.push({ reason: code });
    } else if (PATH_ONLY_CODES.has(code)) {
      // The execution-path rule decides; a missing direct path never blocks a working agent path.
    } else {
      // The write gates (unioned with capabilities, so the stricter answer wins) and any other
      // known code reach every vertical write.
      gates.push({ reason: code });
    }
  }

  // Per workload (§6.2 step 4).
  const workload = input.workload;
  if (workload) {
    if (workload.gitopsManaged === true && persists(actionId, workload)) {
      gates.push({ reason: 'GITOPS_MANAGED' });
    }
    if (workload.skipReason === 'INSUFFICIENT_HISTORY' && needsMetrics(actionId)) {
      gates.push({ reason: 'INSUFFICIENT_HISTORY' });
    }
  }
  return gates;
}

/**
 * A refusal from an engine write, as a gate (NIT 1). `K8S_CLUSTER_ACCESS_NOT_CONFIGURED` and
 * `K8S_CLUSTER_UNREACHABLE` on Auto or apply mean no execution path: they render as
 * AGENT_NOT_INSTALLED / EXECUTION_PATH with the direct path set. Non-gate error codes give null.
 */
export function mapVerticalRefusal(error: K8sApiError, actionId: ActionId): Gate | null {
  if (!error.code) return null;
  const code = stripCodePrefix(error.code);
  if (
    (code === 'CLUSTER_ACCESS_NOT_CONFIGURED' || code === 'CLUSTER_UNREACHABLE') &&
    (actionId === 'vertical.apply' || actionId === 'vertical.policy.auto')
  ) {
    return { reason: 'AGENT_NOT_INSTALLED', detail: 'EXECUTION_PATH', directPath: code };
  }
  if (isReasonCode(code)) return { reason: code, message: error.message };
  if (['UNAUTHENTICATED', 'INVALID_REQUEST', 'INVALID_POLICY', 'NO_ACCOUNT_ACCESS', 'CLUSTER_NOT_REGISTERED', 'INTERNAL']
    .includes(code)) {
    return null;
  }
  return { reason: 'UNKNOWN', raw: error.code, message: error.message };
}
