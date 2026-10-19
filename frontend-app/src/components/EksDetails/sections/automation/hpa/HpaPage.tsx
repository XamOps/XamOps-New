/**
 * Automation › HPA: horizontal autoscaling (replica bounds and CPU target). UI v3 plan §2 "HPA";
 * glossary §13.
 *
 * Two sources, each named on screen:
 *  - the XamOps agent's dashboard (`eksData.k8sHpas`): every HPA on the cluster with its live
 *    numbers (replicas now against its minimum and maximum, CPU now against its target) and its
 *    conditions. A number the agent didn't report is a dash with "from the XamOps agent", never 0;
 *  - endpoint 1 (one `GET` per cluster, `useHpaAutomation`): XamOps's decision for the workload each
 *    HPA scales, as a plain sentence with its formula, and an Apply that is enabled only when every
 *    gate the server re-checks passes (main's HPA logic, unchanged: `hpaApplyGate`, the confirmation,
 *    the reply), plus the VPA coexistence verdict.
 * The two are joined on the HPA's scale target (namespace, kind and name). `?hpa=<namespace>/<name>`
 * opens one HPA's detail on the same page, so this session's apply replies stay with it. Each HPA's
 * history (FEAT-004: kube-state-metrics through Prometheus in the cluster, one GET per HPA, the first
 * {@link HPA_HISTORY_READ_LIMIT}) draws the replicas and CPU charts and the time-at-maximum strip for
 * the HPA held at its maximum (or the one picked), and fills the table's "Last scaled".
 */

