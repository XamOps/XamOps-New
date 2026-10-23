/**
 * Automation › Overview (UI v3 plan §2 "Overview"; design-delta §3.1, §4.1, §4.2; glossary §3, §4,
 * §10). What XamOps can automate on this cluster, what it is doing now and what it has done, each
 * part from a real source, read once:
 *  - endpoint 1 (`useAutomationRead`): the Now sentence, the KPI row, How it works, the feature
 *    cards' Workload autoscaler, HPA and VPA state, which workloads are in place, and the suggested
 *    side of the namespace chart;
 *  - the activity log (FEAT-007, its last 7 days): the 7-day timeline and what Saving now frees; and
 *    its per-day summary (FEAT-005): Changes, last 7 days. A backend without the log keeps each
 *    workload's latest change from endpoint 1, and says so;
 *  - the usage feed (FEAT-004): the cluster's CPU and memory over 3 days, and each namespace's
 *    average use;
 *  - the XamOps agent's dashboard (`eksData`): requested by namespace (and used, without the usage
 *    feed), HPAs, who changes the node count;
 *  - capabilities: the access overlays, the Access block and "What automation can do on this
 *    cluster today" (one row per capability, with the server's message for anything not ready).
 * When the read carries the FEAT-002 cost roll-up, the KPI row leads with the cluster's real monthly
 * cost (and "Can save now" with its $/mo when a recommendation frees money); without it, savings fall
 * back to freed vCPU and memory, never a fabricated $0. "Saving now" stays in vCPU and memory: the
 * roll-up prices what a suggestion would still free, not what a change already freed.
 */
import { useId, useMemo, useState, type ReactNode } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { inPageState } from '../../../../utils/inPageNavigation';
import { formatMonthlyUsd } from '../../../../api/verticalRightsizerAdapter';
import { formatMoney } from '../../../../utils/k8sQuantities';
import {
  LOADING_CAPABILITY,
  findCluster,
  useK8sCapabilities,
  type K8sCapabilitiesValue,
} from '../../../../hooks/useK8sCapabilities';
import useAutomationRead from '../../../../hooks/useAutomationRead';
import useAutomationActivity, { useActivitySummary, type UseAutomationActivityState } from '../../../../hooks/useAutomationActivity';
import useAutomationUsage from '../../../../hooks/useAutomationUsage';
import type { InsightReadState } from '../../../../hooks/useInsightRead';
import type { UseEksDashboardReturn } from '../../../../hooks/useEksDashboard';
import type { AutomationReadResult } from '../../../../api/automationReadApi';
import type { ActionId, CapabilitiesDto, Capability, ReadinessKey } from '../../../../types/k8sAutomation';
import type { VerticalEngineView } from '../../../../types/verticalRightsizer';
import type { ActivitySummaryView } from '../../../../types/automationActivity';
import { AccessBanner } from '../../../CloudK8s/shared/AccessBanner';
import { STATUS_TONES, type StatusState } from '../../../CloudK8s/shared/StatusMark';
import { NowBanner } from '../../../CloudK8s/shared/status/NowBanner';
import { HowItWorks } from '../../../CloudK8s/shared/status/HowItWorks';
import { FeatureMark, type Feature } from '../../../CloudK8s/shared/status/FeatureMark';
import { Delta } from '../../../CloudK8s/shared/status/Delta';
import { StatusDot } from '../../../CloudK8s/shared/status/StatusDot';
import { PageTemplate } from '../../../CloudK8s/shared/PageTemplate';
import { MetricStrip, type MetricCell } from '../../../CloudK8s/shared/MetricStrip';
import { DataTable } from '../../../CloudK8s/shared/DataTable';
import type { ColumnDef } from '../../../CloudK8s/shared/K8sDataTable';
import type { DrawerSection, DrawerDatum } from '../../../CloudK8s/shared/DetailDrawer';
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
import { CARD, INK, InPageLink, MUTED, ReadFailureCard, readFailureText } from './automationUi';
import { dayLabel, feedTimeline, feedWeekCounts, savingNow, weekCounts, type WeekCounts } from './activity/activityInsights';
import { activityResultChip } from './activity/activityText';
import { ClusterUsageCard, ResourceToggle } from './insightCards';
import { sourceService } from './insightCharts';

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

