/**
 * Pure derivations for the Automation pages (UI v3 plan §2): every number the Overview, Workloads,
 * workload detail and Policies pages show, from endpoint 1 (the Workload autoscaler's read), the
 * XamOps agent's dashboard and capabilities. Nothing here fetches, reads a clock or renders.
 *
 * The rules they keep (CLAUDE.md, glossary rule 4): an amount XamOps can't work out is `null`, never
 * 0; a workload without a suggestion stays at its current request; and a change is only "applied"
 * when endpoint 1 recorded it (`lastAction`) or this session's reply confirmed it.
 */
import {
  COOLDOWN_MINUTES,
  CPU_FLOOR,
  DASH,
  MEM_FLOOR,
  codeText,
  findingSentence,
  formatInstant,
  formatMemoryMi,
  formatMib,
  formatMillis,
  formatText,
  manualApplyGate,
  parseCode,
  reasonSentence,
  skipSentence,
  windowWords,
  workloadStatus,
  workloadWindowHours,
  writeGateText,
  WRITE_GATES_NOT_CHECKED,
} from '../../../../api/verticalRightsizerAdapter';
import type {
  ApplyOutcomeWire,
  ApplyResult,
  CodeView,
  ContainerView,
  VerticalEngineView,
  WorkloadView,
} from '../../../../types/verticalRightsizer';
import type { HpaPageView, HpaWorkloadView } from '../../../../types/hpaAutomation';
import type { EksDashboard } from '../../../../types/eksTypes';
import type {
  AgentStatus,
  AutomationDashboardDto,
  AutomationNodeFleet,
  AutomationRebalanceStatus,
  CapabilitiesCluster,
} from '../../../../types/k8sAutomation';
import {
  capacityTypeCounts,
  clusterCapacity,
  clusterNodes,
  clusterPods,
  clusterRequestTotals,
  hpaStates,
  hpasFor,
  isEmptyDashboard,
  nodeGroups,
  nodeProvisioners,
  nodeRows,
  podPlacement,
  sumRequests,
  type NodeRow,
} from '../../../../utils/k8sDerivations';
import { toNumber } from '../../../../utils/k8sQuantities';
import { formatIST } from '../../../../utils/timeUtils';
import type { Lane } from '../../../CloudK8s/shared/charts/LanesTimeline';
import type { HowStepChip, HowStepKey } from '../../../CloudK8s/shared/status/HowItWorks';
import {
  LIFECYCLE_STAGES,
  deriveLifecycle,
  type ApplyProgress,
  type LifecycleStage,
  type StageState,
} from '../../../CloudK8s/shared/status/lifecycle';
import {
  RESULT_LABELS,
  RESULT_TONES,
  WORKLOAD_STATUS_TONES,
  resultKeyForTerminal,
  type ResultKey,
  type Tone,
  type WorkloadStatusKey,
} from '../../../CloudK8s/shared/status/statusTones';

export type Resource = 'cpu' | 'memory';

export const MINUTE_MS = 60_000;
export const DAY_MS = 24 * 60 * MINUTE_MS;
/** XamOps waits this long after a change before it suggests another (glossary {cooldown}). */
export const COOLDOWN_MS = COOLDOWN_MINUTES * MINUTE_MS;

const hasCode = (codes: readonly CodeView[], code: string): boolean =>
  codes.some((c) => c.kind === 'known' && c.code === code);

const knownCode = (code: CodeView | null): string | null => (code && code.kind === 'known' ? code.code : null);

const plural = (n: number, one: string, many: string = `${one}s`): string => (n === 1 ? one : many);

// ─── Units (glossary v2 rule 6) ──────────────────────────────────────────────

/** Totals and KPI cards: vCPU with two decimals ("1.15 vCPU"). A sliver above zero isn't "0.00". */
export const formatVcpu = (millicores: number): string => {
  if (millicores > 0 && millicores < 5) return '< 0.01 vCPU';
  return `${(millicores / 1000).toFixed(2)} vCPU`;
};

/** Memory: Mi below 1024 Mi, GiB with up to two decimals from there (glossary rule 6). */
export const formatMemory = (mi: number): string => formatMemoryMi(Math.round(mi));

/** "0.95 vCPU and 750Mi", leaving out a side that doesn't move. */
export const formatCapacity = (cpuM: number, memMi: number): string => {
  const parts: string[] = [];
  if (cpuM > 0) parts.push(formatVcpu(cpuM));
  if (memMi > 0) parts.push(formatMemory(memMi));
  return parts.length > 0 ? parts.join(' and ') : formatVcpu(0);
};

/** An engine instant (nanoseconds and all) as epoch ms, or null when it is missing or unreadable. */
export function instantMs(at: string | null | undefined): number | null {
  if (!at || at.trim() === '') return null;
  const ms = Date.parse(at.trim().replace(/(\.\d{3})\d+/, '$1'));
  return Number.isFinite(ms) ? ms : null;
}

/** An epoch-ms time in IST, the table form ("4:13 PM IST", "Oct 12, 4:13 PM IST"). */
export const istShort = (ms: number): string => formatIST(new Date(ms).toISOString(), { short: true });

// ─── Per-workload change (current − suggested request) ─────────────────────

/**
 * One container's change for one pod, current − suggested: positive frees, negative adds.
 * 0 when nothing would move (no suggestion, or an advisory one for unset requests that XamOps won't
 * apply); null when one side is unset or unusable, so it can't be worked out.
 */
function containerDelta(c: ContainerView, resource: Resource): number | null {
  if (c.recommended === null) return 0;
  if (hasCode(c.reasons, 'REQUESTS_UNSET')) return 0;
  const now = resource === 'cpu' ? c.current.cpuRequestM : c.current.memRequestMi;
  const next = resource === 'cpu' ? c.recommended.cpuRequestM : c.recommended.memRequestMi;
  if (now.kind === 'unset' && next.kind === 'unset') return 0;
  if (now.kind !== 'value' || next.kind !== 'value') return null;
  return now.value - next.value;
}

export interface WorkloadChange {
  /** current − suggested request of one pod, over its containers (CPU in m, memory in Mi). */
  perPod: { cpuM: number | null; memMi: number | null };
  /** The same across every replica: positive frees, negative adds. Null when unknown, never 0-filled. */
  total: { cpuM: number | null; memMi: number | null };
  /** Why `total` is unknown although one pod's change is known. */
  totalUnknown: 'replicas-unknown' | 'pods-differ' | null;
}

/**
 * The request a workload's suggestion frees (or adds) = (current − recommended) × replicas. An
 * unknown replica count is not 1, and pods that run different sizes make "now" the workload's own
 * size, so in both cases the total is unknown (the same rule `workloadStatus` keeps).
 */
export function workloadChange(w: WorkloadView): WorkloadChange {
  const sum = (resource: Resource): number | null => {
    let total = 0;
    for (const c of w.containers) {
      const d = containerDelta(c, resource);
      if (d === null) return null;
      total += d;
    }
    return total;
  };
  const perPod = { cpuM: sum('cpu'), memMi: sum('memory') };
  const moves = (perPod.cpuM !== null && perPod.cpuM !== 0) || (perPod.memMi !== null && perPod.memMi !== 0);
  const differ = w.containers.some((c) => c.recommended !== null && c.podsDisagree);
  const replicas = w.replicas;
  let totalUnknown: WorkloadChange['totalUnknown'] = null;
  if (moves && differ) totalUnknown = 'pods-differ';
  else if (moves && (replicas === null || replicas === undefined || !Number.isFinite(replicas))) totalUnknown = 'replicas-unknown';
  const times = (d: number | null): number | null => {
    if (d === null) return null;
    if (d === 0) return 0;
    return totalUnknown ? null : d * (replicas as number);
  };
  return { perPod, total: { cpuM: times(perPod.cpuM), memMi: times(perPod.memMi) }, totalUnknown };
}

/** The part of a change that frees request ("saves"), or null when unknown. */
export const freedPart = (d: number | null): number | null => (d === null ? null : Math.max(0, d));
/** The part of a change that adds request ("needs more"), or null when unknown. */
export const addedPart = (d: number | null): number | null => (d === null ? null : Math.max(0, -d));

/** One pod's request now and with the suggestion, over its containers; null when none is set. */
export interface RequestPair {
  now: number | null;
  suggested: number | null;
}

export function podRequests(w: WorkloadView, resource: Resource): RequestPair {
  let now: number | null = null;
  let suggested: number | null = null;
  for (const c of w.containers) {
    const cur = resource === 'cpu' ? c.current.cpuRequestM : c.current.memRequestMi;
    const rec = c.recommended ? (resource === 'cpu' ? c.recommended.cpuRequestM : c.recommended.memRequestMi) : null;
    const advisory = hasCode(c.reasons, 'REQUESTS_UNSET');
    const curValue = cur.kind === 'value' ? cur.value : null;
    const next = !advisory && rec && rec.kind === 'value' ? rec.value : curValue;
    if (curValue !== null) now = (now ?? 0) + curValue;
    if (next !== null) suggested = (suggested ?? 0) + next;
  }
  return { now, suggested };
}

// ─── Workload status (glossary §2.1) ─────────────────────────────────────────

/** The stable status key for `workloadStatus`'s words, so its chip gets the right tone and icon. */
export function statusKeyOf(status: string): WorkloadStatusKey {
  if (status === 'Over-provisioned') return 'over-provisioned';
  if (status.startsWith('Needs more')) return 'needs-more';
  if (status === 'Right-sized') return 'right-sized';
  if (status === 'Not enough data yet') return 'not-enough-data';
  if (status.startsWith('Skipped')) return 'skipped';
  if (status === 'Off') return 'off';
  if (status === 'Left alone: changed recently') return 'left-alone-recently';
  if (status.startsWith('Left alone')) return 'left-alone-managed';
  if (status.startsWith('Not running')) return 'not-running';
  return 'off';
}

export interface WorkloadStatusChip {
  key: WorkloadStatusKey;
  tone: Tone;
  icon: string;
  /** `workloadStatus`'s status words. */
  label: string;
  /** Its second line. */
  detail: string;
}

export function workloadStatusChip(w: WorkloadView): WorkloadStatusChip {
  const s = workloadStatus(w);
  const key = statusKeyOf(s.status);
  return { key, ...WORKLOAD_STATUS_TONES[key], label: s.status, detail: s.detail };
}

// ─── Recorded changes (endpoint 1 `lastAction`) ──────────────────────────────

/** The outcome endpoint 1 records for a mode switch (WorkloadActionRecorder.OUTCOME_POLICY_CHANGE). */
export const POLICY_CHANGE = 'POLICY_CHANGE';

export interface ActionResult {
  key: ResultKey | 'mode-change' | 'other';
  tone: Tone;
  icon: string;
  label: string;
}

const KNOWN_TERMINALS = new Set([
  'APPLIED', 'PARTIALLY_APPLIED', 'DRY_RUN', 'DEFERRED', 'INFEASIBLE', 'STUCK_IN_PROGRESS',
  'RESTART_DETECTED', 'NOT_READY', 'REFUSED', 'EXPIRED', 'FAILED',
]);

/** A recorded outcome as a result chip (glossary §2.2); an unknown one reads "Other: …", never a success. */
export function actionResult(outcome: string | null | undefined, dryRun: boolean | null | undefined): ActionResult | null {
  if (!outcome || outcome.trim() === '') return null;
  if (outcome === POLICY_CHANGE) return { key: 'mode-change', tone: 'info', icon: 'mdi:tune-variant', label: 'Mode changed' };
  if (dryRun === true || outcome === 'DRY_RUN') {
    return { key: 'test-run', ...RESULT_TONES['test-run'], label: RESULT_LABELS['test-run'] };
  }
  if (!KNOWN_TERMINALS.has(outcome)) return { key: 'other', tone: 'off', icon: 'mdi:help-circle-outline', label: `Other: ${outcome}` };
  const key = resultKeyForTerminal(outcome);
  return { key, ...RESULT_TONES[key], label: RESULT_LABELS[key] };
}

