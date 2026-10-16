/**
 * The apply progress stepper (design-delta §4.4; glossary §6). It turns endpoint 3's reply
 * (`ApplyOutcomeWire.terminal` + `pods[]`, src/types/verticalRightsizer.ts) into the step-by-step
 * card: safety checks, the test pod and the rest of the pods, then the terminal path — success,
 * deferred, infeasible or refused — and the final result row.
 *
 * The put-back rows come from the executor's own proof, `pods[].revert` (fix S7, now on main):
 * REVERTED reads "So XamOps put the pod back"; NOT_CONFIRMED and FAILED say the new size may still be
 * applied later. A reply from a build without that field keeps the honest interim copy,
 * "Kubernetes deferred this. The new size may still be applied later.", and never claims a put-back
 * the server didn't prove (design-delta §5.0).
 *
 * The success row says only what the reply proves (glossary v2 §8.2, §11.3; `ResizeProof`): "every
 * running pod (N of M)" when `skippedPods` left pods out, "no restart" only when `restartFree` is
 * true (else "no restart-free proof"), and a Restart row for each container whose restart count
 * rose. Pods are numbered against every pod of the workload, the skipped ones included, and a
 * closing row never says "Nothing changed" while a pod the reply lists did change.
 *
 * `buildApplySteps` is pure, so every terminal path is unit-tested without the DOM.
 */
import { Icon } from '@iconify/react';
import { formatIST } from '../../../../utils/timeUtils';
import { COOLDOWN_MINUTES, formatMemoryMi } from '../../../../api/verticalRightsizerAdapter';
import type { ApplyOutcomeWire, ApplyPodProofWire, VerticalResourcesWire } from '../../../../types/verticalRightsizer';
import type { Tone } from './statusTones';
import { resultKeyForTerminal, RESULT_LABELS, RESULT_TONES } from './statusTones';
import { everyPodPhrase, restartedContainers, skippedPodText, skippedPodsOf, type SkippedPodWire } from './applyProof';
import StatusChip from './StatusChip';
import { toneClasses } from './toneClasses';

export interface ApplyStep {
  key: string;
  tone: Tone;
  icon: string;
  title: string;
  detail?: string;
  /** An ISO time for the step, or null when it has no time yet ("—"). */
  at?: string | null;
  /** The final result row gets a tinted band. */
  banded?: boolean;
}

const DEFERRED_INTERIM = 'Kubernetes deferred this. The new size may still be applied later.';
const MAY_STILL_APPLY = 'The new size may still be applied later. Check the pod before trying again.';
const MAKE_ROOM = `Make room on its node, then apply again. No wait applies: the ${COOLDOWN_MINUTES}-minute wait starts only after a change takes effect.`;

function podShort(pod: ApplyPodProofWire): string {
  const parts = pod.podName.split('-');
  return parts[parts.length - 1] || pod.podName;
}

/** "10m CPU, 50Mi memory": the requests a pod ran before the apply, for the put-back row. */
function sizeText(r: VerticalResourcesWire | null | undefined): string | null {
  if (!r) return null;
  const parts: string[] = [];
  if (r.cpuRequestM !== null && r.cpuRequestM !== undefined) parts.push(`${r.cpuRequestM}m CPU`);
  if (r.memRequestMi !== null && r.memRequestMi !== undefined) parts.push(`${formatMemoryMi(r.memRequestMi)} memory`);
  return parts.length > 0 ? parts.join(', ') : null;
}

/** What putting one declined pod back achieved, from its `revert` proof; null when none was needed. */
function putBackStep(pod: ApplyPodProofWire, i: number): ApplyStep | null {
  const revert = pod.revert;
  if (!revert || !revert.state) return null;
  const key = `revert-${i}`;
  const first = pod.containers?.[0];
  const from = sizeText(first?.observedBefore);
  switch (revert.state) {
    case 'REVERTED': {
      const noRestart =
        first && first.restartCountBefore !== null && first.restartCountBefore === first.restartCountAfter ? ' No restart.' : '';
      return {
        key,
        tone: 'warn',
        icon: RESULT_TONES['applied-put-back'].icon,
        title: 'So XamOps put the pod back',
        detail: `${from ? `Back to ${from}` : 'Back to its size before'}, and the pod confirmed it: nothing is pending.${noRestart}`,
      };
    }
    case 'NOT_CONFIRMED':
      return {
        key,
        tone: 'warn',
        icon: RESULT_TONES['not-confirmed'].icon,
        title: "XamOps sent the pod back, but it didn't confirm",
        detail: `XamOps sent the pod back${from ? ` to ${from}` : ''}, but it didn't confirm before the deadline. The new size may still be applied later.`,
      };
    case 'FAILED':
      return {
        key,
        tone: 'bad',
        icon: RESULT_TONES.failed.icon,
        title: "XamOps couldn't put the pod back",
        detail: `XamOps couldn't put the pod back${revert.detail ? `: ${revert.detail}` : ''}. The new size may still be applied later.`,
      };
    default:
      return {
        key,
        tone: 'warn',
        icon: 'mdi:help-circle-outline',
        title: `Putting the pod back: Other: ${revert.state}`,
        detail: revert.detail ?? undefined,
      };
  }
}

