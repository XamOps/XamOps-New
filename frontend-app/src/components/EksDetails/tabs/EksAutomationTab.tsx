/**
 * EKS Automation Tab — workload vertical rightsizing (requests and limits).
 *
 * Reads `GET /api/eks/automation/vertical/{cluster}` and is prepared to call the apply endpoint.
 * Three rules shape everything here:
 *
 *  - Nothing is fabricated. A value the engine did not measure renders `—`, never `0`, and a `0`
 *    arriving in a resources field is reported as a payload defect rather than drawn as a number.
 *  - Apply is enabled only when the backend says `autoApplyEligible`, and the blocking reason is
 *    shown rather than the control silently doing nothing.
 *  - An apply is a submission. The reply is rendered as submitted, never as applied.
 */

import React, { useMemo, useState } from 'react';
import {
  DASH,
  codeText,
  formatCoverage,
  formatCount,
  formatMib,
  formatMillis,
  formatText,
  toApplyContainers,
} from '../../../api/verticalRightsizerAdapter';
import useVerticalRightsizer from '../../../hooks/useVerticalRightsizer';
import type {
  ApplyOutcomeWire,
  ApplyPodProofWire,
  ApplyResult,
  CodeView,
  ContainerView,
  MetricValue,
  WorkloadView,
} from '../../../types/verticalRightsizer';

interface EksAutomationTabProps {
  clusterName: string;
  accountId: string;
  region: string;
}

// ─── Helpers, following the EksResourcesTab patterns in grey only ─────────────

const Chip: React.FC<{ text: string }> = ({ text }) => (
  <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-bold border bg-[rgba(0,0,0,0.02)] text-[rgba(0,0,0,0.6)] border-[rgba(0,0,0,0.1)]">
    {text}
  </span>
);

const EmptyState: React.FC<{ icon: string; title: string; subtitle: string }> = ({ icon, title, subtitle }) => (
  <div className="flex flex-col items-center justify-center py-16 text-center">
    <div className="w-14 h-14 bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.06)] rounded-lg flex items-center justify-center mb-4">
      <i className={`${icon} text-[rgba(0,0,0,0.4)] text-xl`}></i>
    </div>
    <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">{title}</p>
    <p className="text-[12px] text-[rgba(0,0,0,0.5)] mt-1 max-w-[460px]">{subtitle}</p>
  </div>
);

const StatusItem: React.FC<{ label: string; value: string }> = ({ label, value }) => (
  <div className="flex flex-col gap-0.5">
    <span className="text-[10px] uppercase tracking-wide text-[rgba(0,0,0,0.4)] font-bold">{label}</span>
    <span className="text-[12px] text-[rgba(0,0,0,0.88)] font-semibold">{value}</span>
  </div>
);

/** `current` on top, the recommendation under it only when the engine actually moved the value. */
const ResourceCell: React.FC<{
  current: MetricValue;
  recommended: MetricValue | null;
  format: (v: MetricValue) => string;
}> = ({ current, recommended, format }) => {
  const moved =
    recommended !== null &&
    recommended.kind === 'value' &&
    !(current.kind === 'value' && current.value === recommended.value);
  return (
    <div className="flex flex-col">
      <span className="text-[12px] text-[rgba(0,0,0,0.88)] tabular-nums">{format(current)}</span>
      {moved && (
        <span className="text-[11px] text-[rgba(0,0,0,0.5)] tabular-nums">
          &rarr; {format(recommended)}
        </span>
      )}
    </div>
  );
};

/**
 * What each outcome is called on screen.
 *
 * Only `APPLIED` says anything like "applied". `DEFERRED` is a wait, `INFEASIBLE` is a refusal by
 * the cluster, `DRY_RUN` is a rehearsal and `RESTART_DETECTED` is a broken promise — none of them is
 * a success, and none of them is allowed to read like one.
 */
const OUTCOME_TEXT: Record<string, string> = {
  APPLIED: 'Applied and confirmed in the pods',
  PARTIALLY_APPLIED: 'Partly applied — the rollout stopped at the first failure',
  DRY_RUN: 'Dry run — nothing was sent to the cluster',
  DEFERRED: 'Waiting for node capacity — not applied',
  INFEASIBLE: 'The cluster cannot apply this on the current node — not applied',
  STUCK_IN_PROGRESS: 'Still in progress at the deadline — not confirmed',
  RESTART_DETECTED: 'A container restarted — this was not a restart-free resize',
  NOT_READY: 'The new values are in place but the pod is no longer Ready',
  REFUSED: 'Refused — nothing was sent to the cluster',
  EXPIRED: 'Expired before it was applied — nothing was sent',
  FAILED: 'Failed — not applied',
};

