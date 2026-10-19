/**
 * Wire → view adapter for the HPA page, plus the plain-language mapping and the one-time Apply gate.
 *
 * The HPA page reads the vertical rightsizer payload (one `GET` per cluster): each workload already
 * carries its HPA decision in `horizontal` and its native-VPA target in `container.upstreamVpa`.
 * This adapter turns that into {@link HpaPageView} without a second network call, keeping the two
 * rules that matter testable:
 *
 *  1. `null` in a native-VPA resources field means **unset** and renders a dash; `0` is a backend
 *     bug (Kubernetes cannot express a request of zero), recorded in `anomalies`.
 *  2. the Apply gate fails closed exactly like vertical's: a caller-level refusal or a server that
 *     never evaluated the write gates leaves Apply greyed.
 */

import type {
  CodeView,
  VerticalEngineResponseWire,
  VerticalHorizontalWire,
  VerticalWorkloadWire,
} from '../types/verticalRightsizer';
import type {
  HpaApplyGate,
  HpaApplyRequest,
  HpaCallerRefusal,
  HpaDecisionView,
  HpaEngineView,
  HpaPageView,
  HpaWorkloadView,
  NativeVpaTargetView,
  RpsSizingView,
  VpaCoexistenceKind,
  VpaCoexistenceView,
} from '../types/hpaAutomation';
import {
  WRITE_GATES_NOT_CHECKED,
  isCallerLevelRefusal,
  parseCode,
  writeGateText,
} from './verticalRightsizerAdapter';
import { hpaCodeText, hpaReasonSentence, parseHpaCodes } from '../components/EksDetails/sections/automation/hpaCopy';

export const DASH = '—';

export { hpaCodeText, isCallerLevelRefusal };

// ─── Decision + coexistence ──────────────────────────────────────────────────

const toInt = (raw: number | null | undefined): number | null =>
  raw === null || raw === undefined || !Number.isFinite(raw) ? null : raw;

/**
 * The RPS-based replica-sizing advisory (F3), recommend-only. A sized outcome needs a real replica
 * count AND the reason the backend stamped; a `null`/absent count with a known reason is a dash with
 * that reason; an absent field (a build predating F3) is a plain dash with no reason.
 */
const adaptRps = (h: VerticalHorizontalWire | null | undefined): RpsSizingView => {
  const reason = h?.rpsReason ?? null;
  const replicas = toInt(h?.rpsReplicas);
  const formula = h?.rpsFormula ?? null;
  if (reason === 'RPS_SIZED' && replicas !== null) {
    return { kind: 'sized', replicas, formula };
  }
  if (reason === 'NO_CAPACITY_BASIS') return { kind: 'no-capacity-basis', replicas: null, formula: null };
  if (reason === 'NO_RPS_SOURCE') return { kind: 'no-rps-source', replicas: null, formula: null };
  return { kind: 'none', replicas: null, formula: null };
};

const adaptDecision = (workload: VerticalWorkloadWire): HpaDecisionView => {
  const h = workload.horizontal;
  return {
    state: h?.state ?? 'NOT_EVALUATED',
    minReplicas: toInt(h?.minReplicas),
    maxReplicas: toInt(h?.maxReplicas),
    targetUtilizationPct: toInt(h?.targetCpuUtilizationPct),
    reasons: parseHpaCodes(h?.reasons),
    // `formula` and `autoApplyEligible` were added to HorizontalDto by FEAT-001; older builds omit
    // them, so both are read defensively and autoApplyEligible is never defaulted to true.
    formula: (h as { formula?: string | null } | null)?.formula ?? null,
    autoApplyEligible: (h as { autoApplyEligible?: boolean } | null)?.autoApplyEligible === true,
    rps: adaptRps(h),
  };
};

const hasReason = (codes: CodeView[], code: string): boolean =>
  codes.some((c) => c.kind === 'known' && c.code === code);

/**
 * The native VPA target per container, in the HPA page's shape. A `0` in a resources field is the
 * same payload defect the vertical adapter forbids — recorded and shown as a dash, never a number.
 */
const adaptNativeVpa = (workload: VerticalWorkloadWire, anomalies: string[]): NativeVpaTargetView[] => {
  const targets: NativeVpaTargetView[] = [];
  for (const c of workload.containers ?? []) {
    const vpa = c.upstreamVpa;
    if (!vpa) continue;
    const where = `${workload.ref.namespace}/${workload.ref.name}/${c.name}`;
    const guard = (raw: number | null | undefined, field: string): number | null => {
      if (raw === 0) {
        anomalies.push(`XamOps sent 0 for ${where} VPA ${field}, which Kubernetes doesn't allow, so it's shown as ${DASH}.`);
        return null;
      }
      return toInt(raw);
    };
    targets.push({
      containerName: c.name,
      cpuRequestM: guard(vpa.cpuRequestM, 'cpuRequestM'),
      memRequestMi: guard(vpa.memRequestMi, 'memRequestMi'),
      source: vpa.source ?? null,
      observedAt: vpa.observedAt ?? null,
    });
  }
  return targets;
};

