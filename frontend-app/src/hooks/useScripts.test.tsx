/**
 * Unit tests for the DevOps-in-the-Box script hooks.
 *
 * The typed scriptApiClient is mocked so these tests focus on the hooks' own
 * logic: filter state management, parameter validation + conditional
 * visibility, page flattening / load-more, interaction dispatch + cache
 * invalidation, and the customization validate-then-generate flow.
 */

import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ScriptParameter } from '../types/devopsInTheBox';

// ── Mock the typed API client before importing the hooks under test ──────────
// vi.hoisted lets the mock object exist before the hoisted vi.mock factory runs.
const api = vi.hoisted(() => ({
  getAllScripts: vi.fn(),
  searchScripts: vi.fn(),
  getScriptById: vi.fn(),
  getScriptParameters: vi.fn(),
  customizeScript: vi.fn(),
  recordInteraction: vi.fn(),
  rateScript: vi.fn(),
  favoriteScript: vi.fn(),
  unfavoriteScript: vi.fn(),
}));

vi.mock('../api/scriptApiClient', () => ({
  scriptApiClient: api,
}));

import {
  DEFAULT_SCRIPT_FILTERS,
  buildDefaultInputs,
  isParameterVisible,
  useScriptActions,
  useScriptCustomization,
  useScriptFilters,
  useScripts,
  validateInputs,
  validateParameter,
} from './useScripts';

// ── Helpers ──────────────────────────────────────────────────────────────────
function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const wrapper = ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  return { wrapper, queryClient };
}

const param = (over: Partial<ScriptParameter>): ScriptParameter => ({
  id: 1,
  name: 'REGION',
  label: 'Region',
  description: '',
  parameterType: 'text',
  required: true,
  category: 'infrastructure',
  displayOrder: 0,
  ...over,
});

beforeEach(() => {
  Object.values(api).forEach(fn => fn.mockReset());
});

