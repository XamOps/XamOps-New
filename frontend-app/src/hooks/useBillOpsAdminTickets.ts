/**
 * Custom hook for managing BillOps Admin Tickets
 *
 * Handles ticket fetching, caching, and filtering with stale-while-revalidate pattern
 * For ROLE_BILLOPS_ADMIN users to view all tickets
 *
 * Converted from: billops/admin_tickets.html
 */

import { useState, useEffect, useCallback, useMemo } from 'react';
import {
  AdminTicket,
  AdminTicketCacheData,
  AdminTicketTab
} from '../types/billopsAdminTickets';

const CACHE_KEY = 'xamops_admin_tickets_v1';

interface UseBillOpsAdminTicketsReturn {
  tickets: AdminTicket[];
  filteredTickets: AdminTicket[];
  loading: boolean;
  isUpdating: boolean;
  error: string | null;
  lastUpdated: number | null;
  lastUpdatedDisplay: string;
  hasData: boolean;
  activeTab: AdminTicketTab;
  search: string;
  setActiveTab: (tab: AdminTicketTab) => void;
  setSearch: (search: string) => void;
  fetchTickets: (forceRefresh?: boolean) => Promise<void>;
  getCategoryCount: (category: AdminTicketTab) => number;
}

export const useBillOpsAdminTickets = (): UseBillOpsAdminTicketsReturn => {
  const [tickets, setTickets] = useState<AdminTicket[]>([]);
  const [loading, setLoading] = useState(true);
  const [isUpdating, setIsUpdating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastUpdated, setLastUpdated] = useState<number | null>(null);
  const [activeTab, setActiveTab] = useState<AdminTicketTab>('Account and Billing');
  const [search, setSearch] = useState('');

  const hasData = tickets.length > 0;

  /**
   * Format last updated timestamp for display
   */
  const lastUpdatedDisplay = useMemo(() => {
    if (!lastUpdated) return '';
    const date = new Date(lastUpdated);
    return date.toLocaleString([], {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit'
    });
  }, [lastUpdated]);

  /**
   * Filter tickets based on active tab and search query
   */
  const filteredTickets = useMemo(() => {
    let result = tickets;

    // Filter by category tab
    if (activeTab !== 'All') {
      result = result.filter(t => t.category === activeTab);
    }

    // Filter by search query
    if (search) {
      const q = search.toLowerCase();
      result = result.filter(t =>
        (t.subject && t.subject.toLowerCase().includes(q)) ||
        (t.id && t.id.toString().includes(q)) ||
        (t.accountId && t.accountId.toString().includes(q)) ||
        (t.creatorId && t.creatorId.toString().includes(q)) ||
        (t.status && t.status.toLowerCase().includes(q))
      );
    }

    return result;
  }, [tickets, activeTab, search]);

  /**
   * Get count of tickets for a specific category
   */
  const getCategoryCount = useCallback((category: AdminTicketTab): number => {
    if (category === 'All') {
      return tickets.length;
    }
    return tickets.filter(t => t.category === category).length;
  }, [tickets]);

  /**
   * Load tickets from localStorage cache
   */
  const loadFromCache = useCallback((): boolean => {
    try {
      const cached = localStorage.getItem(CACHE_KEY);
      if (cached) {
        let cacheData: AdminTicketCacheData;
        const parsed = JSON.parse(cached);

        // Handle backward compatibility if cached data was just the array
        if (Array.isArray(parsed)) {
          cacheData = { data: parsed, timestamp: Date.now() };
        } else {
          cacheData = parsed as AdminTicketCacheData;
        }

        if (cacheData.data && Array.isArray(cacheData.data)) {
          console.log('⚡ [Admin Tickets] Loaded from cache.');

          // Sort: Newest first
          const sortedTickets = cacheData.data.sort((a, b) => b.id - a.id);
          setTickets(sortedTickets);

          if (cacheData.timestamp) {
            setLastUpdated(cacheData.timestamp);
          }

          setLoading(false);
          return true;
        }
      }
    } catch (err) {
      console.error('Error reading cache:', err);
    }
    return false;
  }, []);

  /**
   * Fetch fresh tickets from API
   */
  const fetchTickets = useCallback(async (forceRefresh = false) => {
    if (forceRefresh) {
      console.log('🖱️ Manual refresh triggered');
    }

    // Show loading spinner appropriately
    if (forceRefresh || !hasData) {
      setIsUpdating(true);
      if (!hasData) setLoading(true);
    } else {
      // Subtle background refresh
      setIsUpdating(true);
    }

    setError(null);

    try {
      const url = forceRefresh
        ? '/api/billops/tickets?forceRefresh=true'
        : '/api/billops/tickets';

      const response = await fetch(url, { credentials: 'include' });

      if (response.status === 401 || response.status === 403) {
        window.location.href = '/';
        return;
      }

      if (!response.ok) {
        throw new Error(`Server responded with status: ${response.status}`);
      }

      const allTickets: AdminTicket[] = await response.json();
      console.log('✅ [Admin Tickets] Fresh data received.');

      // Sort: Newest first
      const sortedTickets = allTickets.sort((a, b) => b.id - a.id);

      // Update cache with timestamp
      const now = Date.now();
      const cacheData: AdminTicketCacheData = {
        data: sortedTickets,
        timestamp: now
      };
      localStorage.setItem(CACHE_KEY, JSON.stringify(cacheData));

      setLastUpdated(now);
      setTickets(sortedTickets);
      setError(null);

    } catch (err) {
      console.error('Fetch error:', err);
      if (!hasData) {
        setError('Could not load tickets. Please try again.');
      }
    } finally {
      setLoading(false);
      setIsUpdating(false);
    }
  }, [hasData]);

  /**
   * Initialize - Load from cache then fetch fresh data
   */
  useEffect(() => {
    // Attempt instant load from cache
    loadFromCache();

    // Trigger background sync (stale-while-revalidate)
    fetchTickets(false);
  }, []);

  /**
   * Listen for navigation events
   */
  useEffect(() => {
    const handlePopState = () => {
      console.log('[Admin Tickets] Popstate event detected, refreshing tickets list');
      fetchTickets(false);
    };

    window.addEventListener('popstate', handlePopState);

    return () => {
      window.removeEventListener('popstate', handlePopState);
    };
  }, [fetchTickets]);

  return {
    tickets,
    filteredTickets,
    loading,
    isUpdating,
    error,
    lastUpdated,
    lastUpdatedDisplay,
    hasData,
    activeTab,
    search,
    setActiveTab,
    setSearch,
    fetchTickets,
    getCategoryCount
  };
};
