/**
 * Unit tests for the ScriptGrid component.
 *
 * Uses Vitest + React Testing Library. Covers card rendering, loading
 * skeletons, empty state + reset action, infinite-scroll "Load More" fallback,
 * paged controls (prev/next/page numbers), the page-item windowing helper, and
 * the scroll-to-top button.
 *
 * Requirements: REQ-2.3.4, REQ-2.5.1, REQ-2.5.4, REQ-2.6.2
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import ScriptGrid, { buildPageItems } from './ScriptGrid';
import type { DevOpsScript, PaginationState } from '../../types/devopsInTheBox';

function makeScript(overrides: Partial<DevOpsScript> = {}): DevOpsScript {
  return {
    id: 1,
    name: 'Provision AWS VPC',
    description: 'Creates a VPC with public and private subnets.',
    category: 'terraform',
    code: 'resource "aws_vpc" "main" {}',
    usageInstructions: 'Run terraform apply',
    version: '1.0.0',
    tags: ['vpc'],
    cloudProviders: ['aws'],
    difficulty: 'intermediate',
    author: 'XamOps Team',
    authorId: 'team',
    estimatedTimeMinutes: 10,
    prerequisites: 'AWS account',
    relatedDocumentationUrl: '',
    viewCount: 100,
    downloadCount: 1234,
    copyCount: 50,
    favoriteCount: 12,
    averageRating: 4.5,
    ratingCount: 20,
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-02T00:00:00Z',
    lastUsedAt: '2024-01-03T00:00:00Z',
    status: 'published',
    isUserContributed: false,
    isFeatured: false,
    usage: 'Run terraform apply',
    ...overrides,
  };
}

function makeScripts(n: number): DevOpsScript[] {
  return Array.from({ length: n }, (_, i) =>
    makeScript({ id: i + 1, name: `Script ${i + 1}` })
  );
}

describe('buildPageItems', () => {
  it('returns empty list when there are no pages', () => {
    expect(buildPageItems(1, 0)).toEqual([]);
  });

  it('returns a single page when there is only one', () => {
    expect(buildPageItems(1, 1)).toEqual([1]);
  });

  it('lists all pages without gaps for small totals', () => {
    expect(buildPageItems(2, 4)).toEqual([1, 2, 3, 4]);
  });

  it('inserts ellipsis gaps around the current page for large totals', () => {
    expect(buildPageItems(5, 10)).toEqual([1, '…', 4, 5, 6, '…', 10]);
  });

  it('keeps the first pages contiguous when current is near the start', () => {
    expect(buildPageItems(1, 10)).toEqual([1, 2, '…', 10]);
  });
});

describe('ScriptGrid', () => {
  const onScriptClick = vi.fn();
  const onToggleFavorite = vi.fn();
  const onLoadMore = vi.fn();
  const onResetFilters = vi.fn();
  const onPageChange = vi.fn();

  beforeEach(() => {
    onScriptClick.mockReset();
    onToggleFavorite.mockReset();
    onLoadMore.mockReset();
    onResetFilters.mockReset();
    onPageChange.mockReset();
    // jsdom lacks IntersectionObserver; provide a no-op stub so effects run.
    (globalThis as any).IntersectionObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
      takeRecords() {
        return [];
      }
    };
  });

  afterEach(() => {
    delete (globalThis as any).IntersectionObserver;
  });

  it('renders a ScriptCard for each script', () => {
    render(
      <ScriptGrid
        scripts={makeScripts(3)}
        onScriptClick={onScriptClick}
        onToggleFavorite={onToggleFavorite}
      />
    );
    expect(screen.getByText('Script 1')).toBeInTheDocument();
    expect(screen.getByText('Script 2')).toBeInTheDocument();
    expect(screen.getByText('Script 3')).toBeInTheDocument();
  });

  it('shows skeletons during the initial load', () => {
    render(
      <ScriptGrid
        scripts={[]}
        loading
        onScriptClick={onScriptClick}
        onToggleFavorite={onToggleFavorite}
      />
    );
    expect(screen.getByTestId('script-grid-skeletons')).toBeInTheDocument();
  });

  it('shows the empty state with a reset button when there are no results', () => {
    render(
      <ScriptGrid
        scripts={[]}
        loading={false}
        onScriptClick={onScriptClick}
        onToggleFavorite={onToggleFavorite}
        onResetFilters={onResetFilters}
      />
    );
    expect(screen.getByText('No scripts found')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /reset filters/i }));
    expect(onResetFilters).toHaveBeenCalledTimes(1);
  });

  it('renders a Load More button in infinite mode and triggers onLoadMore', () => {
    render(
      <ScriptGrid
        scripts={makeScripts(3)}
        hasMore
        onLoadMore={onLoadMore}
        onScriptClick={onScriptClick}
        onToggleFavorite={onToggleFavorite}
      />
    );
    const btn = screen.getByRole('button', { name: /load more/i });
    fireEvent.click(btn);
    expect(onLoadMore).toHaveBeenCalledTimes(1);
  });

  it('does not render Load More when there are no more results', () => {
    render(
      <ScriptGrid
        scripts={makeScripts(3)}
        hasMore={false}
        onScriptClick={onScriptClick}
        onToggleFavorite={onToggleFavorite}
      />
    );
    expect(screen.queryByRole('button', { name: /load more/i })).toBeNull();
  });

  it('renders paged controls and calls onPageChange on Next', () => {
    const pagination: PaginationState = { page: 2, pageSize: 10, totalCount: 50, hasMore: true };
    render(
      <ScriptGrid
        scripts={makeScripts(10)}
        paginationMode="paged"
        pagination={pagination}
        onPageChange={onPageChange}
        onScriptClick={onScriptClick}
        onToggleFavorite={onToggleFavorite}
      />
    );
    fireEvent.click(screen.getByRole('button', { name: /next page/i }));
    expect(onPageChange).toHaveBeenCalledWith(3);

    fireEvent.click(screen.getByRole('button', { name: /page 1/i }));
    expect(onPageChange).toHaveBeenCalledWith(1);
  });

  it('disables Prev on the first page', () => {
    const pagination: PaginationState = { page: 1, pageSize: 10, totalCount: 50, hasMore: true };
    render(
      <ScriptGrid
        scripts={makeScripts(10)}
        paginationMode="paged"
        pagination={pagination}
        onPageChange={onPageChange}
        onScriptClick={onScriptClick}
        onToggleFavorite={onToggleFavorite}
      />
    );
    expect(screen.getByRole('button', { name: /previous page/i })).toBeDisabled();
  });

  it('shows the scroll-to-top button after scrolling down', () => {
    render(
      <ScriptGrid
        scripts={makeScripts(3)}
        onScriptClick={onScriptClick}
        onToggleFavorite={onToggleFavorite}
      />
    );
    expect(screen.queryByRole('button', { name: /scroll to top/i })).toBeNull();

    act(() => {
      Object.defineProperty(window, 'scrollY', { value: 800, configurable: true });
      window.dispatchEvent(new Event('scroll'));
    });

    const topBtn = screen.getByRole('button', { name: /scroll to top/i });
    expect(topBtn).toBeInTheDocument();

    const scrollToSpy = vi.fn();
    window.scrollTo = scrollToSpy as unknown as typeof window.scrollTo;
    fireEvent.click(topBtn);
    expect(scrollToSpy).toHaveBeenCalled();
  });
});
