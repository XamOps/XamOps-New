/**
 * Unit tests for the chart kit's chrome and plain-SVG primitives (design-delta §3; glossary §10).
 * The colour discipline is covered by colourAudit.test.tsx; these tests cover behaviour: the exact
 * empty / partial copy, the source line, and the deterministic SVG the primitives draw.
 */
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import ChartCard, { EMPTY_COPY } from '../ChartCard';
import ChartDataTable from '../ChartDataTable';
import Sparkline from '../Sparkline';
import MiniBar from '../MiniBar';
import TimeStrip from '../TimeStrip';
import LanesTimeline from '../LanesTimeline';
import SeriesLegend from '../SeriesLegend';
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
});

describe('Sparkline', () => {
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
