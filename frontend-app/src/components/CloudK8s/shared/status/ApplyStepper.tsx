/**
 * The apply progress stepper (design-delta §4.4; glossary §6). It turns endpoint 3's reply
 * (`ApplyOutcomeWire.terminal` + `pods[]`, src/types/workloadRightsizing.ts) into the step-by-step
 * card: safety checks, the test pod and confirmation, the rest of the pods, then the terminal path —
 * success, deferred, infeasible or refused — and the final result row.
 *
 * The deferred path uses honest interim copy. The `pods[].revert` proof field is a backend change
 * (S7 / BE-11) that is not on this branch, so a deferred apply reads
 * "Kubernetes deferred this. The new size may still be applied later." rather than claiming XamOps
 * put the pod back. That is what today's build would honestly report (design-delta §5.0).
 *
 * `buildApplySteps` is pure, so every terminal path is unit-tested without the DOM.
 */
import { Icon } from '@iconify/react';
import { formatIST } from '../../../../utils/timeUtils';
import type { ApplyOutcomeWire, ApplyPodProofWire } from '../../../../types/workloadRightsizing';
import type { Tone } from './statusTones';
import { resultKeyForTerminal, RESULT_TONES } from './statusTones';
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

function podShort(pod: ApplyPodProofWire): string {
  const parts = pod.podName.split('-');
  return parts[parts.length - 1] || pod.podName;
}

/** Build the ordered steps from endpoint 3's reply (glossary §6). */
export function buildApplySteps(outcome: ApplyOutcomeWire | null): ApplyStep[] {
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
  pods.forEach((pod, i) => {
    const confirmed = pod.ready === true && pod.samePod !== false;
    const restarts = pod.containers?.[0]
      ? `${pod.containers[0].restartCountBefore ?? 0} → ${pod.containers[0].restartCountAfter ?? 0}`
      : null;
    steps.push({
      key: `pod-${i}`,
      tone: confirmed ? 'good' : 'info',
      icon: confirmed ? 'mdi:check-circle' : 'mdi:progress-clock',
      title: i === 0 ? `Resized pod 1 of ${pods.length}, the test pod` : `Resized pod ${i + 1} of ${pods.length}`,
      detail: `${podShort(pod)}${restarts ? `: restarts ${restarts}` : ''}${pod.ready === true ? ', still ready' : ''}.`,
    });
  });

  // Terminal path.
  if (result === 'applied') {
    steps.push({
      key: 'done',
      tone: 'good',
      icon: RESULT_TONES.applied.icon,
      title: 'Done: applied in every pod, no restart',
      detail: outcome.detail ?? undefined,
      banded: true,
    });
  } else if (result === 'deferred') {
    steps.push({
      key: 'deferred',
      tone: RESULT_TONES.deferred.tone,
      icon: RESULT_TONES.deferred.icon,
      title: 'Kubernetes deferred this: the node has no free room',
      detail: outcome.detail ?? undefined,
    });
    steps.push({
      key: 'result',
      tone: 'warn',
      icon: RESULT_TONES.deferred.icon,
      title: 'Result: not applied. Nothing changed.',
      detail: DEFERRED_INTERIM,
      banded: true,
    });
  } else if (result === 'infeasible') {
    steps.push({
      key: 'infeasible',
      tone: RESULT_TONES.infeasible.tone,
      icon: RESULT_TONES.infeasible.icon,
      title: 'Kubernetes can never fit this size on its node',
      detail: outcome.detail ?? undefined,
    });
    steps.push({
      key: 'result',
      tone: 'bad',
      icon: RESULT_TONES.infeasible.icon,
      title: 'Result: not applied. Nothing changed.',
      banded: true,
    });
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
    // partly-applied, not-confirmed, failed, expired.
    const r = RESULT_TONES[result];
    steps.push({
      key: 'result',
      tone: r.tone,
      icon: r.icon,
      title: 'Result: not applied. Nothing changed.',
      detail: outcome.detail ?? undefined,
      banded: true,
    });
  }

  return steps;
}

export interface ApplyStepperProps {
  outcome: ApplyOutcomeWire | null;
  /** True while the apply is still running ("Live: updates every 2 seconds."). */
  live?: boolean;
  className?: string;
}

export function ApplyStepper({ outcome, live = false, className = '' }: ApplyStepperProps) {
  const steps = buildApplySteps(outcome);
  const resultChip = outcome ? RESULT_TONES[resultKeyForTerminal(outcome.terminal)] : null;

  return (
    <section
      className={`rounded-lg border border-[rgba(0,0,0,0.08)] bg-white px-4 py-3 dark:border-[rgba(255,255,255,0.1)] dark:bg-[#141414] ${className}`}
      data-apply-stepper=""
    >
      <div className="flex items-start justify-between gap-3">
        <h3 className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] dark:text-[rgba(255,255,255,0.92)]">Apply progress</h3>
        {resultChip && (
          <span className={`inline-flex items-center gap-1 text-[12px] font-semibold ${toneClasses(resultChip.tone).text}`} data-result-tone={resultChip.tone}>
            <Icon icon={resultChip.icon} width={14} aria-hidden="true" className="shrink-0" />
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
              <span className="shrink-0 text-[11px] tabular-nums text-[rgba(0,0,0,0.5)] dark:text-[rgba(255,255,255,0.5)]">
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