type LastAction = WorkloadView['lastAction'];

/** A recorded change that touched pods: anything endpoint 1 records except a mode switch or a test run. */
export const isRecordedPodChange = (a: LastAction): boolean =>
  !!a && a.dryRun !== true && !!a.outcome && a.outcome !== POLICY_CHANGE;

/** Applied live: APPLIED or PARTIALLY_APPLIED, not a test run (UI v3 plan §2 "Saving now"). */
export const isLiveApplied = (a: LastAction): boolean =>
  !!a && a.dryRun === false && (a.outcome === 'APPLIED' || a.outcome === 'PARTIALLY_APPLIED');

export interface RecordedChange {
  workload: WorkloadView;
  at: number;
  result: ActionResult;
  /** False for a test run. */
  live: boolean;
}

/** Each workload's latest recorded change within the last `days` days, newest first. */
export function recentChanges(workloads: readonly WorkloadView[], now: number, days = 7): RecordedChange[] {
  const from = now - days * DAY_MS;
  return workloads
    .flatMap((w): RecordedChange[] => {
      const a = w.lastAction;
      if (!a) return [];
      const at = instantMs(a.at);
      if (at === null || at < from || at > now + 5 * MINUTE_MS) return [];
      const result = actionResult(a.outcome, a.dryRun);
      return result ? [{ workload: w, at, result, live: a.dryRun !== true }] : [];
    })
    .sort((a, b) => b.at - a.at);
}

/** The newest recorded change of any kind, for the "Last change" clause. */
export function latestRecorded(workloads: readonly WorkloadView[]): RecordedChange | null {
  let best: RecordedChange | null = null;
  for (const w of workloads) {
    const a = w.lastAction;
    const at = instantMs(a?.at);
    const result = a ? actionResult(a.outcome, a.dryRun) : null;
    if (at === null || !result) continue;
    if (!best || at > best.at) best = { workload: w, at, result, live: a!.dryRun !== true };
  }
  return best;
}

export interface Cooldown {
  workload: WorkloadView;
  at: number;
  /** `lastAction.at` + COOLDOWN_MINUTES. */
  endsAt: number;
}

/** The newest recorded pod change and when its wait ends. A mode switch starts no wait. */
export function latestCooldown(workloads: readonly WorkloadView[]): Cooldown | null {
  let best: Cooldown | null = null;
  for (const w of workloads) {
    if (!isRecordedPodChange(w.lastAction)) continue;
    const at = instantMs(w.lastAction!.at);
    if (at === null) continue;
    if (!best || at > best.at) best = { workload: w, at, endsAt: at + COOLDOWN_MS };
  }
  return best;
}

// ─── KPI counts ──────────────────────────────────────────────────────────────

export interface ResultCount {
  key: string;
  tone: Tone;
  icon: string;
  label: string;
  count: number;
}

export interface AutomationKpis {
  analysed: number;
  overProvisioned: number;
  needsMore: number;
  /** What the over-provisioned workloads' suggestions free, across their replicas. */
  canSave: { cpuM: number; memMi: number; counted: number };
  /** What the needs-more workloads' suggestions add. */
  needsMoreAdds: { cpuM: number; memMi: number; counted: number };
  /** Over-provisioned or needs-more workloads whose amount is unknown, left out of the totals. */
  notCounted: number;
  /** Workloads whose latest recorded change was applied live. */
  liveApplied: number;
  /** Workloads whose latest recorded change is within the last 7 days. */
  changes7d: number;
  changes7dByResult: ResultCount[];
}

export function automationKpis(workloads: readonly WorkloadView[], now: number): AutomationKpis {
  const canSave = { cpuM: 0, memMi: 0, counted: 0 };
  const needsMoreAdds = { cpuM: 0, memMi: 0, counted: 0 };
  let overProvisioned = 0;
  let needsMore = 0;
  let notCounted = 0;
  for (const w of workloads) {
    const key = workloadStatusChip(w).key;
    if (key !== 'over-provisioned' && key !== 'needs-more') continue;
    if (key === 'over-provisioned') overProvisioned += 1;
    else needsMore += 1;
    const { total } = workloadChange(w);
    if (total.cpuM === null || total.memMi === null) {
      notCounted += 1;
      continue;
    }
    const bucket = key === 'over-provisioned' ? canSave : needsMoreAdds;
    const part = key === 'over-provisioned' ? freedPart : addedPart;
    bucket.cpuM += part(total.cpuM) ?? 0;
    bucket.memMi += part(total.memMi) ?? 0;
    bucket.counted += 1;
  }
  const changes = recentChanges(workloads, now);
  const byResult = new Map<string, ResultCount>();
  for (const c of changes) {
    const entry = byResult.get(c.result.key) ?? { key: c.result.key, tone: c.result.tone, icon: c.result.icon, label: c.result.label, count: 0 };
    entry.count += 1;
    byResult.set(c.result.key, entry);
  }
  return {
    analysed: workloads.length,
    overProvisioned,
    needsMore,
    canSave,
    needsMoreAdds,
    notCounted,
    liveApplied: workloads.filter((w) => isLiveApplied(w.lastAction)).length,
    changes7d: changes.length,
    changes7dByResult: [...byResult.values()].sort((a, b) => b.count - a.count),
  };
}

// ─── How it works: the state chip under each step (design-delta §4.2; glossary §4) ──

const WRITE_GATES = ['DEMO', 'NO_USER_PERMISSION', 'READ_ONLY_CONNECTION'] as const;

/** The least-covered measuring workload's "{covered} of {window}", when its coverage is known. */
function measuringText(w: WorkloadView): { covered: string; window: string } | null {
  const hours = workloadWindowHours(w);
  const coverage = w.provenance?.sampleCoverage;
  if (hours === null || coverage === null || coverage === undefined || !Number.isFinite(coverage)) return null;
  return { covered: windowWords(Math.round(coverage * hours)), window: windowWords(hours) };
}

/** The chips from endpoint 1 only: no running apply (BE-11) or full history (BE-5) is invented. */
export function howItWorksChips(view: VerticalEngineView, now: number): Partial<Record<HowStepKey, HowStepChip>> {
  const chips: Partial<Record<HowStepKey, HowStepChip>> = {};
  const { engine, gateReasons, workloads } = view;
  const has = (code: string) => hasCode(gateReasons, code);
  const keys = workloads.map((w) => workloadStatusChip(w).key);
  const smaller = keys.filter((k) => k === 'over-provisioned').length;
  const larger = keys.filter((k) => k === 'needs-more').length;

  // Measure.
  if (!engine.enabled) {
    chips.measure = { tone: 'off', icon: 'mdi:minus-circle-outline', label: 'Off in this environment' };
  } else if (has('NO_METRICS_SOURCE') || !engine.metricsReachable) {
    chips.measure = { tone: 'bad', icon: 'mdi:alert-octagon-outline', label: 'No usage data', detail: "XamOps couldn't reach Prometheus in the cluster." };
  } else if (engine.lastCycleError) {
    chips.measure = { tone: 'bad', icon: 'mdi:alert-octagon-outline', label: 'Last analysis failed', detail: engine.lastCycleError };
  } else {
    const measuring = workloads.filter((_w, i) => keys[i] === 'not-enough-data');
    if (measuring.length > 0) {
      const texts = measuring.map((w) => ({ w, t: measuringText(w) })).filter((x) => x.t !== null);
      texts.sort((a, b) => (a.w.provenance?.sampleCoverage ?? 0) - (b.w.provenance?.sampleCoverage ?? 0));
      const least = texts[0]?.t ?? null;
      chips.measure = {
        tone: 'info',
        icon: 'mdi:progress-clock',
        label: least ? `Measuring: ${least.covered} of ${least.window}` : 'Measuring',
        detail: `${measuring.length} ${plural(measuring.length, 'workload')} still measuring.`,
      };
    } else {
      const at = instantMs(engine.lastCycleAt);
      chips.measure = {
        tone: 'good',
        icon: 'mdi:check-circle',
        label: 'Up to date',
        detail: `${workloads.length} ${plural(workloads.length, 'workload')}${at !== null ? `, last analysed ${istShort(at)}` : ''}.`,
      };
    }
  }

  // Decide.
  if (engine.enabled && smaller + larger > 0) {
    chips.decide = {
      tone: 'info',
      icon: 'mdi:scale-balance',
      label: `${smaller + larger} ${plural(smaller + larger, 'suggestion')}`,
      detail: `${smaller} smaller, ${larger} larger`,
    };
  } else if (engine.enabled && workloads.length > 0) {
    chips.decide = { tone: 'good', icon: 'mdi:check-circle', label: 'No change needed' };
  }

  // Apply.
  const gate = WRITE_GATES.find((g) => has(g));
  if (!engine.enabled || !engine.ready || has('CONTROLLER_NOT_READY')) {
    chips.apply = { tone: 'off', icon: 'mdi:minus-circle-outline', label: 'Applying is off here', detail: 'You can still review every suggestion.' };
  } else if (gate) {
    chips.apply = { tone: 'off', icon: 'mdi:lock-outline', label: 'View only', detail: writeGateText(gate, view.gateDetails[gate])?.reason };
  } else if (!view.writeGatesChecked) {
    chips.apply = { tone: 'off', icon: 'mdi:lock-outline', label: 'View only', detail: WRITE_GATES_NOT_CHECKED };
  } else {
    const ready = workloads.filter((w) => manualApplyGate(view, w).enabled).length;
    const automatic = workloads.filter((w) => w.policy?.mode === 'AUTO').length;
    if (ready > 0) {
      chips.apply = {
        tone: 'warn',
        icon: 'mdi:gesture-tap-button',
        label: 'Waiting for you',
        detail: automatic > 0
          ? `${ready} ready. ${automatic} on Apply automatically.`
          : `${ready} ready. Mode is Suggest only: nothing applies on its own.`,
      };
    } else if (smaller + larger > 0) {
      const blocked = workloads
        .filter((_w, i) => keys[i] === 'over-provisioned' || keys[i] === 'needs-more')
        .map((w) => manualApplyGate(view, w).reason)
        .find((r): r is string => !!r);
      chips.apply = { tone: 'off', icon: 'mdi:minus-circle-outline', label: 'Nothing can be applied now', detail: blocked };
    }
  }

  // Verify: the latest recorded pod change.
  const cooldown = latestCooldown(workloads);
  if (cooldown) {
    const result = actionResult(cooldown.workload.lastAction?.outcome, cooldown.workload.lastAction?.dryRun);
    const time = istShort(cooldown.at);
    const detail = cooldown.workload.ref.name;
    if (result?.key === 'applied') chips.verify = { tone: 'good', icon: 'mdi:check-circle', label: `${time}: confirmed`, detail };
    else if (result?.key === 'partly-applied') chips.verify = { tone: 'warn', icon: 'mdi:progress-alert', label: `${time}: partly applied`, detail };
    else if (result?.key === 'failed') chips.verify = { tone: 'bad', icon: 'mdi:alert-octagon-outline', label: `${time}: failed`, detail };
    else if (result) chips.verify = { tone: result.tone, icon: result.icon, label: `${time}: ${result.label}`, detail };
  }

  // Watch.
  if (cooldown && cooldown.endsAt > now) {
    chips.watch = {
      tone: 'info',
      icon: 'mdi:eye-outline',
      label: `Until ${istShort(cooldown.endsAt)}`,
      detail: `${cooldown.workload.ref.name}: XamOps waits ${COOLDOWN_MINUTES} minutes after its change, then measures again.`,
    };
  } else {
    chips.watch = { tone: 'off', icon: 'mdi:minus-circle-outline', label: 'Nothing to watch' };
  }

  return chips;
}

