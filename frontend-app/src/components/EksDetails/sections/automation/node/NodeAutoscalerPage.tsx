/**
 * Automation › Node autoscaler (UI v3 plan §1 deviation 1, §2 "Node autoscaler"; glossary §12).
 *
 * One page, not a group: Settings, Rebalancing and Spot handling have no honest content until the
 * automation agent and its endpoints ship. The page shows, each from its own source:
 *  - who changes the node count: Karpenter, the Cluster Autoscaler and managed node groups from the
 *    XamOps agent's data; the automation agent from capabilities and its own dashboard;
 *  - the KPIs: nodes, pods waiting for a node, CPU and memory requested of allocatable (the XamOps
 *    agent), idle nodes (the automation agent's node report, only when it reported);
 *  - CPU and pods on each node against what the node gives pods;
 *  - on-demand and Spot nodes, and the last rebalance in words;
 *  - "What node autoscaling would do now": one row per part, each "Not available yet" with why.
 * There are no controls and no settings forms: nothing on this page sends a request but reads. The
 * cluster's CPU over time needs the usage feed, so that chart says so.
 */
import { useId, type ReactNode } from 'react';
import { useK8sCapabilities } from '../../../../../hooks/useK8sCapabilities';
import useNodeAutomationRead from '../../../../../hooks/useNodeAutomationRead';
import type { UseEksDashboardReturn } from '../../../../../hooks/useEksDashboard';
import type { CapabilitiesReadiness } from '../../../../../types/k8sAutomation';
import { DASH } from '../../../../../api/verticalRightsizerAdapter';
import { FeatureMark } from '../../../../CloudK8s/shared/status/FeatureMark';
import { KpiCard } from '../../../../CloudK8s/shared/status/KpiCard';
import { NowBanner } from '../../../../CloudK8s/shared/status/NowBanner';
import { StatusChip } from '../../../../CloudK8s/shared/status/StatusChip';
import { nowSentence, type NowSentence } from '../../../../CloudK8s/shared/status/nowSentence';
import { Bars } from '../../../../CloudK8s/shared/charts/Bars';
import { ChartCard } from '../../../../CloudK8s/shared/charts/ChartCard';
import { ChartDataTable } from '../../../../CloudK8s/shared/charts/ChartDataTable';
import { SeriesLegend } from '../../../../CloudK8s/shared/charts/SeriesLegend';
import { nodeProvisioners } from '../../../../../utils/k8sDerivations';
import {
  automationAgentLine,
  automationAgentState,
  formatMemory,
  formatVcpu,
  idleNodeCount,
  nodeBarRows,
  nodeChangers,
  nodeKpis,
  nodeLevers,
  rebalanceText,
  type NodeBarRow,
  type NodeChangerLine,
  type NodeKpis,
} from '../automationDerivations';
import { CARD, Card, DIVIDE, FAINT, INK, MUTED, RULE } from '../automationUi';

export interface NodeAutoscalerPageProps {
  clusterName: string;
  accountId: string;
  region: string;
  /** The page's dashboard: nodes, pods, Deployments and Karpenter, from the XamOps agent. */
  eksData?: UseEksDashboardReturn;
}

const pct = (v: number | null): string => (v === null ? DASH : `${Math.round(v)}%`);
/** "ip-10-0-1-106" from "ip-10-0-1-106.ap-south-1.compute.internal". */
const shortNode = (name: string): string => name.split('.')[0];

function ChangerRow({ line }: { line: NodeChangerLine }) {
  return (
    <li className="py-2" data-node-changer={line.id}>
      <StatusChip tone={line.tone} icon={line.icon} label={line.label} />
      {line.detail && <p className={`mt-0.5 text-[12px] ${MUTED}`}>{line.detail}</p>}
    </li>
  );
}

function WhoChanges({ lines, agentLine }: { lines: NodeChangerLine[] | null; agentLine: NodeChangerLine }) {
  return (
    <Card title="Who changes the node count on this cluster">
      {lines === null ? (
        <p className={`text-[12px] ${MUTED}`} data-node-changer="not-checked">
          {DASH} Not checked: the XamOps agent hasn&apos;t reported this cluster&apos;s nodes and Deployments, so XamOps can&apos;t
          tell what adds or removes nodes.
        </p>
      ) : (
        <ul className={`divide-y ${DIVIDE}`}>
          {lines.map((l) => (
            <ChangerRow key={l.id} line={l} />
          ))}
        </ul>
      )}
      <ul className={`border-t ${RULE}`}>
        <ChangerRow line={agentLine} />
      </ul>
      <p className={`mt-2 text-[11px] ${FAINT}`}>
        Source: the XamOps agent (node labels, Deployments and Karpenter&apos;s own report); the automation agent from
        capabilities and its dashboard (GET /api/eks/autoscaling/{'{cluster}'}).
      </p>
    </Card>
  );
}

