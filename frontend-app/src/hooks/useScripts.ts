/**
 * React hooks for the DevOps-in-the-Box script library (Phase 1 state layer).
 *
 * These hooks sit on top of the typed {@link scriptApiClient} and use React
 * Query (@tanstack/react-query — already configured app-wide in
 * src/lib/queryClient.ts and provided in App.tsx) for data fetching, caching,
 * de-duplication and automatic refetching. They provide the state-management
 * surface consumed by the Script Explorer UI components:
 *
 *   useScripts            list / search with pagination, loading & error state
 *   useScriptDetails      single script fetch with caching
 *   useScriptFilters      local searchQuery + filter state with update helpers
 *   useScriptActions      favorite, rate, and record view/download/copy
 *   useScriptParameters   customization parameters with loading state
 *   useScriptCustomization form inputs, validation, and generate-script flow
 *
 * Requirements: REQ-2.1.1, REQ-2.2.2, REQ-2.3.1, REQ-2.4.1, REQ-2.4.2, REQ-2.6.2
 */

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { scriptApiClient } from '../api/scriptApiClient';
import type {
  CustomizedScript,
  DevOpsScript,
  InteractionType,
  PaginationState,
  ParameterValidationError,
  ScriptFilters,
  ScriptParameter,
} from '../types/devopsInTheBox';

// ---------------------------------------------------------------------------
// Query keys — centralised so any component / hook can invalidate them
// ---------------------------------------------------------------------------
export const scriptKeys = {
  all: ['scripts'] as const,
  lists: () => [...scriptKeys.all, 'list'] as const,
  list: (filters: ScriptFilters) => [...scriptKeys.lists(), filters] as const,
  details: () => [...scriptKeys.all, 'detail'] as const,
  detail: (id: number) => [...scriptKeys.details(), id] as const,
  trending: (limit: number) => [...scriptKeys.all, 'trending', limit] as const,
  parameters: (id: number) => [...scriptKeys.all, 'parameters', id] as const,
  customizations: () => [...scriptKeys.all, 'customizations'] as const,
};

/** Default (empty) filter state — newest-first, no filters applied. */
export const DEFAULT_SCRIPT_FILTERS: ScriptFilters = {
  searchQuery: '',
  categories: [],
  cloudProviders: [],
  difficulties: [],
  tags: [],
  sortBy: 'recent',
  showOnlyFavorites: false,
};

const DEFAULT_PAGE_SIZE = 20;

// ===========================================================================
// useScriptFilters — local search + filter state (no network)
// ===========================================================================

/** Array-valued (multi-select) filter keys that support toggling. */
type ArrayFilterKey = 'categories' | 'cloudProviders' | 'difficulties' | 'tags';

export interface UseScriptFiltersReturn {
  /** Convenience accessor for the free-text search term. */
  searchQuery: string;
  /** The full filter + sort state object. */
  filters: ScriptFilters;
  /** Update the free-text search term. */
  setSearchQuery: (query: string) => void;
  /** Set a single filter field by key. */
  updateFilter: <K extends keyof ScriptFilters>(key: K, value: ScriptFilters[K]) => void;
  /** Add/remove a single value from a multi-select array filter. */
  toggleArrayFilter: (key: ArrayFilterKey, value: string) => void;
  /** Replace the entire filter state. */
  setFilters: (filters: ScriptFilters) => void;
  /** Reset all filters back to {@link DEFAULT_SCRIPT_FILTERS}. */
  resetFilters: () => void;
}

/**
 * Manage the Script Explorer's search query and filter/sort state. Pure local
 * state — combine with {@link useScripts} to drive the actual fetch.
 */
export function useScriptFilters(initial?: Partial<ScriptFilters>): UseScriptFiltersReturn {
  const [filters, setFiltersState] = useState<ScriptFilters>({
    ...DEFAULT_SCRIPT_FILTERS,
    ...initial,
  });

  const setSearchQuery = useCallback((query: string) => {
    setFiltersState(prev => ({ ...prev, searchQuery: query }));
  }, []);

  const updateFilter = useCallback(
    <K extends keyof ScriptFilters>(key: K, value: ScriptFilters[K]) => {
      setFiltersState(prev => ({ ...prev, [key]: value }));
    },
    [],
  );

  const toggleArrayFilter = useCallback((key: ArrayFilterKey, value: string) => {
    setFiltersState(prev => {
      const current = (prev[key] as string[]) ?? [];
      const next = current.includes(value)
        ? current.filter(v => v !== value)
        : [...current, value];
      return { ...prev, [key]: next };
    });
  }, []);

  const setFilters = useCallback((next: ScriptFilters) => {
    setFiltersState(next);
  }, []);

  const resetFilters = useCallback(() => {
    setFiltersState({ ...DEFAULT_SCRIPT_FILTERS });
  }, []);

  return {
    searchQuery: filters.searchQuery,
    filters,
    setSearchQuery,
    updateFilter,
    toggleArrayFilter,
    setFilters,
    resetFilters,
  };
}

