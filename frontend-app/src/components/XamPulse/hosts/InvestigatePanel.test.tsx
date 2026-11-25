import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { InvestigatePanel, peerValue } from './InvestigatePanel';
import XamPulsePage from '../../../pages/XamPulsePage';
import type { XpInvestigation } from '../../../types/xampulse';

vi.mock('../../../contexts/AuthContext', () => ({ useAuth: () => ({ hasAnyRole: () => true }) }));

const inv: XpInvestigation = {
  instanceId: 'i-1', from: '2026-11-13T10:00:00Z', to: '2026-11-13T11:00:00Z',
  summary: 'Strongest lead: something changed just before. api redeployed at 10:20 UTC: followed by container CPU 21% → 81%.',
  hypotheses: [
    { code: 'change', title: 'Something changed just before', status: 'SUPPORTED', confidence: 'HIGH', weight: 78, evidence: ['api redeployed at 10:20 UTC: followed by container CPU 21% → 81%'], section: 'changes' },
    { code: 'errors', title: 'The application started failing', status: 'SUPPORTED', confidence: 'MEDIUM', weight: 55, evidence: ['New error: NPE (42×)'], section: 'activity', logsQuery: 'instance:i-1 severity:>=error' },
    { code: 'aws_limit', title: 'AWS capped the instance', status: 'RULED_OUT', weight: 0, evidence: ['AWS network allowances: no limit hit'], section: 'aws' },
    { code: 'host_specific', title: 'Only this host is affected', status: 'NOT_MEASURABLE', weight: 0, evidence: ['No peers to compare with.'], section: 'peers' },
  ],
  peers: { instanceId: 'i-1', basis: 'ASG', basisLabel: 'Auto Scaling group web', peers: [{ instanceId: 'i-2', name: 'web-2' }, { instanceId: 'i-3', name: 'web-3' }],
    metrics: [{ key: 'cpu', label: 'CPU busy', unit: '%', value: 82, peerMedian: 32.5, peerMin: 30, peerMax: 35, z: 12, verdict: 'HIGHER', indicative: true, peerValues: {} },
      { key: 'network', label: 'Network', unit: 'B/s', value: 2_097_152, peerMedian: 2_000_000, peerMin: 1_900_000, peerMax: 2_100_000, z: 0.4, verdict: 'IN_LINE', indicative: true, peerValues: {} }] },
};

describe('InvestigatePanel', () => {
  afterEach(cleanup);

  it('leads with the summary and the strongest supported lead, with a way to the evidence', () => {
    const onSection = vi.fn();
    render(<MemoryRouter><InvestigatePanel inv={inv} onSection={onSection} logsLink={(q) => `/xampulse/logs?q=${encodeURIComponent(q)}`} /></MemoryRouter>);
    expect(screen.getByRole('status')).toHaveTextContent(/^Strongest lead: something changed just before/);
    const sup = screen.getByRole('region', { name: 'Supported by the evidence (2)' });
    const items = Array.from(sup.querySelectorAll<HTMLElement>('.xp-inv__item--sup'));
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent('Strongest lead: Something changed just before');
    expect(items[0]).toHaveTextContent('Strong evidence');
    fireEvent.click(within(items[0]).getByRole('button', { name: 'Open Changes' }));
    expect(onSection).toHaveBeenCalledWith('changes');
    expect(within(items[1]).getByRole('link', { name: 'Open in Logs' })).toHaveAttribute('href', '/xampulse/logs?q=instance%3Ai-1%20severity%3A%3E%3Derror');
    expect(screen.getByRole('region', { name: 'Ruled out (1)' })).toHaveTextContent('AWS capped the instance');
    expect(screen.getByRole('region', { name: 'Cannot tell here (1)' })).toHaveTextContent('No peers to compare with.');
  });

  it('shows the host against its peers and marks an outlier, saying when only two peers back it', () => {
    render(<MemoryRouter><InvestigatePanel inv={inv} onSection={() => undefined} logsLink={(q) => q} /></MemoryRouter>);
    const rows = within(screen.getByRole('table', { name: 'This host against its peers' })).getAllByRole('row');
    expect(rows[1]).toHaveTextContent('CPU busy');
    expect(rows[1]).toHaveTextContent('Higher than peers (2 peers: indicative)');
    expect(rows[1].className).toContain('xp-peers__odd');
    expect(rows[2]).toHaveTextContent('In line');
    expect(peerValue(null, '%')).toBe('—');
    expect(peerValue(4.24, '/min')).toBe('4.2/min');
  });
});

function installStorage() {
  const map = new Map<string, string>();
  Object.defineProperty(window, 'localStorage', { configurable: true, value: {
    get length() { return map.size; }, clear: () => map.clear(), getItem: (k: string) => (map.has(k) ? map.get(k)! : null),
    key: (i: number) => Array.from(map.keys())[i] ?? null, removeItem: (k: string) => void map.delete(k), setItem: (k: string, v: string) => void map.set(k, String(v)),
  } as Storage });
}

describe('Investigate on Host 360 (demo)', () => {
  beforeEach(() => { installStorage(); window.localStorage.setItem('xp:demo', '1'); });
  afterEach(() => { cleanup(); window.localStorage.clear(); });

  it('opens from the header, lands on its own section and reads like the server would', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={['/xampulse/hosts/i-demo0003']}>
          <Routes><Route path="/xampulse/hosts/:instanceId" element={<XamPulsePage />} /></Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    fireEvent.click(await screen.findByRole('button', { name: 'Investigate' }, { timeout: 5000 }));
    expect(screen.getByRole('tab', { name: 'Investigate' })).toHaveAttribute('aria-selected', 'true');
    const peers = await screen.findByRole('table', { name: 'This host against its peers' }, { timeout: 5000 });
    const summary = document.querySelector('.xp-inv__summary');
    expect(summary?.textContent).toMatch(/^(Strongest lead|Nothing stands out)/);
    expect(summary?.textContent).not.toMatch(/caused/i);
    expect(within(peers).getByText('CPU busy').closest('tr')).toHaveTextContent('Higher than peers');
    expect(screen.queryByRole('button', { name: 'Investigate' }), 'the header shortcut hides on its own section').toBeNull();
  }, 15_000);
});
