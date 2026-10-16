/**
 * Automation › Overview (UI v3 plan §2 "Overview"; design-delta §3.1, §4.1, §4.2; glossary §3, §4,
 * §10). What XamOps can automate on this cluster, what it is doing now and what it has done, from
 * three real sources:
 *  - endpoint 1, read once (`useAutomationRead`): the Now sentence, the KPI row, How it works, the
 *    latest change per workload over 7 days, the feature cards' Workload autoscaler, HPA and VPA
 *    state, and the suggested side of the namespace chart;
 *  - the XamOps agent's dashboard (`eksData`): requested and used by namespace, HPAs, who changes
 *    the node count;
 *  - capabilities: the access overlays, the Access block and "What automation can do on this
 *    cluster today" (one row per capability, with the server's message for anything not ready).
 * A time series has no source yet, so its card says "History arrives with the usage feed". No
 * dollar figure appears: prices aren't read, so savings are freed vCPU and memory.
 */
import { useId, useState, type ReactNode } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { inPageState } from '../../../../utils/inPageNavigation';
import {
  LOADING_CAPABILITY,
  findCluster,
  useK8sCapabilities,
  type K8sCapabilitiesValue,
} from '../../../../hooks/useK8sCapabilities';
import useAutomationRead from '../../../../hooks/useAutomationRead';
import type { UseEksDashboardReturn } from '../../../../hooks/useEksDashboard';
import type { AutomationReadResult } from '../../../../api/automationReadApi';
import type { ActionId, CapabilitiesDto, Capability, ReadinessKey } from '../../../../types/k8sAutomation';
import type { VerticalEngineView } from '../../../../types/verticalRightsizer';
import { AccessBanner } from '../../../CloudK8s/shared/AccessBanner';
import { StatusMark } from '../../../CloudK8s/shared/StatusMark';
import { NowBanner } from '../../../CloudK8s/shared/status/NowBanner';
import { HowItWorks } from '../../../CloudK8s/shared/status/HowItWorks';
import { FeatureMark, type Feature } from '../../../CloudK8s/shared/status/FeatureMark';
import { KpiCard } from '../../../CloudK8s/shared/status/KpiCard';
import { StatusChip } from '../../../CloudK8s/shared/status/StatusChip';
import { nowSentence, type AutomationOverviewInputs, type NowSentence } from '../../../CloudK8s/shared/status/nowSentence';
import { Bars } from '../../../CloudK8s/shared/charts/Bars';
import { ChartCard } from '../../../CloudK8s/shared/charts/ChartCard';
import { ChartDataTable } from '../../../CloudK8s/shared/charts/ChartDataTable';
import { LanesTimeline } from '../../../CloudK8s/shared/charts/LanesTimeline';
import { SeriesLegend } from '../../../CloudK8s/shared/charts/SeriesLegend';
import { CHECKING_ACCESS_TEXT, LockIcon, Remedies } from '../../../CloudK8s/shared/ReasonText';
import { alsoNeedsLine, reasonCopy } from '../../../CloudK8s/shared/reasonCopy';
import { formatAgo } from '../../../CloudK8s/shared/SourceLabel';
import {
  DAY_MS,
  automationKpis,
  formatCapacity,
  formatMemory,
  formatVcpu,
  howItWorksChips,
  hpaFeatureStatus,
  istShort,
  latestRecorded,
  namespaceRollup,
  nodeAutoscalerStatus,
  recentChanges,
  timelineLanes,
  vpaFeatureStatus,
  workloadAutoscalerStatus,
  type FeatureStatus,
  type Resource,
} from './automationDerivations';
import { CARD, FAINT, INK, InPageLink, MUTED, ReadFailureCard, readFailureText } from './automationUi';

/** The workload row while the vertical engine is switched off (V7). */
export const NOT_TURNED_ON_TEXT = 'Not turned on in this environment';
/** The Workload rightsizing row's Apply link (demo-cut review S2). */
export const WORKLOAD_AUTOSCALER_LINK = 'Automation › Workload autoscaler';

