import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';

import SeverityDot from '../SeverityDot';
import SeverityPill from '../SeverityPill';
import SeverityBarStrip from '../SeverityBarStrip';
import CountUp from '../CountUp';
import TrendBars from '../TrendBars';
import { SEVERITY } from '../tokens';

/**
 * Unit coverage for the Overview re-skin's new presentational pieces. jsdom has no `matchMedia`,
 * so the reduced-motion tests install a stub that reports `matches: true` and assert the final
 * static state is rendered immediately (CountUp final value, TrendBars final heights).
 */

function mockMatchMedia(matches: boolean) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    configurable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      matches,
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

afterEach(() => {
  vi.restoreAllMocks();
  // Leave matchMedia undefined between tests (jsdom default) so each test sets its own.
  Object.defineProperty(window, 'matchMedia', { writable: true, configurable: true, value: undefined });
});

describe('SeverityDot', () => {
  it('renders the uppercase text label (severity is never color-only) in the level color', () => {
    render(<SeverityDot severity="critical" />);
    const label = screen.getByText('CRITICAL');
    expect(label).toBeInTheDocument();
    expect(label).toHaveStyle({ color: SEVERITY.CRITICAL.pillText });
  });

  it('falls back to the UNKNOWN label + palette when severity is null', () => {
    render(<SeverityDot severity={null} />);
    const label = screen.getByText('UNKNOWN');
    expect(label).toBeInTheDocument();
    expect(label).toHaveStyle({ color: SEVERITY.UNKNOWN.pillText });
  });

  it('falls back to UNKNOWN for an unrecognized severity string', () => {
    render(<SeverityDot severity="whatever" />);
    expect(screen.getByText('WHATEVER')).toHaveStyle({ color: SEVERITY.UNKNOWN.pillText });
  });
});

describe('SeverityPill', () => {
  it('renders the locale-grouped count with the level pill colors', () => {
    render(<SeverityPill severity="HIGH" count={1234} />);
    const pill = screen.getByText('1,234');
    expect(pill).toHaveStyle({ background: SEVERITY.HIGH.pillBg, color: SEVERITY.HIGH.pillText });
  });

  it('uses the UNKNOWN palette when severity is null', () => {
    render(<SeverityPill severity={null} count={0} />);
    expect(screen.getByText('0')).toHaveStyle({ color: SEVERITY.UNKNOWN.pillText });
  });
});

describe('SeverityBarStrip', () => {
  it('sizes segments by each severity share of the total', () => {
    const { container } = render(
      <SeverityBarStrip counts={{ CRITICAL: 1, HIGH: 1, MEDIUM: 0, LOW: 2 }} />,
    );
    const segments = container.querySelectorAll('span');
    // CRITICAL (1/4=25%), HIGH (1/4=25%), LOW (2/4=50%); MEDIUM is zero → omitted.
    expect(segments).toHaveLength(3);
    expect(segments[0]).toHaveStyle({ width: '25%', background: SEVERITY.CRITICAL.dot });
    expect(segments[1]).toHaveStyle({ width: '25%', background: SEVERITY.HIGH.dot });
    expect(segments[2]).toHaveStyle({ width: '50%', background: SEVERITY.LOW.dot });
  });

  it('renders the empty track with no segments when the total is zero', () => {
    const { container } = render(
      <SeverityBarStrip counts={{ CRITICAL: 0, HIGH: 0, MEDIUM: 0, LOW: 0 }} />,
    );
    expect(container.querySelectorAll('span')).toHaveLength(0);
    expect(screen.getByRole('img')).toHaveAttribute('aria-label', 'Severity distribution: No findings.');
  });
});

describe('CountUp under reduced motion', () => {
  it('renders the final value immediately when prefers-reduced-motion is set', () => {
    mockMatchMedia(true);
    render(<CountUp value={4096} />);
    expect(screen.getByText('4,096')).toBeInTheDocument();
  });
});

describe('TrendBars under reduced motion', () => {
  it('renders bars at their final heights immediately when reduced', () => {
    mockMatchMedia(true);
    const { container } = render(
      <TrendBars data={[{ label: 'A', value: 0 }, { label: 'B', value: 50 }, { label: 'C', value: 100 }]} />,
    );
    const bars = container.querySelectorAll('.rounded-t-md');
    expect(bars).toHaveLength(3);
    // max value is 100 → the full-height bar (C) is 84px (TRACK_H); A (value 0) is 0px.
    expect((bars[2] as HTMLElement).style.height).toBe('84px');
    expect((bars[0] as HTMLElement).style.height).toBe('0px');
    // No height transition under reduced motion.
    expect((bars[2] as HTMLElement).style.transition).toBe('');
  });
});
