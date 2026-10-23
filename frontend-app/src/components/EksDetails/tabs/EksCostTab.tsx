/**
 * EKS Cost Tab — real AWS $ from the vertical pricing engine.
 *
 * Every dollar figure is the engine's own (clusterMonthlyCost / per-workload / per-namespace /
 * per-node monthly $). Monthly is authoritative; daily = monthly / 30, hourly = monthly /
 * hoursPerMonth (design Decision 2). The engine emits null (never 0) for an unavailable figure, so
 * this tab NEVER coalesces null → 0 — it renders a dash + costReasonCopy(reason). The only coloured
 * money text goes through the <Delta> component (which carries its own aria-hidden icon).
 *
 * FEAT-003 v3 roll-out (docs/cloudk8s/ui-revamp-design.md §4.2, §7, §8): the tab now stamps the one
 * PageTemplate (width='table') the flagship Pods slice uses, so it renders inside the redesigned
 * [data-k8s-redesigned-main] region and obeys the visual gate. The hero + 3-up KPI grid collapse
 * into ONE MetricStrip (heroLead) — Monthly Est. leads, then Today's spend · Potential savings ·
 * Priced namespaces as peer cells — and the breakdown panels sit on Surface tier='table'. The
 * Workload Cost Ranking is now a DataTable whose rows open a DetailDrawer of REAL per-workload cost.
 * The deprecated HERO_NUMBER/KPI_NUMBER/EYEBROW tokens, rounded-lg/-[10px]/-full radii, the raw-rgba
 * greys, the black-filled tab pills and the fabricated '$0/mo' empty copy are all gone. Colour
 * reaches the page only through the chrome tokens and <Delta>. All dash/reason empty states are
 * preserved verbatim; no fabricated $0.
 */

import React, { useMemo, useState, type ReactNode } from 'react';
import { useEksClusterCost } from '../../../hooks/useEksClusterCost';
import { costReasonCopy } from './eksCostReasons';
import Delta from '../../CloudK8s/shared/status/Delta';
import { StatusDot } from '../../CloudK8s/shared/status/StatusDot';
import { Surface } from '../../CloudK8s/shared/Surface';
import { PageTemplate } from '../../CloudK8s/shared/PageTemplate';
import { MetricStrip, type MetricCell } from '../../CloudK8s/shared/MetricStrip';
import { DataTable } from '../../CloudK8s/shared/DataTable';
import type { ColumnDef } from '../../CloudK8s/shared/K8sDataTable';
import type { DrawerSection } from '../../CloudK8s/shared/DetailDrawer';
import { FIGURE, TITLE, BODY, SECONDARY } from '../../CloudK8s/shared/typeScale';
import { useAnimatedValue } from '../../CloudK8s/shared/useAnimatedValue';
import type {
  VerticalNamespaceCost,
  VerticalNodeCost,
  VerticalWorkload,
} from '../../../types/eksTypes';

interface EksCostTabProps {
  eksData: any;
  clusterName: string;
  accountId: string;
  region: string;
}

const DASH = '—';

/** Chrome ink / muted, both through the chrome tokens so dark mode is free (no raw rgba greys). */
const INK = 'text-[color:var(--chrome-ink)]';
const MUTED = 'text-[color:var(--chrome-muted)]';
/** The one hairline between direct children, from the chrome line token. */
const HAIRLINE = 'border-[color:var(--chrome-line)]';

/** A real $ figure (never called with null — callers render DASH on null). */
const money = (value: number, fractionDigits = 2) =>
  `$${value.toLocaleString('en-US', {
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits,
  })}`;

/** Whole-dollar monthly figure, e.g. "$24". */
const moneyWhole = (value: number) => `$${Math.round(value).toLocaleString('en-US')}`;

/**
 * The animated hero monthly figure. Rendered ONLY with a finite monthly cost, so the count-up hook
 * is never fed a null (the dash + reason branch is a sibling at the call site — never a fabricated
 * $0). Carries the dominant HERO_FIGURE via MetricStrip's heroLead.
 */
function HeroMonthly({ monthly }: { monthly: number }) {
  const animated = useAnimatedValue(monthly);
  return <span data-hero-cost="">{moneyWhole(animated)}</span>;
}

