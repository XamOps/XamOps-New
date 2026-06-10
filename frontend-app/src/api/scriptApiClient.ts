/**
 * DevOps-in-the-Box Script API client.
 *
 * Typed client for browsing, searching, customizing and interacting with the
 * enhanced DevOps script library. All requests go through the shared
 * {@link apiClient} (src/utils/apiClient.ts), which transparently injects auth /
 * CSRF / tenant headers, includes credentials, handles 401 session-expiry, and
 * provides demo-mode short-circuiting. This client never re-implements those
 * interceptors — it builds typed requests on top of the shared instance.
 *
 * Backend endpoints (ScriptController + CustomizationController):
 *   GET    /api/scripts                  list + filters (query params)
 *   GET    /api/scripts/{id}             script details
 *   GET    /api/scripts/search           full-text + filtered search
 *   GET    /api/scripts/trending         trending scripts
 *   POST   /api/scripts/{id}/view|download|copy   record interaction
 *   POST   /api/scripts/{id}/rate        rate a script
 *   POST   /api/scripts/{id}/favorite    favorite (idempotent)
 *   DELETE /api/scripts/{id}/favorite    unfavorite (idempotent)
 *   GET    /api/scripts/{id}/parameters  customization parameters
 *   POST   /api/scripts/{id}/customize   generate customized script
 *   GET    /api/customizations           list saved presets
 *   POST   /api/customizations           save preset
 *
 * Requirements: REQ-2.1.1, REQ-2.2.2, REQ-2.2.6, REQ-2.2.7, REQ-2.4.1, REQ-2.4.2
 */

import apiClient from '../utils/apiClient';
import type {
  CustomizedScript,
  DevOpsScript,
  InteractionType,
  PaginatedResponse,
  SavedCustomization,
  ScriptFilters,
  ScriptParameter,
  SearchResult,
} from '../types/devopsInTheBox';

const BASE = '/api/scripts';
const CUSTOMIZATIONS_BASE = '/api/customizations';

/** Shape returned by GET /api/scripts/{id}/parameters. */
interface ScriptParametersResponse {
  scriptId: number;
  scriptName: string;
  parameters: ScriptParameter[];
}

/**
 * Append a multi-value filter to the query string. Spring binds repeated query
 * params (e.g. ?categories=a&categories=b) to a {@code List<String>}.
 */
function appendList(params: URLSearchParams, key: string, values?: string[]): void {
  if (!values || values.length === 0) return;
  values.forEach(value => {
    if (value !== undefined && value !== null && value !== '') {
      params.append(key, value);
    }
  });
}

/**
 * Map {@link ScriptFilters} + pagination to the backend's expected query params.
 * Shared by the list and search endpoints (they accept the same filter set; the
 * search endpoint reads the text term from `query`, the list endpoint from
 * `search`).
 */
function buildFilterParams(
  filters: Partial<ScriptFilters>,
  page: number,
  size: number,
  searchKey: 'search' | 'query',
): URLSearchParams {
  const params = new URLSearchParams();

  if (filters.searchQuery) {
    params.set(searchKey, filters.searchQuery);
  }
  appendList(params, 'categories', filters.categories);
  appendList(params, 'cloudProviders', filters.cloudProviders);
  appendList(params, 'difficulties', filters.difficulties);
  appendList(params, 'tags', filters.tags);

  if (filters.minRating !== undefined && filters.minRating !== null) {
    params.set('minRating', String(filters.minRating));
  }
  if (filters.sortBy) {
    params.set('sortBy', filters.sortBy);
  }
  // showOnlyFavorites is only honored by the list endpoint, but it is harmless
  // to send to search (ignored server-side).
  if (filters.showOnlyFavorites) {
    params.set('showOnlyFavorites', 'true');
  }

  params.set('page', String(Math.max(0, page)));
  params.set('size', String(size > 0 ? size : 20));
  return params;
}

/** Wrap a thrown error with operation context for easier debugging upstream. */
function withContext<T>(operation: string, promise: Promise<T>): Promise<T> {
  return promise.catch((error: unknown) => {
    const message = error instanceof Error ? error.message : String(error);
    throw new Error(`ScriptApiClient.${operation} failed: ${message}`);
  });
}

/**
 * Typed client for the DevOps script library. Stateless apart from a configurable
 * default page size; safe to instantiate once and share (see the exported
 * singleton {@link scriptApiClient}).
 */
export class ScriptApiClient {
  private readonly defaultPageSize: number;

  constructor(defaultPageSize = 20) {
    this.defaultPageSize = defaultPageSize;
  }

  /**
   * List published scripts matching the supplied filters, paginated.
   * GET /api/scripts
   *
   * The backend list endpoint returns a plain array; we wrap it with pagination
   * metadata. `totalCount` is not known from this endpoint, so it reflects the
   * items seen so far and `hasMore` is inferred from a full page being returned.
   */
  async getAllScripts(
    filters: ScriptFilters,
    page = 0,
  ): Promise<PaginatedResponse<DevOpsScript>> {
    const size = this.defaultPageSize;
    const params = buildFilterParams(filters, page, size, 'search');
    const items = await withContext(
      'getAllScripts',
      apiClient.get<DevOpsScript[]>(`${BASE}?${params.toString()}`),
    );
    const list = Array.isArray(items) ? items : [];
    const hasMore = list.length >= size;
    return {
      items: list,
      page,
      pageSize: size,
      totalCount: page * size + list.length,
      hasMore,
    };
  }

