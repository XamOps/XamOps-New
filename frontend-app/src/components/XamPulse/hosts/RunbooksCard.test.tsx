import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RunbooksCard } from './RunbooksCard';

function installStorage() {
  const map = new Map<string, string>();
  Object.defineProperty(window, 'localStorage', { configurable: true, value: {
    get length() { return map.size; }, clear: () => map.clear(), getItem: (k: string) => (map.has(k) ? map.get(k)! : null),
    key: (i: number) => Array.from(map.keys())[i] ?? null, removeItem: (k: string) => void map.delete(k), setItem: (k: string, v: string) => void map.set(k, String(v)),
  } as Storage });
}

describe('RunbooksCard (demo)', () => {
  beforeEach(() => { installStorage(); window.localStorage.setItem('xp:demo', '1'); });
  afterEach(() => { cleanup(); window.localStorage.clear(); });

  it('shows each fixed command before it runs, runs one, polls and shows the cleaned output', async () => {
    render(<QueryClientProvider client={new QueryClient()}><RunbooksCard accountId={1} instanceId="i-demo0005" /></QueryClientProvider>);
    const oom = await screen.findByRole('button', { name: /Out-of-memory kills/ });
    expect(screen.getAllByText('Command')).toHaveLength(7);
    expect(screen.getByText(/ps -eo pid,user,%cpu/)).toBeInTheDocument();
    fireEvent.click(oom);
    expect(await screen.findByText('Waiting for SSM')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Disk usage/ })).toBeDisabled();
    const out = await screen.findByLabelText('Out-of-memory kills output', undefined, { timeout: 8000 });
    expect(out).toHaveTextContent('Out of memory: Killed process 4121 (java)');
    expect(screen.getByText('Finished')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Disk usage/ })).toBeEnabled();
  }, 12_000);
});