/** The ranking table's row model: the engine workload + its rank within the active kind. */
interface WorkloadRow {
  wl: VerticalWorkload;
  key: string;
  rank: number;
  monthly: number | null;
  costShare: number;
}

const EksCostTab: React.FC<EksCostTabProps> = ({ eksData, clusterName, accountId, region }) => {
  const { dashboard } = eksData ?? {};
  const { cost, workloads, loading, error } = useEksClusterCost({ clusterName, accountId, region });

  const [namespacePage, setNamespacePage] = useState(0);
  const [nodePage, setNodePage] = useState(0);
  const [workloadTab, setWorkloadTab] = useState<'Deployment' | 'DaemonSet' | 'StatefulSet'>('Deployment');
  const itemsPerPage = 10;
  const nodeItemsPerPage = 6;

  // Monthly is authoritative; daily/hourly are always derived from it (Decision 2).
  const hoursPerMonth = cost?.hoursPerMonth ?? 730;
  const clusterMonthly = cost?.clusterMonthlyCost ?? null;
  const dailyCost = clusterMonthly != null ? clusterMonthly / 30 : null;
  const hourlyCost = clusterMonthly != null ? clusterMonthly / hoursPerMonth : null;

  const namespaceCosts = cost?.namespaceCosts ?? [];
  const nodeCosts = cost?.nodeCosts ?? [];

  // Per-workload rows grouped by kind. The engine evaluates Deployments + StatefulSets only, so
  // the DaemonSets tab is honestly empty on this data source.
  const workloadsByKind = useMemo(() => {
    const byKind: Record<string, VerticalWorkload[]> = { Deployment: [], DaemonSet: [], StatefulSet: [] };
    (workloads ?? []).forEach((wl) => {
      const kind = wl.ref?.kind;
      if (kind && byKind[kind]) byKind[kind].push(wl);
    });
    const rank = (wl: VerticalWorkload) => (wl.cost?.monthlyCost != null ? wl.cost.monthlyCost : -1);
    Object.values(byKind).forEach((list) => list.sort((a, b) => rank(b) - rank(a)));
    return byKind;
  }, [workloads]);

  // Node identity join: instance type + ready chip come from the dashboard; money from NodeCostDto.
  const dashboardNodeIndex = useMemo(() => {
    const index: Record<string, any> = {};
    const nodes: any[] = dashboard?.nodes ?? eksData?.sortedNodesByCost ?? [];
    nodes.forEach((n: any) => {
      const name = n.name || n.nodeName;
      if (name) index[name] = n;
    });
    return index;
  }, [dashboard, eksData]);

  const readyOf = (node: any): boolean =>
    node ? !node.condition || node.condition === 'Ready' || node.condition === 'ready' || node.ready === true : true;

  // KPI honest-empty branch keys on clusterMonthlyCost == null (NOT only cost == null): an
  // all-unpriceable flag-ON cluster has cost != null, clusterMonthlyCost == null, but a full all-dash
  // nodeCosts list — the KPI strip dashes while the Node Fleet panel still renders every row.
  const headlineUnavailable = clusterMonthly == null;
  const headlineReason = error
    ? error
    : cost == null
    ? 'Live Kubernetes cost pricing is not enabled for this cluster.'
    : costReasonCopy(cost.monthlySavingsUnavailableReason);

  const activeWorkloads = workloadsByKind[workloadTab] ?? [];
  const maxWorkloadMonthly = Math.max(
    ...activeWorkloads.map((w) => w.cost?.monthlyCost ?? 0),
    0.01
  );

  // The ranking rows: rank by descending monthly (nulls last, handled by workloadsByKind sort), with
  // the cost-share bar scaled to the active kind's priciest workload.
  const workloadRows = useMemo((): WorkloadRow[] =>
    activeWorkloads.map((wl, i) => {
      const monthly = wl.cost?.monthlyCost ?? null;
      return {
        wl,
        key: `${wl.ref.namespace}/${wl.ref.name}`,
        rank: i + 1,
        monthly,
        costShare: monthly != null ? (monthly / Math.max(maxWorkloadMonthly, 0.01)) * 100 : 0,
      };
    }),
  [activeWorkloads, maxWorkloadMonthly]);

  if (!clusterName || !accountId) return null;

  // ── MetricStrip (hero): Monthly Est. leads; Today's spend · Potential savings · Priced namespaces
  //    demote into peer cells. Placement + the heroLead size set the hierarchy, not a bigger hex. ──
  const monthlyCell: MetricCell = {
    label: 'Monthly Est.',
    value: clusterMonthly != null ? <HeroMonthly monthly={clusterMonthly} /> : <span data-hero-dash="">{DASH}</span>,
    sub: clusterMonthly != null ? '730-hr basis' : headlineReason,
  };

  const todayCell: MetricCell = {
    label: "Today's spend",
    value: dailyCost != null ? money(dailyCost) : DASH,
    sub: hourlyCost != null ? `per day · ${money(hourlyCost, 4)}/hr` : headlineReason,
  };

  const savingsCell: MetricCell = (() => {
    if (cost?.monthlySavings == null) {
      return {
        label: 'Potential savings',
        value: DASH,
        sub: costReasonCopy(cost?.monthlySavingsUnavailableReason),
      };
    }
    if (cost.monthlySavings > 0) {
      return {
        label: 'Potential savings',
        value: <Delta direction="saved" amount={`${moneyWhole(cost.monthlySavings)}/mo`} />,
        sub: 'per month identified',
      };
    }
    return {
      label: 'Potential savings',
      value: '$0/mo',
      sub: 'No rightsizing recommendations yet',
    };
  })();

  const namespacesCell: MetricCell = {
    label: 'Priced namespaces',
    value: namespaceCosts.length,
    sub: 'with evaluated workloads',
  };

  const stripCells: readonly MetricCell[] = [monthlyCell, todayCell, savingsCell, namespacesCell];

  // ── Workload Cost Ranking: DataTable with fixed widths, right-aligned money, truncation + title,
  //    StatusDot (not a pill) for the kind, and a per-row DetailDrawer of REAL per-workload cost. ──
  const columns: ReadonlyArray<ColumnDef<WorkloadRow>> = [
    {
      id: 'rank', header: '#', align: 'right', width: '56px',
      cell: (r) => <span className={`tabular-nums ${MUTED}`}>{r.rank}</span>,
      sortValue: (r) => r.rank, csv: (r) => r.rank,
    },
    {
      id: 'name', header: 'Workload', priority: 'essential', width: '260px',
      cell: (r) => (
        <span className="flex min-w-0 flex-col">
          <span className={`truncate font-semibold ${BODY}`} title={r.wl.ref.name}>{r.wl.ref.name}</span>
          <span className={`inline-flex items-center gap-1.5 ${SECONDARY}`}>
            <StatusDot tone="off" label={r.wl.ref.kind} />
            {r.wl.cost?.capacityTypeInferred && (
              <span title="On-demand assumed from a missing capacity-type label.">· assumed on-demand</span>
            )}
          </span>
        </span>
      ),
      sortValue: (r) => r.wl.ref.name, csv: (r) => r.wl.ref.name,
    },
    {
      id: 'namespace', header: 'Namespace', width: '180px',
      cell: (r) => <span className={`block truncate font-mono text-[12px] ${MUTED}`} title={r.wl.ref.namespace}>{r.wl.ref.namespace}</span>,
      sortValue: (r) => r.wl.ref.namespace, csv: (r) => r.wl.ref.namespace,
    },
    {
      id: 'share', header: 'Cost share', width: '160px',
      cell: (r) => (
        <span className="flex items-center" aria-hidden="true">
          <span className="h-1.5 w-full overflow-hidden rounded-full bg-[color:var(--chrome-surface-2)]">
            <span className="block h-full rounded-full bg-[color:var(--chrome-muted)]" style={{ width: `${r.costShare}%` }} />
          </span>
        </span>
      ),
    },
    {
      id: 'daily', header: 'Daily', align: 'right', width: '112px',
      cell: (r) => (r.monthly != null
        ? <span className={`tabular-nums font-semibold ${INK}`}>{money(r.monthly / 30)}</span>
        : <span className={`tabular-nums ${MUTED}`} title={costReasonCopy(r.wl.cost?.reason)}>{DASH}</span>),
      sortValue: (r) => r.monthly, csv: (r) => (r.monthly != null ? money(r.monthly / 30) : ''),
    },
    {
      id: 'monthly', header: 'Monthly', align: 'right', width: '112px',
      cell: (r) => (r.monthly != null
        ? <span className={`tabular-nums ${MUTED}`}>{moneyWhole(r.monthly)}</span>
        : <span className={`tabular-nums ${MUTED}`} title={costReasonCopy(r.wl.cost?.reason)}>{DASH}</span>),
      sortValue: (r) => r.monthly, csv: (r) => (r.monthly != null ? moneyWhole(r.monthly) : ''),
    },
  ];

  /** The real-data drawer for a workload: Identity + Cost (monthly / daily / hourly / savings). */
  const rowToSections = (r: WorkloadRow): DrawerSection[] => {
    const c = r.wl.cost;
    const monthly = c?.monthlyCost ?? null;
    const identity: DrawerSection = {
      title: 'Identity',
      data: [
        { label: 'Workload', value: <span className="break-all">{r.wl.ref.name}</span> },
        { label: 'Kind', value: <StatusDot tone="off" label={r.wl.ref.kind} /> },
        { label: 'Namespace', value: <span className="break-all font-mono text-[12px]">{r.wl.ref.namespace}</span> },
        {
          label: 'Capacity type',
          value: c?.capacityType ? `${c.capacityType}${c.capacityTypeInferred ? ' (assumed)' : ''}` : null,
          reason: 'The engine reported no capacity type for this workload.',
        },
      ],
    };
    const costSection: DrawerSection = {
      title: 'Cost',
      data: [
        {
          label: 'Monthly cost',
          value: monthly != null ? <span className="tabular-nums">{money(monthly)}</span> : null,
          reason: costReasonCopy(c?.reason),
        },
        {
          label: 'Daily cost',
          value: monthly != null ? <span className="tabular-nums">{money(monthly / 30)}</span> : null,
          reason: costReasonCopy(c?.reason),
        },
        {
          label: 'Hourly cost',
          value: monthly != null ? <span className="tabular-nums">{money(monthly / hoursPerMonth, 4)}</span> : null,
          reason: costReasonCopy(c?.reason),
        },
        {
          label: 'Potential savings',
          value: c?.monthlySavings != null && c.monthlySavings > 0
            ? <Delta direction="saved" amount={`${moneyWhole(c.monthlySavings)}/mo`} />
            : c?.monthlySavings === 0
            ? <span className="tabular-nums">$0/mo</span>
            : null,
          reason: 'No rightsizing recommendation priced for this workload yet.',
        },
        {
          label: 'Source',
          value: c?.source ? <span className="font-mono text-[12px]">{c.source}</span> : null,
          reason: 'The engine reported no pricing source for this workload.',
        },
      ],
    };
    return [identity, costSection];
  };

  const intro: ReactNode = loading
    ? 'Loading cluster cost…'
    : 'Real AWS spend from the vertical pricing engine. Monthly is authoritative; daily and hourly derive from it.';

  return (
    <PageTemplate
      title="Cost"
      intro={intro}
      width="table"
      hero={<MetricStrip cells={stripCells} heroLead aria-label="Cluster cost" />}
    >
      <div data-panel="cost" className="flex min-w-0 flex-col gap-6">

        {headlineUnavailable && !loading && (
          <Surface tier="table" className="px-5 py-3">
            <p className={`${BODY} ${MUTED}`}>{headlineReason}</p>
          </Surface>
        )}

        {/* ── Namespace Cost Breakdown ── */}
        <Surface tier="table" className="overflow-hidden">
          <div className={`flex items-center justify-between border-b px-5 py-3.5 ${HAIRLINE}`}>
            <span className={TITLE}>Namespace Cost Breakdown</span>
            <span className={SECONDARY}>{namespaceCosts.length} namespaces</span>
          </div>

          <div className="space-y-3 p-5">
            {namespaceCosts
              .slice(namespacePage * itemsPerPage, (namespacePage + 1) * itemsPerPage)
              .map((ns: VerticalNamespaceCost, i: number) => {
                const idx = namespacePage * itemsPerPage + i;
                const priceable = ns.monthlyCost != null;
                return (
                  <div key={ns.namespace}>
                    <div className="mb-1 flex items-center justify-between">
                      <div className="flex min-w-0 items-center gap-2">
                        <span className={`w-4 font-mono ${SECONDARY}`}>{idx + 1}</span>
                        <span className={`truncate font-mono font-semibold ${BODY}`} title={ns.namespace}>{ns.namespace}</span>
                        <span className={SECONDARY}>
                          {ns.pricedWorkloads} priced · {ns.unpricedWorkloads} unpriced
                        </span>
                      </div>
                      <div className="flex shrink-0 items-center gap-3">
                        {priceable ? (
                          <>
                            <span className={`tabular-nums font-semibold ${BODY} ${INK}`}>
                              {money(ns.monthlyCost! / 30)}
                              <span className={`font-normal ${SECONDARY}`}>/day</span>
                            </span>
                            <span className={`w-16 text-right ${SECONDARY}`}>{moneyWhole(ns.monthlyCost!)}/mo</span>
                          </>
                        ) : (
                          <span className={`tabular-nums font-semibold ${MUTED}`} title="No workload in this namespace was priceable.">{DASH}</span>
                        )}
                      </div>
                    </div>
                  </div>
                );
              })}
            {namespaceCosts.length === 0 && (
              <div className="py-12 text-center">
                <p className={`${BODY} ${MUTED}`}>No namespace cost data available</p>
              </div>
            )}
          </div>

          {namespaceCosts.length > itemsPerPage && (
            <Pager
              page={namespacePage}
              perPage={itemsPerPage}
              total={namespaceCosts.length}
              onPrev={() => setNamespacePage((p) => Math.max(0, p - 1))}
              onNext={() => setNamespacePage((p) => p + 1)}
            />
          )}
        </Surface>

        {/* ── Node Fleet Cost (full fleet) ── */}
        <Surface tier="table" className="overflow-hidden">
          <div className={`border-b px-5 py-3.5 ${HAIRLINE}`}>
            <span className={TITLE}>Node Fleet Cost</span>
            <span className={`ml-2 ${SECONDARY}`}>{nodeCosts.length} nodes · per-node monthly spend</span>
          </div>

          <div className="p-5">
            <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3">
              {nodeCosts.slice(nodePage * nodeItemsPerPage, (nodePage + 1) * nodeItemsPerPage).map((node: VerticalNodeCost, i: number) => {
                const dashNode = dashboardNodeIndex[node.nodeName];
                const isReady = readyOf(dashNode);
                const isSpot = node.capacityType === 'spot';
                const instanceType = node.instanceType || dashNode?.instanceType || 'Unknown';
                const priceable = node.monthlyCost != null;
                return (
                  <div key={node.nodeName || i} className={`rounded-[6px] border p-4 ${HAIRLINE} bg-[color:var(--chrome-card)]`}>
                    <div className="mb-3 flex items-start justify-between">
                      <div className="min-w-0 flex-1">
                        <p className={`truncate font-mono font-semibold ${BODY} ${INK}`} title={node.nodeName}>{node.nodeName}</p>
                        <p className={`mt-0.5 ${SECONDARY}`}>
                          {instanceType}
                          {node.capacityTypeInferred && <span className="ml-1" title="On-demand assumed from a missing capacity-type label; price is real but not authoritative.">· assumed on-demand</span>}
                        </p>
                      </div>
                      {node.capacityType && (
                        <span className={`ml-2 shrink-0 ${SECONDARY}`}>{isSpot ? 'Spot' : 'On-Demand'}</span>
                      )}
                    </div>

                    {priceable ? (
                      <>
                        <div className={`mb-1 ${FIGURE}`}>
                          {money(node.monthlyCost! / 30)}<span className={`font-normal ${SECONDARY}`}>/day</span>
                        </div>
                        <p className={`mb-3 ${SECONDARY}`}>
                          {moneyWhole(node.monthlyCost!)}/mo · {money(node.monthlyCost! / hoursPerMonth, 4)}/hr
                        </p>
                      </>
                    ) : (
                      <>
                        <div className={`mb-1 ${FIGURE} text-[color:var(--chrome-muted)]`}>{DASH}</div>
                        <p className={`mb-3 ${SECONDARY}`}>{costReasonCopy(node.reason)}</p>
                      </>
                    )}

                    <StatusDot
                      tone={isReady ? 'good' : 'bad'}
                      label={isReady ? 'Ready' : (dashNode?.condition || 'Not Ready')}
                    />
                  </div>
                );
              })}
            </div>

            {nodeCosts.length === 0 && (
              <div className="col-span-3 py-10 text-center">
                <p className={`${BODY} ${MUTED}`}>No node cost data available</p>
              </div>
            )}
          </div>

          {nodeCosts.length > nodeItemsPerPage && (
            <Pager
              page={nodePage}
              perPage={nodeItemsPerPage}
              total={nodeCosts.length}
              onPrev={() => setNodePage((p) => Math.max(0, p - 1))}
              onNext={() => setNodePage((p) => p + 1)}
            />
          )}
        </Surface>

        {/* ── Workload Cost Ranking (DataTable + DetailDrawer) ── */}
        <Surface tier="table" className="overflow-hidden">
          <div className={`flex flex-wrap items-center justify-between gap-3 border-b px-5 py-3.5 ${HAIRLINE}`}>
            <span className={TITLE}>Workload Cost Ranking</span>
            <div role="group" aria-label="Workload kind" className="flex items-center gap-1">
              {([
                { key: 'Deployment', label: 'Deployments' },
                { key: 'DaemonSet', label: 'DaemonSets' },
                { key: 'StatefulSet', label: 'StatefulSets' },
              ] as const).map((tab) => {
                const active = workloadTab === tab.key;
                return (
                  <button key={tab.key}
                    type="button"
                    aria-pressed={active}
                    onClick={() => { setWorkloadTab(tab.key); }}
                    className={`rounded-[6px] border px-2.5 py-1 text-[12px] outline-none focus-visible:ring-2 focus-visible:ring-[color:var(--chrome-ink)] ${active
                      ? 'border-[color:var(--chrome-ink)] bg-[color:var(--chrome-surface-2)] font-semibold text-[color:var(--chrome-ink)]'
                      : 'border-[color:var(--chrome-line)] font-medium text-[color:var(--chrome-muted)] hover:bg-[color:var(--chrome-surface-2)]'}`}>
                    {tab.label} <span className="opacity-70">({(workloadsByKind[tab.key] ?? []).length})</span>
                  </button>
                );
              })}
            </div>
          </div>

          <div className="p-5">
            <DataTable<WorkloadRow>
              rows={workloadRows}
              columns={columns}
              rowKey={(r) => r.key}
              caption={`${workloadTab} cost ranking`}
              exportName={`${clusterName}-${workloadTab.toLowerCase()}-cost`}
              rowLabel={(r) => r.wl.ref.name}
              rowTitle={(r) => r.wl.ref.name}
              rowSubtitle={(r) => r.wl.ref.namespace}
              rowToSections={rowToSections}
              emptyState={
                <p className={`px-4 py-8 text-center ${BODY} ${MUTED}`}>
                  {workloadTab === 'DaemonSet'
                    ? 'DaemonSets are not evaluated by the cost engine'
                    : `No ${workloadTab.toLowerCase()} cost data available`}
                </p>
              }
            />
          </div>
        </Surface>
      </div>
    </PageTemplate>
  );
};

/** The shared pager footer for the namespace / node-fleet panels (6px control radius, token chrome). */
function Pager({ page, perPage, total, onPrev, onNext }: {
  page: number; perPage: number; total: number; onPrev: () => void; onNext: () => void;
}) {
  const btn =
    'rounded-[6px] border border-[color:var(--chrome-line)] px-3 py-1.5 text-[12px] font-semibold text-[color:var(--chrome-ink)] ' +
    'hover:bg-[color:var(--chrome-surface-2)] disabled:opacity-40 outline-none focus-visible:ring-2 focus-visible:ring-[color:var(--chrome-ink)]';
  return (
    <div className={`flex items-center justify-between border-t px-5 py-3 ${HAIRLINE}`}>
      <span className={SECONDARY}>
        {page * perPage + 1}–{Math.min((page + 1) * perPage, total)} of {total}
      </span>
      <div className="flex gap-2">
        <button type="button" onClick={onPrev} disabled={page === 0} className={btn}>Previous</button>
        <button type="button" onClick={onNext} disabled={(page + 1) * perPage >= total} className={btn}>Next</button>
      </div>
    </div>
  );
}

export default EksCostTab;
