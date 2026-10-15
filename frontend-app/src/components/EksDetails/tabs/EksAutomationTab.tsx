/**
 * EKS Automation Tab — Workload rightsizing (CPU and memory requests and limits).
 *
 * Reads `GET /api/eks/automation/vertical/{cluster}` and calls the apply endpoint. Four rules shape
 * everything here:
 *
 *  - Nothing is fabricated. A value XamOps did not measure renders `—`, a value the manifest leaves
 *    unset renders "Not set", and a `0` arriving in a resources field is reported as a payload
 *    defect rather than drawn as a number.
 *  - Apply is a one-time, user-initiated action. It is enabled when XamOps suggests a real change
 *    and every gate the server re-checks passes (`manualApplyGate`); otherwise it is greyed with the
 *    blocking reason shown. `autoApplyEligible` ("XamOps would apply this on its own", which needs
 *    mode Apply automatically) is shown as "Automatic changes" and never gates the button.
 *  - A real resize is confirmed first, with the before and after per container. A test run is not.
 *  - Every word on screen comes from the CloudK8s glossary (automation-v2/glossary.md, section 11).
 *    Codes never appear as visible text; they sit in titles as "Code: <CODE>" for support.
 */

import React, { useEffect, useState } from 'react';
import { Icon } from '@iconify/react';
import {
  COOLDOWN_MINUTES,
  DASH,
  codeText,
  findingSentence,
  formatCoverage,
  formatCount,
  formatInstant,
  formatMib,
  formatMillis,
  formatText,
  manualApplyGate,
  modeChangeGate,
  modeText,
  reasonSentence,
  reasonTitle,
  windowWords,
  workloadStatus,
  workloadWindowHours,
  WRITE_GATES_NOT_CHECKED,
  writeGateText,
} from '../../../api/verticalRightsizerAdapter';
import useVerticalRightsizer, { type UseVerticalRightsizerState } from '../../../hooks/useVerticalRightsizer';
import { NowBanner } from '../../CloudK8s/shared/status/NowBanner';
import { HowItWorks } from '../../CloudK8s/shared/status/HowItWorks';
import { StatusChip } from '../../CloudK8s/shared/status/StatusChip';
import { nowSentence } from '../../CloudK8s/shared/status/nowSentence';
import type { Tone } from '../../CloudK8s/shared/status/statusTones';
import type {
  ApplyContainerRequest,
  ApplyOutcomeWire,
  ApplyPodProofWire,
  ApplyResult,
  CodeView,
  ContainerView,
  EngineView,
  ManualApplyGate,
  MetricValue,
  ModeResult,
  VerticalResourcesWire,
  WorkloadModeValue,
  WorkloadView,
} from '../../../types/verticalRightsizer';

/**
 * The tone and icon for a workload's status word (glossary §2.1). The status text from
 * `workloadStatus` is matched by its leading words so a chip always carries the right colour, icon
 * and label together — status is never colour alone.
 */
const workloadStatusTone = (status: string): { tone: Tone; icon: string } => {
  if (status.startsWith('Needs more') || status.startsWith('Not running')) return { tone: 'bad', icon: 'mdi:speedometer' };
  if (status === 'Over-provisioned') return { tone: 'warn', icon: 'mdi:arrow-collapse-down' };
  if (status === 'Right-sized' || status.startsWith('Applied')) return { tone: 'good', icon: 'mdi:check-circle' };
  if (status === 'Not enough data yet') return { tone: 'info', icon: 'mdi:progress-clock' };
  if (status.startsWith('Skipped')) return { tone: 'off', icon: 'mdi:debug-step-over' };
  // Left alone / Off and anything else: a neutral off tone with a label.
  return { tone: 'off', icon: 'mdi:minus-circle-outline' };
};

interface EksAutomationTabProps {
  clusterName: string;
  accountId: string;
  region: string;
  /**
   * A host page's own read and apply state (Automation › Workload autoscaler › Workloads). When it is
   * given, this tab reads, applies and switches modes through it: its own hook gets no cluster, so
   * endpoint 1 is never read twice, and the host page shows the Now banner and How it works.
   */
  controller?: UseVerticalRightsizerState;
  /** Show only this workload's card (the workload detail page), by its `namespace/kind/name` id. */
  onlyWorkloadId?: string;
}

// ─── Helpers, following the EksResourcesTab patterns in grey only ─────────────

const Chip: React.FC<{ text: string; title?: string }> = ({ text, title }) => (
  <span
    title={title}
    className="inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-bold border bg-[rgba(0,0,0,0.02)] text-[rgba(0,0,0,0.6)] border-[rgba(0,0,0,0.1)]"
  >
    {text}
  </span>
);

const EmptyState: React.FC<{ icon: string; title: string; subtitle: string }> = ({ icon, title, subtitle }) => (
  <div className="flex flex-col items-center justify-center py-16 text-center">
    <div className="w-14 h-14 bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.06)] rounded-lg flex items-center justify-center mb-4 dark:bg-[rgba(255,255,255,0.04)] dark:border-[rgba(255,255,255,0.08)]">
      <Icon icon={icon} width={22} aria-hidden="true" className="text-[rgba(0,0,0,0.4)] dark:text-[rgba(255,255,255,0.4)]" />
    </div>
    <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] dark:text-[rgba(255,255,255,0.92)]">{title}</p>
    <p className="text-[12px] text-[rgba(0,0,0,0.5)] mt-1 max-w-[460px] dark:text-[rgba(255,255,255,0.5)]">{subtitle}</p>
  </div>
);

const StatusItem: React.FC<{ label: string; value: string; title?: string }> = ({ label, value, title }) => (
  <div className="flex flex-col gap-0.5">
    <span className="text-[10px] uppercase tracking-wide text-[rgba(0,0,0,0.4)] font-bold">{label}</span>
    <span className="text-[12px] text-[rgba(0,0,0,0.88)] font-semibold" title={title}>
      {value}
    </span>
  </div>
);

/** An engine timestamp shown in IST, with the raw UTC value kept in the tooltip. */
const IstTime: React.FC<{ value: string | null }> = ({ value }) => (
  <span title={value ?? undefined}>{formatInstant(value)}</span>
);

/** Glossary 4.3: an unset request or limit is known ("Not set"); an unusable value is a dash. */
const shown = (value: MetricValue, format: (v: MetricValue) => string): string =>
  value.kind === 'unset' ? 'Not set' : format(value);

/** Glossary 4.2/4.3: "1000m → 25m", "80m · no change", or "Not set → 25m". */
const ResourceCell: React.FC<{
  current: MetricValue;
  recommended: MetricValue | null;
  format: (v: MetricValue) => string;
}> = ({ current, recommended, format }) => {
  if (recommended === null || recommended.kind !== 'value') {
    return <span className="text-[12px] text-[rgba(0,0,0,0.88)] tabular-nums">{shown(current, format)}</span>;
  }
  const unchanged = current.kind === 'value' && current.value === recommended.value;
  return (
    <span className="text-[12px] text-[rgba(0,0,0,0.88)] tabular-nums">
      {unchanged ? `${shown(current, format)} · no change` : `${shown(current, format)} → ${format(recommended)}`}
    </span>
  );
};

// ─── Apply outcome (glossary 8.2, 8.3, 4.6, 6.3) ─────────────────────────────

/**
 * Only `APPLIED` says "Applied". `DEFERRED` is a wait, `INFEASIBLE` is a refusal by the cluster,
 * `DRY_RUN` is a test run and `RESTART_DETECTED` is a broken promise — none of them is a success,
 * and none of them is allowed to read like one.
 */
