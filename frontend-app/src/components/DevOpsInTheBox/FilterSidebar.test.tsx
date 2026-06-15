/**
 * Unit tests for the FilterSidebar component and its URL-persistence helpers.
 *
 * Uses Vitest + React Testing Library. The component is controlled, so we render
 * it with a fixed `filters` prop and assert on the `onChange` payloads it emits.
 *
 * Requirements: REQ-2.3.2, REQ-2.3.3, REQ-2.4.2
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import FilterSidebar, {
  EMPTY_FILTERS,
  countActiveFilters,
  filtersToSearchParams,
  searchParamsToFilters,
} from './FilterSidebar';
import { ScriptFilters } from '../../types/devopsInTheBox';

function makeFilters(overrides: Partial<ScriptFilters> = {}): ScriptFilters {
  return { ...EMPTY_FILTERS, ...overrides };
}

describe('countActiveFilters', () => {
  it('returns 0 for empty filters', () => {
    expect(countActiveFilters(EMPTY_FILTERS)).toBe(0);
  });

  it('counts each active facet (search query and sort excluded)', () => {
    const filters = makeFilters({
      searchQuery: 'aws',
      categories: ['terraform', 'bash'],
      cloudProviders: ['aws'],
      difficulties: ['beginner'],
      tags: ['vpc', 'ec2'],
      minRating: 4,
      showOnlyFavorites: true,
      sortBy: 'name',
    });
    // 2 categories + 1 provider + 1 difficulty + 2 tags + minRating + favorites = 8
    expect(countActiveFilters(filters)).toBe(8);
  });

  it('does not count minRating of 0', () => {
    expect(countActiveFilters(makeFilters({ minRating: 0 }))).toBe(0);
  });
});

describe('URL serialisation round-trip', () => {
  it('serialises and parses back to the same filters', () => {
    const filters = makeFilters({
      searchQuery: 'kubernetes',
      categories: ['python', 'ansible'],
      cloudProviders: ['gcp', 'multi-cloud'],
      difficulties: ['advanced'],
      tags: ['monitoring', 'security'],
      minRating: 3,
      sortBy: 'rating',
      showOnlyFavorites: true,
    });
    const params = filtersToSearchParams(filters);
    expect(searchParamsToFilters(params)).toEqual(filters);
  });

  it('omits default/empty facets from the query string', () => {
    const params = filtersToSearchParams(EMPTY_FILTERS);
    expect(params.toString()).toBe('');
  });

  it('drops unknown / malformed values when parsing', () => {
    const params = new URLSearchParams(
      'categories=terraform,bogus&minRating=99&sort=nonsense&providers=aws'
    );
    const result = searchParamsToFilters(params);
    expect(result.categories).toEqual(['terraform']);
    expect(result.cloudProviders).toEqual(['aws']);
    expect(result.minRating).toBeUndefined();
    expect(result.sortBy).toBe('recent');
  });
});

describe('FilterSidebar', () => {
  const onChange = vi.fn();

  beforeEach(() => {
    onChange.mockReset();
  });

  it('renders the filter heading and sort dropdown', () => {
    render(<FilterSidebar filters={EMPTY_FILTERS} onChange={onChange} />);
    expect(screen.getByRole('heading', { name: /filters/i })).toBeTruthy();
    expect(screen.getByLabelText(/sort by/i)).toBeTruthy();
  });

  it('toggles a category checkbox and emits updated filters', () => {
    render(<FilterSidebar filters={EMPTY_FILTERS} onChange={onChange} />);
    fireEvent.click(screen.getByText('Terraform'));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ categories: ['terraform'] })
    );
  });

  it('toggles a cloud provider checkbox', () => {
    render(<FilterSidebar filters={EMPTY_FILTERS} onChange={onChange} />);
    fireEvent.click(screen.getByText('Azure'));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ cloudProviders: ['azure'] })
    );
  });

  it('removes an already-selected difficulty when toggled off', () => {
    render(
      <FilterSidebar filters={makeFilters({ difficulties: ['beginner'] })} onChange={onChange} />
    );
    fireEvent.click(screen.getByText('Beginner'));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ difficulties: [] })
    );
  });

  it('changes the sort order', () => {
    render(<FilterSidebar filters={EMPTY_FILTERS} onChange={onChange} />);
    fireEvent.change(screen.getByLabelText(/sort by/i), { target: { value: 'name' } });
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ sortBy: 'name' }));
  });

  it('toggles the "Show Only Favorites" switch', () => {
    render(<FilterSidebar filters={EMPTY_FILTERS} onChange={onChange} />);
    fireEvent.click(screen.getByRole('switch', { name: /show only favorites/i }));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ showOnlyFavorites: true })
    );
  });

  it('sets a minimum rating from the star selector', () => {
    render(<FilterSidebar filters={EMPTY_FILTERS} onChange={onChange} />);
    // The Minimum Rating section is collapsed by default — expand it first.
    fireEvent.click(screen.getByRole('button', { name: /minimum rating/i }));
    fireEvent.click(screen.getByRole('button', { name: /minimum 4 stars/i }));
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ minRating: 4 }));
  });

  it('selects an available tag', () => {
    render(
      <FilterSidebar filters={EMPTY_FILTERS} onChange={onChange} availableTags={['vpc', 'ec2']} />
    );
    fireEvent.click(screen.getByRole('button', { name: 'vpc' }));
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ tags: ['vpc'] }));
  });

  it('filters tags via the tag search box', () => {
    render(
      <FilterSidebar
        filters={EMPTY_FILTERS}
        onChange={onChange}
        availableTags={['vpc', 'monitoring', 'security']}
      />
    );
    fireEvent.change(screen.getByLabelText(/search tags/i), { target: { value: 'mon' } });
    expect(screen.getByRole('button', { name: 'monitoring' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'vpc' })).toBeNull();
  });

  it('shows an active filter count badge', () => {
    render(
      <FilterSidebar
        filters={makeFilters({ categories: ['terraform'], cloudProviders: ['aws'] })}
        onChange={onChange}
      />
    );
    expect(screen.getByLabelText(/2 active filters/i)).toBeTruthy();
  });

  it('resets all facets while preserving the search query and sort order', () => {
    render(
      <FilterSidebar
        filters={makeFilters({
          searchQuery: 'aws',
          categories: ['terraform'],
          tags: ['vpc'],
          sortBy: 'name',
        })}
        onChange={onChange}
      />
    );
    fireEvent.click(screen.getByRole('button', { name: /reset all/i }));
    expect(onChange).toHaveBeenCalledWith({
      ...EMPTY_FILTERS,
      searchQuery: 'aws',
      sortBy: 'name',
    });
  });
});