// ─────────────────────────────────────────────────────────────────────────────
describe('useScriptFilters', () => {
  it('initialises with defaults and merges initial overrides', () => {
    const { result } = renderHook(() => useScriptFilters({ sortBy: 'rating' }));
    expect(result.current.filters.searchQuery).toBe('');
    expect(result.current.filters.sortBy).toBe('rating');
  });

  it('updates the search query', () => {
    const { result } = renderHook(() => useScriptFilters());
    act(() => result.current.setSearchQuery('vpc'));
    expect(result.current.searchQuery).toBe('vpc');
  });

  it('updateFilter sets a single field', () => {
    const { result } = renderHook(() => useScriptFilters());
    act(() => result.current.updateFilter('minRating', 4));
    expect(result.current.filters.minRating).toBe(4);
  });

  it('toggleArrayFilter adds then removes a value', () => {
    const { result } = renderHook(() => useScriptFilters());
    act(() => result.current.toggleArrayFilter('categories', 'terraform'));
    expect(result.current.filters.categories).toEqual(['terraform']);
    act(() => result.current.toggleArrayFilter('categories', 'terraform'));
    expect(result.current.filters.categories).toEqual([]);
  });

  it('resetFilters restores defaults', () => {
    const { result } = renderHook(() => useScriptFilters());
    act(() => result.current.setSearchQuery('x'));
    act(() => result.current.resetFilters());
    expect(result.current.filters).toEqual(DEFAULT_SCRIPT_FILTERS);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
describe('parameter validation helpers', () => {
  it('flags required empty fields', () => {
    expect(validateParameter(param({ required: true }), '')).toMatch(/required/);
  });

  it('allows optional empty fields', () => {
    expect(validateParameter(param({ required: false }), '')).toBeNull();
  });

  it('enforces regex pattern on text', () => {
    const p = param({ validationRules: { pattern: '^[a-z]{2}-[a-z]+-\\d$' } });
    expect(validateParameter(p, 'bad value')).toMatch(/invalid format/);
    expect(validateParameter(p, 'us-east-1')).toBeNull();
  });

  it('enforces min/max on numbers', () => {
    const p = param({ parameterType: 'number', validationRules: { min: 1, max: 10 } });
    expect(validateParameter(p, 0)).toMatch(/at least/);
    expect(validateParameter(p, 11)).toMatch(/at most/);
    expect(validateParameter(p, 5)).toBeNull();
  });

  it('enforces select options', () => {
    const p = param({
      parameterType: 'select',
      validationRules: { options: [{ value: 'a', label: 'A' }] },
    });
    expect(validateParameter(p, 'z')).toMatch(/invalid selection/);
    expect(validateParameter(p, 'a')).toBeNull();
  });

  it('isParameterVisible respects dependsOn equality and truthiness', () => {
    const inputs = { ENABLE_HA: true, MODE: 'prod' };
    expect(isParameterVisible(param({ dependsOn: 'ENABLE_HA' }), inputs)).toBe(true);
    expect(isParameterVisible(param({ dependsOn: 'MODE=prod' }), inputs)).toBe(true);
    expect(isParameterVisible(param({ dependsOn: 'MODE=dev' }), inputs)).toBe(false);
    expect(isParameterVisible(param({ dependsOn: 'MISSING' }), inputs)).toBe(false);
  });

  it('validateInputs skips hidden parameters', () => {
    const params = [
      param({ name: 'MODE', parameterType: 'select', required: false }),
      param({ name: 'SECRET', required: true, dependsOn: 'MODE=prod' }),
    ];
    // SECRET is hidden because MODE !== prod, so no required error.
    expect(validateInputs(params, { MODE: 'dev', SECRET: '' })).toEqual([]);
    // SECRET becomes visible and required.
    expect(validateInputs(params, { MODE: 'prod', SECRET: '' })).toHaveLength(1);
  });

  it('buildDefaultInputs seeds type-appropriate empties', () => {
    const inputs = buildDefaultInputs([
      param({ name: 'A', parameterType: 'multiselect' }),
      param({ name: 'B', parameterType: 'boolean' }),
      param({ name: 'C', parameterType: 'text', defaultValue: 'x' }),
    ]);
    expect(inputs).toEqual({ A: [], B: false, C: 'x' });
  });
});

// ─────────────────────────────────────────────────────────────────────────────
describe('useScripts', () => {
  it('uses the list endpoint and flattens loaded pages', async () => {
    api.getAllScripts.mockResolvedValueOnce({
      items: [{ id: 1 }, { id: 2 }],
      page: 0,
      pageSize: 20,
      totalCount: 2,
      hasMore: true,
    });
    const { wrapper } = createWrapper();
    const { result } = renderHook(() => useScripts(DEFAULT_SCRIPT_FILTERS), { wrapper });

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(api.getAllScripts).toHaveBeenCalled();
    expect(result.current.scripts).toHaveLength(2);
    expect(result.current.hasMore).toBe(true);
  });

  it('uses the search endpoint when a query is present', async () => {
    api.searchScripts.mockResolvedValueOnce({
      items: [{ id: 9 }],
      totalHits: 1,
      page: 0,
      size: 20,
      totalPages: 1,
      tookMillis: 3,
    });
    const { wrapper } = createWrapper();
    const { result } = renderHook(
      () => useScripts({ ...DEFAULT_SCRIPT_FILTERS, searchQuery: 'vpc' }),
      { wrapper },
    );

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(api.searchScripts).toHaveBeenCalledWith('vpc', expect.any(Object), 0);
    expect(result.current.scripts).toEqual([{ id: 9 }]);
    expect(result.current.hasMore).toBe(false);
  });

  it('loadMore fetches and appends the next page', async () => {
    api.getAllScripts
      .mockResolvedValueOnce({ items: [{ id: 1 }], page: 0, pageSize: 20, totalCount: 2, hasMore: true })
      .mockResolvedValueOnce({ items: [{ id: 2 }], page: 1, pageSize: 20, totalCount: 2, hasMore: false });
    const { wrapper } = createWrapper();
    const { result } = renderHook(() => useScripts(DEFAULT_SCRIPT_FILTERS), { wrapper });

    await waitFor(() => expect(result.current.scripts).toHaveLength(1));
    act(() => result.current.loadMore());
    await waitFor(() => expect(result.current.scripts).toHaveLength(2));
    expect(result.current.hasMore).toBe(false);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
describe('useScriptActions', () => {
  it('favoriteScript and unfavorite route to the right client methods', async () => {
    api.favoriteScript.mockResolvedValue(undefined);
    api.unfavoriteScript.mockResolvedValue(undefined);
    const { wrapper } = createWrapper();
    const { result } = renderHook(() => useScriptActions(), { wrapper });

    await act(async () => {
      await result.current.favoriteScript(5);
    });
    expect(api.favoriteScript).toHaveBeenCalledWith(5);

    await act(async () => {
      await result.current.favoriteScript(5, false);
    });
    expect(api.unfavoriteScript).toHaveBeenCalledWith(5);
  });

  it('rateScript forwards rating and review', async () => {
    api.rateScript.mockResolvedValue(undefined);
    const { wrapper } = createWrapper();
    const { result } = renderHook(() => useScriptActions(), { wrapper });

    await act(async () => {
      await result.current.rateScript(7, 4, 'great');
    });
    expect(api.rateScript).toHaveBeenCalledWith(7, 4, 'great');
  });

  it('record helpers dispatch the matching interaction type', async () => {
    api.recordInteraction.mockResolvedValue(undefined);
    const { wrapper } = createWrapper();
    const { result } = renderHook(() => useScriptActions(), { wrapper });

    await act(async () => {
      await result.current.recordView(1);
      await result.current.recordDownload(1);
      await result.current.recordCopy(1);
    });
    expect(api.recordInteraction).toHaveBeenCalledWith(1, 'view');
    expect(api.recordInteraction).toHaveBeenCalledWith(1, 'download');
    expect(api.recordInteraction).toHaveBeenCalledWith(1, 'copy');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
describe('useScriptCustomization', () => {
  const params = [
    param({ id: 1, name: 'REGION', label: 'Region', required: true }),
    param({
      id: 2,
      name: 'COUNT',
      label: 'Count',
      parameterType: 'number',
      required: true,
      validationRules: { min: 1, max: 5 },
    }),
  ];

  it('seeds inputs from defaults and reports invalid when required fields empty', () => {
    const { wrapper } = createWrapper();
    const { result } = renderHook(() => useScriptCustomization(3, params), { wrapper });
    expect(result.current.inputs).toEqual({ REGION: '', COUNT: '' });
    expect(result.current.isValid).toBe(false);
  });

  it('generateScript blocks on validation errors and does not call the API', async () => {
    const { wrapper } = createWrapper();
    const { result } = renderHook(() => useScriptCustomization(3, params), { wrapper });

    let returned: unknown;
    await act(async () => {
      returned = await result.current.generateScript();
    });
    expect(returned).toBeNull();
    expect(api.customizeScript).not.toHaveBeenCalled();
    expect(result.current.errors.length).toBeGreaterThan(0);
  });

  it('generateScript calls the API once inputs are valid', async () => {
    api.customizeScript.mockResolvedValue({
      scriptId: 3,
      scriptName: 'X',
      customizedCode: 'echo us-east-1',
      parameterValues: { REGION: 'us-east-1', COUNT: 2 },
      generatedAt: '2024-01-01T00:00:00Z',
    });
    const { wrapper } = createWrapper();
    const { result } = renderHook(() => useScriptCustomization(3, params), { wrapper });

    act(() => {
      result.current.setInput('REGION', 'us-east-1');
      result.current.setInput('COUNT', 2);
    });
    await waitFor(() => expect(result.current.isValid).toBe(true));

    let returned: any;
    await act(async () => {
      returned = await result.current.generateScript();
    });
    expect(api.customizeScript).toHaveBeenCalledWith(3, { REGION: 'us-east-1', COUNT: 2 });
    expect(returned?.customizedCode).toBe('echo us-east-1');
    expect(result.current.customizedScript?.customizedCode).toBe('echo us-east-1');
  });
});