// ===========================================================================
// useScripts — paginated list / search
// ===========================================================================

interface ScriptsPage {
  items: DevOpsScript[];
  page: number;
  hasMore: boolean;
  totalCount: number;
}

export interface UseScriptsReturn {
  /** All scripts loaded so far (flattened across pages). */
  scripts: DevOpsScript[];
  /** Pagination metadata reflecting the most recent page loaded. */
  pagination: PaginationState;
  /** True on the very first load (no cached data yet). */
  isLoading: boolean;
  /** True whenever a request is in-flight (initial load or "load more"). */
  isFetching: boolean;
  /** True while a subsequent page is being fetched. */
  isFetchingMore: boolean;
  /** Whether another page is available to load. */
  hasMore: boolean;
  /** Error message, or null when the request succeeded. */
  error: string | null;
  /** Load the next page of results (infinite scroll / "Load More"). */
  loadMore: () => void;
  /** Force a refetch of the current query. */
  refetch: () => void;
}

/**
 * Fetch scripts for the supplied filters with pagination support. When a
 * search query is present the full-text search endpoint is used; otherwise the
 * filtered list endpoint is used. Pages are accumulated for infinite-scroll
 * style loading; `pagination`/`hasMore` expose progress for pagination controls.
 */
export function useScripts(
  filters: ScriptFilters,
  options?: { enabled?: boolean; pageSize?: number },
): UseScriptsReturn {
  const pageSize = options?.pageSize ?? DEFAULT_PAGE_SIZE;

  const query = useInfiniteQuery<ScriptsPage, Error>({
    queryKey: scriptKeys.list(filters),
    initialPageParam: 0,
    queryFn: async ({ pageParam }) => {
      const page = pageParam as number;
      const term = filters.searchQuery?.trim();
      if (term) {
        const result = await scriptApiClient.searchScripts(term, filters, page);
        const totalPages = result.totalPages ?? 0;
        return {
          items: result.items ?? [],
          page: result.page ?? page,
          hasMore: (result.page ?? page) + 1 < totalPages,
          totalCount: result.totalHits ?? 0,
        };
      }
      const result = await scriptApiClient.getAllScripts(filters, page);
      return {
        items: result.items ?? [],
        page: result.page ?? page,
        hasMore: result.hasMore ?? false,
        totalCount: result.totalCount ?? 0,
      };
    },
    getNextPageParam: lastPage => (lastPage.hasMore ? lastPage.page + 1 : undefined),
    enabled: options?.enabled ?? true,
  });

  const scripts = useMemo(
    () => query.data?.pages.flatMap(p => p.items) ?? [],
    [query.data],
  );

  const lastPage = query.data?.pages[query.data.pages.length - 1];
  const pagination: PaginationState = {
    page: lastPage?.page ?? 0,
    pageSize,
    totalCount: lastPage?.totalCount ?? 0,
    hasMore: Boolean(query.hasNextPage),
  };

  const loadMore = useCallback(() => {
    if (query.hasNextPage && !query.isFetchingNextPage) {
      query.fetchNextPage();
    }
  }, [query]);

  return {
    scripts,
    pagination,
    isLoading: query.isLoading,
    isFetching: query.isFetching,
    isFetchingMore: query.isFetchingNextPage,
    hasMore: Boolean(query.hasNextPage),
    error: query.error ? query.error.message : null,
    loadMore,
    refetch: () => {
      query.refetch();
    },
  };
}

// ===========================================================================
// useScriptDetails — single script fetch with caching
// ===========================================================================

export interface UseScriptDetailsReturn {
  script: DevOpsScript | null;
  isLoading: boolean;
  isFetching: boolean;
  error: string | null;
  refetch: () => void;
}

/**
 * Fetch a single script by id. Results are cached by React Query (keyed on the
 * script id) so re-opening the same script renders instantly. The query is
 * disabled until a valid id is supplied.
 */
