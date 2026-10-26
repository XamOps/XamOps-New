import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { getCapabilities } = vi.hoisted(() => ({ getCapabilities: vi.fn() }));
vi.mock('../../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/k8sAutomationApi')>();
  return { ...actual, getCapabilities };
});

import { K8sApiError } from '../../../api/k8sAutomationApi';
import { K8sCapabilitiesProvider } from '../../../hooks/useK8sCapabilities';
import { demoCapabilities } from '../../../demo/k8sAutomationMockData';
import { demoEksDashboard, demoEksEmptyShellDashboard } from '../../../demo/xamopsMockData';
import type { CapabilitiesDto } from '../../../types/k8sAutomation';
import type { EksDashboard } from '../../../types/eksTypes';
import { ClusterStatusStrip, DATA_UNAVAILABLE, STRIP_LABEL } from '../ClusterStatusStrip';

const PROD = 'prod-eks-cluster';
const dashboard = (edit?: (d: any) => void): EksDashboard => {
  const d = structuredClone(demoEksDashboard) as any;
  edit?.(d);
  return d as EksDashboard;
};

function renderStrip({ caps, cluster = PROD, data = dashboard(), error = false, account = 'demo-aws-001' }: {
  caps?: CapabilitiesDto | Promise<never> | Error; cluster?: string; data?: EksDashboard | null; error?: boolean; account?: string;
}) {
  if (caps instanceof Error) getCapabilities.mockRejectedValue(caps);
  else if (caps instanceof Promise) getCapabilities.mockReturnValue(caps);
  else getCapabilities.mockResolvedValue(caps ?? demoCapabilities(account));
  render(
    <K8sCapabilitiesProvider accountId={account} demo>
      <ClusterStatusStrip clusterName={cluster} dashboard={data} dashboardError={error} />
    </K8sCapabilitiesProvider>,
  );
  return screen.getByRole('region', { name: STRIP_LABEL });
}

const value = (term: string) => {
  const item = screen.getByRole('region', { name: STRIP_LABEL }).querySelector(`[data-strip-item="${term}"] dd`);
  if (!item) throw new Error(`no strip item ${term}`);
  return item.textContent;
};

// A block body: a function returned from beforeEach would run as teardown.
beforeEach(() => { getCapabilities.mockReset(); });
afterEach(() => vi.restoreAllMocks());

describe('ClusterStatusStrip (§4.2, D10)', () => {
  it('shows health, nodes, pods, node automation and access for a monitored read-write cluster', async () => {
    renderStrip({});
    await waitFor(() => expect(value('Access')).toBe('Read-write'));
    expect(value('Health')).toBe('Healthy');
    expect(value('Nodes ready')).toBe('6 / 6');
    expect(value('Pods running')).toBe('16 / 18');
    expect(value('Node automation')).toBe('Analysis');
  });

  it('counts running and total from one pod list, so running never exceeds the total', async () => {
    // The old cards divided the overview's running count (52) by the pod list's length (18).
    renderStrip({ data: dashboard((d) => { d.clusterOverview.runningPods = 52; delete d.clusterOverview.totalPods; }) });
    await waitFor(() => expect(value('Access')).toBe('Read-write'));
    expect(value('Pods running')).toBe('16 / 18');
  });

  it('reads the read-only connection from capabilities', async () => {
    renderStrip({ account: 'demo-aws-002' });
    await waitFor(() => expect(value('Access')).toBe('Read-only'));
  });

  it('says Needs attention with the count, and what needs it', async () => {
    renderStrip({ data: dashboard((d) => { d.k8sNodes[2].conditions[0].status = 'False'; }) });
    await waitFor(() => expect(value('Health')).toBe('Needs attention (1)'));
    expect(value('Nodes ready')).toBe('5 / 6 · 1 node not ready');
  });

  it('says Data stale when capabilities report the XamOps agent stale', async () => {
    const caps = demoCapabilities('demo-aws-001')!;
    caps.clusters[0].agent.status = 'STALE';
    renderStrip({ caps });
    await waitFor(() => expect(value('Health')).toBe('Data stale'));
  });

  it('shows Not monitored and no counts for the empty shell, whatever score it carries', async () => {
    const shell = demoEksEmptyShellDashboard('staging-eks-cluster') as unknown as EksDashboard;
    const strip = renderStrip({ cluster: 'staging-eks-cluster', data: shell });
    // Health already reads Not monitored from the empty dashboard while capabilities load,
    // so wait on Node automation, which only settles once capabilities resolve.
    await waitFor(() => expect(value('Node automation')).toBe('Not installed'));
    expect(value('Health')).toBe('Not monitored');
    expect(value('Nodes ready')).toBe('—');
    expect(value('Pods running')).toBe('—');
    // workloadHealth.clusterHealthScore is 100 in the shell; it is never rendered.
    expect(strip).not.toHaveTextContent('100');
    expect(strip).not.toHaveTextContent(/score/i);
  });

  it('says Checking… while capabilities load, and still shows the dashboard counts', () => {
    renderStrip({ caps: new Promise<never>(() => {}) });
    expect(value('Access')).toBe('Checking…');
    expect(value('Node automation')).toBe('Checking…');
    expect(value('Nodes ready')).toBe('6 / 6');
  });

  it('says Unknown for access and automation when capabilities fail', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    renderStrip({ caps: new K8sApiError({ status: 500, code: 'K8S_INTERNAL' }) });
    await waitFor(() => expect(value('Access')).toBe('Unknown'));
    expect(value('Node automation')).toBe('Unknown');
    // Monitoring falls back to the dashboard, which has data.
    expect(value('Health')).toBe('Healthy');
  });

  it('says Data unavailable and shows no counts when the dashboard failed', async () => {
    renderStrip({ data: null, error: true });
    await waitFor(() => expect(value('Access')).toBe('Read-write'));
    expect(value('Health')).toBe(DATA_UNAVAILABLE);
    expect(value('Nodes ready')).toBe('—');
    expect(value('Pods running')).toBe('—');
  });
});
