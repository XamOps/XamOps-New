import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import { TimeSeriesChart, clusterMarkers, shortDuration, timeTicks, type ChartSeries } from './TimeSeriesChart';

const MIN = 60_000;
const T0 = Date.parse('2026-11-10T10:00:00Z');

/** 10 minutes of data, the agent offline for 20 minutes, then 5 more minutes; the range runs 10 minutes past the end. */
function withGap(): ChartSeries {
  const pts: [number, number][] = [];
  for (let i = 0; i < 10; i += 1) pts.push([T0 + i * MIN, 40 + i]);
  for (let i = 30; i < 35; i += 1) pts.push([T0 + i * MIN, 60]);
  return { key: 'cpu', name: 'CPU', points: pts };
}

describe('TimeSeriesChart', () => {
  it('breaks the line where data is missing and shades the gap as "No data"', () => {
    const { container } = render(
      <TimeSeriesChart series={[withGap()]} fromMs={T0} toMs={T0 + 35 * MIN} ariaLabel="CPU" format={(v) => `${v}%`} />,
    );
    const line = container.querySelector('path[stroke]');
    expect(line).not.toBeNull();
    // Two runs of data: two "move to" commands, so no segment is drawn across the 20 offline minutes.
    expect((line?.getAttribute('d') ?? '').match(/M/g)?.length).toBe(2);
    // No curves: only straight segments between real samples.
    expect(line?.getAttribute('d')).not.toMatch(/C/);
    const gaps = container.querySelectorAll('rect.xp-chart__gap');
    expect(gaps.length).toBe(1);
    expect(gaps[0].querySelector('title')?.textContent).toMatch(/^No data /);
    expect(container.querySelector('svg')?.getAttribute('aria-label')).toMatch(/No data for \d+ minutes of the range/);
  });

  it('marks the legend value as stale when the last sample is long before the end of the range', () => {
    const { container, rerender } = render(
      <TimeSeriesChart showLegend series={[withGap()]} fromMs={T0} toMs={T0 + 60 * MIN} ariaLabel="CPU" format={(v) => `${v}%`} />,
    );
    expect(container.querySelector('.xp-legend')?.textContent).toContain('no recent data');
    rerender(<TimeSeriesChart showLegend series={[withGap()]} fromMs={T0} toMs={T0 + 35 * MIN} ariaLabel="CPU" format={(v) => `${v}%`} />);
    expect(container.querySelector('.xp-legend')?.textContent).toContain('60%');
  });

  it('draws no gap when samples are continuous', () => {
    const pts: [number, number][] = Array.from({ length: 30 }, (_, i) => [T0 + i * MIN, 10]);
    const { container } = render(<TimeSeriesChart series={[{ key: 'm', name: 'Mem', points: pts }]} fromMs={T0} toMs={T0 + 29 * MIN} ariaLabel="Memory" />);
    expect(container.querySelectorAll('rect.xp-chart__gap').length).toBe(0);
    expect((container.querySelector('path[stroke]')?.getAttribute('d') ?? '').match(/M/g)?.length).toBe(1);
  });

  it('says which legend number is which: the latest value and the peak in range', () => {
    const { container } = render(<TimeSeriesChart showLegend series={[withGap()]} fromMs={T0} toMs={T0 + 35 * MIN} ariaLabel="CPU" format={(v) => `${v}%`} />);
    expect(container.querySelector('.xp-legend')?.textContent).toContain('60% now');
    const spike: ChartSeries = { key: 's', name: 'CPU', points: [[T0, 20], [T0 + MIN, 95], [T0 + 2 * MIN, 30]] };
    const { container: c2 } = render(<TimeSeriesChart showLegend series={[spike]} fromMs={T0} toMs={T0 + 2 * MIN} ariaLabel="CPU" format={(v) => `${v}%`} />);
    expect(c2.querySelector('.xp-legend')?.textContent).toContain('30% now · peak 95%');
  });

  it('labels a long gap with how long it lasted', () => {
    const pts: [number, number][] = [[T0, 1], [T0 + MIN, 1], [T0 + 7 * 24 * 60 * MIN, 1], [T0 + 7 * 24 * 60 * MIN + MIN, 1]];
    const { container } = render(<TimeSeriesChart series={[{ key: 'n', name: 'Net', points: pts }]} fromMs={T0} toMs={T0 + 7 * 24 * 60 * MIN + MIN} ariaLabel="Net" />);
    expect(container.querySelector('text.xp-chart__gaplabel')?.textContent).toMatch(/^No data · 6d 23h|^No data · 7d/);
  });
});