export interface TodayRow {
  id: string;
  label: string;
  /** What XamOps analyses today; null renders a dash. */
  analysis: string | null;
  /** Where the Apply column comes from: an action's capability, or a readiness key with no control yet. */
  apply: { actionId: ActionId } | { readiness: ReadinessKey };
  /** The vertical engine's row, replaced by {@link NOT_TURNED_ON_TEXT} while the engine is off. */
  vertical?: boolean;
  /** The Automation page that shows this analysis, linked under it (its {@link OverviewHrefs} key). */
  page?: { target: 'hpa' | 'vpa'; label: string };
}

/** §9.1 item 4, in display order. The HPA and VPA rows point at the pages that now show them. */
export const TODAY_ROWS: readonly TodayRow[] = [
  { id: 'workload-rightsizing', label: 'Workload rightsizing', analysis: 'Recommendations', apply: { actionId: 'vertical.apply' }, vertical: true },
  { id: 'node-consolidation', label: 'Node consolidation', analysis: 'Analysis not yet reliable', apply: { actionId: 'evictor.live' } },
  { id: 'node-optimizer', label: 'Node optimizer', analysis: 'Idle-node thresholds', apply: { actionId: 'nodeOptimizer.actions' } },
  { id: 'rebalancing', label: 'Rebalancing', analysis: null, apply: { actionId: 'rebalance.trigger' } },
  {
    id: 'hpa-manager',
    label: 'HPA manager',
    analysis: "Each HPA's limits, flags and conflicts",
    page: { target: 'hpa', label: 'Automation › HPA' },
    apply: { actionId: 'hpa.apply' },
  },
  { id: 'scale-out', label: 'Scale-out', analysis: 'Not built', apply: { actionId: 'scaleOut.settings' } },
  { id: 'node-templates', label: 'Node templates', analysis: 'Not built', apply: { actionId: 'nodeTemplates.edit' } },
  {
    id: 'vpa-baseline',
    label: 'VPA baseline',
    analysis: "Each Kubernetes VPA's target, next to XamOps's suggestion",
    page: { target: 'vpa', label: 'Automation › VPA' },
    apply: { readiness: 'vpa-apply' },
  },
  { id: 'spot-interruption', label: 'Spot interruption handling', analysis: 'Protective · state not shown yet', apply: { actionId: 'nodeOptimizer.settings' } },
];

/** A readiness key with no control of its own (VPA): ready, or its server message. */
function readinessCapability(caps: K8sCapabilitiesValue, key: ReadinessKey): Capability {
  if (caps.status === 'loading') return LOADING_CAPABILITY;
  if (caps.status === 'error' || !caps.data) return { allowed: false, editable: false, gate: { reason: 'UNKNOWN' }, also: [] };
  const readiness = caps.data.readiness?.[key];
  if (readiness?.ready) return { allowed: true, editable: true, gate: null, also: [] };
  return {
    allowed: false,
    editable: false,
    gate: { reason: 'CONTROLLER_NOT_READY', message: readiness?.message ?? undefined },
    also: [],
  };
}

function applyCapability(caps: K8sCapabilitiesValue, row: TodayRow, clusterName: string): Capability {
  return 'actionId' in row.apply
    ? caps.can(row.apply.actionId, { cluster: clusterName })
    : readinessCapability(caps, row.apply.readiness);
}

/** "data 4 min old", or "no data yet" when the agent reported no time. */
function dataAge(at: string | null, now: number): string {
  const ago = formatAgo(at, now);
  return ago ? `data ${ago.replace(/ ago$/, '')} old` : 'no data yet';
}

function ApplyCell({ capability }: { capability: Capability }) {
  if (capability.allowed) return <StatusMark state="healthy" label="Available" />;
  const gate = capability.gate;
  if (!gate) return <span className={MUTED}>{CHECKING_ACCESS_TEXT}</span>;
  const copy = reasonCopy(gate);
  const also = alsoNeedsLine(capability.also);
  return (
    <span className="flex items-start gap-1.5">
      <LockIcon className="mt-0.5" />
      <span className="min-w-0">
        <span className={`block ${INK}`} title={gate.raw}>
          {copy.heading && <span className="font-semibold">{copy.heading}: </span>}
          {copy.text}
        </span>
        {also && <span className={`mt-0.5 block text-[12px] ${MUTED}`}>{also}</span>}
      </span>
    </span>
  );
}