/** The collapsed How it works line ("Now: …"), from the chips that are about now. */
export function howSummary(chips: Partial<Record<HowStepKey, HowStepChip>>): string {
  const parts = [chips.decide?.label, chips.apply?.label, chips.watch?.tone === 'info' ? `watching ${chips.watch.label.toLowerCase()}` : null];
  const text = parts.filter((p): p is string => !!p).join(' · ');
  return text ? `${text}.` : 'nothing to report.';
}

// ─── Requested, used and suggested by namespace ─────────────────────────────

export interface NamespaceRow {
  namespace: string;
  /** Requests of the namespace's pods, from the XamOps agent (m or Mi); null without the agent's pods. */
  requested: number | null;
  /** Use, from the agent's namespaceUtilization; null when it didn't report the namespace. */
  used: number | null;
  /**
   * What the namespace would request with every XamOps suggestion applied (endpoint 1): pods XamOps
   * doesn't size stay at their request. Null when a suggested workload's amount is unknown.
   */
  suggested: number | null;
}

export interface NamespaceRollup {
  rows: NamespaceRow[];
  /** The agent reported pods for this cluster. */
  agentPods: boolean;
  /** The agent reported namespace use. */
  agentUsage: boolean;
}

/**
 * Only namespaces with analysed workloads (design-delta §3.1). Requested and used cover every pod
 * in the namespace; suggested is requested minus what the analysed workloads' suggestions free (or
 * plus what they add), so a namespace's other pods count at their request, never as "freed".
 */
export function namespaceRollup(
  workloads: readonly WorkloadView[],
  dashboard: EksDashboard | null | undefined,
  resource: Resource,
): NamespaceRollup {
  const allPods = clusterPods(dashboard);
  const agentPods = allPods.length > 0;
  // Finished pods hold no request on a node.
  const pods = allPods.filter((p) => p.phase !== 'Succeeded' && p.phase !== 'Failed');
  const usage = Array.isArray(dashboard?.namespaceUtilization) ? (dashboard!.namespaceUtilization as Array<Record<string, unknown>>) : [];
  const namespaces = [...new Set(workloads.map((w) => w.ref.namespace))];
  const rows = namespaces.map((namespace): NamespaceRow => {
    let requested: number | null = null;
    if (agentPods) {
      const sum = sumRequests(pods.filter((p) => p.namespace === namespace).flatMap((p) => p.containers));
      requested = resource === 'cpu' ? sum.cpuRequestM : sum.memRequestMi;
    }
    const row = usage.find((u) => u && u.namespace === namespace);
    const used = row ? toNumber(resource === 'cpu' ? row.cpuUsageMillicores : row.memoryUsageMB) : null;
    let net: number | null = 0;
    for (const w of workloads) {
      if (w.ref.namespace !== namespace) continue;
      const d = workloadChange(w).total[resource === 'cpu' ? 'cpuM' : 'memMi'];
      if (d === null) {
        net = null;
        break;
      }
      net += d;
    }
    const suggested = requested !== null && net !== null && requested - net >= 0 ? requested - net : null;
    return { namespace, requested, used, suggested };
  });
  rows.sort((a, b) => (b.requested ?? -1) - (a.requested ?? -1) || a.namespace.localeCompare(b.namespace));
  return { rows, agentPods, agentUsage: usage.length > 0 };
}

// ─── The 7-day timeline of latest changes ────────────────────────────────────

/** One lane per workload with a recorded change in the window, a dot at its time, toned by outcome. */
export function timelineLanes(changes: readonly RecordedChange[], now: number, days = 7): Lane[] {
  const span = days * DAY_MS;
  const from = now - span;
  const count = new Map<string, number>();
  for (const c of changes) count.set(c.workload.ref.name, (count.get(c.workload.ref.name) ?? 0) + 1);
  return changes.map((c) => ({
    name: (count.get(c.workload.ref.name) ?? 0) > 1 ? `${c.workload.ref.namespace}/${c.workload.ref.name}` : c.workload.ref.name,
    feature: 'workload' as const,
    entries: [{ at: (c.at - from) / span, tone: c.result.tone, dryRun: !c.live, label: `${istShort(c.at)}: ${c.result.label}` }],
  }));
}

// ─── Lifecycle per workload (design-delta §4.3; glossary §5) ─────────────────

export interface LifecycleContext {
  /** An apply for this workload is in flight now. */
  applying?: boolean;
  /** This session's reply for this workload. */
  reply?: ApplyResult;
  /** When this session's reply arrived (epoch ms): the time XamOps answered for the change. */
  replyAt?: number;
  now: number;
}

export interface LifecycleView {
  states: StageState[];
  /** The compact form's one label (glossary §5). */
  label: string;
  /** The full form's line per stage. */
  details: Partial<Record<LifecycleStage, string>>;
}

const SKIP_SHORT: Record<string, string> = {
  PROTECTED_NAMESPACE: 'system namespace',
  SINGLE_REPLICA: '1 replica',
  ROLLOUT_IN_PROGRESS: 'rolling out',
  LOCKED: 'locked',
  HPA_VOLATILE: 'HPA just scaled it',
  POLICY_OFF: 'rightsizing off',
  VPA_MANAGED: 'a Kubernetes VPA changes it',
};

const ALL_TODO: StageState[] = LIFECYCLE_STAGES.map((stage) => ({ stage, status: 'todo', tone: 'off' }));

/** This session's live outcome for a workload, when its reply carried one. A test run is not one. */
export function liveOutcome(reply: ApplyResult | undefined): ApplyOutcomeWire | null {
  if (!reply || reply.kind !== 'submitted' || reply.dryRun === true || !reply.result) return null;
  return reply.result;
}

/**
 * Whether a live outcome left a pod changed, or one that may still change: what starts the wait
 * (VerticalApplyService, review-2 SF4). A declined pod proved back changed nothing.
 */
export function outcomeStartsWait(o: ApplyOutcomeWire): boolean {
  const pods = o.pods ?? [];
  if (pods.some((p) => p.changed === true || (p.revert != null && p.revert.state !== 'REVERTED'))) return true;
  if (pods.some((p) => p.changed === false)) return false;
  return ['APPLIED', 'PARTIALLY_APPLIED', 'RESTART_DETECTED', 'NOT_READY'].includes(o.terminal ?? '') || o.reverted === false;
}

function progressOf(terminal: string | null | undefined): ApplyProgress | undefined {
  switch (terminal) {
    case 'APPLIED':
      return 'APPLIED';
    case 'DEFERRED':
      return 'DEFERRED';
    case 'PARTIALLY_APPLIED':
      return 'PARTIALLY_APPLIED';
    case 'STUCK_IN_PROGRESS':
      return 'STUCK';
    case 'INFEASIBLE':
      return 'INFEASIBLE';
    case 'RESTART_DETECTED':
      return 'RESTART_DETECTED';
    case 'NOT_READY':
      return 'NOT_READY';
    case 'FAILED':
    case 'EXPIRED':
      return 'FAILED';
    default:
      // A test run, a refusal, or nothing reported: no change was attempted.
      return undefined;
  }
}

const PROBLEM_LABEL: Partial<Record<ApplyProgress, string>> = {
  DEFERRED: 'Deferred: no room on its node',
  INFEASIBLE: "Can't fit on its node",
  PARTIALLY_APPLIED: 'Partly applied',
  STUCK: 'Not confirmed: still resizing',
  RESTART_DETECTED: 'Restart detected',
  NOT_READY: 'Stopped being ready',
  FAILED: 'Last change failed',
};

/** "CPU 1000m → 25m, memory 1 GiB → 250Mi": one pod's move, over its containers. */
export function suggestionText(w: WorkloadView): string | null {
  const parts: string[] = [];
  const cpu = podRequests(w, 'cpu');
  const mem = podRequests(w, 'memory');
  if (cpu.now !== null && cpu.suggested !== null && cpu.now !== cpu.suggested) {
    parts.push(`CPU ${formatMillis({ kind: 'value', value: cpu.now })} → ${formatMillis({ kind: 'value', value: cpu.suggested })}`);
  }
  if (mem.now !== null && mem.suggested !== null && mem.now !== mem.suggested) {
    parts.push(`memory ${formatMib({ kind: 'value', value: mem.now })} → ${formatMib({ kind: 'value', value: mem.suggested })}`);
  }
  return parts.length > 0 ? parts.join(', ') : null;
}

export function workloadLifecycle(w: WorkloadView, ctx: LifecycleContext): LifecycleView {
  const status = workloadStatusChip(w);
  const skip = knownCode(w.skipReason);
  const window = windowWords(workloadWindowHours(w));

  // Not sized by a rule (skipped, or rightsizing off): nothing in the loop runs for it.
  if (status.key === 'skipped' || status.key === 'off') {
    const short = skip && SKIP_SHORT[skip] ? SKIP_SHORT[skip] : w.skipReason ? codeText(w.skipReason) : 'rightsizing off';
    return { states: ALL_TODO, label: `Not sized: ${short}`, details: { measuring: status.detail } };
  }

  // Still measuring.
  if (status.key === 'not-enough-data') {
    const m = measuringText(w);
    const label = m ? `Measuring: ${m.covered} of ${m.window}` : 'Measuring';
    return { states: deriveLifecycle({ measured: false, hasSuggestion: false }), label, details: { measuring: m ? label : status.detail } };
  }

  const live = liveOutcome(ctx.reply);
  let applyProgress: ApplyProgress | undefined = ctx.applying ? 'RUNNING' : undefined;
  let appliedOpen = false;
  let appliedAt: number | null = null;
  let waitStart: number | null = null;
  if (live) {
    applyProgress = applyProgress ?? progressOf(live.terminal);
    appliedOpen = live.terminal === 'APPLIED';
    if (appliedOpen && ctx.replyAt !== undefined) appliedAt = ctx.replyAt;
    if (outcomeStartsWait(live) && ctx.replyAt !== undefined) waitStart = ctx.replyAt;
  } else if (!ctx.applying && isRecordedPodChange(w.lastAction)) {
    const at = instantMs(w.lastAction!.at);
    applyProgress = progressOf(w.lastAction!.outcome);
    appliedOpen = w.lastAction!.outcome === 'APPLIED' && w.lastAction!.dryRun === false;
    if (appliedOpen) appliedAt = at;
    waitStart = at;
  }
  const endsAt = waitStart !== null ? waitStart + COOLDOWN_MS : null;
  const cooldownEnded = endsAt !== null && ctx.now >= endsAt;
  const suggestedNow = status.key === 'over-provisioned' || status.key === 'needs-more';
  const states = deriveLifecycle({
    measured: true,
    // A change that was applied had a suggestion, even once the engine stops showing it.
    hasSuggestion: suggestedNow || applyProgress !== undefined,
    applyProgress,
    appliedOpen,
    hasCooldown: endsAt !== null,
    cooldownEnded,
  });

  const reasons = w.containers.flatMap((c) => c.reasons);
  let label: string;
  if (applyProgress === 'RUNNING') label = 'Applying';
  else if (applyProgress && applyProgress !== 'APPLIED') label = PROBLEM_LABEL[applyProgress] ?? 'Last change failed';
  else if (endsAt !== null && !cooldownEnded) label = `Watching until ${istShort(endsAt)}`;
  else if (suggestedNow) label = 'Suggestion ready';
  else if (status.key === 'right-sized') label = 'Measuring: no change needed';
  else if (hasCode(reasons, 'HPA_CPU_MANAGED')) label = 'Measuring: CPU left to its HPA';
  else if (hasCode(reasons, 'HPA_MEMORY_MANAGED')) label = 'Measuring: memory left to its HPA';
  else if (hasCode(reasons, 'REQUESTS_UNSET')) label = 'Measuring: advice only';
  else if (status.key === 'left-alone-recently') label = 'Measuring: changed recently';
  else label = status.label;

  const coverage = w.provenance?.sampleCoverage;
  const pods = live?.pods?.length ?? 0;
  const details: Partial<Record<LifecycleStage, string>> = {
    measuring: `${window} of use${coverage !== null && coverage !== undefined ? `, ${Math.round(coverage * 100)}% covered` : ''}`,
    suggestion: suggestedNow ? (suggestionText(w) ?? 'Suggestion ready') : applyProgress !== undefined ? 'Suggested before the change' : 'No change needed',
    applying:
      applyProgress === 'RUNNING' ? 'Applying now'
        : applyProgress === 'APPLIED' ? (live && pods > 0 ? `${pods} of ${pods} pods` : appliedAt !== null ? `Applied ${istShort(appliedAt)}` : 'Applied')
          : applyProgress ? (PROBLEM_LABEL[applyProgress] ?? 'Last change failed')
            : 'Not yet',
    applied: appliedOpen
      ? `${appliedAt !== null ? `Applied ${istShort(appliedAt)}` : 'Applied'}${live?.restartFree === true ? ', no restart' : ''}`
      : 'Not yet',
    watching: endsAt === null ? 'Starts after it applies' : cooldownEnded ? `The wait ended at ${istShort(endsAt)}` : `Next change allowed at ${istShort(endsAt)}`,
  };
  return { states, label, details };
}

