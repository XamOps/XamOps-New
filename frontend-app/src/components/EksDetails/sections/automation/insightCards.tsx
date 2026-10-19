/**
 * Chart cards more than one Automation page shows, on the Phase-2 reads (FEAT-004):
 *  - the cluster's CPU (or memory) as a stack of used, reserved but unused, and not requested,
 *    adding up to allocatable, with the provisioned or allocatable line: Overview's "CPU and memory
 *    over time" and Node autoscaler's "CPU: what pods can use, reserve and use".
 * Each card makes its own GET, names its source on screen and shows the honest state (loading,
 * partial, unavailable in words, error with Retry); a backend without the usage feed (an untyped
 * 404) renders no card at all.
 */
import { useState } from 'react';
import useAutomationUsage from '../../../../hooks/useAutomationUsage';
import type { UsageResource } from '../../../../types/automationInsights';
import { ChartCard, type ChartEmptyState } from '../../../CloudK8s/shared/charts/ChartCard';
import { ChartDataTable } from '../../../CloudK8s/shared/charts/ChartDataTable';
import { SeriesLegend } from '../../../CloudK8s/shared/charts/SeriesLegend';
import { StackedArea } from '../../../CloudK8s/shared/charts/StackedArea';
import {
  AXIS_NAME,
  axisText,
  clusterStackRows,
  coverageInfo,
  insightCardState,
  noDataBand,
  sourceService,
  stepWords,
  windowText,
} from './insightCharts';
import { istShort } from './automationDerivations';
import { FAINT, FOCUS, MUTED } from './automationUi';

/** CPU | Memory, as pressed buttons (an in-page view switch, never navigation). */
export function ResourceToggle({ value, onChange, options = ['cpu', 'memory'] }: { value: UsageResource; onChange: (r: UsageResource) => void; options?: readonly UsageResource[] }) {
  return (
    <div role="group" aria-label="Resource" className="inline-flex overflow-hidden rounded-lg border border-[rgba(0,0,0,0.12)] dark:border-[rgba(255,255,255,0.16)]">
      {options.map((r) => (
        <button
          key={r}
          type="button"
          aria-pressed={value === r}
          onClick={() => onChange(r)}
          className={`px-3 py-1 text-[12px] font-semibold ${FOCUS} ${value === r ? 'bg-[rgba(0,0,0,0.88)] text-white dark:bg-[rgba(255,255,255,0.92)] dark:text-black' : `bg-white dark:bg-[#141414] ${MUTED}`}`}
        >
          {r === 'cpu' ? 'CPU' : 'Memory'}
        </button>
      ))}
    </div>
  );
}

export interface ClusterUsageCardProps {
  clusterName: string;
  accountId: string;
  region: string;
  /** The exact title (glossary §10). */
  title: string;
  /** One resource (no switch) or both (a CPU | Memory switch). */
  resources?: readonly UsageResource[];
  /** The line drawn over the stack: what the nodes have (provisioned) or give pods (allocatable). */
  total: 'capacity' | 'allocatable';
}

const TOTAL_NAME = { capacity: 'Provisioned', allocatable: 'Allocatable' } as const;

export function ClusterUsageCard({ clusterName, accountId, region, title, resources = ['cpu', 'memory'], total }: ClusterUsageCardProps) {
  const [resource, setResource] = useState<UsageResource>(resources[0]);
  const read = useAutomationUsage({ clusterName, accountId, region }, { scope: 'cluster', resource, window: '72h' });
  const card = insightCardState(read);
  if (!card.render) return null;

  const cpu = resource === 'cpu';
  const unit = cpu ? 'vcpu' : 'gib';
  const view = card.view;
  const rows = view ? clusterStackRows(view, unit, total) : [];
  let state: ChartEmptyState = card.state;
  let bands: ReturnType<typeof noDataBand> = [];
  if (view && card.state.kind === 'ok') {
    const coverage = coverageInfo(view, istShort);
    state = coverage.state;
    bands = noDataBand(rows, coverage.noDataBefore, `No data before ${coverage.noDataBefore !== null ? istShort(coverage.noDataBefore) : ''}`);
    if (state.kind !== 'unavailable' && !rows.some((r) => r.layerUsed !== null)) {
      state = { kind: 'unavailable', reason: 'Prometheus in your cluster has no complete point for this window yet: use, requests and allocatable are needed together.' };
    }
  }
  const drawn = state.kind === 'ok' || state.kind === 'partial';
  const what = cpu ? 'CPU' : 'Memory';
  const step = view ? stepWords(view.stepSeconds) : '30 min';
  const span = view ? windowText(view.window) : '3 days';
  const source = `Usage feed (FEAT-004, GET /api/eks/automation/usage/{cluster}?scope=cluster&resource=${resource}&window=72h): ${
    view ? sourceService(view.source) : 'Prometheus in your cluster'
  }, ${cpu ? 'used (cAdvisor)' : 'working set (cAdvisor)'}, requested by Running pods, allocatable and ${total === 'capacity' ? 'provisioned' : 'allocatable'} (kube-state-metrics), step ${step}, ${span}.`;

  return (
    <ChartCard
      title={title}
      subline={`${what} for the whole cluster. The layers add up to what the nodes give pods (allocatable); the line is ${
        total === 'capacity' ? 'what the nodes have in all (provisioned)' : 'allocatable'
      }. A point Prometheus didn't measure is a gap, never 0.`}
      source={source}
      state={state}
      actions={resources.length > 1 ? <ResourceToggle value={resource} onChange={setResource} options={resources} /> : undefined}
      table={
        drawn && rows.length > 0 ? (
          <ChartDataTable
            caption={`${title}, ${what}`}
            rowHeader="Time (IST)"
            columns={[
              { key: 'used', header: 'Used' },
              { key: 'requested', header: 'Requested' },
              { key: 'allocatable', header: 'Allocatable' },
              ...(total === 'capacity' ? [{ key: 'capacity', header: 'Provisioned' }] : []),
            ]}
            rows={rows.map((r) => ({
              key: String(r.t),
              label: r.x,
              values: {
                used: axisText(r.used, unit),
                requested: axisText(r.requested, unit),
                allocatable: axisText(r.allocatable, unit),
                capacity: axisText(r.capacity, unit),
              },
            }))}
          />
        ) : undefined
      }
    >
      <SeriesLegend
        items={[
          { label: 'Used', kind: 'used' },
          { label: 'Reserved, unused', kind: 'requested' },
          { label: 'Not requested', kind: 'capacity' },
          { label: `${TOTAL_NAME[total]} (line)`, kind: 'capacity' },
        ]}
        className="mb-2"
      />
      <StackedArea
        data={rows.map((r) => ({ x: r.x, used: r.layerUsed, reserved: r.layerReserved, free: r.layerFree, line: r.line }))}
        layers={[
          { dataKey: 'used', name: 'Used', kind: 'used', shade: 0.55 },
          { dataKey: 'reserved', name: 'Reserved, unused', kind: 'requested', shade: 0.3 },
          { dataKey: 'free', name: 'Not requested', kind: 'capacity', shade: 0.18 },
        ]}
        total={{ dataKey: 'line', name: TOTAL_NAME[total], kind: 'capacity' }}
        bands={bands}
        xKey="x"
        height={240}
        ariaLabel={`${what} used, reserved and not requested over ${span}, in ${AXIS_NAME[unit]}`}
      />
      <p className={`mt-1 text-[11px] ${FAINT}`}>{AXIS_NAME[unit]}</p>
    </ChartCard>
  );
}
