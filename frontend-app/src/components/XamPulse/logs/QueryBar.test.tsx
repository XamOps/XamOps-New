import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { QueryBar } from './QueryBar';

afterEach(cleanup);

describe('QueryBar plain-English mode', () => {
  it('shows the query it will run before running it, then runs it and sets the range', () => {
    const onSubmit = vi.fn();
    const onRange = vi.fn();
    render(<QueryBar value="" onSubmit={onSubmit} onRange={onRange} />);
    fireEvent.click(screen.getByRole('button', { name: /Plain English/ }));
    const input = screen.getByLabelText('Describe the logs you want');
    fireEvent.change(input, { target: { value: 'errors from api in the last 2 hours containing timeout' } });
    expect(screen.getByText('severity:>=error service:api "timeout"')).toBeInTheDocument();
    expect(screen.getByText(/read as: errors and worse, service api, last 2 hours \(shown: 6h\)/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    expect(onRange).toHaveBeenCalledWith('6h');
    expect(onSubmit).toHaveBeenCalledWith('severity:>=error service:api "timeout"');
  });

  it('can hand the built query over for editing in the query language', () => {
    render(<QueryBar value="" onSubmit={() => undefined} />);
    fireEvent.click(screen.getByRole('button', { name: /Plain English/ }));
    fireEvent.change(screen.getByLabelText('Describe the logs you want'), { target: { value: 'warnings on i-0abc12345' } });
    fireEvent.click(screen.getByRole('button', { name: 'Edit as query' }));
    expect(screen.getByLabelText('Log query')).toHaveValue('severity:>=warn instance:i-0abc12345');
    expect(screen.getByRole('button', { name: /Plain English/ })).toHaveAttribute('aria-pressed', 'false');
  });
});