// ─── The ?workload= page parameter (automation-v2 design §3.2) ───────────────

const DNS = '[a-z0-9](?:[-a-z0-9.]{0,251}[a-z0-9])?';
const WORKLOAD_PARAM = new RegExp(`^(${DNS})/(Deployment|StatefulSet)/(${DNS})$`);

export interface WorkloadRefParam {
  namespace: string;
  kind: 'Deployment' | 'StatefulSet';
  name: string;
}

/** `<namespace>/<Deployment|StatefulSet>/<name>`, or null when the value is malformed. */
export function parseWorkloadParam(raw: string): WorkloadRefParam | null {
  const m = WORKLOAD_PARAM.exec(raw);
  return m ? { namespace: m[1], kind: m[2] as WorkloadRefParam['kind'], name: m[3] } : null;
}

/** The param value for a workload; it is the adapter's `namespace/kind/name` id. */
export const workloadParamOf = (ref: { namespace: string; kind: string; name: string }): string =>
  `${ref.namespace}/${ref.kind}/${ref.name}`;

/** `href` with one search parameter set (or removed with `null`), every other one kept. */
export function withParam(href: string, key: string, value: string | null): string {
  const q = href.indexOf('?');
  const path = q >= 0 ? href.slice(0, q) : href;
  const params = new URLSearchParams(q >= 0 ? href.slice(q + 1) : '');
  if (value === null) params.delete(key);
  else params.set(key, value);
  const search = params.toString();
  return search ? `${path}?${search}` : path;
}

// ─── Policies (glossary v2 §5) ───────────────────────────────────────────────

export interface Common<T> {
  value: T;
  count: number;
}

function mostCommon<T>(values: ReadonlyArray<T | null | undefined>): Common<T> | null {
  const counts = new Map<T, number>();
  for (const v of values) if (v !== null && v !== undefined) counts.set(v, (counts.get(v) ?? 0) + 1);
  let best: Common<T> | null = null;
  for (const [value, count] of counts) if (!best || count > best.count) best = { value, count };
  return best;
}

/** The rule most workloads use, per setting, as the engine reports it in endpoint 1. */
export interface PolicyDefaults {
  total: number;
  mode: Common<string> | null;
  headroomPct: Common<number> | null;
  percentile: Common<number> | null;
  window: Common<string> | null;
  applyMethod: Common<string> | null;
  policySource: Common<string> | null;
}

export function commonPolicy(workloads: readonly WorkloadView[]): PolicyDefaults {
  return {
    total: workloads.length,
    mode: mostCommon(workloads.map((w) => w.policy?.mode)),
    headroomPct: mostCommon(workloads.map((w) => w.policy?.headroomPct)),
    percentile: mostCommon(workloads.map((w) => w.policy?.percentile ?? w.provenance?.percentile)),
    window: mostCommon(workloads.map((w) => w.policy?.window)),
    applyMethod: mostCommon(workloads.map((w) => w.policy?.applyMethod)),
    policySource: mostCommon(workloads.map((w) => w.provenance?.policySource)),
  };
}

export const headroomText = (pct: number | null | undefined): string =>
  pct === null || pct === undefined ? DASH : `${pct}% above the target`;

export const percentileText = (pct: number | null | undefined): string =>
  pct === null || pct === undefined ? DASH : `${pct}th percentile of usage`;

/** "24 hours", "3 days" from the wire's `24h` / `72h`; anything else verbatim. */
export const lookBackText = (window: string | null | undefined): string => {
  const m = window ? /^(\d+)h$/.exec(window.trim()) : null;
  return m ? windowWords(Number(m[1])) : formatText(window);
};

export const applyMethodText = (method: string | null | undefined): string => {
  if (method === 'IN_PLACE') return 'In place, no restart';
  if (method === 'IN_PLACE_AND_PERSIST') return 'In place and saved to the workload (restarts every pod)';
  return method ? `Other: ${method}` : DASH;
};

export const policySourceText = (source: string | null | undefined): string => {
  if (source === 'DEFAULTS') return 'XamOps defaults';
  if (source === 'WORKLOAD_ROW') return 'Saved for this workload';
  return source ? `Other: ${source}` : DASH;
};

/** The mode as a chip: on (you apply, or automatic) in green, off in grey; the label says which. */
export function modeChip(mode: string | null | undefined): { tone: Tone; icon: string; label: string } {
  if (mode === 'AUTO') return { tone: 'good', icon: 'mdi:robot-outline', label: 'Apply automatically' };
  if (mode === 'RECOMMEND') return { tone: 'good', icon: 'mdi:gesture-tap-button', label: 'Suggest only' };
  if (mode === 'OFF') return { tone: 'off', icon: 'mdi:minus-circle-outline', label: 'Off' };
  return { tone: 'off', icon: 'mdi:help-circle-outline', label: mode ? `Other: ${mode}` : 'Not reported' };
}

/** A protection that leaves a workload alone, with the workloads it holds back today. */
export interface LeftAloneRule {
  id: string;
  label: string;
  sentence: string;
  workloads: WorkloadView[];
}

const LEFT_ALONE_ORDER = [
  'PROTECTED_NAMESPACE', 'SINGLE_REPLICA', 'ROLLOUT_IN_PROGRESS', 'HPA_MANAGED', 'HPA_VOLATILE',
  'VPA_MANAGED', 'LOCKED', 'POLICY_OFF', 'REQUESTS_UNSET',
];

/** Glossary v2 §5 "Left alone" labels, plus the engine's other holds. */
const LEFT_ALONE_LABEL: Record<string, string> = {
  PROTECTED_NAMESPACE: 'System namespaces',
  SINGLE_REPLICA: 'Single replica',
  ROLLOUT_IN_PROGRESS: 'Rolling out',
  HPA_MANAGED: 'Scaled by an HPA',
  HPA_VOLATILE: 'HPA just scaled it',
  VPA_MANAGED: 'Changed by a Kubernetes VPA',
  LOCKED: 'Paused',
  POLICY_OFF: 'Rightsizing off',
  REQUESTS_UNSET: 'No requests set',
};

function leftAloneSentence(id: string, w: WorkloadView): string {
  switch (id) {
    case 'HPA_MANAGED':
      return findingSentence(parseCode('HPA_COEXISTENCE')!, w);
    case 'VPA_MANAGED':
      return 'A Kubernetes VPA resizes these pods. Two autoscalers would undo each other, so XamOps leaves the workload alone.';
    case 'REQUESTS_UNSET':
      return reasonSentence(parseCode('REQUESTS_UNSET')!, undefined, w);
    default: {
      const code = parseCode(id);
      return code && code.kind === 'known' ? skipSentence(code, w) : '';
    }
  }
}

/** The protections endpoint 1 shows holding a workload back today, in the glossary's order. */
export function leftAloneRules(workloads: readonly WorkloadView[]): LeftAloneRule[] {
  const rules = new Map<string, LeftAloneRule>();
  const add = (id: string, label: string, w: WorkloadView) => {
    const rule = rules.get(id) ?? { id, label, sentence: leftAloneSentence(id, w), workloads: [] };
    if (!rule.workloads.includes(w)) rule.workloads.push(w);
    rules.set(id, rule);
  };
  for (const w of workloads) {
    const skip = w.skipReason;
    if (skip) {
      if (skip.kind === 'known') {
        if (skip.code !== 'INSUFFICIENT_HISTORY') add(skip.code, LEFT_ALONE_LABEL[skip.code] ?? skip.label, w);
      } else {
        const raw = skip.raw.replace(/^K8S_/, '');
        add(raw, raw === 'VPA_MANAGED' ? LEFT_ALONE_LABEL.VPA_MANAGED : `Other: ${skip.raw}`, w);
      }
    }
    const reasons = w.containers.flatMap((c) => c.reasons);
    if (hasCode(reasons, 'HPA_CPU_MANAGED') || hasCode(reasons, 'HPA_MEMORY_MANAGED')) add('HPA_MANAGED', LEFT_ALONE_LABEL.HPA_MANAGED, w);
    if (hasCode(reasons, 'REQUESTS_UNSET')) add('REQUESTS_UNSET', LEFT_ALONE_LABEL.REQUESTS_UNSET, w);
  }
  const rank = (id: string) => {
    const i = LEFT_ALONE_ORDER.indexOf(id);
    return i < 0 ? LEFT_ALONE_ORDER.length : i;
  };
  return [...rules.values()].sort((a, b) => rank(a.id) - rank(b.id) || a.id.localeCompare(b.id));
}

/** The values the engine fixes that endpoint 1 doesn't carry yet (glossary rule 8). */
export const FIXED_RULES: ReadonlyArray<{ label: string; value: string }> = [
  { label: 'Wait after a change', value: `${COOLDOWN_MINUTES} minutes` },
  { label: 'Minimums', value: `${CPU_FLOOR} CPU · ${MEM_FLOOR} memory` },
  { label: 'Memory target', value: 'Peak usage' },
];

// ─── Feature cards (Overview) ───────────────────────────────────────────────

export interface FeatureStatus {
  tone: Tone;
  icon: string;
  label: string;
  /** Plain lines under the chip, each from a named source. */
  lines: string[];
}

/** The Workload autoscaler's state on this cluster, from endpoint 1 (glossary §2.3). */
export function workloadAutoscalerStatus(view: VerticalEngineView, now: number): FeatureStatus {
  const { engine, gateReasons, workloads } = view;
  const has = (code: string) => hasCode(gateReasons, code);
  const k = automationKpis(workloads, now);
  const automatic = workloads.filter((w) => w.policy?.mode === 'AUTO').length;
  const lines = [
    `${k.analysed} ${plural(k.analysed, 'workload')} analysed`,
    `${k.overProvisioned} can be resized smaller, ${k.needsMore} ${plural(k.needsMore, 'needs', 'need')} more`,
  ];
  if (!engine.enabled) return { tone: 'off', icon: 'mdi:minus-circle-outline', label: 'Off in this environment', lines: [] };
  if (has('CLUSTER_ACCESS_NOT_CONFIGURED') || has('CLUSTER_UNREACHABLE')) {
    return { tone: 'bad', icon: 'mdi:lan-disconnect', label: "Can't reach the cluster", lines };
  }
  if (!engine.metricsReachable || has('NO_METRICS_SOURCE')) return { tone: 'warn', icon: 'mdi:progress-clock', label: 'Waiting for usage data', lines };
  if (!engine.ready || has('CONTROLLER_NOT_READY')) return { tone: 'warn', icon: 'mdi:lightbulb-on-outline', label: 'On: suggestions only', lines };
  if (WRITE_GATES.some((g) => has(g)) || !view.writeGatesChecked) return { tone: 'warn', icon: 'mdi:eye-outline', label: 'On: view only', lines };
  if (automatic > 0) return { tone: 'good', icon: 'mdi:robot-outline', label: 'Applying automatically', lines: [...lines, `${automatic} on Apply automatically`] };
  return { tone: 'good', icon: 'mdi:check-circle', label: 'On: you apply suggestions', lines };
}

