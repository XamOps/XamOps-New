import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import CellDelta from '../CellDelta';

describe('CellDelta (FEAT-001)', () => {
  it('always carries its arrow icon and tabular-nums', () => {
    render(<CellDelta value={12} />);
    const root = screen.getByText('12').closest('[data-cell-delta]')!;
    expect(root).toHaveAttribute('data-cell-delta', 'up');
    expect(root.querySelector('svg[aria-hidden="true"]')).toBeTruthy();
    expect(root.className).toContain('tabular-nums');
  });

  it('is chrome ink by default and tones only when asked', () => {
    const { rerender } = render(<CellDelta value={-3} />);
    let root = screen.getByText('3').closest('[data-cell-delta]')!;
    expect(root).toHaveAttribute('data-cell-delta', 'down');
    expect(root.className).toContain('var(--chrome-ink)');

    rerender(<CellDelta value={-3} tone="warn" />);
    root = screen.getByText('3').closest('[data-cell-delta]')!;
    expect(root.className).toContain('text-status-warn');
  });

  it('uses the formatted magnitude when provided', () => {
    render(<CellDelta value={-512} formatted="512 MiB" />);
    expect(screen.getByText('512 MiB')).toBeInTheDocument();
  });
});
