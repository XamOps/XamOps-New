/**
 * Nodes › Utilization (docs/cloudk8s/ui-revamp-design.md §10.5, D10, §14.1; plan item 25; FEAT-004).
 *
 * How busy the cluster and each node are, where the agent's Prometheus metrics report it. The old
 * Performance tab averaged CPU and memory usage into an "efficiency score" even when neither was
 * reported, turned a missing per-node value into 0 (`|| 0`), and coloured a 40-75% "optimal" ring.
 * Now: the average shows only when both cluster usages are reported; a node without usage stays "—"
 * and is left out of the averages, which say how many nodes they cover; and the band is a labelled
 * status MARK, never a coloured ring. Requested % (pod requests of allocatable) sits beside usage.
 *
 * v3 roll-out: the tab stamps PageTemplate width='table' so it renders inside the redesigned
 * [data-k8s-redesigned-main] region and the wide table scrolls inside itself. The summary MetricTile
 * grid collapses into ONE hero MetricStrip — honest to the guards: a cell whose figure isn't reported
 * shows the muted dash with its reason as the sub-line (never 0), and the average's band rides its
 * sub-line as a labelled StatusMark.
 */
import { useMemo, type ReactNode } from 'react';
import type { UseEksDashboardReturn } from '../../../hooks/useEksDashboard';
import { clusterUsagePct, nodeRows, nodeUsageByName } from '../../../utils/k8sDerivations';
import { DASH, formatPercent } from '../../../utils/k8sQuantities';
import { type ColumnDef } from '../../CloudK8s/shared/K8sDataTable';
import { DataTable } from '../../CloudK8s/shared/DataTable';
import { PageTemplate } from '../../CloudK8s/shared/PageTemplate';
import { MetricStrip, type MetricCell } from '../../CloudK8s/shared/MetricStrip';
import { RequestBar } from '../../CloudK8s/shared/RequestBar';
import { StatusMark, type StatusState } from '../../CloudK8s/shared/StatusMark';
import type { DrawerSection } from '../../CloudK8s/shared/DetailDrawer';
import { SECONDARY } from '../../CloudK8s/shared/typeScale';

export const UTILIZATION_CAPTION = 'Node utilisation';
export const USAGE_SOURCE = 'Prometheus (agent)';

/** The 40-75% band as a labelled mark (D10) — never a coloured ring. */
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
  { id: 'name', header: 'Node', priority: 'essential', width: '240px', cell: (r) => <span className="block truncate font-mono text-[12px] font-semibold text-[color:var(--chrome-ink)]" title={r.name}>{r.name}</span>, sortValue: (r) => r.name },
  { id: 'cpuUsed', header: 'CPU used', width: '180px', cell: (r) => pctBar(r.cpuUsedPct), sortValue: (r) => r.cpuUsedPct },
  { id: 'memUsed', header: 'Memory used', width: '180px', cell: (r) => pctBar(r.memUsedPct), sortValue: (r) => r.memUsedPct },
  { id: 'cpuReq', header: 'CPU requested', width: '180px', cell: (r) => pctBar(r.cpuRequestedPct), sortValue: (r) => r.cpuRequestedPct },
  { id: 'memReq', header: 'Memory requested', width: '180px', cell: (r) => pctBar(r.memRequestedPct), sortValue: (r) => r.memRequestedPct },
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

  // The average shows ONLY when both cluster usages are reported (D10) — never a one-sided score.
  const both = cpu !== null && mem !== null ? (cpu + mem) / 2 : null;
  const nodeCpu = average(rows.map((r) => r.cpuUsedPct));
  const nodeMem = average(rows.map((r) => r.memUsedPct));
  const band = both === null ? null : bandMark(both);

  // One hero MetricStrip, honest to the guards: a cell whose figure isn't reported shows the muted
  // dash (never 0) with its reason as the sub; the average rides its band as a labelled StatusMark.
  const stripCells: MetricCell[] = [
    {
      label: 'Cluster CPU used',
      value: cpu === null ? <span className="text-[color:var(--chrome-muted)]">{DASH}</span> : formatPercent(cpu, 1),
      sub: cpu === null ? `Not reported by ${USAGE_SOURCE}` : USAGE_SOURCE,
    },
    {
      label: 'Cluster memory used',
      value: mem === null ? <span className="text-[color:var(--chrome-muted)]">{DASH}</span> : formatPercent(mem, 1),
      sub: mem === null ? `Not reported by ${USAGE_SOURCE}` : USAGE_SOURCE,
    },
    {
      label: 'Average utilisation',
      value: both === null ? <span className="text-[color:var(--chrome-muted)]">{DASH}</span> : formatPercent(both),
      sub: band ? <StatusMark state={band.state} label={band.label} /> : 'Needs both CPU and memory usage',
    },
    {
      label: 'Average node CPU used',
      value: nodeCpu.value === null ? <span className="text-[color:var(--chrome-muted)]">{DASH}</span> : formatPercent(nodeCpu.value),
      sub: nodeCpu.value === null ? 'No node reports usage' : `${nodeCpu.n} of ${rows.length} nodes report usage`,
    },
    {
      label: 'Average node memory used',
      value: nodeMem.value === null ? <span className="text-[color:var(--chrome-muted)]">{DASH}</span> : formatPercent(nodeMem.value),
      sub: nodeMem.value === null ? 'No node reports usage' : `${nodeMem.n} of ${rows.length} nodes report usage`,
    },
  ];

  /** The real-data drawer for a node's usage: Used + Requested, "—" + reason when a figure is missing. */
  const rowToSections = (r: UsageRow): DrawerSection[] => [
    {
      title: 'Used',
      data: [
        { label: 'CPU used', value: r.cpuUsedPct === null ? null : pctBar(r.cpuUsedPct), reason: `Not reported by ${USAGE_SOURCE}` },
        { label: 'Memory used', value: r.memUsedPct === null ? null : pctBar(r.memUsedPct), reason: `Not reported by ${USAGE_SOURCE}` },
      ],
    },
    {
      title: 'Requested',
      data: [
        { label: 'CPU requested', value: r.cpuRequestedPct === null ? null : pctBar(r.cpuRequestedPct), reason: "The node doesn't report allocatable CPU." },
        { label: 'Memory requested', value: r.memRequestedPct === null ? null : pctBar(r.memRequestedPct), reason: "The node doesn't report allocatable memory." },
      ],
    },
  ];

  const intro: ReactNode = `How busy the cluster and each node are. Used: ${USAGE_SOURCE}, "—" where a node reports none; Requested: pod requests ÷ node allocatable.`;

  return (
    <PageTemplate
      title="Utilisation"
      intro={intro}
      width="table"
      hero={<MetricStrip cells={stripCells} aria-label="Cluster utilisation" />}
    >
      <div data-panel="utilization" className="flex min-w-0 flex-col gap-3">
        <DataTable<UsageRow>
          rows={rows}
          columns={COLUMNS}
          rowKey={(r) => r.name}
          caption={UTILIZATION_CAPTION}
          initialSort={{ columnId: 'cpuUsed', direction: 'desc' }}
          rowLabel={(r) => r.name}
          rowTitle={(r) => r.name}
          rowToSections={rowToSections}
          emptyState={<p className={`px-4 py-8 text-center ${SECONDARY}`}>The agent reported no nodes.</p>}
          source={<span className={SECONDARY}>{`Used: ${USAGE_SOURCE}, "—" where a node reports none · Requested: pod requests ÷ node allocatable`}</span>}
        />
      </div>
    </PageTemplate>
  );
}