function NodeKpiRow({ k, idle, agentReported }: { k: NodeKpis; idle: number | null; agentReported: boolean }) {
  const { onDemand, spot, unknown } = k.capacity;
  return (
    <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-5" data-node-kpis="">
      <KpiCard tone={k.nodes > 0 && k.ready === k.nodes ? 'good' : k.nodes > 0 ? 'warn' : 'off'} icon="mdi:server" label="Nodes" value={agentReported ? k.nodes : DASH}>
        {agentReported ? (
          <>
            <p>{k.ready} ready</p>
            <p>On-demand {onDemand} · Spot {spot}{unknown > 0 ? ` · ${unknown} with no capacity type` : ''}</p>
          </>
        ) : (
          <p>from the XamOps agent</p>
        )}
      </KpiCard>
      <KpiCard tone={k.waiting === null ? 'off' : k.waiting > 0 ? 'bad' : 'good'} icon={k.waiting ? 'mdi:alert-octagon-outline' : 'mdi:check-circle'} label="Pods waiting for a node" value={k.waiting ?? DASH}>
        <p>
          {k.waiting === null
            ? 'from the XamOps agent'
            : k.waiting > 0 && k.biggestWaiting
              ? `${k.biggestWaiting.namespace}/${k.biggestWaiting.name} asks for ${formatVcpu(k.biggestWaiting.cpuM)} and ${formatMemory(k.biggestWaiting.memMi)}`
              : 'Every pod has a node'}
        </p>
      </KpiCard>
      <KpiCard tone={k.cpu.requestedPct === null ? 'off' : k.cpu.requestedPct >= 80 ? 'warn' : 'info'} icon="mdi:gauge" label="CPU requested" value={pct(k.cpu.requestedPct)}>
        <p>
          {k.cpu.allocatableM !== null ? `${formatVcpu(k.cpu.requestedM)} of ${formatVcpu(k.cpu.allocatableM)} pods can use` : 'allocatable not reported'}
        </p>
        <p>{k.cpu.usedPct !== null ? `Used ${pct(k.cpu.usedPct)}` : 'Use: not reported by Prometheus in the cluster'}</p>
      </KpiCard>
      <KpiCard tone={k.memory.requestedPct === null ? 'off' : k.memory.requestedPct >= 80 ? 'warn' : 'info'} icon="mdi:memory" label="Memory requested" value={pct(k.memory.requestedPct)}>
        <p>
          {k.memory.allocatableMi !== null ? `${formatMemory(k.memory.requestedMi)} of ${formatMemory(k.memory.allocatableMi)} pods can use` : 'allocatable not reported'}
        </p>
      </KpiCard>
      <KpiCard tone={idle === null ? 'off' : idle > 0 ? 'warn' : 'good'} icon="mdi:sleep" label="Idle nodes" value={idle ?? DASH}>
        <p>{idle === null ? "Needs the node automation agent's node report" : "from the node automation agent's node report"}</p>
      </KpiCard>
    </div>
  );
}

const CPU_BARS = [
  { dataKey: 'allocatable', name: 'Allocatable', kind: 'capacity' as const },
  { dataKey: 'requested', name: 'Requested', kind: 'requested' as const },
];
const POD_BARS = [
  { dataKey: 'allowed', name: 'Pods the node accepts', kind: 'capacity' as const },
  { dataKey: 'pods', name: 'Pods', kind: 'requested' as const },
];