/** HPAs from the XamOps agent, with XamOps's HPA decisions from endpoint 1. */
export function hpaFeatureStatus(hpa: HpaPageView | null, dashboard: EksDashboard | null | undefined): FeatureStatus {
  const states = hpaStates(dashboard);
  const lines: string[] = [];
  if (hpa) {
    const changes = hpa.hpaWorkloads.filter((w) => w.decision.state === 'CHANGE').length;
    lines.push(hpa.horizontalEvaluated
      ? `XamOps suggests a change for ${changes} ${plural(changes, 'HPA')}`
      : 'XamOps HPA suggestions are off here');
  }
  if (states === null) return { tone: 'off', icon: 'mdi:help-circle-outline', label: 'Not reported by the XamOps agent', lines };
  if (states.length === 0) return { tone: 'off', icon: 'mdi:minus-circle-outline', label: 'No HPAs found', lines };
  const atMax = states.filter((s) => s.atMax).length;
  if (atMax > 0) {
    return { tone: 'warn', icon: 'mdi:arrow-collapse-up', label: `${atMax} ${plural(atMax, 'HPA')}, at their maximum`, lines: [`${states.length} ${plural(states.length, 'HPA')} on the cluster`, ...lines] };
  }
  return { tone: 'good', icon: 'mdi:check-circle', label: `${states.length} ${plural(states.length, 'HPA')}, none at the maximum`, lines };
}

/** Native VPAs XamOps reads in endpoint 1 and how it shares the workload with them. */
export function vpaFeatureStatus(hpa: HpaPageView | null, vertical: VerticalEngineView | null): FeatureStatus {
  if (!hpa || !vertical) return { tone: 'off', icon: 'mdi:help-circle-outline', label: 'Not checked', lines: [] };
  const rows = hpa.vpaWorkloads;
  const managed = rows.filter((w) => w.vpaCoexistence.kind === 'managed').length;
  const unreadable = rows.filter((w) => w.vpaCoexistence.kind === 'unreadable').length;
  const off = rows.filter((w) => w.vpaCoexistence.kind === 'off').length;
  const notReadable = vertical.workloads.some((w) => hasCode(w.findings, 'VPA_NOT_READABLE'));
  const targets = vertical.workloads.filter((w) => w.containers.some((c) => c.upstreamVpa !== null)).length;
  const lines = [`${targets} analysed ${plural(targets, 'workload')} with a Kubernetes VPA target`];
  if (unreadable > 0 || notReadable) return { tone: 'warn', icon: 'mdi:eye-off-outline', label: "XamOps can't read the VPAs", lines };
  if (managed > 0) {
    return { tone: 'off', icon: 'mdi:minus-circle-outline', label: `${managed} ${plural(managed, 'VPA')} ${plural(managed, 'changes', 'change')} pods`, lines: [...lines, 'XamOps leaves those workloads alone'] };
  }
  if (off > 0) return { tone: 'off', icon: 'mdi:minus-circle-outline', label: `${off} ${plural(off, 'VPA')}, all Off`, lines };
  return { tone: 'off', icon: 'mdi:minus-circle-outline', label: 'No VPAs found', lines };
}

/** Who changes the node count, from capabilities (the automation agent) and the XamOps agent's data. */
export function nodeAutoscalerStatus(
  capsStatus: 'loading' | 'ready' | 'error',
  cluster: CapabilitiesCluster | null,
  dashboard: EksDashboard | null | undefined,
): FeatureStatus {
  const lines: string[] = [];
  if (dashboard) {
    const p = nodeProvisioners(dashboard);
    if (p.karpenter) lines.push('Karpenter adds and removes nodes');
    if (p.clusterAutoscaler) lines.push('The Cluster Autoscaler adds and removes nodes');
    if (!p.karpenter && !p.clusterAutoscaler) lines.push("No node autoscaler in the XamOps agent's data");
  }
  if (capsStatus === 'loading') return { tone: 'off', icon: 'mdi:progress-clock', label: 'Checking', lines };
  if (capsStatus === 'error') return { tone: 'off', icon: 'mdi:help-circle-outline', label: 'Not checked', lines };
  switch (cluster?.nodeAutomation?.derivedState ?? 'NOT_INSTALLED') {
    case 'AUTOMATED':
      return { tone: 'good', icon: 'mdi:robot-outline', label: 'Applying automatically', lines };
    case 'ANALYSIS':
      return { tone: 'warn', icon: 'mdi:lightbulb-on-outline', label: 'On: suggestions only', lines };
    case 'OFF':
      return { tone: 'off', icon: 'mdi:minus-circle-outline', label: 'Off', lines };
    case 'UNKNOWN':
      return { tone: 'off', icon: 'mdi:help-circle-outline', label: 'Not checked', lines };
    default:
      return { tone: 'off', icon: 'mdi:minus-circle-outline', label: 'Not running on this cluster', lines: [...lines, "The automation agent isn't installed"] };
  }
}

// ─── HPA list and detail (UI v3 plan §2 "HPA"; glossary §13) ─────────────────

/** A condition an HPA reports, as the XamOps agent read it. */
export interface HpaConditionView {
  type: string;
  status: string;
  reason: string | null;
  message: string | null;
}

/** One HPA as the XamOps agent reported it. A number it didn't report is null, never 0. */
export interface AgentHpa {
  namespace: string;
  name: string;
  targetKind: string | null;
  targetName: string | null;
  min: number | null;
  max: number | null;
  current: number | null;
  desired: number | null;
  /** Its CPU utilization target, % of request; null when the agent read no CPU utilization target. */
  cpuTargetPct: number | null;
  /** CPU use now, % of request; null when Kubernetes reported no reading. */
  cpuNowPct: number | null;
  conditions: HpaConditionView[];
}

const textOf = (value: unknown): string | null => (typeof value === 'string' && value.trim() !== '' ? value : null);

/** The first value that is a number (0 included). */
const firstNumber = (...values: unknown[]): number | null => {
  for (const v of values) {
    const n = toNumber(v);
    if (n !== null) return n;
  }
  return null;
};

/** The first value above 0: a CPU target of 0 is "none", not a target. */
const firstPositive = (...values: unknown[]): number | null => {
  for (const v of values) {
    const n = toNumber(v);
    if (n !== null && n > 0) return n;
  }
  return null;
};

/**
 * The HPAs the XamOps agent reported (`k8sHpas`), or null when it reported no HPA list at all (not
 * the same as none). Both spellings are read: the agent's `cpuTargetPct` / `currentCpuPct` and the
 * older `targetCPUUtilization` / `currentCPUUtilization`.
 */
export function agentHpas(dashboard: EksDashboard | null | undefined): AgentHpa[] | null {
  const raw = dashboard?.k8sHpas;
  if (!Array.isArray(raw)) return null;
  return raw.flatMap((entry): AgentHpa[] => {
    if (!entry || typeof entry !== 'object') return [];
    const h = entry as Record<string, unknown>;
    const name = textOf(h.name);
    if (!name) return [];
    const conditions = Array.isArray(h.conditions)
      ? h.conditions.flatMap((c): HpaConditionView[] => {
        if (!c || typeof c !== 'object') return [];
        const r = c as Record<string, unknown>;
        const type = textOf(r.type);
        return type ? [{ type, status: textOf(r.status) ?? 'Unknown', reason: textOf(r.reason), message: textOf(r.message) }] : [];
      })
      : [];
    return [{
      namespace: textOf(h.namespace) ?? 'default',
      name,
      targetKind: textOf(h.targetKind),
      targetName: textOf(h.targetName) ?? textOf(h.targetRef),
      min: firstNumber(h.minReplicas),
      max: firstNumber(h.maxReplicas),
      current: firstNumber(h.currentReplicas),
      desired: firstNumber(h.desiredReplicas),
      cpuTargetPct: firstPositive(h.cpuTargetPct, h.targetCPUUtilization, h.targetCPUPercent),
      cpuNowPct: firstNumber(h.currentCpuPct, h.currentCPUUtilization),
      conditions,
    }];
  });
}

export type HpaFlagKey = 'not-scaling' | 'cant-scale' | 'fixed-size' | 'at-max' | 'at-min' | 'scaling';

export interface HpaFlag {
  key: HpaFlagKey;
  tone: Tone;
  icon: string;
  label: string;
  detail?: string;
}

const conditionOf = (h: AgentHpa, type: string): HpaConditionView | undefined => h.conditions.find((c) => c.type === type);

/** One condition in plain words (glossary §13.2); anything else is its raw reason and message. */
export function hpaConditionText(c: HpaConditionView): string {
  const message = c.message ? ` · ${c.message}` : '';
  if (c.type === 'AbleToScale') {
    if (c.status === 'True') {
      if (c.reason === 'ScaleDownStabilized') return 'Can scale: yes · holding off a scale-down: recent readings asked for more';
      if (c.reason === 'ScaleUpStabilized') return 'Can scale: yes · holding off a scale-up for now';
      if (c.reason === 'ReadyForNewScale' || c.reason === 'SucceededRescale' || c.reason === 'SucceededGetScale' || c.reason === null) return 'Can scale: yes';
    }
    if (c.status === 'False') return `Can scale: no${message}`;
  }
  if (c.type === 'ScalingActive') {
    if (c.status === 'True' && (c.reason === 'ValidMetricFound' || c.reason === null)) return 'Reading its metric: yes';
    if (c.status === 'False') return `Reading its metric: no${message}`;
  }
  if (c.type === 'ScalingLimited') {
    if (c.status === 'True' && c.reason === 'TooManyReplicas') return 'Held at a limit: yes, the maximum';
    if (c.status === 'True' && c.reason === 'TooFewReplicas') return 'Held at a limit: yes, the minimum';
    if (c.status === 'False') return 'Held at a limit: no';
  }
  return `${c.type} ${c.status}${c.reason ? `: ${c.reason}` : ''}${message}`;
}

/**
 * Every flag that holds for an HPA, the first one first (glossary §13.1, first match): not scaling
 * (ScalingActive False), can't scale (AbleToScale False), fixed size (min = max, so it is at both
 * limits by design), at its maximum, at its minimum (unless CPU is above target), else scaling.
 * Empty when the agent didn't report the replica numbers: nothing is flagged on a guess.
 */
export function hpaFlags(h: AgentHpa): HpaFlag[] {
  const flags: HpaFlag[] = [];
  const active = conditionOf(h, 'ScalingActive');
  if (active?.status === 'False') {
    flags.push({ key: 'not-scaling', tone: 'bad', icon: 'mdi:alert-octagon-outline', label: 'Not scaling', detail: hpaConditionText(active) });
  }
  const able = conditionOf(h, 'AbleToScale');
  if (able?.status === 'False') {
    flags.push({ key: 'cant-scale', tone: 'bad', icon: 'mdi:alert-octagon-outline', label: "Can't scale", detail: hpaConditionText(able) });
  }
  const { min, max, current } = h;
  if (min !== null && max !== null && min === max) {
    flags.push({ key: 'fixed-size', tone: 'off', icon: 'mdi:lock-outline', label: 'Fixed size', detail: `Minimum and maximum are both ${max}, so it never scales` });
  } else {
    if (current !== null && max !== null && current >= max) {
      flags.push({ key: 'at-max', tone: 'warn', icon: 'mdi:arrow-collapse-up', label: 'At its maximum' });
    }
    const aboveTarget = h.cpuNowPct !== null && h.cpuTargetPct !== null && h.cpuNowPct > h.cpuTargetPct;
    if (current !== null && min !== null && current <= min && !aboveTarget) {
      flags.push({ key: 'at-min', tone: 'info', icon: 'mdi:arrow-collapse-down', label: 'At its minimum' });
    }
  }
  if (flags.length === 0 && current !== null && min !== null && max !== null) {
    flags.push({ key: 'scaling', tone: 'good', icon: 'mdi:check-circle', label: 'Scaling normally' });
  }
  return flags;
}

