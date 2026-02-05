/**
 * useUnifiedDashboard Hook
 *
 * Custom hook for managing unified dashboard state and data fetching
 * Handles fetching data from all providers (AWS, GCP, Azure) and summary endpoint
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import type {
  ProviderData,
  SummaryData,
  HealthData,
  DashboardErrors,
  DashboardLoading,
  ViewMode,
  SubAccount,
} from '../types/dashboard';
import { useCurrency } from './useCurrency';

export const useUnifiedDashboard = () => {
  const { convert, loadRates } = useCurrency();
  // Loading states
  const [isLoading, setIsLoading] = useState<DashboardLoading>({
    summary: true,
    aws: true,
    gcp: true,
    azure: true,
    health: true,
  });

  // Error states
  const [error, setError] = useState<DashboardErrors>({
    summary: null,
    aws: null,
    gcp: null,
    azure: null,
    health: null,
  });

  // Data states
  const [summaryData, setSummaryData] = useState({
    awsCount: 0,
    gcpCount: 0,
    azureCount: 0,
    totalResources: 0,
    totalAccounts: 0,
  });
  const [awsData, setAwsData] = useState<ProviderData | null>(null);
  const [gcpData, setGcpData] = useState<ProviderData | null>(null);
  const [azureData, setAzureData] = useState<ProviderData | null>(null);
  const [healthData, setHealthData] = useState<HealthData | null>(null);

  // UI states
  const [showModal, setShowModal] = useState(false);
  const [viewMode, setViewMode] = useState<ViewMode>('cost');
  const [activeProvider, setActiveProvider] = useState('');
  const [activeAccountList, setActiveAccountList] = useState<SubAccount[]>([]);

  // Timestamp states
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
  const [serverTime, setServerTime] = useState<string | null>(null);
  const [lastUpdatedText, setLastUpdatedText] = useState('Checking...');

  // Intervals and initialization refs
  const intervalsRef = useRef<{ timeAgo?: ReturnType<typeof setInterval>; autoRefresh?: ReturnType<typeof setInterval> }>({});
  const initializedRef = useRef(false);

  // Update time ago text - stable reference using ref
  const updateTimeAgoRef = useRef<(() => void) | undefined>(undefined);
  updateTimeAgoRef.current = () => {
    if (!lastUpdated) {
      setLastUpdatedText('Updating...');
      return;
    }
    const diff = Math.floor((new Date().getTime() - lastUpdated.getTime()) / 60000);
    if (diff < 1) setLastUpdatedText('Just now');
    else setLastUpdatedText(`Updated ${diff} mins ago`);
  };

  const updateTimeAgo = useCallback(() => {
    updateTimeAgoRef.current?.();
  }, []);

  // Fetch summary data
  const fetchSummary = useCallback(async (force: boolean = false, silent: boolean = false) => {
    setError((prev) => ({ ...prev, summary: null }));

    // Only show loading if forcing refresh AND not silent, or if we haven't loaded initially
    if (!silent && (force || isLoading.summary)) {
      setIsLoading((prev) => ({ ...prev, summary: true }));
    }

    try {
      const url = '/api/xamops/dashboard/unified/summary' + (force ? '?forceRefresh=true' : '');
      const res = await fetch(url);
      if (!res.ok) throw new Error('Summary failed');
      const data: SummaryData = await res.json();
      const acc = data.selectedAccount;

      setSummaryData({
        awsCount: acc.awsAccountCount,
        gcpCount: acc.gcpAccountCount,
        azureCount: acc.azureAccountCount,
        totalAccounts: acc.awsAccountCount + acc.gcpAccountCount + acc.azureAccountCount,
        totalResources: 0,
      });
    } catch (e) {
      console.error(e);
      setError((prev) => ({ ...prev, summary: 'Data Unavailable' }));
    } finally {
      setIsLoading((prev) => ({ ...prev, summary: false }));
    }
  }, []);

  // Fetch provider data
  const fetchProvider = useCallback(async (provider: 'aws' | 'gcp' | 'azure', force: boolean = false, silent: boolean = false) => {
    // Only set loading if not silent AND (forced or if we don't have data yet)
    if (!silent && (force || (provider === 'aws' && !awsData) || (provider === 'gcp' && !gcpData) || (provider === 'azure' && !azureData))) {
      setIsLoading((prev) => ({ ...prev, [provider]: true }));
    }
    setError((prev) => ({ ...prev, [provider]: null }));

    try {
      const url = `/api/xamops/dashboard/unified/${provider}` + (force ? '?forceRefresh=true' : '');
      const res = await fetch(url);
      if (!res.ok) throw new Error(`${provider.toUpperCase()} Failed`);
      const data: ProviderData = await res.json();
      if (data.error) throw new Error(data.error);

      let processedData = data;
      if (provider === 'gcp') {
        processedData = convertGcpDataToUSD(data);
      }

      // Update state based on provider
      if (provider === 'aws') setAwsData(processedData);
      else if (provider === 'gcp') setGcpData(processedData);
      else if (provider === 'azure') setAzureData(processedData);

      // Update server time
      if (data.lastUpdated) {
        setServerTime(data.lastUpdated);
      }

      // Update total resources
      if (data.selectedAccount?.resourceInventory) {
        setSummaryData((prev) => ({
          ...prev,
          totalResources: prev.totalResources + (data.selectedAccount.resourceInventory.totalCount || 0),
        }));
      }
    } catch (e: any) {
      console.error(`Error loading ${provider}`, e);
      setError((prev) => ({ ...prev, [provider]: e.message || 'Connection Failed' }));
    } finally {
      setIsLoading((prev) => ({ ...prev, [provider]: false }));
    }
  }, [awsData, gcpData, azureData]);

  // Fetch health data
  const fetchHealth = useCallback(async () => {
    try {
      const res = await fetch('/api/xamops/dashboard/unified/health');
      if (res.ok) {
        const data = await res.json();
        setHealthData(data);
      }
    } catch (e) {
      console.error(e);
    } finally {
      setIsLoading((prev) => ({ ...prev, health: false }));
    }
  }, []);

  // Store fetch functions in refs for stable reference in intervals
  const fetchFunctionsRef = useRef({ fetchSummary, fetchProvider, fetchHealth });
  fetchFunctionsRef.current = { fetchSummary, fetchProvider, fetchHealth };

  // Fetch all data - using ref to avoid dependency issues
  const fetchAllImpl = useCallback((force: boolean = false, silent: boolean = false) => {
    const { fetchSummary, fetchProvider, fetchHealth } = fetchFunctionsRef.current;

    if (force && !silent) {
      // Reset states when forcing refresh (only if not silent)
      setIsLoading({
        summary: true,
        aws: true,
        gcp: true,
        azure: true,
        health: true,
      });
      setError({
        summary: null,
        aws: null,
        gcp: null,
        azure: null,
        health: null,
      });
      setAwsData(null);
      setGcpData(null);
      setAzureData(null);
      setSummaryData((prev) => ({ ...prev, totalResources: 0 }));
    }

    if (!silent) {
      setLastUpdated(new Date());
    }

    fetchSummary(force, silent);
    fetchProvider('aws', force, silent);
    fetchProvider('gcp', force, silent);
    fetchProvider('azure', force, silent);
    fetchHealth();
  }, []);

  // Stable fetchAll reference for external use
  const fetchAll = useCallback((force: boolean = false, silent: boolean = false) => {
    fetchAllImpl(force, silent);
  }, [fetchAllImpl]);

  // Helper to get all accounts
  const getAllAccounts = useCallback((): SubAccount[] => {
    const all: SubAccount[] = [];
    if (awsData?.selectedAccount?.subAccounts) all.push(...awsData.selectedAccount.subAccounts);
    if (gcpData?.selectedAccount?.subAccounts) all.push(...gcpData.selectedAccount.subAccounts);
    if (azureData?.selectedAccount?.subAccounts) all.push(...azureData.selectedAccount.subAccounts);
    return JSON.parse(JSON.stringify(all));
  }, [awsData, gcpData, azureData]);

  // Helper to convert GCP data to USD
  const convertGcpDataToUSD = useCallback((data: ProviderData): ProviderData => {
    if (!data.selectedAccount) return data;

    const convertValue = (val: number | undefined) => {
      if (!val) return 0;
      const converted = convert(val, 'usd');
      return converted !== null ? converted : val;
    };

    const convertedAccount = {
      ...data.selectedAccount,
      monthToDateSpend: convertValue(data.selectedAccount.monthToDateSpend),
      forecastedSpend: convertValue(data.selectedAccount.forecastedSpend),
      optimizationSummary: {
        ...data.selectedAccount.optimizationSummary,
        totalPotentialSavings: convertValue(data.selectedAccount.optimizationSummary?.totalPotentialSavings),
      },
      costHistory: {
        ...data.selectedAccount.costHistory,
        costs: data.selectedAccount.costHistory?.costs?.map((c) => {
          const converted = convert(c, 'usd');
          return converted !== null ? converted : c;
        }) || [],
      },
      subAccounts: data.selectedAccount.subAccounts?.map((acc) => ({
        ...acc,
        monthToDateSpend: convertValue(acc.monthToDateSpend),
        forecastedSpend: convertValue(acc.forecastedSpend),
        optimizationSummary: acc.optimizationSummary ? {
          ...acc.optimizationSummary,
          totalPotentialSavings: convertValue(acc.optimizationSummary.totalPotentialSavings),
        } : undefined,
      })) || [],
    };

    return {
      ...data,
      selectedAccount: convertedAccount,
    };
  }, [convert]);

  // Initialize on mount - only run once
  useEffect(() => {
    // Load INR rates for GCP conversion
    loadRates('inr');

    // Guard against double initialization (React Strict Mode)
    if (initializedRef.current) {
      return;
    }
    initializedRef.current = true;

    console.log('📊 Unified Dashboard: Initializing...');
    fetchAllImpl(false);

    // Setup intervals
    intervalsRef.current.timeAgo = setInterval(() => {
      updateTimeAgoRef.current?.();
    }, 60000);

    intervalsRef.current.autoRefresh = setInterval(() => {
      console.log('Auto-refreshing dashboard data (silent force refresh)...');
      fetchFunctionsRef.current.fetchSummary(true, true);
      fetchFunctionsRef.current.fetchProvider('aws', true, true);
      fetchFunctionsRef.current.fetchProvider('gcp', true, true);
      fetchFunctionsRef.current.fetchProvider('azure', true, true);
      fetchFunctionsRef.current.fetchHealth();
      setLastUpdated(new Date()); // Update the last updated time after background fetch
    }, 60000);

    // Cleanup
    return () => {
      if (intervalsRef.current.timeAgo) clearInterval(intervalsRef.current.timeAgo);
      if (intervalsRef.current.autoRefresh) clearInterval(intervalsRef.current.autoRefresh);
    };
  }, []); // Empty dependency array - only run on mount

  // Update time ago when lastUpdated changes
  useEffect(() => {
    updateTimeAgo();
  }, [lastUpdated, updateTimeAgo]);

  return {
    // State
    isLoading,
    error,
    summaryData,
    awsData,
    gcpData,
    azureData,
    healthData,
    showModal,
    viewMode,
    activeProvider,
    activeAccountList,
    lastUpdatedText,
    serverTime,

    // Actions
    fetchAll,
    fetchProvider,
    setShowModal,
    setViewMode,
    setActiveProvider,
    setActiveAccountList,
    getAllAccounts,
  };
};
