/**
 * Custom hook for managing BillOps Admin Tickets
 *
 * Handles ticket fetching, caching, and filtering with stale-while-revalidate pattern
 * For ROLE_BILLOPS_ADMIN users to view all tickets
 *
 * Converted from: billops/admin_tickets.html
 */

import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import {
  AdminTicket,
  AdminTicketCacheData,
  AdminTicketTab
} from '../types/billopsAdminTickets';
import { useAuth } from '../contexts/AuthContext';

export interface TicketFilters {
  statuses: string[];       // [] = all, or ['OPEN', 'IN_PROGRESS', 'CLOSED']
  severities: string[];     // [] = all, or ['Critical', 'High', ...]
  dateFrom: string;         // '' or ISO date string
  dateTo: string;           // '' or ISO date string
  assignee: string;         // '' = all, or specific username
}

const DEFAULT_FILTERS: TicketFilters = {
  statuses: [],
  severities: [],
  dateFrom: '',
  dateTo: '',
  assignee: ''
};

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
  filters: TicketFilters;
  activeFilterCount: number;
  setActiveTab: (tab: AdminTicketTab) => void;
  setSearch: (search: string) => void;
  setFilters: (filters: TicketFilters) => void;
  clearFilters: () => void;
  fetchTickets: (forceRefresh?: boolean) => Promise<void>;
  getCategoryCount: (category: AdminTicketTab) => number;
}

export const useBillOpsAdminTickets = (): UseBillOpsAdminTicketsReturn => {
  const { userProfile } = useAuth();
  const cacheKeyRef = useRef(`xamops_admin_tickets_v1:${userProfile?.username ?? 'unknown'}`);
  useEffect(() => {
    cacheKeyRef.current = `xamops_admin_tickets_v1:${userProfile?.username ?? 'unknown'}`;
  }, [userProfile?.username]);

  const [tickets, setTickets] = useState<AdminTicket[]>([]);
  const [loading, setLoading] = useState(true);
  const [isUpdating, setIsUpdating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastUpdated, setLastUpdated] = useState<number | null>(null);
  const [activeTab, setActiveTab] = useState<AdminTicketTab>('All');
  const [search, setSearch] = useState('');
  const [filters, setFilters] = useState<TicketFilters>(DEFAULT_FILTERS);

  const hasData = tickets.length > 0;

  // Ref to always read the latest hasData inside callbacks without stale closures
  const hasDataRef = useRef(hasData);
  hasDataRef.current = hasData;

  const clearFilters = useCallback(() => setFilters(DEFAULT_FILTERS), []);

  const activeFilterCount = useMemo(() => {
    let count = 0;
    if (filters.statuses.length > 0) count++;
    if (filters.severities.length > 0) count++;
    if (filters.dateFrom || filters.dateTo) count++;
    if (filters.assignee) count++;
    return count;
  }, [filters]);

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
   * Filter tickets based on active tab, search query, and advanced filters
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
        (t.accountId && t.accountId.toString().toLowerCase().includes(q)) ||
        (t.accountName && t.accountName.toLowerCase().includes(q)) ||
        (t.creatorId && t.creatorId.toString().includes(q)) ||
        (t.creatorUsername && t.creatorUsername.toLowerCase().includes(q)) ||
        (t.status && t.status.toLowerCase().includes(q)) ||
        (t.assignedToUsername && t.assignedToUsername.toLowerCase().includes(q))
      );
    }

    // Filter by status
    if (filters.statuses.length > 0) {
      result = result.filter(t => filters.statuses.includes(t.status));
    }

    // Filter by severity
    if (filters.severities.length > 0) {
      result = result.filter(t => filters.severities.includes(t.severity));
    }

    // Filter by date range (createdAt)
    if (filters.dateFrom) {
      const from = new Date(filters.dateFrom).getTime();
      result = result.filter(t => new Date(t.createdAt).getTime() >= from);
    }
    if (filters.dateTo) {
      const to = new Date(filters.dateTo).getTime() + 86400000; // include end of day
      result = result.filter(t => new Date(t.createdAt).getTime() <= to);
    }

    // Filter by assignee
    if (filters.assignee) {
      result = result.filter(t => t.assignedToUsername === filters.assignee);
    }

    return result;
  }, [tickets, activeTab, search, filters]);

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
      const cached = localStorage.getItem(cacheKeyRef.current);
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
   * Fetch fresh tickets from API.
   * Uses hasDataRef so this callback is stable (empty deps) and never stale.
   */
  const fetchTickets = useCallback(async (forceRefresh = false) => {
    if (forceRefresh) {
      console.log('🖱️ Manual refresh triggered');
      // Clear local cache so force refresh starts truly fresh
      localStorage.removeItem(cacheKeyRef.current);
    }

    const currentHasData = hasDataRef.current;

    setIsUpdating(true);
    if (!currentHasData) setLoading(true);
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
      localStorage.setItem(cacheKeyRef.current, JSON.stringify(cacheData));

      setLastUpdated(now);
      setTickets(sortedTickets);
      setError(null);

    } catch (err) {
      console.error('Fetch error:', err);
      // Always show error on force refresh; only show when no data otherwise
      if (forceRefresh || !hasDataRef.current) {
        setError('Could not load tickets. Please try again.');
      }
    } finally {
      setLoading(false);
      setIsUpdating(false);
    }
  }, []); // stable — reads latest state via hasDataRef

  /**
   * Initialize - Load from cache then fetch fresh data
   */
  useEffect(() => {
    // Show stale data instantly from localStorage while fresh data loads in background
    loadFromCache();

    // Background refresh — does NOT clear cache or show skeleton (isUpdating only)
    // ticket:updated event handles force-refresh after mutations (close, reply, assign)
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

  /**
   * Listen for ticket mutations from detail pages (close, reply, assign)
   * and immediately refresh to reflect changes
   */
  useEffect(() => {
    const handleTicketUpdated = () => {
      console.log('[Admin Tickets] ticket:updated event — refreshing list');
      fetchTickets(true);
    };
    window.addEventListener('ticket:updated', handleTicketUpdated);
    return () => window.removeEventListener('ticket:updated', handleTicketUpdated);
  }, [fetchTickets]);

  /**
   * Auto-poll every 30 seconds so newly created tickets (by users) appear
   * without requiring a manual refresh
   */
  useEffect(() => {
    const intervalId = setInterval(() => {
      if (document.hidden) return; // skip when tab is not visible
      fetchTickets(false);
    }, 30000);
    return () => clearInterval(intervalId);
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
    filters,
    activeFilterCount,
    setActiveTab,
    setSearch,
    setFilters,
    clearFilters,
    fetchTickets,
    getCategoryCount
  };
};
