/**
 * XamOps's HPA decision and its gated Apply, shared by the HPA list (one table row per HPA), the HPA
 * detail and the "an HPA would help" cards. This is main's EksHpaTab logic, moved unchanged in
 * behaviour: the decision as one plain sentence with its formula and reason chips, the bounds an
 * apply would set, an Apply that is enabled only when `hpaApplyGate` passes (otherwise greyed with
 * the reason), a confirmation before a live change, and the server's reply, never a fabricated
 * success. Codes never appear as visible text, only in titles as "Code: <CODE>".
 */
import React, { useState } from 'react';
import { RPS_REASON_TEXT, RPS_SIZING_LABEL, hpaCodeText, hpaReasonSentence, hpaReasonTitle } from '../hpaCopy';
import { DASH } from '../../../../../api/hpaAdapter';
import { StatusChip } from '../../../../CloudK8s/shared/status/StatusChip';
import type { Tone } from '../../../../CloudK8s/shared/status/statusTones';
import type { CodeView } from '../../../../../types/verticalRightsizer';
import type { HpaApplyGate, HpaApplyResult, HpaWorkloadView, RpsSizingView } from '../../../../../types/hpaAutomation';
import { FAINT, INK, MUTED, RULE } from '../automationUi';

/** The tone and icon of an HPA reason chip. A reason that holds a change back is a neutral "left alone". */
export const reasonTone = (code: CodeView): { tone: Tone; icon: string } => {
  switch (code.kind === 'known' ? code.code : null) {
    case 'AT_MAX_UNMET_DEMAND':
      return { tone: 'warn', icon: 'mdi:arrow-collapse-up' };
    case 'MAX_ABOVE_OBSERVED_PEAK':
      return { tone: 'warn', icon: 'mdi:arrow-collapse-down' };
    case 'TARGET_COADJUSTED':
    case 'TARGET_RETUNED':
      return { tone: 'info', icon: 'mdi:tune-variant' };
    case 'HPA_RECOMMENDED_NEW':
      return { tone: 'info', icon: 'mdi:lightbulb-outline' };
    case 'HPA_NOT_READABLE':
    case 'VPA_NOT_READABLE':
      return { tone: 'warn', icon: 'mdi:eye-off-outline' };
    default:
      return { tone: 'off', icon: 'mdi:minus-circle-outline' };
  }
};

/** A chip with a tooltip: the title sits on a wrapper, since the chip itself takes none. */
export const TitledChip: React.FC<{ tone: Tone; icon: string; label: string; title?: string }> = ({ tone, icon, label, title }) => (
  <span title={title} className="inline-flex">
    <StatusChip tone={tone} icon={icon} label={label} />
  </span>
);

/**
 * The engine's decision as one plain sentence, built from the first actionable reason. When the
 * engine left the HPA alone (NO_CHANGE) the reasons still explain why; NOT_EVALUATED means the
 * horizontal engine is off in this environment.
 */
export const decisionSentence = (workload: HpaWorkloadView): string => {
  const d = workload.decision;
  if (d.state === 'NOT_EVALUATED') {
    return 'HPA suggestions are off in this environment. Nothing was evaluated for this workload.';
  }
  const ctx = {
    name: workload.hpaName,
    min: d.minReplicas,
    max: d.maxReplicas,
    target: d.targetUtilizationPct,
    formula: d.formula,
  };
  const lead = d.reasons.find((r) => r.kind === 'known');
  if (lead) return hpaReasonSentence(lead, ctx);
  if (d.state === 'CHANGE') return 'XamOps suggests a change to this HPA.';
  return 'No change: this HPA is already the right size.';
};

/** The decision in a few words, for a table cell. */
export const decisionShort = (workload: HpaWorkloadView): { tone: Tone; icon: string; label: string } => {
  const d = workload.decision;
  if (d.state === 'NOT_EVALUATED') return { tone: 'off', icon: 'mdi:minus-circle-outline', label: 'Not evaluated' };
  if (d.state === 'CHANGE') return { tone: 'warn', icon: 'mdi:lightbulb-on-outline', label: 'Change suggested' };
  if (d.state === 'NO_CHANGE') return { tone: 'good', icon: 'mdi:check-circle', label: 'No change needed' };
  return { tone: 'off', icon: 'mdi:help-circle-outline', label: `Other: ${d.state}` };
};

/**
 * The F3 "Suggested replicas (from throughput)" advisory, recommend-only: the number + formula when
 * sized, else a dash with the honest reason. Nothing here is an apply affordance. A `none` kind (the
 * field absent on the wire) renders a plain dash with no reason. Hidden entirely only when the
 * advisory is `none` AND there is nothing to say — but a dash is more honest, so we always show it.
 */
