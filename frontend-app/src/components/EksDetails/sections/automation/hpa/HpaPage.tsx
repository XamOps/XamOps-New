/**
 * Automation › HPA: horizontal autoscaling (replica bounds and CPU target).
 *
 * The HPA half of main's combined Pod autoscaler page (`EksHpaTab`), moved here unchanged in
 * behaviour; the VPA coexistence half is its own page (`../vpa/VpaPage`). It reads the vertical
 * rightsizer payload (one `GET` per cluster) and renders, per workload, the engine's decision as a
 * plain sentence (e.g. "XamOps suggests raising the maximum to 8") with its formula, and an Apply
 * button that is enabled only when the decision is appliable and every gate the server re-checks
 * passes, otherwise greyed with the reason — exactly the vertical Apply gating.
 *
 * The same four rules as the Automation tab hold: nothing is fabricated (a value XamOps did not
 * measure is a dash, an unset field is "Not set", a 0 in a resources field is a reported defect),
 * Apply confirms a real change, and codes never appear as visible text — only in titles as
 * "Code: <CODE>". Every status is a chip with an icon and a label, never colour alone.
 */

import React, { useState } from 'react';
import useHpaAutomation from '../../../../../hooks/useHpaAutomation';
import { DASH, hpaApplyGate } from '../../../../../api/hpaAdapter';
import { hpaCodeText, hpaReasonSentence, hpaReasonTitle } from '../hpaCopy';
import { EmptyState, HpaReadFallback, gateEmptyState, type GateCopy } from '../hpaReadStates';
import { StatusChip } from '../../../../CloudK8s/shared/status/StatusChip';
import type { Tone } from '../../../../CloudK8s/shared/status/statusTones';
import type { UseEksDashboardReturn } from '../../../../../hooks/useEksDashboard';
import type { CodeView } from '../../../../../types/verticalRightsizer';
import type { HpaApplyGate, HpaApplyResult, HpaWorkloadView } from '../../../../../types/hpaAutomation';

export interface HpaPageProps {
  clusterName: string;
  accountId: string;
  region: string;
  /** The page's dashboard (the XamOps agent's HPAs), for the list and detail visuals that build on this page. */
  eksData?: UseEksDashboardReturn;
}

// ─── Chips (tone + icon + label, glossary-additions §2) ──────────────────────

