import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { UseEksDashboardReturn } from '../../../../hooks/useEksDashboard';
import { demoEksDashboard } from '../../../../demo/xamopsMockData';
import EksKarpenterTab, { KARPENTER_NOT_DETECTED, KARPENTER_STATE_NOT_REPORTED } from '../EksKarpenterTab';
import EksPerformanceTab, { UTILIZATION_CAPTION, bandMark } from '../EksPerformanceTab';

const data = (dashboard: unknown) => ({ dashboard } as unknown as UseEksDashboardReturn);
const clone = (edit?: (d: any) => void) => {
  const d = structuredClone(demoEksDashboard) as any;
  edit?.(d);
  return d;
};

describe('Nodes › Karpenter (§10.5)', () => {
  it('says Karpenter not detected when neither the agent nor any node label reports it', () => {
    render(<EksKarpenterTab eksData={data(clone((d) => { d.k8sNodes = d.k8sNodes.map((n: any) => ({ ...n, labels: {} })); }))} />);
    expect(screen.getByRole('heading', { name: KARPENTER_NOT_DETECTED })).toBeInTheDocument();
  });

  it('lists NodePools from labels without claiming the controller is healthy', () => {
    render(<EksKarpenterTab eksData={data(demoEksDashboard)} />);
    expect(screen.getByText(KARPENTER_STATE_NOT_REPORTED)).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'NodePools from node labels' })).toHaveTextContent('default');
    expect(document.body).not.toHaveTextContent(/healthy/i);
    expect(document.body).not.toHaveTextContent('WhenEmptyOrUnderutilized');
  });

  it('shows what the agent reports: controller, NodePools, NodeClasses with why one is not ready, NodeClaims', () => {
    render(<EksKarpenterTab eksData={data(clone((d) => {
      d.karpenter = {
        healthy: false, version: 'v1.0.6',
        nodePools: [{ name: 'default', ready: true, nodeCount: 3, cpuLimit: '100', memoryLimit: '' }],
        ec2NodeClasses: [{ name: 'default', ready: false, readyMessage: 'AMI resolution failed', amiFamily: 'AL2023', instanceProfile: 'KarpenterNodeRole' }],
        nodeClaims: [{ name: 'default-x7k2p', nodePoolName: 'default', phase: 'Ready', instanceType: 'm5.xlarge', capacityType: 'spot', zone: 'us-east-1b', nodeName: 'ip-10-0-1-102' }],
        totalActiveClaims: 1, totalPendingClaims: 0, spotNodesCount: 3, onDemandNodesCount: 0, provisionedNodes: 3, pendingPods: 0,
      };
    }))} />);
    expect(screen.getByText('Controller unhealthy')).toBeInTheDocument();
    expect(screen.getByRole('table', { name: 'EC2NodeClasses' })).toHaveTextContent('AMI resolution failed');
    expect(screen.getByRole('table', { name: 'NodePools' })).toHaveTextContent('None');
    expect(screen.getByRole('table', { name: 'NodeClaims' })).toHaveTextContent('default-x7k2p');
  });
});

/** A MetricStrip cell, found by its LABEL caption, as the v3 Utilization hero renders it. */
const stripCell = (label: string): HTMLElement => {
  const caption = [...document.querySelectorAll('[data-metric-cell] span')].find((s) => s.textContent === label);
  return caption?.closest<HTMLElement>('[data-metric-cell]')!;
};

describe('Nodes › Utilization (D10)', () => {
  it('averages only when both cluster usages are reported, with the band as a labelled mark', () => {
    render(<EksPerformanceTab eksData={data(demoEksDashboard)} />);
    // 52% CPU and 62.5% memory.
    const cell = stripCell('Average utilisation');
    expect(cell).toHaveTextContent('57%');
    expect(cell).toHaveTextContent('In the 40-75% band');
  });

  it('shows — rather than 0 when nothing reports usage, and leaves those nodes out of averages', () => {
    render(<EksPerformanceTab eksData={data(clone((d) => {
      d.clusterOverview.cpuUsagePercent = 0;
      d.clusterOverview.memoryUsagePercent = 0;
      d.resourceUtilization.nodes = d.resourceUtilization.nodes.slice(0, 2);
    }))} />);
    expect(stripCell('Average utilisation')).toHaveTextContent('Needs both CPU and memory usage');
    expect(stripCell('Cluster CPU used')).toHaveTextContent('—');
    expect(stripCell('Average node CPU used')).toHaveTextContent('2 of 6 nodes report usage');
    const table = screen.getByRole('table', { name: UTILIZATION_CAPTION });
    const row = [...table.querySelectorAll('tbody tr')].find((tr) => tr.textContent?.includes('ip-10-0-1-106'))!;
    expect(row.querySelectorAll('td')[1]).toHaveTextContent('—');
    // No unknown usage turned into 0% (a "40%" requested value is fine).
    expect(table.textContent).not.toMatch(/(^|[^0-9.])0%/);
  });

  it('labels the band', () => {
    expect(bandMark(20).label).toBe('Below 40%: room to consolidate');
    expect(bandMark(60).label).toBe('In the 40-75% band');
    expect(bandMark(90).label).toBe('Above 75%: little headroom');
  });
});