/** One HPA row: the agent's live HPA joined with endpoint 1's decision for the workload it scales. */
export interface HpaRow {
  /** `<namespace>/<HPA name>`: the `?hpa=` value. */
  id: string;
  namespace: string;
  name: string;
  /** What it scales; null when neither the agent nor endpoint 1 says. */
  target: { kind: string; name: string } | null;
  /** The agent's HPA; null when the agent didn't report it. */
  live: AgentHpa | null;
  /** Endpoint 1's view of the workload it scales; null when endpoint 1 doesn't analyse that workload. */
  workload: HpaWorkloadView | null;
  /** The first flag that holds; null when the agent didn't report the HPA's numbers. */
  flag: HpaFlag | null;
  /** The other flags that hold, shown as tags. */
  tags: HpaFlag[];
}

/**
 * The HPAs on the cluster, each with the workload endpoint 1 analysed for it. The join is the
 * agent's scale target (namespace, kind and name, `hpasFor`), else the HPA name endpoint 1 reports
 * for the workload. HPAs only endpoint 1 knows (the agent didn't report them) stay, with no live
 * numbers; HPAs endpoint 1 doesn't analyse stay, with no decision.
 */
export function hpaRows(engine: HpaPageView | null, dashboard: EksDashboard | null | undefined): HpaRow[] {
  const live = agentHpas(dashboard) ?? [];
  const keyOf = (namespace: string, name: string) => `${namespace}/${name}`;
  const liveByKey = new Map(live.map((h) => [keyOf(h.namespace, h.name), h]));
  const workloadByKey = new Map<string, HpaWorkloadView>();
  const workloads = engine?.workloads ?? [];
  for (const w of workloads) {
    const keys = hpasFor(dashboard, w.ref).flatMap((h) => {
      const name = textOf(h.name);
      return name ? [keyOf(textOf(h.namespace) ?? 'default', name)] : [];
    });
    if (keys.length === 0 && w.hpaName) keys.push(keyOf(w.ref.namespace, w.hpaName));
    for (const key of keys) if (liveByKey.has(key) && !workloadByKey.has(key)) workloadByKey.set(key, w);
  }

  const rows: HpaRow[] = live.map((h) => {
    const id = keyOf(h.namespace, h.name);
    const workload = workloadByKey.get(id) ?? null;
    const flags = hpaFlags(h);
    const target = h.targetName
      ? { kind: h.targetKind ?? workload?.ref.kind ?? 'Deployment', name: h.targetName }
      : workload ? { kind: workload.ref.kind, name: workload.ref.name } : null;
    return { id, namespace: h.namespace, name: h.name, target, live: h, workload, flag: flags[0] ?? null, tags: flags.slice(1) };
  });

  const joined = new Set(workloadByKey.values());
  for (const w of workloads) {
    if (!w.hpaPresent || joined.has(w)) continue;
    const name = w.hpaName ?? w.ref.name;
    const id = keyOf(w.ref.namespace, name);
    if (rows.some((r) => r.id === id)) continue;
    rows.push({ id, namespace: w.ref.namespace, name, target: { kind: w.ref.kind, name: w.ref.name }, live: null, workload: w, flag: null, tags: [] });
  }
  return rows.sort((a, b) => a.namespace.localeCompare(b.namespace) || a.name.localeCompare(b.name));
}

/** Workloads with no HPA that endpoint 1 says one would help (D4); XamOps never creates one. */
export const hpaCandidates = (engine: HpaPageView | null): HpaWorkloadView[] =>
  (engine?.hpaWorkloads ?? []).filter((w) => !w.hpaPresent);

export const rowHasFlag = (row: HpaRow, key: HpaFlagKey): boolean => row.flag?.key === key || row.tags.some((t) => t.key === key);

export interface HpaKpis {
  /** HPAs on the cluster. */
  total: number;
  /** At their maximum / minimum; null when the agent reported no HPA list. */
  atMax: number | null;
  atMin: number | null;
  /** HPA workloads a Kubernetes VPA also changes; null when endpoint 1 didn't answer. */
  conflicts: number | null;
}

export function hpaKpis(rows: readonly HpaRow[], agentReported: boolean, engineRead: boolean): HpaKpis {
  return {
    total: rows.length,
    atMax: agentReported ? rows.filter((r) => rowHasFlag(r, 'at-max')).length : null,
    atMin: agentReported ? rows.filter((r) => rowHasFlag(r, 'at-min')).length : null,
    conflicts: engineRead ? rows.filter((r) => r.workload?.vpaCoexistence.kind === 'managed').length : null,
  };
}

/** What the HPA itself asks for now, by its own formula, and how it was worked out. */
export interface HpaWants {
  /** ceil(current × now ÷ target), or current within Kubernetes' 10% tolerance. */
  wants: number;
  /** The same, held between its minimum and maximum: what it can actually run. */
  capped: number;
  formula: string;
}

/**
 * "The HPA wants about <n>" (glossary §13): only when the agent reported its CPU utilization target,
 * its CPU use now and its replicas. The agent reads only the HPA's CPU utilization metric, so the
 * text says that another metric could ask for more.
 */
export function hpaWants(h: AgentHpa): HpaWants | null {
  const { current, cpuNowPct, cpuTargetPct, min, max } = h;
  if (current === null || current <= 0 || cpuNowPct === null || cpuTargetPct === null || cpuTargetPct <= 0) return null;
  const ratio = cpuNowPct / cpuTargetPct;
  const tolerated = Math.abs(ratio - 1) <= 0.1;
  const wants = tolerated ? current : Math.ceil(Number((current * ratio).toFixed(6)));
  const capped = Math.min(max ?? wants, Math.max(min ?? wants, wants));
  const formula = [
    `Its own formula: ${current} × ${cpuNowPct}% ÷ ${cpuTargetPct}%, rounded up.`,
    tolerated ? ` That is within Kubernetes' 10% tolerance, so it stays at ${current}.` : '',
    max !== null ? ` Capped at ${max}.` : '',
    ' If the HPA also scales on another metric, Kubernetes uses whichever asks for more.',
  ].join('');
  return { wants, capped, formula };
}

/** One "Works with XamOps" check (glossary §13.3). */
export interface WorksCheck {
  id: 'cpu' | 'vpa' | 'pdb';
  tone: Tone;
  icon: string;
  label: string;
  detail?: string;
}

export function hpaWorksChecks(row: HpaRow): WorksCheck[] {
  const checks: WorksCheck[] = [];
  if (row.live?.cpuTargetPct !== null && row.live?.cpuTargetPct !== undefined) {
    checks.push({
      id: 'cpu',
      tone: 'good',
      icon: 'mdi:check-circle',
      label: 'Workload autoscaler leaves CPU alone',
      detail: "This HPA scales on CPU, so XamOps doesn't change the workload's CPU request.",
    });
  } else {
    checks.push({
      id: 'cpu',
      tone: 'off',
      icon: 'mdi:help-circle-outline',
      label: 'What it scales on: not reported',
      detail: "The XamOps agent didn't report a CPU target for this HPA. XamOps leaves an HPA's resource alone either way.",
    });
  }
  const kind = row.workload?.vpaCoexistence.kind;
  if (!row.workload) {
    checks.push({ id: 'vpa', tone: 'off', icon: 'mdi:help-circle-outline', label: 'Kubernetes VPA: not checked', detail: "XamOps doesn't analyse the workload this HPA scales." });
  } else if (kind === 'managed') {
    checks.push({ id: 'vpa', tone: 'bad', icon: 'mdi:alert-octagon-outline', label: 'A Kubernetes VPA also changes these pods', detail: 'Two autoscalers on one resource undo each other.' });
  } else if (kind === 'unreadable') {
    checks.push({ id: 'vpa', tone: 'warn', icon: 'mdi:eye-off-outline', label: "XamOps can't read the VPAs here", detail: "So it leaves this workload's requests alone." });
  } else if (kind === 'off') {
    checks.push({ id: 'vpa', tone: 'good', icon: 'mdi:check-circle', label: 'No Kubernetes VPA changes these pods', detail: "A VPA targets it in Off mode, which doesn't change pods." });
  } else {
    checks.push({ id: 'vpa', tone: 'good', icon: 'mdi:check-circle', label: 'No Kubernetes VPA changes these pods' });
  }
  if (row.workload?.decision.reasons.some((r) => r.kind === 'known' && r.code === 'PDB_FLOOR_BINDS')) {
    checks.push({
      id: 'pdb',
      tone: 'warn',
      icon: 'mdi:shield-alert-outline',
      label: 'A disruption budget needs more than the minimum',
      detail: 'At the minimum, no pod could be moved, so XamOps keeps the minimum.',
    });
  }
  return checks;
}

const HPA_PARAM = new RegExp(`^(${DNS})/(${DNS})$`);

/** `<namespace>/<HPA name>`, or null when the value is malformed. */
export function parseHpaParam(raw: string): { namespace: string; name: string } | null {
  const m = HPA_PARAM.exec(raw);
  return m ? { namespace: m[1], name: m[2] } : null;
}

// ─── VPA (UI v3 plan §2 "VPA"; glossary §14) ─────────────────────────────────

/** One resource per pod: the request now, what XamOps suggests and what the Kubernetes VPA suggests. */
export interface PerPodSizes {
  now: number | null;
  xamops: number | null;
  vpa: number | null;
}

export interface VpaRowView {
  id: string;
  /** Endpoint 1's HPA/VPA view of the workload: its coexistence verdict and VPA targets. */
  hpa: HpaWorkloadView;
  /** Endpoint 1's Workload autoscaler view of the same workload; null when it isn't in that view. */
  workload: WorkloadView | null;
  cpu: PerPodSizes;
  memory: PerPodSizes;
  /** Why XamOps doesn't size it, when it doesn't. */
  skipped: string | null;
  /** The VPA reported no target for any container. */
  noTarget: boolean;
  /** CPU per pod: the VPA within 20% of XamOps; null when either side is unknown. */
  agrees: boolean | null;
  /** The CPU comparison in words (glossary §14 "Comparison"). */
  compare: { tone: Tone; icon: string; label: string } | null;
}

const SKIP_REASON_SHORT = (w: WorkloadView): string => {
  const skip = knownCode(w.skipReason);
  if (skip && SKIP_SHORT[skip]) return SKIP_SHORT[skip];
  return w.skipReason ? codeText(w.skipReason) : 'rightsizing off';
};

function vpaPerPod(hpa: HpaWorkloadView, resource: Resource): number | null {
  if (hpa.nativeVpa.length === 0) return null;
  let total = 0;
  for (const t of hpa.nativeVpa) {
    const v = resource === 'cpu' ? t.cpuRequestM : t.memRequestMi;
    if (v === null) return null;
    total += v;
  }
  return total;
}

/** "No VPA suggestion": a fact only when the workload runs no pod to measure. */
export function noVpaSuggestionText(hpa: HpaWorkloadView): string {
  return hpa.replicas === 0 ? 'No VPA suggestion: no running pod to measure' : 'No VPA suggestion reported';
}

/**
 * The workloads a Kubernetes VPA touches (endpoint 1), with CPU and memory per pod side by side:
 * the request now, XamOps's suggestion (its current request when it suggests no change; none when it
 * skips the workload) and the VPA's target. CPU agrees when the VPA is within 20% of XamOps.
 */
