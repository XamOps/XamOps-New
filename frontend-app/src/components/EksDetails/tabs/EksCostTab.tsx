/**
 * EKS Cost Tab — real AWS $ from the vertical pricing engine.
 *
 * Every dollar figure is the engine's own (clusterMonthlyCost / per-workload / per-namespace /
 * per-node monthly $). Monthly is authoritative; daily = monthly / 30, hourly = monthly /
 * hoursPerMonth (design Decision 2). The engine emits null (never 0) for an unavailable figure, so
 * this tab NEVER coalesces null → 0 — it renders a dash + costReasonCopy(reason). The only coloured
 * money text goes through the <Delta> component (which carries its own aria-hidden icon).
 */

import React, { useMemo, useState } from 'react';
import { useEksClusterCost } from '../../../hooks/useEksClusterCost';
import { costReasonCopy } from './eksCostReasons';
import Delta from '../../CloudK8s/shared/status/Delta';
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
        <p className="text-[13px] text-[rgba(0,0,0,0.5)]">Loading cluster cost…</p>
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

      {/* ── KPI Strip (Cluster view) ── */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        {/* Today's Spend = derived daily */}
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-lg px-5 py-4">
          <div className="text-[11px] font-medium uppercase tracking-wider text-[rgba(0,0,0,0.4)] mb-1">TODAY'S SPEND</div>
          <div className="text-[26px] font-semibold leading-none text-[rgba(0,0,0,0.88)]">
            {dailyCost != null ? money(dailyCost) : DASH}
          </div>
          <div className="text-[11px] text-[rgba(0,0,0,0.4)] mt-1">
            {hourlyCost != null ? `per day · ${money(hourlyCost, 4)}/hr` : headlineReason}
          </div>
        </div>

        {/* Monthly Est. = clusterMonthlyCost (730-hr basis) */}
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-lg px-5 py-4">
          <div className="text-[11px] font-medium uppercase tracking-wider text-[rgba(0,0,0,0.4)] mb-1">MONTHLY EST.</div>
          <div className="text-[26px] font-semibold leading-none text-[rgba(0,0,0,0.88)]">
            {clusterMonthly != null ? moneyWhole(clusterMonthly) : DASH}
          </div>
          <div className="text-[11px] text-[rgba(0,0,0,0.4)] mt-1">730-hr basis</div>
        </div>

        {/* Potential Savings = cost.monthlySavings verbatim (Decision 3) */}
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-lg px-5 py-4">
          <div className="text-[11px] font-medium uppercase tracking-wider text-[rgba(0,0,0,0.4)] mb-1">POTENTIAL SAVINGS</div>
          {cost?.monthlySavings == null ? (
            <>
              <div className="text-[26px] font-semibold leading-none text-[rgba(0,0,0,0.88)]">{DASH}</div>
              <div className="text-[11px] text-[rgba(0,0,0,0.4)] mt-1">{costReasonCopy(cost?.monthlySavingsUnavailableReason)}</div>
            </>
          ) : cost.monthlySavings > 0 ? (
            <>
              <div className="leading-none">
                <Delta direction="saved" amount={`${moneyWhole(cost.monthlySavings)}/mo`} />
              </div>
              <div className="text-[11px] text-[rgba(0,0,0,0.4)] mt-1">per month identified</div>
            </>
          ) : (
            <>
              <div className="text-[26px] font-semibold leading-none text-[rgba(0,0,0,0.88)]">$0/mo</div>
              <div className="text-[11px] text-[rgba(0,0,0,0.4)] mt-1">No rightsizing recommendations yet</div>
            </>
          )}
        </div>

        {/* Priced namespaces = namespaceCosts.length (Decision 5, Finding 4) */}
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-lg px-5 py-4">
          <div className="text-[11px] font-medium uppercase tracking-wider text-[rgba(0,0,0,0.4)] mb-1">PRICED NAMESPACES</div>
          <div className="text-[26px] font-semibold leading-none text-[rgba(0,0,0,0.88)]">{namespaceCosts.length}</div>
          <div className="text-[11px] text-[rgba(0,0,0,0.4)] mt-1">with evaluated workloads</div>
        </div>
      </div>

      {headlineUnavailable && (
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-lg px-5 py-3">
          <p className="text-[13px] text-[rgba(0,0,0,0.5)]">{headlineReason}</p>
        </div>
      )}

      {/* ── Namespace Cost Breakdown ── */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-lg overflow-hidden">
        <div className="px-5 py-3.5 border-b border-[rgba(0,0,0,0.06)] flex items-center justify-between">
          <span className="text-sm font-semibold text-[rgba(0,0,0,0.88)]">Namespace Cost Breakdown</span>
          <span className="rounded-full px-2.5 py-0.5 text-[11px] font-semibold border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.5)]">
            {namespaceCosts.length} namespaces
          </span>
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
                      <span className="text-[11px] text-[rgba(0,0,0,0.4)] font-mono w-4">{idx + 1}</span>
                      <span className="font-mono text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">{ns.namespace}</span>
                      <span className="rounded-full px-2 py-0.5 text-[11px] font-semibold border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.5)]">
                        {ns.pricedWorkloads} priced · {ns.unpricedWorkloads} unpriced
                      </span>
                    </div>
                    <div className="flex items-center gap-3">
                      {priceable ? (
                        <>
                          <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">
                            {money(ns.monthlyCost! / 30)}
                            <span className="text-[11px] text-[rgba(0,0,0,0.4)] font-normal">/day</span>
                          </span>
                          <span className="text-[11px] text-[rgba(0,0,0,0.4)] w-16 text-right">{moneyWhole(ns.monthlyCost!)}/mo</span>
                        </>
                      ) : (
                        <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.4)]" title="No workload in this namespace was priceable.">{DASH}</span>
                      )}
                    </div>
                  </div>
                </div>
              );
            })}
          {namespaceCosts.length === 0 && (
            <div className="py-12 text-center">
              <p className="text-[13px] text-[rgba(0,0,0,0.5)]">No namespace cost data available</p>
            </div>
          )}
        </div>

        {namespaceCosts.length > itemsPerPage && (
          <div className="px-5 py-3 bg-[rgba(0,0,0,0.02)] border-t border-[rgba(0,0,0,0.06)] flex items-center justify-between">
            <span className="text-[11px] text-[rgba(0,0,0,0.4)]">
              {namespacePage * itemsPerPage + 1}–{Math.min((namespacePage + 1) * itemsPerPage, namespaceCosts.length)} of {namespaceCosts.length}
            </span>
            <div className="flex gap-2">
              <button onClick={() => setNamespacePage((p) => Math.max(0, p - 1))} disabled={namespacePage === 0}
                className="px-3 py-1.5 text-[12px] font-semibold bg-white border border-[rgba(0,0,0,0.12)] rounded-lg hover:bg-[rgba(0,0,0,0.02)] disabled:opacity-40 text-[rgba(0,0,0,0.88)]">Previous</button>
              <button onClick={() => setNamespacePage((p) => p + 1)} disabled={(namespacePage + 1) * itemsPerPage >= namespaceCosts.length}
                className="px-3 py-1.5 text-[12px] font-semibold bg-white border border-[rgba(0,0,0,0.12)] rounded-lg hover:bg-[rgba(0,0,0,0.02)] disabled:opacity-40 text-[rgba(0,0,0,0.88)]">Next</button>
            </div>
          </div>
        )}
      </div>

      {/* ── Node Fleet Cost (full fleet) ── */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-lg overflow-hidden">
        <div className="px-5 py-3.5 border-b border-[rgba(0,0,0,0.06)]">
          <span className="text-sm font-semibold text-[rgba(0,0,0,0.88)]">Node Fleet Cost</span>
          <span className="ml-2 text-[11px] text-[rgba(0,0,0,0.4)]">{nodeCosts.length} nodes · per-node monthly spend</span>
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
                <div key={node.nodeName || i} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-lg p-4 hover:bg-[rgba(0,0,0,0.02)] transition-colors">
                  <div className="flex items-start justify-between mb-3">
                    <div className="min-w-0 flex-1">
                      <p className="text-[13px] font-mono font-semibold text-[rgba(0,0,0,0.88)] truncate">{node.nodeName}</p>
                      <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-0.5">
                        {instanceType}
                        {node.capacityTypeInferred && <span className="ml-1" title="On-demand assumed from a missing capacity-type label; price is real but not authoritative.">· assumed on-demand</span>}
                      </p>
                    </div>
                    {node.capacityType && (
                      <span className="rounded-full px-2.5 py-0.5 text-[11px] font-semibold border ml-2 flex-shrink-0"
                        style={isSpot
                          ? { color: '#0550ae', background: '#ddf4ff', borderColor: 'rgba(84,174,255,0.4)' }
                          : { color: 'rgba(0,0,0,0.5)', background: 'rgba(0,0,0,0.02)', borderColor: 'rgba(0,0,0,0.12)' }}>
                        {isSpot ? 'Spot' : 'On-Demand'}
                      </span>
                    )}
                  </div>

                  {priceable ? (
                    <>
                      <div className="text-[26px] font-semibold text-[rgba(0,0,0,0.88)] leading-none mb-1">
                        {money(node.monthlyCost! / 30)}<span className="text-[13px] text-[rgba(0,0,0,0.4)] font-normal">/day</span>
                      </div>
                      <p className="text-[11px] text-[rgba(0,0,0,0.4)] mb-3">
                        {moneyWhole(node.monthlyCost!)}/mo · {money(node.monthlyCost! / hoursPerMonth, 4)}/hr
                      </p>
                    </>
                  ) : (
                    <>
                      <div className="text-[26px] font-semibold text-[rgba(0,0,0,0.4)] leading-none mb-1">{DASH}</div>
                      <p className="text-[11px] text-[rgba(0,0,0,0.4)] mb-3">{costReasonCopy(node.reason)}</p>
                    </>
                  )}

                  <span className="rounded-full px-2.5 py-0.5 text-[11px] font-semibold border"
                    style={isReady
                      ? { color: '#1a7f37', background: '#dafbe1', borderColor: 'rgba(74,194,107,0.4)' }
                      : { color: '#cf222e', background: '#ffebe9', borderColor: 'rgba(255,129,130,0.4)' }}>
                    {isReady ? 'Ready' : (dashNode?.condition || 'Not Ready')}
                  </span>
                </div>
              );
            })}
          </div>

          {nodeCosts.length === 0 && (
            <div className="py-10 text-center col-span-3">
              <p className="text-[13px] text-[rgba(0,0,0,0.5)]">No node cost data available</p>
            </div>
          )}
        </div>

        {nodeCosts.length > nodeItemsPerPage && (
          <div className="flex items-center justify-between mt-4 pt-4 border-t border-[rgba(0,0,0,0.06)] px-5 pb-4">
            <span className="text-[11px] text-[rgba(0,0,0,0.4)]">
              {nodePage * nodeItemsPerPage + 1}–{Math.min((nodePage + 1) * nodeItemsPerPage, nodeCosts.length)} of {nodeCosts.length}
            </span>
            <div className="flex gap-2">
              <button onClick={() => setNodePage((p) => Math.max(0, p - 1))} disabled={nodePage === 0}
                className="px-3 py-1.5 text-[12px] font-semibold bg-white border border-[rgba(0,0,0,0.12)] rounded-lg hover:bg-[rgba(0,0,0,0.02)] disabled:opacity-40 text-[rgba(0,0,0,0.88)]">Previous</button>
              <button onClick={() => setNodePage((p) => p + 1)} disabled={(nodePage + 1) * nodeItemsPerPage >= nodeCosts.length}
                className="px-3 py-1.5 text-[12px] font-semibold bg-white border border-[rgba(0,0,0,0.12)] rounded-lg hover:bg-[rgba(0,0,0,0.02)] disabled:opacity-40 text-[rgba(0,0,0,0.88)]">Next</button>
            </div>
          </div>
        )}
      </div>

      {/* ── Workload Cost Ranking ── */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-lg overflow-hidden">
        <div className="px-5 py-3.5 border-b border-[rgba(0,0,0,0.06)] flex items-center justify-between flex-wrap gap-3">
          <span className="text-sm font-semibold text-[rgba(0,0,0,0.88)]">Workload Cost Ranking</span>
          <div className="flex items-center gap-1">
            {([
              { key: 'Deployment', label: 'Deployments' },
              { key: 'DaemonSet', label: 'DaemonSets' },
              { key: 'StatefulSet', label: 'StatefulSets' },
            ] as const).map((tab) => (
              <button key={tab.key}
                onClick={() => { setWorkloadTab(tab.key); setWorkloadPage(0); }}
                className={`px-3 py-1.5 rounded-lg text-[12px] font-semibold transition-colors ${workloadTab === tab.key ? 'bg-[rgba(0,0,0,0.88)] text-white' : 'bg-white text-[rgba(0,0,0,0.5)] border border-[rgba(0,0,0,0.12)] hover:bg-[rgba(0,0,0,0.02)]'}`}>
                {tab.label} <span className="opacity-70">({(workloadsByKind[tab.key] ?? []).length})</span>
              </button>
            ))}
          </div>
        </div>

        <table className="w-full">
          <thead>
            <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.12)]">
              <th className="px-5 py-2 text-left text-[11px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.4)]">#</th>
              <th className="px-5 py-2 text-left text-[11px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.4)]">Workload</th>
              <th className="px-5 py-2 text-left text-[11px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.4)]">Namespace</th>
              <th className="px-5 py-2 text-left text-[11px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.4)]">Cost share</th>
              <th className="px-5 py-2 text-right text-[11px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.4)]">Daily</th>
              <th className="px-5 py-2 text-right text-[11px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.4)]">Monthly</th>
            </tr>
          </thead>
          <tbody>
            {activeWorkloads.slice(workloadPage * itemsPerPage, (workloadPage + 1) * itemsPerPage).map((wl, i) => {
              const rank = workloadPage * itemsPerPage + i + 1;
              const monthly = wl.cost?.monthlyCost ?? null;
              const costShare = monthly != null ? (monthly / Math.max(maxWorkloadMonthly, 0.01)) * 100 : 0;
              return (
                <tr key={`${wl.ref.namespace}/${wl.ref.name}`} className="border-b border-[rgba(0,0,0,0.06)] hover:bg-[rgba(0,0,0,0.02)] transition-colors">
                  <td className="px-5 py-2.5 text-[11px] text-[rgba(0,0,0,0.4)] font-mono">{rank}</td>
                  <td className="px-5 py-2.5">
                    <div className="flex items-center gap-2">
                      <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] truncate max-w-[180px]">{wl.ref.name}</span>
                      <span className="rounded-full px-2 py-0.5 text-[11px] font-semibold border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.5)]">{wl.ref.kind}</span>
                      {wl.cost?.capacityTypeInferred && (
                        <span className="text-[11px] text-[rgba(0,0,0,0.4)]" title="On-demand assumed from a missing capacity-type label.">assumed on-demand</span>
                      )}
                    </div>
                  </td>
                  <td className="px-5 py-2.5 text-[13px] font-mono text-[rgba(0,0,0,0.5)]">{wl.ref.namespace}</td>
                  <td className="px-5 py-2.5 w-36">
                    <div className="flex items-center gap-2">
                      <div className="flex-1 h-1.5 bg-[rgba(0,0,0,0.06)] rounded-full overflow-hidden">
                        <div className="h-full bg-[rgba(0,0,0,0.88)] rounded-full" style={{ width: `${costShare}%` }} />
                      </div>
                    </div>
                  </td>
                  {monthly != null ? (
                    <>
                      <td className="px-5 py-2.5 text-right text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">{money(monthly / 30)}</td>
                      <td className="px-5 py-2.5 text-right text-[11px] text-[rgba(0,0,0,0.4)]">{moneyWhole(monthly)}</td>
                    </>
                  ) : (
                    <>
                      <td className="px-5 py-2.5 text-right text-[13px] font-semibold text-[rgba(0,0,0,0.4)]" title={costReasonCopy(wl.cost?.reason)}>{DASH}</td>
                      <td className="px-5 py-2.5 text-right text-[11px] text-[rgba(0,0,0,0.4)]">{DASH}</td>
                    </>
                  )}
                </tr>
              );
            })}
          </tbody>
        </table>
        {activeWorkloads.length === 0 && (
          <div className="py-10 text-center">
            <p className="text-[13px] text-[rgba(0,0,0,0.5)]">
              {workloadTab === 'DaemonSet'
                ? 'DaemonSets are not evaluated by the cost engine'
                : `No ${workloadTab.toLowerCase()} cost data available`}
            </p>
          </div>
        )}
        {activeWorkloads.length > itemsPerPage && (
          <div className="px-5 py-3 bg-[rgba(0,0,0,0.02)] border-t border-[rgba(0,0,0,0.06)] flex items-center justify-between">
            <span className="text-[11px] text-[rgba(0,0,0,0.4)]">
              {workloadPage * itemsPerPage + 1}–{Math.min((workloadPage + 1) * itemsPerPage, activeWorkloads.length)} of {activeWorkloads.length}
            </span>
            <div className="flex gap-2">
              <button onClick={() => setWorkloadPage((p) => Math.max(0, p - 1))} disabled={workloadPage === 0}
                className="px-3 py-1.5 text-[12px] font-semibold bg-white border border-[rgba(0,0,0,0.12)] rounded-lg hover:bg-[rgba(0,0,0,0.02)] disabled:opacity-40 text-[rgba(0,0,0,0.88)]">Previous</button>
              <button onClick={() => setWorkloadPage((p) => p + 1)} disabled={(workloadPage + 1) * itemsPerPage >= activeWorkloads.length}
                className="px-3 py-1.5 text-[12px] font-semibold bg-white border border-[rgba(0,0,0,0.12)] rounded-lg hover:bg-[rgba(0,0,0,0.02)] disabled:opacity-40 text-[rgba(0,0,0,0.88)]">Next</button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default EksCostTab;
