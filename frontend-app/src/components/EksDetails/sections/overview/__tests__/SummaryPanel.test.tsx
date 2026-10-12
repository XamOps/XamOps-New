import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { getCapabilities } = vi.hoisted(() => ({ getCapabilities: vi.fn() }));
vi.mock('../../../../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../../../api/k8sAutomationApi')>();
  return { ...actual, getCapabilities };
});

import { K8sApiError } from '../../../../../api/k8sAutomationApi';
import { K8sCapabilitiesProvider } from '../../../../../hooks/useK8sCapabilities';
import { EKS_SECTIONS, LEGACY_TAB_MAP, resolveSectionRoute, useEksSectionRoute } from '../../../../../hooks/useEksSectionRoute';
import type { UseEksDashboardReturn } from '../../../../../hooks/useEksDashboard';
import { demoCapabilities } from '../../../../../demo/k8sAutomationMockData';
import { demoEksDashboard, demoEksEmptyShellDashboard } from '../../../../../demo/xamopsMockData';
import type { CapabilitiesDto } from '../../../../../types/k8sAutomation';
import { NOT_MONITORED_TITLE } from '../../../../CloudK8s/shared/NotMonitoredPanel';
import SummaryPanel, { ALERTS_HREF, FINDINGS_TITLE, HEALTH_TITLE, VIEW_ALL_ALERTS } from '../SummaryPanel';

const ACCOUNT = 'demo-aws-001';
const PROD = 'prod-eks-cluster';
const clone = (edit?: (d: any) => void) => {
  const d = structuredClone(demoEksDashboard) as any;
  edit?.(d);
  return d;
};

function Harness({ cluster, dashboard, onOpenGuide }: { cluster: string; dashboard: unknown; onOpenGuide: () => void }) {
  const route = useEksSectionRoute();
  return (
    <SummaryPanel
      clusterName={cluster}
      accountId={ACCOUNT}
      region="us-east-1"
      eksData={{ dashboard } as unknown as UseEksDashboardReturn}
      route={route}
      onOpenGuide={onOpenGuide}
    />
  );
}

function renderPanel({ cluster = PROD, dashboard = clone(), caps }: { cluster?: string; dashboard?: unknown; caps?: CapabilitiesDto | Error } = {}) {
  if (caps instanceof Error) getCapabilities.mockRejectedValue(caps);
  else getCapabilities.mockResolvedValue(caps ?? demoCapabilities(ACCOUNT));
  const onOpenGuide = vi.fn();
  render(
    <MemoryRouter initialEntries={[`/eks-details?name=${cluster}&region=us-east-1&accountIds=${ACCOUNT}&tab=overview&sub=summary`]}>
      <K8sCapabilitiesProvider accountId={ACCOUNT} demo>
        <Harness cluster={cluster} dashboard={dashboard} onOpenGuide={onOpenGuide} />
      </K8sCapabilitiesProvider>
    </MemoryRouter>,
  );
  return { onOpenGuide };
}

const tile = (label: string) => screen.getByRole('group', { name: label });

// A block body: a function returned from beforeEach would run as teardown.
beforeEach(() => { getCapabilities.mockReset(); });
afterEach(() => vi.restoreAllMocks());

