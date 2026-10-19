/**
 * Unit tests for the chart kit's chrome and plain-SVG primitives (design-delta §3; glossary §10).
 * The colour discipline is covered by colourAudit.test.tsx; these tests cover behaviour: the exact
 * empty / partial copy, the source line, and the deterministic SVG the primitives draw.
 */
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import ChartCard, { EMPTY_COPY } from '../ChartCard';
import ChartDataTable from '../ChartDataTable';
import Sparkline from '../Sparkline';
import MiniBar from '../MiniBar';
import TimeStrip from '../TimeStrip';
import LanesTimeline from '../LanesTimeline';
import SeriesLegend from '../SeriesLegend';
import RangeBar, { rangeBarText } from '../RangeBar';
import Bars, { logDomain } from '../Bars';
import { SERIES, MONEY, HATCH } from '../chartTokens';

describe('chartTokens (design-delta §2.2)', () => {
  it('fixes one line style per series', () => {
    expect(SERIES.requested.strokeWidth).toBe(1.8);
    expect(SERIES.used.strokeWidth).toBe(2.2);
    expect(SERIES.used.dash).toBeUndefined();
    expect(SERIES.suggested.dash).toBe('7 4');
    expect(SERIES.limit.dash).toBe('1.5 3.5');
  });

  it('reads the fills from the "Opacity rules" table', () => {
    expect(SERIES.requested.fillOpacity).toBe(0.16);
    expect(SERIES.used.fillOpacity).toBe(0.26);
    expect(SERIES.suggested.fillOpacity).toBe(0.1);
    expect(MONEY.saved.fillOpacity).toBe(0.24);
  });

  it('every series colour is rgb(var(--k8s-series-…)): the tokens are channel triples, which paint black bare', () => {
    for (const s of Object.values(SERIES)) {
      expect(s.color).toMatch(/^rgb\(var\(--k8s-series-[a-z-]+\)\)$/);
    }
  });

  it('names a pattern id per hatch kind', () => {
    expect(HATCH.noHistory.id).toBe('k8s-hatch-no-history');
    expect(HATCH.notInEffect.id).toBe('k8s-hatch-not-in-effect');
  });
});