export function vpaRows(hpaView: HpaPageView, vertical: VerticalEngineView | null): VpaRowView[] {
  const byId = new Map((vertical?.workloads ?? []).map((w) => [w.id, w]));
  return hpaView.vpaWorkloads.map((hpa): VpaRowView => {
    const workload = byId.get(hpa.id) ?? null;
    const status = workload ? workloadStatusChip(workload).key : null;
    const skipped = workload && (status === 'skipped' || status === 'off' || status === 'left-alone-managed') ? SKIP_REASON_SHORT(workload) : null;
    const sizes = (resource: Resource): PerPodSizes => {
      const pair = workload ? podRequests(workload, resource) : { now: null, suggested: null };
      return { now: pair.now, xamops: skipped ? null : pair.suggested, vpa: vpaPerPod(hpa, resource) };
    };
    const cpu = sizes('cpu');
    const memory = sizes('memory');
    const noTarget = hpa.nativeVpa.length === 0;
    let agrees: boolean | null = null;
    let compare: VpaRowView['compare'] = null;
    if (skipped) {
      compare = { tone: 'off', icon: 'mdi:debug-step-over', label: `XamOps skips it: ${skipped}` };
    } else if (noTarget) {
      compare = { tone: 'off', icon: 'mdi:minus-circle-outline', label: noVpaSuggestionText(hpa) };
    } else if (cpu.xamops !== null && cpu.vpa !== null && cpu.xamops > 0) {
      agrees = Math.abs(cpu.vpa - cpu.xamops) <= 0.2 * cpu.xamops;
      if (Math.abs(cpu.vpa - cpu.xamops) < 1) compare = { tone: 'good', icon: 'mdi:check-circle', label: 'CPU: same as XamOps' };
      else if (agrees) compare = { tone: 'good', icon: 'mdi:check-circle', label: 'CPU: within 20% of XamOps' };
      else compare = { tone: 'info', icon: 'mdi:compare-vertical', label: `CPU: VPA suggests ${cpu.vpa > cpu.xamops ? 'more' : 'less'} than XamOps` };
    }
    return { id: hpa.id, hpa, workload, cpu, memory, skipped, noTarget, agrees, compare };
  });
}

/** The coexistence chip (UI v3 plan §2 "VPA"): a VPA that changes pods is a conflict. */
export const VPA_CONFLICT_CHIPS: Record<'managed' | 'unreadable' | 'off' | 'none', { tone: Tone; icon: string }> = {
  managed: { tone: 'bad', icon: 'mdi:alert-octagon-outline' },
  unreadable: { tone: 'warn', icon: 'mdi:eye-off-outline' },
  off: { tone: 'info', icon: 'mdi:information-outline' },
  none: { tone: 'off', icon: 'mdi:minus-circle-outline' },
};

export interface VpaKpis {
  vpas: number;
  changingPods: number;
  unreadable: number;
  off: number;
  conflicts: number;
  agreement: { agree: number; compared: number };
}

export function vpaKpis(rows: readonly VpaRowView[]): VpaKpis {
  const kind = (k: string) => rows.filter((r) => r.hpa.vpaCoexistence.kind === k).length;
  const compared = rows.filter((r) => r.agrees !== null);
  return {
    vpas: rows.length,
    changingPods: kind('managed'),
    unreadable: kind('unreadable'),
    off: kind('off'),
    conflicts: kind('managed') + kind('unreadable'),
    agreement: { agree: compared.filter((r) => r.agrees === true).length, compared: compared.length },
  };
}

// ─── Node autoscaler (UI v3 plan §2 "Node autoscaler"; glossary §12) ─────────

/** One line of "Who changes the node count on this cluster" (glossary §12.1). */
export interface NodeChangerLine {
  id: string;
  tone: Tone;
  icon: string;
  label: string;
  detail?: string;
}

const CLUSTER_AUTOSCALER = /(^|-)cluster-autoscaler($|-)/;

function clusterAutoscalerDeployment(dashboard: EksDashboard): { namespace: string; name: string; image: string | null } | null {
  const raw = Array.isArray(dashboard.k8sDeployments) && dashboard.k8sDeployments.length > 0
    ? dashboard.k8sDeployments
    : (dashboard.workloadHealth as unknown as { deployments?: unknown[] } | undefined)?.deployments ?? [];
  for (const entry of raw as unknown[]) {
    if (!entry || typeof entry !== 'object') continue;
    const d = entry as Record<string, unknown>;
    const name = textOf(d.name);
    if (!name || !CLUSTER_AUTOSCALER.test(name)) continue;
    const images = Array.isArray(d.images) ? d.images.filter((i): i is string => typeof i === 'string') : [];
    return { namespace: textOf(d.namespace) ?? 'default', name, image: images[0] ?? null };
  }
  return null;
}

/** The image's tag ("v1.30.0" from "registry.k8s.io/autoscaling/cluster-autoscaler:v1.30.0"). */
const imageTag = (image: string | null): string | null => {
  if (!image) return null;
  const at = image.lastIndexOf(':');
  return at > image.lastIndexOf('/') ? image.slice(at + 1) : null;
};

/**
 * Who adds and removes nodes, from the XamOps agent's data (labels, Deployments and Karpenter's own
 * report). Null when the agent hasn't reported this cluster, so nothing is claimed either way.
 */
export function nodeChangers(dashboard: EksDashboard | null | undefined, clusterName: string): NodeChangerLine[] | null {
  if (!dashboard || isEmptyDashboard(dashboard)) return null;
  const p = nodeProvisioners(dashboard);
  const lines: NodeChangerLine[] = [];
  if (p.karpenter === 'reported') {
    const pools = dashboard.karpenter?.nodePools?.length ?? 0;
    lines.push({ id: 'karpenter', tone: 'good', icon: 'mdi:check-circle', label: `Karpenter: installed (${pools} ${plural(pools, 'NodePool')})` });
  } else if (p.karpenter === 'labels') {
    lines.push({ id: 'karpenter', tone: 'good', icon: 'mdi:check-circle', label: 'Karpenter: installed', detail: 'Nodes carry its NodePool label.' });
  } else {
    lines.push({ id: 'karpenter', tone: 'off', icon: 'mdi:minus-circle-outline', label: 'Karpenter: not installed' });
  }
  const ca = clusterAutoscalerDeployment(dashboard);
  if (ca) {
    const tag = imageTag(ca.image);
    lines.push({ id: 'cluster-autoscaler', tone: 'good', icon: 'mdi:check-circle', label: `Cluster Autoscaler: installed (${ca.namespace}/${ca.name}${tag ? `, ${tag}` : ''})` });
  } else {
    lines.push({ id: 'cluster-autoscaler', tone: 'off', icon: 'mdi:minus-circle-outline', label: 'Cluster Autoscaler: not installed' });
  }
  if (p.karpenter && ca) {
    lines.push({ id: 'both', tone: 'warn', icon: 'mdi:alert-outline', label: 'Karpenter and the Cluster Autoscaler are both installed, so they can fight over the same nodes.' });
  }
  for (const g of nodeGroups(dashboard).filter((x) => x.type === 'Managed node group')) {
    const kinds = [g.onDemand > 0 ? `${g.onDemand} on-demand` : null, g.spot > 0 ? `${g.spot} Spot` : null].filter((x): x is string => !!x).join(', ');
    lines.push({
      id: `node-group:${g.name}`,
      tone: 'info',
      icon: 'mdi:server',
      label: `Node group ${g.name}: EKS managed · ${g.instanceTypes.join(', ') || 'instance type not reported'}${kinds ? `, ${kinds}` : ''} · ${g.nodes} ${plural(g.nodes, 'node')} now`,
      detail: !p.karpenter && !ca ? 'Without an autoscaler, EKS keeps it at the desired count.' : undefined,
    });
  }
  if (!p.karpenter && !ca) {
    lines.push({ id: 'none', tone: 'off', icon: 'mdi:minus-circle-outline', label: `Nothing adds or removes nodes on ${clusterName} today.` });
  }
  return lines;
}

/** The automation agent's install state, from capabilities: what "Remove empty nodes" and the rest need. */
export type AutomationAgentState = 'installed' | 'stale' | 'not-installed' | 'unknown';

export function automationAgentState(capsStatus: 'loading' | 'ready' | 'error', cluster: CapabilitiesCluster | null): AutomationAgentState {
  if (capsStatus !== 'ready' || !cluster) return 'unknown';
  const status: AgentStatus | undefined = cluster.automationAgent?.status;
  if (status === 'CONNECTED') return 'installed';
  if (status === 'STALE') return 'stale';
  if (status === 'NOT_INSTALLED') return 'not-installed';
  return 'unknown';
}

/** The automation agent line (glossary §12.1), with what its dashboard says when it answered. */
export function automationAgentLine(
  capsStatus: 'loading' | 'ready' | 'error',
  cluster: CapabilitiesCluster | null,
  dashboard: { kind: 'ok'; data: AutomationDashboardDto } | { kind: 'error'; message: string } | { kind: 'loading' },
): NodeChangerLine {
  if (capsStatus === 'loading') return { id: 'automation-agent', tone: 'off', icon: 'mdi:progress-clock', label: 'XamOps automation agent: checking' };
  if (capsStatus === 'error') {
    return { id: 'automation-agent', tone: 'off', icon: 'mdi:help-circle-outline', label: 'XamOps automation agent: not checked', detail: "XamOps couldn't read this cluster's capabilities." };
  }
  if (!cluster) {
    return { id: 'automation-agent', tone: 'off', icon: 'mdi:help-circle-outline', label: 'XamOps automation agent: not checked', detail: "This cluster isn't in XamOps's capabilities for the account." };
  }
  const agent = cluster.automationAgent;
  const seen = agent?.lastSeenAt ? formatInstant(agent.lastSeenAt) : null;
  const settings = dashboard.kind === 'error'
    ? `XamOps couldn't read its node automation settings: ${dashboard.message}`
    : dashboard.kind === 'ok' && dashboard.data.agentStatus?.mode
      ? `Node automation mode: ${dashboard.data.agentStatus.mode}.`
      : undefined;
  switch (agent?.status) {
    case 'CONNECTED':
      return {
        id: 'automation-agent', tone: 'good', icon: 'mdi:check-circle',
        label: `XamOps automation agent: installed (${agent.version ?? 'version not reported'}${seen ? `, last report ${seen}` : ''})`,
        detail: settings,
      };
    case 'STALE':
      return {
        id: 'automation-agent', tone: 'warn', icon: 'mdi:clock-alert-outline',
        label: `XamOps automation agent: installed, but its last report is older than 15 minutes${seen ? ` (${seen})` : ''}.`,
        detail: settings,
      };
    case 'UNKNOWN':
      return { id: 'automation-agent', tone: 'off', icon: 'mdi:help-circle-outline', label: 'XamOps automation agent: not checked', detail: settings };
    default:
      return {
        id: 'automation-agent', tone: 'off', icon: 'mdi:minus-circle-outline',
        label: 'XamOps automation agent: not installed. It is the part of XamOps that empties and packs nodes.',
      };
  }
}

/** The Node autoscaler KPIs, each from its own source; null where that source didn't report. */
export interface NodeKpis {
  nodes: number;
  ready: number;
  capacity: { onDemand: number; spot: number; unknown: number };
  /** Pending pods without a node; null without the agent's pods. */
  waiting: number | null;
  /** The largest waiting pod's ask, for the sub-line. */
  biggestWaiting: { name: string; namespace: string; cpuM: number; memMi: number } | null;
  cpu: { requestedM: number; allocatableM: number | null; requestedPct: number | null; usedPct: number | null };
  memory: { requestedMi: number; allocatableMi: number | null; requestedPct: number | null };
}

