import { useState, useCallback, useRef } from 'react';
import { apiFetchJson } from '../utils/apiClient';
import type {
  RequestLogEntry,
  PagedResponse,
  AgentUsageStat,
  ModelComparisonStat,
  HeatmapCell,
  FilterOptions,
} from '../types/aiops';

export interface UsageFilters {
  provider: string;
  model: string;
  agent: string;
}

export interface UseUsageAnalyticsReturn {
  // Request Logs
  requestLogs: PagedResponse<RequestLogEntry> | null;
  logsLoading: boolean;
  logsPage: number;
  logsSize: number;
  setLogsPage: (page: number) => void;
  setLogsSize: (size: number) => void;
  loadRequestLogs: () => Promise<void>;

  // Agent Breakdown
  agentBreakdown: AgentUsageStat[];
  agentLoading: boolean;
  loadAgentBreakdown: () => Promise<void>;

  // Model Comparison
  modelComparison: ModelComparisonStat[];
  modelLoading: boolean;
  loadModelComparison: () => Promise<void>;

  // Token Heatmap
  tokenHeatmap: HeatmapCell[];
  heatmapLoading: boolean;
  loadTokenHeatmap: () => Promise<void>;

  // Filters
  filterOptions: FilterOptions | null;
  filters: UsageFilters;
  setFilters: (filters: UsageFilters) => void;
  loadFilterOptions: () => Promise<void>;

  // Error
  error: string | null;
}

export function useUsageAnalyticsData(period: string): UseUsageAnalyticsReturn {
  const [requestLogs, setRequestLogs] = useState<PagedResponse<RequestLogEntry> | null>(null);
  const [logsLoading, setLogsLoading] = useState(false);
  const [logsPage, setLogsPage] = useState(0);
  const [logsSize, setLogsSize] = useState(20);

  const [agentBreakdown, setAgentBreakdown] = useState<AgentUsageStat[]>([]);
  const [agentLoading, setAgentLoading] = useState(false);

  const [modelComparison, setModelComparison] = useState<ModelComparisonStat[]>([]);
  const [modelLoading, setModelLoading] = useState(false);

  const [tokenHeatmap, setTokenHeatmap] = useState<HeatmapCell[]>([]);
  const [heatmapLoading, setHeatmapLoading] = useState(false);

  const [filterOptions, setFilterOptions] = useState<FilterOptions | null>(null);
  const [filters, setFilters] = useState<UsageFilters>({ provider: '', model: '', agent: '' });
  const [error, setError] = useState<string | null>(null);

  const requestVersion = useRef(0);

  const buildFilterParams = useCallback(() => {
    const params: string[] = [];
    if (filters.provider) params.push(`provider=${encodeURIComponent(filters.provider)}`);
    if (filters.model) params.push(`model=${encodeURIComponent(filters.model)}`);
    if (filters.agent) params.push(`agent=${encodeURIComponent(filters.agent)}`);
    return params.length > 0 ? '&' + params.join('&') : '';
  }, [filters]);

  const loadFilterOptions = useCallback(async () => {
    try {
      const data = await apiFetchJson<FilterOptions>('/api/xamops/aiops/filter-options');
      setFilterOptions(data);
    } catch (e: any) {
      console.error('Failed to load filter options:', e);
    }
  }, []);

  const loadRequestLogs = useCallback(async () => {
    const version = ++requestVersion.current;
    setLogsLoading(true);
    setError(null);
    try {
      const filterParams = buildFilterParams();
      const data = await apiFetchJson<PagedResponse<RequestLogEntry>>(
        `/api/xamops/aiops/request-logs?period=${period}&page=${logsPage}&size=${logsSize}${filterParams}`
      );
      if (version !== requestVersion.current) return;
      setRequestLogs(data);
    } catch (e: any) {
      if (version !== requestVersion.current) return;
      setError(e.message || 'Failed to load request logs');
    } finally {
      if (version === requestVersion.current) setLogsLoading(false);
    }
  }, [period, logsPage, logsSize, buildFilterParams]);

  const loadAgentBreakdown = useCallback(async () => {
    setAgentLoading(true);
    try {
      const data = await apiFetchJson<AgentUsageStat[]>(
        `/api/xamops/aiops/agent-breakdown?period=${period}`
      );
      setAgentBreakdown(data);
    } catch (e: any) {
      console.error('Failed to load agent breakdown:', e);
    } finally {
      setAgentLoading(false);
    }
  }, [period]);

  const loadModelComparison = useCallback(async () => {
    setModelLoading(true);
    try {
      const data = await apiFetchJson<ModelComparisonStat[]>(
        `/api/xamops/aiops/model-comparison?period=${period}`
      );
      setModelComparison(data);
    } catch (e: any) {
      console.error('Failed to load model comparison:', e);
    } finally {
      setModelLoading(false);
    }
  }, [period]);

  const loadTokenHeatmap = useCallback(async () => {
    setHeatmapLoading(true);
    try {
      const data = await apiFetchJson<HeatmapCell[]>(
        `/api/xamops/aiops/token-heatmap?period=${period}`
      );
      setTokenHeatmap(data);
    } catch (e: any) {
      console.error('Failed to load token heatmap:', e);
    } finally {
      setHeatmapLoading(false);
    }
  }, [period]);

  return {
    requestLogs,
    logsLoading,
    logsPage,
    logsSize,
    setLogsPage,
    setLogsSize,
    loadRequestLogs,
    agentBreakdown,
    agentLoading,
    loadAgentBreakdown,
    modelComparison,
    modelLoading,
    loadModelComparison,
    tokenHeatmap,
    heatmapLoading,
    loadTokenHeatmap,
    filterOptions,
    filters,
    setFilters,
    loadFilterOptions,
    error,
  };
}