function NodeCharts({ rows }: { rows: NodeBarRow[] }) {
  const withPods = rows.filter((r) => r.allocatablePods !== null);
  const height = (n: number) => Math.max(120, 44 * n + 40);
  return (
    <div className="grid gap-4 xl:grid-cols-2">
      <ChartCard
        title="CPU on each node: requested of allocatable"
        subline="What the pods on each node have set aside, against what the node gives pods. Highest first."
        source="The XamOps agent (k8sNodes allocatable, k8sPods requests of the pods scheduled on each node)."
        state={rows.length === 0 ? { kind: 'unavailable', reason: "The XamOps agent hasn't reported this cluster's nodes." } : { kind: 'ok' }}
        table={
          rows.length > 0 ? (
            <ChartDataTable
              caption="CPU on each node"
              rowHeader="Node"
              columns={[{ key: 'requested', header: 'Requested' }, { key: 'allocatable', header: 'Allocatable' }, { key: 'pct', header: 'Requested of allocatable' }]}
              rows={rows.map((r) => ({
                key: r.node,
                label: r.node,
                values: {
                  requested: `${r.requestedCpu} vCPU`,
                  allocatable: r.allocatableCpu === null ? DASH : `${r.allocatableCpu} vCPU`,
                  pct: pct(r.cpuRequestedPct),
                },
              }))}
            />
          ) : undefined
        }
      >
        <SeriesLegend items={CPU_BARS.map((b) => ({ label: b.name, kind: b.kind }))} className="mb-2" />
        <Bars
          mode="capacity"
          data={rows.map((r) => ({ node: shortNode(r.node), requested: r.requestedCpu, allocatable: r.allocatableCpu }))}
          bars={CPU_BARS}
          xKey="node"
          height={height(rows.length)}
          categoryWidth={140}
          tickFormat={(v) => `${v} vCPU`}
          ariaLabel="CPU requested and allocatable on each node, in vCPU"
        />
      </ChartCard>
      {withPods.length > 0 && (
        <ChartCard
          title="Pods on each node"
          subline="Pods scheduled on each node against how many the node accepts (its allocatable pods)."
          source="The XamOps agent (k8sNodes allocatablePods, k8sPods spec.nodeName for pods that haven't finished)."
          table={
            <ChartDataTable
              caption="Pods on each node"
              rowHeader="Node"
              columns={[{ key: 'pods', header: 'Pods' }, { key: 'allowed', header: 'Pods the node accepts' }]}
              rows={withPods.map((r) => ({ key: r.node, label: r.node, values: { pods: String(r.pods), allowed: String(r.allocatablePods) } }))}
            />
          }
        >
          <SeriesLegend items={POD_BARS.map((b) => ({ label: b.name, kind: b.kind }))} className="mb-2" />
          <Bars
            mode="capacity"
            data={withPods.map((r) => ({ node: shortNode(r.node), pods: r.pods, allowed: r.allocatablePods }))}
            bars={POD_BARS}
            xKey="node"
            height={height(withPods.length)}
            categoryWidth={140}
            ariaLabel="Pods on each node against the pods each node accepts"
          />
        </ChartCard>
      )}
    </div>
  );
}

function Fact({ term, children }: { term: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-[160px_minmax(0,1fr)] gap-3 py-2 text-[12px]">
      <dt className={`font-semibold ${MUTED}`}>{term}</dt>
      <dd className={INK}>{children}</dd>
    </div>
  );
}