export function useScriptDetails(
  scriptId: number | null | undefined,
  options?: { enabled?: boolean },
): UseScriptDetailsReturn {
  const isValidId = typeof scriptId === 'number' && scriptId > 0;

  const query = useQuery<DevOpsScript, Error>({
    queryKey: scriptKeys.detail(scriptId as number),
    queryFn: () => scriptApiClient.getScriptById(scriptId as number),
    enabled: (options?.enabled ?? true) && isValidId,
  });

  return {
    script: query.data ?? null,
    isLoading: query.isLoading,
    isFetching: query.isFetching,
    error: query.error ? query.error.message : null,
    refetch: () => {
      query.refetch();
    },
  };
}

// ===========================================================================
// useScriptActions — favorite, rate, and interaction tracking
// ===========================================================================

export interface UseScriptActionsReturn {
  /** Favorite (default) or unfavorite a script. */
  favoriteScript: (scriptId: number, favorite?: boolean) => Promise<void>;
  /** Create or update the caller's 1-5 rating (with optional review). */
  rateScript: (scriptId: number, rating: number, review?: string) => Promise<void>;
  /** Record a view interaction (fire-and-forget). */
  recordView: (scriptId: number) => Promise<void>;
  /** Record a download interaction and refresh the script's counters. */
  recordDownload: (scriptId: number) => Promise<void>;
  /** Record a copy interaction and refresh the script's counters. */
  recordCopy: (scriptId: number) => Promise<void>;
  isFavoriting: boolean;
  isRating: boolean;
}

/**
 * User-interaction actions for scripts. Mutations invalidate the affected
 * script detail (and the lists) so metric counters / favorite state stay in
 * sync with the backend. `recordView` is intentionally not invalidated — views
 * fire on every detail open and a refetch loop is undesirable.
 */
export function useScriptActions(): UseScriptActionsReturn {
  const queryClient = useQueryClient();

  const invalidateScript = useCallback(
    (scriptId: number) => {
      queryClient.invalidateQueries({ queryKey: scriptKeys.detail(scriptId) });
      queryClient.invalidateQueries({ queryKey: scriptKeys.lists() });
    },
    [queryClient],
  );

  const favoriteMutation = useMutation({
    mutationFn: ({ scriptId, favorite }: { scriptId: number; favorite: boolean }) =>
      favorite
        ? scriptApiClient.favoriteScript(scriptId)
        : scriptApiClient.unfavoriteScript(scriptId),
    onSuccess: (_data, variables) => invalidateScript(variables.scriptId),
  });

  const rateMutation = useMutation({
    mutationFn: ({
      scriptId,
      rating,
      review,
    }: {
      scriptId: number;
      rating: number;
      review?: string;
    }) => scriptApiClient.rateScript(scriptId, rating, review ?? ''),
    onSuccess: (_data, variables) => invalidateScript(variables.scriptId),
  });

  const recordInteraction = useCallback(
    (scriptId: number, type: InteractionType) =>
      scriptApiClient.recordInteraction(scriptId, type),
    [],
  );

  const favoriteScript = useCallback(
    (scriptId: number, favorite = true) =>
      favoriteMutation.mutateAsync({ scriptId, favorite }),
    [favoriteMutation],
  );

  const rateScript = useCallback(
    (scriptId: number, rating: number, review?: string) =>
      rateMutation.mutateAsync({ scriptId, rating, review }),
    [rateMutation],
  );

  const recordView = useCallback(
    (scriptId: number) => recordInteraction(scriptId, 'view'),
    [recordInteraction],
  );

  const recordDownload = useCallback(
    async (scriptId: number) => {
      await recordInteraction(scriptId, 'download');
      invalidateScript(scriptId);
    },
    [recordInteraction, invalidateScript],
  );

  const recordCopy = useCallback(
    async (scriptId: number) => {
      await recordInteraction(scriptId, 'copy');
      invalidateScript(scriptId);
    },
    [recordInteraction, invalidateScript],
  );

  return {
    favoriteScript,
    rateScript,
    recordView,
    recordDownload,
    recordCopy,
    isFavoriting: favoriteMutation.isPending,
    isRating: rateMutation.isPending,
  };
}

// ===========================================================================
// useScriptParameters — customization parameters with loading state
// ===========================================================================

