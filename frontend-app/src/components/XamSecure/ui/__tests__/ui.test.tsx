import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import type Highcharts from 'highcharts';
import SeverityLetterBadge from '../SeverityLetterBadge';
import SeverityGradientBar from '../SeverityGradientBar';
import SeverityPillBars from '../SeverityPillBars';
import KpiHero from '../KpiHero';
import CountChip from '../CountChip';
import MiniFeedRow, { RowChevron } from '../MiniFeedRow';
import FunnelStream from '../FunnelStream';
import FindingsOverTimeBars from '../FindingsOverTimeBars';
import { buildAreaOptions } from '../SeverityAreaChart';
import { sevTokens } from '../tokens';

/** matchMedia mock: pass `reduce` to simulate prefers-reduced-motion. */
function mockMatchMedia(reduce: boolean) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    configurable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      matches: query.includes('reduce') ? reduce : false,
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  });
}

describe('SeverityLetterBadge', () => {
  it('renders the correct letter and glyph token per severity', () => {
    const cases: Array<[string, string, string]> = [
      ['CRITICAL', 'C', 'var(--xs-badge-c)'],
      ['HIGH', 'H', 'var(--xs-badge-h)'],
      ['MEDIUM', 'M', 'var(--xs-badge-m)'],
      ['LOW', 'L', 'var(--xs-badge-l)'],
    ];
    for (const [sev, letter, glyph] of cases) {
      const { unmount } = render(<SeverityLetterBadge severity={sev} />);
      const badge = screen.getByTestId('severity-letter-badge');
      expect(badge).toHaveTextContent(letter);
      expect(badge.style.color).toBe(glyph);
      unmount();
    }
  });

  it('maps an unknown severity to O (never color-alone still carries a letter)', () => {
    render(<SeverityLetterBadge severity="WHATEVER" />);
    const badge = screen.getByTestId('severity-letter-badge');
    expect(badge).toHaveTextContent('O');
    expect(badge.style.color).toBe('var(--xs-badge-o)');
    // Always labelled for assistive tech, so color is never the sole carrier.
    expect(badge).toHaveAttribute('aria-label', 'Severity WHATEVER');
  });

  it('maps null to O', () => {
    render(<SeverityLetterBadge severity={null} />);
    expect(screen.getByTestId('severity-letter-badge')).toHaveTextContent('O');
  });
});

describe('sevTokens', () => {
  it('resolves known severities and falls back to Other', () => {
    expect(sevTokens('critical').letter).toBe('C');
    expect(sevTokens('HIGH').letter).toBe('H');
    expect(sevTokens(null).letter).toBe('O');
    expect(sevTokens('nonsense').letter).toBe('O');
  });
});

describe('SeverityGradientBar', () => {
  it('shows the exact counts from props in the legend', () => {
    render(<SeverityGradientBar counts={{ CRITICAL: 11, HIGH: 50, MEDIUM: 83, LOW: 6 }} />);
    expect(screen.getByText('11')).toBeInTheDocument();
    expect(screen.getByText('50')).toBeInTheDocument();
    expect(screen.getByText('83')).toBeInTheDocument();
    expect(screen.getByText('6')).toBeInTheDocument();
    expect(screen.getByText('Critical')).toBeInTheDocument();
  });

  it('collapses a zero segment (the bar has no segment for it)', () => {
    render(<SeverityGradientBar counts={{ CRITICAL: 10, HIGH: 0, MEDIUM: 5, LOW: 0 }} />);
    expect(screen.getByTestId('gradient-segment-CRITICAL')).toBeInTheDocument();
    expect(screen.getByTestId('gradient-segment-MEDIUM')).toBeInTheDocument();
    expect(screen.queryByTestId('gradient-segment-HIGH')).not.toBeInTheDocument();
    expect(screen.queryByTestId('gradient-segment-LOW')).not.toBeInTheDocument();
  });

  it('fires onSegmentClick with the severity', () => {
    const onClick = vi.fn();
    render(<SeverityGradientBar counts={{ CRITICAL: 1, HIGH: 1, MEDIUM: 1, LOW: 1 }} onSegmentClick={onClick} />);
    fireEvent.click(screen.getByText('Critical'));
    expect(onClick).toHaveBeenCalledWith('CRITICAL');
  });
});

