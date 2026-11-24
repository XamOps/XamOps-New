import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import XamPulsePage from '../XamPulsePage';
import { FleetHoneycomb } from '../../components/XamPulse/charts/Visuals';
import { Drawer, EmptyState, ErrorState } from '../../components/XamPulse/ui/ui';
import { XpApiError } from '../../api/xampulseErrors';

vi.mock('../../contexts/AuthContext', () => ({ useAuth: () => ({ hasAnyRole: () => true }) }));

/**
 * NOT RUN in the authoring environment (no node_modules, registry unreachable). Written against the real
 * Testing Library / react-router / react-query APIs. Demo mode is switched on through localStorage, which the
 * api client honours only under Vite's DEV flag (true under vitest).
 */
function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/xampulse" element={<div>redirect</div>} />
            <Route path="/xampulse/hosts/:instanceId" element={<XamPulsePage />} />
            <Route path="/xampulse/:tab" element={<XamPulsePage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
  );
}


// Node's experimental localStorage shadows jsdom's and has no methods; install a working in-memory Storage.
function installStorage() {
  const map = new Map<string, string>();
  const s = {
    get length() { return map.size; },
    clear: () => map.clear(),
    getItem: (k: string) => (map.has(k) ? map.get(k)! : null),
    key: (i: number) => Array.from(map.keys())[i] ?? null,
    removeItem: (k: string) => void map.delete(k),
    setItem: (k: string, v: string) => void map.set(k, String(v)),
  } as Storage;
  Object.defineProperty(window, 'localStorage', { configurable: true, value: s });
}
beforeEach(() => { installStorage(); window.localStorage.setItem('xp:demo', '1'); });
afterEach(() => { cleanup(); window.localStorage.clear(); });

describe('XamPulsePage in demo mode', () => {
  it('shows the DEMO banner, landmarks and the sub-tab nav', async () => {
    renderAt('/xampulse/overview');
    expect(await screen.findByText(/DEMO DATA: generated sample data/)).toBeInTheDocument();
    expect(screen.getByRole('main')).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'XamPulse sections' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Skip to content' })).toBeInTheDocument();
  });

  it('renders Mission Control with the narrative and a health score', async () => {
    renderAt('/xampulse/overview');
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('Overview');
    expect(await screen.findByText('74 / 100')).toBeInTheDocument();
  });

  it('every tab renders a first-level heading and has no unlabeled buttons or inputs', async () => {
    for (const tab of ['overview', 'hosts', 'containers', 'logs', 'metrics', 'alerts', 'insights', 'setup']) {
      const { unmount } = renderAt(`/xampulse/${tab}`);
      await screen.findByRole('heading', { level: 1 }, { timeout: 5000 });
      document.querySelectorAll('button').forEach((b) => {
        const name = (b.textContent ?? '').trim() || b.getAttribute('aria-label') || b.getAttribute('title');
        expect(name, `unnamed button on ${tab}`).toBeTruthy();
      });
      document.querySelectorAll('input:not([type="hidden"]), select, textarea').forEach((el) => {
        const id = el.getAttribute('id');
        const labelled = el.getAttribute('aria-label') || el.closest('label') || (id && document.querySelector(`label[for="${id}"]`));
        expect(labelled, `unlabelled control #${id ?? '?'} on ${tab}`).toBeTruthy();
      });
      unmount();
    }
  }, 30_000);

  it('opens the Host 360 page from the hosts table with Throttle Radar on top', async () => {
    renderAt('/xampulse/hosts');
    const hostLink = await screen.findByRole('link', { name: 'demo-api-2' });
    fireEvent.click(hostLink);
    expect(await screen.findByRole('heading', { level: 1, name: 'demo-api-2' })).toBeInTheDocument();
    const radar = await screen.findByRole('region', { name: /Throttle Radar/ });
    // The critical finding is announced; the measured evidence is shown with it.
    const alert = await within(radar).findByRole('alert');
    expect(alert).toHaveTextContent(/stalled on memory/);
    expect(alert).toHaveTextContent(/stalled share: 12%/);
    expect(within(radar).getByRole('list', { name: 'Limit checks' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'All hosts' })).toBeInTheDocument();
  });

  it('opens Host 360 from the deep link, switches sections by click and arrow keys, and labels every control', async () => {
    renderAt('/xampulse/hosts/i-demo0006');
    expect(await screen.findByRole('heading', { level: 1, name: 'demo-db-1' })).toBeInTheDocument();
    const tabs = screen.getByRole('tablist', { name: 'Host sections' });
    const overview = within(tabs).getByRole('tab', { name: 'Overview' });
    expect(overview).toHaveAttribute('aria-selected', 'true');
    fireEvent.click(within(tabs).getByRole('tab', { name: 'CPU' }));
    expect(await screen.findByText('CPU per core')).toBeInTheDocument();
    fireEvent.keyDown(within(tabs).getByRole('tab', { name: 'CPU' }), { key: 'ArrowRight' });
    expect(within(tabs).getByRole('tab', { name: 'Memory' })).toHaveAttribute('aria-selected', 'true');
    expect(await screen.findByText('Memory pressure')).toBeInTheDocument();
    document.querySelectorAll('button').forEach((b) => {
      const name = (b.textContent ?? '').trim() || b.getAttribute('aria-label') || b.getAttribute('title');
      expect(name, 'unnamed button on the host page').toBeTruthy();
    });
  });

  it('never shows a limit check it could not measure as healthy', async () => {
    renderAt('/xampulse/hosts/i-demo0006');
    const checks = await screen.findByRole('list', { name: 'Limit checks' });
    const ebs = within(checks).getByText('EBS volume and instance limits').closest('li') as HTMLElement;
    expect(ebs).toHaveTextContent(/not measured here/);
    expect(ebs.className).toContain('xp-radar__signal--nodata');
    expect(screen.getByText(/not measurable here: why/)).toBeInTheDocument();
  });

  it('sends an invalid tab back to Mission Control', async () => {
    renderAt('/xampulse/traces');
    expect(await screen.findByText(/DEMO DATA/)).toBeInTheDocument();
  });

  it('filters logs when a facet is included', async () => {
    renderAt('/xampulse/logs');
    const include = await screen.findByRole('button', { name: /Include Service nginx/ });
    fireEvent.click(include);
    expect(await screen.findByRole('button', { name: 'Remove filter service:nginx' })).toBeInTheDocument();
  });

  it('shows the archive cost estimate before anything is run', async () => {
    renderAt('/xampulse/logs');
    fireEvent.click(await screen.findByRole('button', { name: 'Archive' }));
    fireEvent.click(await screen.findByRole('button', { name: /Estimate cost/ }));
    expect(await screen.findByText(/would cost about/)).toHaveTextContent(/\$0\.07/);
    expect(screen.getByRole('button', { name: 'Run archive search' })).toBeDisabled();
  });

  it('Setup shows the trust panel on the Trust section', async () => {
    renderAt('/xampulse/setup');
    fireEvent.click(await screen.findByRole('button', { name: 'Trust' }));
    expect(await screen.findByText('Your data, your rules')).toBeInTheDocument();
  });
});