describe('ChartCard empty / partial states (glossary §10)', () => {
  it('shows the title and the Source line', () => {
    render(<ChartCard title="CPU, last 3 days" source="Source: Workload autoscaler (BE-3): used, step 30 min, 72 h." />);
    expect(screen.getByText('CPU, last 3 days')).toBeTruthy();
    const source = document.querySelector('[data-chart-source]');
    expect(source?.textContent).toContain('Source:');
    expect(source?.textContent).toContain('step 30 min, 72 h.');
  });

  it('history-short draws a hatched block labelled "No data before {time}"', () => {
    const { container } = render(
      <ChartCard title="t" source="s" state={{ kind: 'history-short', before: '10:00' }} />,
    );
    expect(container.querySelector('[data-empty="history-short"]')).toBeTruthy();
    expect(screen.getByText('No data before 10:00')).toBeTruthy();
  });

  it('activity-off shows the exact Activity copy and keeps the card', () => {
    const { container } = render(<ChartCard title="t" source="s" state={{ kind: 'activity-off' }} />);
    expect(screen.getByText(EMPTY_COPY.activityOff)).toBeTruthy();
    expect(container.querySelector('[data-chart-card]')).toBeTruthy();
  });

  it('no-suggestions shows the exact copy', () => {
    render(<ChartCard title="t" source="s" state={{ kind: 'no-suggestions' }} />);
    expect(screen.getByText('No change suggested for any workload.')).toBeTruthy();
  });

  it('history-pending says "History arrives with the usage feed" and names where it will come from', () => {
    const { container } = render(
      <ChartCard title="CPU and memory over time" source="s" state={{ kind: 'history-pending', from: 'It will come from Prometheus in your cluster.' }}>
        <svg data-testid="never-drawn" />
      </ChartCard>,
    );
    expect(EMPTY_COPY.historyPending).toBe('History arrives with the usage feed');
    expect(container.querySelector('[data-empty="history-pending"]')?.textContent).toContain('History arrives with the usage feed');
    expect(screen.getByText('It will come from Prometheus in your cluster.')).toBeTruthy();
    // No fake points: the chart itself is not drawn.
    expect(screen.queryByTestId('never-drawn')).toBeNull();
  });

  it('unavailable shows its reason and keeps the card and its source', () => {
    const { container } = render(<ChartCard title="Cost per day" source="Prices (planned)." state={{ kind: 'unavailable', reason: "Needs CPU and memory prices, which XamOps doesn't read yet." }} />);
    expect(container.querySelector('[data-empty="unavailable"]')?.textContent).toBe("Needs CPU and memory prices, which XamOps doesn't read yet.");
    expect(container.querySelector('[data-chart-source]')?.textContent).toContain('Prices (planned).');
  });

  it('renders a table slot under the chart', () => {
    render(
      <ChartCard
        title="t"
        source="s"
        table={
          <ChartDataTable
            caption="points"
            rowHeader="Time"
            columns={[{ key: 'used', header: 'Used' }]}
            rows={[{ key: 'r', label: '09:00', values: { used: '10m' } }]}
          />
        }
      />,
    );
    expect(screen.getByText('Data table')).toBeTruthy();
    expect(screen.getByText('10m')).toBeTruthy();
  });

  it('partial draws the chart and says how much history there is', () => {
    const { container } = render(
      <ChartCard title="t" source="s" state={{ kind: 'partial', note: 'Prometheus keeps 3 days here. No data before Oct 11.' }}>
        <svg data-testid="drawn" />
      </ChartCard>,
    );
    expect(screen.getByTestId('drawn')).toBeTruthy();
    expect(container.querySelector('[data-chart-partial]')?.textContent).toBe('Prometheus keeps 3 days here. No data before Oct 11.');
  });

  it('loading shows a skeleton in the chart\'s place and no chart, and error says what happened with Retry', () => {
    const loading = render(
      <ChartCard title="t" source="s" state={{ kind: 'loading' }}>
        <svg data-testid="never-drawn" />
      </ChartCard>,
    );
    expect(loading.container.querySelector('[data-empty="loading"][aria-busy="true"]')).toBeTruthy();
    expect(screen.queryByTestId('never-drawn')).toBeNull();
    loading.unmount();
    const onRetry = vi.fn();
    const failed = render(<ChartCard title="t" source="s" state={{ kind: 'error', message: "Couldn't read this chart's data: HTTP 502", onRetry }} />);
    expect(failed.container.querySelector('[data-empty="error"]')?.textContent).toContain('HTTP 502');
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('keeps its view switch in the header in every state, so a failed resource can be switched back', () => {
    render(<ChartCard title="t" source="s" state={{ kind: 'error', message: 'x' }} actions={<button type="button">CPU</button>} />);
    expect(screen.getByRole('button', { name: 'CPU' }).closest('header')).toBeTruthy();
  });
});

describe('Sparkline', () => {
  it('draws a reference line (the request) on the same scale from 0, so the two heights compare', () => {
    const { container } = render(<Sparkline values={[5, 10]} reference={[20, 20]} width={72} height={20} ariaLabel="s" />);
    const ref = container.querySelector('path[data-sparkline-reference]')!;
    const used = container.querySelector('path:not([data-sparkline-reference])')!;
    expect(ref.getAttribute('class')).toContain('stroke-series-requested');
    expect(used.getAttribute('class')).toContain('stroke-series-used');
    // 20 is the top of the shared scale (y = pad), 10 is halfway, 5 a quarter up.
    expect(ref.getAttribute('d')).toBe('M2.00 2.00 L70.00 2.00');
    expect(used.getAttribute('d')).toBe('M2.00 14.00 L70.00 10.00');
  });

  it('draws one deterministic path for the values', () => {
    const a = render(<Sparkline values={[1, 2, 3, 2]} ariaLabel="s" />);
    const d1 = a.container.querySelector('path')!.getAttribute('d');
    a.unmount();
    const b = render(<Sparkline values={[1, 2, 3, 2]} ariaLabel="s" />);
    const d2 = b.container.querySelector('path')!.getAttribute('d');
    expect(d1).toBe(d2);
    expect(d1).toMatch(/^M/);
  });

  it('breaks the line on a null and shows the empty text when there is nothing to draw', () => {
    const withGap = render(<Sparkline values={[1, null, 3]} ariaLabel="s" />);
    expect(withGap.container.querySelector('path')!.getAttribute('d')).toMatch(/M.*M/s);
    withGap.unmount();
    render(<Sparkline values={[null, null]} />);
    expect(screen.getByText('no pod running')).toBeTruthy();
  });
});

describe('MiniBar', () => {
  it('scales the value bar and draws a secondary bar and a tick', () => {
    const { container } = render(
      <MiniBar value={5} max={10} kind="requested" secondary={{ value: 2, kind: 'used' }} tick={{ value: 7.5, kind: 'suggested' }} width={100} />,
    );
    const rects = container.querySelectorAll('rect');
    // track, value, secondary, tick
    expect(rects.length).toBe(4);
    expect(rects[1].getAttribute('width')).toBe('50.00');
  });

  it('draws nothing but a dash when the value is null', () => {
    render(<MiniBar value={null} max={10} />);
    expect(screen.getByText('—')).toBeTruthy();
  });
});

describe('TimeStrip', () => {
  it('places a held segment and a not-measured block by fraction', () => {
    const { container } = render(
      <TimeStrip segments={[{ from: 0.25, to: 0.5 }]} notMeasuredBefore={0.1} width={200} ariaLabel="x" />,
    );
    expect(container.querySelector('[data-not-measured]')!.getAttribute('width')).toBe('20.00');
    const held = [...container.querySelectorAll('rect')].find((r) => r.getAttribute('x') === '50.00');
    expect(held).toBeTruthy();
    expect(held!.getAttribute('width')).toBe('50.00');
  });
});

describe('SeriesLegend', () => {
  it('names every series beside a swatch in its token colour', () => {
    const { container } = render(
      <SeriesLegend items={[{ label: 'Requested', kind: 'requested' }, { label: 'CPU freed', kind: 'saved', money: true }]} />,
    );
    expect([...container.querySelectorAll('li')].map((li) => li.textContent)).toEqual(['Requested', 'CPU freed']);
    expect([...container.querySelectorAll('rect')].map((r) => r.getAttribute('fill'))).toEqual([SERIES.requested.color, MONEY.saved.color]);
  });
});

describe('LanesTimeline', () => {
  it('draws one lane per workload with a feature source dot and the outcome markers', () => {
    const { container } = render(
      <LanesTimeline
        width={420}
        lanes={[
          { name: 'idle-batch-listener', feature: 'workload', entries: [{ at: 0.5, tone: 'good' }] },
          { name: 'throttled-encoder', feature: 'hpa', entries: [{ at: 0.4, tone: 'warn' }, { at: 0.7, tone: 'info', dryRun: true }] },
        ]}
        ariaLabel="lanes"
      />,
    );
    expect(container.querySelectorAll('[data-lane]').length).toBe(2);
    expect(container.querySelectorAll('[data-feature-dot]').length).toBe(2);
    // The source dots carry the feature accent; the markers carry status tones.
    const dot = container.querySelector('[data-feature-dot="workload"]')!;
    expect(dot.getAttribute('fill')).toBe('rgb(var(--k8s-feature-workload))');
  });

  it('hatches the no-history block before the cluster existed', () => {
    const { container } = render(
      <LanesTimeline lanes={[{ name: 'w', feature: 'node', entries: [] }]} noHistoryBefore={0.2} />,
    );
    expect(container.querySelector('[data-no-history]')).toBeTruthy();
  });
});

describe('RangeBar', () => {
  it('reads "now 5 of 1–5" and places the band and the marker on the scale', () => {
    const { container } = render(<RangeBar min={1} max={5} current={5} width={106} />);
    const svg = container.querySelector('svg[data-range-bar]')!;
    expect(svg.getAttribute('role')).toBe('img');
    expect(svg.getAttribute('aria-label')).toBe('now 5 of 1–5');
    expect(rangeBarText({ min: 1, max: 5, current: 5 })).toBe('now 5 of 1–5');
    // Scale 0..5 over 100 px (3 px padding each side): 1 → 23, 5 → 103.
    const band = container.querySelector('[data-range-band]')!;
    expect(band.getAttribute('x')).toBe('23.00');
    expect(band.getAttribute('width')).toBe('80.00');
    expect(container.querySelector('[data-range-current]')!.getAttribute('cx')).toBe('103.00');
    // Colour only through the series classes: bounds grey, the marker "used".
    expect(band.getAttribute('class')).toBe(SERIES.capacity.fillClass);
    expect(container.querySelector('[data-range-current]')!.getAttribute('class')).toBe(SERIES.used.fillClass);
  });

  it("draws XamOps's suggested bounds as ticks and says so", () => {
    const { container } = render(<RangeBar min={1} max={5} current={5} suggestedMax={8} />);
    expect(container.querySelector('[data-range-suggested="max"]')!.getAttribute('class')).toBe(SERIES.suggested.fillClass);
    expect(container.querySelector('[data-range-suggested="min"]')).toBeNull();
    expect(container.querySelector('svg')!.getAttribute('aria-label')).toBe('now 5 of 1–5, XamOps suggests 1–8');
  });

  it('draws only a dash, never a guessed range, when a bound is unknown', () => {
    const { container } = render(<RangeBar min={null} max={5} current={3} />);
    expect(container.querySelector('svg')).toBeNull();
    expect(container.querySelector('[data-range-bar="empty"]')!.textContent).toContain('—');
    expect(container.textContent).toContain('now 3 of bounds not reported');
  });

  it('keeps the marker off when replicas now are unknown', () => {
    const { container } = render(<RangeBar min={2} max={10} current={null} />);
    expect(container.querySelector('[data-range-current]')).toBeNull();
    expect(container.querySelector('svg')!.getAttribute('aria-label')).toBe('now — of 2–10');
  });
});

describe('Bars on a log scale', () => {
  it('spans powers of ten around the values', () => {
    expect(logDomain([25, 63, 1000])).toEqual({ domain: [10, 1000], ticks: [10, 100, 1000] });
    expect(logDomain([10, 10])).toEqual({ domain: [10, 100], ticks: [10, 100] });
    expect(logDomain([null, 0, -3])).toBeNull();
  });

  it('marks the chart log and declares the hatch its VPA series fills with', () => {
    const { container } = render(
      <Bars
        mode="grouped"
        scale="log"
        width={420}
        xKey="w"
        data={[{ w: 'a', now: 100, xamops: 25, vpa: 0 }, { w: 'b', now: 1000, xamops: null, vpa: 78 }]}
        bars={[
          { dataKey: 'now', kind: 'requested', name: 'Request now' },
          { dataKey: 'xamops', kind: 'suggested', name: 'XamOps suggests' },
          { dataKey: 'vpa', kind: 'suggested', name: 'Kubernetes VPA suggests', hatch: 'suggested' },
        ]}
        ariaLabel="per pod"
      />,
    );
    expect(container.querySelector('[data-bars]')!.getAttribute('data-bars-scale')).toBe('log');
    // The hatch is this chart's own pattern, and the VPA bar fills with it.
    const hatched = container.querySelector('.recharts-surface path[fill^="url(#k8s-hatch-suggested-"]')!;
    const id = /^url\(#([\w-]+)\)$/.exec(hatched.getAttribute('fill')!)![1];
    expect(container.querySelector(`.recharts-surface pattern#${id}`)).not.toBeNull();
  });
});

describe('SeriesLegend hatched swatch', () => {
  it('draws a hatched swatch for a hatched series, in its own SVG', () => {
    const { container } = render(<SeriesLegend items={[{ label: 'Kubernetes VPA suggests', kind: 'suggested', hatch: 'suggested' }]} />);
    const rect = container.querySelector('rect')!;
    const id = /^url\(#(k8s-legend-hatch-suggested-[\w-]+)\)$/.exec(rect.getAttribute('fill') ?? '')?.[1];
    expect(id).toBeTruthy();
    expect(rect.getAttribute('stroke')).toBe(SERIES.suggested.color);
    expect(container.querySelector(`svg pattern#${id}`)).not.toBeNull();
  });
});