describe('Overview › Summary (§10.1, D10)', () => {
  it('shows the Not monitored panel for the empty shell, never its score or zeros', async () => {
    const user = userEvent.setup();
    const { onOpenGuide } = renderPanel({ cluster: 'staging-eks-cluster', dashboard: demoEksEmptyShellDashboard('staging-eks-cluster') });
    expect(await screen.findByRole('heading', { name: NOT_MONITORED_TITLE })).toBeInTheDocument();
    expect(screen.queryByRole('group', { name: 'Nodes ready' })).toBeNull();
    expect(document.body).not.toHaveTextContent('100');
    await user.click(screen.getByRole('button', { name: 'Set up monitoring' }));
    expect(onOpenGuide).toHaveBeenCalledTimes(1);
  });

  it('lists the rule-based health signals with their counts and source', async () => {
    renderPanel();
    const health = await screen.findByRole('region', { name: HEALTH_TITLE });
    await waitFor(() => expect(health).toHaveTextContent('XamOps agent'));
    const signals = [...health.querySelectorAll('[data-signal]')].map((li) => (li as HTMLElement).dataset.signal);
    expect(signals).toEqual(['nodes-ready', 'node-pressure', 'pods', 'unschedulable']);
    expect(health.querySelector('[data-signal="nodes-ready"]')).toHaveTextContent('6 / 6');
    expect(health.querySelector('[data-signal="pods"]')).toHaveTextContent('16 / 18');
  });

  it('never renders a composite score, even when the dashboard sends one', async () => {
    renderPanel({ dashboard: clone((d) => { d.workloadHealth.clusterHealthScore = 92; }) });
    await screen.findByRole('region', { name: HEALTH_TITLE });
    expect(document.body).not.toHaveTextContent(/score/i);
    expect(document.body).not.toHaveTextContent('92');
    expect(document.body).not.toHaveTextContent(/savings opportunity/i);
  });

  it('computes requested CPU and memory from pod requests over node allocatable, with usage only when reported', async () => {
    renderPanel();
    await screen.findByRole('region', { name: HEALTH_TITLE });
    expect(tile('Nodes ready')).toHaveTextContent('6/ 6');
    expect(tile('Pods running')).toHaveTextContent('16/ 18');
    expect(tile('Pods running')).toHaveTextContent('2 pending · 0 failed');
    // 16 scheduled pods request 2143m (worker-staging sets none); the six nodes report 25.52 cores allocatable.
    const cpu = tile('CPU requested');
    expect(cpu.querySelector('[title]')).toHaveAttribute('title', '2.14 cores requested of 25.52 cores allocatable');
    expect(cpu).toHaveTextContent('8%');
    expect(cpu).toHaveTextContent('Used 52% · Prometheus (agent)');
    // (62.5).toFixed(0) is "63".
    expect(tile('Memory requested')).toHaveTextContent('Used 63% · Prometheus (agent)');
  });

  it('says usage is not reported rather than 0% when Prometheus sent nothing', async () => {
    renderPanel({ dashboard: clone((d) => { d.clusterOverview.cpuUsagePercent = 0; d.clusterOverview.memoryUsagePercent = 0; delete d.nodeUtilization; }) });
    await screen.findByRole('region', { name: HEALTH_TITLE });
    expect(tile('CPU requested')).toHaveTextContent('Usage not reported');
    expect(tile('Memory requested')).toHaveTextContent('Usage not reported');
    expect(tile('CPU requested')).not.toHaveTextContent('Used 0%');
  });

  it('names the cost source and shows unreported Trivy counts as —', async () => {
    renderPanel();
    await screen.findByRole('region', { name: HEALTH_TITLE });
    expect(tile('Cost today')).toHaveTextContent('$48.60');
    expect(tile('Cost today')).toHaveTextContent('XamOps cost exporter (Prometheus)');
    expect(tile('Critical vulnerabilities')).toHaveTextContent('—');
    expect(tile('Critical vulnerabilities')).toHaveTextContent('Not reported by Trivy (agent)');
  });

  it('shows the five most severe findings, each linking to the section that holds it', async () => {
    renderPanel();
    const findings = await screen.findByRole('region', { name: FINDINGS_TITLE });
    const rows = [...findings.querySelectorAll('li[data-finding]')];
    expect(rows.length).toBeGreaterThan(0);
    expect(rows.length).toBeLessThanOrEqual(5);
    const severities = rows.map((r) => r.querySelector('[data-severity]')?.getAttribute('data-severity'));
    const order = ['critical', 'high', 'medium', 'low', 'info'];
    expect([...severities].sort((a, b) => order.indexOf(a!) - order.indexOf(b!))).toEqual(severities);
    for (const row of rows) {
      const href = row.querySelector('a')!.getAttribute('href')!;
      const url = new URL(href, 'http://x');
      expect(url.pathname).toBe('/eks-details');
      expect(url.searchParams.get('name')).toBe(PROD);
      const resolved = resolveSectionRoute(EKS_SECTIONS, LEGACY_TAB_MAP, url.search.slice(1));
      expect(resolved.corrected).toBeNull();
    }
    expect(within(findings).getByRole('link', { name: VIEW_ALL_ALERTS })).toHaveAttribute('href', ALERTS_HREF);
  });

  it('lists the data sources capabilities report, marking one that is received but not shown', async () => {
    renderPanel();
    const sources = await screen.findByRole('region', { name: 'Data sources' });
    await waitFor(() => expect(sources.querySelector('[data-source-type="k8s-resources"]')).toHaveTextContent('Kubernetes resources'));
    expect(sources.querySelector('[data-source-type="kubescape-compliance"]')).toHaveTextContent('received, not shown');
  });

  it('keeps working on the dashboard when capabilities fail', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    renderPanel({ caps: new K8sApiError({ status: 500, code: 'K8S_INTERNAL' }) });
    const sources = await screen.findByRole('region', { name: 'Data sources' });
    await waitFor(() => expect(sources).toHaveTextContent("Couldn't check data sources."));
    expect(tile('Nodes ready')).toHaveTextContent('6');
  });

  it('shows namespace and top-pod usage only where Prometheus reported it', async () => {
    renderPanel();
    const namespaces = await screen.findByRole('region', { name: 'Namespace utilisation' });
    expect(within(namespaces).getByRole('table', { name: 'Namespace utilisation' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Top CPU pods' })).toHaveTextContent('prometheus-0');
    expect(screen.getByRole('region', { name: 'Top CPU pods' })).toHaveTextContent('Usage is reported for the top 20 pods only.');
  });
});
