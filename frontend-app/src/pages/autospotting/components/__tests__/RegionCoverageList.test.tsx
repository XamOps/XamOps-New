import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import RegionCoverageList from '../RegionCoverageList';

describe('RegionCoverageList', () => {
  it('shows each region and the Update stack fix when one is not covered', () => {
    render(<RegionCoverageList coverage={{ applicable: true, regions: [
      { region: 'ap-south-1', status: 'COVERED', checkedAt: '2026-11-09T00:00:00Z', lastEventAt: null },
      { region: 'us-east-1', status: 'UNKNOWN', checkedAt: '2026-11-09T00:00:00Z', lastEventAt: null },
    ] }} />);
    expect(screen.getByText('ap-south-1: Covered')).toBeInTheDocument();
    expect(screen.getByText('us-east-1: Unknown')).toBeInTheDocument();
    expect(screen.getByText(/Update stack to cover them/)).toBeInTheDocument();
  });

  it('is hidden for a read-only connection or before the first check', () => {
    const { container, rerender } = render(<RegionCoverageList coverage={{ applicable: false, regions: [] }} />);
    expect(container).toBeEmptyDOMElement();
    rerender(<RegionCoverageList coverage={undefined} />);
    expect(container).toBeEmptyDOMElement();
  });
});
