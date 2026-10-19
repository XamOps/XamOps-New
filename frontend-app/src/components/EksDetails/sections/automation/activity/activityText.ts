/**
 * The activity feed's words (FEAT-007; glossary §2.2): each entry's result chip, its restart-free
 * proof chip and its before → after text. Every chip carries its icon and its label; a result this
 * build doesn't know reads "Other: …", never a success; a value the row didn't record is a dash or
 * "before not recorded", never 0.
 */
import { DASH, formatMemoryMi, modeText } from '../../../../../api/verticalRightsizerAdapter';
import { RESULT_LABELS, RESULT_TONES, type Tone } from '../../../../CloudK8s/shared/status/statusTones';
import type {
  ActivityContainerChange,
  ActivityEntryView,
  ActivityHpaBounds,
  ActivityResultKey,
  ActivitySizes,
} from '../../../../../types/automationActivity';

export interface ActivityChip {
  tone: Tone;
  icon: string;
  label: string;
}

/** The Result filter's wording for each result. */
export const RESULT_FILTER_LABELS: Record<ActivityResultKey, string> = {
  applied: 'Applied',
  'put-back': 'Deferred or put back',
  refused: 'Refused',
  'test-run': 'Test run',
  failed: 'Failed',
};

export const RESULT_FILTER_ORDER: readonly ActivityResultKey[] = ['applied', 'put-back', 'refused', 'test-run', 'failed'];

/** One entry's result as a chip, from the server's result and the stored outcome and proof. */
export function activityResultChip(e: ActivityEntryView): ActivityChip {
  switch (e.result) {
    case 'applied':
      return e.outcome === 'PARTIALLY_APPLIED'
        ? { ...RESULT_TONES['partly-applied'], label: RESULT_LABELS['partly-applied'] }
        : { ...RESULT_TONES.applied, label: RESULT_LABELS.applied };
    case 'put-back': {
      const reverted = e.proof?.reverted ?? null;
      if (reverted === false) return { tone: 'warn', icon: 'mdi:progress-question', label: 'Deferred, put back not confirmed' };
      if (e.terminal === 'INFEASIBLE') return { tone: 'warn', icon: 'mdi:undo-variant', label: "Didn't fit the node, put back" };
      if (e.terminal === 'DEFERRED' || reverted === true) return { tone: 'warn', icon: 'mdi:undo-variant', label: 'Deferred, put back' };
      return { tone: 'warn', icon: 'mdi:minus-circle-outline', label: 'Not applied: no pod changed' };
    }
    case 'refused':
      return { ...RESULT_TONES.refused, label: RESULT_LABELS.refused };
    case 'test-run':
      return { ...RESULT_TONES['test-run'], label: 'Test run' };
    case 'failed':
      return e.outcome === 'EXPIRED'
        ? { ...RESULT_TONES.expired, label: RESULT_LABELS.expired }
        : { ...RESULT_TONES.failed, label: RESULT_LABELS.failed };
    default:
      if (e.action === 'mode-change') return { tone: 'info', icon: 'mdi:tune-variant', label: 'Mode changed' };
      // The HPA agent path records the command as queued; the applied result arrives later.
      if (e.outcome === 'QUEUED') return { tone: 'info', icon: 'mdi:progress-clock', label: 'Queued for the in-cluster agent' };
      return { tone: 'off', icon: 'mdi:help-circle-outline', label: `Other: ${e.outcome ?? DASH}` };
  }
}

const SHORT = 200;

/**
 * The line under an entry's result chip: a refusal's message without its code, the kubelet's words
 * for a deferral without the revert note (the chip says it), "Nothing was sent to the cluster." for
 * a test run, and the stored detail otherwise. `short` is cut at 200 characters; `full` is the
 * stored detail, for a title. Null when there is nothing to add.
 */
export function resultDetail(e: ActivityEntryView): { short: string; full: string | null } | null {
  let text: string | null;
  if (e.result === 'test-run' || e.dryRun) text = 'Nothing was sent to the cluster.';
  else if (e.action === 'mode-change') text = null;
  else if (e.result === 'refused') text = e.detail ? e.detail.replace(/^K8S_[A-Z_]+:\s*/, '') : null;
  else if (e.result === 'put-back') text = e.detail ? e.detail.replace(/\s*Revert: [A-Z_, ]+\.\s*$/, '') : null;
  else text = e.detail;
  if (!text || text.trim() === '') return null;
  const t = text.trim();
  return { short: t.length > SHORT ? `${t.slice(0, SHORT - 1).trimEnd()}…` : t, full: e.detail };
}

/** Whether the entry touched pods at all: a test run, a refusal, a mode change and an HPA change don't. */
export const touchedPods = (e: ActivityEntryView): boolean =>
  e.action === 'resize' && !e.dryRun && e.result !== 'refused' && e.result !== 'test-run';

/**
 * The restart-free proof as a chip, or null when the entry touched no pod. "Restart-free" only when
 * the server says every pod touched was proved not to have restarted; a pod that was replaced or
 * restarted reads "Restarted"; anything else "Not proved".
 */
