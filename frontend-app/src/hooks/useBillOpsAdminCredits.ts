/**
 * useBillOpsAdminCredits Hook
 *
 * Converted from: billops/admin_credits.html (creditAdmin Alpine.js component)
 *
 * Manages BillOps admin credit requests with:
 * - LocalStorage caching for instant load
 * - Background refresh capability
 * - Status updates via PUT API
 *
 * What stayed the same:
 * - Same API endpoints: GET /api/billops/credits/admin/all and /api/billops/credits/admin/all/cached
 * - Same PUT endpoint for status update: PUT /api/billops/credits/{id}/status
 * - Same caching strategy
 * - All business logic preserved
 *
 * For: ROLE_BILLOPS_ADMIN
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { apiFetch } from '../utils/apiClient';
import { normalizeCreditStatus } from '../types/credits';
import type {
  AdminCreditRequest,
  AdminCreditStatus,
  UseBillOpsAdminCreditsReturn,
} from '../types/billopsAdminCredits';

const CACHE_KEY = 'admin_credits_list';

/**
 * Newest first, with any pre-rename stage translated — the localStorage cache can outlive the
 * rename, and a stage the status dropdown has no option for would render as an empty select.
 */
const prepare = (data: AdminCreditRequest[]): AdminCreditRequest[] =>
  [...data]
    .map((request) => ({ ...request, status: normalizeCreditStatus(request.status) as AdminCreditStatus }))
    .sort((a, b) => b.id - a.id);

export const useBillOpsAdminCredits = (): UseBillOpsAdminCreditsReturn => {
  const [requests, setRequests] = useState<AdminCreditRequest[]>([]);
  const [loading, setLoading] = useState(true);
  const [isUpdating, setIsUpdating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [updateError, setUpdateError] = useState<string | null>(null);

  const isMountedRef = useRef(true);

  /**
   * Load credit requests from localStorage cache
   */
  const loadFromCache = useCallback((): boolean => {
    const cached = localStorage.getItem(CACHE_KEY);
    if (cached) {
      try {
        const { data } = JSON.parse(cached);
        if (data && Array.isArray(data)) {
          console.log('Found cached admin credits. Displaying and refreshing.');
          setRequests(prepare(data));
          setLoading(false);
          return true;
        }
      } catch (e) {
        console.warn('Failed to parse cached admin credits', e);
      }
    }
    return false;
  }, []);

  /**
   * Save credit requests to localStorage cache
   */
  const saveToCache = useCallback((data: AdminCreditRequest[]) => {
    try {
      localStorage.setItem(CACHE_KEY, JSON.stringify({
        data,
        timestamp: Date.now(),
      }));
    } catch (e) {
      console.warn('Failed to cache admin credits', e);
    }
  }, []);

  /**
   * Fetch cached data from server first (for fast load)
   */
  const fetchCachedData = useCallback(async (): Promise<AdminCreditRequest[] | null> => {
    try {
      const response = await apiFetch('/api/billops/credits/admin/all/cached', {
        credentials: 'include',
      });

      if (response.ok) {
        return await response.json();
      }

      // 404 means no cache, which is OK
      if (response.status === 404) {
        return null;
      }

      throw new Error(`Cache check failed with status: ${response.status}`);
    } catch (err) {
      console.error('Error loading cached credits:', err);
      return null;
    }
  }, []);

  /**
   * Fetch fresh data from server
   */
  const fetchFreshData = useCallback(async () => {
    if (!loading) {
      setIsUpdating(true);
    }
    setUpdateError(null);

    try {
      const response = await apiFetch('/api/billops/credits/admin/all', {
        credentials: 'include',
      });

      if (!response.ok) {
        if (response.status === 401 || response.status === 403) {
          setError('You are not authorized to view this page. Redirecting to login...');
          setTimeout(() => {
            window.location.href = '/';
          }, 2000);
          return;
        }
        throw new Error(`Server responded with status: ${response.status}`);
      }

      const data: AdminCreditRequest[] = await response.json();
      console.log('Received fresh admin credits.');

      if (isMountedRef.current) {
        const sortedData = prepare(data);
        setRequests(sortedData);
        saveToCache(sortedData);
        setError(null);
      }
    } catch (err) {
      console.error('An error occurred during fresh fetch:', err);
      if (isMountedRef.current) {
        if (!error) {
          if (loading) {
            setError('Could not load credit requests.');
          } else {
            setUpdateError('Failed to refresh credit requests.');
          }
        }
      }
    } finally {
      if (isMountedRef.current) {
        setLoading(false);
        setIsUpdating(false);
      }
    }
  }, [loading, error, saveToCache]);

  /**
   * Main fetch function - loads cache first, then refreshes
   */
  const fetchData = useCallback(async (forceFresh: boolean = false) => {
    setLoading(true);
    setIsUpdating(false);
    setError(null);
    setUpdateError(null);

    if (!forceFresh) {
      // Try to load from localStorage first
      const hasLocalCache = loadFromCache();

      if (hasLocalCache) {
        // Background refresh
        fetchFreshData();
        return;
      }

      // Try server-side cache
      const cachedData = await fetchCachedData();
      if (cachedData) {
        setRequests(prepare(cachedData));
        setLoading(false);
        fetchFreshData(); // Background refresh
        return;
      }
    }

    // No cache available or force fresh, fetch directly
    console.log('No cached admin credits found. Fetching fresh data.');
    await fetchFreshData();
  }, [loadFromCache, fetchCachedData, fetchFreshData]);

  /**
   * Update credit request status
   */
  const updateStatus = useCallback(async (id: number, newStatus: AdminCreditStatus) => {
    try {
      const response = await apiFetch(`/api/billops/credits/${id}/status`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ status: newStatus }),
      });

      if (!response.ok) {
        throw new Error('Failed to update status.');
      }

      const updatedRequest: AdminCreditRequest = await response.json();

      if (isMountedRef.current) {
        setRequests(prev => {
          const updated = prev.map(r => (r.id === id ? updatedRequest : r));
          saveToCache(updated);
          return updated;
        });
      }
    } catch (err) {
      console.error('Error updating status:', err);
      alert(err instanceof Error ? err.message : 'Failed to update status.');
      // Re-fetch to revert optimistic update
      fetchFreshData();
    }
  }, [saveToCache, fetchFreshData]);

  /**
   * Initialize on mount
   */
  useEffect(() => {
    isMountedRef.current = true;

    fetchData();

    return () => {
      isMountedRef.current = false;
    };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  return {
    requests,
    loading,
    isUpdating,
    error,
    updateError,
    fetchData,
    updateStatus,
  };
};

export default useBillOpsAdminCredits;