import React, { useEffect, useId, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import useHpaAutomation from '../../../../../hooks/useHpaAutomation';
import { DASH, hpaApplyGate } from '../../../../../api/hpaAdapter';
import { inPageState } from '../../../../../utils/inPageNavigation';
import { hpaCodeText } from '../hpaCopy';
import { EmptyState, HpaReadFallback, gateEmptyState, type GateCopy } from '../hpaReadStates';
import { StatusChip } from '../../../../CloudK8s/shared/status/StatusChip';
import { FeatureMark } from '../../../../CloudK8s/shared/status/FeatureMark';
import { KpiCard } from '../../../../CloudK8s/shared/status/KpiCard';
import { NowBanner } from '../../../../CloudK8s/shared/status/NowBanner';
import { nowSentence, type NowSentence } from '../../../../CloudK8s/shared/status/nowSentence';
import { RangeBar } from '../../../../CloudK8s/shared/charts/RangeBar';
import type { UseEksDashboardReturn } from '../../../../../hooks/useEksDashboard';
import type { UseHpaAutomationState } from '../../../../../hooks/useHpaAutomation';
import type { HpaPageView, HpaWorkloadView } from '../../../../../types/hpaAutomation';
import {
  agentHpas,
  hpaCandidates,
  hpaKpis,
  hpaRows,
  parseHpaParam,
  rowHasFlag,
  withParam,
  type HpaRow,
} from '../automationDerivations';
import { CARD, CONTROL, FAINT, INK, InPageLink, MUTED, Notice, RULE, Tag } from '../automationUi';
import { HpaDecisionPanel, TitledChip, decisionShort } from './HpaDecision';
import HpaDetail from './HpaDetail';
import { FROM_AGENT, HpaHistoryCards, historyOf, lastScaled } from './hpaShared';
import { useHpaHistories } from '../../../../../hooks/useHpaHistory';
import type { InsightReadState } from '../../../../../hooks/useInsightRead';
import type { HpaHistoryView } from '../../../../../types/automationInsights';

export interface HpaPageProps {
  clusterName: string;
  accountId: string;
  region: string;
  /** The page's dashboard (the XamOps agent's HPAs, with their live numbers and conditions). */
  eksData?: UseEksDashboardReturn;
}

/** Exact copy (automation-v2 design §3.2), shared with the workload detail. */
export const MALFORMED_HPA_NOTICE = "That link pointed to something XamOps can't read.";
export const missingHpaNotice = (ref: string): string => `${ref} isn't in this cluster any more.`;

const StatusItem: React.FC<{ label: string; value: string; title?: string }> = ({ label, value, title }) => (
  <div className="flex flex-col gap-0.5">
    <span className={`text-[10px] font-bold uppercase tracking-wide ${FAINT}`}>{label}</span>
    <span className={`text-[12px] font-semibold ${INK}`} title={title}>
      {value}
    </span>
  </div>
);

const count = (value: number | null): string => (value === null ? DASH : String(value));

const plural = (n: number, one: string, many = `${one}s`) => (n === 1 ? one : many);

// ─── Status text (main's) ────────────────────────────────────────────────────

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

/** A dash with the source that didn't report it. */
const Unknown: React.FC<{ why?: string }> = ({ why = FROM_AGENT }) => (
  <span className="inline-flex flex-col">
    <span className={INK}>{DASH}</span>
    <span className={`text-[11px] ${FAINT}`}>{why}</span>
  </span>
);

// ─── Engine status (main's header block) ─────────────────────────────────────

const EngineStatus: React.FC<{
  view: HpaPageView;
  dryRun: boolean;
  onDryRun: (next: boolean) => void;
  reload: () => void;
}> = ({ view, dryRun, onDryRun, reload }) => {
  const { engine, gateReasons, anomalies } = view;
  return (
    <section className={`${CARD} px-4 py-3`} data-hpa-engine="">
      <h3 className={`text-[13px] font-semibold ${INK}`}>How XamOps changes HPAs here</h3>
      <p className={`mt-1 max-w-[820px] text-[12px] ${MUTED}`}>
        Reviews each HorizontalPodAutoscaler&apos;s replica bounds and CPU target against how the workload has
        actually scaled, and can tune them. XamOps only suggests: nothing changes until you press Apply and confirm.
        XamOps never creates an HPA on its own.
      </p>
      <div className="mt-3 flex flex-wrap items-start justify-between gap-4">
        <div className="grid grid-cols-2 gap-x-8 gap-y-3 sm:grid-cols-3 lg:grid-cols-5">
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
          <label className={`flex items-center gap-2 text-[11px] font-semibold ${MUTED}`} title="A test run checks the change without sending anything to the cluster">
            {/* Important: a global `input[type="checkbox"]` rule (styles/gcpPerformance.css) paints every
                checkbox's accent blue, and outranks a plain accent class. */}
            <input
              type="checkbox"
              checked={dryRun}
              onChange={(e) => onDryRun(e.target.checked)}
              className="!accent-black dark:!accent-white"
            />
            Test run only (nothing is sent)
          </label>
          <button
            type="button"
            onClick={reload}
            className="rounded-xl border border-[rgba(0,0,0,0.12)] bg-white px-4 py-2 text-xs font-bold text-[rgba(0,0,0,0.6)] transition-all hover:bg-[rgba(0,0,0,0.03)] dark:border-[rgba(255,255,255,0.16)] dark:bg-[#141414] dark:text-[rgba(255,255,255,0.7)]"
          >
            Refresh
          </button>
        </div>
      </div>

      {gateReasons.length > 0 && (
        <div className="mt-3 flex flex-wrap items-center gap-1">
          <span className={`mr-1 text-[10px] font-bold uppercase tracking-wide ${FAINT}`}>Why changes are off</span>
          {gateReasons.map((r) => (
            <TitledChip key={hpaCodeText(r)} tone="off" icon="mdi:lock-outline" label={hpaCodeText(r)} title={`Code: ${r.kind === 'known' ? r.code : r.raw}`} />
          ))}
        </div>
      )}

      {anomalies.length > 0 && (
        <div className={`mt-3 border-t ${RULE} pt-3`}>
          <p className={`text-[11px] font-bold ${MUTED}`}>Some numbers couldn&apos;t be shown</p>
          <ul className="mt-1 space-y-0.5">
            {anomalies.map((a) => (
              <li key={a} className={`text-[11px] ${FAINT}`}>
                {a}
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
};

// ─── "An HPA would help" cards (D4; main's HPA card) ─────────────────────────

const CandidateCard: React.FC<{
  workload: HpaWorkloadView;
  view: HpaPageView;
  dryRun: boolean;
  state: UseHpaAutomationState;
}> = ({ workload, view, dryRun, state }) => (
  <section className={`${CARD} px-4 py-3`} data-hpa-candidate={workload.id}>
    <div className="mb-2 flex flex-wrap items-center gap-2">
      <span className={`text-[13px] font-semibold ${INK}`}>{workload.ref.name}</span>
      <TitledChip tone="off" icon="mdi:minus-circle-outline" label="No HPA" title="No HPA targets this workload" />
      <span className={`text-[11px] ${FAINT}`}>
        {workload.ref.kind} · Namespace {workload.ref.namespace} · Replicas {count(workload.replicas)}
      </span>
    </div>
    <HpaDecisionPanel
      workload={workload}
      gate={hpaApplyGate(view, workload, dryRun, state.callerRefusal)}
      dryRun={dryRun}
      applying={state.applyingIds.has(workload.id)}
      reply={state.applyResults[workload.id]}
      onApply={() => {
        void state.apply(workload, dryRun);
      }}
    />
  </section>
);

// ─── KPIs and history ────────────────────────────────────────────────────────

const names = (rows: readonly HpaRow[]): string => {
  const list = rows.map((r) => r.name);
  return list.length <= 3 ? list.join(', ') : `${list.slice(0, 3).join(', ')} and ${list.length - 3} more`;
};

const HpaKpiRow: React.FC<{ rows: HpaRow[]; agentReported: boolean; engineRead: boolean }> = ({ rows, agentReported, engineRead }) => {
  const k = hpaKpis(rows, agentReported, engineRead);
  const atMax = rows.filter((r) => rowHasFlag(r, 'at-max'));
  const atMin = rows.filter((r) => rowHasFlag(r, 'at-min'));
  const conflicts = rows.filter((r) => r.workload?.vpaCoexistence.kind === 'managed');
  return (
    <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4" data-hpa-kpis="">
      <KpiCard tone={k.total === null ? 'off' : 'info'} icon="mdi:arrow-expand-horizontal" label="HPAs" value={k.total ?? DASH}>
        {k.total === null ? (
          <p>Not checked: neither the XamOps agent nor the Workload autoscaler answered</p>
        ) : (
          <>
            <p>{k.total > 0 ? names(rows) : 'None on this cluster'}</p>
            <p>{agentReported ? FROM_AGENT : 'from endpoint 1: the XamOps agent reported no HPA list'}</p>
          </>
        )}
      </KpiCard>
      <KpiCard tone={k.atMax === null ? 'off' : k.atMax > 0 ? 'warn' : 'good'} icon="mdi:arrow-collapse-up" label="At their maximum" value={k.atMax ?? DASH}>
        <p>{k.atMax === null ? FROM_AGENT : k.atMax > 0 ? names(atMax) : 'None is held at its maximum'}</p>
      </KpiCard>
      <KpiCard tone={k.atMin === null ? 'off' : 'info'} icon="mdi:arrow-collapse-down" label="At their minimum" value={k.atMin ?? DASH}>
        <p>{k.atMin === null ? FROM_AGENT : k.atMin > 0 ? names(atMin) : 'None is at its minimum'}</p>
      </KpiCard>
      <KpiCard
        tone={k.conflicts === null ? 'off' : k.conflicts > 0 ? 'bad' : 'good'}
        icon={k.conflicts ? 'mdi:alert-octagon-outline' : 'mdi:shield-check-outline'}
        label="Conflicts with XamOps"
        value={k.conflicts ?? DASH}
      >
        <p>
          {k.conflicts === null
            ? "XamOps couldn't read its HPA decisions just now"
            : k.conflicts > 0
              ? `A Kubernetes VPA also changes the pods of ${names(conflicts)}`
              : 'No Kubernetes VPA changes their pods; XamOps leaves their CPU alone'}
        </p>
      </KpiCard>
    </div>
  );
};

// ─── The table ───────────────────────────────────────────────────────────────

/** How XamOps and this HPA get along: the VPA coexistence verdict (endpoint 1). */
const coexistChip = (row: HpaRow, engineRead: boolean): { tone: 'good' | 'warn' | 'bad' | 'off'; icon: string; label: string; detail?: string } => {
  if (!engineRead) return { tone: 'off', icon: 'mdi:help-circle-outline', label: 'Not checked', detail: "XamOps couldn't read its decisions" };
  if (!row.workload) return { tone: 'off', icon: 'mdi:minus-circle-outline', label: 'Not analysed by XamOps' };
  switch (row.workload.vpaCoexistence.kind) {
    case 'managed':
      return { tone: 'bad', icon: 'mdi:alert-octagon-outline', label: 'Conflict: a VPA also changes its pods' };
    case 'unreadable':
      return { tone: 'warn', icon: 'mdi:eye-off-outline', label: "VPA not readable", detail: 'XamOps leaves its requests alone' };
    default:
      return {
        tone: 'good',
        icon: 'mdi:check-circle',
        label: 'Works with XamOps',
        detail: row.live?.cpuTargetPct != null ? 'CPU left to the HPA' : undefined,
      };
  }
};

const HpaTableRow: React.FC<{
  row: HpaRow;
  view: HpaPageView | null;
  engineRead: boolean;
  dryRun: boolean;
  state: UseHpaAutomationState;
  detailHref: string;
  /** This HPA's history (FEAT-004); null when the page didn't read it. Undefined: no history column. */
  history?: InsightReadState<HpaHistoryView> | null;
}> = ({ row, view, engineRead, dryRun, state, detailHref, history }) => {
  const live = row.live;
  const w = row.workload;
  const coexist = coexistChip(row, engineRead);
  const d = w?.decision;
  const scaled = history === undefined ? null : lastScaled(history);
  const cells = scaled ? 8 : 7;
  return (
    <>
      <tr className="align-top" data-hpa-row={row.id}>
        <th scope="row" className={`px-4 py-2.5 text-left font-semibold ${INK}`}>
          {row.name}
          <span className={`block text-[11px] font-normal ${FAINT}`}>{row.namespace}</span>
          <InPageLink to={detailHref} className={`mt-1 inline-block text-[11px] font-semibold underline underline-offset-2 ${MUTED}`} aria-label={`Details for ${row.name}`}>
            Details
          </InPageLink>
        </th>
        <td className={`px-4 py-2.5 ${INK}`}>{row.target ? `${row.target.kind} ${row.target.name}` : <Unknown />}</td>
        <td className="px-4 py-2.5">
          {!live ? (
            <Unknown />
          ) : live.cpuTargetPct !== null ? (
            <span className="inline-flex flex-col">
              <span className={INK}>CPU</span>
              <span className={`text-[11px] ${FAINT}`}>target {live.cpuTargetPct}% of request</span>
            </span>
          ) : (
            <Unknown why="no CPU target reported" />
          )}
        </td>
        <td className="px-4 py-2.5 tabular-nums">
          {!live ? <Unknown /> : live.cpuNowPct !== null ? <span className={`font-semibold ${INK}`}>{live.cpuNowPct}%</span> : <Unknown why="Kubernetes reports no reading" />}
        </td>
        <td className="px-4 py-2.5">
          {live ? (
            <span className="inline-flex flex-col gap-1">
              <RangeBar min={live.min} max={live.max} current={live.current} suggestedMin={d?.minReplicas ?? null} suggestedMax={d?.maxReplicas ?? null} />
              <span className={`text-[11px] tabular-nums ${MUTED}`}>
                {count(live.current)} of {count(live.min)}–{count(live.max)}
              </span>
            </span>
          ) : (
            <Unknown />
          )}
        </td>
        <td className="px-4 py-2.5">
          {row.flag ? (
            <span className="inline-flex flex-col items-start gap-1">
              <StatusChip tone={row.flag.tone} icon={row.flag.icon} label={row.flag.label} detail={row.flag.detail} />
              {row.tags.map((t) => (
                <Tag key={t.key} icon={t.icon}>{t.label}</Tag>
              ))}
              {scaled?.never && <Tag icon="mdi:minus-circle-outline">Never scales: no change in 3 days</Tag>}
            </span>
          ) : scaled?.never ? (
            <Tag icon="mdi:minus-circle-outline">Never scales: no change in 3 days</Tag>
          ) : (
            <Unknown />
          )}
        </td>
        {scaled && (
          <td className="px-4 py-2.5" data-hpa-last-scaled={row.id} title={scaled.title}>
            <span className="inline-flex flex-col">
              <span className={`tabular-nums ${INK}`}>{scaled.text}</span>
              {scaled.sub && <span className={`text-[11px] ${FAINT}`}>{scaled.sub}</span>}
            </span>
          </td>
        )}
        <td className="px-4 py-2.5">
          <span className="inline-flex flex-col items-start gap-1">
            <StatusChip tone={coexist.tone} icon={coexist.icon} label={coexist.label} detail={coexist.detail} />
            {w && <span className={`text-[11px] ${FAINT}`}>{decisionShort(w).label}</span>}
          </span>
        </td>
      </tr>
      <tr className={`border-b ${RULE}`} data-hpa-row-decision={row.id}>
        <td colSpan={cells} className="px-4 pb-3 pt-0">
          {w && view ? (
            <HpaDecisionPanel
              workload={w}
              gate={hpaApplyGate(view, w, dryRun, state.callerRefusal)}
              dryRun={dryRun}
              applying={state.applyingIds.has(w.id)}
              reply={state.applyResults[w.id]}
              onApply={() => {
                void state.apply(w, dryRun);
              }}
            />
          ) : (
            <p className={`text-[12px] ${MUTED}`}>
              {engineRead
                ? "XamOps doesn't analyse the workload this HPA scales, so it has no suggestion for it."
                : "XamOps couldn't read its HPA decisions just now."}
            </p>
          )}
        </td>
      </tr>
    </>
  );
};

const HpaTable: React.FC<{
  rows: HpaRow[];
  view: HpaPageView | null;
  engineRead: boolean;
  dryRun: boolean;
  state: UseHpaAutomationState;
  hrefFor: (row: HpaRow) => string;
  agentReported: boolean;
  /** Each HPA's history for "Last scaled"; omitted when the backend doesn't serve it. */
  historyFor?: (row: HpaRow) => InsightReadState<HpaHistoryView> | null;
}> = ({ rows, view, engineRead, dryRun, state, hrefFor, agentReported, historyFor }) => {
  const titleId = useId();
  return (
    <section className={CARD} aria-labelledby={titleId} data-hpa-table="">
      <div className="px-4 pt-3">
        <h3 id={titleId} className={`text-[13px] font-semibold ${INK}`}>HPAs on this cluster</h3>
        <p className={`mt-0.5 text-[12px] ${MUTED}`}>
          Each HPA&apos;s replicas against its minimum and maximum, with XamOps&apos;s decision for the workload it scales under it.
        </p>
      </div>
      <div className="mt-2 overflow-x-auto">
        <table className="w-full border-collapse text-left text-[12px]" aria-labelledby={titleId}>
          <thead>
            <tr className={`border-y ${RULE} text-[11px] ${MUTED}`}>
              <th scope="col" className="px-4 py-2 font-semibold">HPA</th>
              <th scope="col" className="px-4 py-2 font-semibold">Scales</th>
              <th scope="col" className="px-4 py-2 font-semibold">On</th>
              <th scope="col" className="px-4 py-2 font-semibold">Now</th>
              <th scope="col" className="px-4 py-2 font-semibold">Replicas (now, min–max)</th>
              <th scope="col" className="px-4 py-2 font-semibold">Status</th>
              {historyFor && <th scope="col" className="px-4 py-2 font-semibold">Last scaled</th>}
              <th scope="col" className="px-4 py-2 font-semibold">XamOps</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <HpaTableRow
                key={row.id}
                row={row}
                view={view}
                engineRead={engineRead}
                dryRun={dryRun}
                state={state}
                detailHref={hrefFor(row)}
                history={historyFor ? historyFor(row) : undefined}
              />
            ))}
          </tbody>
        </table>
      </div>
      <p className={`px-4 py-2.5 text-[11px] ${FAINT}`}>
        <span className="font-semibold">Source:</span>{' '}
        {agentReported ? 'the XamOps agent (k8sHpas: HPA spec, status and conditions)' : 'the XamOps agent reported no HPA list, so live numbers are missing'};
        decisions and conflicts from the Workload autoscaler (endpoint 1, GET /api/eks/automation/vertical/{'{cluster}'})
        {historyFor ? "; last scaled from each HPA's history (kube-state-metrics, 1-minute steps, 3 days)." : '.'}
      </p>
    </section>
  );
};

/** Which HPA's history the charts show, when the page read more than one. */
const HistoryPicker: React.FC<{ rows: HpaRow[]; value: string; onChange: (id: string) => void }> = ({ rows, value, onChange }) => {
  const id = useId();
  return (
    <label htmlFor={id} className={`flex flex-wrap items-center gap-2 text-[12px] font-semibold ${MUTED}`}>
      History of
      <select id={id} value={value} onChange={(e) => onChange(e.target.value)} className={CONTROL}>
        {rows.map((r) => (
          <option key={r.id} value={r.id}>{`${r.name} (${r.namespace})`}</option>
        ))}
      </select>
    </label>
  );
};

// ─── Now sentence ────────────────────────────────────────────────────────────

function hpaNow(clusterName: string, rows: HpaRow[], agentReported: boolean, view: HpaPageView | null): NowSentence {
  const atMax = rows.filter((r) => rowHasFlag(r, 'at-max') && r.live?.max != null);
  return nowSentence('hpa', {
    cluster: clusterName,
    hpas: agentReported || view ? rows.map((r) => ({ name: r.name, namespace: r.namespace })) : undefined,
    atMax: atMax.map((r) => ({
      name: r.name,
      max: r.live!.max as number,
      cpuNowPct: r.live!.cpuNowPct ?? undefined,
      cpuTargetPct: r.live!.cpuTargetPct ?? undefined,
    })),
    notScaling: rows.filter((r) => rowHasFlag(r, 'not-scaling')).map((r) => r.name),
    engine: view
      ? { evaluated: view.horizontalEvaluated, suggestions: rows.filter((r) => r.workload?.decision.state === 'CHANGE').length }
      : undefined,
    cpuLeftToHpa: rows.filter((r) => r.workload && r.live?.cpuTargetPct != null).map((r) => r.workload!.ref.name),
    conflicts: view ? rows.filter((r) => r.workload?.vpaCoexistence.kind === 'managed').length : undefined,
  });
}

// ─── Page ────────────────────────────────────────────────────────────────────

export const HpaPage: React.FC<HpaPageProps> = ({ clusterName, accountId, region, eksData }) => {
  const [dryRun, setDryRun] = useState(true);
  const state = useHpaAutomation({ clusterName, accountId, region });
  const { loading, result, reload } = state;
  const location = useLocation();
  const navigate = useNavigate();
  const [malformed, setMalformed] = useState(false);

  const raw = new URLSearchParams(location.search).get('hpa');
  const parsed = raw === null ? null : parseHpaParam(raw);
  const malformedParam = raw !== null && parsed === null;

  /** Takes `hpa` out of the URL in place: same history entry, the page stays mounted. */
  const dropHpaParam = () => {
    const params = new URLSearchParams(location.search);
    params.delete('hpa');
    navigate({ search: params.toString(), hash: location.hash }, { replace: true, state: inPageState(location) });
  };

  useEffect(() => {
    if (malformedParam) {
      setMalformed(true);
      dropHpaParam();
    }
    // Runs once per malformed value; dropHpaParam reads the location it removes it from.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [malformedParam, raw]);

  const view = result?.kind === 'ok' ? result.view : null;
  const dashboard = eksData?.dashboard ?? null;
  const agentReported = agentHpas(dashboard) !== null;
  const rows = hpaRows(view, dashboard);
  const candidates = hpaCandidates(view);
  const here = `${location.pathname}${location.search}`;
  const listHref = withParam(here, 'hpa', null);
  const hrefFor = (row: HpaRow) => withParam(listHref, 'hpa', row.id);
  const settled = result !== null && !eksData?.loading;
  const selected = parsed ? rows.find((r) => r.id === `${parsed.namespace}/${parsed.name}`) ?? null : null;
  const missing = parsed !== null && settled && selected === null ? raw : null;

  // Each HPA's history (FEAT-004), one GET each: the detail reads its own HPA's; the list reads every
  // HPA's for "Last scaled", the one held at its maximum first (it leads the charts).
  const featured = rows.find((r) => rowHasFlag(r, 'at-max')) ?? rows[0] ?? null;
  const historyRows = selected ? [selected] : featured ? [featured, ...rows.filter((r) => r !== featured)] : [];
  const histories = useHpaHistories({ clusterName, accountId, region }, historyRows.map((r) => ({ namespace: r.namespace, name: r.name })), '72h');
  const [historyPick, setHistoryPick] = useState<string | null>(null);

  if (selected) {
    return (
      <HpaDetail
        row={selected}
        view={view}
        engineRead={view !== null}
        dryRun={dryRun}
        onDryRun={setDryRun}
        state={state}
        listHref={listHref}
        history={historyOf(histories, selected.id)}
      />
    );
  }

  const readRows = rows.filter((r) => histories.read.has(r.id));
  const shownHistory = readRows.find((r) => r.id === historyPick) ?? featured;
  const historyServed = readRows.length > 0 && !Object.values(histories.results).some((r) => r.kind === 'not-deployed');

  const sentence: NowSentence = loading && !result && !dashboard
    ? { clauses: ["Reading this cluster's HPAs…"], chips: [] }
    : hpaNow(clusterName, rows, agentReported, view);
  const empty = view ? gateEmptyState(view.gateReasons, 'hpa', NO_HPAS) : NO_HPAS;

  return (
    // data-k8s-kit: every colour on this page has its dark variant, so the legacy dark CSS leaves it
    // alone and the write controls keep their own disabled and confirm states in dark mode.
    <div className="space-y-4" data-hpa-page="" data-k8s-kit="">
      {malformed && <Notice onDismiss={() => setMalformed(false)}>{MALFORMED_HPA_NOTICE}</Notice>}
      {missing && <Notice onDismiss={dropHpaParam}>{missingHpaNotice(missing)}</Notice>}
      <div className="space-y-1">
        <FeatureMark feature="hpa" title="HPA" />
        <p className={`text-[12px] ${MUTED}`}>
          Kubernetes Horizontal Pod Autoscalers add and remove replicas as load changes. XamOps shows each one&apos;s limits
          and conflicts, and keeps its own changes out of their way.
        </p>
      </div>
      <NowBanner sentence={sentence} />
      <HpaKpiRow rows={rows} agentReported={agentReported} engineRead={view !== null} />
      {shownHistory && historyOf(histories, shownHistory.id) && (
        <HpaHistoryCards
          subject={shownHistory.name}
          read={historyOf(histories, shownHistory.id)!}
          picker={readRows.length > 1 ? <HistoryPicker rows={readRows} value={shownHistory.id} onChange={setHistoryPick} /> : undefined}
        />
      )}
      {view && !view.horizontalEvaluated && (
        <Notice>
          HPA suggestions are off in this environment: XamOps doesn&apos;t evaluate HPA settings here. The HPAs and their limits
          still come from the XamOps agent.
        </Notice>
      )}
      {view ? (
        <EngineStatus view={view} dryRun={dryRun} onDryRun={setDryRun} reload={reload} />
      ) : (
        <HpaReadFallback feature="hpa" loading={loading} result={result} reload={reload} />
      )}
      {rows.length > 0 ? (
        <HpaTable
          rows={rows}
          view={view}
          engineRead={view !== null}
          dryRun={dryRun}
          state={state}
          hrefFor={hrefFor}
          agentReported={agentReported}
          historyFor={historyServed ? (row) => historyOf(histories, row.id) : undefined}
        />
      ) : (
        view && candidates.length === 0 && (
          <section className={CARD}>
            <EmptyState icon={empty.icon} title={empty.title} subtitle={empty.subtitle} />
          </section>
        )
      )}
      {view && candidates.length > 0 && (
        <section aria-label="Workloads an HPA would help" className="space-y-3">
          <h3 className={`text-[13px] font-semibold ${INK}`}>
            {candidates.length} {plural(candidates.length, 'workload')} an HPA would help
          </h3>
          {candidates.map((w) => (
            <CandidateCard key={w.id} workload={w} view={view} dryRun={dryRun} state={state} />
          ))}
        </section>
      )}
    </div>
  );
};

export default HpaPage;
