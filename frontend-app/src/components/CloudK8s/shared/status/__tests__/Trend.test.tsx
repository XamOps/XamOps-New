import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import Trend from '../Trend';

describe('Trend (FEAT-001)', () => {
  it('renders nothing when prior is null (no fabricated trend)', () => {
    const { container } = render(<Trend current={120} prior={null} />);
    expect(container.firstChild).toBeNull();
  });

  it('shows an up arrow and percent when the value rose', () => {
    render(<Trend current={120} prior={100} />);
    const root = screen.getByText('20%').closest('[data-trend]')!;
    expect(root).toHaveAttribute('data-trend', 'up');
    expect(root.querySelector('svg[aria-hidden="true"]')).toBeTruthy();
  });

  it('tones by meaning: a rise is good when up is the good direction, warn otherwise', () => {
    const { rerender } = render(<Trend current={120} prior={100} goodDirection="up" />);
    expect(screen.getByText('20%').closest('[data-trend]')!.className).toContain('text-status-good');

    rerender(<Trend current={120} prior={100} goodDirection="down" />);
    expect(screen.getByText('20%').closest('[data-trend]')!.className).toContain('text-status-warn');
  });

  it('is flat and muted when unchanged', () => {
    render(<Trend current={100} prior={100} />);
    const root = screen.getByText('0%').closest('[data-trend]')!;
    expect(root).toHaveAttribute('data-trend', 'flat');
    expect(root.className).toContain('text-status-off');
  });
});
