import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import Pagination from '../components/FinOps/Pagination';
import React from 'react';

// Mock TooltipWrapper since it might depend on complex react-aria-components
vi.mock('../components/ui', () => ({
  TooltipWrapper: ({ children, tip }: { children: React.ReactNode; tip: string }) => (
    <div data-testid="tooltip-wrapper" data-tip={tip}>
      {children}
    </div>
  ),
}));

describe('Pagination Component', () => {
  const defaultProps = {
    currentPage: 2,
    totalPages: 5,
    totalItems: 50,
    itemsPerPage: 10,
    onPageChange: vi.fn(),
  };

  it('renders correctly and shows showing info', () => {
    render(<Pagination {...defaultProps} />);
    expect(screen.getByText(/Showing/)).toBeInTheDocument();
    expect(screen.getByText('11')).toBeInTheDocument(); // start: (2-1)*10 + 1
    expect(screen.getByText('20')).toBeInTheDocument(); // end: 2*10
    expect(screen.getByText('50')).toBeInTheDocument(); // total
    expect(screen.getByText('Page 2 of 5')).toBeInTheDocument();
  });

  it('has accessible labels and tooltips on all buttons', () => {
    render(<Pagination {...defaultProps} />);

    const firstBtn = screen.getByLabelText('Go to first page');
    const prevBtn = screen.getByLabelText('Go to previous page');
    const nextBtn = screen.getByLabelText('Go to next page');
    const lastBtn = screen.getByLabelText('Go to last page');

    expect(firstBtn).toBeInTheDocument();
    expect(prevBtn).toBeInTheDocument();
    expect(nextBtn).toBeInTheDocument();
    expect(lastBtn).toBeInTheDocument();

    const tooltips = screen.getAllByTestId('tooltip-wrapper');
    expect(tooltips).toHaveLength(4);
    expect(tooltips[0]).toHaveAttribute('data-tip', 'First page');
    expect(tooltips[1]).toHaveAttribute('data-tip', 'Previous page');
    expect(tooltips[2]).toHaveAttribute('data-tip', 'Next page');
    expect(tooltips[3]).toHaveAttribute('data-tip', 'Last page');
  });

  it('disables first and previous buttons on the first page', () => {
    render(<Pagination {...defaultProps} currentPage={1} />);
    expect(screen.getByLabelText('Go to first page')).toBeDisabled();
    expect(screen.getByLabelText('Go to previous page')).toBeDisabled();
    expect(screen.getByLabelText('Go to next page')).not.toBeDisabled();
    expect(screen.getByLabelText('Go to last page')).not.toBeDisabled();
  });

  it('disables next and last buttons on the last page', () => {
    render(<Pagination {...defaultProps} currentPage={5} />);
    expect(screen.getByLabelText('Go to first page')).not.toBeDisabled();
    expect(screen.getByLabelText('Go to previous page')).not.toBeDisabled();
    expect(screen.getByLabelText('Go to next page')).toBeDisabled();
    expect(screen.getByLabelText('Go to last page')).toBeDisabled();
  });
});
