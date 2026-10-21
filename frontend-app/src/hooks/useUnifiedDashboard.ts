/**
 * useUnifiedDashboard Hook
 *
 * Custom hook for managing unified dashboard state and data fetching.
 * Uses React Query so responses are cached and shared across page navigations —
 * if the user already visited any provider page, the Unified Dashboard renders
 * instantly from cache without firing duplicate network calls.
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import type {
  ProviderData,
  SummaryData,
  HealthData,
  DashboardErrors,
  DashboardLoading,
  ViewMode,
  SubAccount,
} from '../types/dashboard';
import { useAccounts } from '../contexts/AccountContext';
import { apiFetch } from '../utils/apiClient';

// ---------------------------------------------------------------------------
// Query keys — centralised so any page can invalidate them
// ---------------------------------------------------------------------------
export const unifiedDashboardKeys = {
  summary: ['unified', 'summary'] as const,
  provider: (p: 'aws' | 'gcp' | 'azure') => ['unified', 'provider', p] as const,
  health: ['unified', 'health'] as const,
  tickets: ['unified', 'tickets'] as const,
};

// ---------------------------------------------------------------------------
// Fetcher functions (plain async, no React state)
// ---------------------------------------------------------------------------
const fetchSummaryData = async (force: boolean): Promise<SummaryData> => {
  const url = '/api/xamops/dashboard/unified/summary' + (force ? '?forceRefresh=true' : '');
  const res = await apiFetch(url);
  if (!res.ok) throw new Error('Summary failed');
  return res.json();
};

const fetchProviderData = async (provider: 'aws' | 'gcp' | 'azure', force: boolean): Promise<ProviderData> => {
  const url = `/api/xamops/dashboard/unified/${provider}` + (force ? '?forceRefresh=true' : '');
  const res = await apiFetch(url);
  if (!res.ok) throw new Error(`${provider.toUpperCase()} Failed`);
  const data: ProviderData = await res.json();
  if (data.error) throw new Error(data.error);
  return data;
};

const fetchHealthData = async (): Promise<HealthData> => {
  const res = await apiFetch('/api/xamops/dashboard/unified/health');
  if (!res.ok) throw new Error('Health failed');
  return res.json();
};

const fetchTicketCount = async (): Promise<number> => {
  const res = await apiFetch('/api/xamops/tickets/category/Technical/count');
  if (!res.ok) throw new Error('Tickets failed');
  const data = await res.json();
  return data.count ?? 0;
};

// ---------------------------------------------------------------------------
// Hook
// ---------------------------------------------------------------------------
export const useUnifiedDashboard = () => {
  const qc = useQueryClient();
  const { accounts } = useAccounts();

  // UI states
  const [showModal, setShowModal] = useState(false);
  const [viewMode, setViewMode] = useState<ViewMode>('cost');
  const [activeProvider, setActiveProvider] = useState('');
  const [activeAccountList, setActiveAccountList] = useState<SubAccount[]>([]);
  const [notification, setNotification] = useState<{ type: 'error' | 'success'; title: string; message: string } | null>(null);

  // Timestamp display
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
  const [serverTime, setServerTime] = useState<string | null>(null);
  const [lastUpdatedText, setLastUpdatedText] = useState('Checking...');

  // Monotonically increasing counter — each Refresh click increments this,
  // creating a unique query key so React Query always fires a real network
  // request even when clicked multiple times in a row (avoids toggle confusion).
  const [forceRefreshCount, setForceRefreshCount] = useState(0);
  // Whether the current query cycle is a forced refresh (count > 0)
  const isForced = forceRefreshCount > 0;

  // -------------------------------------------------------------------------
  // React Query — one query per data source
  // -------------------------------------------------------------------------
  const summaryQuery = useQuery({
    queryKey: [...unifiedDashboardKeys.summary, forceRefreshCount],
    queryFn: () => fetchSummaryData(isForced),
    staleTime: isForced ? 0 : 5 * 60 * 1000,
    gcTime: 24 * 60 * 60 * 1000,
    retry: 1,
  });

  const awsQuery = useQuery({
    queryKey: [...unifiedDashboardKeys.provider('aws'), forceRefreshCount],
    queryFn: () => fetchProviderData('aws', isForced),
    staleTime: isForced ? 0 : 5 * 60 * 1000,
    gcTime: 24 * 60 * 60 * 1000,
    retry: 1,
  });

  const gcpQuery = useQuery({
    queryKey: [...unifiedDashboardKeys.provider('gcp'), forceRefreshCount],
    queryFn: () => fetchProviderData('gcp', isForced),
    staleTime: isForced ? 0 : 5 * 60 * 1000,
    gcTime: 24 * 60 * 60 * 1000,
    retry: 1,
  });

  const azureQuery = useQuery({
    queryKey: [...unifiedDashboardKeys.provider('azure'), forceRefreshCount],
    queryFn: () => fetchProviderData('azure', isForced),
    staleTime: isForced ? 0 : 5 * 60 * 1000,
    gcTime: 24 * 60 * 60 * 1000,
    retry: 1,
  });

  const healthQuery = useQuery({
    queryKey: unifiedDashboardKeys.health,
    queryFn: fetchHealthData,
    staleTime: 5 * 60 * 1000,
    gcTime: 24 * 60 * 60 * 1000,
    retry: 1,
  });

  const ticketsQuery = useQuery({
    queryKey: unifiedDashboardKeys.tickets,
    queryFn: fetchTicketCount,
    staleTime: 5 * 60 * 1000,
    gcTime: 24 * 60 * 60 * 1000,
    retry: 1,
  });

  // -------------------------------------------------------------------------
  // Derive processed provider data
  // -------------------------------------------------------------------------
  const awsData = awsQuery.data ?? null;
  // GCP is no longer converted here (mirrors Azure below).
  //
  // The backend now returns GCP figures already in USD, at GCP's own billed rate — it divides
  // each row's native cost by the billing export's currency_conversion_rate, so a USD-billed
  // project passes through unchanged and a non-USD project is converted at the rate GCP actually
  // billed. Converting again on the client would divide by roughly 83 a second time, which is what
  // drove GCP down to ~$0 / 0.0% on this dashboard.
  const gcpData = gcpQuery.data ?? null;
  // Azure is no longer converted here.
  //
  // The backend now returns these figures already in USD, using Azure's own CostUSD aggregation —
  // the rate Azure actually billed at. Converting again would divide by roughly 83 a second time.
  //
  // Even before that, converting here was wrong twice over: it assumed every Azure subscription
  // bills in INR, which nothing verified, and it applied a static rate. Measured against a live
  // subscription, Azure reported 454.5288 INR as 4.752 USD — an implied 95.65 against the 83.33
  // this multiplier assumes, so every Azure figure on this dashboard was around 15% out even when
  // the INR assumption happened to hold.
  const azureData = azureQuery.data ?? null;
  const healthData = healthQuery.data ?? null;
  const ticketCount = ticketsQuery.data ?? 0;

  // Derive summaryData from the summary query response
  const summaryData = (() => {
    const acc = summaryQuery.data?.selectedAccount;
    if (!acc) return { awsCount: 0, gcpCount: 0, azureCount: 0, totalAccounts: 0, totalResources: 0 };

    const totalResources =
      (awsData?.selectedAccount?.resourceInventory?.totalCount || 0) +
      (gcpData?.selectedAccount?.resourceInventory?.totalCount || 0) +
      (azureData?.selectedAccount?.resourceInventory?.totalCount || 0);

    return {
      awsCount: acc.awsAccountCount,
      gcpCount: acc.gcpAccountCount,
      azureCount: acc.azureAccountCount,
      totalAccounts: acc.awsAccountCount + acc.gcpAccountCount + acc.azureAccountCount,
      totalResources,
    };
  })();

  // -------------------------------------------------------------------------
  // Stale snapshot detection — if the backend snapshot was built with fewer
  // accounts than are currently connected, auto-trigger a force refresh so the
  // unified dashboard always reflects the user's actual account list.
  // -------------------------------------------------------------------------
  const staleCheckDoneRef = useRef(false);
  useEffect(() => {
    if (staleCheckDoneRef.current) return;
    if (accounts.length === 0) return;           // AccountContext not loaded yet
    if (summaryQuery.isLoading) return;          // Summary not fetched yet
    if (!summaryQuery.data) return;

    const snapshotTotal = summaryData.totalAccounts;
    const liveTotal = accounts.length;

    if (snapshotTotal < liveTotal) {
      console.log(`[UnifiedDashboard] Stale snapshot detected: snapshot has ${snapshotTotal} accounts, live has ${liveTotal}. Force-refreshing.`);
      staleCheckDoneRef.current = true;
      setForceRefreshCount((prev) => prev + 1);
    } else {
      staleCheckDoneRef.current = true;
    }
  }, [accounts.length, summaryQuery.isLoading, summaryQuery.data, summaryData.totalAccounts]);

  // Derive loading states from React Query
  const isLoading: DashboardLoading = {
    summary: summaryQuery.isLoading,
    aws: awsQuery.isLoading,
    gcp: gcpQuery.isLoading,
    azure: azureQuery.isLoading,
    health: healthQuery.isLoading,
    tickets: ticketsQuery.isLoading,
  };

  // Derive error states
  const error: DashboardErrors = {
    summary: summaryQuery.error ? 'Data Unavailable' : null,
    aws: awsQuery.error ? (awsQuery.error as Error).message || 'Connection Failed' : null,
    gcp: gcpQuery.error ? (gcpQuery.error as Error).message || 'Connection Failed' : null,
    azure: azureQuery.error ? (azureQuery.error as Error).message || 'Connection Failed' : null,
    health: healthQuery.error ? 'Health Unavailable' : null,
    tickets: ticketsQuery.error ? 'Failed' : null,
  };

  // -------------------------------------------------------------------------
  // Update serverTime from whichever provider responds first
  // -------------------------------------------------------------------------
  useEffect(() => {
    const lastUpdatedVal =
      awsQuery.data?.lastUpdated ||
      gcpQuery.data?.lastUpdated ||
      azureQuery.data?.lastUpdated;
    if (lastUpdatedVal) setServerTime(lastUpdatedVal);
  }, [awsQuery.data, gcpQuery.data, azureQuery.data]);

  // -------------------------------------------------------------------------
  // Track lastUpdated timestamp and "X mins ago" display
  // -------------------------------------------------------------------------
  const anyLoading = Object.values(isLoading).some(Boolean);
  const prevAnyLoading = useRef(anyLoading);

  useEffect(() => {
    // When all queries finish loading, update the timestamp
    if (prevAnyLoading.current && !anyLoading) {
      setLastUpdated(new Date());
    }
    prevAnyLoading.current = anyLoading;
  }, [anyLoading]);

  useEffect(() => {
    if (!lastUpdated) {
      setLastUpdatedText('Updating...');
      return;
    }
    const update = () => {
      const diff = Math.floor((new Date().getTime() - lastUpdated.getTime()) / 60000);
      if (diff < 1) setLastUpdatedText('Just now');
      else setLastUpdatedText(`Updated ${diff} mins ago`);
    };
    update();
    const interval = setInterval(update, 60000);
    return () => clearInterval(interval);
  }, [lastUpdated]);

  // -------------------------------------------------------------------------
  // 24-hour silent background auto-refresh
  // -------------------------------------------------------------------------
  useEffect(() => {
    const interval = setInterval(async () => {
      console.log('Auto-refreshing unified dashboard (silent cache refresh)...');
      try {
        await Promise.all([
          qc.invalidateQueries({ queryKey: unifiedDashboardKeys.summary }),
          qc.invalidateQueries({ queryKey: unifiedDashboardKeys.provider('aws') }),
          qc.invalidateQueries({ queryKey: unifiedDashboardKeys.provider('gcp') }),
          qc.invalidateQueries({ queryKey: unifiedDashboardKeys.provider('azure') }),
          qc.invalidateQueries({ queryKey: unifiedDashboardKeys.health }),
          qc.invalidateQueries({ queryKey: unifiedDashboardKeys.tickets }),
        ]);
        setLastUpdated(new Date());
      } catch {
        setNotification({
          type: 'error',
          title: 'Refresh Alert',
          message: 'One or more providers failed to refresh. Displaying last cached data.',
        });
        setTimeout(() => setNotification(null), 5000);
      }
    }, 24 * 60 * 60 * 1000);

    return () => clearInterval(interval);
  }, [qc]);

  // -------------------------------------------------------------------------
  // fetchAll — manual refresh (force=true bypasses backend cache)
  // -------------------------------------------------------------------------
  const fetchAll = useCallback((force: boolean = false) => {
    if (force) {
      // Increment counter so the query key changes → React Query fires a fresh
      // request with ?forceRefresh=true even if clicked multiple times.
      setForceRefreshCount((prev) => prev + 1);
    } else {
      qc.invalidateQueries({ queryKey: ['unified'] });
    }
  }, [qc]);

  // fetchProvider — retry a single provider
  const fetchProvider = useCallback((provider: 'aws' | 'gcp' | 'azure', force: boolean = false) => {
    if (force) {
      setForceRefreshCount((prev) => prev + 1);
    } else {
      qc.invalidateQueries({ queryKey: unifiedDashboardKeys.provider(provider) });
    }
  }, [qc]);

  // -------------------------------------------------------------------------
  // WebSocket real-time inventory updates
  // -------------------------------------------------------------------------
  const updateResourceInventory = useCallback((accountId: string, inventory: any) => {
    console.log(`📦 [useUnifiedDashboard] Received inventory update for ${accountId}`);

    const patchProvider = (prev: ProviderData | undefined): ProviderData | undefined => {
      if (!prev?.selectedAccount) return prev;
      const subAccounts = prev.selectedAccount.subAccounts || [];
      const index = subAccounts.findIndex((a) => a.id === accountId);
      if (index === -1) return prev;
      const newSubs = [...subAccounts];
      newSubs[index] = { ...newSubs[index], resourceInventory: inventory };
      return { ...prev, selectedAccount: { ...prev.selectedAccount, subAccounts: newSubs } };
    };

    qc.setQueryData<ProviderData>(
      [...unifiedDashboardKeys.provider('aws'), forceRefreshCount],
      (prev) => patchProvider(prev) as ProviderData
    );
    qc.setQueryData<ProviderData>(
      [...unifiedDashboardKeys.provider('gcp'), forceRefreshCount],
      (prev) => patchProvider(prev) as ProviderData
    );
    qc.setQueryData<ProviderData>(
      [...unifiedDashboardKeys.provider('azure'), forceRefreshCount],
      (prev) => patchProvider(prev) as ProviderData
    );
  }, [qc, forceRefreshCount]);

  // -------------------------------------------------------------------------
  // Helper — merge subAccounts (accounts with live cost data) with the live
  // account list from AccountContext filtered by provider. Any account whose
  // backend data fetch failed still appears in the modal with $0 spend instead
  // of being silently dropped. Uses AccountContext (not availableAccounts) so
  // only the correct provider's accounts are included.
  // -------------------------------------------------------------------------
  const mergeProviderAccounts = useCallback((
    data: typeof awsData,
    provider: 'AWS' | 'GCP' | 'Azure'
  ): SubAccount[] => {
    if (!data) return [];
    const withData = data.selectedAccount?.subAccounts ?? [];
    const withDataIds = new Set(withData.map((a) => a.id));

    // Get connected accounts for this provider from AccountContext
    const providerAccounts = accounts.filter(
      (a) => a.provider?.toLowerCase() === provider.toLowerCase()
    );

    const fallbacks: SubAccount[] = providerAccounts
      .filter((a) => !withDataIds.has(a.id))
      .map((a) => ({
        id: a.id,
        name: a.name,
        monthToDateSpend: 0,
        forecastedSpend: 0,
      }));

    return [...withData, ...fallbacks];
  }, [accounts]);

  const getAllAccounts = useCallback((): SubAccount[] => {
    return JSON.parse(JSON.stringify([
      ...mergeProviderAccounts(awsData, 'AWS'),
      ...mergeProviderAccounts(gcpData, 'GCP'),
      ...mergeProviderAccounts(azureData, 'Azure'),
    ]));
  }, [awsData, gcpData, azureData, mergeProviderAccounts]);

  return {
    // State
    isLoading,
    error,
    summaryData,
    awsData,
    gcpData,
    azureData,
    healthData,
    ticketCount,
    showModal,
    viewMode,
    activeProvider,
    activeAccountList,
    lastUpdatedText,
    serverTime,
    notification,

    // Actions
    fetchAll,
    fetchProvider,
    updateResourceInventory,
    setNotification,
    setShowModal,
    setViewMode,
    setActiveProvider,
    setActiveAccountList,
    getAllAccounts,
    mergeProviderAccounts,
  };
};
