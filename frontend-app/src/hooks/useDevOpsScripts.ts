/**
 * Custom hook for managing DevOps scripts
 * Handles fetching, filtering, and searching scripts from the backend API
 */

import { useState, useEffect, useMemo } from 'react';
import { DevOpsScript, DevOpsFilters } from '../types/devopsInTheBox';
import apiClient from '../utils/apiClient';

interface UseDevOpsScriptsReturn {
  scripts: DevOpsScript[];
  filteredScripts: DevOpsScript[];
  isLoading: boolean;
  error: string | null;
  filters: DevOpsFilters;
  setSearchTerm: (term: string) => void;
  setFilterCategory: (category: DevOpsFilters['filterCategory']) => void;
  refreshScripts: () => Promise<void>;
}

export const useDevOpsScripts = (): UseDevOpsScriptsReturn => {
  const [scripts, setScripts] = useState<DevOpsScript[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [filters, setFilters] = useState<DevOpsFilters>({
    searchTerm: '',
    filterCategory: 'all',
  });

  /**
   * Fetch scripts from backend API
   */
  const fetchScripts = async () => {
    setIsLoading(true);
    setError(null);

    try {
      console.log('🔧 Fetching DevOps scripts from API...');
      const response = await apiClient.get('/api/dev_ops_script');

      if (Array.isArray(response.data)) {
        setScripts(response.data);
        console.log(`✅ Loaded ${response.data.length} DevOps scripts`);
      } else {
        throw new Error('Invalid response format from server');
      }
    } catch (err: any) {
      console.error('❌ Failed to fetch DevOps scripts:', err);
      const errorMessage = err?.response?.data?.message || err?.message || 'Failed to load scripts. Please try again.';
      setError(errorMessage);
      setScripts([]);
    } finally {
      setIsLoading(false);
    }
  };

  /**
   * Refresh scripts (reset filters and reload)
   */
  const refreshScripts = async () => {
    setFilters({ searchTerm: '', filterCategory: 'all' });
    await fetchScripts();
  };

  /**
   * Filter and search scripts based on current filters
   */
  const filteredScripts = useMemo(() => {
    let result = [...scripts];

    // Filter by category
    if (filters.filterCategory !== 'all') {
      result = result.filter(
        (script) => script.category?.toLowerCase() === filters.filterCategory.toLowerCase()
      );
    }

    // Filter by search term
    if (filters.searchTerm) {
      const term = filters.searchTerm.toLowerCase();
      result = result.filter(
        (script) =>
          script.name?.toLowerCase().includes(term) ||
          script.description?.toLowerCase().includes(term)
      );
    }

    return result;
  }, [scripts, filters]);

  /**
   * Update search term
   */
  const setSearchTerm = (term: string) => {
    setFilters((prev) => ({ ...prev, searchTerm: term }));
  };

  /**
   * Update filter category
   */
  const setFilterCategory = (category: DevOpsFilters['filterCategory']) => {
    setFilters((prev) => ({ ...prev, filterCategory: category }));
  };

  // Fetch scripts on mount
  useEffect(() => {
    fetchScripts();
  }, []);

  return {
    scripts,
    filteredScripts,
    isLoading,
    error,
    filters,
    setSearchTerm,
    setFilterCategory,
    refreshScripts,
  };
};