const OUTCOME_TEXT: Record<string, string> = {
  APPLIED: 'Applied. Confirmed in every pod.',
  // Each pod's line below shows what it ran before and runs now (review-2 SF7).
  PARTIALLY_APPLIED: 'Partly applied: it stopped at the first pod that failed.',
  DRY_RUN: 'Test run passed. Nothing was sent to the cluster.',
  DEFERRED: "Not applied: the node doesn't have room right now. Kubernetes may apply it later.",
  INFEASIBLE: 'Not applied: the node can never fit this size.',
  STUCK_IN_PROGRESS: 'Not confirmed: the pod was still resizing when XamOps stopped waiting.',
  RESTART_DETECTED: 'A container restarted during the resize. This was not a restart-free change.',
  NOT_READY: 'The new size is in place, but the pod stopped being ready.',
  REFUSED: 'Refused by a safety rule. Nothing was sent to the cluster.',
  EXPIRED: 'Expired before it was applied. Nothing was sent.',
  FAILED: 'Failed. Not applied.',
};

/** There is no Activity page on this branch, so "It will appear in Activity." is left out. */
const NO_RESULT = 'No result reported yet.';

const resourceWord = (insufficient: string | null | undefined): string | null =>
  insufficient === 'cpu' ? 'CPU' : insufficient === 'memory' ? 'memory' : null;

/**
 * A declined resize that XamOps put back and proved back (review-1 S7). A patch was sent, so these
 * never say "Nothing was changed"; they say what was done about it and what would let it through.
 */
const putBackText = (outcome: ApplyOutcomeWire): string | null => {
  const resource = resourceWord(outcome.insufficient);
  switch (outcome.terminal) {
    case 'DEFERRED':
      return `The node has no free ${resource ?? 'room'} for this increase, so XamOps put the pod back as it was. Free room on that node, for example by applying an over-provisioned workload there, or add a node.`;
    case 'INFEASIBLE':
      return `This size can never fit on the node${
        resource ? `: it has less ${resource} in total than the pod would ask for` : ''
      }, so XamOps put the pod back as it was. Choose a smaller size, or run the workload on a larger node.`;
    case 'STUCK_IN_PROGRESS':
      return 'The pod was still resizing when XamOps stopped waiting, so XamOps put it back as it was.';
    case 'FAILED':
      return "The new size wasn't confirmed, so XamOps put the pod back as it was.";
    case 'PARTIALLY_APPLIED':
      return 'Partly applied: it stopped at the first pod that failed, and put that pod back as it was. The pods below show what each one runs now.';
    default:
      return null;
  }
};

/** A declined resize XamOps tried to put back and could not prove back. */
const NOT_PUT_BACK =
  "XamOps tried to put the pod back as it was and couldn't confirm it, so the new size may still be applied later. Check the pod before trying again.";

/** What happened, said once, ahead of {@link NOT_PUT_BACK}. */
const NOT_PUT_BACK_HEAD: Record<string, string> = {
  DEFERRED: "Not applied: the node doesn't have room right now.",
  INFEASIBLE: 'Not applied: the node can never fit this size.',
  STUCK_IN_PROGRESS: 'Not confirmed: the pod was still resizing when XamOps stopped waiting.',
  FAILED: 'Not applied.',
  PARTIALLY_APPLIED: 'Partly applied: it stopped at the first pod that failed.',
};

const outcomeText = (outcome: ApplyOutcomeWire): string => {
  const terminal = outcome.terminal;
  if (terminal === null || terminal.trim() === '') return NO_RESULT;
  if (outcome.reverted === true) {
    const putBack = putBackText(outcome);
    if (putBack) return putBack;
  }
  if (outcome.reverted === false && NOT_PUT_BACK_HEAD[terminal]) {
    return `${NOT_PUT_BACK_HEAD[terminal]} ${NOT_PUT_BACK}`;
  }
  const text = terminal === 'APPLIED' ? appliedText(outcome) : OUTCOME_TEXT[terminal];
  if (!text) return `Other: ${terminal}`;
  return terminal === 'APPLIED' && outcome.restartFree === true ? `${text} No restart.` : text;
};

/** "N of M" when pods were left out, else null (review-2 SF3). */
const runningShare = (outcome: ApplyOutcomeWire): string | null => {
  const skipped = (outcome.skippedPods ?? []).length;
  if (skipped === 0) return null;
  const touched = (outcome.pods ?? []).length;
  return `${touched} of ${touched + skipped}`;
};

/** Never "every pod" when some were not running: they start at the workload's own size later. */
const appliedText = (outcome: ApplyOutcomeWire): string => {
  const share = runningShare(outcome);
  return share ? `Applied. Confirmed in every running pod (${share}).` : OUTCOME_TEXT.APPLIED;
};

const SKIPPED_POD_TEXT: Record<string, string> = {
  TERMINATING: 'being deleted',
  OLD_REVISION: 'from the previous version of the workload',
};

const skippedPodText = (pod: { phase: string | null; reason: string | null }): string => {
  if (pod.reason && SKIPPED_POD_TEXT[pod.reason]) return SKIPPED_POD_TEXT[pod.reason];
  return pod.phase ? `not running (${pod.phase})` : 'not running';
};

const OBSERVED_FIELDS: { key: keyof VerticalResourcesWire; label: string; format: (v: MetricValue) => string }[] = [
  { key: 'cpuRequestM', label: 'CPU request', format: formatMillis },
  { key: 'memRequestMi', label: 'memory request', format: formatMib },
  { key: 'cpuLimitM', label: 'CPU limit', format: formatMillis },
  { key: 'memLimitMi', label: 'memory limit', format: formatMib },
];

const metric = (value: number | null | undefined): MetricValue =>
  value === null || value === undefined ? { kind: 'unset' } : { kind: 'value', value };

/**
 * What one container ran before and runs now, read from the pod's status (review-2 SF7): only the
 * fields that moved, or "no change".
 */
const observedChange = (before: VerticalResourcesWire | null, after: VerticalResourcesWire | null): string => {
  if (!before || !after) return 'not reported';
  const moved = OBSERVED_FIELDS.filter((f) => (before[f.key] ?? null) !== (after[f.key] ?? null)).map(
    (f) => `${f.label} ${shown(metric(before[f.key]), f.format)} → ${shown(metric(after[f.key]), f.format)}`,
  );
  return moved.length > 0 ? moved.join(' · ') : 'no change';
};

const CONTAINER_STATE_TEXT: Record<string, string> = {
  APPLIED: 'Applied',
  DEFERRED: 'Waiting for room on the node',
  INFEASIBLE: "Can't fit on the node",
  STUCK_IN_PROGRESS: 'Still resizing',
  RESTARTED: 'Restarted',
  REFUSED: 'Refused',
  DRY_RUN: 'Test run',
  NOT_ATTEMPTED: 'Not attempted: the test pod failed first',
  FAILED: 'Failed',
};

const containerStateText = (state: string | null, reverted: boolean | null | undefined = null): string => {
  if (state === null || state.trim() === '') return DASH;
  // Once the pod is back as it was, it is no longer waiting or resizing.
  if (reverted === true && ['DEFERRED', 'INFEASIBLE', 'STUCK_IN_PROGRESS', 'FAILED'].includes(state)) {
    return 'Not applied: put back as it was';
  }
  return CONTAINER_STATE_TEXT[state] ?? `Other: ${state}`;
};

const POD_REVERT_TEXT: Record<string, string> = {
  REVERTED: ' · put back as it was',
  NOT_CONFIRMED: " · sent back, but it didn't confirm",
  FAILED: " · couldn't be put back",
};

const SUBMISSION_TEXT: Record<string, string> = {
  ACCEPTED: 'Submitted',
  DUPLICATE: 'Already submitted',
  REFUSED: 'Not submitted: nothing was sent to the cluster',
};

const submissionText = (state: string | null): string =>
  state === null || state.trim() === '' ? DASH : (SUBMISSION_TEXT[state] ?? `Other: ${state}`);

