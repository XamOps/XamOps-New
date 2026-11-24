import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import XamPulsePage from '../XamPulsePage';
import { memoryCell } from './ContainersTab';
import type { XpContainerRow } from '../../types/xampulse';

vi.mock('../../contexts/AuthContext', () => ({ useAuth: () => ({ hasAnyRole: () => true }) }));

// Page-level demo tests get 5 s waits: they flake under a loaded full-suite run otherwise.
const WAIT = { timeout: 5000 };

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/xampulse/containers/:instanceId/:name" element={<XamPulsePage />} />
          <Route path="/xampulse/:tab" element={<XamPulsePage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function installStorage() {
  const map = new Map<string, string>();
  Object.defineProperty(window, 'localStorage', {
    configurable: true,
    value: {
      get length() { return map.size; }, clear: () => map.clear(), getItem: (k: string) => (map.has(k) ? map.get(k)! : null),
      key: (i: number) => Array.from(map.keys())[i] ?? null, removeItem: (k: string) => void map.delete(k), setItem: (k: string, v: string) => void map.set(k, String(v)),
    } as Storage,
  });
}
beforeEach(() => { installStorage(); window.localStorage.setItem('xp:demo', '1'); });
afterEach(() => { cleanup(); window.localStorage.clear(); });

describe('Container 360 (demo)', () => {
  it('leads with the verdict, then vitals and charts, then the container\'s own logs', async () => {
    renderAt('/xampulse/containers/i-demo0005/api?accountId=1');
    const verdict = await screen.findByRole('region', { name: 'Container verdict' }, WAIT);
    // Each problem once: the OOM headline is the verdict, not repeated in a second finding card.
    expect(within(verdict).getAllByText(/likely an OOM kill/)).toHaveLength(1);
    expect(screen.getAllByText(/likely an OOM kill/)).toHaveLength(1);
    expect(within(verdict).getAllByRole('listitem').length).toBeGreaterThanOrEqual(2);
    expect(within(verdict).getAllByText('Next').length).toBeGreaterThanOrEqual(2);
    expect(within(verdict).getByText(/throttled periods/)).toBeInTheDocument();
    const vitals = screen.getByRole('region', { name: 'Container vitals' });
    expect(within(vitals).getByText('Memory')).toBeInTheDocument();
    expect(within(vitals).getByText('Restarts')).toBeInTheDocument();
    // The image tag keeps its case: it is an identifier, not a label.
    expect(screen.getByTitle('Image').textContent).toBe('demo/api:2.14.0');
    expect(screen.getByRole('button', { name: 'Copy full container id' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Resource usage' })).toBeInTheDocument();

    const logs = screen.getByRole('region', { name: 'Logs' });
    expect(await within(logs).findByRole('grid', { name: 'Container log lines' }, WAIT)).toBeInTheDocument();
    const sev = within(logs).getByRole('group', { name: 'Minimum severity' });
    const errorOnly = within(sev).getByRole('button', { name: /Error\+/ });
    fireEvent.click(errorOnly);
    expect(errorOnly).toHaveAttribute('aria-pressed', 'true');
    expect(within(logs).getByRole('link', { name: 'Open in Logs' }).getAttribute('href')).toContain(encodeURIComponent('severity:>=error'));
    document.querySelectorAll('button').forEach((b) => {
      const name = (b.textContent ?? '').trim() || b.getAttribute('aria-label') || b.getAttribute('title');
      expect(name, 'unnamed button on the container page').toBeTruthy();
    });
  });

  it('opens with Error+ selected from the containers list errors link', async () => {
    renderAt('/xampulse/containers/i-demo0005/api?accountId=1#errors');
    const logs = await screen.findByRole('region', { name: 'Logs' }, WAIT);
    expect(within(logs).getByRole('button', { name: /Error\+/ })).toHaveAttribute('aria-pressed', 'true');
  });
});

describe('Containers list', () => {
  it('shows the errors column and links a non-zero count to the container\'s errors', async () => {
    renderAt('/xampulse/containers');
    expect(await screen.findByRole('columnheader', { name: 'Errors 15m' }, WAIT)).toBeInTheDocument();
    const link = await screen.findByRole('link', { name: '19' }, WAIT);
    expect(link.getAttribute('href')).toMatch(/\/xampulse\/containers\/i-demo0005\/api.*#errors$/);
  });

  it('leads each row with a status and filters to the containers that need attention', async () => {
    renderAt('/xampulse/containers');
    expect(await screen.findByRole('columnheader', { name: 'Status' }, WAIT)).toBeInTheDocument();
    expect(await screen.findByText('Critical', {}, WAIT)).toBeInTheDocument();
    expect(screen.getAllByText('Healthy').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText(/1 needs attention: api on i-demo0005/)).toBeInTheDocument();
    const filter = screen.getByRole('group', { name: 'Show containers' });
    fireEvent.click(within(filter).getByRole('button', { name: /Needs attention/ }));
    const table = screen.getByRole('table');
    expect(within(table).getAllByRole('row')).toHaveLength(2); // header + the one critical container
    expect(screen.getByText('Showing 1 of 3 on this page', { exact: false })).toBeInTheDocument();
    // One page of results: no pagination controls.
    expect(screen.queryByRole('navigation', { name: 'Container pages' })).toBeNull();
  });

  it('says "No limit" instead of a bar when the limit is the host\'s memory', () => {
    const row: XpContainerRow = { accountId: 1, instanceId: 'i-1', containerId: 'c', name: 'svc', memBytes: 1.2e9, memLimitBytes: 8.15e9, memLimitIsHost: true };
    const { container } = render(<>{memoryCell(row)}</>);
    expect(screen.getByText(/No limit · host/)).toBeInTheDocument();
    expect(container.querySelector('.xp-meter')).toBeNull();
    cleanup();
    const real = render(<>{memoryCell({ ...row, memLimitBytes: 2e9, memLimitIsHost: false })}</>);
    expect(screen.queryByText(/No limit/)).toBeNull();
    expect(real.container.querySelector('.xp-meter')).not.toBeNull();
  });
});
