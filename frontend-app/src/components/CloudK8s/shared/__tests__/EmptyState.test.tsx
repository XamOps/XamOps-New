import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import EmptyState from '../EmptyState';

describe('EmptyState (FEAT-001)', () => {
  it('shows one icon, one sentence, and at most one button', async () => {
    const onClick = vi.fn();
    render(<EmptyState icon="mdi:cloud-off-outline" message="Nothing to show yet." action={{ label: 'Refresh', onClick }} />);
    expect(screen.getByText('Nothing to show yet.')).toBeInTheDocument();
    const buttons = screen.getAllByRole('button');
    expect(buttons).toHaveLength(1);
    await userEvent.click(buttons[0]);
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('renders no button when no action is given', () => {
    render(<EmptyState icon="mdi:cloud-off-outline" message="All done." />);
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('blurs a skeleton, never sample data', () => {
    const { container } = render(<EmptyState icon="mdi:cloud-off-outline" message="Loading soon." blurredBackdrop />);
    const skeleton = container.querySelector('[data-empty-skeleton]')!;
    expect(skeleton).toBeTruthy();
    expect(skeleton.className).toContain('blur-sm');
    // The skeleton is placeholder bars only — no text / numbers.
    expect((skeleton.textContent ?? '').trim()).toBe('');
  });
});