export const SuggestedReplicasLine: React.FC<{ rps: RpsSizingView }> = ({ rps }) => {
  const reasonText =
    rps.kind === 'no-rps-source'
      ? RPS_REASON_TEXT.NO_RPS_SOURCE
      : rps.kind === 'no-capacity-basis'
        ? RPS_REASON_TEXT.NO_CAPACITY_BASIS
        : null;
  return (
    <div className="mt-2" data-hpa-rps={rps.kind}>
      <p className={`text-[11px] font-semibold uppercase tracking-wide ${FAINT}`}>{RPS_SIZING_LABEL}</p>
      {rps.kind === 'sized' ? (
        <>
          <p className={`text-[13px] font-semibold tabular-nums ${INK}`}>about {rps.replicas}</p>
          {rps.formula && (
            <p className={`mt-0.5 text-[11px] tabular-nums ${FAINT}`} title="How XamOps derived the suggestion">
              {rps.formula}
            </p>
          )}
        </>
      ) : (
        <p className={`text-[12px] ${MUTED}`}>
          {DASH}
          {reasonText ? ` ${reasonText}` : ''}
        </p>
      )}
    </div>
  );
};

export const DecisionBlock: React.FC<{ workload: HpaWorkloadView }> = ({ workload }) => {
  const d = workload.decision;
  const ctx = { name: workload.hpaName, min: d.minReplicas, max: d.maxReplicas, target: d.targetUtilizationPct };
  return (
    <div data-hpa-decision={workload.id}>
      <p className={`text-[12px] ${INK}`}>{decisionSentence(workload)}</p>
      {d.formula && (
        <p className={`mt-1 text-[11px] tabular-nums ${FAINT}`} title="How XamOps derived the suggestion">
          {d.formula}
        </p>
      )}
      {d.reasons.length > 0 && (
        <div className="mt-2 flex flex-wrap gap-1">
          {d.reasons.map((r) => (
            <TitledChip key={hpaCodeText(r)} {...reasonTone(r)} label={hpaCodeText(r)} title={hpaReasonTitle(r, ctx)} />
          ))}
        </div>
      )}
      <SuggestedReplicasLine rps={d.rps} />
    </div>
  );
};

/** A bound shown as "now → suggested", or just "now" when the engine leaves it alone. */
export const Bound: React.FC<{ label: string; suggested: number | null }> = ({ label, suggested }) => (
  <div className="flex flex-col gap-0.5">
    <span className={`text-[11px] font-bold uppercase tracking-wide ${FAINT}`}>{label}</span>
    <span className={`text-[12px] font-semibold tabular-nums ${INK}`}>{suggested === null ? 'Leave as is' : `→ ${suggested}`}</span>
  </div>
);

// v3 chrome: the sanctioned control radius (6px) and sizes; behaviour (gate / confirm / reply) is unchanged.
const PRIMARY = 'rounded-[6px] border border-[rgba(0,0,0,0.12)] bg-[rgba(0,0,0,0.88)] px-4 py-2 text-[12px] font-bold text-white transition-all hover:bg-black dark:border-[rgba(255,255,255,0.16)] dark:bg-[rgba(255,255,255,0.92)] dark:text-black';
const SECONDARY = 'rounded-[6px] border border-[rgba(0,0,0,0.12)] bg-white px-4 py-2 text-[12px] font-bold text-[rgba(0,0,0,0.6)] transition-all hover:bg-[rgba(0,0,0,0.03)] dark:border-[rgba(255,255,255,0.16)] dark:bg-[#141414] dark:text-[rgba(255,255,255,0.7)]';

export const ApplyConfirmation: React.FC<{
  workload: HpaWorkloadView;
  gate: HpaApplyGate;
  onConfirm: () => void;
  onCancel: () => void;
}> = ({ workload, gate, onConfirm, onCancel }) => {
  const c = gate.changes;
  const parts: string[] = [];
  if (c?.minReplicas != null) parts.push(`minimum replicas → ${c.minReplicas}`);
  if (c?.maxReplicas != null) parts.push(`maximum replicas → ${c.maxReplicas}`);
  if (c?.targetUtilizationPct != null) parts.push(`CPU target → ${c.targetUtilizationPct}%`);
  const titleId = `hpa-confirm-${workload.id}`;
  return (
    <div role="alertdialog" aria-labelledby={titleId} className={`space-y-3 rounded-[8px] border ${RULE} bg-[rgba(0,0,0,0.02)] px-4 py-3 dark:bg-[rgba(255,255,255,0.03)]`}>
      <p id={titleId} className={`text-[13px] font-semibold ${INK}`}>
        Change {workload.hpaName ?? workload.ref.name} now?
      </p>
      <p className={`text-[12px] ${MUTED}`}>{parts.join(' · ')}</p>
      <ul className={`list-disc space-y-0.5 pl-5 text-[11px] ${MUTED}`}>
        <li>XamOps changes only the bounds and target shown. The HPA keeps scaling the workload.</li>
        <li>No pods are restarted by this change.</li>
      </ul>
      <div className="flex items-center gap-2">
        <button type="button" onClick={onConfirm} className={PRIMARY}>
          Change HPA
        </button>
        <button type="button" onClick={onCancel} className={SECONDARY}>
          Cancel
        </button>
      </div>
    </div>
  );
};