function AccessRow({ term, children }: { term: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-[160px_minmax(0,1fr)] gap-3 py-2.5 text-[13px]">
      <dt className={`font-semibold ${MUTED}`}>{term}</dt>
      <dd className={`min-w-0 ${INK}`}>{children}</dd>
    </div>
  );
}

const noteClass = `mt-1 block text-[12px] ${MUTED}`;

function ConnectionValue({ data, clusterName }: { data: CapabilitiesDto; clusterName: string }) {
  const connection = findCluster(data, clusterName)?.connection ?? data.connection;
  const readOnly = connection.accessLevel !== 'READ_WRITE';
  const verified = (connection.status ?? '').toUpperCase() === 'CONNECTED';
  const readOnlyCopy = reasonCopy({ reason: 'READ_ONLY_CONNECTION' });
  const unverifiedCopy = reasonCopy({ reason: 'CONNECTION_NOT_VERIFIED' });
  return (
    <>
      {readOnly ? <StatusMark state="not-set-up" label="Read-only" /> : <StatusMark state="healthy" label="Read-write" />}
      {readOnly && (
        <>
          <span className={noteClass}>{readOnlyCopy.text}</span>
          <Remedies remedies={readOnlyCopy.remedies} />
        </>
      )}
      {!verified && (
        <>
          <span className="mt-1 block"><StatusMark state="unverified" label="Not verified" /></span>
          <span className={noteClass}>{unverifiedCopy.text}</span>
          <Remedies remedies={unverifiedCopy.remedies} />
        </>
      )}
    </>
  );
}

function PermissionValue({ data }: { data: CapabilitiesDto }) {
  const { reasons, detail } = data.user;
  if (reasons.includes('NO_USER_PERMISSION')) {
    if (detail === 'ROLE') return <StatusMark state="not-set-up" label="View only: your role can't change automation" />;
    if (detail === 'IMPERSONATION') return <StatusMark state="not-set-up" label="View only while signed in as another user" />;
    return (
      <>
        <StatusMark state="not-set-up" label="View only" />
        <Remedies remedies={reasonCopy({ reason: 'NO_USER_PERMISSION', detail: 'TAB_GRANT' }).remedies} />
      </>
    );
  }
  if (reasons.includes('DEMO')) return <StatusMark state="not-set-up" label={reasonCopy({ reason: 'DEMO' }).text} />;
  return <StatusMark state="healthy" label="You can change automation" />;
}

function AgentValue({ data, clusterName, now }: { data: CapabilitiesDto; clusterName: string; now: number }) {
  const agent = findCluster(data, clusterName)?.agent;
  if (!agent || agent.status === 'NOT_INSTALLED') {
    return (
      <>
        <StatusMark state="not-set-up" label="Not installed" />
        <Remedies remedies={reasonCopy({ reason: 'AGENT_NOT_INSTALLED', detail: 'XAMOPS_AGENT' }).remedies} />
      </>
    );
  }
  if (agent.status === 'UNKNOWN') return <StatusMark state="unknown" label="Unknown" />;
  const detail = dataAge(agent.lastDataAt, now);
  return agent.status === 'STALE'
    ? <StatusMark state="attention" label="Stale" detail={detail} />
    : <StatusMark state="healthy" label="Connected" detail={detail} />;
}

function AccessBlock({ caps, clusterName }: { caps: K8sCapabilitiesValue; clusterName: string }) {
  const headingId = useId();
  const data = caps.status === 'ready' ? caps.data : null;
  const unknown = <StatusMark state="unknown" label="Unknown" />;
  return (
    // data-k8s-kit: every colour here has its dark variant, so the legacy dark CSS leaves it alone.
    <section aria-labelledby={headingId} className={`${CARD} p-4`} data-k8s-kit="">
      <h2 id={headingId} className={`text-[14px] font-semibold ${INK}`}>Access</h2>
      {caps.status === 'loading' ? (
        <p className={`mt-2 text-[13px] ${MUTED}`}>{CHECKING_ACCESS_TEXT}</p>
      ) : (
        <dl className="mt-1 divide-y divide-[rgba(0,0,0,0.08)] dark:divide-[rgba(255,255,255,0.08)]">
          <AccessRow term="Connection">{data ? <ConnectionValue data={data} clusterName={clusterName} /> : unknown}</AccessRow>
          <AccessRow term="Your permission">{data ? <PermissionValue data={data} /> : unknown}</AccessRow>
          <AccessRow term="XamOps agent">
            {data ? <AgentValue data={data} clusterName={clusterName} now={Date.now()} /> : unknown}
          </AccessRow>
        </dl>
      )}
    </section>
  );
}

