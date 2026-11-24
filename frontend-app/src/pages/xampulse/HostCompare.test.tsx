import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import XamPulsePage from '../XamPulsePage';

vi.mock('../../contexts/AuthContext', () => ({ useAuth: () => ({ hasAnyRole: () => true }) }));

// Page-level demo tests get 5 s waits: they flake under a loaded full-suite run otherwise.
const WAIT = { timeout: 5000 };

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes><Route path="/xampulse/hosts/:instanceId" element={<XamPulsePage />} /></Routes>
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

describe('Host 360 normal range and compare (demo)', () => {
  it('shades the usual range and draws last week dashed when the link asks for it', async () => {
    renderAt('/xampulse/hosts/i-demo0003?accountId=1&compare=week');
    expect((await screen.findAllByText(/Shaded: the range this host usually stays in/, undefined, WAIT)).length).toBeGreaterThan(0);
    expect((await screen.findAllByText(/Total, last week/, undefined, WAIT)).length).toBeGreaterThan(0);
    const select = screen.getByLabelText('Compare with') as HTMLSelectElement;
    expect(select.value).toBe('week');
  });

  it('turns the shaded range off and on from the header, and compares only when chosen', async () => {
    renderAt('/xampulse/hosts/i-demo0003?accountId=1');
    await screen.findAllByText(/Shaded: the range this host usually stays in/, undefined, WAIT);
    expect(screen.queryByText(/Total, last week/)).toBeNull();
    const toggle = screen.getByRole('button', { name: 'Normal range' });
    expect(toggle).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-pressed', 'false');
    expect(screen.queryByText(/Shaded: the range this host usually stays in/)).toBeNull();
  });
});
