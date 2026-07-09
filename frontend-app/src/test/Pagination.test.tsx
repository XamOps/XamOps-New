import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import React from 'react';
import Pagination from '../components/FinOps/Pagination';

// Mock TooltipWrapper as it uses react-aria-components which might be tricky in jsdom without full setup
vi.mock('../components/ui', () => ({
  TooltipWrapper: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

describe('Pagination Component', () => {
  const defaultProps = {
    currentPage: 2,
    totalPages: 5,
    totalItems: 50,
    itemsPerPage: 10,
    onPageChange: vi.fn(),
  };

  it('renders correctly and shows progress', () => {
    render(<Pagination {...defaultProps} />);
    expect(screen.getByText(/Showing/)).toBeDefined();
    expect(screen.getByText('11')).toBeDefined();
    expect(screen.getByText('20')).toBeDefined();
    expect(screen.getByText('50')).toBeDefined();
    expect(screen.getByText(/Page 2 of 5/)).toBeDefined();
  });

  it('has accessibility attributes on buttons', () => {
    render(<Pagination {...defaultProps} />);

    const firstPageBtn = screen.getByLabelText('First Page');
    const prevPageBtn = screen.getByLabelText('Previous Page');
    const nextPageBtn = screen.getByLabelText('Next Page');
    const lastPageBtn = screen.getByLabelText('Last Page');

    expect(firstPageBtn.getAttribute('type')).toBe('button');
    expect(prevPageBtn.getAttribute('type')).toBe('button');
    expect(nextPageBtn.getAttribute('type')).toBe('button');
    expect(lastPageBtn.getAttribute('type')).toBe('button');
  });

  it('disables buttons correctly on first page', () => {
    render(<Pagination {...defaultProps} currentPage={1} />);

    const firstPageBtn = screen.getByLabelText('First Page');
    const prevPageBtn = screen.getByLabelText('Previous Page');

    expect(firstPageBtn.hasAttribute('disabled')).toBe(true);
    expect(prevPageBtn.hasAttribute('disabled')).toBe(true);
  });

  it('disables buttons correctly on last page', () => {
    render(<Pagination {...defaultProps} currentPage={5} />);

    const nextPageBtn = screen.getByLabelText('Next Page');
    const lastPageBtn = screen.getByLabelText('Last Page');

    expect(nextPageBtn.hasAttribute('disabled')).toBe(true);
    expect(lastPageBtn.hasAttribute('disabled')).toBe(true);
  });
});