describe('SeverityPillBars — horizontal', () => {
  beforeEach(() => mockMatchMedia(false));

  it('width ∝ value/max, and zero → a 6px dot', () => {
    render(
      <SeverityPillBars
        mode="horizontal"
        max={100}
        rows={[
          { sev: 'CRITICAL', value: 50 },
          { sev: 'HIGH', value: 0 },
          { sev: 'MEDIUM', value: 100 },
          { sev: 'LOW', value: 25 },
        ]}
        caption="169 total issues"
      />,
    );
    expect(screen.getByTestId('hbar-CRITICAL').style.width).toBe('50%');
    expect(screen.getByTestId('hbar-MEDIUM').style.width).toBe('100%');
    // Zero renders the dot, not a bar.
    expect(screen.queryByTestId('hbar-HIGH')).not.toBeInTheDocument();
    const dot = screen.getByTestId('hbar-zero-HIGH');
    expect(dot.style.width).toBe('6px');
    expect(dot.style.height).toBe('6px');
    expect(screen.getByText('169 total issues')).toBeInTheDocument();
  });

  it('fires onRowClick with the severity', () => {
    const onRowClick = vi.fn();
    render(
      <SeverityPillBars mode="horizontal" max={10} rows={[{ sev: 'CRITICAL', value: 5 }]} onRowClick={onRowClick} />,
    );
    fireEvent.click(screen.getByTestId('hbar-CRITICAL').closest('button')!);
    expect(onRowClick).toHaveBeenCalledWith('CRITICAL');
  });
});

describe('SeverityPillBars — vertical', () => {
  it('renders only the single group (no Resolved) with its footer, bars at final height under reduced motion', () => {
    mockMatchMedia(true);
    render(
      <SeverityPillBars
        mode="vertical"
        max={100}
        cols={[
          { sev: 'CRITICAL', value: 100 },
          { sev: 'HIGH', value: 0 },
          { sev: 'OTHER', value: 50 },
        ]}
        footerValue={150}
        footerLabel="open issues"
      />,
    );
    // Final static height immediately under reduced motion.
    expect(screen.getByTestId('vbar-CRITICAL').style.height).toBe('150px');
    expect(screen.getByTestId('vbar-OTHER').style.height).toBe('75px');
    expect(screen.getByTestId('vbar-zero-HIGH')).toBeInTheDocument();
    expect(screen.getByText('150')).toBeInTheDocument();
    expect(screen.getByText('open issues')).toBeInTheDocument();
    // No "Resolved" group label anywhere.
    expect(screen.queryByText(/resolved/i)).not.toBeInTheDocument();
  });
});

describe('KpiHero', () => {
  it('shows the passed count (no percentage) and applies the good tone tint at value 0 under reduced motion', () => {
    mockMatchMedia(true);
    render(<KpiHero icon="mdi:shield-alert-outline" value={0} label="Active threat stories" desc="No active stories" tone="good" />);
    const value = screen.getByTestId('kpi-hero-value');
    // Final count rendered immediately (reduced motion), no % sign.
    expect(value).toHaveTextContent('0');
    expect(value.textContent).not.toContain('%');
    expect(value.style.color).toBe('var(--xs-green-00)');
    expect(screen.getByText('Active threat stories')).toBeInTheDocument();
  });

  it('applies the bad tone when value > 0', () => {
    mockMatchMedia(true);
    render(<KpiHero icon="mdi:shield-alert-outline" value={7} label="Active threat stories" tone="bad" />);
    expect(screen.getByTestId('kpi-hero-value')).toHaveTextContent('7');
    expect(screen.getByTestId('kpi-hero-value').style.color).toBe('var(--xs-pink-00)');
  });

  it('renders the story list passed as children', () => {
    mockMatchMedia(true);
    render(
      <KpiHero icon="mdi:shield-alert-outline" value={2} label="Active threat stories" tone="bad">
        <MiniFeedRow severity="CRITICAL" title="A correlated story" rightSlot={<RowChevron />} onClick={() => undefined} />
      </KpiHero>,
    );
    expect(screen.getByText('A correlated story')).toBeInTheDocument();
  });
});

describe('CountChip', () => {
  it('renders the value and AA-corrected text token per variant', () => {
    const { rerender } = render(<CountChip value={12} variant="resources" />);
    let chip = screen.getByTestId('count-chip');
    expect(chip).toHaveTextContent('12');
    expect(chip.style.color).toBe('var(--xs-blue-01)');

    rerender(<CountChip value={3} variant="events" />);
    chip = screen.getByTestId('count-chip');
    expect(chip.style.color).toBe('var(--xs-purple-01)');
  });
});