export function proofChip(e: ActivityEntryView): ActivityChip | null {
  if (!touchedPods(e)) return null;
  if (e.restartFree) return { tone: 'good', icon: 'mdi:shield-check-outline', label: 'Restart-free' };
  const restarted = e.proof?.pods.some(
    (p) => p.uidChanged === true || (p.restartsBefore !== null && p.restartsAfter !== null && p.restartsAfter > p.restartsBefore),
  );
  if (restarted) return { tone: 'bad', icon: 'mdi:alert-octagon-outline', label: 'Restarted' };
  return { tone: 'warn', icon: 'mdi:progress-question', label: 'Not proved' };
}

/** Why an entry has no proof chip, in words. */
export function noProofText(e: ActivityEntryView): string {
  if (e.action === 'hpa-change') return 'No pod touched: an HPA change';
  if (e.action === 'mode-change') return 'No pod touched: a mode change';
  if (e.dryRun || e.result === 'test-run') return 'No pod touched: a test run';
  if (e.result === 'refused') return 'No pod touched: refused';
  return DASH;
}

const millis = (v: number | null): string => (v === null ? DASH : `${v}m`);
const mib = (v: number | null): string => (v === null ? DASH : formatMemoryMi(v));

const FIELDS: ReadonlyArray<{ key: keyof ActivitySizes; label: string; format: (v: number | null) => string }> = [
  { key: 'cpuRequestM', label: 'CPU request', format: millis },
  { key: 'cpuLimitM', label: 'CPU limit', format: millis },
  { key: 'memRequestMi', label: 'Memory request', format: mib },
  { key: 'memLimitMi', label: 'Memory limit', format: mib },
];

export interface SizeChangeText {
  /** "CPU request" */
  field: string;
  /** "1000m → 25m", "10m → 63m asked" */
  text: string;
  /** "put back to 10m", "nothing was sent", "observed 10m" */
  note: string | null;
}

/**
 * One container's change, per field it touched, in the words its result calls for: applied reads
 * before → after (observed); put back reads before → asked, then what the pod runs now; a test run
 * reads before → what it would set; a failure reads before → asked, with what the pod ran
 * afterwards when that differs. A row that didn't record the size before says so.
 */
export function containerChangeText(c: ActivityContainerChange, e: ActivityEntryView): SizeChangeText[] {
  const lines: SizeChangeText[] = [];
  for (const f of FIELDS) {
    const before = c.before?.[f.key] ?? null;
    const after = c.after?.[f.key] ?? null;
    const asked = c.requested?.[f.key] ?? null;
    const moved = asked !== null || (before !== null && after !== null && before !== after);
    if (!moved) continue;
    const from = before !== null ? f.format(before) : 'before not recorded';
    if (e.result === 'applied') {
      lines.push({ field: f.label, text: `${from} → ${f.format(after ?? asked)}`, note: null });
    } else if (e.result === 'put-back') {
      lines.push({ field: f.label, text: `${from} → ${f.format(asked)} asked`, note: after !== null ? `put back to ${f.format(after)}` : 'put back' });
    } else if (e.result === 'test-run' || e.dryRun) {
      // A test run reads no pod, so the row has no size before: say what it would have set.
      lines.push({ field: f.label, text: before !== null ? `${from} → ${f.format(asked ?? after)}` : `would set ${f.format(asked ?? after)}`, note: null });
    } else {
      lines.push({
        field: f.label,
        text: `${from} → ${f.format(asked ?? after)} asked`,
        note: after !== null && asked !== null && after !== asked ? `observed ${f.format(after)}` : null,
      });
    }
  }
  return lines;
}

const replicas = (b: ActivityHpaBounds | null): string =>
  b ? `${b.minReplicas ?? DASH}–${b.maxReplicas ?? DASH} replicas${b.targetUtilizationPct !== null ? `, CPU target ${b.targetUtilizationPct}%` : ''}` : DASH;

/** "1–5 replicas, CPU target 50% → 1–8 replicas, CPU target 50%" */
export function hpaChangeText(e: ActivityEntryView): string | null {
  if (!e.hpa) return null;
  return `${replicas(e.hpa.before)} → ${replicas(e.hpa.after)}`;
}

/** "Suggest only → Apply automatically" */
export function modeChangeText(e: ActivityEntryView): string | null {
  if (!e.mode) return null;
  return `${modeText(e.mode.from)} → ${modeText(e.mode.to)}`;
}

/** The action in words. */
export function actionText(e: ActivityEntryView): string {
  if (e.action === 'resize') return 'Resize';
  if (e.action === 'hpa-change') return 'HPA change';
  if (e.action === 'mode-change') return 'Mode change';
  return DASH;
}

/** "A user" / "XamOps (automatic)"; a dash when the row doesn't say. */
export const whoText = (e: ActivityEntryView): string =>
  e.who ?? (e.origin === 'AUTO' ? 'XamOps (automatic)' : e.origin === 'MANUAL' ? 'A user' : DASH);

/** The workload (or HPA) the entry names, as "namespace/Kind/name". */
export const entryRef = (e: ActivityEntryView): string =>
  `${e.target.namespace ?? DASH}/${e.target.kind ?? DASH}/${e.target.name ?? DASH}`;