/**
 * The restart-free proof for one pod, in the numbers it was proved from (glossary 4.6), and what
 * each container ran before and runs now, so a partial outcome shows which pods changed.
 */
const PodProof: React.FC<{ pod: ApplyPodProofWire }> = ({ pod }) => (
  <li className="text-[11px] text-[rgba(0,0,0,0.5)]">
    <span data-testid="pod-proof-summary">
      {pod.podName}: {pod.samePod === true ? 'same pod' : 'pod replaced'}
      {(pod.containers ?? []).map((c) => (
        <span key={c.name}>
          {c.containerIdBefore !== null && c.containerIdAfter !== null
            ? c.containerIdBefore === c.containerIdAfter
              ? ' · same container'
              : ' · container replaced'
            : ''}
          {c.restartCountBefore !== null && c.restartCountAfter !== null && c.restartCountBefore !== c.restartCountAfter
            ? ` · restarted (${c.restartCountBefore} → ${c.restartCountAfter})`
            : ` · restarts ${formatCount(c.restartCountBefore)} → ${formatCount(c.restartCountAfter)}`}
        </span>
      ))}
      {pod.ready === true ? ' · ready' : ' · not ready'}
      {pod.revert?.state ? (
        <span title={pod.revert.detail ?? undefined}>{POD_REVERT_TEXT[pod.revert.state] ?? ` · ${pod.revert.state}`}</span>
      ) : null}
    </span>
    {(pod.containers ?? []).length > 0 && (
      <ul className="pl-3">
        {(pod.containers ?? []).map((c) => (
          <li key={c.name}>
            {c.name}: {observedChange(c.observedBefore, c.observedAfter)}
          </li>
        ))}
      </ul>
    )}
  </li>
);