/**
 * The coexistence verdict, resolved from the HPA decision reasons:
 *  - `VPA_MANAGED` → a mutating VPA owns the requests;
 *  - `VPA_NOT_READABLE` → the fail-safe;
 *  - a native VPA that is present but neither of those → Off mode, which changes no pods;
 *  - nothing → no native VPA.
 */
const adaptCoexistence = (decision: HpaDecisionView, nativeVpa: NativeVpaTargetView[]): VpaCoexistenceView => {
  let kind: VpaCoexistenceKind = 'none';
  if (hasReason(decision.reasons, 'VPA_MANAGED')) kind = 'managed';
  else if (hasReason(decision.reasons, 'VPA_NOT_READABLE')) kind = 'unreadable';
  else if (nativeVpa.length > 0) kind = 'off';
  return { kind };
};

const workloadId = (workload: VerticalWorkloadWire): string =>
  `${workload.ref.namespace}/${workload.ref.kind}/${workload.ref.name}`;

const adaptWorkload = (workload: VerticalWorkloadWire, anomalies: string[]): HpaWorkloadView => {
  const decision = adaptDecision(workload);
  const nativeVpa = adaptNativeVpa(workload, anomalies);
  return {
    id: workloadId(workload),
    ref: workload.ref,
    hpaName: workload.hpa?.name ?? null,
    hpaPresent: workload.hpa?.present ?? false,
    replicas: toInt(workload.replicas),
    gitopsManaged: workload.gitopsManaged ?? null,
    decision,
    nativeVpa,
    vpaCoexistence: adaptCoexistence(decision, nativeVpa),
  };
};

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

const adaptEngine = (wire: VerticalEngineResponseWire['engine']): HpaEngineView => ({
  enabled: wire?.enabled === true,
  ready: wire?.ready === true,
  executionPath: wire?.executionPath ?? null,
  clusterAccess: wire?.clusterAccess ?? null,
  directReason: parseCode(wire?.executionPathDetail?.direct?.reason),
  metricsReachable: wire?.metricsSource?.reachable === true,
  lastCycleAt: wire?.lastCycle?.at ?? null,
  lastCycleEvaluated: wire?.lastCycle?.evaluated ?? null,
  lastCycleError: wire?.lastCycle?.error ?? null,
});

/** The HPA page view, adapted once from the vertical read payload. */
export const adaptHpaPage = (wire: VerticalEngineResponseWire): HpaPageView => {
  const anomalies: string[] = [];
  const workloads = (wire?.workloads ?? []).map((w) => adaptWorkload(w, anomalies));
  // HPA-first ordering: workloads that have an HPA, then the rest (D4 recommend-new candidates).
  const sorted = [...workloads].sort((a, b) => Number(b.hpaPresent) - Number(a.hpaPresent));
  const hpaWorkloads = sorted.filter(
    (w) => w.hpaPresent || hasReason(w.decision.reasons, 'HPA_RECOMMENDED_NEW'),
  );
  const vpaWorkloads = sorted.filter((w) => w.vpaCoexistence.kind !== 'none');
  return {
    engine: adaptEngine(wire?.engine),
    workloads: sorted,
    hpaWorkloads,
    vpaWorkloads,
    gateReasons: parseCodes(wire?.gateReasons),
    gateDetails: adaptGateDetails(wire?.gateDetails),
    writeGatesChecked: wire?.writeGatesChecked === true,
    horizontalEvaluated: workloads.some((w) => w.decision.state !== 'NOT_EVALUATED'),
    anomalies,
  };
};

/** Gate reasons parsed with the shared vertical label table (write gates, cluster-access gates). */
const parseCodes = (raw: string[] | null | undefined): CodeView[] =>
  (raw ?? []).map(parseCode).filter((c): c is CodeView => c !== null);

// ─── Apply body ──────────────────────────────────────────────────────────────

/**
 * The HPA apply body for one workload: only the fields the decision moves.
 *
 * A null bound/target in the decision means "leave alone", so it is dropped from the body — never
 * sent as null, which the backend would read as a deliberate value. Returns null when the decision
 * is not a CHANGE or moves nothing, so the caller never submits an empty apply.
 */
