/**
 * EKS Cost Tab — real AWS $ from the vertical pricing engine.
 *
 * Every dollar figure is the engine's own (clusterMonthlyCost / per-workload / per-namespace /
 * per-node monthly $). Monthly is authoritative; daily = monthly / 30, hourly = monthly /
 * hoursPerMonth (design Decision 2). The engine emits null (never 0) for an unavailable figure, so
 * this tab NEVER coalesces null → 0 — it renders a dash + costReasonCopy(reason). The only coloured
 * money text goes through the <Delta> component (which carries its own aria-hidden icon).
 *
 * FEAT-004 relayout (DESIGN §4.1, §6): the 4-up KPI grid is replaced by one HERO_NUMBER hero
 * (Monthly Est.) with a divide-x strip of Today's spend · Potential savings · Priced namespaces;
 * the three breakdown panels move onto tier-3 <Surface>s so radius/border/shadow come from the one
 * primitive. This file had ZERO dark: variants before — Surface + the typeScale tokens + StatusMark
 * give it dark-mode treatment (an intended improvement, not a regression). All dash/reason empty
 * states are preserved verbatim; no fabricated $0.
 */

import React, { useMemo, useState } from 'react';
import { useEksClusterCost } from '../../../hooks/useEksClusterCost';
import { costReasonCopy } from './eksCostReasons';
import Delta from '../../CloudK8s/shared/status/Delta';
import { StatusMark } from '../../CloudK8s/shared/StatusMark';
import { Surface } from '../../CloudK8s/shared/Surface';
import { HERO_NUMBER, KPI_NUMBER, EYEBROW, TITLE, SECONDARY } from '../../CloudK8s/shared/typeScale';
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

/** A real $ figure (never called with null — callers render DASH on null). */
const money = (value: number, fractionDigits = 2) =>
  `$${value.toLocaleString('en-US', {
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits,
  })}`;

/** Whole-dollar monthly figure, e.g. "$24". */
const moneyWhole = (value: number) => `$${Math.round(value).toLocaleString('en-US')}`;

/** Shared text roles come from the typeScale (DESIGN §7); only this tab's local neutral chip stays. */
const INK = 'text-[rgba(0,0,0,0.88)] dark:text-[rgba(255,255,255,0.92)]';
const MUTED = 'text-[rgba(0,0,0,0.4)] dark:text-[rgba(255,255,255,0.4)]';
const FAINT = 'text-[rgba(0,0,0,0.5)] dark:text-[rgba(255,255,255,0.5)]';
const NEUTRAL_PILL =
  'inline-flex items-center rounded-full border px-2.5 py-0.5 text-[11px] font-semibold ' +
  'border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.5)] dark:border-[rgba(255,255,255,0.15)] dark:text-[rgba(255,255,255,0.6)]';
const PAGE_BTN =
  'px-3 py-1.5 text-[12px] font-semibold bg-white border border-[rgba(0,0,0,0.12)] rounded-lg ' +
  'hover:bg-[rgba(0,0,0,0.02)] disabled:opacity-40 text-[rgba(0,0,0,0.88)] ' +
  'dark:bg-[#141414] dark:border-[rgba(255,255,255,0.15)] dark:text-[rgba(255,255,255,0.92)] dark:hover:bg-[rgba(255,255,255,0.04)]';

/** The hero monthly number. Rendered ONLY with a finite monthly cost, so the count-up hook is never
 *  fed a null (the dash + reason branch is a sibling at the call site — never a fabricated $0). */
function HeroMonthly({ monthly }: { monthly: number }) {
  const animated = useAnimatedValue(monthly);
  return (
    <p className={`${HERO_NUMBER} ${INK}`} data-hero-cost="">
      {moneyWhole(animated)}
    </p>
  );
}

