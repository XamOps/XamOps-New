import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import CapacityBar from '../CapacityBar';
import { CAPACITY_TERMS } from '../CapacityDonut';

const fmt = (v: number | null) => (v === null ? '—' : `${v} cores`);

describe('CapacityBar (FEAT-001)', () => {
  it('draws the requested fill and used overlay against allocatable', () => {
    const { container } = render(
      <CapacityBar resource="CPU" allocatable={8} requested={4} used={2} format={fmt} />,
    );
    expect(container.querySelector('.bg-series-allocatable')).toBeTruthy();
    expect(container.querySelector('.bg-series-requested')).toBeTruthy();
    expect(container.querySelector('.bg-series-used')).toBeTruthy();
  });

  it('labels carry the verbatim CAPACITY_TERMS tooltips', () => {
    render(<CapacityBar resource="CPU" allocatable={8} requested={4} used={2} format={fmt} />);
    expect(screen.getByText('Requested')).toHaveAttribute('title', CAPACITY_TERMS.requested);
    expect(screen.getByText('Used')).toHaveAttribute('title', CAPACITY_TERMS.used);
    expect(screen.getByText('Allocatable')).toHaveAttribute('title', CAPACITY_TERMS.allocatable);
  });

  it('shows a muted em-dash, never 0, for a missing datum', () => {
    render(<CapacityBar resource="CPU" allocatable={8} requested={4} used={null} format={fmt} missingReason="no agent" />);
    const dash = screen.getByText('—');
    expect(dash).toHaveAttribute('title', 'no agent');
  });

  it('drops the bar (no series fill) when allocatable is unknown', () => {
    const { container } = render(
      <CapacityBar resource="CPU" allocatable={null} requested={4} used={2} format={fmt} missingReason="no capacity" />,
    );
    expect(container.querySelector('.bg-series-allocatable')).toBeNull();
    expect(container.querySelector('.bg-series-requested')).toBeNull();
  });
});