export const toHpaApplyRequest = (decision: HpaDecisionView, dryRun: boolean): HpaApplyRequest | null => {
  if (decision.state !== 'CHANGE') return null;
  const body: HpaApplyRequest = { dryRun };
  let moved = false;
  if (decision.minReplicas !== null) {
    body.minReplicas = decision.minReplicas;
    moved = true;
  }
  if (decision.maxReplicas !== null) {
    body.maxReplicas = decision.maxReplicas;
    moved = true;
  }
  if (decision.targetUtilizationPct !== null) {
    body.targetUtilizationPct = decision.targetUtilizationPct;
    moved = true;
  }
  return moved ? body : null;
};

// ─── Manual apply gate (mirrors verticalRightsizerAdapter.manualApplyGate) ───

const WRITE_GATES = ['DEMO', 'NO_USER_PERMISSION', 'READ_ONLY_CONNECTION'] as const;
const PATH_GATES = ['CLUSTER_ACCESS_NOT_CONFIGURED', 'CLUSTER_UNREACHABLE'] as const;

const findGate = (gateReasons: CodeView[], code: string): CodeView | undefined =>
  gateReasons.find((g) => g.kind === 'known' && g.code === code);

const label = (code: string): string => {
  const parsed = parseCode(code);
  return parsed ? hpaCodeText(parsed) : code;
};

/**
 * The HpaReason codes that are a safety refusal on this workload (never a success).
 * `GITOPS_MANAGED` is both a gate concept and an HpaReason; here it is the workload's own refusal.
 */
const WORKLOAD_REFUSALS = [
  'GITOPS_MANAGED',
  'PDB_FLOOR_BINDS',
  'USER_MAX_RESPECTED',
  'BELOW_OBSERVED_NEED',
  'HPA_NOT_READABLE',
  'VPA_NOT_READABLE',
] as const;

/**
 * Whether a user may submit a one-time HPA apply for one workload, and why not.
 *
 * The order mirrors the server's (FEAT-002 gate order): a caller-level refusal already answered,
 * then the write gates, then a checked-gates guard, a ready controller / execution path, then the
 * workload's own HpaReason refusals and the cooldown. The first that fails is the reason shown.
 */
export const hpaApplyGate = (
  view: HpaPageView,
  workload: HpaWorkloadView,
  dryRun: boolean,
  callerRefusal: HpaCallerRefusal | null = null,
): HpaApplyGate => {
  const engine: HpaEngineView = view.engine;
  const changes = toHpaApplyRequest(workload.decision, dryRun);
  const blocked = (reason: string, nextStep: string | null = null): HpaApplyGate => ({
    enabled: false,
    reason,
    nextStep,
    changes,
  });
  const { gateReasons, gateDetails } = view;

  if (callerRefusal) {
    const text = writeGateText(callerRefusal.code, callerRefusal.detail);
    if (text) return blocked(text.reason, text.nextStep);
  }
  for (const code of WRITE_GATES) {
    if (findGate(gateReasons, code)) {
      const text = writeGateText(code, gateDetails[code]);
      return blocked(text!.reason, text!.nextStep);
    }
  }
  if (!view.writeGatesChecked) return blocked(WRITE_GATES_NOT_CHECKED);
  if (!engine.enabled || !engine.ready || findGate(gateReasons, 'CONTROLLER_NOT_READY')) {
    return blocked('Applying is off in this environment');
  }
  if (engine.executionPath === null || engine.executionPath === 'NONE') {
    const pathGate = PATH_GATES.map((code) => findGate(gateReasons, code)).find((g) => g !== undefined);
    const reason = engine.directReason ?? pathGate;
    return blocked(reason ? hpaCodeText(reason) : 'No way to send changes to this cluster');
  }
  if (workload.gitopsManaged === true) return blocked(label('GITOPS_MANAGED'));
  // The workload's own HPA refusals (FEAT-002): the first one is why.
  const refusal = workload.decision.reasons.find(
    (r) => r.kind === 'known' && (WORKLOAD_REFUSALS as readonly string[]).includes(r.code),
  );
  if (refusal && refusal.kind === 'known') {
    return blocked(hpaReasonSentence(refusal, { name: workload.hpaName }));
  }
  if (hasReason(workload.decision.reasons, 'COOLDOWN')) return blocked(label('COOLDOWN'));
  // D4 recommend-new is never auto-created; there is nothing to apply by click.
  if (hasReason(workload.decision.reasons, 'HPA_RECOMMENDED_NEW')) {
    return blocked('XamOps never creates an HPA on its own. Add it to your manifest.');
  }
  if (changes === null) return blocked('No change suggested');
  return { enabled: true, reason: null, nextStep: null, changes };
};