const EksCostTab: React.FC<EksCostTabProps> = ({ eksData, clusterName, accountId, region }) => {
  const { dashboard } = eksData ?? {};
  const { cost, workloads, loading, error } = useEksClusterCost({ clusterName, accountId, region });

  const [namespacePage, setNamespacePage] = useState(0);
  const [nodePage, setNodePage] = useState(0);
  const [workloadPage, setWorkloadPage] = useState(0);
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

  if (!clusterName || !accountId) return null;

  if (loading) {
    return (
      <div className="py-16 text-center">
        <p className={`text-[13px] ${FAINT}`}>Loading cluster cost…</p>
      </div>
    );
  }

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

  const readyOf = (node: any): boolean =>
    node ? !node.condition || node.condition === 'Ready' || node.condition === 'ready' || node.ready === true : true;

  return (
    <div className="space-y-5">

      {/* ── HERO (DESIGN §4.1): Monthly Est. as the one HERO_NUMBER, with Today's spend ·
          Potential savings · Priced namespaces demoted into a divide-x KPI strip. ── */}
      <Surface tier="hero" aria-label="Cluster cost">
        <div className="flex flex-col gap-6 lg:flex-row lg:items-start lg:justify-between">
          <section aria-label="Monthly estimate" className="flex min-w-0 flex-col gap-1.5">
            <h3 className={EYEBROW}>Monthly Est.</h3>
            {clusterMonthly != null ? (
              <>
                <HeroMonthly monthly={clusterMonthly} />
                <p className={SECONDARY}>730-hr basis</p>
              </>
            ) : (
              <>
                <p className={`${HERO_NUMBER} ${INK}`} data-hero-dash="">{DASH}</p>
                <p className={SECONDARY}>{headlineReason}</p>
              </>
            )}
          </section>

          <dl className="grid grid-cols-3 divide-x divide-[rgba(0,0,0,0.06)] dark:divide-[rgba(255,255,255,0.08)]" aria-label="Cost totals">
            {/* Today's Spend = derived daily */}
            <div className="flex flex-col gap-0.5 px-5 first:pl-0" data-kpi="today">
              <dt className={EYEBROW}>Today's spend</dt>
              <dd className={`${KPI_NUMBER} ${INK}`}>{dailyCost != null ? money(dailyCost) : DASH}</dd>
              <dd className={`text-[11px] ${MUTED}`}>
                {hourlyCost != null ? `per day · ${money(hourlyCost, 4)}/hr` : headlineReason}
              </dd>
            </div>

            {/* Potential Savings = cost.monthlySavings verbatim (Decision 3) */}
            <div className="flex flex-col gap-0.5 px-5" data-kpi="savings">
              <dt className={EYEBROW}>Potential savings</dt>
              {cost?.monthlySavings == null ? (
                <>
                  <dd className={`${KPI_NUMBER} ${INK}`}>{DASH}</dd>
                  <dd className={`text-[11px] ${MUTED}`}>{costReasonCopy(cost?.monthlySavingsUnavailableReason)}</dd>
                </>
              ) : cost.monthlySavings > 0 ? (
                <>
                  <dd className="leading-tight">
                    <Delta direction="saved" amount={`${moneyWhole(cost.monthlySavings)}/mo`} />
                  </dd>
                  <dd className={`text-[11px] ${MUTED}`}>per month identified</dd>
                </>
              ) : (
                <>
                  <dd className={`${KPI_NUMBER} ${INK}`}>$0/mo</dd>
                  <dd className={`text-[11px] ${MUTED}`}>No rightsizing recommendations yet</dd>
                </>
              )}
            </div>

            {/* Priced namespaces = namespaceCosts.length (Decision 5, Finding 4) */}
            <div className="flex flex-col gap-0.5 px-5" data-kpi="namespaces">
              <dt className={EYEBROW}>Priced namespaces</dt>
              <dd className={`${KPI_NUMBER} ${INK}`}>{namespaceCosts.length}</dd>
              <dd className={`text-[11px] ${MUTED}`}>with evaluated workloads</dd>
            </div>
          </dl>
        </div>
      </Surface>

      {headlineUnavailable && (
        <Surface tier="table" className="px-5 py-3">
          <p className={`text-[13px] ${FAINT}`}>{headlineReason}</p>
        </Surface>
      )}

      {/* ── Namespace Cost Breakdown ── */}
      <Surface tier="table" className="overflow-hidden">
        <div className="px-5 py-3.5 border-b border-[rgba(0,0,0,0.06)] dark:border-[rgba(255,255,255,0.08)] flex items-center justify-between">
          <span className={TITLE}>Namespace Cost Breakdown</span>
          <span className={NEUTRAL_PILL}>{namespaceCosts.length} namespaces</span>
        </div>

        <div className="p-5 space-y-3">
          {namespaceCosts
            .slice(namespacePage * itemsPerPage, (namespacePage + 1) * itemsPerPage)
            .map((ns: VerticalNamespaceCost, i: number) => {
              const idx = namespacePage * itemsPerPage + i;
              const priceable = ns.monthlyCost != null;
              return (
                <div key={ns.namespace}>
                  <div className="flex items-center justify-between mb-1">
                    <div className="flex items-center gap-2">
                      <span className={`text-[11px] ${MUTED} font-mono w-4`}>{idx + 1}</span>
                      <span className={`font-mono text-[13px] font-semibold ${INK}`}>{ns.namespace}</span>
                      <span className={NEUTRAL_PILL}>
                        {ns.pricedWorkloads} priced · {ns.unpricedWorkloads} unpriced
                      </span>
                    </div>
                    <div className="flex items-center gap-3">
                      {priceable ? (
                        <>
                          <span className={`text-[13px] font-semibold ${INK}`}>
                            {money(ns.monthlyCost! / 30)}
                            <span className={`text-[11px] ${MUTED} font-normal`}>/day</span>
                          </span>
                          <span className={`text-[11px] ${MUTED} w-16 text-right`}>{moneyWhole(ns.monthlyCost!)}/mo</span>
                        </>
                      ) : (
                        <span className={`text-[13px] font-semibold ${MUTED}`} title="No workload in this namespace was priceable.">{DASH}</span>
                      )}
                    </div>
                  </div>
                </div>
              );
            })}
          {namespaceCosts.length === 0 && (
            <div className="py-12 text-center">
              <p className={`text-[13px] ${FAINT}`}>No namespace cost data available</p>
            </div>
          )}
        </div>

        {namespaceCosts.length > itemsPerPage && (
          <div className="px-5 py-3 bg-[rgba(0,0,0,0.02)] dark:bg-[rgba(255,255,255,0.03)] border-t border-[rgba(0,0,0,0.06)] dark:border-[rgba(255,255,255,0.08)] flex items-center justify-between">
            <span className={`text-[11px] ${MUTED}`}>
              {namespacePage * itemsPerPage + 1}–{Math.min((namespacePage + 1) * itemsPerPage, namespaceCosts.length)} of {namespaceCosts.length}
            </span>
            <div className="flex gap-2">
              <button onClick={() => setNamespacePage((p) => Math.max(0, p - 1))} disabled={namespacePage === 0} className={PAGE_BTN}>Previous</button>
              <button onClick={() => setNamespacePage((p) => p + 1)} disabled={(namespacePage + 1) * itemsPerPage >= namespaceCosts.length} className={PAGE_BTN}>Next</button>
            </div>
          </div>
        )}
      </Surface>

      {/* ── Node Fleet Cost (full fleet) ── */}
      <Surface tier="table" className="overflow-hidden">
        <div className="px-5 py-3.5 border-b border-[rgba(0,0,0,0.06)] dark:border-[rgba(255,255,255,0.08)]">
          <span className={TITLE}>Node Fleet Cost</span>
          <span className={`ml-2 text-[11px] ${MUTED}`}>{nodeCosts.length} nodes · per-node monthly spend</span>
        </div>

        <div className="p-5">
          <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
            {nodeCosts.slice(nodePage * nodeItemsPerPage, (nodePage + 1) * nodeItemsPerPage).map((node: VerticalNodeCost, i: number) => {
              const dashNode = dashboardNodeIndex[node.nodeName];
              const isReady = readyOf(dashNode);
              const isSpot = node.capacityType === 'spot';
              const instanceType = node.instanceType || dashNode?.instanceType || 'Unknown';
              const priceable = node.monthlyCost != null;
              return (
                <div key={node.nodeName || i} className="bg-white dark:bg-[#141414] border border-[rgba(0,0,0,0.07)] dark:border-[rgba(255,255,255,0.09)] rounded-[10px] p-4 hover:bg-[rgba(0,0,0,0.02)] dark:hover:bg-[rgba(255,255,255,0.04)] transition-colors">
                  <div className="flex items-start justify-between mb-3">
                    <div className="min-w-0 flex-1">
                      <p className={`text-[13px] font-mono font-semibold ${INK} truncate`}>{node.nodeName}</p>
                      <p className={`text-[11px] ${MUTED} mt-0.5`}>
                        {instanceType}
                        {node.capacityTypeInferred && <span className="ml-1" title="On-demand assumed from a missing capacity-type label; price is real but not authoritative.">· assumed on-demand</span>}
                      </p>
                    </div>
                    {node.capacityType && (
                      <span className={`${NEUTRAL_PILL} ml-2 flex-shrink-0`}>
                        {isSpot ? 'Spot' : 'On-Demand'}
                      </span>
                    )}
                  </div>

                  {priceable ? (
                    <>
                      <div className={`text-[26px] font-semibold ${INK} leading-none mb-1`}>
                        {money(node.monthlyCost! / 30)}<span className={`text-[13px] ${MUTED} font-normal`}>/day</span>
                      </div>
                      <p className={`text-[11px] ${MUTED} mb-3`}>
                        {moneyWhole(node.monthlyCost!)}/mo · {money(node.monthlyCost! / hoursPerMonth, 4)}/hr
                      </p>
                    </>
                  ) : (
                    <>
                      <div className={`text-[26px] font-semibold ${MUTED} leading-none mb-1`}>{DASH}</div>
                      <p className={`text-[11px] ${MUTED} mb-3`}>{costReasonCopy(node.reason)}</p>
                    </>
                  )}

                  <StatusMark
                    state={isReady ? 'healthy' : 'error'}
                    label={isReady ? 'Ready' : (dashNode?.condition || 'Not Ready')}
                  />
                </div>
              );
            })}
          </div>

          {nodeCosts.length === 0 && (
            <div className="py-10 text-center col-span-3">
              <p className={`text-[13px] ${FAINT}`}>No node cost data available</p>
            </div>
          )}
        </div>

        {nodeCosts.length > nodeItemsPerPage && (
          <div className="flex items-center justify-between mt-4 pt-4 border-t border-[rgba(0,0,0,0.06)] dark:border-[rgba(255,255,255,0.08)] px-5 pb-4">
            <span className={`text-[11px] ${MUTED}`}>
              {nodePage * nodeItemsPerPage + 1}–{Math.min((nodePage + 1) * nodeItemsPerPage, nodeCosts.length)} of {nodeCosts.length}
            </span>
            <div className="flex gap-2">
              <button onClick={() => setNodePage((p) => Math.max(0, p - 1))} disabled={nodePage === 0} className={PAGE_BTN}>Previous</button>
              <button onClick={() => setNodePage((p) => p + 1)} disabled={(nodePage + 1) * nodeItemsPerPage >= nodeCosts.length} className={PAGE_BTN}>Next</button>
            </div>
          </div>
        )}
      </Surface>

      {/* ── Workload Cost Ranking ── */}
      <Surface tier="table" className="overflow-hidden">
        <div className="px-5 py-3.5 border-b border-[rgba(0,0,0,0.06)] dark:border-[rgba(255,255,255,0.08)] flex items-center justify-between flex-wrap gap-3">
          <span className={TITLE}>Workload Cost Ranking</span>
          <div className="flex items-center gap-1">
            {([
              { key: 'Deployment', label: 'Deployments' },
              { key: 'DaemonSet', label: 'DaemonSets' },
              { key: 'StatefulSet', label: 'StatefulSets' },
            ] as const).map((tab) => (
              <button key={tab.key}
                onClick={() => { setWorkloadTab(tab.key); setWorkloadPage(0); }}
                className={`px-3 py-1.5 rounded-lg text-[12px] font-semibold transition-colors ${workloadTab === tab.key ? 'bg-[rgba(0,0,0,0.88)] text-white dark:bg-white dark:text-[#141414]' : 'bg-white text-[rgba(0,0,0,0.5)] border border-[rgba(0,0,0,0.12)] hover:bg-[rgba(0,0,0,0.02)] dark:bg-[#141414] dark:text-[rgba(255,255,255,0.6)] dark:border-[rgba(255,255,255,0.15)] dark:hover:bg-[rgba(255,255,255,0.04)]'}`}>
                {tab.label} <span className="opacity-70">({(workloadsByKind[tab.key] ?? []).length})</span>
              </button>
            ))}
          </div>
        </div>

        <table className="w-full">
          <thead>
            <tr className="bg-[rgba(0,0,0,0.02)] dark:bg-[rgba(255,255,255,0.03)] border-b border-[rgba(0,0,0,0.12)] dark:border-[rgba(255,255,255,0.12)]">
              <th className={`px-5 py-2 text-left text-[11px] font-semibold uppercase tracking-wider ${MUTED}`}>#</th>
              <th className={`px-5 py-2 text-left text-[11px] font-semibold uppercase tracking-wider ${MUTED}`}>Workload</th>
              <th className={`px-5 py-2 text-left text-[11px] font-semibold uppercase tracking-wider ${MUTED}`}>Namespace</th>
              <th className={`px-5 py-2 text-left text-[11px] font-semibold uppercase tracking-wider ${MUTED}`}>Cost share</th>
              <th className={`px-5 py-2 text-right text-[11px] font-semibold uppercase tracking-wider ${MUTED}`}>Daily</th>
              <th className={`px-5 py-2 text-right text-[11px] font-semibold uppercase tracking-wider ${MUTED}`}>Monthly</th>
            </tr>
          </thead>
          <tbody>
            {activeWorkloads.slice(workloadPage * itemsPerPage, (workloadPage + 1) * itemsPerPage).map((wl, i) => {
              const rank = workloadPage * itemsPerPage + i + 1;
              const monthly = wl.cost?.monthlyCost ?? null;
              const costShare = monthly != null ? (monthly / Math.max(maxWorkloadMonthly, 0.01)) * 100 : 0;
              return (
                <tr key={`${wl.ref.namespace}/${wl.ref.name}`} className="border-b border-[rgba(0,0,0,0.06)] dark:border-[rgba(255,255,255,0.08)] hover:bg-[rgba(0,0,0,0.02)] dark:hover:bg-[rgba(255,255,255,0.04)] transition-colors">
                  <td className={`px-5 py-2.5 text-[11px] ${MUTED} font-mono`}>{rank}</td>
                  <td className="px-5 py-2.5">
                    <div className="flex items-center gap-2">
                      <span className={`text-[13px] font-semibold ${INK} truncate max-w-[180px]`}>{wl.ref.name}</span>
                      <span className={NEUTRAL_PILL}>{wl.ref.kind}</span>
                      {wl.cost?.capacityTypeInferred && (
                        <span className={`text-[11px] ${MUTED}`} title="On-demand assumed from a missing capacity-type label.">assumed on-demand</span>
                      )}
                    </div>
                  </td>
                  <td className={`px-5 py-2.5 text-[13px] font-mono ${FAINT}`}>{wl.ref.namespace}</td>
                  <td className="px-5 py-2.5 w-36">
                    <div className="flex items-center gap-2">
                      <div className="flex-1 h-1.5 bg-[rgba(0,0,0,0.06)] dark:bg-[rgba(255,255,255,0.08)] rounded-full overflow-hidden">
                        <div className="h-full bg-[rgba(0,0,0,0.88)] dark:bg-[rgba(255,255,255,0.88)] rounded-full" style={{ width: `${costShare}%` }} />
                      </div>
                    </div>
                  </td>
                  {monthly != null ? (
                    <>
                      <td className={`px-5 py-2.5 text-right text-[13px] font-semibold ${INK}`}>{money(monthly / 30)}</td>
                      <td className={`px-5 py-2.5 text-right text-[11px] ${MUTED}`}>{moneyWhole(monthly)}</td>
                    </>
                  ) : (
                    <>
                      <td className={`px-5 py-2.5 text-right text-[13px] font-semibold ${MUTED}`} title={costReasonCopy(wl.cost?.reason)}>{DASH}</td>
                      <td className={`px-5 py-2.5 text-right text-[11px] ${MUTED}`}>{DASH}</td>
                    </>
                  )}
                </tr>
              );
            })}
          </tbody>
        </table>
        {activeWorkloads.length === 0 && (
          <div className="py-10 text-center">
            <p className={`text-[13px] ${FAINT}`}>
              {workloadTab === 'DaemonSet'
                ? 'DaemonSets are not evaluated by the cost engine'
                : `No ${workloadTab.toLowerCase()} cost data available`}
            </p>
          </div>
        )}
        {activeWorkloads.length > itemsPerPage && (
          <div className="px-5 py-3 bg-[rgba(0,0,0,0.02)] dark:bg-[rgba(255,255,255,0.03)] border-t border-[rgba(0,0,0,0.06)] dark:border-[rgba(255,255,255,0.08)] flex items-center justify-between">
            <span className={`text-[11px] ${MUTED}`}>
              {workloadPage * itemsPerPage + 1}–{Math.min((workloadPage + 1) * itemsPerPage, activeWorkloads.length)} of {activeWorkloads.length}
            </span>
            <div className="flex gap-2">
              <button onClick={() => setWorkloadPage((p) => Math.max(0, p - 1))} disabled={workloadPage === 0} className={PAGE_BTN}>Previous</button>
              <button onClick={() => setWorkloadPage((p) => p + 1)} disabled={(workloadPage + 1) * itemsPerPage >= activeWorkloads.length} className={PAGE_BTN}>Next</button>
            </div>
          </div>
        )}
      </Surface>
    </div>
  );
};

export default EksCostTab;