/**
 * The Apply column's compact mark (§14): a status word with its icon, never colour alone. The full
 * gate / reason / RCA copy lives in the row's DetailDrawer (opened by activating the row), not in
 * the cell — the table stays a scannable grid and the detail reads in the drawer.
 */
function ApplyCell({ capability }: { capability: Capability }) {
  if (capability.allowed) return <StatusDot tone="good" label="Available" />;
  const gate = capability.gate;
  if (!gate) return <span className={MUTED}>{CHECKING_ACCESS_TEXT}</span>;
  return (
    <span className="inline-flex items-center gap-1.5" title={gate.raw}>
      <LockIcon />
      <StatusDot tone="off" label="Blocked" />
    </span>
  );
}

/** The drawer's Apply section for a gated row: the gate heading, text, "Also needs" and raw reason. */
function applyDrawerData(capability: Capability): DrawerDatum[] {
  if (capability.allowed) {
    return [{ label: 'Apply', value: 'Available — XamOps may make this change on the cluster.' }];
  }
  const gate = capability.gate;
  if (!gate) return [{ label: 'Apply', value: CHECKING_ACCESS_TEXT }];
  const copy = reasonCopy(gate);
  const also = alsoNeedsLine(capability.also);
  return [
    { label: 'Status', value: 'Blocked' },
    { label: copy.heading ?? 'Reason', value: copy.text },
    { label: 'Also needs', value: also ?? null, reason: 'Nothing else is required.' },
    { label: 'Server reason', value: gate.raw ?? null, reason: 'XamOps resolved a known reason, with no raw server message.' },
  ];
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

/**
 * The Access card's routine status as the quiet {@link StatusDot} (dot + word), never a filled
 * tinted pill (owner debox review): the dot is the one spot of meaning colour, the word carries the
 * meaning for anyone who can't see it, and an optional `detail` (a data age) rides in the muted
 * grey. The state → tone mapping is the same one {@link StatusMark} uses.
 */
function AccessStatus({ state, label, detail }: { state: StatusState; label: string; detail?: string | null }) {
  return <StatusDot tone={STATUS_TONES[state]} label={label} detail={detail} />;
}

function ConnectionValue({ data, clusterName }: { data: CapabilitiesDto; clusterName: string }) {
  const connection = findCluster(data, clusterName)?.connection ?? data.connection;
  const readOnly = connection.accessLevel !== 'READ_WRITE';
  const verified = (connection.status ?? '').toUpperCase() === 'CONNECTED';
  const readOnlyCopy = reasonCopy({ reason: 'READ_ONLY_CONNECTION' });
  const unverifiedCopy = reasonCopy({ reason: 'CONNECTION_NOT_VERIFIED' });
  return (
    <>
      {readOnly ? <AccessStatus state="not-set-up" label="Read-only" /> : <AccessStatus state="healthy" label="Read-write" />}
      {readOnly && (
        <>
          <span className={noteClass}>{readOnlyCopy.text}</span>
          <Remedies remedies={readOnlyCopy.remedies} />
        </>
      )}
      {!verified && (
        <>
          <span className="mt-1 block"><AccessStatus state="unverified" label="Not verified" /></span>
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
    if (detail === 'ROLE') return <AccessStatus state="not-set-up" label="View only: your role can't change automation" />;
    if (detail === 'IMPERSONATION') return <AccessStatus state="not-set-up" label="View only while signed in as another user" />;
    return (
      <>
        <AccessStatus state="not-set-up" label="View only" />
        <Remedies remedies={reasonCopy({ reason: 'NO_USER_PERMISSION', detail: 'TAB_GRANT' }).remedies} />
      </>
    );
  }
  if (reasons.includes('DEMO')) return <AccessStatus state="not-set-up" label={reasonCopy({ reason: 'DEMO' }).text} />;
  return <AccessStatus state="healthy" label="You can change automation" />;
}

function AgentValue({ data, clusterName, now }: { data: CapabilitiesDto; clusterName: string; now: number }) {
  const agent = findCluster(data, clusterName)?.agent;
  if (!agent || agent.status === 'NOT_INSTALLED') {
    return (
      <>
        <AccessStatus state="not-set-up" label="Not installed" />
        <Remedies remedies={reasonCopy({ reason: 'AGENT_NOT_INSTALLED', detail: 'XAMOPS_AGENT' }).remedies} />
      </>
    );
  }
  if (agent.status === 'UNKNOWN') return <AccessStatus state="unknown" label="Unknown" />;
  const detail = dataAge(agent.lastDataAt, now);
  return agent.status === 'STALE'
    ? <AccessStatus state="attention" label="Stale" detail={detail} />
    : <AccessStatus state="healthy" label="Connected" detail={detail} />;
}

function AccessBlock({ caps, clusterName }: { caps: K8sCapabilitiesValue; clusterName: string }) {
  const headingId = useId();
  const data = caps.status === 'ready' ? caps.data : null;
  const unknown = <AccessStatus state="unknown" label="Unknown" />;
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

/** The analysis a row shows in its Analysis cell, as plain text (never a fabricated value). */
function analysisText(caps: K8sCapabilitiesValue, row: TodayRow): string {
  if (row.vertical && caps.status === 'error') return 'Unknown';
  if (row.analysis === null || (row.vertical && caps.status === 'loading')) return '—';
  return row.analysis;
}

/** How this analysis is calculated / where Apply comes from — the §7 provenance, in the drawer. */
function howCalculatedData(row: TodayRow): DrawerDatum[] {
  return [
    {
      label: 'Analysis',
      value: 'Analysis is what XamOps reads and recommends from the cluster; it changes nothing on its own.',
    },
    {
      label: 'Apply',
      value: 'Apply is whether XamOps may make this change — your permission, the connection and the controller’s readiness, checked live.',
    },
    row.page ? { label: 'Shown on', value: row.page.label } : { label: 'Shown on', value: null, reason: 'This capability has no dedicated page yet.' },
  ];
}

/**
 * "What automation can do on this cluster today" (§9.1): one row per capability in DataTable, each
 * row opening a DetailDrawer with its Capability, Apply (the gate / RCA / reason copy) and
 * How-this-is-calculated sections. Analysis is what XamOps reads and recommends; Apply is whether
 * XamOps may change the cluster.
 */
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

  const columns: ColumnDef<TodayRow>[] = [
    {
      id: 'capability',
      header: 'Capability',
      width: '28%',
      cell: (row) => <span className={`font-semibold ${INK}`}>{row.label}</span>,
      sortValue: (row) => row.label,
    },
    {
      id: 'analysis',
      header: 'Analysis',
      width: '34%',
      cell: (row) => {
        if (row.vertical && engineOff) return <span className={MUTED}>{NOT_TURNED_ON_TEXT}</span>;
        const text = analysisText(caps, row);
        return (
          <span className={MUTED} data-analysis={row.id}>
            {text === '—' ? <><span aria-hidden="true">—</span><span className="sr-only">None</span></> : text}
            {row.page && pageHrefs[row.page.target] && (
              <span className="mt-0.5 block text-[12px]" data-analysis-page={row.page.target}>
                <InPageLink to={pageHrefs[row.page.target]!}>{row.page.label}</InPageLink>
              </span>
            )}
          </span>
        );
      },
    },
    {
      id: 'apply',
      header: 'Apply',
      cell: (row) => {
        if (row.vertical && engineOff) return <span aria-hidden="true" className={MUTED}>—</span>;
        if (row.vertical && workloadAutoscalerHref) {
          return (
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
          );
        }
        return <ApplyCell capability={applyCapability(caps, row, clusterName)} />;
      },
    },
  ];

  const rowToSections = (row: TodayRow): DrawerSection[] => {
    const capabilitySection: DrawerSection = {
      title: 'Capability',
      data: [
        { label: 'Analysis', value: row.vertical && engineOff ? NOT_TURNED_ON_TEXT : analysisText(caps, row) === '—' ? null : analysisText(caps, row), reason: 'XamOps reads nothing to recommend here yet.' },
        row.page && pageHrefs[row.page.target]
          ? { label: 'Automation page', value: <InPageLink to={pageHrefs[row.page.target]!}>{row.page.label}</InPageLink> }
          : { label: 'Automation page', value: row.page ? row.page.label : null, reason: 'This capability has no dedicated page yet.' },
      ],
    };
    const applySection: DrawerSection =
      row.vertical && (engineOff || workloadAutoscalerHref)
        ? {
          title: 'Apply',
          data: engineOff
            ? [{ label: 'Apply', value: NOT_TURNED_ON_TEXT }]
            : [{
              label: 'Apply',
              value: workloadAutoscalerHref
                ? <span>{'Decided per workload in '}<InPageLink to={workloadAutoscalerHref}>{WORKLOAD_AUTOSCALER_LINK}</InPageLink></span>
                : null,
            }],
        }
        : { title: 'Apply', data: applyDrawerData(applyCapability(caps, row, clusterName)) };
    return [
      capabilitySection,
      applySection,
      { title: 'How this is calculated', data: howCalculatedData(row) },
    ];
  };

  return (
    // data-k8s-kit: every colour here has its dark variant, so the legacy dark CSS leaves it alone.
    <section aria-labelledby={headingId} className="flex flex-col gap-2" data-k8s-kit="">
      <h2 id={headingId} className={`text-[14px] font-semibold ${INK}`}>
        What automation can do on this cluster today
      </h2>
      <p className={`text-[12px] ${MUTED}`}>
        Analysis is what XamOps reads and recommends. Apply is whether XamOps may change the cluster. Open a row for the detail.
      </p>
      <DataTable<TodayRow>
        rows={TODAY_ROWS}
        columns={columns}
        rowKey={(row) => row.id}
        rowLabel={(row) => row.label}
        caption="What automation can do on this cluster today"
        rowTitle={(row) => row.label}
        rowToSections={rowToSections}
      />
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

/** A MetricStrip sub-line: the SECONDARY-sized supporting copy that sat under a KpiCard's number. */
function Sub({ children }: { children: ReactNode }) {
  return <span className="flex flex-col gap-0.5">{children}</span>;
}

/**
 * "Saving now" (glossary §10): what the changes in place free, in vCPU and memory. Its $ is a dash:
 * the FEAT-002 cost read prices what a suggestion would still free, not capacity already freed.
 * Which workloads are in place comes from endpoint 1; each one's amount from the activity log's
 * before and after for that same change. Unknown amounts are left out and counted as such.
 *
 * The cell renders ONE short supporting sentence (owner polish review): when a change is in place it
 * names what it frees; when none is, it reads as a clean, deliberate empty state next to the dash.
 */
function savingNowCell({ view, feed }: { view: VerticalEngineView; feed: UseAutomationActivityState }): MetricCell {
  const s = savingNow(view.workloads, feed.entries);
  const pods = s.counted.reduce((n, c) => n + c.pods, 0);
  const who = s.counted.length === 1 ? s.counted[0].workload.ref.name : `${s.counted.length} workloads`;

  // The honest empty-state line: a dash plus ONE muted sentence that says why nothing is counted.
  const emptyLine =
    s.inPlace === 0
      ? 'No savings recorded yet.'
      : s.inPlace === 1
        ? "1 change is in place, but its earlier size isn't recorded."
        : `${s.inPlace} changes are in place, but their earlier sizes aren't recorded.`;

  return {
    label: 'Saving now',
    value: s.counted.length > 0 ? formatVcpu(s.cpuM) : '—',
    sub: (
      <Sub>
        {s.counted.length > 0 ? (
          <span>{`${who}, in place on ${pods} ${pods === 1 ? 'pod' : 'pods'}.`}</span>
        ) : (
          <span className={MUTED}>{emptyLine}</span>
        )}
      </Sub>
    ),
  };
}

/** "Changes, last 7 days": per IST day from the summary, else the feed's entries, else endpoint 1. */
function changesCell({ k, feed, summary, now }: {
  k: ReturnType<typeof automationKpis>;
  feed: UseAutomationActivityState;
  summary: InsightReadState<ActivitySummaryView>;
  now: number;
}): MetricCell {
  let week: WeekCounts | null = null;
  let basis: string;
  if (summary.result?.kind === 'ok' && (week = weekCounts(summary.result.view)) !== null) {
    basis = week.since ? `counted per IST day since ${dayLabel(week.since)}, when XamOps began recording every kind of change` : 'counted per IST day, by result';
  } else if (feed.result?.kind === 'ok') {
    week = feedWeekCounts(feed.entries, now);
    basis = feed.hasOlder ? `the newest ${feed.entries.length} entries in the activity log` : 'entries in the activity log';
  } else {
    basis = 'latest change per workload';
  }
  const byResult = week ? week.byResult : k.changes7dByResult;
  const total = week ? week.total : k.changes7d;
  return {
    label: 'Changes, last 7 days',
    value: total,
    sub: (
      <Sub>
        {byResult.length > 0 && (
          <span className="flex flex-wrap gap-x-4 gap-y-1 pt-0.5">
            {byResult.map((r) => (
              <StatusDot key={r.key} tone={r.tone} label={`${r.label}: ${r.count}`} />
            ))}
          </span>
        )}
        {week && week.testRuns > 0 && (
          <span>{`${week.testRuns} of the ${week.total} ${week.testRuns === 1 ? 'was a test run' : 'were test runs'}: nothing was sent.`}</span>
        )}
        <span>{basis}</span>
      </Sub>
    ),
  };
}

/**
 * The automation metric strip (DESIGN §4.1): automation's own figures — the cluster's monthly cost
 * (when the FEAT-002 roll-up prices it) or Can save now as the lead cell, then Saving now / Applied
 * live, Can save now, Needs more and Changes. Each cell is one FIGURE; placement, not a bigger
 * number, sets the hierarchy. The Monthly cost cell is wired for a Trend chip but shows none while
 * CostSummaryView.previousClusterMonthlyCost is null — there is no prior-period source yet (no cost
 * snapshot), so no prior exists to compare against and a fabricated trend is never shown; the chip
 * lights up automatically once a snapshot populates it. The Health / Nodes / Pods cluster vitals are NOT here — the
 * sticky ClusterStatusStrip owns them; this strip is automation's numbers alone.
 */
function Kpis({ view, now, feed, summary }: { view: VerticalEngineView; now: number; feed: UseAutomationActivityState; summary: InsightReadState<ActivitySummaryView> }) {
  const k = automationKpis(view.workloads, now);
  const unknownSave = k.overProvisioned > 0 && k.canSave.counted === 0;
  // FEAT-002: when the cluster cost roll-up carries a real $/mo saving, the Can-save-now cell leads
  // with it (the money tone + arrow live inside Delta, so this file writes no money class); the
  // freed vCPU/GiB stay as the grey sub-line. A null roll-up keeps the freed-capacity figure, never
  // a `$0`.
  const cost = view.cost;
  const moneyValue = cost && cost.monthlySavings !== null;
  // The cluster's real monthly cost, shown independently of savings: with no recommendation yet
  // every saving is a true $0, but the cluster still costs real money, so this figure proves cost
  // is flowing. A null roll-up (feature off / nothing priceable) renders a dash, never a fake $0.
  const clusterCost = cost && cost.clusterMonthlyCost !== null;
  // DESIGN §4.1.1: the lead cell is Monthly cost when the FEAT-002 roll-up prices the cluster, else
  // Can save now. Precedence below is unchanged — only which figure leads the strip.
  const heroIsMonthly = Boolean(cost);

  const monthlyCell: MetricCell | null = cost
    ? {
      label: 'Monthly cost',
      value: clusterCost ? formatMoney(cost.clusterMonthlyCost) : '—',
      // The Trend chip (lower cost is good, so goodDirection="down") renders nothing while
      // previousClusterMonthlyCost is null — there is no prior-period source yet (no cost
      // snapshot). It lights up automatically, with no further UI work, once a cost snapshot
      // populates previousClusterMonthlyCost; a fabricated trend is never shown.
      trend: clusterCost
        ? { current: cost.clusterMonthlyCost!, prior: cost.previousClusterMonthlyCost, goodDirection: 'down' }
        : undefined,
      sub: (
        <Sub>
          <span data-priced-line="">{`${cost.pricedWorkloads} of ${cost.pricedWorkloads + cost.unpricedWorkloads} priced`}</span>
          <span>what these workloads reserve, at the nodes&apos; live AWS price</span>
        </Sub>
      ),
    }
    : null;

  const savingOrApplied: MetricCell =
    feed.result?.kind === 'ok'
      ? savingNowCell({ view, feed })
      : {
        // Without the activity log: "Applied live", not "Saving now". Endpoint 1's lastAction has no
        // before and after, so a raise for a workload that needed more counts here too.
        label: 'Applied live',
        value: k.liveApplied,
        sub: (
          <Sub>
            <span>{k.liveApplied === 1 ? 'workload' : 'workloads'} whose latest change was applied live</span>
            {feed.result && feed.result.kind !== 'not-deployed' ? (
              <span>Before and after: —, XamOps couldn&apos;t read the activity log just now</span>
            ) : (
              <span>
                Before and after: <span title="Not recorded yet: XamOps keeps the size before a change once the activity feed is on.">—</span>, recorded
                once the activity feed is on
              </span>
            )}
          </Sub>
        ),
      };

  const canSaveCell: MetricCell = {
    label: 'Can save now',
    value: moneyValue ? <Delta direction="saved" amount={formatMonthlyUsd(cost!.monthlySavings)} /> : unknownSave ? '—' : formatVcpu(k.canSave.cpuM),
    sub: (
      <Sub>
        {moneyValue && cost && <span data-priced-line="">{`${cost.pricedWorkloads} of ${cost.pricedWorkloads + cost.unpricedWorkloads} priced`}</span>}
        {moneyValue
          ? (unknownSave ? <span>Freed capacity isn&apos;t known: replica counts are missing</span> : <span>{`Frees ${formatVcpu(k.canSave.cpuM)} and ${formatMemory(k.canSave.memMi)}`}</span>)
          : <span>{unknownSave ? "The replica counts aren't known, so the amount isn't either" : `${formatMemory(k.canSave.memMi)} memory`}</span>}
        <span>{k.overProvisioned} over-provisioned {k.overProvisioned === 1 ? 'workload' : 'workloads'}, waiting for you</span>
        {k.notCounted > 0 && !unknownSave && <span>{k.notCounted} not counted: replicas or pod sizes unknown</span>}
      </Sub>
    ),
  };

  const needsMoreCell: MetricCell = {
    label: 'Needs more',
    value: k.needsMore,
    sub: <Sub><span>{k.needsMore > 0 ? `+${formatCapacity(k.needsMoreAdds.cpuM, k.needsMoreAdds.memMi)} added if applied` : 'No workload needs more'}</span></Sub>,
  };

  const changes = changesCell({ k, feed, summary, now });

  // Lead = Monthly cost (when priced) else Can save now; the rest follow in the one strip.
  const cells: MetricCell[] = heroIsMonthly
    ? [monthlyCell!, savingOrApplied, canSaveCell, needsMoreCell, changes]
    : [canSaveCell, savingOrApplied, needsMoreCell, changes];

  return (
    <div data-overview-kpis="">
      <MetricStrip cells={cells} heroLead aria-label="Automation summary" />
    </div>
  );
}

/** How many entries the Overview reads for its 7-day timeline (the feed's page limit). */
export const TIMELINE_ENTRY_LIMIT = 200;

/** The 7-day timeline from the activity feed: every entry, one lane per workload or HPA. */
function FeedTimeline({ feed, now }: { feed: UseAutomationActivityState; now: number }) {
  const t = feedTimeline(feed.entries, now);
  const notes = [
    'Every change XamOps recorded in the last 7 days, one lane per workload or HPA, coloured by result. A hollow dot is a test run: nothing was sent.',
    t.hiddenLanes > 0 ? `${t.hiddenLanes} more ${t.hiddenLanes === 1 ? 'lane is' : 'lanes are'} on Automation › Activity.` : null,
    feed.hasOlder ? `Shows the newest ${feed.entries.length} entries of the week.` : null,
  ].filter(Boolean);
  return (
    <ChartCard
      title="What automation did, last 7 days"
      subline={notes.join(' ')}
      source={`Activity log (FEAT-007, GET /api/eks/automation/activity/{cluster}?from=…&limit=${TIMELINE_ENTRY_LIMIT}): every entry XamOps recorded, newest first, 7 days.`}
      state={t.lanes.length === 0 ? { kind: 'unavailable', reason: 'XamOps recorded no change on this cluster in the last 7 days.' } : { kind: 'ok' }}
      table={
        t.placed.length > 0 ? (
          <ChartDataTable
            caption="What automation did, last 7 days"
            rowHeader="Workload"
            columns={[{ key: 'when', header: 'When' }, { key: 'result', header: 'Result' }, { key: 'kind', header: 'Live or test' }]}
            rows={t.placed.map((e) => ({
              key: e.id,
              label: e.target.name ?? '—',
              values: {
                when: e.at !== null ? istShort(e.at) : '—',
                result: activityResultChip(e).label,
                kind: e.dryRun || e.result === 'test-run' ? 'Test run' : e.action === 'mode-change' ? 'Setting' : 'Live',
              },
            }))}
          />
        ) : undefined
      }
    >
      <div className="overflow-x-auto">
        <LanesTimeline lanes={t.lanes} width={720} ariaLabel="Every change XamOps recorded, last 7 days, one lane per workload or HPA" />
        <div className={`ml-[120px] flex w-[600px] justify-between text-[11px] ${MUTED}`} aria-hidden="true">
          <span>{istShort(now - 7 * DAY_MS)}</span>
          <span>now</span>
        </div>
      </div>
      <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1.5">
        {t.results.map((r) => (
          <StatusDot key={r.label} tone={r.tone} label={r.label} />
        ))}
      </div>
    </ChartCard>
  );
}

function Timeline({ result, feed, now }: { result: AutomationReadResult | null; feed: UseAutomationActivityState; now: number }) {
  if (feed.result?.kind === 'ok') return <FeedTimeline feed={feed} now={now} />;
  if (feed.loading && !feed.result) {
    return (
      <ChartCard
        title="What automation did, last 7 days"
        source="Activity log (FEAT-007, GET /api/eks/automation/activity/{cluster}): every entry XamOps recorded, newest first, 7 days."
        state={{ kind: 'loading' }}
      />
    );
  }
  // A backend without the activity log (or one that couldn't read it): each workload's latest change.
  const feedFailed = feed.result !== null && feed.result.kind !== 'not-deployed';
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
      subline={
        feedFailed
          ? "XamOps couldn't read the activity log just now, so this shows only the latest change it recorded for each workload."
          : 'Shows only the latest change XamOps recorded for each workload. The full history arrives with the activity feed.'
      }
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
        <div className={`ml-[120px] flex w-[600px] justify-between text-[11px] ${MUTED}`} aria-hidden="true">
          <span>{istShort(now - 7 * DAY_MS)}</span>
          <span>now</span>
        </div>
      </div>
      <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1.5">
        {results.map((r) => (
          <StatusDot key={r.key} tone={r.tone} label={r.label} />
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
      <span><StatusDot tone={status.tone} label={status.label} /></span>
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

const MIB_BYTES = 1024 * 1024;

function NamespaceChart({
  result,
  eksData,
  clusterName,
  accountId,
  region,
}: {
  result: AutomationReadResult | null;
  eksData?: UseEksDashboardReturn;
  clusterName: string;
  accountId: string;
  region: string;
}) {
  const [resource, setResource] = useState<Resource>('cpu');
  const view = result?.kind === 'ok' ? result.view.vertical : null;
  // Used, as the 72-hour average Prometheus measured (design-delta §3.1), once the usage feed answers;
  // until then, or without it, the XamOps agent's namespace reading.
  const usage = useAutomationUsage({ clusterName, accountId, region }, view ? { scope: 'namespace', resource, window: '72h' } : null);
  const usageView = usage.result?.kind === 'ok' && !usage.result.view.unavailable && usage.result.view.namespaces ? usage.result.view : null;
  const agentRollup = view ? namespaceRollup(view.workloads, eksData?.dashboard, resource) : null;
  const rollup = agentRollup && usageView
    ? {
      ...agentRollup,
      rows: agentRollup.rows.map((r) => {
        const avg = usageView.namespaces!.find((n) => n.namespace === r.namespace)?.usedAvg ?? null;
        return { ...r, used: avg === null ? null : resource === 'cpu' ? avg * 1000 : avg / MIB_BYTES };
      }),
    }
    : agentRollup;
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
      subline={`Only namespaces with analysed workloads. ${
        usageView ? 'Used is the average over the last 3 days. ' : ''
      }Suggested is what the namespace would request with every XamOps suggestion applied; pods XamOps doesn't size stay at their request.`}
      source={
        usageView
          ? `Requested: the XamOps agent's pods (k8sPods). Used: usage feed (FEAT-004, GET /api/eks/automation/usage/{cluster}?scope=namespace&window=72h), ${sourceService(usageView.source)}, the mean of each namespace's use over 3 days. Suggested: Workload autoscaler (endpoint 1), (request now − suggested) × replicas.`
          : "Requested: the XamOps agent's pods (k8sPods). Used: the XamOps agent's namespace use (namespaceUtilization). Suggested: Workload autoscaler (endpoint 1), (request now − suggested) × replicas."
      }
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
        <ResourceToggle value={resource} onChange={setResource} />
      </div>
      <Bars
        mode="grouped"
        data={rows.map((r) => ({ namespace: r.namespace, requested: unit(r.requested), used: unit(r.used), suggested: unit(r.suggested) }))}
        bars={NAMESPACE_BARS}
        xKey="namespace"
        height={240}
        ariaLabel={cpu ? 'CPU by namespace, in vCPU' : 'Memory by namespace, in GiB'}
      />
      <p className={`mt-1 text-[11px] ${MUTED}`}>{cpu ? 'vCPU' : 'GiB'}</p>
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
  // The activity log's last 7 days, read once for the timeline and Saving now; fixed at mount, so it
  // isn't re-read on every render.
  const [weekAgo] = useState(() => new Date(Math.floor((Date.now() - 7 * DAY_MS) / 60_000) * 60_000).toISOString());
  const feedFilters = useMemo(() => ({ features: [], results: [], q: '', from: weekAgo }), [weekAgo]);
  const feed = useAutomationActivity({ clusterName, accountId, region, filters: feedFilters, limit: TIMELINE_ENTRY_LIMIT });
  const summary = useActivitySummary({ clusterName, accountId, region }, 7);
  const location = useLocation();
  const now = Date.now();
  const view = read.result?.kind === 'ok' ? read.result.view.vertical : null;
  const sentence = overviewSentence(clusterName, read.result, read.loading, now);

  // The hero is automation's own metric strip (never the Health/Nodes/Pods cluster vitals — the
  // sticky ClusterStatusStrip owns those, so the duplicate strip is not rendered here).
  const hero = (
    <>
      <AccessBanner capabilities={caps} cluster={clusterName} />
      <NowBanner sentence={sentence} />
      {read.loading && !read.result && <div className="skeleton h-28 rounded-[8px]" />}
      {read.result && read.result.kind !== 'ok' && <ReadFailureCard failure={read.result} onRetry={read.reload} />}
      {view && <Kpis view={view} now={now} feed={feed} summary={summary} />}
    </>
  );

  return (
    <PageTemplate
      title="Automation"
      intro="What XamOps can automate on this cluster, what it is doing now and what it has done — each number from a real source."
      // Full-width (max-w-full, the width the Pods tab uses): the Overview fills the available
      // content area (viewport − the ~220px app sidebar) instead of a left-hugging 935px prose
      // column that left a large right-side gap (owner one-sided review). The Access card already
      // sits as the right rail (`aside`); the hero MetricStrip, NOW banner, How-it-works steps and
      // the "today" table now breathe into the reclaimed width as a balanced full-width page. Long
      // single paragraphs stay legible via their own max-w-[66ch] (the intro, the step copy), not a
      // cap on the whole page.
      width="table"
      hero={hero}
      aside={<AccessBlock caps={caps} clusterName={clusterName} />}
      // Only the How-it-works card pairs with the Access rail (the balanced top row); every section
      // below it spans the FULL content width via `detail`, so the short Access card no longer
      // reserves a tall 280px rail that left a dead band down the right (owner one-sided review).
      detail={
        <div className="flex min-w-0 flex-col gap-6">
          <Timeline result={read.result} feed={feed} now={now} />
          <FeatureCards result={read.result} caps={caps} clusterName={clusterName} eksData={eksData} hrefs={hrefs} now={now} />
          <NamespaceChart result={read.result} eksData={eksData} clusterName={clusterName} accountId={accountId} region={region} />
          <ClusterUsageCard clusterName={clusterName} accountId={accountId} region={region} title="CPU and memory over time" total="capacity" />
          <TodayTable
            caps={caps}
            clusterName={clusterName}
            workloadAutoscalerHref={hrefs.workloadAutoscaler}
            pageHrefs={{ hpa: hrefs.hpa, vpa: hrefs.vpa }}
          />
        </div>
      }
    >
      <HowItWorks
        chips={view ? howItWorksChips(view, now) : {}}
        policiesHref={hrefs.policies}
        policiesLinkState={inPageState(location)}
      />
    </PageTemplate>
  );
}

export default OverviewPanel;