const APPLY_STATE_TEXT: Record<string, string> = {
  ACCEPTED: 'Submitted. XamOps sent the change to the cluster.',
  DUPLICATE: 'Already submitted. XamOps did not send it twice.',
  REFUSED: 'Refused by a safety rule. Nothing was sent to the cluster.',
};

export const ApplyReply: React.FC<{ reply: HpaApplyResult }> = ({ reply }) => {
  if (reply.kind === 'submitted') {
    const applied = reply.applied;
    const dry = reply.dryRun === true || applied?.dryRun === true;
    const stateText = reply.state ? (APPLY_STATE_TEXT[reply.state] ?? `State: ${reply.state}`) : 'Submitted.';
    return (
      <div>
        <p className={`text-[12px] font-semibold ${INK}`}>{dry ? 'Test run passed. Nothing was sent to the cluster.' : stateText}</p>
        {applied && (
          <p className={`mt-1 text-[11px] tabular-nums ${MUTED}`}>
            {applied.minReplicas != null && `min ${applied.minReplicas} `}
            {applied.maxReplicas != null && `max ${applied.maxReplicas} `}
            {applied.targetUtilizationPct != null && `target ${applied.targetUtilizationPct}%`}
          </p>
        )}
      </div>
    );
  }
  if (reply.kind === 'refused') {
    return (
      <p className={`text-[12px] ${MUTED}`} title={`Code: ${reply.code.kind === 'known' ? reply.code.code : reply.code.raw}`}>
        {reply.message}
      </p>
    );
  }
  if (reply.kind === 'cluster-not-registered') {
    return <p className={`text-[12px] ${MUTED}`}>{reply.message}</p>;
  }
  if (reply.kind === 'writes-not-deployed') {
    return <p className={`text-[12px] ${MUTED}`}>Applying HPAs isn&apos;t deployed in this environment yet.</p>;
  }
  return <p className={`text-[12px] ${MUTED}`}>{reply.message}</p>;
};

export interface HpaDecisionPanelProps {
  workload: HpaWorkloadView;
  gate: HpaApplyGate;
  dryRun: boolean;
  applying: boolean;
  reply: HpaApplyResult | undefined;
  onApply: () => void;
}

/**
 * The bounds an apply would set, Apply (greyed with the reason when a gate fails), the
 * confirmation before a live change, the decision and the reply. A test run sends at once; a live
 * change asks first.
 */
export const HpaDecisionPanel: React.FC<HpaDecisionPanelProps> = ({ workload, gate, dryRun, applying, reply, onApply }) => {
  const [confirming, setConfirming] = useState(false);
  React.useEffect(() => {
    if (dryRun || !gate.enabled) setConfirming(false);
  }, [dryRun, gate.enabled]);
  const disabled = !gate.enabled || applying;
  const d = workload.decision;

  const onClickApply = () => {
    if (dryRun) onApply();
    else setConfirming(true);
  };

  return (
    <div className="space-y-3" data-hpa-panel={workload.id}>
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0 flex-1">
          <DecisionBlock workload={workload} />
        </div>
        <div className="flex flex-col items-end gap-1">
          <div className="flex items-center gap-6">
            <Bound label="Min replicas" suggested={d.minReplicas} />
            <Bound label="Max replicas" suggested={d.maxReplicas} />
            <Bound label="CPU target" suggested={d.targetUtilizationPct} />
          </div>
          <button
            type="button"
            onClick={onClickApply}
            disabled={disabled || confirming}
            title={gate.reason ?? (dryRun ? 'Run a test: nothing is sent to the cluster' : 'Review and change the HPA')}
            aria-label={`Apply ${workload.hpaName ?? workload.ref.name}`}
            className={`mt-2 ${PRIMARY} disabled:cursor-not-allowed disabled:bg-white disabled:text-[rgba(0,0,0,0.35)] dark:disabled:bg-[#141414] dark:disabled:text-[rgba(255,255,255,0.35)]`}
          >
            {applying ? 'Submitting…' : 'Apply'}
          </button>
          {gate.reason && <span className={`max-w-[260px] text-right text-[11px] ${FAINT}`}>{gate.reason}</span>}
          {gate.reason && gate.nextStep && <span className={`max-w-[260px] text-right text-[11px] ${FAINT}`}>{gate.nextStep}</span>}
        </div>
      </div>

      {confirming && !dryRun && gate.enabled && !applying && (
        <ApplyConfirmation
          workload={workload}
          gate={gate}
          onConfirm={() => {
            setConfirming(false);
            onApply();
          }}
          onCancel={() => setConfirming(false)}
        />
      )}

      {reply && (
        <div className={`rounded-[8px] border ${RULE} bg-[rgba(0,0,0,0.02)] px-4 py-2.5 dark:bg-[rgba(255,255,255,0.03)]`} data-hpa-reply={workload.id}>
          <ApplyReply reply={reply} />
        </div>
      )}
    </div>
  );
};
