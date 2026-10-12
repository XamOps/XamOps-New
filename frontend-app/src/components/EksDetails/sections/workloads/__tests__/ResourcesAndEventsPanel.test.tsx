import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { getCapabilities } = vi.hoisted(() => ({ getCapabilities: vi.fn() }));
vi.mock('../../../../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../../../api/k8sAutomationApi')>();
  return { ...actual, getCapabilities };
});

import { K8sCapabilitiesProvider } from '../../../../../hooks/useK8sCapabilities';
import { useEksSectionRoute } from '../../../../../hooks/useEksSectionRoute';
import type { UseEksDashboardReturn } from '../../../../../hooks/useEksDashboard';
import { demoCapabilities } from '../../../../../demo/k8sAutomationMockData';
import { demoEksDashboard } from '../../../../../demo/xamopsMockData';
import ResourcesPanel, { ingressHosts, servicePorts } from '../ResourcesPanel';
import EventsPanel, { EVENTS_CAPTION, eventRows } from '../../overview/EventsPanel';

function Harness({ panel, dashboard }: { panel: 'resources' | 'events'; dashboard: unknown }) {
  const route = useEksSectionRoute();
  const props = {
    clusterName: 'prod-eks-cluster', accountId: 'demo-aws-001', region: 'us-east-1',
    eksData: { dashboard } as unknown as UseEksDashboardReturn, route, onOpenGuide: () => {},
  };
  return panel === 'resources' ? <ResourcesPanel {...props} /> : <EventsPanel {...props} />;
}

function renderPanel(panel: 'resources' | 'events', dashboard: unknown = demoEksDashboard) {
  getCapabilities.mockResolvedValue(demoCapabilities('demo-aws-001'));
  render(
    <MemoryRouter>
      <K8sCapabilitiesProvider accountId="demo-aws-001" demo>
        <Harness panel={panel} dashboard={dashboard} />
      </K8sCapabilitiesProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => { getCapabilities.mockReset(); });
afterEach(() => vi.restoreAllMocks());

describe('Workloads › Resources (§4.3)', () => {
  it('lists HPAs, Services, Ingresses, PVCs and Jobs, each in its own table', async () => {
    renderPanel('resources');
    for (const name of ['Horizontal pod autoscalers', 'Services', 'Ingresses', 'Persistent volume claims']) {
      expect(screen.getByRole('table', { name })).toBeInTheDocument();
    }
    // The demo has no jobs: said so, no table.
    expect(within(screen.getByRole('region', { name: 'Jobs' })).getByText('The agent reported no jobs.')).toBeInTheDocument();
    expect(screen.getByRole('table', { name: 'Persistent volume claims' })).toHaveTextContent('Pending');
    // Events are no longer here.
    expect(screen.queryByText(/FailedScheduling/)).toBeNull();
    await waitFor(() => expect(getCapabilities).toHaveBeenCalled());
  });

  it('reads both the agent shapes and the older ones', () => {
    expect(servicePorts({ ports: [{ port: 80, targetPort: 8080, protocol: 'TCP' }, { port: 443, targetPort: 443 }] })).toBe('80:8080/TCP, 443');
    expect(servicePorts({ ports: '53:53/UDP' })).toBe('53:53/UDP');
    expect(ingressHosts({ rules: [{ host: 'a.example.com' }, { host: 'b.example.com' }] })).toBe('a.example.com, b.example.com');
    expect(ingressHosts({ host: 'api.xammer.in' })).toBe('api.xammer.in');
    expect(ingressHosts({})).toBe('—');
  });
});

describe('Overview › Events (§10.2)', () => {
  it('puts warnings first, newest first, from either event shape', () => {
    const rows = eventRows([
      { type: 'Normal', reason: 'Pulled', name: 'pod-a', lastTimestamp: '2026-10-12T08:00:00Z' },
      { type: 'Warning', reason: 'BackOff', involvedObject: 'pod-b', objectKind: 'Pod', namespace: 'team-a', lastTime: '2026-10-12T07:00:00Z', count: 4 },
      { type: 'Warning', reason: 'FailedScheduling', name: 'pod-c', lastTimestamp: '2026-10-12T09:00:00Z' },
    ]);
    expect(rows.map((r) => r.reason)).toEqual(['FailedScheduling', 'BackOff', 'Pulled']);
    expect(rows[1]).toMatchObject({ object: 'Pod/pod-b', namespace: 'team-a', count: 4 });
  });

  it('filters by type and searches the message', async () => {
    const user = userEvent.setup();
    renderPanel('events');
    const table = screen.getByRole('table', { name: EVENTS_CAPTION });
    const all = table.querySelectorAll('tbody tr').length;
    expect(all).toBeGreaterThan(0);
    await user.click(within(screen.getByRole('group', { name: 'Type' })).getByRole('button', { name: 'Normal' }));
    expect([...table.querySelectorAll('tbody tr')].every((tr) => tr.textContent?.includes('Normal'))).toBe(true);
    await user.click(within(screen.getByRole('group', { name: 'Type' })).getByRole('button', { name: 'All' }));
    await user.type(screen.getByRole('searchbox'), 'Insufficient cpu');
    expect(table.querySelectorAll('tbody tr')).toHaveLength(1);
    expect(table).toHaveTextContent('FailedScheduling');
  });

  it('says when the agent reported no events', async () => {
    renderPanel('events', { clusterName: 'prod-eks-cluster' });
    expect(screen.getByText('The agent reported no Kubernetes events.')).toBeInTheDocument();
    await waitFor(() => expect(getCapabilities).toHaveBeenCalled());
  });
});
