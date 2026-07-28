/**
 * Custom hook for managing BillOps tickets
 *
 * Handles ticket fetching, caching, and submission with stale-while-revalidate pattern
 *
 * Converted from: billops/tickets.html
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import {
  BillOpsTicket,
  CreateTicketFormData,
  TicketCacheData
} from '../types/billopsTickets';
import { useAccounts } from '../contexts/AccountContext';
import { useAuth } from '../contexts/AuthContext';
import {
  initNotificationService,
  notifyNewTicketCreated
} from '../utils/notificationService';

interface UseBillOpsTicketsReturn {
  tickets: BillOpsTicket[];
  loading: boolean;
  isUpdating: boolean;
  error: string | null;
  lastUpdated: number | null;
  hasData: boolean;
  selectedAccountId: string | null;
  fetchTickets: (forceRefresh?: boolean) => Promise<void>;
  submitTicket: (formData: CreateTicketFormData) => Promise<void>;
}

export const useBillOpsTickets = (): UseBillOpsTicketsReturn => {
  const { selectedAccountIds, accounts, isLoading: accountsLoading } = useAccounts();
  const { userProfile } = useAuth();
  const cacheKeyRef = useRef(`billops_account_tickets_v1:${userProfile?.username ?? 'unknown'}`);
  useEffect(() => {
    cacheKeyRef.current = `billops_account_tickets_v1:${userProfile?.username ?? 'unknown'}`;
  }, [userProfile?.username]);
  const [tickets, setTickets] = useState<BillOpsTicket[]>([]);
  const [loading, setLoading] = useState(true);
  const [isUpdating, setIsUpdating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastUpdated, setLastUpdated] = useState<number | null>(null);

  // Derive directly from context — avoids a two-render-cycle delay where selectedAccountId
  // was null on first fetch, causing all accounts' tickets to flash before filtering applied.
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  // Refs for tracking new tickets (for admin notifications)
  const previousTicketIdsRef = useRef<Set<number>>(new Set());
  const isInitialLoadRef = useRef<boolean>(true);
  const pollingIntervalRef = useRef<NodeJS.Timeout | null>(null);

  const hasData = tickets.length > 0;

  /**
   * Fetch fresh tickets from API
   */
  const fetchTickets = useCallback(async (forceRefresh = false) => {
    // Without a selected account there is nothing this user is scoped to see —
    // returning here stops event-driven refreshes from rendering an unfiltered list.
    if (!selectedAccountId) {
      setTickets([]);
      setLoading(false);
      return;
    }

    if (forceRefresh) {
      console.log('🖱️ Manual refresh triggered');
    }

    // Show loading spinner appropriately
    if (forceRefresh || !hasData) {
      setIsUpdating(true);
    } else {
      // Subtle background refresh
      setIsUpdating(true);
    }

    setError(null);

    try {
      const url = `/api/billops/tickets/category/Account%20and%20Billing?forceRefresh=${forceRefresh}`;
      const response = await fetch(url, { credentials: 'include' });

      if (response.status === 401 || response.status === 403) {
        window.location.href = '/login';
        return;
      }

      if (!response.ok) {
        throw new Error(`Failed to fetch tickets (Status: ${response.status})`);
      }

      const allTickets: BillOpsTicket[] = await response.json();

      // Update cache with timestamp
      const now = Date.now();
      const cacheData: TicketCacheData = {
        data: allTickets,
        timestamp: now
      };
      localStorage.setItem(cacheKeyRef.current, JSON.stringify(cacheData));

      setLastUpdated(now);

      // Filter tickets based on selected account ID
      let filteredTickets = allTickets;
      if (selectedAccountId) {
        filteredTickets = allTickets.filter(
          ticket => ticket.accountId === selectedAccountId
        );
      }

      // Sort: most recently active first (reply or creation)
      const sortedTickets = filteredTickets.sort((a, b) => {
        const aT = new Date(a.updatedAt ?? a.createdAt).getTime();
        const bT = new Date(b.updatedAt ?? b.createdAt).getTime();
        return bT - aT;
      });
      setTickets(sortedTickets);

    } catch (err) {
      console.error('Fetch error:', err);
      if (!hasData) {
        setError('Could not load tickets. Please check your connection.');
      }
    } finally {
      setLoading(false);
      setIsUpdating(false);
    }
  }, [hasData, selectedAccountId]);

  /**
   * Submit a new ticket
   */
  const submitTicket = useCallback(async (formData: CreateTicketFormData) => {
    // Auto-populate account ID if not provided
    const submitData = {
      ...formData,
      accountId: formData.accountId || selectedAccountId || ''
    };

    const response = await fetch('/api/billops/tickets', {
      credentials: 'include',
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(submitData)
    });

    if (!response.ok) {
      throw new Error('Failed to submit ticket');
    }

    // Refresh tickets after successful submission
    await fetchTickets(true);
  }, [selectedAccountId, fetchTickets]);

  /**
   * Load data on mount and when selected account changes
   */
  useEffect(() => {
    // Don't fetch if accounts are still loading or user has no accounts
    if (accountsLoading) return;
    if (accounts.length === 0) {
      setLoading(false);
      return;
    }
    // Don't fetch until an account is selected — prevents flashing all accounts'
    // tickets while AccountContext auto-selects the first account after init.
    if (!selectedAccountId) return;
    fetchTickets(false);
  }, [selectedAccountId, accounts.length, accountsLoading]);

  /**
   * Listen for account changes and navigation events
   */
  useEffect(() => {
    // Listen for account change events from sidebar
    const handleAccountChanged = () => {
      console.log('[BillOps Tickets] Account changed event detected, refreshing tickets');
      fetchTickets(false);
    };

    // Listen for browser back/forward navigation
    const handlePopState = () => {
      console.log('[BillOps Tickets] Popstate event detected, refreshing tickets list');
      fetchTickets(false);
    };

    window.addEventListener('account-changed', handleAccountChanged);
    window.addEventListener('popstate', handlePopState);

    return () => {
      window.removeEventListener('account-changed', handleAccountChanged);
      window.removeEventListener('popstate', handlePopState);
    };
  }, [fetchTickets]);

  /**
   * Initialize notification service
   */
  useEffect(() => {
    initNotificationService();
  }, []);

  /**
   * Poll for new tickets and notify admins (every 30 seconds)
   */
  useEffect(() => {
    // Mirror the initial-load guard: a user with no accounts (or none selected yet)
    // must not poll — an unguarded poll re-populated the list unfiltered, showing
    // other users' tickets.
    if (accountsLoading || accounts.length === 0 || !selectedAccountId) {
      setTickets([]);
      return;
    }

    pollingIntervalRef.current = setInterval(async () => {
      try {
        const url = `/api/billops/tickets/category/Account%20and%20Billing?forceRefresh=false`;
        const response = await fetch(url, { credentials: 'include' });

        if (!response.ok) return;

        const allTickets: BillOpsTicket[] = await response.json();
        const currentTicketIds = new Set(allTickets.map(t => t.id));

        // Check for new tickets (skip initial load)
        if (!isInitialLoadRef.current) {
          for (const ticket of allTickets) {
            if (!previousTicketIdsRef.current.has(ticket.id) && document.hidden) {
              // New ticket detected - notify admin
              notifyNewTicketCreated(
                ticket.id,
                ticket.subject,
                ticket.accountId
              );
              break; // Only one notification for batch of new tickets
            }
          }
        }

        // Update tracking ref
        previousTicketIdsRef.current = currentTicketIds;
        isInitialLoadRef.current = false;

        // Update tickets state silently (without loading indicator)
        let filteredTickets = allTickets;
        if (selectedAccountId) {
          filteredTickets = allTickets.filter(t => t.accountId === selectedAccountId);
        }
        const sortedTickets = [...filteredTickets].sort((a, b) => {
          const aT = new Date(a.updatedAt ?? a.createdAt).getTime();
          const bT = new Date(b.updatedAt ?? b.createdAt).getTime();
          return bT - aT;
        });
        setTickets(sortedTickets);
      } catch (err) {
        console.error('[BillOps Tickets] Polling error:', err);
      }
    }, 30000); // 30 second polling interval

    return () => {
      if (pollingIntervalRef.current) {
        clearInterval(pollingIntervalRef.current);
      }
    };
  }, [selectedAccountId, accounts.length, accountsLoading]);

  /**
   * Update ticket IDs ref when tickets change
   */
  useEffect(() => {
    if (tickets.length > 0) {
      previousTicketIdsRef.current = new Set(tickets.map(t => t.id));
    }
  }, [tickets]);

  return {
    tickets,
    loading,
    isUpdating,
    error,
    lastUpdated,
    hasData,
    selectedAccountId,
    fetchTickets,
    submitTicket
  };
};
