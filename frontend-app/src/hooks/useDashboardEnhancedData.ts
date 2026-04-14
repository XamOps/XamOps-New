import { useState, useCallback } from 'react';
import { apiFetchJson } from '../utils/apiClient';
import type { ModelDailyAnalysis, InsightsSummary, RoutingStats, TeamAnalyticsSummary, PagedResponse, RequestLogEntry } from '../types/aiops';

export function useDashboardEnhancedData(period: string) {
  const [modelAnalysis, setModelAnalysis] = useState<ModelDailyAnalysis | null>(null);
  const [modelAnalysisLoading, setModelAnalysisLoading] = useState(false);

  const [dashInsights, setDashInsights] = useState<InsightsSummary | null>(null);
  const [dashInsightsLoading, setDashInsightsLoading] = useState(false);

  const [dashRoutingStats, setDashRoutingStats] = useState<RoutingStats | null>(null);
  const [dashRoutingLoading, setDashRoutingLoading] = useState(false);

  const [dashTeamData, setDashTeamData] = useState<TeamAnalyticsSummary | null>(null);
  const [dashTeamLoading, setDashTeamLoading] = useState(false);

  const [dashRecentLogs, setDashRecentLogs] = useState<PagedResponse<RequestLogEntry> | null>(null);
  const [dashRecentLogsLoading, setDashRecentLogsLoading] = useState(false);

  const loadModelAnalysis = useCallback(async () => {
    setModelAnalysisLoading(true);
    try {
      const data = await apiFetchJson<ModelDailyAnalysis>(`/api/xamops/aiops/model-daily-analysis?period=${period}`);
      setModelAnalysis(data);
    } catch (e) {
      console.error('Failed to load model daily analysis:', e);
    } finally {
      setModelAnalysisLoading(false);
    }
  }, [period]);

  const loadDashInsights = useCallback(async () => {
    setDashInsightsLoading(true);
    try {
      const data = await apiFetchJson<InsightsSummary>(`/api/xamops/aiops/insights?period=${period}`);
      setDashInsights(data);
    } catch (e) {
      console.error('Failed to load dashboard insights:', e);
    } finally {
      setDashInsightsLoading(false);
    }
  }, [period]);

  const loadDashRoutingStats = useCallback(async () => {
    setDashRoutingLoading(true);
    try {
      const data = await apiFetchJson<RoutingStats>(`/api/xamops/aiops/routing/stats?period=${period}`);
      setDashRoutingStats(data);
    } catch (e) {
      console.error('Failed to load dashboard routing stats:', e);
    } finally {
      setDashRoutingLoading(false);
    }
  }, [period]);

  const loadDashTeamData = useCallback(async () => {
    setDashTeamLoading(true);
    try {
      const data = await apiFetchJson<TeamAnalyticsSummary>(`/api/xamops/aiops/team-analytics?period=${period}`);
      setDashTeamData(data);
    } catch (e) {
      console.error('Failed to load dashboard team data:', e);
    } finally {
      setDashTeamLoading(false);
    }
  }, [period]);

  const loadDashRecentLogs = useCallback(async () => {
    setDashRecentLogsLoading(true);
    try {
      const data = await apiFetchJson<PagedResponse<RequestLogEntry>>(`/api/xamops/aiops/request-logs?period=${period}&page=0&size=10`);
      setDashRecentLogs(data);
    } catch (e) {
      console.error('Failed to load recent request logs:', e);
    } finally {
      setDashRecentLogsLoading(false);
    }
  }, [period]);

  const loadAllDashboardData = useCallback(async () => {
    await Promise.all([
      loadModelAnalysis(),
      loadDashInsights(),
      loadDashRoutingStats(),
      loadDashTeamData(),
      loadDashRecentLogs(),
    ]);
  }, [loadModelAnalysis, loadDashInsights, loadDashRoutingStats, loadDashTeamData, loadDashRecentLogs]);

  return {
    modelAnalysis, modelAnalysisLoading,
    dashInsights, dashInsightsLoading,
    dashRoutingStats, dashRoutingLoading,
    dashTeamData, dashTeamLoading,
    dashRecentLogs, dashRecentLogsLoading,
    loadAllDashboardData,
  };
}
