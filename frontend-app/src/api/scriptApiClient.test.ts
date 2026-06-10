/**
 * Unit tests for ScriptApiClient.
 *
 * The shared apiClient (auth/CSRF/tenant interceptors) is mocked so these tests
 * focus on the client's own logic: endpoint routing, filter -> query-param
 * mapping, interaction-type dispatch, response shaping, and error context.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { ScriptFilters } from '../types/devopsInTheBox';

// Mock the shared apiClient module before importing the client under test.
const get = vi.fn();
const post = vi.fn();
const del = vi.fn();

vi.mock('../utils/apiClient', () => ({
  default: {
    get: (...args: any[]) => get(...args),
    post: (...args: any[]) => post(...args),
    delete: (...args: any[]) => del(...args),
  },
}));

import { ScriptApiClient } from './scriptApiClient';

const baseFilters: ScriptFilters = {
  searchQuery: '',
  categories: [],
  cloudProviders: [],
  difficulties: [],
  tags: [],
  sortBy: 'recent',
};

/** Extract the URL passed to a mocked apiClient method call. */
const urlOf = (mock: ReturnType<typeof vi.fn>, call = 0): string => mock.mock.calls[call][0];

describe('ScriptApiClient', () => {
  let client: ScriptApiClient;

  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    del.mockReset();
    client = new ScriptApiClient(20);
  });

  describe('getAllScripts', () => {
    it('hits GET /api/scripts with pagination and wraps the array', async () => {
      get.mockResolvedValue([{ id: 1 }, { id: 2 }]);
      const result = await client.getAllScripts(baseFilters, 0);

      const url = urlOf(get);
      expect(url.startsWith('/api/scripts?')).toBe(true);
      expect(url).toContain('page=0');
      expect(url).toContain('size=20');
      expect(result.items).toHaveLength(2);
      expect(result.page).toBe(0);
      expect(result.pageSize).toBe(20);
    });

    it('maps multi-value filters to repeated query params', async () => {
      get.mockResolvedValue([]);
      const filters: ScriptFilters = {
        ...baseFilters,
        searchQuery: 'vpc',
        categories: ['terraform', 'bash'],
        cloudProviders: ['aws'],
        minRating: 4,
        sortBy: 'popular',
        showOnlyFavorites: true,
      };
      await client.getAllScripts(filters, 2);

      const url = urlOf(get);
      expect(url).toContain('search=vpc');
      expect(url).toContain('categories=terraform');
      expect(url).toContain('categories=bash');
      expect(url).toContain('cloudProviders=aws');
      expect(url).toContain('minRating=4');
      expect(url).toContain('sortBy=popular');
      expect(url).toContain('showOnlyFavorites=true');
      expect(url).toContain('page=2');
    });

    it('infers hasMore=true when a full page is returned', async () => {
      get.mockResolvedValue(Array.from({ length: 20 }, (_, i) => ({ id: i })));
      const result = await client.getAllScripts(baseFilters, 0);
      expect(result.hasMore).toBe(true);
    });

    it('infers hasMore=false for a partial page', async () => {
      get.mockResolvedValue([{ id: 1 }]);
      const result = await client.getAllScripts(baseFilters, 0);
      expect(result.hasMore).toBe(false);
    });
  });

  describe('getScriptById', () => {
    it('hits GET /api/scripts/{id}', async () => {
      get.mockResolvedValue({ id: 7 });
      const script = await client.getScriptById(7);
      expect(urlOf(get)).toBe('/api/scripts/7');
      expect(script.id).toBe(7);
    });
  });

  describe('searchScripts', () => {
    it('hits GET /api/scripts/search with the query under "query"', async () => {
      get.mockResolvedValue({ items: [], totalHits: 0, page: 0, size: 20, totalPages: 0, tookMillis: 1 });
      await client.searchScripts('rds', { categories: ['terraform'] }, 1);

      const url = urlOf(get);
      expect(url.startsWith('/api/scripts/search?')).toBe(true);
      expect(url).toContain('query=rds');
      expect(url).toContain('categories=terraform');
      expect(url).toContain('page=1');
    });
  });

  describe('getTrendingScripts', () => {
    it('hits GET /api/scripts/trending with a limit', async () => {
      get.mockResolvedValue([]);
      await client.getTrendingScripts(5);
      expect(urlOf(get)).toBe('/api/scripts/trending?limit=5');
    });
  });

  describe('recordInteraction', () => {
    it('posts view/download/copy to the dedicated endpoint', async () => {
      post.mockResolvedValue(undefined);
      await client.recordInteraction(3, 'view');
      await client.recordInteraction(3, 'download');
      await client.recordInteraction(3, 'copy');
      expect(post).toHaveBeenNthCalledWith(1, '/api/scripts/3/view');
      expect(post).toHaveBeenNthCalledWith(2, '/api/scripts/3/download');
      expect(post).toHaveBeenNthCalledWith(3, '/api/scripts/3/copy');
    });

    it('routes favorite to POST and unfavorite to DELETE', async () => {
      post.mockResolvedValue(undefined);
      del.mockResolvedValue(undefined);
      await client.recordInteraction(9, 'favorite');
      await client.recordInteraction(9, 'unfavorite');
      expect(post).toHaveBeenCalledWith('/api/scripts/9/favorite');
      expect(del).toHaveBeenCalledWith('/api/scripts/9/favorite');
    });
  });

  describe('rateScript', () => {
    it('posts rating with trimmed review', async () => {
      post.mockResolvedValue({});
      await client.rateScript(4, 5, '  great  ');
      expect(post).toHaveBeenCalledWith('/api/scripts/4/rate', { rating: 5, review: 'great' });
    });

    it('sends null review when empty', async () => {
      post.mockResolvedValue({});
      await client.rateScript(4, 3, '   ');
      expect(post).toHaveBeenCalledWith('/api/scripts/4/rate', { rating: 3, review: null });
    });
  });

  describe('favorite / unfavorite', () => {
    it('favoriteScript posts to favorite endpoint', async () => {
      post.mockResolvedValue(undefined);
      await client.favoriteScript(11);
      expect(post).toHaveBeenCalledWith('/api/scripts/11/favorite');
    });

    it('unfavoriteScript deletes the favorite endpoint', async () => {
      del.mockResolvedValue(undefined);
      await client.unfavoriteScript(11);
      expect(del).toHaveBeenCalledWith('/api/scripts/11/favorite');
    });
  });

  describe('getScriptParameters', () => {
    it('unwraps the parameters array from the response', async () => {
      get.mockResolvedValue({ scriptId: 2, scriptName: 'X', parameters: [{ id: 1 }, { id: 2 }] });
      const params = await client.getScriptParameters(2);
      expect(urlOf(get)).toBe('/api/scripts/2/parameters');
      expect(params).toHaveLength(2);
    });

    it('returns an empty array when parameters are absent', async () => {
      get.mockResolvedValue({ scriptId: 2, scriptName: 'X' });
      const params = await client.getScriptParameters(2);
      expect(params).toEqual([]);
    });
  });

  describe('customizeScript', () => {
    it('posts inputs to the customize endpoint', async () => {
      post.mockResolvedValue({ scriptId: 5, customizedCode: '...' });
      await client.customizeScript(5, { REGION: 'us-east-1' });
      expect(post).toHaveBeenCalledWith('/api/scripts/5/customize', { inputs: { REGION: 'us-east-1' } });
    });
  });

  describe('saveCustomization', () => {
    it('posts the preset payload to /api/customizations', async () => {
      post.mockResolvedValue({ id: 1 });
      await client.saveCustomization(5, 'My preset', { REGION: 'us-east-1' }, true);
      expect(post).toHaveBeenCalledWith('/api/customizations', {
        scriptId: 5,
        customizationName: 'My preset',
        inputs: { REGION: 'us-east-1' },
        isDefault: true,
      });
    });
  });

  describe('error handling', () => {
    it('wraps thrown errors with operation context', async () => {
      get.mockRejectedValue(new Error('HTTP 500: boom'));
      await expect(client.getScriptById(1)).rejects.toThrow('ScriptApiClient.getScriptById failed: HTTP 500: boom');
    });
  });
});