/**
 * The closing row of a path that didn't apply. With the put-back proof, "Nothing changed" is said
 * only when every declined pod was proved back; without it (an older build), the interim copy stays.
 */
function notAppliedResult(outcome: ApplyOutcomeWire, tone: Tone, icon: string, deferred: boolean): ApplyStep {
  if (outcome.reverted === false) {
    return { key: 'result', tone, icon, title: 'Result: not applied yet.', detail: MAY_STILL_APPLY, banded: true };
  }
  if (outcome.reverted === true) {
    return {
      key: 'result',
      tone,
      icon,
      title: 'Result: not applied. Nothing changed.',
      detail: deferred ? MAKE_ROOM : undefined,
      banded: true,
    };
  }
  return {
    key: 'result',
    tone,
    icon,
    title: 'Result: not applied. Nothing changed.',
    detail: deferred ? DEFERRED_INTERIM : undefined,
    banded: true,
  };
}

const RESOURCE_FIELDS = ['cpuRequestM', 'memRequestMi', 'cpuLimitM', 'memLimitMi'] as const;

/**
 * Whether Kubernetes put the new size in this pod. A ready pod is not proof: a deferred pod stays
 * ready at its old size. The executor's own `changed` flag decides; a build without it falls back to
 * the resources it observed before and after, and only then to "same pod, still ready".
 */
function podResized(pod: ApplyPodProofWire): boolean {
  if (pod.ready !== true || pod.samePod === false) return false;
  if (typeof pod.changed === 'boolean') return pod.changed;
  const observed = (pod.containers ?? []).filter((c) => c.observedBefore && c.observedAfter);
  if (observed.length > 0) {
    return observed.some((c) => RESOURCE_FIELDS.some((f) => (c.observedBefore![f] ?? null) !== (c.observedAfter![f] ?? null)));
  }
  return true;
}

/** Whether the reply shows this pod's resources moved: its `changed` flag, else before against after. */
function podChanged(pod: ApplyPodProofWire): boolean {
  if (typeof pod.changed === 'boolean') return pod.changed;
  return (pod.containers ?? []).some(
    (c) => !!c.observedBefore && !!c.observedAfter && RESOURCE_FIELDS.some((f) => (c.observedBefore![f] ?? null) !== (c.observedAfter![f] ?? null)),
  );
}

const resourceWord = (insufficient: string | null | undefined): string =>
  insufficient === 'cpu' ? 'CPU' : insufficient === 'memory' ? 'memory' : 'room';

/** The workload's pods the reply left out (glossary §6 "Not touched"), numbered after the touched ones. */
function skippedStep(skipped: readonly SkippedPodWire[], touched: number): ApplyStep | null {
  if (skipped.length === 0) return null;
  const total = touched + skipped.length;
  const first = touched + 1;
  const which =
    skipped.length === 1 ? `Pod ${first} of ${total}` : skipped.length === 2 ? `Pods ${first} and ${total} of ${total}` : `Pods ${first} to ${total} of ${total}`;
  return {
    key: 'skipped',
    tone: 'off',
    icon: 'mdi:minus-circle-outline',
    title: `${which} not touched`,
    detail: `${skipped.map((p) => `${p.podName}: ${skippedPodText(p)}`).join('; ')}. ${skipped.length === 1 ? 'It runs' : 'They run'} at the workload's own size.`,
  };
}

/** The glossary's Restart row, once per container whose restart count rose during the change. */
function restartSteps(outcome: ApplyOutcomeWire): ApplyStep[] {
  return restartedContainers(outcome).map((r) => ({
    key: `restart-${r.podIndex}-${r.container}`,
    tone: 'bad' as const,
    icon: 'mdi:restart-alert',
    title: 'A container restarted during the resize',
    detail: `${podShort(r.pod)}, container ${r.container}: restart count ${r.before} → ${r.after}. This was not a restart-free change.${
      r.bySetting ? ' The container restarts by its own setting.' : ''
    }`,
  }));
}