describe('stacked and reference lines', () => {
  const pts = (v: number): [number, number][] => Array.from({ length: 10 }, (_, i) => [T0 + i * MIN, v]);
  it('stacks series as filled bands whose top is the total, with no separate lines', () => {
    const { container } = render(<TimeSeriesChart stacked series={[{ key: 'u', name: 'User', points: pts(60) }, { key: 's', name: 'System', points: pts(15) }]}
      fromMs={T0} toMs={T0 + 9 * MIN} yMax={100} ariaLabel="CPU by state" format={(v) => `${v}%`} />);
    const bands = container.querySelectorAll('path.xp-chart__stack');
    expect(bands.length).toBe(2);
    expect(container.querySelectorAll('path[fill="none"]').length).toBe(0);
  });
  it('keeps a reference line in view even when the data is far below it', () => {
    const { container } = render(<TimeSeriesChart series={[{ key: 'l', name: 'Load', points: pts(0.2) }]} refLines={[{ value: 1, label: '1.0 = all vCPUs busy' }]}
      fromMs={T0} toMs={T0 + 9 * MIN} ariaLabel="Load per vCPU" format={(v) => v.toFixed(2)} />);
    expect(container.querySelector('text.xp-chart__reflabel')?.textContent).toBe('1.0 = all vCPUs busy');
    const labels = Array.from(container.querySelectorAll('svg text')).map((t) => t.textContent);
    expect(labels.some((l) => l !== null && parseFloat(l) >= 1)).toBe(true);
  });
});

describe('time axis', () => {
  const H = 60 * MIN;
  const D = 24 * H;
  it.each([[H, 420], [24 * H, 420], [7 * D, 420], [7 * D, 1200], [30 * D, 600]])('never more ticks than fit (span %i, width %i)', (span, w) => {
    const ticks = timeTicks(T0, T0 + span, w);
    expect(ticks.length).toBeGreaterThanOrEqual(1);
    expect(ticks.length).toBeLessThanOrEqual(Math.max(2, Math.floor(w / 85)));
    ticks.forEach((t) => { expect(t.t).toBeGreaterThanOrEqual(T0); expect(t.t).toBeLessThanOrEqual(T0 + span); });
    const gaps = ticks.slice(1).map((t, i) => t.t - ticks[i].t);
    gaps.forEach((g) => expect(g).toBe(gaps[0]));
  });
  it('uses HH:mm inside a day and day labels on weekly ranges, never seconds', () => {
    timeTicks(T0, T0 + H, 420).forEach((t) => expect(t.label).toMatch(/^\d{2}:\d{2}$/));
    timeTicks(T0, T0 + 30 * D, 600).forEach((t) => expect(t.label).not.toMatch(/:/));
    timeTicks(T0, T0 + 7 * D, 420).forEach((t) => expect(t.label).not.toMatch(/:\d{2}:/));
  });
  it('lands on round local times', () => {
    timeTicks(T0, T0 + 6 * H, 600).forEach((t) => expect(new Date(t.t).getMinutes() % 30).toBe(0));
  });
});

describe('helpers', () => {
  it('shortDuration reads naturally', () => {
    expect(shortDuration(45 * MIN)).toBe('45m');
    expect(shortDuration(190 * MIN)).toBe('3h 10m');
    expect(shortDuration((6 * 24 + 22) * 60 * MIN)).toBe('6d 22h');
    expect(shortDuration(2 * 24 * 60 * MIN)).toBe('2d');
  });
  it('merges markers closer than 12 px and keeps their labels', () => {
    const toX = (t: number) => (t - T0) / MIN;
    const out = clusterMarkers([{ t: T0, label: 'a' }, { t: T0 + 5 * MIN, label: 'b' }, { t: T0 + 40 * MIN, label: 'c' }], toX);
    expect(out.map((c) => c.labels)).toEqual([['a', 'b'], ['c']]);
  });
});