  /**
   * Fetch a single script by id.
   * GET /api/scripts/{id}
   */
  async getScriptById(id: number): Promise<DevOpsScript> {
    return withContext('getScriptById', apiClient.get<DevOpsScript>(`${BASE}/${id}`));
  }

  /**
   * Full-text + filtered search over scripts.
   * GET /api/scripts/search
   */
  async searchScripts(
    query: string,
    filters: Partial<ScriptFilters> = {},
    page = 0,
  ): Promise<SearchResult<DevOpsScript>> {
    const size = this.defaultPageSize;
    const params = buildFilterParams(
      { ...filters, searchQuery: query },
      page,
      size,
      'query',
    );
    return withContext(
      'searchScripts',
      apiClient.get<SearchResult<DevOpsScript>>(`${BASE}/search?${params.toString()}`),
    );
  }

  /**
   * Trending scripts ranked by recent, time-decayed activity.
   * GET /api/scripts/trending
   */
  async getTrendingScripts(limit = 10): Promise<DevOpsScript[]> {
    const params = new URLSearchParams({ limit: String(limit > 0 ? limit : 10) });
    const items = await withContext(
      'getTrendingScripts',
      apiClient.get<DevOpsScript[]>(`${BASE}/trending?${params.toString()}`),
    );
    return Array.isArray(items) ? items : [];
  }

  /**
   * Record a user interaction with a script. `view`, `download` and `copy` post
   * to their dedicated endpoints; `favorite` / `unfavorite` map to the favorite
   * create/delete endpoints.
   * POST /api/scripts/{id}/{view|download|copy|favorite}, DELETE .../favorite
   */
  async recordInteraction(scriptId: number, type: InteractionType): Promise<void> {
    switch (type) {
      case 'favorite':
        await this.favoriteScript(scriptId);
        return;
      case 'unfavorite':
        await this.unfavoriteScript(scriptId);
        return;
      case 'view':
      case 'download':
      case 'copy':
        await withContext(
          'recordInteraction',
          apiClient.post<void>(`${BASE}/${scriptId}/${type}`),
        );
        return;
      default: {
        // Exhaustiveness guard — fail loudly on an unknown interaction type.
        const exhaustive: never = type;
        throw new Error(`Unsupported interaction type: ${String(exhaustive)}`);
      }
    }
  }

  /**
   * Create or update the caller's rating for a script.
   * POST /api/scripts/{id}/rate
   */
  async rateScript(scriptId: number, rating: number, review = ''): Promise<void> {
    await withContext(
      'rateScript',
      apiClient.post<unknown>(`${BASE}/${scriptId}/rate`, {
        rating,
        review: review.trim() ? review.trim() : null,
      }),
    );
  }

  /**
   * Favorite a script for the caller (idempotent).
   * POST /api/scripts/{id}/favorite
   */
  async favoriteScript(scriptId: number): Promise<void> {
    await withContext(
      'favoriteScript',
      apiClient.post<void>(`${BASE}/${scriptId}/favorite`),
    );
  }

  /**
   * Remove the caller's favorite for a script (idempotent).
   * DELETE /api/scripts/{id}/favorite
   */
  async unfavoriteScript(scriptId: number): Promise<void> {
    await withContext(
      'unfavoriteScript',
      apiClient.delete<void>(`${BASE}/${scriptId}/favorite`),
    );
  }

  /**
   * Fetch the customizable parameters for a script (ordered by display order).
   * GET /api/scripts/{id}/parameters
   */
  async getScriptParameters(scriptId: number): Promise<ScriptParameter[]> {
    const response = await withContext(
      'getScriptParameters',
      apiClient.get<ScriptParametersResponse>(`${BASE}/${scriptId}/parameters`),
    );
    return response?.parameters ?? [];
  }

  /**
   * Generate a customized script from the supplied parameter values.
   * POST /api/scripts/{id}/customize
   */
  async customizeScript(
    scriptId: number,
    inputs: Record<string, any>,
  ): Promise<CustomizedScript> {
    return withContext(
      'customizeScript',
      apiClient.post<CustomizedScript>(`${BASE}/${scriptId}/customize`, { inputs }),
    );
  }

  /**
   * Save a reusable customization preset for the caller.
   * POST /api/customizations
   */
  async saveCustomization(
    scriptId: number,
    name: string,
    inputs: Record<string, any>,
    isDefault = false,
  ): Promise<SavedCustomization> {
    return withContext(
      'saveCustomization',
      apiClient.post<SavedCustomization>(CUSTOMIZATIONS_BASE, {
        scriptId,
        customizationName: name,
        inputs,
        isDefault,
      }),
    );
  }

  /**
   * List the caller's saved customization presets.
   * GET /api/customizations
   */
  async getCustomizations(): Promise<SavedCustomization[]> {
    const items = await withContext(
      'getCustomizations',
      apiClient.get<SavedCustomization[]>(CUSTOMIZATIONS_BASE),
    );
    return Array.isArray(items) ? items : [];
  }
}

/** Shared singleton instance for app-wide use. */
export const scriptApiClient = new ScriptApiClient();

export default scriptApiClient;
