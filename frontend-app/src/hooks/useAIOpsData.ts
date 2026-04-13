import { useState, useEffect, useCallback, useRef } from 'react';
import { apiFetchJson } from '../utils/apiClient';
import type {
  LlmKpis,
  CostTrend,
  ProviderCostBreakdown,
  ModelUsageStat,
  CostAnomaly,
  AIOPsSectionLoading,
  LlmProviderConfig,
  AllTimeMetrics,
} from '../types/aiops';

interface UseAIOpsDataOptions {
  period: string;
}

export interface UseAIOpsDataReturn {
  kpis: LlmKpis | null;
  allTimeMetrics: AllTimeMetrics | null;
  costTrend: CostTrend | null;
  providerBreakdown: ProviderCostBreakdown[];
  modelUsageTable: ModelUsageStat[];
  anomalies: CostAnomaly[];
  providers: LlmProviderConfig[];
  sectionLoading: AIOPsSectionLoading;
  isLoading: boolean;
  error: string | null;
  loadDashboard: (forceRefresh?: boolean) => Promise<void>;
  loadProviders: () => Promise<void>;
  addProvider: (config: LlmProviderConfig) => Promise<LlmProviderConfig>;
  updateProvider: (id: number, config: LlmProviderConfig) => Promise<LlmProviderConfig>;
  deleteProvider: (id: number) => Promise<void>;
  testConnection: (config: LlmProviderConfig) => Promise<{ success: boolean; message: string }>;
}

export function useAIOpsData({ period }: UseAIOpsDataOptions): UseAIOpsDataReturn {
  const [kpis, setKpis] = useState<LlmKpis | null>(null);
  const [costTrend, setCostTrend] = useState<CostTrend | null>(null);
  const [providerBreakdown, setProviderBreakdown] = useState<ProviderCostBreakdown[]>([]);
  const [modelUsageTable, setModelUsageTable] = useState<ModelUsageStat[]>([]);
  const [anomalies, setAnomalies] = useState<CostAnomaly[]>([]);
  const [providers, setProviders] = useState<LlmProviderConfig[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sectionLoading, setSectionLoading] = useState<AIOPsSectionLoading>({
    kpis: false,
    charts: false,
    table: false,
    anomalies: false,
  });

  const [allTimeMetrics, setAllTimeMetrics] = useState<AllTimeMetrics | null>(null);

  const requestVersion = useRef(0);

  const loadProviders = useCallback(async () => {
    try {
      const data = await apiFetchJson<LlmProviderConfig[]>('/api/xamops/aiops/providers');
      setProviders(data);
    } catch (e) {
      console.error('Failed to load providers:', e);
    }
  }, []);

  const loadDashboard = useCallback(async () => {
    const version = ++requestVersion.current;
    setError(null);
    setIsLoading(true);
    setSectionLoading({ kpis: true, charts: true, table: true, anomalies: true });

    try {
      // ── Group 1: Fast — KPIs + providers ──
      const [kpisData, providersData] = await Promise.all([
        apiFetchJson<LlmKpis>(`/api/xamops/aiops/kpis?period=${period}`),
        apiFetchJson<LlmProviderConfig[]>('/api/xamops/aiops/providers'),
      ]);

      if (version !== requestVersion.current) return; // stale
      setKpis(kpisData);
      setProviders(providersData);
      setSectionLoading(prev => ({ ...prev, kpis: false }));
      setIsLoading(false); // critical path done

      // Fetch all-time metrics (not period-dependent, fire-and-forget)
      apiFetchJson<AllTimeMetrics>('/api/xamops/aiops/all-time-metrics')
        .then(data => { if (version === requestVersion.current) setAllTimeMetrics(data); })
        .catch(() => {});

      // ── Group 2: Medium — charts ──
      const [costTrendData, breakdownData] = await Promise.all([
        apiFetchJson<CostTrend>(`/api/xamops/aiops/cost-trend?period=${period}`),
        apiFetchJson<ProviderCostBreakdown[]>(`/api/xamops/aiops/provider-breakdown?period=${period}`),
      ]);

      if (version !== requestVersion.current) return;
      setCostTrend(costTrendData);
      setProviderBreakdown(breakdownData);
      setSectionLoading(prev => ({ ...prev, charts: false }));

      // ── Group 3: Slow — table + anomalies ──
      const [modelData, anomalyData] = await Promise.all([
        apiFetchJson<ModelUsageStat[]>(`/api/xamops/aiops/model-usage?period=${period}`),
        apiFetchJson<CostAnomaly[]>(`/api/xamops/aiops/anomalies?period=${period}`),
      ]);

      if (version !== requestVersion.current) return;
      setModelUsageTable(modelData);
      setAnomalies(anomalyData);
      setSectionLoading(prev => ({ ...prev, table: false, anomalies: false }));

    } catch (e: any) {
      if (version !== requestVersion.current) return;
      setError(e.message || 'Failed to load dashboard data');
      setIsLoading(false);
      setSectionLoading({ kpis: false, charts: false, table: false, anomalies: false });
    }
  }, [period]);

  // Load on mount and when period changes
  useEffect(() => {
    loadDashboard();
  }, [loadDashboard]);

  // ─── Provider CRUD ─────────────────────────────────────────

  const addProvider = useCallback(async (config: LlmProviderConfig): Promise<LlmProviderConfig> => {
    const result = await apiFetchJson<LlmProviderConfig>('/api/xamops/aiops/providers', {
      method: 'POST',
      body: JSON.stringify(config),
    });
    await loadProviders();
    return result;
  }, [loadProviders]);

  const updateProvider = useCallback(async (id: number, config: LlmProviderConfig): Promise<LlmProviderConfig> => {
    const result = await apiFetchJson<LlmProviderConfig>(`/api/xamops/aiops/providers/${id}`, {
      method: 'PUT',
      body: JSON.stringify(config),
    });
    await loadProviders();
    return result;
  }, [loadProviders]);

  const deleteProvider = useCallback(async (id: number): Promise<void> => {
    await apiFetchJson<void>(`/api/xamops/aiops/providers/${id}`, { method: 'DELETE' });
    await loadProviders();
  }, [loadProviders]);

  const testConnection = useCallback(async (
    config: LlmProviderConfig
  ): Promise<{ success: boolean; message: string }> => {
    return apiFetchJson<{ success: boolean; message: string }>('/api/xamops/aiops/providers/test', {
      method: 'POST',
      body: JSON.stringify(config),
    });
  }, []);

  return {
    kpis,
    allTimeMetrics,
    costTrend,
    providerBreakdown,
    modelUsageTable,
    anomalies,
    providers,
    sectionLoading,
    isLoading,
    error,
    loadDashboard,
    loadProviders,
    addProvider,
    updateProvider,
    deleteProvider,
    testConnection,
  };
}
