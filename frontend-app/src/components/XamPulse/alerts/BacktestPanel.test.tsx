import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { BacktestPanel, backtestChart, backtestVerdict } from './BacktestPanel';
import type { XpAlertPreview } from '../../../types/xampulse';

const T = Date.parse('2026-11-13T10:00:00Z');
const iso = (min: number) => new Date(T + min * 60_000).toISOString();

const p: XpAlertPreview = {
  firings: [
    { from: iso(1), firedAt: iso(3), to: iso(5), group: { group: 'i-1' }, peak: 97, ongoing: false, notified: true },
    { from: iso(9), firedAt: iso(9), to: iso(12), group: { group: 'i-1' }, peak: 92, ongoing: true, notified: false },
  ],
  evaluatedPoints: 2880, wouldNotify: 1, from: iso(0), to: iso(12), op: '>', threshold: 90, groups: 2, resolved: 1, suppressedRepeats: 0, quietReopens: 1,
  series: [{ group: 'i-1', points: [[T, 50], [T + 60_000, 97]] }, { group: 'i-2', points: [[T, 40], [T + 60_000, 60]] }],
  notes: ['Starts from OK: the rule’s state before the range is not known.'],
};

describe('BacktestPanel', () => {
  it('says what the rule would have done in one sentence', () => {
    expect(backtestVerdict(p)).toBe('This rule would have fired 2 times on 1 host and sent 1 notification.');
    expect(backtestVerdict({ ...p, firings: [], wouldNotify: 0 })).toBe('This rule would not have fired in the last 24 hours.');
    expect(backtestVerdict({ ...p, firings: [], evaluatedPoints: 0 })).toMatch(/Nothing to replay/);
  });

  it('draws the threshold across the data and marks each firing where it would have fired', () => {
    const c = backtestChart(p, null);
    const th = c.series.find((s) => s.key === '__threshold');
    expect(th?.points).toEqual([[T, 90], [T + 60_000, 90]]);
    expect(th?.dashed).toBe(true);
    expect(c.markers.map((m) => m.t)).toEqual([Date.parse(iso(3)), Date.parse(iso(9))]);
    expect(c.markers[1].label).toMatch(/not sent/);
    expect(backtestChart(p, 'i-2').markers).toEqual([]);
  });

  it('lists firings with the real fire time, recovery or "still firing", and whether it was sent', () => {
    render(<BacktestPanel p={p} />);
    const region = screen.getByRole('region', { name: 'Backtest result' });
    const rows = within(within(region).getByRole('table', { name: 'Firings in the backtest' })).getAllByRole('row');
    expect(rows).toHaveLength(3);
    expect(rows[2]).toHaveTextContent('still firing');
    expect(rows[2]).toHaveTextContent('No (repeat or quick re-open)');
    expect(within(region).getByText('Quick re-opens')).toBeInTheDocument();
    expect(within(region).getByText(/Starts from OK/)).toBeInTheDocument();
    fireEvent.click(within(region).getByRole('button', { name: 'i-2' }));
    expect(within(region).getByRole('button', { name: 'i-2' })).toHaveAttribute('aria-pressed', 'true');
  });
});