function analysisCell(caps: K8sCapabilitiesValue, row: TodayRow): ReactNode {
  if (row.vertical && caps.status === 'error') return 'Unknown';
  if (row.analysis === null || (row.vertical && caps.status === 'loading')) {
    return (
      <>
        <span aria-hidden="true">—</span>
        <span className="sr-only">None</span>
      </>
    );
  }
  return row.analysis;
}

function TodayTable({
  caps,
  clusterName,
  workloadAutoscalerHref,
  pageHrefs = {},
}: {
  caps: K8sCapabilitiesValue;
  clusterName: string;
  workloadAutoscalerHref?: string;
  /** The HPA and VPA pages, linked under the rows they show. */
  pageHrefs?: { hpa?: string; vpa?: string };
}) {
  const headingId = useId();
  const location = useLocation();
  const engineOff = caps.status === 'ready' && !caps.verticalEngineEnabled;
  return (
    // data-k8s-kit: every colour here has its dark variant, so the legacy dark CSS leaves it alone.
    <section aria-labelledby={headingId} className={`${CARD} pt-4`} data-k8s-kit="">
      <h2 id={headingId} className={`px-4 text-[14px] font-semibold ${INK}`}>
        What automation can do on this cluster today
      </h2>
      <p className={`px-4 pt-1 text-[12px] ${MUTED}`}>
        Analysis is what XamOps reads and recommends. Apply is whether XamOps may change the cluster.
      </p>
      <div className="mt-3 overflow-x-auto">
        <table aria-labelledby={headingId} className="w-full border-collapse text-left text-[13px]">
          <thead>
            <tr className={`border-y border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.03)] text-[12px] dark:border-[rgba(255,255,255,0.1)] dark:bg-[rgba(255,255,255,0.03)] ${MUTED}`}>
              <th scope="col" className="w-[22%] px-4 py-2 font-semibold">Capability</th>
              <th scope="col" className="w-[28%] px-4 py-2 font-semibold">Analysis</th>
              <th scope="col" className="px-4 py-2 font-semibold">Apply</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[rgba(0,0,0,0.08)] dark:divide-[rgba(255,255,255,0.08)]">
            {TODAY_ROWS.map((row) => (
              <tr key={row.id} data-row={row.id} className="align-top">
                <th scope="row" className={`px-4 py-2.5 font-semibold ${INK}`}>{row.label}</th>
                {row.vertical && engineOff ? (
                  <td colSpan={2} className={`px-4 py-2.5 ${MUTED}`}>{NOT_TURNED_ON_TEXT}</td>
                ) : (
                  <>
                    <td className={`px-4 py-2.5 ${MUTED}`}>
                      {analysisCell(caps, row)}
                      {row.page && pageHrefs[row.page.target] && (
                        <span className="mt-0.5 block text-[12px]" data-analysis-page={row.page.target}>
                          <InPageLink to={pageHrefs[row.page.target]!}>{row.page.label}</InPageLink>
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-2.5">
                      {row.vertical && workloadAutoscalerHref ? (
                        <span className={INK} data-apply="workload-autoscaler">
                          <span>{'Decided per workload in '}</span>
                          <Link
                            to={workloadAutoscalerHref}
                            state={inPageState(location)}
                            className="font-semibold underline-offset-2 hover:underline outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.88)] focus-visible:ring-offset-2"
                            data-k8s-kit=""
                          >
                            {WORKLOAD_AUTOSCALER_LINK}
                          </Link>
                        </span>
                      ) : (
                        <ApplyCell capability={applyCapability(caps, row, clusterName)} />
                      )}
                    </td>
                  </>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

// ─── Endpoint-1 blocks ───────────────────────────────────────────────────────

/** Why endpoint 1 lists no workload, in words for the Now sentence. */
function noneReason(view: VerticalEngineView): string {
  const has = (code: string) => view.gateReasons.some((g) => g.kind === 'known' && g.code === code);
  if (!view.engine.enabled) return 'the Workload autoscaler is off in this environment';
  if (has('NO_METRICS_SOURCE')) return 'there is no usage data from Prometheus in the cluster';
  if (has('CLUSTER_ACCESS_NOT_CONFIGURED')) return "XamOps can't sign in to the cluster";
  if (has('CLUSTER_UNREACHABLE')) return "XamOps can't reach the cluster's API server";
  return 'it found no Deployments or StatefulSets outside the system namespaces';
}

function overviewSentence(clusterName: string, result: AutomationReadResult | null, loading: boolean, now: number): NowSentence {
  if (loading && !result) return { clauses: ['Reading what XamOps is doing on this cluster…'], chips: [] };
  if (result?.kind !== 'ok') return nowSentence('automation-overview', { cluster: clusterName });
  const v = result.view.vertical;
  const k = automationKpis(v.workloads, now);
  const latest = latestRecorded(v.workloads);
  const inputs: AutomationOverviewInputs = {
    cluster: clusterName,
    watching: v.workloads.length,
    ready: k.overProvisioned,
    readyFreed: k.canSave.counted > 0 ? formatCapacity(k.canSave.cpuM, k.canSave.memMi) : undefined,
    needMore: k.needsMore,
    autoApplyCount: v.workloads.filter((w) => w.policy?.mode === 'AUTO').length,
    latestRecorded: latest
      ? { time: istShort(latest.at), workload: latest.workload.ref.name, result: latest.result.label.charAt(0).toLowerCase() + latest.result.label.slice(1) }
      : undefined,
    noneReason: noneReason(v),
  };
  return nowSentence('automation-overview', inputs);
}

function Kpis({ view, now }: { view: VerticalEngineView; now: number }) {
  const k = automationKpis(view.workloads, now);
  const unknownSave = k.overProvisioned > 0 && k.canSave.counted === 0;
  return (
    <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4" data-overview-kpis="">
      {/* "Applied live", not "Saving now": endpoint 1's lastAction has no before and after, so a raise
          for a workload that needed more counts here too (the Workloads page's "Applied"). */}
      <KpiCard tone="good" icon="mdi:check-circle" label="Applied live" value={k.liveApplied}>
        <p>{k.liveApplied === 1 ? 'workload' : 'workloads'} whose latest change was applied live</p>
        <p>
          Before and after: <span title="Not recorded yet: XamOps keeps the size before a change once the activity feed is on.">—</span>, recorded
          once the activity feed is on
        </p>
      </KpiCard>
      <KpiCard tone="warn" icon="mdi:arrow-collapse-down" label="Can save now" value={unknownSave ? '—' : formatVcpu(k.canSave.cpuM)}>
        <p>{unknownSave ? "The replica counts aren't known, so the amount isn't either" : `${formatMemory(k.canSave.memMi)} memory`}</p>
        <p>{k.overProvisioned} over-provisioned {k.overProvisioned === 1 ? 'workload' : 'workloads'}, waiting for you</p>
        {k.notCounted > 0 && !unknownSave && <p>{k.notCounted} not counted: replicas or pod sizes unknown</p>}
      </KpiCard>
      <KpiCard tone="warn" icon="mdi:speedometer" label="Needs more" value={k.needsMore}>
        <p>{k.needsMore > 0 ? `+${formatCapacity(k.needsMoreAdds.cpuM, k.needsMoreAdds.memMi)} added if applied` : 'No workload needs more'}</p>
      </KpiCard>
      <KpiCard tone="info" icon="mdi:history" label="Changes, last 7 days" value={k.changes7d}>
        <p>latest change per workload</p>
        {k.changes7dByResult.length > 0 && (
          <div className="flex flex-wrap gap-1 pt-0.5">
            {k.changes7dByResult.map((r) => (
              <StatusChip key={r.key} tone={r.tone} icon={r.icon} label={`${r.label}: ${r.count}`} weight="normal" />
            ))}
          </div>
        )}
      </KpiCard>
    </div>
  );
}

function Timeline({ result, now }: { result: AutomationReadResult | null; now: number }) {
  const view = result?.kind === 'ok' ? result.view.vertical : null;
  const changes = view ? recentChanges(view.workloads, now) : [];
  const lanes = timelineLanes(changes, now);
  const results = [...new Map(changes.map((c) => [c.result.key, c.result])).values()];
  const state = !view
    ? { kind: 'unavailable' as const, reason: result && result.kind !== 'ok' ? `${readFailureText(result).title}, so there is nothing to place.` : 'Reading the Workload autoscaler…' }
    : changes.length === 0
      ? { kind: 'unavailable' as const, reason: 'XamOps recorded no change to any workload in the last 7 days.' }
      : { kind: 'ok' as const };
  return (
    <ChartCard
      title="What automation did, last 7 days"
      subline="Shows only the latest change XamOps recorded for each workload. The full history arrives with the activity feed."
      source="Workload autoscaler (endpoint 1, GET /api/eks/automation/vertical/{cluster}): each workload's lastAction, 7 days."
      state={state}
      table={
        changes.length > 0 ? (
          <ChartDataTable
            caption="Latest change per workload, last 7 days"
            rowHeader="Workload"
            columns={[{ key: 'when', header: 'When' }, { key: 'result', header: 'Result' }, { key: 'kind', header: 'Live or test' }]}
            rows={changes.map((c) => ({
              key: c.workload.id,
              label: c.workload.ref.name,
              values: { when: istShort(c.at), result: c.result.label, kind: c.live ? 'Live' : 'Test run' },
            }))}
          />
        ) : undefined
      }
    >
      <div className="overflow-x-auto">
        <LanesTimeline lanes={lanes} width={720} ariaLabel="The latest change XamOps recorded for each workload, last 7 days" />
        <div className={`ml-[120px] flex w-[600px] justify-between text-[11px] ${FAINT}`} aria-hidden="true">
          <span>{istShort(now - 7 * DAY_MS)}</span>
          <span>now</span>
        </div>
      </div>
      <div className="mt-2 flex flex-wrap gap-1.5">
        {results.map((r) => (
          <StatusChip key={r.key} tone={r.tone} icon={r.icon} label={r.label} weight="normal" />
        ))}
      </div>
    </ChartCard>
  );
}

interface CardLink {
  href?: string;
  label: string;
}

function FeatureCard({ feature, status, link }: { feature: Feature; status: FeatureStatus; link: CardLink }) {
  return (
    <section className={`${CARD} flex flex-col gap-2 px-4 py-3`} data-feature-card={feature} data-k8s-kit="">
      <FeatureMark feature={feature} />
      <span><StatusChip tone={status.tone} icon={status.icon} label={status.label} /></span>
      {status.lines.length > 0 && (
        <ul className={`space-y-0.5 text-[12px] ${MUTED}`}>
          {status.lines.map((line) => <li key={line}>{line}</li>)}
        </ul>
      )}
      {link.href && (
        <span className="mt-auto pt-1 text-[12px]">
          <InPageLink to={link.href}>{link.label}</InPageLink>
        </span>
      )}
    </section>
  );
}

function FeatureCards({
  result,
  caps,
  clusterName,
  eksData,
  hrefs,
  now,
}: {
  result: AutomationReadResult | null;
  caps: K8sCapabilitiesValue;
  clusterName: string;
  eksData?: UseEksDashboardReturn;
  hrefs: OverviewHrefs;
  now: number;
}) {
  const dashboard = eksData?.dashboard ?? null;
  const ok = result?.kind === 'ok' ? result.view : null;
  let workload: FeatureStatus;
  if (!result) workload = { tone: 'off', icon: 'mdi:progress-clock', label: 'Checking', lines: [] };
  else if (result.kind === 'ok') workload = workloadAutoscalerStatus(result.view.vertical, now);
  else if (result.kind === 'refused' && result.code.kind === 'known' && ['CLUSTER_ACCESS_NOT_CONFIGURED', 'CLUSTER_UNREACHABLE'].includes(result.code.code)) {
    workload = { tone: 'bad', icon: 'mdi:lan-disconnect', label: "Can't reach the cluster", lines: [readFailureText(result).title] };
  } else workload = { tone: 'off', icon: 'mdi:help-circle-outline', label: 'Not checked', lines: [readFailureText(result).title] };
  const capsCluster = caps.status === 'ready' ? caps.forCluster(clusterName) : null;
  return (
    <section aria-label="Automation features on this cluster" className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
      <FeatureCard feature="workload" status={workload} link={{ href: hrefs.workloadAutoscaler, label: 'Open Workloads' }} />
      <FeatureCard feature="hpa" status={hpaFeatureStatus(ok?.hpa ?? null, dashboard)} link={{ href: hrefs.hpa, label: 'Open HPA' }} />
      <FeatureCard feature="vpa" status={vpaFeatureStatus(ok?.hpa ?? null, ok?.vertical ?? null)} link={{ href: hrefs.vpa, label: 'Open VPA' }} />
      <FeatureCard
        feature="node"
        status={nodeAutoscalerStatus(caps.status, capsCluster, dashboard)}
        link={{ href: hrefs.nodeAutoscaler, label: hrefs.nodeAutoscalerLabel ?? 'Open Node autoscaler' }}
      />
    </section>
  );
}

const NAMESPACE_BARS = [
  { dataKey: 'requested', name: 'Requested', kind: 'requested' as const },
  { dataKey: 'used', name: 'Used', kind: 'used' as const },
  { dataKey: 'suggested', name: 'Suggested', kind: 'suggested' as const },
];

function NamespaceChart({ result, eksData }: { result: AutomationReadResult | null; eksData?: UseEksDashboardReturn }) {
  const [resource, setResource] = useState<Resource>('cpu');
  const view = result?.kind === 'ok' ? result.view.vertical : null;
  const rollup = view ? namespaceRollup(view.workloads, eksData?.dashboard, resource) : null;
  const cpu = resource === 'cpu';
  const unit = (v: number | null) => (v === null ? null : cpu ? Number((v / 1000).toFixed(3)) : Number((v / 1024).toFixed(3)));
  const text = (v: number | null) => (v === null ? '—' : cpu ? formatVcpu(v) : formatMemory(v));
  let state: { kind: 'ok' } | { kind: 'unavailable'; reason: string } = { kind: 'ok' };
  if (!view) state = { kind: 'unavailable', reason: result && result.kind !== 'ok' ? `${readFailureText(result).title}, so the analysed namespaces aren't known.` : 'Reading the Workload autoscaler…' };
  else if (!rollup?.agentPods) state = { kind: 'unavailable', reason: "The XamOps agent hasn't reported this cluster's pods, so requests by namespace aren't known." };
  else if (rollup.rows.length === 0) state = { kind: 'unavailable', reason: 'No analysed workloads, so no namespace to compare.' };
  const rows = rollup?.rows ?? [];
  return (
    <ChartCard
      title={cpu ? 'CPU by namespace: requested, used and suggested' : 'Memory by namespace: requested, used and suggested'}
      subline="Only namespaces with analysed workloads. Suggested is what the namespace would request with every XamOps suggestion applied; pods XamOps doesn't size stay at their request."
      source="Requested: the XamOps agent's pods (k8sPods). Used: the XamOps agent's namespace use (namespaceUtilization). Suggested: Workload autoscaler (endpoint 1), (request now − suggested) × replicas."
      state={state}
      table={
        rows.length > 0 && state.kind === 'ok' ? (
          <ChartDataTable
            caption={cpu ? 'CPU by namespace' : 'Memory by namespace'}
            rowHeader="Namespace"
            columns={NAMESPACE_BARS.map((b) => ({ key: b.dataKey, header: b.name }))}
            rows={rows.map((r) => ({ key: r.namespace, label: r.namespace, values: { requested: text(r.requested), used: text(r.used), suggested: text(r.suggested) } }))}
          />
        ) : undefined
      }
    >
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <SeriesLegend items={NAMESPACE_BARS.map((b) => ({ label: b.name, kind: b.kind }))} />
        <div role="group" aria-label="Resource" className="inline-flex overflow-hidden rounded-lg border border-[rgba(0,0,0,0.12)] dark:border-[rgba(255,255,255,0.16)]">
          {(['cpu', 'memory'] as const).map((r) => (
            <button
              key={r}
              type="button"
              aria-pressed={resource === r}
              onClick={() => setResource(r)}
              className={`px-3 py-1 text-[12px] font-semibold ${resource === r ? 'bg-[rgba(0,0,0,0.88)] text-white dark:bg-[rgba(255,255,255,0.92)] dark:text-black' : `bg-white dark:bg-[#141414] ${MUTED}`}`}
            >
              {r === 'cpu' ? 'CPU' : 'Memory'}
            </button>
          ))}
        </div>
      </div>
      <Bars
        mode="grouped"
        data={rows.map((r) => ({ namespace: r.namespace, requested: unit(r.requested), used: unit(r.used), suggested: unit(r.suggested) }))}
        bars={NAMESPACE_BARS}
        xKey="namespace"
        height={240}
        ariaLabel={cpu ? 'CPU by namespace, in vCPU' : 'Memory by namespace, in GiB'}
      />
      <p className={`mt-1 text-[11px] ${FAINT}`}>{cpu ? 'vCPU' : 'GiB'}</p>
    </ChartCard>
  );
}

export interface OverviewHrefs {
  /** Automation › Workload autoscaler › Workloads. */
  workloadAutoscaler?: string;
  /** Workload autoscaler › Policies, the How it works link. */
  policies?: string;
  hpa?: string;
  vpa?: string;
  /** Automation › Node autoscaler, the Node autoscaler card's next step. */
  nodeAutoscaler?: string;
  nodeAutoscalerLabel?: string;
}

export interface OverviewPanelProps {
  clusterName: string;
  accountId: string;
  region: string;
  /** The page's dashboard (the XamOps agent's view): namespace requests and use, HPAs, node autoscalers. */
  eksData?: UseEksDashboardReturn;
  /** Links to the Automation pages the cards and table point at. */
  hrefs?: OverviewHrefs;
}

export function OverviewPanel({ clusterName, accountId, region, eksData, hrefs = {} }: OverviewPanelProps) {
  const caps = useK8sCapabilities();
  const read = useAutomationRead({ clusterName, accountId, region });
  const location = useLocation();
  const now = Date.now();
  const view = read.result?.kind === 'ok' ? read.result.view.vertical : null;
  const sentence = overviewSentence(clusterName, read.result, read.loading, now);

  return (
    <div className="space-y-4">
      <AccessBanner capabilities={caps} cluster={clusterName} />
      <NowBanner sentence={sentence} />
      {read.loading && !read.result && <div className="skeleton h-28 rounded-lg" />}
      {read.result && read.result.kind !== 'ok' && <ReadFailureCard failure={read.result} onRetry={read.reload} />}
      {view && <Kpis view={view} now={now} />}
      <HowItWorks
        chips={view ? howItWorksChips(view, now) : {}}
        policiesHref={hrefs.policies}
        policiesLinkState={inPageState(location)}
      />
      <Timeline result={read.result} now={now} />
      <FeatureCards result={read.result} caps={caps} clusterName={clusterName} eksData={eksData} hrefs={hrefs} now={now} />
      <NamespaceChart result={read.result} eksData={eksData} />
      <ChartCard
        title="CPU and memory over time"
        source="Usage feed (planned): requested and used by namespace from Prometheus in your cluster (kube-state-metrics and cAdvisor), 3 days at 1-hour steps."
        state={{ kind: 'history-pending', from: 'It will come from Prometheus in your cluster: requested and used CPU and memory, over the last 3 days.' }}
      />
      <AccessBlock caps={caps} clusterName={clusterName} />
      <TodayTable
        caps={caps}
        clusterName={clusterName}
        workloadAutoscalerHref={hrefs.workloadAutoscaler}
        pageHrefs={{ hpa: hrefs.hpa, vpa: hrefs.vpa }}
      />
    </div>
  );
}

export default OverviewPanel;