const outcomeText = (terminal: string | null): string =>
  terminal === null || terminal.trim() === ''
    ? 'The outcome has not been reported yet'
    : (OUTCOME_TEXT[terminal] ?? `The engine reported ${terminal}`);

/** The restart-free proof for one pod, in the numbers it was proved from. */
const PodProof: React.FC<{ pod: ApplyPodProofWire }> = ({ pod }) => (
  <li className="text-[11px] text-[rgba(0,0,0,0.5)]">
    {pod.podName} &middot; {pod.samePod === true ? 'same pod' : 'pod replaced'} &middot;{' '}
    {pod.ready === true ? 'Ready' : 'not Ready'}
    {(pod.containers ?? []).map((c) => (
      <span key={c.name}>
        {' '}
        &middot; {c.name} restarts {formatCount(c.restartCountBefore)} &rarr;{' '}
        {formatCount(c.restartCountAfter)}
        {c.containerIdBefore !== null && c.containerIdAfter !== null
          ? c.containerIdBefore === c.containerIdAfter
            ? ' · same container'
            : ' · container replaced'
          : ''}
      </span>
    ))}
  </li>
);

const ApplyOutcome: React.FC<{ outcome: ApplyOutcomeWire }> = ({ outcome }) => (
  <div className="space-y-1">
    <p className="text-[12px] font-semibold text-[rgba(0,0,0,0.88)]">
      {outcomeText(outcome.terminal)}
    </p>
    <p className="text-[11px] text-[rgba(0,0,0,0.5)]">
      Method {formatText(outcome.method)} &middot;{' '}
      {outcome.terminal === 'APPLIED' && outcome.restartFree === true
        ? 'restart-free, proved per pod'
        : 'restart-free not claimed'}
    </p>
    {outcome.detail && <p className="text-[11px] text-[rgba(0,0,0,0.6)]">{outcome.detail}</p>}
    {(outcome.containers ?? []).length > 0 && (
      <ul className="space-y-0.5">
        {(outcome.containers ?? []).map((c) => (
          <li key={c.name} className="text-[11px] text-[rgba(0,0,0,0.5)]">
            {c.name} &middot; {formatText(c.state)}
            {c.restartExpected === true ? ' · restarts by its own resizePolicy' : ''}
            {c.detail ? ` · ${c.detail}` : ''}
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
  </div>
);

const ApplyReply: React.FC<{ reply: ApplyResult }> = ({ reply }) => {
  if (reply.kind === 'writes-not-deployed') {
    return (
      <p className="text-[12px] text-[rgba(0,0,0,0.6)]">
        Apply is not deployed in this environment yet. The recommendation above is unchanged.
      </p>
    );
  }
  if (reply.kind === 'cluster-not-registered') {
    return <p className="text-[12px] text-[rgba(0,0,0,0.6)]">{reply.message}</p>;
  }
  if (reply.kind === 'submitted') {
    return (
      <div className="space-y-1">
        <p className="text-[12px] text-[rgba(0,0,0,0.6)]">
          Submitted &middot; {formatText(reply.state)}
          {reply.dryRun === true ? ' · dry run' : ''} &middot; id {formatText(reply.correlationId)}
          {reply.result === null ? '. The outcome arrives in the actions feed.' : ''}
        </p>
        {reply.result !== null && <ApplyOutcome outcome={reply.result} />}
      </div>
    );
  }
  if (reply.kind === 'refused') {
    return (
      <p className="text-[12px] text-[rgba(0,0,0,0.6)]">
        Refused &middot; {codeText(reply.code)} &middot; {reply.message}
      </p>
    );
  }
  return <p className="text-[12px] text-[rgba(0,0,0,0.6)]">{reply.message}</p>;
};

// ─── Empty-state copy, keyed by the gate the backend actually sent ────────────

interface GateCopy {
  icon: string;
  title: string;
  subtitle: string;
}

const GATE_COPY: Record<string, GateCopy> = {
  CONTROLLER_NOT_READY: {
    icon: 'fas fa-toggle-off',
    title: 'Workload automation is off',
    subtitle:
      'Vertical rightsizing is disabled for this environment, so nothing has been evaluated. No cluster or AWS call was made.',
  },
  CLUSTER_ACCESS_NOT_CONFIGURED: {
    icon: 'fas fa-key',
    title: 'This cluster has not granted XamOps access',
    subtitle:
      "Create an EKS access entry for the role registered for this account, mapped to the group matching the connection's access type.",
  },
  CLUSTER_UNREACHABLE: {
    icon: 'fas fa-plug',
    title: 'The cluster API server could not be reached',
    subtitle:
      'Access is configured but the endpoint did not answer, which is expected for a private-endpoint cluster without the XamOps agent installed.',
  },
  NO_METRICS_SOURCE: {
    icon: 'fas fa-chart-area',
    title: 'No usage history source in this cluster',
    subtitle:
      'Prometheus could not be reached through the Kubernetes service proxy, so there is no measured usage to size against. Nothing is estimated.',
  },
  READ_ONLY_CONNECTION: {
    icon: 'fas fa-lock',
    title: 'This account is connected read-only',
    subtitle: 'Recommendations can be read, but nothing can be applied through this connection.',
  },
  NO_USER_PERMISSION: {
    icon: 'fas fa-user-lock',
    title: 'You do not have permission to run automation',
    subtitle: 'Ask an administrator for the automation permission on this account.',
  },
  DEMO: {
    icon: 'fas fa-flask',
    title: 'Demo mode',
    subtitle: 'Automation is disabled while the console is in demo mode.',
  },
  INSUFFICIENT_HISTORY: {
    icon: 'fas fa-hourglass-half',
    title: 'Not enough usage history yet',
    subtitle:
      'The measured window is too short or too sparse to size anything. This resolves itself as the workloads keep running.',
  },
};

const NO_WORKLOADS: GateCopy = {
  icon: 'fas fa-cubes',
  title: 'No workloads evaluated',
  subtitle:
    'The engine ran and published nothing for this cluster. Deployments and StatefulSets in protected namespaces are never evaluated.',
};

const gateEmptyState = (gateReasons: CodeView[]): GateCopy => {
  for (const reason of gateReasons) {
    if (reason.kind === 'known' && GATE_COPY[reason.code]) return GATE_COPY[reason.code];
  }
  const unknown = gateReasons.find((r) => r.kind === 'unknown');
  if (unknown && unknown.kind === 'unknown') {
    return {
      icon: 'fas fa-circle-info',
      title: 'Nothing evaluated',
      subtitle: `The engine reported ${unknown.raw}.`,
    };
  }
  return NO_WORKLOADS;
};

// ─── Container table ─────────────────────────────────────────────────────────

const ContainerTable: React.FC<{ containers: ContainerView[]; skipReason: CodeView | null }> = ({
  containers,
  skipReason,
}) => {
  if (containers.length === 0) {
    return (
      <div className="px-6 py-5 border-t border-[rgba(0,0,0,0.06)]">
        <p className="text-[12px] text-[rgba(0,0,0,0.6)]">
          Not measured yet{skipReason ? ` — ${codeText(skipReason)}` : ''}. No current values and no
          recommendation are published for this workload.
        </p>
      </div>
    );
  }
  return (
    <div className="border-t border-[rgba(0,0,0,0.06)] overflow-x-auto">
      <table className="w-full text-left">
        <thead>
          <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
            {['Container', 'QoS', 'CPU req', 'Mem req', 'CPU limit', 'Mem limit', 'VPA target', 'Reasons'].map(
              (h) => (
                <th
                  key={h}
                  className="px-4 py-2 text-[10px] uppercase tracking-wide font-bold text-[rgba(0,0,0,0.4)] whitespace-nowrap"
                >
                  {h}
                </th>
              ),
            )}
          </tr>
        </thead>
        <tbody>
          {containers.map((c) => (
            <tr key={c.name} className="border-b border-[rgba(0,0,0,0.04)] last:border-0 align-top">
              <td className="px-4 py-3 text-[12px] font-semibold text-[rgba(0,0,0,0.88)] whitespace-nowrap">
                {c.name}
              </td>
              <td className="px-4 py-3 text-[12px] text-[rgba(0,0,0,0.6)] whitespace-nowrap">
                {formatText(c.qosClass)}
              </td>
              <td className="px-4 py-3 whitespace-nowrap">
                <ResourceCell
                  current={c.current.cpuRequestM}
                  recommended={c.recommended?.cpuRequestM ?? null}
                  format={formatMillis}
                />
              </td>
              <td className="px-4 py-3 whitespace-nowrap">
                <ResourceCell
                  current={c.current.memRequestMi}
                  recommended={c.recommended?.memRequestMi ?? null}
                  format={formatMib}
                />
              </td>
              <td className="px-4 py-3 whitespace-nowrap">
                <ResourceCell
                  current={c.current.cpuLimitM}
                  recommended={c.recommended?.cpuLimitM ?? null}
                  format={formatMillis}
                />
              </td>
              <td className="px-4 py-3 whitespace-nowrap">
                <ResourceCell
                  current={c.current.memLimitMi}
                  recommended={c.recommended?.memLimitMi ?? null}
                  format={formatMib}
                />
              </td>
              <td className="px-4 py-3 text-[12px] text-[rgba(0,0,0,0.6)] whitespace-nowrap tabular-nums">
                {c.upstreamVpa
                  ? `${formatMillis(c.upstreamVpa.cpuRequestM)} / ${formatMib(c.upstreamVpa.memRequestMi)}`
                  : `${DASH} / ${DASH}`}
              </td>
              <td className="px-4 py-3">
                <div className="flex flex-wrap gap-1">
                  {c.reasons.length === 0 ? (
                    <span className="text-[12px] text-[rgba(0,0,0,0.4)]">{DASH}</span>
                  ) : (
                    c.reasons.map((r) => <Chip key={codeText(r)} text={codeText(r)} />)
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

// ─── Workload card ───────────────────────────────────────────────────────────

const WorkloadCard: React.FC<{
  workload: WorkloadView;
  dryRun: boolean;
  applying: boolean;
  reply: ApplyResult | undefined;
  onApply: () => void;
}> = ({ workload, dryRun, applying, reply, onApply }) => {
  const changes = useMemo(() => toApplyContainers(workload), [workload]);
  const nothingToApply = changes.length === 0;
  const disabled = !workload.autoApplyEligible || nothingToApply || applying;

  const blockedReason = !workload.autoApplyEligible
    ? workload.applyBlockedBy
      ? codeText(workload.applyBlockedBy)
      : 'The engine has not marked this workload as auto-appliable.'
    : nothingToApply
      ? 'No change is recommended.'
      : null;

  const p = workload.provenance;

  return (
    <section className="bg-white rounded-lg border border-[rgba(0,0,0,0.12)] overflow-hidden">
      <div className="flex flex-wrap items-start justify-between gap-4 px-6 py-4">
        <div className="min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">{workload.ref.name}</span>
            <Chip text={workload.ref.kind} />
            <span className="text-[11px] text-[rgba(0,0,0,0.5)]">{workload.ref.namespace}</span>
          </div>
          <div className="flex items-center gap-3 flex-wrap mt-2 text-[11px] text-[rgba(0,0,0,0.5)]">
            <span>Mode {formatText(workload.policy?.mode)}</span>
            <span>Replicas {formatCount(workload.replicas)}</span>
            <span>HPA {workload.hpaPresent ? formatText(workload.hpaName) : DASH}</span>
            <span>
              Window {formatText(workload.policy?.window)} &middot; p
              {formatCount(workload.policy?.percentile ?? null)}
            </span>
          </div>
          {workload.skipReason && (
            <p className="mt-2 text-[12px] text-[rgba(0,0,0,0.6)]">
              Skipped &middot; {codeText(workload.skipReason)}
            </p>
          )}
          {workload.findings.length > 0 && (
            <div className="flex flex-wrap gap-1 mt-2">
              {workload.findings.map((f) => (
                <Chip key={codeText(f)} text={codeText(f)} />
              ))}
            </div>
          )}
        </div>

        <div className="flex flex-col items-end gap-1">
          <button
            type="button"
            onClick={onApply}
            disabled={disabled}
            title={blockedReason ?? (dryRun ? 'Submit as a dry run' : 'Submit this change')}
            aria-label={`Apply ${workload.ref.name}`}
            className="px-4 py-2 text-xs font-bold rounded-xl border border-[rgba(0,0,0,0.12)] bg-[rgba(0,0,0,0.88)] text-white hover:bg-black transition-all disabled:bg-white disabled:text-[rgba(0,0,0,0.35)] disabled:cursor-not-allowed"
          >
            {applying ? 'Submitting…' : 'Apply'}
          </button>
          {blockedReason && (
            <span className="text-[11px] text-[rgba(0,0,0,0.5)] text-right max-w-[220px]">{blockedReason}</span>
          )}
        </div>
      </div>

      {p && (
        <div className="px-6 pb-4 text-[11px] text-[rgba(0,0,0,0.5)] flex flex-wrap gap-3">
          <span>p{formatCount(p.percentile)}</span>
          <span>{p.windowHours === null ? DASH : `${p.windowHours}h window`}</span>
          <span>Coverage {formatCoverage(p.sampleCoverage)}</span>
          <span>
            {formatText(p.windowStart)} &rarr; {formatText(p.windowEnd)}
          </span>
          <span>Source {formatText(p.usageSource)}</span>
          <span>Policy {formatText(p.policySource)}</span>
        </div>
      )}

      <ContainerTable containers={workload.containers} skipReason={workload.skipReason} />

      {reply && (
        <div className="px-6 py-3 border-t border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.02)]">
          <ApplyReply reply={reply} />
        </div>
      )}
    </section>
  );
};

// ─── Tab ─────────────────────────────────────────────────────────────────────

const EksAutomationTab: React.FC<EksAutomationTabProps> = ({ clusterName, accountId, region }) => {
  const [dryRun, setDryRun] = useState(true);
  const { loading, result, reload, applyingId, applyResults, apply } = useVerticalRightsizer({
    clusterName,
    accountId,
    region,
  });

  if (loading && !result) {
    return <div className="skeleton h-64 rounded-xl"></div>;
  }

  if (!result) {
    return (
      <EmptyState
        icon="fas fa-circle-info"
        title="Nothing requested yet"
        subtitle="Automation needs a cluster, an account and a region."
      />
    );
  }

  if (result.kind === 'cluster-not-registered') {
    return (
      <EmptyState
        icon="fas fa-link-slash"
        title="This cluster is not registered with XamOps in this environment"
        subtitle="Install the XamOps agent from the cluster's setup guide, then reload. Nothing is wrong with the cluster itself."
      />
    );
  }

  if (result.kind === 'unauthenticated') {
    return (
      <EmptyState
        icon="fas fa-user-lock"
        title="Your session has expired"
        subtitle="Sign in again to read automation status."
      />
    );
  }

  if (result.kind === 'refused') {
    const copy = result.code.kind === 'known' ? GATE_COPY[result.code.code] : undefined;
    return (
      <EmptyState
        icon={copy?.icon ?? 'fas fa-ban'}
        title={copy?.title ?? codeText(result.code)}
        subtitle={copy?.subtitle ?? result.message}
      />
    );
  }

  if (result.kind === 'error') {
    return (
      <div className="bg-white rounded-lg border border-[rgba(0,0,0,0.12)] p-6">
        <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">Could not load automation</p>
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

  return (
    <div className="space-y-4">
      <section className="bg-white rounded-lg border border-[rgba(0,0,0,0.12)] px-6 py-4">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-x-8 gap-y-3">
            <StatusItem label="Engine" value={engine.enabled ? 'Enabled' : 'Off'} />
            <StatusItem label="Ready" value={engine.ready ? 'Yes' : 'No'} />
            <StatusItem label="Cluster access" value={formatText(engine.clusterAccess)} />
            <StatusItem label="Path" value={formatText(engine.executionPath)} />
            <StatusItem
              label="Metrics"
              value={engine.metricsReachable ? formatText(engine.metricsName) : DASH}
            />
            <StatusItem
              label="Last cycle"
              value={
                engine.lastCycleAt === null
                  ? DASH
                  : `${engine.lastCycleAt} · ${formatCount(engine.lastCycleEvaluated)}`
              }
            />
          </div>
          <div className="flex items-center gap-3">
            <label className="flex items-center gap-2 text-[11px] font-semibold text-[rgba(0,0,0,0.6)]">
              <input
                type="checkbox"
                checked={dryRun}
                onChange={(e) => setDryRun(e.target.checked)}
                className="accent-black"
              />
              Dry run
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

        {engine.lastCycleError && (
          <p className="mt-3 text-[12px] text-[rgba(0,0,0,0.6)]">
            Last cycle failed &middot; {engine.lastCycleError}
          </p>
        )}

        {gateReasons.length > 0 && (
          <div className="flex flex-wrap items-center gap-1 mt-3">
            <span className="text-[10px] uppercase tracking-wide text-[rgba(0,0,0,0.4)] font-bold mr-1">
              Gates
            </span>
            {gateReasons.map((r) => (
              <Chip key={codeText(r)} text={codeText(r)} />
            ))}
          </div>
        )}

        {anomalies.length > 0 && (
          <div className="mt-3 border-t border-[rgba(0,0,0,0.06)] pt-3">
            <p className="text-[11px] font-bold text-[rgba(0,0,0,0.6)]">
              Unusable values in the payload, shown as {DASH}
            </p>
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
        workloads.map((w) => (
          <WorkloadCard
            key={w.id}
            workload={w}
            dryRun={dryRun}
            applying={applyingId === w.id}
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