/**
 * The Done row's caveat (glossary §6): an in-place resize changes the running pods only, so the
 * workload's own template still holds the old size. Said only for `IN_PLACE`, the method that leaves
 * the template alone; the price clause waits for prices (BE-4).
 */
function doneDetail(outcome: ApplyOutcomeWire, kind: string | undefined): string | undefined {
  if (outcome.method !== 'IN_PLACE') return outcome.detail ?? undefined;
  const old = sizeText(outcome.pods?.[0]?.containers?.[0]?.observedBefore);
  const caveat = `The ${kind ?? 'workload'} itself wasn't changed, so a pod created later starts at ${old ?? 'its old size'}.`;
  return outcome.detail ? `${outcome.detail} ${caveat}` : caveat;
}

/**
 * The closing line of a path that isn't a clean success but did change a pod (glossary v2 §8.2), so
 * the row never says "Nothing changed" over a pod the reply shows resized.
 */
const CHANGED_RESULT_TEXT: Record<string, string> = {
  PARTIALLY_APPLIED: 'It stopped at the first pod that failed. The pods above say which changed.',
  RESTART_DETECTED: 'A container restarted during the resize. This was not a restart-free change.',
  NOT_READY: 'The new size is in place, but the pod stopped being ready.',
};

export interface ApplyStepContext {
  /** The workload's kind ("Deployment", "StatefulSet"), for the Done row's caveat. */
  kind?: string;
}

/** Build the ordered steps from endpoint 3's reply (glossary §6). */
export function buildApplySteps(outcome: ApplyOutcomeWire | null, context: ApplyStepContext = {}): ApplyStep[] {
  if (!outcome) {
    return [{ key: 'waiting', tone: 'info', icon: 'mdi:progress-clock', title: 'Waiting for the cluster…', at: null }];
  }

  const steps: ApplyStep[] = [];
  const result = resultKeyForTerminal(outcome.terminal);
  const refused = result === 'refused';

  // Safety checks (good unless the whole apply was refused before any patch).
  steps.push({
    key: 'checks',
    tone: refused ? 'bad' : 'good',
    icon: refused ? RESULT_TONES.refused.icon : 'mdi:shield-check',
    title: refused ? 'Stopped by a safety rule' : 'Checked the safety rules: all passed',
    detail: refused ? (outcome.detail ?? undefined) : undefined,
  });

  if (refused) {
    steps.push({
      key: 'result',
      tone: 'bad',
      icon: RESULT_TONES.refused.icon,
      title: 'Result: not applied. Nothing changed.',
      detail: outcome.detail ?? undefined,
      banded: true,
    });
    return steps;
  }

  const pods = outcome.pods ?? [];
  const skipped = skippedPodsOf(outcome);
  // Numbered against every pod of the workload, so a pod left out is never silently dropped.
  const total = pods.length + skipped.length;
  pods.forEach((pod, i) => {
    const confirmed = podResized(pod);
    const restarts = pod.containers?.[0]
      ? `${pod.containers[0].restartCountBefore ?? 0} → ${pod.containers[0].restartCountAfter ?? 0}`
      : null;
    const which = i === 0 ? `pod 1 of ${total}, the test pod` : `pod ${i + 1} of ${total}`;
    steps.push({
      key: `pod-${i}`,
      tone: confirmed ? 'good' : 'info',
      icon: confirmed ? 'mdi:check-circle' : 'mdi:progress-clock',
      title: confirmed ? `Resized ${which}` : `Resizing ${which}`,
      detail: `${podShort(pod)}${restarts ? `: restarts ${restarts}` : ''}${pod.ready === true ? ', still ready' : ''}.`,
    });
  });
  const notTouched = skippedStep(skipped, pods.length);
  if (notTouched) steps.push(notTouched);
  const restartRows = restartSteps(outcome);
  steps.push(...restartRows);
  const putBack = pods.map(putBackStep).filter((s): s is ApplyStep => s !== null);

  // Terminal path.
  if (result === 'applied') {
    // "no restart" is a proof, not a default: only restartFree says it, and never over a restart.
    const proof = outcome.restartFree === true && restartRows.length === 0 ? 'no restart' : 'no restart-free proof';
    steps.push({
      key: 'done',
      tone: 'good',
      icon: RESULT_TONES.applied.icon,
      title: `Done: applied in ${everyPodPhrase(outcome)}, ${proof}`,
      detail: doneDetail(outcome, context.kind),
      banded: true,
    });
  } else if (result === 'deferred') {
    steps.push({
      key: 'deferred',
      tone: RESULT_TONES.deferred.tone,
      icon: RESULT_TONES.deferred.icon,
      title: `Kubernetes deferred this: the node has no free ${resourceWord(outcome.insufficient)}`,
      detail: outcome.detail ?? undefined,
    });
    steps.push(...putBack);
    steps.push(notAppliedResult(outcome, 'warn', RESULT_TONES.deferred.icon, true));
  } else if (result === 'infeasible') {
    steps.push({
      key: 'infeasible',
      tone: RESULT_TONES.infeasible.tone,
      icon: RESULT_TONES.infeasible.icon,
      title: 'Kubernetes can never fit this size on its node',
      detail: outcome.detail ?? undefined,
    });
    steps.push(...putBack);
    steps.push(notAppliedResult(outcome, 'bad', RESULT_TONES.infeasible.icon, false));
  } else if (result === 'test-run') {
    steps.push({
      key: 'done',
      tone: RESULT_TONES['test-run'].tone,
      icon: RESULT_TONES['test-run'].icon,
      title: 'Test run passed: nothing was changed',
      detail: outcome.detail ?? undefined,
      banded: true,
    });
  } else {
    // partly-applied, not-confirmed, failed (incl. a restart or a pod that stopped being ready), expired.
    const r = RESULT_TONES[result];
    steps.push(...putBack);
    const label = RESULT_LABELS[result];
    steps.push(
      outcome.reverted === false
        ? { key: 'result', tone: r.tone, icon: r.icon, title: 'Result: not applied yet.', detail: MAY_STILL_APPLY, banded: true }
        : pods.some(podChanged)
          ? {
              key: 'result',
              tone: r.tone,
              icon: r.icon,
              title: `Result: ${label.charAt(0).toLowerCase()}${label.slice(1)}.`,
              detail: CHANGED_RESULT_TEXT[outcome.terminal ?? ''] ?? outcome.detail ?? undefined,
              banded: true,
            }
          : { key: 'result', tone: r.tone, icon: r.icon, title: 'Result: not applied. Nothing changed.', detail: outcome.detail ?? undefined, banded: true },
    );
  }

  return steps;
}

