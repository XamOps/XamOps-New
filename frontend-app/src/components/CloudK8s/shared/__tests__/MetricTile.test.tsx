import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { MetricTile } from '../MetricTile';

describe('MetricTile (§10.1, D10)', () => {
  it('shows a known value with its suffix, detail and source', () => {
    render(<MetricTile label="Nodes ready" value={6} suffix="/ 6" detail="0 not ready" source="XamOps agent · k8s-resources" />);
    const tile = screen.getByRole('group', { name: 'Nodes ready' });
    expect(tile).toHaveAttribute('data-known', 'true');
    expect(tile).toHaveTextContent('6/ 6');
    expect(within(tile).getByText('0 not ready')).toBeInTheDocument();
    expect(within(tile).getByText('XamOps agent · k8s-resources')).toBeInTheDocument();
  });

  it('shows "—" and who did not report it for an unknown value, never 0', () => {
    render(<MetricTile label="CPU used" value={null} format={(v) => `${v}%`} suffix="of allocatable" detail="hidden" source="Agent · prometheus-metrics" />);
    const tile = screen.getByRole('group', { name: 'CPU used' });
    expect(tile).toHaveAttribute('data-known', 'false');
    expect(tile).toHaveTextContent('—');
    expect(tile).not.toHaveTextContent('0');
    expect(within(tile).getByText('Not reported by Agent · prometheus-metrics')).toBeInTheDocument();
    expect(within(tile).queryByText('hidden')).toBeNull();
    expect(within(tile).queryByText('of allocatable')).toBeNull();
  });

  it('treats a real 0 as a value and formats it', () => {
    render(<MetricTile label="Failed pods" value={0} format={(v) => v.toFixed(0)} source="XamOps agent" missingText="Unknown" />);
    const tile = screen.getByRole('group', { name: 'Failed pods' });
    expect(tile).toHaveTextContent('0');
    expect(within(tile).queryByText('Unknown')).toBeNull();
  });

  it('uses the caller\'s missing text', () => {
    render(<MetricTile label="Cost today" value={undefined} source="No cost source" missingText="Install OpenCost to see cost" />);
    expect(screen.getByText('Install OpenCost to see cost')).toBeInTheDocument();
  });
});