export interface UseScriptParametersReturn {
  parameters: ScriptParameter[];
  isLoading: boolean;
  isFetching: boolean;
  error: string | null;
  refetch: () => void;
}

/**
 * Fetch the customizable parameters for a script (ordered by display order).
 * Disabled until a valid script id is supplied; results are cached per script.
 */
export function useScriptParameters(
  scriptId: number | null | undefined,
  options?: { enabled?: boolean },
): UseScriptParametersReturn {
  const isValidId = typeof scriptId === 'number' && scriptId > 0;

  const query = useQuery<ScriptParameter[], Error>({
    queryKey: scriptKeys.parameters(scriptId as number),
    queryFn: () => scriptApiClient.getScriptParameters(scriptId as number),
    enabled: (options?.enabled ?? true) && isValidId,
  });

  return {
    parameters: query.data ?? [],
    isLoading: query.isLoading,
    isFetching: query.isFetching,
    error: query.error ? query.error.message : null,
    refetch: () => {
      query.refetch();
    },
  };
}

// ===========================================================================
// Parameter validation + conditional-visibility helpers (pure, exported for
// reuse and unit testing)
// ===========================================================================

/** Build the initial form input map from parameter default values. */
export function buildDefaultInputs(parameters: ScriptParameter[]): Record<string, any> {
  const inputs: Record<string, any> = {};
  for (const param of parameters) {
    if (param.defaultValue !== undefined && param.defaultValue !== null) {
      inputs[param.name] = param.defaultValue;
    } else if (param.parameterType === 'multiselect') {
      inputs[param.name] = [];
    } else if (param.parameterType === 'boolean') {
      inputs[param.name] = false;
    } else {
      inputs[param.name] = '';
    }
  }
  return inputs;
}

/**
 * Determine whether a parameter should be visible given the current inputs.
 * `dependsOn` supports two forms:
 *   "fieldName"        -> visible when fieldName has a truthy value
 *   "fieldName=value"  -> visible when fieldName equals value
 */
export function isParameterVisible(
  param: ScriptParameter,
  inputs: Record<string, any>,
): boolean {
  if (!param.dependsOn) return true;

  const [depName, expected] = param.dependsOn.split('=');
  const actual = inputs[depName.trim()];

  if (expected === undefined) {
    // Truthy check (booleans, non-empty strings, non-empty arrays).
    if (Array.isArray(actual)) return actual.length > 0;
    return Boolean(actual);
  }
  return String(actual) === expected.trim();
}

function isEmptyValue(value: any): boolean {
  if (value === undefined || value === null) return true;
  if (typeof value === 'string') return value.trim() === '';
  if (Array.isArray(value)) return value.length === 0;
  return false;
}

/** Validate a single parameter's value, returning an error message or null. */
export function validateParameter(
  param: ScriptParameter,
  value: any,
): string | null {
  const empty = isEmptyValue(value);

  if (param.required && empty) {
    return `${param.label} is required`;
  }
  // Optional + empty -> nothing else to validate.
  if (empty) return null;

  const rules = param.validationRules;

  switch (param.parameterType) {
    case 'text':
    case 'region':
    case 'instanceType': {
      if (rules?.pattern) {
        try {
          if (!new RegExp(rules.pattern).test(String(value))) {
            return `${param.label} has an invalid format`;
          }
        } catch {
          // Invalid regex in metadata — skip pattern validation rather than block.
        }
      }
      break;
    }
    case 'number': {
      const num = Number(value);
      if (Number.isNaN(num)) {
        return `${param.label} must be a number`;
      }
      if (rules?.min !== undefined && num < rules.min) {
        return `${param.label} must be at least ${rules.min}`;
      }
      if (rules?.max !== undefined && num > rules.max) {
        return `${param.label} must be at most ${rules.max}`;
      }
      break;
    }
    case 'select': {
      if (rules?.options && rules.options.length > 0) {
        const allowed = rules.options.map(o => o.value);
        if (!allowed.includes(String(value))) {
          return `${param.label} has an invalid selection`;
        }
      }
      break;
    }
    case 'multiselect': {
      if (rules?.options && rules.options.length > 0 && Array.isArray(value)) {
        const allowed = new Set(rules.options.map(o => o.value));
        if (!value.every(v => allowed.has(String(v)))) {
          return `${param.label} has an invalid selection`;
        }
      }
      break;
    }
    // boolean: no further validation beyond required.
    default:
      break;
  }
  return null;
}