export function nodeKpis(dashboard: EksDashboard | null | undefined): NodeKpis {
  const nodes = clusterNodes(dashboard);
  const totals = clusterRequestTotals(dashboard);
  const capacity = clusterCapacity(dashboard);
  const placement = podPlacement(dashboard);
  const waitingPods = clusterPods(dashboard).filter((p) => p.phase === 'Pending' && !p.nodeName);
  const biggest = [...waitingPods].sort((a, b) => sumRequests(b.containers).cpuRequestM - sumRequests(a.containers).cpuRequestM)[0];
  const usedPct = capacity.cpu.used !== null && capacity.cpu.allocatable ? (capacity.cpu.used / capacity.cpu.allocatable) * 100 : null;
  return {
    nodes: nodes.length,
    ready: nodes.filter((n) => n.ready === true).length,
    capacity: capacityTypeCounts(nodes),
    waiting: placement ? placement.waiting : null,
    biggestWaiting: biggest
      ? { name: biggest.name, namespace: biggest.namespace, cpuM: sumRequests(biggest.containers).cpuRequestM, memMi: sumRequests(biggest.containers).memRequestMi }
      : null,
    cpu: { requestedM: totals.cpuRequestM, allocatableM: totals.cpuAllocatableM, requestedPct: totals.cpuRequestedPct, usedPct },
    memory: { requestedMi: totals.memRequestMi, allocatableMi: totals.memAllocatableMi, requestedPct: totals.memRequestedPct },
  };
}

/** Per node, requested and allocatable CPU (vCPU) and pods, for the two node charts. */
export interface NodeBarRow {
  node: string;
  requestedCpu: number;
  allocatableCpu: number | null;
  cpuRequestedPct: number | null;
  pods: number;
  allocatablePods: number | null;
}

export function nodeBarRows(dashboard: EksDashboard | null | undefined): NodeBarRow[] {
  return nodeRows(dashboard)
    .map((n: NodeRow) => ({
      node: n.name,
      requestedCpu: Number((n.cpuRequestM / 1000).toFixed(3)),
      allocatableCpu: n.allocatableCpuM === null ? null : Number((n.allocatableCpuM / 1000).toFixed(3)),
      cpuRequestedPct: n.cpuRequestedPct,
      pods: n.pods,
      allocatablePods: n.allocatablePods,
    }))
    .sort((a, b) => (b.cpuRequestedPct ?? -1) - (a.cpuRequestedPct ?? -1) || a.node.localeCompare(b.node));
}

/** The idle nodes the automation agent reported; null when it didn't (not installed, or no report). */
export function idleNodeCount(agent: AutomationAgentState, fleet: AutomationNodeFleet | null): number | null {
  if (agent !== 'installed' && agent !== 'stale') return null;
  if (!fleet || !fleet.collectedAt || !Array.isArray(fleet.idleNodes)) return null;
  return fleet.idleNodes.length;
}

/** The last rebalance in words (`rebalance-status`). */
export function rebalanceText(status: AutomationRebalanceStatus | null): string {
  if (!status) return 'Not reported';
  const message = textOf(status.message);
  switch (status.status) {
    case 'idle':
      return message ?? 'No rebalance is running';
    case 'running':
      return 'A rebalance is running now';
    case 'completed':
      return message ? `The last rebalance completed: ${message}` : 'The last rebalance completed';
    case 'failed':
      return message ? `The last rebalance failed: ${message}` : 'The last rebalance failed';
    default:
      return message ? `Not known: ${message}` : 'Not known';
  }
}

/** One row of "What node autoscaling would do now" (glossary §12.2). */
export interface NodeLever {
  id: 'scale-up' | 'remove-empty' | 'bin-packing' | 'rebalancing' | 'spot';
  name: string;
  line: string;
  /** What it would do on the cluster as it is now, from the source named in `source`. */
  verdict: string;
  source: string;
  /** Why it isn't available yet. */
  reason: string;
}

export interface NodeLeverInputs {
  agent: AutomationAgentState;
  /** Who changes the node count; null when the agent's data is missing. */
  provisioners: { karpenter: boolean; clusterAutoscaler: boolean } | null;
  kpis: NodeKpis;
  idle: number | null;
  /** Capabilities readiness messages, when the automation agent's parts aren't ready. */
  readiness?: Partial<Record<'node-optimizer-actions' | 'evictor-live' | 'rebalancer', string | null>>;
}

const AGENT_MISSING = "Needs the automation agent, which isn't installed on this cluster.";
const NOT_BUILT = 'Not built in XamOps yet.';

/**
 * The five parts of node autoscaling, each with what it would do now and why it isn't available
 * yet: XamOps doesn't add nodes (not built), the parts that empty and pack nodes need the
 * automation agent, rebalancing needs something that brings up replacement nodes.
 */
export function nodeLevers({ agent, provisioners, kpis, idle, readiness = {} }: NodeLeverInputs): NodeLever[] {
  const agentReason = (key: 'node-optimizer-actions' | 'evictor-live' | 'rebalancer') =>
    agent === 'installed' || agent === 'stale' ? (readiness[key] ?? NOT_BUILT) : agent === 'unknown' ? "XamOps couldn't check the automation agent." : AGENT_MISSING;
  const waiting = kpis.waiting;
  const big = kpis.biggestWaiting;
  const scaleUp = waiting === null
    ? 'Not known: the XamOps agent reported no pods.'
    : waiting === 0
      ? 'No pod is waiting.'
      : `${waiting} ${plural(waiting, 'pod')} ${waiting === 1 ? 'is' : 'are'} waiting.${big ? ` The largest, ${big.namespace}/${big.name}, asks for ${formatVcpu(big.cpuM)} and ${formatMemory(big.memMi)}.` : ''}`;
  const { onDemand, spot, unknown } = kpis.capacity;
  const total = onDemand + spot + unknown;
  const spotVerdict = total === 0
    ? 'Not known: the XamOps agent reported no nodes.'
    : spot === 0 && unknown === 0
      ? `All ${total} ${plural(total, 'node')} are on-demand.`
      : `${spot} of ${total} ${plural(total, 'node')} ${spot === 1 ? 'is' : 'are'} Spot${unknown > 0 ? `, ${unknown} with no capacity type` : ''}.`;
  const replaces = provisioners && (provisioners.karpenter || provisioners.clusterAutoscaler);
  return [
    {
      id: 'scale-up',
      name: 'Scale up for waiting pods',
      line: "Add a node when a pod can't be scheduled.",
      verdict: scaleUp,
      source: 'Pod status, from the XamOps agent',
      reason: "XamOps doesn't add nodes. Not built in XamOps yet.",
    },
    {
      id: 'remove-empty',
      name: 'Remove empty nodes',
      line: 'Empty idle nodes so they can be removed.',
      verdict: idle === null ? "Not known: the automation agent hasn't reported idle nodes." : idle === 0 ? 'No node is idle.' : `${idle} ${plural(idle, 'node')} ${idle === 1 ? 'is' : 'are'} idle.`,
      source: "The automation agent's node report",
      reason: agentReason('node-optimizer-actions'),
    },
    {
      id: 'bin-packing',
      name: 'Bin-packing',
      line: 'Move pods off a node when they all fit elsewhere.',
      verdict: agent === 'installed' || agent === 'stale' ? "Not shown yet: the automation agent's fit check isn't read here." : "Not known: the fit check runs in the automation agent.",
      source: 'Fit check by the automation agent',
      reason: agentReason('evictor-live'),
    },
    {
      id: 'rebalancing',
      name: 'Rebalancing',
      line: 'Swap nodes for a cheaper or better-fitting set.',
      verdict: provisioners === null
        ? "Not known: the XamOps agent hasn't reported who adds nodes."
        : replaces ? 'Something here can bring up replacement nodes.' : 'Not useful here: nothing would bring up replacement nodes (no Karpenter or Cluster Autoscaler).',
      source: 'Detection above',
      reason: provisioners !== null && !replaces ? 'No node provisioner detected.' : agentReason('rebalancer'),
    },
    {
      id: 'spot',
      name: 'Spot handling',
      line: 'Run suitable nodes on Spot capacity.',
      verdict: spotVerdict,
      source: 'Node capacity-type labels, from the XamOps agent',
      reason: NOT_BUILT,
    },
  ];
}

// ─── Activity: each workload's latest change (UI v3 plan §2 "Activity") ──────

export type ActivitySource = 'workload' | 'hpa';

export type ResultGroup = 'applied' | 'test-run' | 'deferred' | 'refused-failed' | 'mode-change' | 'other';

/** One workload's latest recorded change (endpoint 1 `lastAction`). */
export interface LatestChangeRow {
  id: string;
  source: ActivitySource;
  workload: WorkloadView;
  /** Epoch ms; null when the time is missing or unreadable. */
  at: number | null;
  atRaw: string | null;
  action: 'Resize' | 'Mode change';
  result: ActionResult;
  group: ResultGroup;
  /** False for a test run. */
  live: boolean;
}

export function resultGroup(result: ActionResult): ResultGroup {
  switch (result.key) {
    case 'applied':
    case 'applied-put-back':
    case 'partly-applied':
      return 'applied';
    case 'test-run':
      return 'test-run';
    case 'deferred':
    case 'not-confirmed':
      return 'deferred';
    case 'refused':
    case 'failed':
    case 'infeasible':
    case 'expired':
      return 'refused-failed';
    case 'mode-change':
      return 'mode-change';
    default:
      return 'other';
  }
}

export const RESULT_GROUP_LABELS: Record<ResultGroup, string> = {
  applied: 'Applied',
  'test-run': 'Test run',
  deferred: 'Deferred',
  'refused-failed': 'Refused or failed',
  'mode-change': 'Mode changed',
  other: 'Other',
};

/**
 * Every workload's latest recorded change, newest first (an unreadable time last). Endpoint 1
 * records the Workload autoscaler's changes only, so every row's source is the Workload autoscaler.
 */
export function latestChangeRows(workloads: readonly WorkloadView[]): LatestChangeRow[] {
  return workloads
    .flatMap((w): LatestChangeRow[] => {
      const a = w.lastAction;
      if (!a) return [];
      const result = actionResult(a.outcome, a.dryRun);
      if (!result) return [];
      return [{
        id: w.id,
        source: 'workload',
        workload: w,
        at: instantMs(a.at),
        atRaw: a.at,
        action: a.outcome === POLICY_CHANGE ? 'Mode change' : 'Resize',
        result,
        group: resultGroup(result),
        live: a.dryRun !== true && result.key !== 'test-run',
      }];
    })
    .sort((a, b) => (b.at ?? -Infinity) - (a.at ?? -Infinity) || a.id.localeCompare(b.id));
}

export interface ActivityKpis {
  /** Latest change applied live, in full or in part. */
  applied: number;
  testRuns: number;
  refusedOrFailed: number;
  /** Latest change applied live in full: in place unless something outside XamOps changed it since. */
  inPlace: number;
}

export function activityKpis(rows: readonly LatestChangeRow[]): ActivityKpis {
  return {
    applied: rows.filter((r) => r.group === 'applied' && r.live).length,
    testRuns: rows.filter((r) => r.group === 'test-run').length,
    refusedOrFailed: rows.filter((r) => r.group === 'refused-failed').length,
    inPlace: rows.filter((r) => r.live && r.result.key === 'applied').length,
  };
}

const istDay = (ms: number): string => new Date(ms).toLocaleDateString('en-IN', { timeZone: 'Asia/Kolkata' });

/** Today's (IST) latest changes, by result. */
export function todayByResult(rows: readonly LatestChangeRow[], now: number): { applied: number; deferred: number; refused: number; failed: number; testRuns: number } {
  const today = rows.filter((r) => r.at !== null && istDay(r.at) === istDay(now));
  return {
    applied: today.filter((r) => r.group === 'applied' && r.live).length,
    deferred: today.filter((r) => r.group === 'deferred').length,
    refused: today.filter((r) => r.result.key === 'refused').length,
    failed: today.filter((r) => r.group === 'refused-failed' && r.result.key !== 'refused').length,
    testRuns: today.filter((r) => r.group === 'test-run').length,
  };
}

/** The Kubernetes event reasons that are about scaling, resizing or eviction. */
export const SCALING_EVENT_REASON = /Resize|Scal|Evict|Rescale/;
