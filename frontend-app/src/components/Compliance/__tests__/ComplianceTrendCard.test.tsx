import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import ComplianceTrendCard from '../ComplianceTrendCard';
import type { ComplianceTrendPoint } from '../../../types/compliance';

/**
 * Guards the honesty of the trend chart.
 *
 * The version this component replaced synthesised a 14-day improving curve from today's
 * counts (`decay = 1 - progress * 0.15` plus a sine wave), so it always sloped downwards
 * regardless of the account. These tests exist to make sure nobody reintroduces a chart
 * that draws more points than there are scans, and that too little data reads as too
 * little data rather than as a flat, stable line.
 */

const point = (offsetDays: number, score: number | null): ComplianceTrendPoint => ({
  scanId: 1000 + offsetDays,
  at: new Date(Date.now() - offsetDays * 86_400_000).toISOString(),
  score,
  passedControls: 10,
  failedControls: 5,
  critical: 1,
  high: 2,
  medium: 1,
  low: 1,
});

describe('ComplianceTrendCard', () => {
  it('says there are no scans rather than drawing an empty chart', () => {
    render(<ComplianceTrendCard trend={[]} mode="score" onModeChange={vi.fn()} />);

    expect(screen.getByText(/no completed scans yet/i)).toBeInTheDocument();
    expect(screen.getByText(/needs at least two scans/i)).toBeInTheDocument();
  });

  it('refuses to plot a single scan as a trend', () => {
    render(<ComplianceTrendCard trend={[point(0, 72)]} mode="score" onModeChange={vi.fn()} />);

    // One point drawn as a line would imply a measured period of stability that no scan
    // established.
    expect(screen.getByText(/one scan so far/i)).toBeInTheDocument();
    expect(screen.getByText(/1 completed scan on record/i)).toBeInTheDocument();
  });

  it('reports the delta between the first and last scored scan, not a generated slope', () => {
    render(
      <ComplianceTrendCard
        trend={[point(14, 60), point(7, 68), point(0, 74)]}
        mode="score"
        onModeChange={vi.fn()}
      />,
    );

    expect(screen.getByText('+14 pts')).toBeInTheDocument();
    expect(screen.getByText(/from 60% to 74% across 3 scans/i)).toBeInTheDocument();
  });

  it('reports a decline as a decline', () => {
    render(
      <ComplianceTrendCard
        trend={[point(14, 80), point(0, 62)]}
        mode="score"
        onModeChange={vi.fn()}
      />,
    );

    expect(screen.getByText('-18 pts')).toBeInTheDocument();
  });

  it('ignores unscored scans when computing the delta', () => {
    // A scan with nothing scoreable has a null score. Treating it as zero would show a
    // catastrophic drop that never happened.
    render(
      <ComplianceTrendCard
        trend={[point(21, 70), point(14, null), point(0, 76)]}
        mode="score"
        onModeChange={vi.fn()}
      />,
    );

    expect(screen.getByText('+6 pts')).toBeInTheDocument();
    expect(screen.getByText(/across 2 scans/i)).toBeInTheDocument();
  });

  it('states the number of scans on record so the sample size is never hidden', () => {
    render(
      <ComplianceTrendCard
        trend={[point(14, 60), point(7, 65), point(0, 70)]}
        mode="score"
        onModeChange={vi.fn()}
      />,
    );

    expect(screen.getByText(/3 completed scans on record/i)).toBeInTheDocument();
  });
});