/** The tone and icon of an HPA reason chip. A reason that holds a change back is a neutral "left alone". */
const reasonTone = (code: CodeView): { tone: Tone; icon: string } => {
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
const TitledChip: React.FC<{ tone: Tone; icon: string; label: string; title?: string }> = ({ tone, icon, label, title }) => (
  <span title={title} className="inline-flex">
    <StatusChip tone={tone} icon={icon} label={label} />
  </span>
);

const StatusItem: React.FC<{ label: string; value: string; title?: string }> = ({ label, value, title }) => (
  <div className="flex flex-col gap-0.5">
    <span className="text-[10px] uppercase tracking-wide text-[rgba(0,0,0,0.4)] font-bold">{label}</span>
    <span className="text-[12px] text-[rgba(0,0,0,0.88)] font-semibold" title={title}>
      {value}
    </span>
  </div>
);

const count = (value: number | null): string => (value === null ? DASH : String(value));

// ─── Decision sentence (glossary) ────────────────────────────────────────────

/**
 * The engine's decision as one plain sentence, built from the first actionable reason. When the
 * engine left the HPA alone (NO_CHANGE) the reasons still explain why; NOT_EVALUATED means the
 * horizontal engine is off in this environment.
 */
const decisionSentence = (workload: HpaWorkloadView): string => {
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

const DecisionBlock: React.FC<{ workload: HpaWorkloadView }> = ({ workload }) => {
  const d = workload.decision;
  const ctx = { name: workload.hpaName, min: d.minReplicas, max: d.maxReplicas, target: d.targetUtilizationPct };
  return (
    <div className="px-6 py-3 border-t border-[rgba(0,0,0,0.06)]">
      <p className="text-[12px] text-[rgba(0,0,0,0.78)]">{decisionSentence(workload)}</p>
      {d.formula && (
        <p className="mt-1 text-[11px] text-[rgba(0,0,0,0.5)] tabular-nums" title="How XamOps derived the suggestion">
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
    </div>
  );
};

// ─── HPA card ────────────────────────────────────────────────────────────────

/** A bound shown as "now → suggested", or just "now" when the engine leaves it alone. */
const Bound: React.FC<{ label: string; suggested: number | null }> = ({ label, suggested }) => (
  <div className="flex flex-col gap-0.5">
    <span className="text-[10px] uppercase tracking-wide text-[rgba(0,0,0,0.4)] font-bold">{label}</span>
    <span className="text-[12px] text-[rgba(0,0,0,0.88)] font-semibold tabular-nums">
      {suggested === null ? 'Leave as is' : `→ ${suggested}`}
    </span>
  </div>
);

const ApplyConfirmation: React.FC<{
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
    <div role="alertdialog" aria-labelledby={titleId} className="px-6 py-4 border-t border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.02)] space-y-3">
      <p id={titleId} className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">
        Change {workload.hpaName ?? workload.ref.name} now?
      </p>
      <p className="text-[12px] text-[rgba(0,0,0,0.6)]">{parts.join(' · ')}</p>
      <ul className="list-disc pl-5 space-y-0.5 text-[11px] text-[rgba(0,0,0,0.6)]">
        <li>XamOps changes only the bounds and target shown. The HPA keeps scaling the workload.</li>
        <li>No pods are restarted by this change.</li>
      </ul>
      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={onConfirm}
          className="px-4 py-2 text-xs font-bold rounded-xl border border-[rgba(0,0,0,0.12)] bg-[rgba(0,0,0,0.88)] text-white hover:bg-black transition-all"
        >
          Change HPA
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

const APPLY_STATE_TEXT: Record<string, string> = {
  ACCEPTED: 'Submitted. XamOps sent the change to the cluster.',
  DUPLICATE: 'Already submitted. XamOps did not send it twice.',
  REFUSED: 'Refused by a safety rule. Nothing was sent to the cluster.',
};

const ApplyReply: React.FC<{ reply: HpaApplyResult }> = ({ reply }) => {
  if (reply.kind === 'submitted') {
    const applied = reply.applied;
    const dry = reply.dryRun === true || applied?.dryRun === true;
    const stateText = reply.state ? (APPLY_STATE_TEXT[reply.state] ?? `State: ${reply.state}`) : 'Submitted.';
    return (
      <div>
        <p className="text-[12px] font-semibold text-[rgba(0,0,0,0.88)]">
          {dry ? 'Test run passed. Nothing was sent to the cluster.' : stateText}
        </p>
        {applied && (
          <p className="mt-1 text-[11px] text-[rgba(0,0,0,0.6)] tabular-nums">
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
      <p className="text-[12px] text-[rgba(0,0,0,0.6)]" title={`Code: ${reply.code.kind === 'known' ? reply.code.code : reply.code.raw}`}>
        {reply.message}
      </p>
    );
  }
  if (reply.kind === 'cluster-not-registered') {
    return <p className="text-[12px] text-[rgba(0,0,0,0.6)]">{reply.message}</p>;
  }
  if (reply.kind === 'writes-not-deployed') {
    return <p className="text-[12px] text-[rgba(0,0,0,0.6)]">Applying HPAs isn&apos;t deployed in this environment yet.</p>;
  }
  return <p className="text-[12px] text-[rgba(0,0,0,0.6)]">{reply.message}</p>;
};

const HpaCard: React.FC<{
  workload: HpaWorkloadView;
  gate: HpaApplyGate;
  dryRun: boolean;
  applying: boolean;
  reply: HpaApplyResult | undefined;
  onApply: () => void;
}> = ({ workload, gate, dryRun, applying, reply, onApply }) => {
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
    <section className="bg-white rounded-lg border border-[rgba(0,0,0,0.12)] overflow-hidden">
      <div className="flex flex-wrap items-start justify-between gap-4 px-6 py-4">
        <div className="min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">
              {workload.hpaPresent ? (workload.hpaName ?? workload.ref.name) : workload.ref.name}
            </span>
            {workload.hpaPresent ? (
              <TitledChip tone="info" icon="mdi:arrow-expand-horizontal" label="HPA" title="A HorizontalPodAutoscaler targets this workload" />
            ) : (
              <TitledChip tone="off" icon="mdi:minus-circle-outline" label="No HPA" title="No HPA targets this workload" />
            )}
            {workload.vpaCoexistence.kind === 'managed' && (
              <TitledChip
                tone="off"
                icon="mdi:minus-circle-outline"
                label="VPA-managed"
                title={hpaReasonTitle({ kind: 'known', code: 'VPA_MANAGED', label: '' } as CodeView, { name: workload.hpaName })}
              />
            )}
          </div>
          <p className="mt-0.5 text-[11px] text-[rgba(0,0,0,0.5)]">
            {workload.ref.kind} &middot; Namespace {workload.ref.namespace} &middot; Replicas {count(workload.replicas)}
          </p>
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
            className="mt-2 px-4 py-2 text-xs font-bold rounded-xl border border-[rgba(0,0,0,0.12)] bg-[rgba(0,0,0,0.88)] text-white hover:bg-black transition-all disabled:bg-white disabled:text-[rgba(0,0,0,0.35)] disabled:cursor-not-allowed"
          >
            {applying ? 'Submitting…' : 'Apply'}
          </button>
          {gate.reason && <span className="text-[11px] text-[rgba(0,0,0,0.5)] text-right max-w-[240px]">{gate.reason}</span>}
          {gate.reason && gate.nextStep && (
            <span className="text-[11px] text-[rgba(0,0,0,0.5)] text-right max-w-[240px]">{gate.nextStep}</span>
          )}
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

      <DecisionBlock workload={workload} />

      {reply && (
        <div className="px-6 py-3 border-t border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.02)]">
          <ApplyReply reply={reply} />
        </div>
      )}
    </section>
  );
};

// ─── Status text ─────────────────────────────────────────────────────────────

const NO_HPAS: GateCopy = {
  icon: 'mdi:arrow-expand-horizontal',
  title: 'No HPAs to show',
  subtitle: 'XamOps found no HorizontalPodAutoscalers and no workload that would benefit from one.',
};

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

// ─── Page ────────────────────────────────────────────────────────────────────

export const HpaPage: React.FC<HpaPageProps> = ({ clusterName, accountId, region }) => {
  const [dryRun, setDryRun] = useState(true);
  const { loading, result, reload, applyingIds, applyResults, callerRefusal, apply } = useHpaAutomation({
    clusterName,
    accountId,
    region,
  });

  if (result?.kind !== 'ok') {
    return <HpaReadFallback feature="hpa" loading={loading} result={result} reload={reload} />;
  }

  const view = result.view;
  const { engine, gateReasons, hpaWorkloads, anomalies } = view;
  const empty = gateEmptyState(gateReasons, 'hpa', NO_HPAS);

  return (
    <div className="space-y-4">
      <section className="bg-white rounded-lg border border-[rgba(0,0,0,0.12)] px-6 py-4">
        <h3 className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">HPA</h3>
        <p className="mt-1 text-[12px] text-[rgba(0,0,0,0.6)] max-w-[820px]">
          Reviews each HorizontalPodAutoscaler&apos;s replica bounds and CPU target against how the workload has
          actually scaled, and can tune them. XamOps only suggests: nothing changes until you press Apply and confirm.
          XamOps never creates an HPA on its own.
        </p>

        <div className="flex flex-wrap items-start justify-between gap-4 mt-4">
          <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-x-8 gap-y-3">
            <StatusItem label="HPA automation" value={engine.enabled ? 'On' : 'Off in this environment'} title={`Code: enabled ${engine.enabled}`} />
            <StatusItem
              label="Changes"
              value={engine.ready ? (view.writeGatesChecked ? 'Can be applied' : 'Not checked') : 'Suggestions only'}
              title="Applying is gated the same way as workload rightsizing."
            />
            <StatusItem
              label="Cluster access"
              value={engine.clusterAccess === null ? 'Not checked' : CLUSTER_ACCESS_TEXT[engine.clusterAccess] ?? `Other: ${engine.clusterAccess}`}
            />
            <StatusItem
              label="How changes are sent"
              value={engine.executionPath === null ? DASH : PATH_TEXT[engine.executionPath] ?? `Other: ${engine.executionPath}`}
            />
            <StatusItem
              label="Evaluated"
              value={view.horizontalEvaluated ? `${count(engine.lastCycleEvaluated)} workloads` : 'Not evaluated yet'}
              title={engine.lastCycleAt ?? undefined}
            />
          </div>
          <div className="flex items-center gap-3">
            <label className="flex items-center gap-2 text-[11px] font-semibold text-[rgba(0,0,0,0.6)]" title="A test run checks the change without sending anything to the cluster">
              <input type="checkbox" checked={dryRun} onChange={(e) => setDryRun(e.target.checked)} className="accent-black" />
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
            <span className="text-[10px] uppercase tracking-wide text-[rgba(0,0,0,0.4)] font-bold mr-1">Why changes are off</span>
            {gateReasons.map((r) => (
              <TitledChip key={hpaCodeText(r)} tone="off" icon="mdi:lock-outline" label={hpaCodeText(r)} title={`Code: ${r.kind === 'known' ? r.code : r.raw}`} />
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

      {hpaWorkloads.length === 0 ? (
        <section className="bg-white rounded-lg border border-[rgba(0,0,0,0.12)]">
          <EmptyState icon={empty.icon} title={empty.title} subtitle={empty.subtitle} />
        </section>
      ) : (
        hpaWorkloads.map((w) => (
          <HpaCard
            key={w.id}
            workload={w}
            gate={hpaApplyGate(view, w, dryRun, callerRefusal)}
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

export default HpaPage;