const ApplyOutcome: React.FC<{ outcome: ApplyOutcomeWire }> = ({ outcome }) => {
  const proven = outcome.terminal === 'APPLIED' && outcome.restartFree === true;
  const share = runningShare(outcome);
  const skipped = outcome.skippedPods ?? [];
  return (
    <div className="space-y-1">
      <p className="text-[12px] font-semibold text-[rgba(0,0,0,0.88)]" title={outcome.detail ?? undefined}>
        {outcomeText(outcome)}
      </p>
      <p className="text-[11px] text-[rgba(0,0,0,0.5)]">
        {proven
          ? `In place, no restart · proven in every ${share ? `running pod (${share})` : 'pod'}`
          : 'In place · no restart-free proof'}
      </p>
      {proven && (
        <p className="text-[11px] text-[rgba(0,0,0,0.5)]">
          No restart: same pods, same containers, restart count unchanged, all still ready.
        </p>
      )}
      {(outcome.containers ?? []).length > 0 && (
        <ul className="space-y-0.5">
          {(outcome.containers ?? []).map((c) => (
            <li key={c.name} className="text-[11px] text-[rgba(0,0,0,0.5)]" title={c.detail ?? undefined}>
              {c.name}: {containerStateText(c.state, outcome.reverted)}
              {c.restartExpected === true ? ' · restarts by its own setting' : ''}
            </li>
          ))}
        </ul>
      )}
      {(outcome.pods ?? []).length > 0 && (
        <ul className="space-y-0.5">
          {(outcome.pods ?? []).map((pod) => (
            <PodProof key={pod.podName} pod={pod} />
          ))}
        </ul>
      )}
      {skipped.length > 0 && (
        <div className="text-[11px] text-[rgba(0,0,0,0.5)]">
          <p>Not resized. These pods run at the workload&apos;s own size:</p>
          <ul className="space-y-0.5">
            {skipped.map((pod) => (
              <li key={pod.podName} title={pod.reason ? `Code: ${pod.reason}` : undefined}>
                {pod.podName}: {skippedPodText(pod)}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
};

/** Glossary 7.1: a gate refusal, as the reason shown at the control. */
const GATE_REFUSAL_TEXT: Record<string, string> = {
  DEMO: "Demo workspace. Changes aren't saved.",
  READ_ONLY_CONNECTION:
    'View only: this AWS account is connected read-only. Applying changes needs read-write access.',
  CONTROLLER_NOT_READY: 'Applying is turned off in this XamOps environment. You can still review every suggestion.',
  CLUSTER_ACCESS_NOT_CONFIGURED: "XamOps can't sign in to this cluster: no EKS access entry maps this account's XamOps role.",
  CLUSTER_UNREACHABLE:
    "XamOps can't reach this cluster's API server. Access is set up, but the endpoint didn't answer (for example, a private endpoint).",
  GITOPS_MANAGED:
    "Saving the change to the workload itself isn't available. Use in-place resize, which doesn't restart pods.",
  APPLY_IN_PROGRESS:
    'Not applied: another change to this workload is still running. Wait for it to finish, then refresh.',
  UNAUTHENTICATED: 'Your session has expired. Sign in again.',
  NO_ACCOUNT_ACCESS: "You don't have access to this AWS account.",
  INTERNAL: 'Something went wrong on our side. Nothing was changed. Try again in a minute.',
};

/**
 * Glossary 7.5: an apply refused with `K8S_INVALID_POLICY` names its rule at the head of the
 * message (the engine holds and the cooldown) or in the message text (the body checks). The plain
 * sentence is shown; the server's own message goes in the tooltip.
 */
const refusalText = (code: CodeView, message: string, hpaName: string | null, detail: string | null): string => {
  const raw = code.kind === 'known' ? code.code : code.raw.replace(/^K8S_/, '');
  if (raw === 'INVALID_POLICY') {
    const m = message ?? '';
    const hpa = hpaName ? `the HPA ${hpaName}` : 'an HPA';
    if (m.startsWith('PROTECTED_NAMESPACE:')) return 'XamOps never resizes workloads in this namespace.';
    if (m.startsWith('POLICY_OFF:')) return 'Not applied: rightsizing is off for this workload.';
    if (m.startsWith('SINGLE_REPLICA:'))
      return 'Not applied: it runs a single replica, so a problem during a resize would take it down.';
    if (m.startsWith('ROLLOUT_IN_PROGRESS:')) return 'Not applied: a rollout is in progress. Try again when it ends.';
    if (m.startsWith('LOCKED:')) return 'Not applied: locked with xamops.io/lock: active.';
    if (m.startsWith('HPA_CPU_MANAGED:'))
      return `Not applied: ${hpa} scales this workload on CPU, so CPU is left alone. Memory can still be applied.`;
    if (m.startsWith('HPA_MEMORY_MANAGED:'))
      return `Not applied: ${hpa} scales this workload on memory, so memory is left alone. CPU can still be applied.`;
    if (m.startsWith('COOLDOWN:'))
      return `Not applied: this workload was changed less than ${COOLDOWN_MINUTES} minutes ago. Nothing was sent.`;
    if (/floor/i.test(m)) return 'Not applied: below the minimum XamOps allows (25m CPU, 250Mi memory).';
    if (/QoS shape/i.test(m)) return "Not applied: this would change the pod's Kubernetes QoS class, which a running pod can't change.";
    if (/would exceed its limit/.test(m)) return 'Not applied: the request would be above its limit.';
    if (/nothing to apply/.test(m)) return "Not applied: this workload is no longer in the cluster, or XamOps can't resize it.";
    const missing = /Container (\S+) is not part of/.exec(m);
    if (missing) return `Not applied: container ${missing[1]} isn't in this workload any more. Refresh and try again.`;
    if (/dryRun/.test(m)) return 'Not applied: choose a test run or a real resize.';
    return "Not applied: XamOps couldn't read the change. Refresh and try again.";
  }
  // Glossary 7.2: a permission refusal is worded by the rule that refused. A server that sends no
  // detail gets its own message shown instead, which is what the glossary says to do until then.
  if (raw === 'NO_USER_PERMISSION' && !detail && message) return message;
  const caller = writeGateText(raw, detail);
  if (caller) return caller.nextStep ? `${caller.reason} ${caller.nextStep}` : caller.reason;
  return GATE_REFUSAL_TEXT[raw] ?? (message || codeText(code));
};

const ApplyReply: React.FC<{ reply: ApplyResult; hpaName: string | null }> = ({ reply, hpaName }) => {
  if (reply.kind === 'writes-not-deployed') {
    return (
      <p className="text-[12px] text-[rgba(0,0,0,0.6)]">
        Applying isn&apos;t available in this environment yet. The suggestion is unchanged.
      </p>
    );
  }
  if (reply.kind === 'cluster-not-registered') {
    return (
      <p className="text-[12px] text-[rgba(0,0,0,0.6)]" title={reply.message}>
        This cluster isn&apos;t connected to XamOps yet. Install the XamOps agent from its setup guide, then reload.
      </p>
    );
  }
  if (reply.kind === 'submitted') {
    return (
      <div className="space-y-1">
        <p className="text-[12px] text-[rgba(0,0,0,0.6)]">
          {submissionText(reply.state)}
          {reply.dryRun === true ? ' · Test run' : ''} &middot;{' '}
          <span title={reply.correlationId ?? undefined}>
            Reference {reply.correlationId ? reply.correlationId.slice(0, 8) : DASH}
          </span>
        </p>
        {reply.result === null ? (
          <p className="text-[12px] text-[rgba(0,0,0,0.6)]">{NO_RESULT}</p>
        ) : (
          <ApplyOutcome outcome={reply.result} />
        )}
      </div>
    );
  }
  if (reply.kind === 'refused') {
    return (
      <p role="alert" className="text-[12px] text-[rgba(0,0,0,0.6)]" title={reply.message}>
        {refusalText(reply.code, reply.message, hpaName, reply.detail)}
      </p>
    );
  }
  return <p className="text-[12px] text-[rgba(0,0,0,0.6)]">{reply.message}</p>;
};

// ─── Empty-state copy, keyed by the gate the backend actually sent (glossary 11.2) ──

interface GateCopy {
  icon: string;
  title: string;
  subtitle: string;
}

const GATE_COPY: Record<string, GateCopy> = {
  CONTROLLER_NOT_READY: {
    icon: 'mdi:toggle-switch-off-outline',
    title: 'Workload rightsizing is off in this environment',
    subtitle: "XamOps hasn't analysed this cluster's workloads, and nothing was read from the cluster.",
  },
  CLUSTER_ACCESS_NOT_CONFIGURED: {
    icon: 'mdi:key',
    title: "XamOps can't sign in to this cluster",
    subtitle: "Create an EKS access entry for this account's XamOps role. The cluster setup guide shows how.",
  },
  CLUSTER_UNREACHABLE: {
    icon: 'mdi:power-plug-outline',
    title: "XamOps can't reach this cluster's API server",
    subtitle: "Access is set up, but the endpoint didn't answer. That's expected for a private endpoint.",
  },
  NO_METRICS_SOURCE: {
    icon: 'mdi:chart-areaspline',
    title: 'No usage data for this cluster',
    subtitle:
      "XamOps reads CPU and memory usage from Prometheus in the cluster and couldn't reach it. Nothing is estimated without it.",
  },
  READ_ONLY_CONNECTION: {
    icon: 'mdi:lock-outline',
    title: 'View only: this account is connected read-only',
    subtitle: 'You can review every suggestion. Applying one needs read-write access.',
  },
  NO_USER_PERMISSION: {
    icon: 'mdi:account-lock-outline',
    title: "View only: you can't change automation",
    subtitle: 'Ask your XamOps administrator to allow automation changes for your user.',
  },
  DEMO: {
    icon: 'mdi:flask-outline',
    title: 'Demo workspace',
    subtitle: "Changes aren't saved.",
  },
  INSUFFICIENT_HISTORY: {
    icon: 'mdi:timer-sand',
    title: 'Not enough usage data yet',
    subtitle:
      'XamOps needs at least half of the last measurement window of usage. This clears on its own as the workloads keep running.',
  },
};

const NO_WORKLOADS: GateCopy = {
  icon: 'mdi:cube-outline',
  title: 'No workloads to analyse',
  subtitle: 'XamOps found no Deployments or StatefulSets outside the system namespaces.',
};

/**
 * The caller's write gates say who may apply, not why the list is empty, so an empty cluster with
 * only a write gate still reads "No workloads to analyse" (review-2 item 9).
 */
const CALLER_WRITE_GATES = new Set(['DEMO', 'NO_USER_PERMISSION', 'READ_ONLY_CONNECTION']);

const gateEmptyState = (gateReasons: CodeView[]): GateCopy => {
  for (const reason of gateReasons) {
    if (reason.kind === 'known' && !CALLER_WRITE_GATES.has(reason.code) && GATE_COPY[reason.code]) {
      return GATE_COPY[reason.code];
    }
  }
  const unknown = gateReasons.find((r) => r.kind === 'unknown');
  if (unknown && unknown.kind === 'unknown') {
    return {
      icon: 'mdi:information-outline',
      title: 'Nothing analysed',
      subtitle: `Other: ${unknown.raw}`,
    };
  }
  return NO_WORKLOADS;
};

// ─── Container table (glossary 4.3) ──────────────────────────────────────────

/** Review-2 SF2: said beside "now", because "now" is then not what every pod runs. */
const PODS_DIFFER_TITLE =
  "This container's pods don't all run the same size right now, for example after a partial change or a replaced pod. \"Now\" shows the workload's own size, which new pods start at.";

const ContainerTable: React.FC<{ workload: WorkloadView }> = ({ workload }) => {
  const { containers } = workload;
  if (containers.length === 0) return null;
  const vpaHidden = workload.findings.some((f) => f.kind === 'known' && f.code === 'VPA_NOT_READABLE');
  return (
    <div className="border-t border-[rgba(0,0,0,0.06)] overflow-x-auto">
      <table className="w-full text-left">
        <thead>
          <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
            {[
              'Container',
              'CPU request (now → suggested)',
              'Memory request (now → suggested)',
              'CPU limit (now → suggested)',
              'Memory limit (now → suggested)',
              'Kubernetes VPA suggests',
              'Why',
            ].map((h) => (
              <th
                key={h}
                title={h.startsWith('CPU') ? 'm = millicores. 1000m = 1 vCPU.' : undefined}
                className="px-4 py-2 text-[10px] uppercase tracking-wide font-bold text-[rgba(0,0,0,0.4)] whitespace-nowrap"
              >
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {containers.map((c) => (
            <tr key={c.name} className="border-b border-[rgba(0,0,0,0.04)] last:border-0 align-top">
              <td className="px-4 py-3 text-[12px] font-semibold text-[rgba(0,0,0,0.88)] whitespace-nowrap">
                {c.name}
                {c.podsDisagree && (
                  <span className="ml-2">
                    <Chip text="Pods differ" title={PODS_DIFFER_TITLE} />
                  </span>
                )}
              </td>
              <td className="px-4 py-3 whitespace-nowrap">
                <ResourceCell current={c.current.cpuRequestM} recommended={c.recommended?.cpuRequestM ?? null} format={formatMillis} />
              </td>
              <td className="px-4 py-3 whitespace-nowrap">
                <ResourceCell current={c.current.memRequestMi} recommended={c.recommended?.memRequestMi ?? null} format={formatMib} />
              </td>
              <td className="px-4 py-3 whitespace-nowrap">
                <ResourceCell current={c.current.cpuLimitM} recommended={c.recommended?.cpuLimitM ?? null} format={formatMillis} />
              </td>
              <td className="px-4 py-3 whitespace-nowrap">
                <ResourceCell current={c.current.memLimitMi} recommended={c.recommended?.memLimitMi ?? null} format={formatMib} />
              </td>
              <td
                className="px-4 py-3 text-[12px] text-[rgba(0,0,0,0.6)] whitespace-nowrap tabular-nums"
                title={
                  c.upstreamVpa
                    ? undefined
                    : vpaHidden
                      ? "XamOps isn't allowed to read VerticalPodAutoscaler objects in this cluster, so the Kubernetes VPA comparison is blank."
                      : 'No Kubernetes VPA targets this workload'
                }
              >
                {c.upstreamVpa
                  ? `${formatMillis(c.upstreamVpa.cpuRequestM)} / ${formatMib(c.upstreamVpa.memRequestMi)}`
                  : DASH}
              </td>
              <td className="px-4 py-3">
                <div className="flex flex-wrap gap-1">
                  {c.reasons.length === 0 ? (
                    <span className="text-[12px] text-[rgba(0,0,0,0.4)]">{DASH}</span>
                  ) : (
                    c.reasons.map((r) => (
                      <Chip key={codeText(r)} text={codeText(r)} title={reasonTitle(r, c, workload)} />
                    ))
                  )}
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

/** "Why this suggestion": every reason as its sentence, once. */
const WhyThisSuggestion: React.FC<{ workload: WorkloadView }> = ({ workload }) => {
  const seen = new Set<string>();
  const sentences: string[] = [];
  for (const c of workload.containers) {
    for (const r of c.reasons) {
      const sentence = reasonSentence(r, c, workload);
      if (!seen.has(sentence)) {
        seen.add(sentence);
        sentences.push(sentence);
      }
    }
  }
  if (sentences.length === 0) return null;
  return (
    <div className="px-6 py-3 border-t border-[rgba(0,0,0,0.06)]">
      <p className="text-[11px] font-semibold text-[rgba(0,0,0,0.7)]">Why this suggestion</p>
      <ul className="mt-1 list-disc pl-5 space-y-0.5 text-[12px] text-[rgba(0,0,0,0.6)]">
        {sentences.map((s) => (
          <li key={s}>{s}</li>
        ))}
      </ul>
    </div>
  );
};

// ─── Apply confirmation (glossary 4.5) ───────────────────────────────────────

const CHANGE_FIELDS: {
  key: keyof Omit<ApplyContainerRequest, 'name'>;
  label: string;
  current: (c: ContainerView) => MetricValue;
  format: (v: MetricValue) => string;
}[] = [
  { key: 'cpuRequestM', label: 'CPU request', current: (c) => c.current.cpuRequestM, format: formatMillis },
  { key: 'memRequestMi', label: 'Memory request', current: (c) => c.current.memRequestMi, format: formatMib },
  { key: 'cpuLimitM', label: 'CPU limit', current: (c) => c.current.cpuLimitM, format: formatMillis },
  { key: 'memLimitMi', label: 'Memory limit', current: (c) => c.current.memLimitMi, format: formatMib },
];

/**
 * The last step before a real resize: what changes, per container, and what that does and does
 * not mean. Greyscale and inline, in the card it confirms.
 */
const ApplyConfirmation: React.FC<{
  workload: WorkloadView;
  changes: ApplyContainerRequest[];
  onConfirm: () => void;
  onCancel: () => void;
}> = ({ workload, changes, onConfirm, onCancel }) => {
  const titleId = `apply-confirm-${workload.id}`;
  return (
    <div
      role="alertdialog"
      aria-labelledby={titleId}
      className="px-6 py-4 border-t border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.02)] space-y-3"
    >
      <p id={titleId} className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">
        Resize {workload.ref.name} now?
      </p>
      <ul className="space-y-1">
        {changes.map((change) => {
          const container = workload.containers.find((c) => c.name === change.name);
          const parts = CHANGE_FIELDS.filter((f) => change[f.key] !== undefined).map(
            (f) =>
              `${f.label} ${container ? shown(f.current(container), f.format) : DASH} → ${f.format({
                kind: 'value',
                value: change[f.key] as number,
              })}`,
          );
          return (
            <li key={change.name} className="text-[12px] text-[rgba(0,0,0,0.6)] tabular-nums">
              <span className="font-semibold text-[rgba(0,0,0,0.88)]">{change.name}</span>: {parts.join(' · ')}
            </li>
          );
        })}
      </ul>
      <ul className="list-disc pl-5 space-y-0.5 text-[11px] text-[rgba(0,0,0,0.6)]">
        <li>
          XamOps resizes the running pods in place: one pod first as a check, then the rest two at a time. It
          stops at the first pod that fails. Pods aren&apos;t restarted, unless a container&apos;s own resize
          setting says so.
        </li>
        <li>
          The {workload.ref.kind} itself isn&apos;t changed, so pods created later (a rollout, a rescheduled pod)
          start at the old size.
        </li>
        <li>XamOps won&apos;t change this workload again for {COOLDOWN_MINUTES} minutes.</li>
      </ul>
      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={onConfirm}
          className="px-4 py-2 text-xs font-bold rounded-xl border border-[rgba(0,0,0,0.12)] bg-[rgba(0,0,0,0.88)] text-white hover:bg-black transition-all"
        >
          Resize pods
        </button>
        <button
          type="button"
          onClick={onCancel}
          className="px-4 py-2 text-xs font-bold rounded-xl border border-[rgba(0,0,0,0.12)] bg-white text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.03)] transition-all"
        >
          Cancel
        </button>
      </div>
    </div>
  );
};

// ─── Status block words (glossary 4.1) ───────────────────────────────────────

const CLUSTER_ACCESS_TEXT: Record<string, string> = {
  CONFIGURED: 'Connected',
  NOT_CONFIGURED: 'Not set up (no EKS access entry for XamOps)',
  UNREACHABLE: "Can't reach the API server",
};

const PATH_TEXT: Record<string, string> = {
  DIRECT: 'Directly to the cluster API',
  AGENT: 'Only the XamOps agent is connected. Applying needs direct cluster access today.',
  NONE: 'No way to send changes',
};

/**
 * Glossary 4.1 "Changes", told the caller's write gates (review-2 SF6): "Can be applied" only when
 * the environment allows applying, the server checked this caller, and no write gate applies.
 */
const changesStatus = (
  view: { engine: EngineView; gateReasons: CodeView[]; gateDetails: Record<string, string>; writeGatesChecked: boolean },
  callerRefusal: { code: string; detail: string | null } | null,
): { value: string; title: string } => {
  if (!view.engine.ready) {
    return { value: 'Suggestions only', title: 'Applying is turned off in this XamOps environment.' };
  }
  const gate =
    (callerRefusal && writeGateText(callerRefusal.code, callerRefusal.detail)) ??
    view.gateReasons
      .filter((g): g is Extract<CodeView, { kind: 'known' }> => g.kind === 'known' && CALLER_WRITE_GATES.has(g.code))
      .map((g) => writeGateText(g.code, view.gateDetails[g.code]))
      .find((text) => text !== null) ??
    null;
  if (gate) return { value: 'View only', title: gate.nextStep ? `${gate.reason} ${gate.nextStep}` : gate.reason };
  if (!view.writeGatesChecked) return { value: 'Not checked', title: WRITE_GATES_NOT_CHECKED };
  return { value: 'Can be applied', title: `Code: ready ${view.engine.ready}` };
};

const clusterAccessText = (engine: EngineView): string =>
  engine.clusterAccess === null ? 'Not checked' : (CLUSTER_ACCESS_TEXT[engine.clusterAccess] ?? `Other: ${engine.clusterAccess}`);

const pathText = (engine: EngineView): string =>
  engine.executionPath === null ? DASH : (PATH_TEXT[engine.executionPath] ?? `Other: ${engine.executionPath}`);

const lastAnalysedText = (engine: EngineView): string => {
  if (engine.lastCycleError) return `Analysis failed: ${engine.lastCycleError}`;
  if (engine.lastCycleAt === null) return 'Not analysed yet';
  return `${formatInstant(engine.lastCycleAt)} · ${formatCount(engine.lastCycleEvaluated)} workloads`;
};

// ─── How this was calculated (glossary 4.4) ──────────────────────────────────

const QOS_TEXT: Record<string, string> = {
  Guaranteed: 'Guaranteed (requests equal limits)',
  Burstable: 'Burstable (requests below limits, or only some set)',
  BestEffort: 'BestEffort (no requests or limits)',
};

/** The engineering fields, behind a disclosure and in sentences. Codes only in the title. */
const HowCalculated: React.FC<{ workload: WorkloadView }> = ({ workload }) => {
  const p = workload.provenance;
  const pct = p?.percentile ?? workload.policy?.percentile ?? null;
  const headroom = workload.policy?.headroomPct ?? null;
  const window = windowWords(workloadWindowHours(workload));
  const plus = headroom === null ? '' : `, plus ${headroom}% headroom`;
  const censored = workload.containers.some((c) =>
    c.reasons.some((r) => r.kind === 'known' && r.code === 'DEMAND_CENSORED_BY_LIMIT'),
  );
  return (
    <details
      className="px-6 py-3 border-t border-[rgba(0,0,0,0.06)] text-[11px] text-[rgba(0,0,0,0.6)]"
      title={`Code: mode ${formatText(workload.policy?.mode)} · policySource ${formatText(p?.policySource)} · window ${formatText(workload.policy?.window)}`}
    >
      <summary className="cursor-pointer font-semibold text-[rgba(0,0,0,0.7)]">How this was calculated</summary>
      <ul className="mt-2 space-y-1">
        <li>
          CPU: the {pct === null ? DASH : `${pct}th`} percentile of usage over the last {window}, measured as
          5-minute rates, from the busiest pod{plus}.
        </li>
        <li>
          Memory: the highest usage over the last {window}, from the busiest pod{plus}.
        </li>
        {censored && <li>CPU was held at its limit, so XamOps sized from the limit instead of the measurement.</li>}
        {p &&
          (p.sampleCoverage === null ? (
            <li>No usage was measured for this window.</li>
          ) : (
            <li>
              Data from <IstTime value={p.windowStart} /> to <IstTime value={p.windowEnd} /> (IST), covering{' '}
              {formatCoverage(p.sampleCoverage)} of the window.
            </li>
          ))}
        <li>The first 2 minutes after a container starts are ignored.</li>
        {/* Review-2 item 11: name a source only when the payload names it. */}
        <li>
          {p?.usageSource === 'prometheus-proxy'
            ? 'Usage from Prometheus in your cluster, read through the Kubernetes API.'
            : p?.usageSource
              ? `Usage from ${p.usageSource}.`
              : `Usage source: ${DASH}`}
        </li>
        <li>
          {/* There is no way to edit a workload's settings yet, so a stored row is one an apply
              recorded: the settings in force then, not a choice someone saved. */}
          {p?.policySource === 'WORKLOAD_ROW'
            ? 'Settings recorded for this workload when a change was applied: the defaults in force then'
            : p?.policySource === 'DEFAULTS'
              ? 'XamOps defaults (not saved for this workload)'
              : `Settings: ${formatText(p?.policySource)}`}
        </li>
        {workload.containers.length > 0 && (
          <li>
            Kubernetes QoS class:{' '}
            {workload.containers
              .map((c) => `${c.name} ${c.qosClass ? (QOS_TEXT[c.qosClass] ?? c.qosClass) : DASH}`)
              .join(', ')}
          </li>
        )}
        <li>Never below 25m CPU or 250Mi memory.</li>
        <li>
          XamOps raises CPU by at least 15% and 25m, lowers it by at least 30% and 100m, raises memory by at least
          15% and 32Mi, and lowers it by at least 30% and 128Mi. For small containers, the fixed amounts shrink to
          what&apos;s reachable.
        </li>
        <li>
          Skips workloads with one replica, a rollout in progress, the xamops.io/lock: active annotation, or an
          HPA that scaled in the last 5 minutes.
        </li>
      </ul>
    </details>
  );
};

// ─── Mode control (endpoint 2) ───────────────────────────────────────────────

const MODE_OPTIONS: { value: WorkloadModeValue; label: string }[] = [
  { value: 'OFF', label: 'Off' },
  { value: 'RECOMMEND', label: 'Recommend' },
  { value: 'AUTO', label: 'Automatic' },
];

/** The plain-language consequence of choosing Automatic. No emoji; greyscale only. */
const AUTOMATIC_CONSEQUENCE =
  'XamOps will apply eligible changes to this workload on its own, at most one per cluster every 15 minutes, in place with no restart. Turn off any time.';

/** The outcome line for a mode change, in words. Only a saved change reads as done. */
const modeResultText = (
  reply: ModeResult,
  refusalText: (code: CodeView, message: string, hpaName: string | null, detail: string | null) => string,
): { text: string; alert: boolean } => {
  switch (reply.kind) {
    case 'saved':
      return { text: `Mode set to ${modeText(reply.mode)}.`, alert: false };
    case 'writes-not-deployed':
      return { text: "Changing the mode isn't available in this environment yet.", alert: false };
    case 'cluster-not-registered':
      return { text: "This cluster isn't connected to XamOps yet.", alert: false };
    case 'refused':
      return { text: refusalText(reply.code, reply.message, null, reply.detail), alert: true };
    default:
      return { text: reply.message, alert: true };
  }
};

/**
 * The per-workload mode switch: Off / Recommend / Automatic, gated and greyed exactly like Apply
 * through {@link modeChangeGate}. Choosing Automatic asks for confirmation first, because it starts
 * unattended changes; Off and Recommend are set immediately.
 */
const ModeControl: React.FC<{
  workload: WorkloadView;
  gate: ManualApplyGate;
  changing: boolean;
  reply: ModeResult | undefined;
  onSetMode: (mode: WorkloadModeValue) => void;
}> = ({ workload, gate, changing, reply, onSetMode }) => {
  const [confirmAuto, setConfirmAuto] = useState(false);
  const current = (workload.policy?.mode ?? null) as WorkloadModeValue | null;
  const disabled = !gate.enabled || changing;

  // If the gate closes mid-flight, drop any pending Automatic confirmation.
  useEffect(() => {
    if (!gate.enabled) setConfirmAuto(false);
  }, [gate.enabled]);

  const choose = (mode: WorkloadModeValue) => {
    if (mode === current) return;
    if (mode === 'AUTO') {
      setConfirmAuto(true);
    } else {
      onSetMode(mode);
    }
  };

  const outcome = reply ? modeResultText(reply, refusalText) : null;

  return (
    <div className="px-6 py-3 border-t border-[rgba(0,0,0,0.06)]">
      <div className="flex flex-wrap items-center gap-3">
        <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.7)]">Automation mode</span>
        <div
          role="group"
          aria-label={`Automation mode for ${workload.ref.name}`}
          className="inline-flex rounded-xl border border-[rgba(0,0,0,0.12)] overflow-hidden"
        >
          {MODE_OPTIONS.map((option) => {
            const active = current === option.value;
            return (
              <button
                key={option.value}
                type="button"
                onClick={() => choose(option.value)}
                disabled={disabled || active}
                aria-pressed={active}
                title={disabled && !active ? (gate.reason ?? undefined) : undefined}
                className={
                  'px-3 py-1.5 text-[11px] font-bold border-r border-[rgba(0,0,0,0.12)] last:border-r-0 transition-all ' +
                  (active
                    ? 'bg-[rgba(0,0,0,0.88)] text-white'
                    : 'bg-white text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.03)] disabled:text-[rgba(0,0,0,0.3)] disabled:cursor-not-allowed disabled:hover:bg-white')
                }
              >
                {option.label}
              </button>
            );
          })}
        </div>
        {changing && <span className="text-[11px] text-[rgba(0,0,0,0.5)]">Saving…</span>}
      </div>

      {confirmAuto && gate.enabled && !changing && (
        <div
          role="alertdialog"
          aria-label={`Turn ${workload.ref.name} Automatic?`}
          className="mt-2 p-3 rounded-xl border border-[rgba(0,0,0,0.12)] bg-[rgba(0,0,0,0.02)] space-y-2 max-w-[520px]"
        >
          <p className="text-[12px] font-semibold text-[rgba(0,0,0,0.88)]">
            Turn {workload.ref.name} Automatic?
          </p>
          <p className="text-[11px] text-[rgba(0,0,0,0.6)]">{AUTOMATIC_CONSEQUENCE}</p>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => {
                setConfirmAuto(false);
                onSetMode('AUTO');
              }}
              className="px-4 py-1.5 text-[11px] font-bold rounded-xl border border-[rgba(0,0,0,0.12)] bg-[rgba(0,0,0,0.88)] text-white hover:bg-black transition-all"
            >
              Turn on Automatic
            </button>
            <button
              type="button"
              onClick={() => setConfirmAuto(false)}
              className="px-4 py-1.5 text-[11px] font-bold rounded-xl border border-[rgba(0,0,0,0.12)] bg-white text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.03)] transition-all"
            >
              Cancel
            </button>
          </div>
        </div>
      )}

      {outcome && (
        <p
          {...(outcome.alert ? { role: 'alert' } : {})}
          className="mt-1 text-[11px] text-[rgba(0,0,0,0.6)]"
        >
          {outcome.text}
        </p>
      )}
    </div>
  );
};

// ─── Workload card (glossary 4.2) ────────────────────────────────────────────

const WorkloadCard: React.FC<{
  workload: WorkloadView;
  gate: ManualApplyGate;
  modeGate: ManualApplyGate;
  modeChanging: boolean;
  modeReply: ModeResult | undefined;
  onSetMode: (mode: WorkloadModeValue) => void;
  dryRun: boolean;
  applying: boolean;
  reply: ApplyResult | undefined;
  onApply: () => void;
}> = ({ workload, gate, modeGate, modeChanging, modeReply, onSetMode, dryRun, applying, reply, onApply }) => {
  const [confirming, setConfirming] = useState(false);
  // A confirmation describes a real resize; it closes if the gate closes or this becomes a test run.
  useEffect(() => {
    if (dryRun || !gate.enabled) setConfirming(false);
  }, [dryRun, gate.enabled]);
  const disabled = !gate.enabled || applying;
  const blockedReason = gate.reason;

  const onClickApply = () => {
    if (dryRun) {
      onApply();
    } else {
      setConfirming(true);
    }
  };

  const status = workloadStatus(workload);

  return (
    <section className="bg-white rounded-lg border border-[rgba(0,0,0,0.12)] overflow-hidden">
      <div className="flex flex-wrap items-start justify-between gap-4 px-6 py-4">
        <div className="min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] dark:text-[rgba(255,255,255,0.92)]">{workload.ref.name}</span>
            <span
              aria-label={`Status: ${status.status}`}
              title={workload.skipReason ? `Code: ${workload.skipReason.kind === 'known' ? workload.skipReason.code : workload.skipReason.raw}` : undefined}
            >
              <StatusChip
                tone={workloadStatusTone(status.status).tone}
                icon={workloadStatusTone(status.status).icon}
                label={status.status}
                weight="semibold"
              />
            </span>
            {workload.findings.map((f) => (
              <Chip
                key={codeText(f)}
                text={codeText(f)}
                title={`${findingSentence(f, workload)} Code: ${f.kind === 'known' ? f.code : f.raw}`}
              />
            ))}
          </div>
          <p className="mt-0.5 text-[11px] text-[rgba(0,0,0,0.5)]">
            {workload.ref.kind} &middot; Namespace {workload.ref.namespace}
          </p>
          {status.detail && <p className="mt-2 text-[12px] text-[rgba(0,0,0,0.6)]">{status.detail}</p>}
          <div className="flex items-center gap-3 flex-wrap mt-2 text-[11px] text-[rgba(0,0,0,0.5)]">
            <span>Mode {modeText(workload.policy?.mode)}</span>
            <span>Automatic changes {workload.autoApplyEligible ? 'On' : 'Off'}</span>
            <span>Replicas {formatCount(workload.replicas)}</span>
            <span>HPA {workload.hpaPresent ? formatText(workload.hpaName) : 'None'}</span>
          </div>
        </div>

        <div className="flex flex-col items-end gap-1">
          <button
            type="button"
            onClick={onClickApply}
            disabled={disabled || confirming}
            title={blockedReason ?? (dryRun ? 'Run a test: nothing is sent to the cluster' : 'Review and resize the running pods')}
            aria-label={`Apply ${workload.ref.name}`}
            className="px-4 py-2 text-xs font-bold rounded-xl border border-[rgba(0,0,0,0.12)] bg-[rgba(0,0,0,0.88)] text-white hover:bg-black transition-all disabled:bg-white disabled:text-[rgba(0,0,0,0.35)] disabled:cursor-not-allowed"
          >
            {applying ? 'Submitting…' : 'Apply'}
          </button>
          {blockedReason && (
            <span className="text-[11px] text-[rgba(0,0,0,0.5)] text-right max-w-[220px]">{blockedReason}</span>
          )}
          {blockedReason && gate.nextStep && (
            <span className="text-[11px] text-[rgba(0,0,0,0.5)] text-right max-w-[220px]">{gate.nextStep}</span>
          )}
        </div>
      </div>

      {confirming && !dryRun && gate.enabled && !applying && (
        <ApplyConfirmation
          workload={workload}
          changes={gate.changes}
          onConfirm={() => {
            setConfirming(false);
            onApply();
          }}
          onCancel={() => setConfirming(false)}
        />
      )}

      <ModeControl
        workload={workload}
        gate={modeGate}
        changing={modeChanging}
        reply={modeReply}
        onSetMode={onSetMode}
      />

      <ContainerTable workload={workload} />

      <WhyThisSuggestion workload={workload} />

      <HowCalculated workload={workload} />

      {reply && (
        <div className="px-6 py-3 border-t border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.02)]">
          <ApplyReply reply={reply} hpaName={workload.hpaName} />
        </div>
      )}
    </section>
  );
};

// ─── Tab ─────────────────────────────────────────────────────────────────────

const EksAutomationTab: React.FC<EksAutomationTabProps> = ({ clusterName, accountId, region, controller, onlyWorkloadId }) => {
  const [dryRun, setDryRun] = useState(true);
  // With a host controller this hook gets no cluster, so it never fetches; the host's state is used.
  const own = useVerticalRightsizer({
    clusterName: controller ? null : clusterName,
    accountId,
    region,
  });
  const {
    loading,
    result,
    reload,
    applyingIds,
    applyResults,
    modeChangingIds,
    modeResults,
    callerRefusal,
    apply,
    setMode,
  } = controller ?? own;
  /** The host page already shows the Now banner and How it works. */
  const hostOwnsHeader = controller !== undefined;

  if (loading && !result) {
    return <div className="skeleton h-64 rounded-xl"></div>;
  }

  if (!result) {
    return (
      <EmptyState
        icon="mdi:information-outline"
        title="Nothing requested yet"
        subtitle="Workload rightsizing needs a cluster, an account and a region."
      />
    );
  }

  if (result.kind === 'cluster-not-registered') {
    return (
      <EmptyState
        icon="mdi:link-off"
        title="This cluster isn't connected to XamOps yet"
        subtitle="Install the XamOps agent from the cluster's setup guide, then reload."
      />
    );
  }

  if (result.kind === 'unauthenticated') {
    return (
      <EmptyState icon="mdi:account-lock-outline" title="Your session has expired" subtitle="Sign in again to see automation." />
    );
  }

  if (result.kind === 'refused') {
    const copy = result.code.kind === 'known' ? GATE_COPY[result.code.code] : undefined;
    return (
      <EmptyState
        icon={copy?.icon ?? 'mdi:cancel'}
        title={copy?.title ?? codeText(result.code)}
        subtitle={copy?.subtitle ?? result.message}
      />
    );
  }

  if (result.kind === 'error') {
    return (
      <div className="bg-white rounded-lg border border-[rgba(0,0,0,0.12)] p-6">
        <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">Couldn&apos;t load Workload rightsizing</p>
        <p className="text-[12px] text-[rgba(0,0,0,0.5)] mt-1">{result.message}</p>
        <button
          type="button"
          onClick={reload}
          className="mt-4 px-4 py-2 text-xs font-bold rounded-xl border border-[rgba(0,0,0,0.12)] bg-white text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.03)] transition-all"
        >
          Retry
        </button>
      </div>
    );
  }

  const { engine, gateReasons, workloads, anomalies } = result.view;
  const empty = gateEmptyState(gateReasons);
  const changes = changesStatus(result.view, callerRefusal);

  // The "Now" sentence (glossary §3), built only from endpoint 1. `ready` is the number of
  // over-provisioned workloads with a real suggestion; `needMore` is those that need more CPU or
  // memory. Savings ($) come from BE-4, which isn't built, so no `readyMoney` is passed and that
  // clause is honestly dropped — never a fabricated dollar figure. The "apply X first" clause needs
  // BE-12 node room, also absent, so `needsRoom` is omitted.
  const readyCount = workloads.filter((w) => workloadStatus(w).status === 'Over-provisioned').length;
  const needMoreCount = workloads.filter((w) => workloadStatus(w).status.startsWith('Needs more')).length;
  const now = nowSentence('workloads', {
    analysed: workloads.length,
    analysedAt: engine.lastCycleAt ? formatInstant(engine.lastCycleAt) : undefined,
    ready: readyCount,
    needsRoom: undefined,
  });
  // The 5-step flow (glossary §4), collapsed on the Workloads page. The chips come from endpoint 1
  // only; steps that need BE-5/BE-11 history are left without a chip rather than invented.
  const howChips = {
    measure: engine.metricsReachable
      ? { tone: 'good' as const, icon: 'mdi:check-circle', label: 'Up to date' }
      : { tone: 'bad' as const, icon: 'mdi:alert-octagon-outline', label: 'No usage data' },
    decide: { tone: 'info' as const, icon: 'mdi:scale-balance', label: `${readyCount + needMoreCount} suggestions` },
    apply: engine.ready
      ? { tone: 'warn' as const, icon: 'mdi:gesture-tap-button', label: 'Waiting for you' }
      : { tone: 'off' as const, icon: 'mdi:minus-circle-outline', label: 'Applying is off here' },
  };

  const shownWorkloads = onlyWorkloadId === undefined ? workloads : workloads.filter((w) => w.id === onlyWorkloadId);

  return (
    <div className="space-y-4">
      {!hostOwnsHeader && <NowBanner sentence={now} />}
      {!hostOwnsHeader && <HowItWorks chips={howChips} collapsed summary={now.clauses.join(' ')} />}
      <section className="bg-white rounded-lg border border-[rgba(0,0,0,0.12)] px-6 py-4 dark:bg-[#141414] dark:border-[rgba(255,255,255,0.1)]">
        <h3 className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">Workload rightsizing</h3>
        <p className="mt-1 text-[12px] text-[rgba(0,0,0,0.6)] max-w-[820px]">
          Sets each container&apos;s CPU and memory requests to what it actually uses, plus headroom, and can resize
          running pods without restarting them. XamOps only suggests: nothing changes in the cluster until you press
          Apply and confirm.
        </p>

        <div className="flex flex-wrap items-start justify-between gap-4 mt-4">
          <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-x-8 gap-y-3">
            <StatusItem
              label="Workload rightsizing"
              value={engine.enabled ? 'On' : 'Off in this environment'}
              title={`Code: enabled ${engine.enabled}`}
            />
            <StatusItem label="Changes" value={changes.value} title={changes.title} />
            <StatusItem
              label="Cluster access"
              value={clusterAccessText(engine)}
              title={`Code: ${formatText(engine.clusterAccess)}`}
            />
            <StatusItem
              label="How changes are sent"
              value={pathText(engine)}
              title={`Code: ${formatText(engine.executionPath)}`}
            />
            <StatusItem
              label="Usage data"
              value={engine.metricsReachable ? 'Prometheus in your cluster' : 'Not available'}
              title="Read through the Kubernetes API (service proxy)."
            />
            <StatusItem
              label="Last analysed"
              value={lastAnalysedText(engine)}
              title={engine.lastCycleAt ?? undefined}
            />
          </div>
          <div className="flex items-center gap-3">
            <label
              className="flex items-center gap-2 text-[11px] font-semibold text-[rgba(0,0,0,0.6)]"
              title="A test run checks the change without sending anything to the cluster"
            >
              <input
                type="checkbox"
                checked={dryRun}
                onChange={(e) => setDryRun(e.target.checked)}
                className="accent-black"
              />
              Test run only (nothing is sent)
            </label>
            <button
              type="button"
              onClick={reload}
              className="px-4 py-2 text-xs font-bold rounded-xl border border-[rgba(0,0,0,0.12)] bg-white text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.03)] transition-all"
            >
              Refresh
            </button>
          </div>
        </div>

        {gateReasons.length > 0 && (
          <div className="flex flex-wrap items-center gap-1 mt-3">
            <span className="text-[10px] uppercase tracking-wide text-[rgba(0,0,0,0.4)] font-bold mr-1">
              Why changes are off
            </span>
            {gateReasons.map((r) => (
              <Chip key={codeText(r)} text={codeText(r)} title={`Code: ${r.kind === 'known' ? r.code : r.raw}`} />
            ))}
          </div>
        )}

        {anomalies.length > 0 && (
          <div className="mt-3 border-t border-[rgba(0,0,0,0.06)] pt-3">
            <p className="text-[11px] font-bold text-[rgba(0,0,0,0.6)]">Some numbers couldn&apos;t be shown</p>
            <ul className="mt-1 space-y-0.5">
              {anomalies.map((a) => (
                <li key={a} className="text-[11px] text-[rgba(0,0,0,0.5)]">
                  {a}
                </li>
              ))}
            </ul>
          </div>
        )}
      </section>

      {workloads.length === 0 ? (
        <section className="bg-white rounded-lg border border-[rgba(0,0,0,0.12)]">
          <EmptyState icon={empty.icon} title={empty.title} subtitle={empty.subtitle} />
        </section>
      ) : (
        shownWorkloads.map((w) => (
          <WorkloadCard
            key={w.id}
            workload={w}
            gate={manualApplyGate(result.view, w, callerRefusal)}
            modeGate={modeChangeGate(result.view, callerRefusal)}
            modeChanging={modeChangingIds.has(w.id)}
            modeReply={modeResults[w.id]}
            onSetMode={(mode) => {
              void setMode(w, mode);
            }}
            dryRun={dryRun}
            applying={applyingIds.has(w.id)}
            reply={applyResults[w.id]}
            onApply={() => {
              void apply(w, dryRun);
            }}
          />
        ))
      )}
    </div>
  );
};

export default EksAutomationTab;
