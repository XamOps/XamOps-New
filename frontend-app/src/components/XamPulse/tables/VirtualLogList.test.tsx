import { describe, expect, it } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { computeWindow, VirtualLogList } from './VirtualLogList';
import type { XpLogRow } from '../../../types/xampulse';

const mk = (n: number): XpLogRow[] => Array.from({ length: n }, (_, i) => ({ id: `r${i}`, ts: new Date(1_700_000_000_000 + i * 1000).toISOString(), accountId: 1, region: 'us-east-1', instanceId: 'i-1', service: 'svc', source: 's', severity: 'INFO', body: `line ${i}`, attrs: {} }));

describe('computeWindow', () => {
  it('only covers the viewport plus overscan', () => {
    const w = computeWindow(30_000, 560, 30, 100_000);
    expect(w.end - w.start).toBeLessThan(60);
    expect(w.start).toBeGreaterThanOrEqual(0);
    expect(computeWindow(0, 560, 30, 5)).toEqual({ start: 0, end: 5 });
  });
});

describe('VirtualLogList', () => {
  it('keeps the DOM small with 100,000 rows and supports keyboard selection', () => {
    const rows = mk(100_000);
    let picked: string | null = null;
    render(<VirtualLogList rows={rows} onSelect={(r) => { picked = r.id; }} label="Log lines" />);
    expect(document.querySelectorAll('[role="row"]').length).toBeLessThan(200);
    const grid = screen.getByRole('grid', { name: 'Log lines' });
    fireEvent.keyDown(grid, { key: 'j' });
    fireEvent.keyDown(grid, { key: 'Enter' });
    expect(picked).toBe('r1');
  });
  it('shows the empty text when there are no rows', () => {
    render(<VirtualLogList rows={[]} onSelect={() => undefined} label="x" emptyText="Nothing here" />);
    expect(screen.getByText('Nothing here')).toBeInTheDocument();
  });
});