describe('MiniFeedRow', () => {
  it('renders the title, badge and right slot, and fires onClick only when enabled', () => {
    const onClick = vi.fn();
    const { rerender } = render(
      <MiniFeedRow severity="HIGH" title="Reverse shell spawned" rightSlot={<span>res-1</span>} onClick={onClick} />,
    );
    expect(screen.getByText('Reverse shell spawned')).toBeInTheDocument();
    expect(within(screen.getByTestId('severity-letter-badge')).queryByText('H') ?? screen.getByTestId('severity-letter-badge')).toHaveTextContent('H');
    expect(screen.getByText('res-1')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button'));
    expect(onClick).toHaveBeenCalledTimes(1);

    // Disabled: no button role, handler not wired.
    rerender(<MiniFeedRow severity="HIGH" title="Reverse shell spawned" onClick={onClick} disabled />);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});

describe('FunnelStream', () => {
  it('renders three stat values and well-formed ribbon paths', () => {
    render(<FunnelStream events={120} detections={40} stories={7} />);
    expect(screen.getByText('Events')).toBeInTheDocument();
    expect(screen.getByText('120')).toBeInTheDocument();
    expect(screen.getByText('40')).toBeInTheDocument();
    expect(screen.getByText('7')).toBeInTheDocument();
    // Three decorative layers, each a non-empty SVG path (ribbonPath output).
    for (const i of [0, 1, 2]) {
      const layer = screen.getByTestId(`funnel-layer-${i}`);
      const d = layer.getAttribute('d')!;
      expect(d.length).toBeGreaterThan(0);
      expect(d.startsWith('M')).toBe(true);
      expect(d.includes('C')).toBe(true);
    }
  });

  it('renders an empty-message child when passed', () => {
    render(<FunnelStream events={0} detections={0} stories={0}><p>No recent threat detections.</p></FunnelStream>);
    expect(screen.getByText('No recent threat detections.')).toBeInTheDocument();
  });
});

describe('FindingsOverTimeBars', () => {
  it('grows bars by value/max and renders a 6px dot for a zero day', () => {
    mockMatchMedia(false);
    render(<FindingsOverTimeBars data={[{ label: 'A', value: 10 }, { label: 'B', value: 0 }, { label: 'C', value: 5 }]} height={100} />);
    expect(screen.getByTestId('fot-bar-A').style.height).toBe('100px');
    expect(screen.getByTestId('fot-bar-C').style.height).toBe('50px');
    const dot = screen.getByTestId('fot-zero-B');
    expect(dot.style.width).toBe('6px');
  });
});

describe('SeverityAreaChart buildAreaOptions (data binding + font + reduced-motion)', () => {
  const rows: Array<Record<string, number | string>> = [
    { date: 'Jan 01', CRITICAL: 3, HIGH: 5, OTHER: 2 },
    { date: 'Jan 02', CRITICAL: 4, HIGH: '7', OTHER: 1 }, // string cell coerces
    { date: 'Jan 03', CRITICAL: 2 }, // missing cells coerce to 0
  ];

  it('maps xAxis.categories to data.map(r => r.date)', () => {
    const opts = buildAreaOptions(rows, { height: 230, reducedMotion: false });
    const xAxis = opts.xAxis as Highcharts.XAxisOptions;
    expect(xAxis.categories).toEqual(['Jan 01', 'Jan 02', 'Jan 03']);
  });

  it('binds series in the fixed Critical/High/Other order with Number(r[KEY])||0', () => {
    const opts = buildAreaOptions(rows, { height: 230, reducedMotion: false });
    const series = opts.series as Array<{ name: string; data: number[] }>;
    expect(series[0].name).toBe('Critical');
    expect(series[1].name).toBe('High');
    expect(series[2].name).toBe('Other');
    expect(series[0].data).toEqual([3, 4, 2]);
    expect(series[1].data).toEqual([5, 7, 0]); // '7' → 7, missing → 0
    expect(series[2].data).toEqual([2, 1, 0]);
  });

  it('sets the chart font to inherit (never the nonexistent --font-default-family)', () => {
    const opts = buildAreaOptions(rows, { height: 230, reducedMotion: false });
    expect(opts.chart?.style?.fontFamily).toBe('inherit');
    expect(JSON.stringify(opts)).not.toContain('font-default-family');
  });

  it('has no gridlines, 2px line width, and a transparent background', () => {
    const opts = buildAreaOptions(rows, { height: 230, reducedMotion: false });
    expect((opts.xAxis as Highcharts.XAxisOptions).gridLineWidth).toBe(0);
    expect((opts.yAxis as Highcharts.YAxisOptions).gridLineWidth).toBe(0);
    expect(opts.plotOptions?.areaspline?.lineWidth).toBe(2);
    expect(opts.chart?.backgroundColor).toBe('transparent');
    expect(opts.legend?.enabled).toBe(false);
  });

  it('disables animation under reduced motion and enables it otherwise', () => {
    expect(buildAreaOptions(rows, { height: 230, reducedMotion: true }).plotOptions?.series?.animation).toBe(false);
    expect(buildAreaOptions(rows, { height: 230, reducedMotion: false }).plotOptions?.series?.animation).toEqual({ duration: 1000 });
  });
});

afterEach(() => {
  vi.restoreAllMocks();
});
