import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import ConfidenceChip from '../ConfidenceChip';
import type { ConfidenceView } from '../../../../../types/verticalRightsizer';

const confidence = (over: Partial<ConfidenceView> = {}): ConfidenceView => ({
  score: 0.82,
  band: 'HIGH',
  sampleCoverage: 0.96,
  windowHours: 24,
  belowFloor: false,
  ...over,
});

describe('ConfidenceChip (FEAT-001)', () => {
  it('shows the score, the band word and the why line', () => {
    render(<ConfidenceChip confidence={confidence()} />);
    expect(screen.getByText('0.82 · High')).toBeInTheDocument();
    expect(screen.getByText('96% window coverage over 24h')).toBeInTheDocument();
  });

  it('maps HIGH to the good tone, MEDIUM to info, LOW to warn — existing tokens only', () => {
    const { container, rerender } = render(<ConfidenceChip confidence={confidence({ band: 'HIGH' })} />);
    expect(container.querySelector('[data-tone="good"]')).not.toBeNull();

    rerender(<ConfidenceChip confidence={confidence({ band: 'MEDIUM' })} />);
    expect(container.querySelector('[data-tone="info"]')).not.toBeNull();

    rerender(<ConfidenceChip confidence={confidence({ band: 'LOW' })} />);
    expect(container.querySelector('[data-tone="warn"]')).not.toBeNull();
  });

  it('drops the why sub-line when compact', () => {
    render(<ConfidenceChip confidence={confidence()} compact />);
    expect(screen.getByText('0.82 · High')).toBeInTheDocument();
    expect(screen.queryByText('96% window coverage over 24h')).toBeNull();
  });
});
