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
  starredClientIds: Set<number>;
  newlyUpdatedIds: Set<number>;
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
  fetchTickets: (forceRefresh?: boolean, clearLocalCache?: boolean) => Promise<void>;
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
  const [newlyUpdatedIds, setNewlyUpdatedIds] = useState<Set<number>>(new Set());

  // Tracks the snapshot seen on the previous fetch to detect new/updated tickets
  const prevSnapshotRef = useRef<Map<number, { updatedAt: string; hasUnreadUserReply: boolean }>>(new Map());
  // True until the first successful fetch completes — we don't highlight on initial load
  const isFirstFetchRef = useRef(true);
  const highlightClearTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

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

          // Sort: Most recently active first (new reply or new ticket)
          const sortedTickets = cacheData.data.sort((a, b) =>
            new Date(b.updatedAt ?? b.createdAt).getTime() - new Date(a.updatedAt ?? a.createdAt).getTime()
          );
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
  const fetchTickets = useCallback(async (forceRefresh = false, clearLocalCache = true) => {
    if (forceRefresh && clearLocalCache) {
      console.log('🖱️ Manual refresh triggered');
      // Clear local cache so force refresh starts truly fresh
      localStorage.removeItem(cacheKeyRef.current);
    }

    const currentHasData = hasDataRef.current;

    setIsUpdating(true);
    if (!currentHasData) setLoading(true);
    setError(null);

    try {
      // Always bypass Redis on force-refresh so cross-service writes (e.g. XamOps
      // reply updating the shared DB) are visible even though BillOps Redis cache
      // was not evicted by the other service.
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

      // Sort: Most recently active first (new reply or new ticket)
      const sortedTickets = allTickets.sort((a, b) =>
        new Date(b.updatedAt ?? b.createdAt).getTime() - new Date(a.updatedAt ?? a.createdAt).getTime()
      );

      // Detect newly arrived or updated tickets vs the previous snapshot
      if (!isFirstFetchRef.current) {
        const prev = prevSnapshotRef.current;
        const newIds = new Set<number>();
        for (const t of sortedTickets) {
          const prevEntry = prev.get(t.id);
          if (!prevEntry) {
            // Brand-new ticket that wasn't in the previous fetch
            newIds.add(t.id);
          } else if (
            prevEntry.updatedAt !== (t.updatedAt ?? '') ||
            prevEntry.hasUnreadUserReply !== (t.hasUnreadUserReply ?? false)
          ) {
            // Existing ticket got a reply or was otherwise updated
            newIds.add(t.id);
          }
        }
        if (newIds.size > 0) {
          setNewlyUpdatedIds(newIds);
          // Auto-clear highlight after 8 s — one-time visibility
          if (highlightClearTimerRef.current) clearTimeout(highlightClearTimerRef.current);
          highlightClearTimerRef.current = setTimeout(() => {
            setNewlyUpdatedIds(new Set());
          }, 8000);
        }
      }

      // Update snapshot for next comparison
      const nextSnapshot = new Map<number, { updatedAt: string; hasUnreadUserReply: boolean }>();
      for (const t of sortedTickets) {
        nextSnapshot.set(t.id, {
          updatedAt: t.updatedAt ?? '',
          hasUnreadUserReply: t.hasUnreadUserReply ?? false,
        });
      }
      prevSnapshotRef.current = nextSnapshot;
      isFirstFetchRef.current = false;

      // Update cache with timestamp
      const now = Date.now();
      const cacheData: AdminTicketCacheData = {
        data: sortedTickets,
        timestamp: now
      };
      localStorage.setItem(cacheKeyRef.current, JSON.stringify(cacheData));

      setLastUpdated(now);

      // Skip state update when background poll returns identical data — prevents full re-render every 30 s
      setTickets(prev => {
        if (
          !forceRefresh &&
          prev.length === sortedTickets.length &&
          prev.every((t, i) =>
            t.id === sortedTickets[i].id &&
            t.updatedAt === sortedTickets[i].updatedAt &&
            t.hasUnreadUserReply === sortedTickets[i].hasUnreadUserReply
          )
        ) {
          return prev;
        }
        return sortedTickets;
      });

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

    // If a reply/close/assign was made from the detail page (which unmounts this component),
    // the ticket:updated event was missed. A localStorage flag signals that a force-refresh
    // is needed so the replied ticket bubbles to the top and the optimization bail-out
    // doesn't serve stale Redis data.
    const needsRefresh = localStorage.getItem('billops_admin_tickets_needs_refresh') === '1';
    if (needsRefresh) {
      localStorage.removeItem('billops_admin_tickets_needs_refresh');
      fetchTickets(true);
    } else {
      fetchTickets(false);
    }
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

  // Cleanup highlight clear timer on unmount
  useEffect(() => {
    return () => {
      if (highlightClearTimerRef.current) clearTimeout(highlightClearTimerRef.current);
    };
  }, []);

  /**
   * Auto-poll every 30 seconds so newly created/replied tickets appear without
   * a manual refresh. Uses forceRefresh=true to bypass BillOps Redis cache so
   * that replies added via XamOps (shared DB, separate Redis) are visible.
   * clearLocalCache=false keeps the UI from flickering on each poll.
   */
  useEffect(() => {
    const intervalId = setInterval(() => {
      if (document.hidden) return; // skip when tab is not visible
      fetchTickets(true, false);
    }, 30000);
    return () => clearInterval(intervalId);
  }, [fetchTickets]);

  // Derive starred client IDs from the clients cache written by ClientDetailsPage
  const starredClientIds = useMemo((): Set<number> => {
    try {
      const raw = localStorage.getItem('billops_clients_cache');
      if (!raw) return new Set();
      const { data } = JSON.parse(raw);
      if (!Array.isArray(data)) return new Set();
      return new Set(
        data.filter((c: { id: number; starred: boolean }) => c.starred).map((c: { id: number }) => c.id)
      );
    } catch {
      return new Set();
    }
  }, [tickets]); // re-derive whenever tickets refresh (cache may have updated)

  return {
    tickets,
    filteredTickets,
    starredClientIds,
    newlyUpdatedIds,
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