function Levers({ levers }: { levers: ReturnType<typeof nodeLevers> }) {
  const titleId = useId();
  return (
    <section className={CARD} aria-labelledby={titleId} data-node-levers="">
      <div className="px-4 pt-3">
        <h3 id={titleId} className={`text-[13px] font-semibold ${INK}`}>What node autoscaling would do now</h3>
        <p className={`mt-0.5 text-[12px] ${MUTED}`}>Each part on its own, for the cluster as it is now. Nothing here has been done.</p>
      </div>
      <div className="mt-2 overflow-x-auto">
        <table className="w-full border-collapse text-left text-[12px]" aria-labelledby={titleId}>
          <thead>
            <tr className={`border-y ${RULE} text-[11px] ${MUTED}`}>
              <th scope="col" className="px-4 py-2 font-semibold">Part</th>
              <th scope="col" className="px-4 py-2 font-semibold">Now</th>
              <th scope="col" className="px-4 py-2 font-semibold">In XamOps</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[rgba(0,0,0,0.06)] dark:divide-[rgba(255,255,255,0.08)]">
            {levers.map((l) => (
              <tr key={l.id} className="align-top" data-node-lever={l.id}>
                <th scope="row" className={`px-4 py-2.5 font-semibold ${INK}`}>
                  {l.name}
                  <span className={`block text-[11px] font-normal ${FAINT}`}>{l.line}</span>
                </th>
                <td className={`px-4 py-2.5 ${INK}`}>
                  {l.verdict}
                  <span className={`block text-[11px] ${FAINT}`}>Source: {l.source}</span>
                </td>
                <td className="px-4 py-2.5">
                  <StatusChip tone="off" icon="mdi:minus-circle-outline" label="Not available yet" detail={l.reason} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

export function NodeAutoscalerPage({ clusterName, accountId, region, eksData }: NodeAutoscalerPageProps) {
  const caps = useK8sCapabilities();
  const reads = useNodeAutomationRead(clusterName, accountId, region);
  const dashboard = eksData?.dashboard ?? null;
  const capsCluster = caps.status === 'ready' ? caps.forCluster(clusterName) : null;

  const changers = nodeChangers(dashboard, clusterName);
  const agent = automationAgentState(caps.status, capsCluster);
  const agentLine = automationAgentLine(caps.status, capsCluster, reads.dashboard);
  const k = nodeKpis(dashboard);
  const agentReported = changers !== null;
  const fleet = reads.fleet.kind === 'ok' ? reads.fleet.data : null;
  const idle = idleNodeCount(agent, fleet);
  const p = agentReported ? nodeProvisioners(dashboard) : null;
  const provisioners = p ? { karpenter: p.karpenter !== null, clusterAutoscaler: p.clusterAutoscaler } : null;
  const readiness: Record<string, CapabilitiesReadiness | undefined> = caps.data?.readiness ?? {};
  const notReady = (key: 'node-optimizer-actions' | 'evictor-live' | 'rebalancer'): string | null => {
    const r = readiness[key];
    return r && !r.ready ? r.message : null;
  };
  const levers = nodeLevers({
    agent,
    provisioners,
    kpis: k,
    idle,
    readiness: { 'node-optimizer-actions': notReady('node-optimizer-actions'), 'evictor-live': notReady('evictor-live'), rebalancer: notReady('rebalancer') },
  });
  const rows = nodeBarRows(dashboard);

  let sentence: NowSentence;
  if (eksData?.loading && !dashboard) sentence = { clauses: ['Reading who changes the node count on this cluster…'], chips: [] };
  else {
    sentence = nowSentence('node-autoscaler', {
      cluster: clusterName,
      provisioners: provisioners ?? undefined,
      nodes: agentReported ? { total: k.nodes, ...k.capacity } : undefined,
      cpu: k.cpu.requestedPct !== null ? { requestedPct: k.cpu.requestedPct, usedPct: k.cpu.usedPct ?? undefined } : undefined,
      waiting: k.waiting ?? undefined,
      idle: idle ?? undefined,
      automationAgent: agent === 'unknown' ? undefined : agent,
    });
  }

  const rebalance = reads.rebalance;
  return (
    // data-k8s-kit: every colour on this page has its dark variant, so the legacy dark CSS leaves it alone.
    <div className="space-y-4" data-node-autoscaler="" data-k8s-kit="">
      <div className="space-y-1">
        <FeatureMark feature="node" title="Node autoscaler" />
        <p className={`text-[12px] ${MUTED}`}>
          Adds nodes when pods can&apos;t be scheduled, removes empty nodes and packs pods onto fewer nodes. XamOps empties and
          packs nodes with its automation agent; Karpenter or the Cluster Autoscaler adds and removes them.
        </p>
      </div>
      <NowBanner sentence={sentence} />
      <WhoChanges lines={changers} agentLine={agentLine} />
      <NodeKpiRow k={k} idle={idle} agentReported={agentReported} />
      <NodeCharts rows={rows} />
      <Card title="Capacity type and rebalancing">
        <dl className={`divide-y ${DIVIDE}`}>
          <Fact term="On-demand and Spot">
            {agentReported
              ? `${k.capacity.onDemand} on-demand, ${k.capacity.spot} Spot${k.capacity.unknown > 0 ? `, ${k.capacity.unknown} with no capacity type` : ''}`
              : `${DASH} from the XamOps agent`}
          </Fact>
          <Fact term="Last rebalance">
            {rebalance.kind === 'loading'
              ? 'Checking…'
              : rebalance.kind === 'error'
                ? `Not checked: ${rebalance.message}`
                : rebalanceText(rebalance.data)}
          </Fact>
        </dl>
        <p className={`mt-2 text-[11px] ${FAINT}`}>
          Source: node capacity-type labels from the XamOps agent; the last rebalance from the automation agent
          (GET /api/eks/autoscaling/{'{cluster}'}/rebalance-status).
        </p>
      </Card>
      <Levers levers={levers} />
      <ChartCard
        title="CPU: what pods can use, reserve and use"
        source="Usage feed (planned): allocatable, requested and used CPU for the whole cluster from Prometheus in your cluster, 3 days at 1-hour steps."
        state={{ kind: 'history-pending', from: 'It will come from Prometheus in your cluster: what the nodes give pods, what pods reserve and what they use.' }}
      />
    </div>
  );
}

export default NodeAutoscalerPage;