export interface ApplyStepperProps {
  outcome: ApplyOutcomeWire | null;
  /** True while the apply is still running ("Live: updates every 2 seconds."). */
  live?: boolean;
  /** The workload's kind ("Deployment", "StatefulSet"), named in the Done row's caveat. */
  workloadKind?: string;
  className?: string;
}

export function ApplyStepper({ outcome, live = false, workloadKind, className = '' }: ApplyStepperProps) {
  const steps = buildApplySteps(outcome, { kind: workloadKind });
  const resultKey = outcome ? resultKeyForTerminal(outcome.terminal) : null;

  return (
    <section
      className={`rounded-lg border border-[rgba(0,0,0,0.08)] bg-white px-4 py-3 dark:border-[rgba(255,255,255,0.1)] dark:bg-[#141414] ${className}`}
      data-apply-stepper=""
      data-k8s-kit=""
    >
      <div className="flex items-start justify-between gap-3">
        <h3 className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] dark:text-[rgba(255,255,255,0.92)]">Apply progress</h3>
        {resultKey && (
          <span data-result-tone={RESULT_TONES[resultKey].tone}>
            <StatusChip tone={RESULT_TONES[resultKey].tone} icon={RESULT_TONES[resultKey].icon} label={RESULT_LABELS[resultKey]} />
          </span>
        )}
      </div>
      <p className="mt-0.5 text-[12px] text-[rgba(0,0,0,0.6)] dark:text-[rgba(255,255,255,0.6)]">
        {live ? 'Live: updates every 2 seconds.' : 'Each step as it happened.'}
      </p>
      <ol className="mt-3 space-y-2">
        {steps.map((step) => {
          const t = toneClasses(step.tone);
          const band = step.banded ? `${t.band} ${t.border} border-l-4` : '';
          return (
            <li key={step.key} className={`flex items-start gap-2 rounded-md px-2 py-1.5 ${band}`} data-apply-step={step.key}>
              <Icon icon={step.icon} width={16} aria-hidden="true" className={`mt-0.5 shrink-0 ${t.text}`} />
              <div className="min-w-0 flex-1">
                <div className={`text-[13px] font-semibold ${t.text}`}>{step.title}</div>
                {step.detail && (
                  <div className="text-[12px] text-[rgba(0,0,0,0.6)] dark:text-[rgba(255,255,255,0.6)]">{step.detail}</div>
                )}
              </div>
              <span className="shrink-0 text-[11px] tabular-nums text-[rgba(0,0,0,0.56)] dark:text-[rgba(255,255,255,0.5)]">
                {step.at ? formatIST(step.at, { short: true }) : '—'}
              </span>
            </li>
          );
        })}
      </ol>
    </section>
  );
}

export default ApplyStepper;