/**
 * Validate all currently-visible parameters against their constraints,
 * returning one {@link ParameterValidationError} per failing parameter.
 */
export function validateInputs(
  parameters: ScriptParameter[],
  inputs: Record<string, any>,
): ParameterValidationError[] {
  const errors: ParameterValidationError[] = [];
  for (const param of parameters) {
    if (!isParameterVisible(param, inputs)) continue;
    const message = validateParameter(param, inputs[param.name]);
    if (message) {
      errors.push({ parameterName: param.name, errorMessage: message });
    }
  }
  return errors;
}

// ===========================================================================
// useScriptCustomization — form inputs, validation, generate
// ===========================================================================

export interface UseScriptCustomizationReturn {
  /** Current form input values keyed by parameter name. */
  inputs: Record<string, any>;
  /** Set a single input value. */
  setInput: (name: string, value: any) => void;
  /** Replace the entire input map. */
  setInputs: (inputs: Record<string, any>) => void;
  /** Validation errors for visible parameters (empty when valid). */
  errors: ParameterValidationError[];
  /** Lookup the error message for a single parameter, if any. */
  fieldError: (name: string) => string | undefined;
  /** Whether all visible required/constrained parameters are currently valid. */
  isValid: boolean;
  /** Parameters visible given current inputs (respects `dependsOn`). */
  visibleParameters: ScriptParameter[];
  /** Run validation and update `errors`; returns the current error list. */
  validate: () => ParameterValidationError[];
  /**
   * Validate then generate the customized script. Resolves to the generated
   * script, or null when validation fails (errors are populated).
   */
  generateScript: () => Promise<CustomizedScript | null>;
  /** True while a generate request is in-flight. */
  isGenerating: boolean;
  /** The most recently generated script, or null. */
  customizedScript: CustomizedScript | null;
  /** Reset inputs back to parameter defaults and clear errors/output. */
  reset: () => void;
}

/**
 * Manage the customization form for a script: input state seeded from parameter
 * defaults, real-time validation (including conditional `dependsOn` fields),
 * and the generate-script flow via the customize endpoint.
 */
export function useScriptCustomization(
  scriptId: number,
  parameters: ScriptParameter[],
): UseScriptCustomizationReturn {
  const [inputs, setInputsState] = useState<Record<string, any>>(() =>
    buildDefaultInputs(parameters),
  );
  const [errors, setErrors] = useState<ParameterValidationError[]>([]);
  const [customizedScript, setCustomizedScript] = useState<CustomizedScript | null>(null);

  // Re-seed defaults whenever the parameter set changes (e.g. different script).
  useEffect(() => {
    setInputsState(buildDefaultInputs(parameters));
    setErrors([]);
    setCustomizedScript(null);
  }, [parameters]);

  const setInput = useCallback((name: string, value: any) => {
    setInputsState(prev => ({ ...prev, [name]: value }));
  }, []);

  const setInputs = useCallback((next: Record<string, any>) => {
    setInputsState(next);
  }, []);

  const visibleParameters = useMemo(
    () => parameters.filter(p => isParameterVisible(p, inputs)),
    [parameters, inputs],
  );

  const liveErrors = useMemo(
    () => validateInputs(parameters, inputs),
    [parameters, inputs],
  );

  const isValid = liveErrors.length === 0;

  const validate = useCallback(() => {
    const result = validateInputs(parameters, inputs);
    setErrors(result);
    return result;
  }, [parameters, inputs]);

  const generateMutation = useMutation({
    mutationFn: () => scriptApiClient.customizeScript(scriptId, inputs),
    onSuccess: result => setCustomizedScript(result),
  });

  const generateScript = useCallback(async (): Promise<CustomizedScript | null> => {
    const validationErrors = validate();
    if (validationErrors.length > 0) {
      return null;
    }
    return generateMutation.mutateAsync();
  }, [validate, generateMutation]);

  const fieldError = useCallback(
    (name: string) => errors.find(e => e.parameterName === name)?.errorMessage,
    [errors],
  );

  const reset = useCallback(() => {
    setInputsState(buildDefaultInputs(parameters));
    setErrors([]);
    setCustomizedScript(null);
  }, [parameters]);

  return {
    inputs,
    setInput,
    setInputs,
    errors,
    fieldError,
    isValid,
    visibleParameters,
    validate,
    generateScript,
    isGenerating: generateMutation.isPending,
    customizedScript,
    reset,
  };
}
