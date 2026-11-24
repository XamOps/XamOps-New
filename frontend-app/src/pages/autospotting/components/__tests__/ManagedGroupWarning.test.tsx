import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import ManagedGroupWarning from '../ManagedGroupWarning';

describe('ManagedGroupWarning', () => {
  it('shows the state badge, a Revert first link and the acknowledgment', () => {
    const onChange = vi.fn();
    render(<ManagedGroupWarning state="ACTIVE" busy={false} acknowledged={false} onAcknowledgedChange={onChange} />);
    expect(screen.getByText('Active')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Revert first' })).toHaveAttribute('href', '/autospotting');
    fireEvent.click(screen.getByRole('checkbox'));
    expect(onChange).toHaveBeenCalledWith(true);
  });
  it('offers no acknowledgment while the group is being changed', () => {
    render(<ManagedGroupWarning state="APPLYING" busy acknowledged={false} onAcknowledgedChange={() => {}} />);
    expect(screen.queryByRole('checkbox')).toBeNull();
    expect(screen.getByText(/Try again when it finishes/)).toBeInTheDocument();
  });
});
