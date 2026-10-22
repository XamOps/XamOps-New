import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import MetricStrip from '../MetricStrip';

describe('MetricStrip (FEAT-001)', () => {
  it('renders a cell per entry with a label, figure, and sub-line', () => {
    render(
      <MetricStrip
        aria-label="Cluster metrics"
        cells={[
          { label: 'Monthly cost', value: '$1,240', sub: 'this month' },
          { label: 'Nodes', value: '12' },
        ]}
      />,
    );
    expect(screen.getByText('Monthly cost')).toBeInTheDocument();
    expect(screen.getByText('$1,240')).toBeInTheDocument();
    expect(screen.getByText('this month')).toBeInTheDocument();
    expect(screen.getByText('Nodes')).toBeInTheDocument();
  });

  it('shows a Trend chip only when a prior exists', () => {
    render(
      <MetricStrip
        cells={[
          { label: 'With prior', value: '120', trend: { current: 120, prior: 100 } },
          { label: 'No prior', value: '5', trend: { current: 5, prior: null } },
        ]}
      />,
    );
    // Only one trend chip renders (the one with a prior).
    expect(document.querySelectorAll('[data-trend]')).toHaveLength(1);
  });

  it('renders a matching skeleton when loading', () => {
    const { container } = render(
      <MetricStrip
        aria-label="Loading metrics"
        loading
        cells={[{ label: 'a', value: '1' }, { label: 'b', value: '2' }, { label: 'c', value: '3' }]}
      />,
    );
    const strip = container.querySelector('[aria-busy="true"]')!;
    expect(strip).toBeTruthy();
    // No real values rendered while loading.
    expect(screen.queryByText('1')).toBeNull();
  });
});
