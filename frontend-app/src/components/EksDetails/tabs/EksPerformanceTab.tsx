/**
 * Nodes › Utilization (docs/cloudk8s/ui-revamp-design.md §10.5, D10, §14.1; plan item 25).
 *
 * How busy the cluster and each node are, where the agent's Prometheus metrics report it. The old
 * Performance tab averaged CPU and memory usage into an "efficiency score" even when neither was
 * reported, turned a missing per-node value into 0 (`|| 0`), and coloured a 40-75% "optimal" ring.
 * Now: the average shows only when both cluster usages are reported; a node without usage stays "—"
 * and is left out of the averages, which say how many nodes they cover; and the band is a labelled
 * status mark, not a colour. Requested % (pod requests of allocatable) sits beside usage.
 */
import { useMemo } from 'react';
import type { UseEksDashboardReturn } from '../../../hooks/useEksDashboard';
import { clusterUsagePct, nodeRows, nodeUsageByName } from '../../../utils/k8sDerivations';
import { DASH, formatPercent } from '../../../utils/k8sQuantities';
import { K8sDataTable, type ColumnDef } from '../../CloudK8s/shared/K8sDataTable';
import { MetricTile } from '../../CloudK8s/shared/MetricTile';
import { RequestBar } from '../../CloudK8s/shared/RequestBar';
import { StatusMark, type StatusState } from '../../CloudK8s/shared/StatusMark';

export const UTILIZATION_CAPTION = 'Node utilisation';
export const USAGE_SOURCE = 'Prometheus (agent)';
const secondary = 'text-[12px] text-[rgba(0,0,0,0.6)]';

/** The 40-75% band as a labelled mark (D10). */
export function bandMark(pct: number): { state: StatusState; label: string } {
  if (pct < 40) return { state: 'attention', label: 'Below 40%: room to consolidate' };
  if (pct > 75) return { state: 'attention', label: 'Above 75%: little headroom' };
  return { state: 'healthy', label: 'In the 40-75% band' };
}

interface UsageRow {
  name: string;
  cpuUsedPct: number | null;
  memUsedPct: number | null;
  cpuRequestedPct: number | null;
  memRequestedPct: number | null;
}

const average = (values: Array<number | null>): { value: number | null; n: number } => {
  const known = values.filter((v): v is number => v !== null);
  return { value: known.length ? known.reduce((s, v) => s + v, 0) / known.length : null, n: known.length };
};

const pctBar = (pct: number | null) => (pct === null ? <span>{DASH}</span> : <RequestBar requested={formatPercent(pct)} allocatable={null} pct={pct} />);

const COLUMNS: ReadonlyArray<ColumnDef<UsageRow>> = [
  { id: 'name', header: 'Node', priority: 'essential', cell: (r) => <span className="font-mono text-[12px] font-semibold">{r.name}</span>, sortValue: (r) => r.name },
  { id: 'cpuUsed', header: 'CPU used', cell: (r) => pctBar(r.cpuUsedPct), sortValue: (r) => r.cpuUsedPct },
  { id: 'memUsed', header: 'Memory used', cell: (r) => pctBar(r.memUsedPct), sortValue: (r) => r.memUsedPct },
  { id: 'cpuReq', header: 'CPU requested', cell: (r) => pctBar(r.cpuRequestedPct), sortValue: (r) => r.cpuRequestedPct },
  { id: 'memReq', header: 'Memory requested', cell: (r) => pctBar(r.memRequestedPct), sortValue: (r) => r.memRequestedPct },
];

export default function EksPerformanceTab({ eksData }: { eksData: UseEksDashboardReturn }) {
  const dashboard = eksData.dashboard;
  const { rows, cpu, mem } = useMemo(() => {
    const usage = nodeUsageByName(dashboard);
    const list: UsageRow[] = nodeRows(dashboard).map((n) => ({
      name: n.name,
      cpuUsedPct: usage.get(n.name)?.cpuPct ?? null,
      memUsedPct: usage.get(n.name)?.memPct ?? null,
      cpuRequestedPct: n.cpuRequestedPct,
      memRequestedPct: n.memRequestedPct,
    }));
    return { rows: list, cpu: clusterUsagePct(dashboard, 'cpu'), mem: clusterUsagePct(dashboard, 'memory') };
  }, [dashboard]);

  const both = cpu !== null && mem !== null ? (cpu + mem) / 2 : null;
  const nodeCpu = average(rows.map((r) => r.cpuUsedPct));
  const nodeMem = average(rows.map((r) => r.memUsedPct));
  const band = both === null ? null : bandMark(both);

  return (
    <div className="space-y-4" data-panel="utilization">
      <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <MetricTile label="Cluster CPU used" value={cpu} format={(v) => formatPercent(v, 1)} source={USAGE_SOURCE} />
        <MetricTile label="Cluster memory used" value={mem} format={(v) => formatPercent(v, 1)} source={USAGE_SOURCE} />
        <MetricTile
          label="Average utilisation"
          value={both}
          format={(v) => formatPercent(v)}
          detail={band ? <StatusMark state={band.state} label={band.label} /> : undefined}
          source="CPU and memory used, averaged"
          missingText="Needs both CPU and memory usage"
        />
        <MetricTile
          label="Average node CPU used"
          value={nodeCpu.value}
          format={(v) => formatPercent(v)}
          detail={`${nodeCpu.n} of ${rows.length} nodes report usage`}
          source={USAGE_SOURCE}
          missingText="No node reports usage"
        />
      </div>
      {nodeMem.value !== null && (
        <p className={secondary}>{`Average node memory used: ${formatPercent(nodeMem.value)} over ${nodeMem.n} of ${rows.length} nodes.`}</p>
      )}
      <K8sDataTable
        rows={rows}
        columns={COLUMNS}
        rowKey={(r) => r.name}
        caption={UTILIZATION_CAPTION}
        initialSort={{ columnId: 'cpuUsed', direction: 'desc' }}
        emptyState={<p className={`px-4 py-8 text-center ${secondary}`}>The agent reported no nodes.</p>}
        source={<span className={secondary}>{`Used: ${USAGE_SOURCE}, "—" where a node reports none · Requested: pod requests ÷ node allocatable`}</span>}
      />
    </div>
  );
}
