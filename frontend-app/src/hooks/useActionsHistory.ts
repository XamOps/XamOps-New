/**
 * Custom Hook for Actions History Data
 *
 * Fetches and manages AutoSpotting events and actions history
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import {
  ActionsHistoryData,
  ActionsHistoryEvent,
  ActionsHistorySummary,
  ActionsHistoryFilters,
} from '../types/actionsHistory';

interface UseActionsHistoryOptions {
  accountId: string | null;
  autoLoad?: boolean;
}

interface UseActionsHistoryReturn {
  events: ActionsHistoryEvent[];
  summary: ActionsHistorySummary;
  loading: boolean;
  error: Error | null;
  loadEvents: (filters: ActionsHistoryFilters) => Promise<void>;
  resetFilters: () => ActionsHistoryFilters;
}

const BACKEND_BASE_URL =
  window.location.hostname === 'localhost'
    ? 'http://localhost:8080'
    : window.location.origin;

const TENANT_ID = sessionStorage.getItem('tenantId') || 'customer1';

// Initialize persistent cache
interface ActionsHistoryCache {
  dataLoaded: boolean;
  events: ActionsHistoryEvent[];
  summary: ActionsHistorySummary;
}

declare global {
  interface Window {
    actionsHistoryCache?: ActionsHistoryCache;
  }
}

if (!window.actionsHistoryCache) {
  window.actionsHistoryCache = {
    dataLoaded: false,
    events: [],
    summary: {
      total_replacements: 0,
      total_interruptions: 0,
      total_estimated_savings: 0,
    },
  };
}

export const useActionsHistory = ({
  accountId,
  autoLoad = false,
}: UseActionsHistoryOptions): UseActionsHistoryReturn => {
  const [events, setEvents] = useState<ActionsHistoryEvent[]>(
    window.actionsHistoryCache?.events || []
  );
  const [summary, setSummary] = useState<ActionsHistorySummary>(
    window.actionsHistoryCache?.summary || {
      total_replacements: 0,
      total_interruptions: 0,
      total_estimated_savings: 0,
    }
  );
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const abortControllerRef = useRef<AbortController | null>(null);

  /**
   * Calculate MTBI (Mean Time Between Interruptions)
   */
  const calculateMTBI = useCallback((eventsData: ActionsHistoryEvent[]): { mtbi: string; mtbiColor: string } => {
    const interruptions = eventsData
      .filter((e) => e.event_type === 'spot_interruption')
      .sort((a, b) => new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime());

    let mtbiText = 'N/A';
    let mtbiColor = 'text-gray-400';

    if (interruptions.length > 1) {
      let totalDiffHours = 0;
      for (let i = 1; i < interruptions.length; i++) {
        const diffMs =
          new Date(interruptions[i].timestamp).getTime() -
          new Date(interruptions[i - 1].timestamp).getTime();
        totalDiffHours += diffMs / (1000 * 60 * 60);
      }
      const avgHours = totalDiffHours / (interruptions.length - 1);

      if (avgHours < 24) {
        mtbiText = `${avgHours.toFixed(1)} hrs`;
        mtbiColor = 'text-red-600';
      } else {
        mtbiText = `${(avgHours / 24).toFixed(1)} days`;
        mtbiColor = 'text-emerald-600';
      }
    } else if (interruptions.length === 1) {
      mtbiText = 'Single Event';
      mtbiColor = 'text-blue-600';
    } else {
      // Only say "Stable" if there are other events but NO interruptions
      mtbiText = eventsData.length > 0 ? 'Stable' : 'N/A';
      mtbiColor = eventsData.length > 0 ? 'text-emerald-600' : 'text-gray-400';
    }

    return { mtbi: mtbiText, mtbiColor };
  }, []);

  /**
   * Load events from API
   */
  const loadEvents = useCallback(
    async (filters: ActionsHistoryFilters) => {
      if (!accountId) {
        setError(new Error('No account selected'));
        return;
      }

      // Cancel previous request
      if (abortControllerRef.current) {
        abortControllerRef.current.abort();
      }
      abortControllerRef.current = new AbortController();

      setLoading(true);
      setError(null);

      try {
        const params = new URLSearchParams({
          start: new Date(filters.startDate + 'T00:00:00Z').toISOString(),
          end: new Date(filters.endDate + 'T23:59:59Z').toISOString(),
        });

        if (filters.eventType) params.append('eventType', filters.eventType);
        if (filters.asgName) params.append('asgName', filters.asgName);

        const response = await fetch(
          `${BACKEND_BASE_URL}/api/autospotting/events/${accountId}?${params}`,
          {
            signal: abortControllerRef.current.signal,
            credentials: 'include',
            headers: { 'X-Tenant-ID': TENANT_ID },
          }
        );

        if (!response.ok) {
          throw new Error(`HTTP ${response.status}: Failed to load events`);
        }

        const data: ActionsHistoryData = await response.json();

        const eventsData = data.events || [];
        const summaryData = data.summary || {
          total_replacements: 0,
          total_interruptions: 0,
          total_estimated_savings: 0,
        };

        // Calculate MTBI
        const mtbiData = calculateMTBI(eventsData);
        summaryData.mtbi = mtbiData.mtbi;
        summaryData.mtbiColor = mtbiData.mtbiColor;

        setEvents(eventsData);
        setSummary(summaryData);

        // Store in window cache
        if (window.actionsHistoryCache) {
          window.actionsHistoryCache.events = eventsData;
          window.actionsHistoryCache.summary = summaryData;
          window.actionsHistoryCache.dataLoaded = true;
        }
      } catch (err) {
        if (err instanceof Error && err.name !== 'AbortError') {
          console.error('❌ Error loading events:', err);
          setError(err);
        }
      } finally {
        setLoading(false);
      }
    },
    [accountId, calculateMTBI]
  );

  /**
   * Reset filters to default
   */
  const resetFilters = useCallback((): ActionsHistoryFilters => {
    const defaultFilters: ActionsHistoryFilters = {
      startDate: new Date(Date.now() - 7 * 24 * 60 * 60 * 1000)
        .toISOString()
        .split('T')[0],
      endDate: new Date().toISOString().split('T')[0],
      eventType: '',
      asgName: '',
    };
    return defaultFilters;
  }, []);

  /**
   * Auto-load on mount if enabled
   */
  useEffect(() => {
    if (accountId && autoLoad && !window.actionsHistoryCache?.dataLoaded) {
      const defaultFilters = resetFilters();
      loadEvents(defaultFilters);
    }
  }, [accountId, autoLoad, loadEvents, resetFilters]);

  return {
    events,
    summary,
    loading,
    error,
    loadEvents,
    resetFilters,
  };
};
