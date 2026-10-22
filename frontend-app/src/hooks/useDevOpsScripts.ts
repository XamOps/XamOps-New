/**
 * Custom hook for managing DevOps scripts
 * Handles fetching, filtering, and searching scripts from the backend API
 */

import { useState, useEffect, useMemo } from 'react';
import { DevOpsScript, DevOpsFilters } from '../types/devopsInTheBox';
import apiClient from '../utils/apiClient';
import { useAccounts } from '../contexts/AccountContext';

/**
 * True when a script belongs on the shelf for `provider`.
 *
 * A script with no provider tag, or one tagged `multi-cloud`, is cloud-agnostic and shows
 * everywhere; otherwise it must name the selected provider. With no account selected (or an
 * unknown provider) the whole catalogue is shown, so the page is never empty on first load.
 */
const matchesProvider = (script: DevOpsScript, provider?: string): boolean => {
  if (!provider) return true;
  const providers = script.cloudProviders ?? [];
  if (providers.length === 0) return true;
  const wanted = provider.toLowerCase();
  return providers.some((p) => {
    const tag = p?.toLowerCase();
    return tag === 'multi-cloud' || tag === wanted;
  });
};

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

  // The selected account's provider scopes the catalogue: a GCP project should be offered GCP
  // tooling, not EC2/S3 scripts. A multi-account selection is single-provider by construction
  // (AccountContext blocks mixing clouds), so the first selected account decides the shelf.
  const { accounts, selectedAccountIds } = useAccounts();
  const selectedProvider = useMemo(() => {
    if (selectedAccountIds.length === 0) return undefined;
    return accounts.find((a) => a.id === selectedAccountIds[0])?.provider;
  }, [accounts, selectedAccountIds]);

  /**
   * Fetch scripts from backend API
   */
  const fetchScripts = async () => {
    setIsLoading(true);
    setError(null);

    try {
      console.log('🔧 Fetching DevOps scripts from API...');
      const response = await apiClient.get<DevOpsScript[]>('/api/dev_ops_script');

      if (Array.isArray(response)) {
        setScripts(response);
        console.log(`✅ Loaded ${response.length} DevOps scripts`);
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

    // Scope to the selected account's cloud (multi-cloud and untagged scripts always pass).
    if (selectedProvider) {
      result = result.filter((script) => matchesProvider(script, selectedProvider));
    }

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
  }, [scripts, filters, selectedProvider]);

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