describe('shared UI states', () => {
  it('ErrorState shows the request id and a retry button', () => {
    let retried = false;
    render(<ErrorState error={new XpApiError('XP_STORE_UNAVAILABLE', 'down', 503, 'req-42')} onRetry={() => { retried = true; }} />);
    expect(screen.getByRole('alert')).toHaveTextContent('req-42');
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(retried).toBe(true);
  });

  it('EmptyState renders a guided checklist', () => {
    render(<EmptyState title="Nothing" steps={['One', 'Two']} />);
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
  });

  it('Drawer traps focus, closes on Esc and returns focus to the opener', () => {
    function Harness() {
      const [open, setOpen] = React.useState(false);
      return (<><button type="button" onClick={() => setOpen(true)}>open</button>{open && <Drawer title="t" label="Panel" onClose={() => setOpen(false)}><button type="button">inside</button></Drawer>}</>);
    }
    render(<Harness />);
    const opener = screen.getByRole('button', { name: 'open' });
    opener.focus();
    fireEvent.click(opener);
    expect(screen.getByRole('dialog', { name: 'Panel' })).toBeInTheDocument();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(document.activeElement).toBe(opener);
  });

  it('honeycomb cells carry text status in their accessible names and support arrow keys', () => {
    const tiles = [
      { accountId: 1, instanceId: 'i-1', name: 'web-1', health: 'OK' as const, score: 90, cpuPct: 10, memPct: 20 },
      { accountId: 1, instanceId: 'i-2', name: 'web-2', health: 'CRIT' as const, score: 20, cpuPct: 99, memPct: 90 },
      { accountId: 1, instanceId: 'i-3', name: 'web-3', health: 'NO_DATA' as const, score: null, cpuPct: null, memPct: null },
    ];
    render(<MemoryRouter><FleetHoneycomb tiles={tiles} hrefFor={(t) => `/h/${t.instanceId}`} /></MemoryRouter>);
    expect(screen.getByRole('link', { name: /web-2, Critical/ })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /web-3, No data/ })).toBeInTheDocument();
    const first = screen.getByRole('link', { name: /web-1, Healthy/ });
    first.focus();
    fireEvent.keyDown(first, { key: 'ArrowRight' });
    expect(document.activeElement).toBe(screen.getByRole('link', { name: /web-2, Critical/ }));
  });
});
