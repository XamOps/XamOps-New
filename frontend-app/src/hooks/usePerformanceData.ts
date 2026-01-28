/**
 * usePerformanceData Hook
 *
 * Custom hook to fetch and manage performance insights data
 * Converted from: performance.html (performanceInsights() Alpine.js component)
 *
 * API Endpoints:
 * - GET /api/xamops/metrics/insights?accountId={accountId}&forceRefresh={boolean}
 * - GET /api/xamops/metrics/insights/summary?accountId={accountId}&forceRefresh={boolean}
 * - POST /api/xamops/metrics/insights/bulk-archive
 */

import { useState, useEffect, useCallback, useMemo } from 'react';
import apiClient from '../utils/apiClient';
import type {
  PerformanceInsight,
  PerformanceSummary,
  SeverityFilter,
  ActiveTab,
  ToastState,
} from '../types/performance';

interface UsePerformanceDataOptions {
  selectedAccountId: string | null;
}

interface UsePerformanceDataReturn {
  insights: PerformanceInsight[];
  filteredInsights: PerformanceInsight[];
  bestPracticeInsights: PerformanceInsight[];
  summary: PerformanceSummary;
  isLoading: boolean;
  isRefreshing: boolean;
  isExporting: boolean;
  activeTab: ActiveTab;
  currentSeverity: SeverityFilter;
  selectedInsights: string[];
  toast: ToastState;
  isAllSelected: boolean;
  loadInsights: (forceRefresh?: boolean) => Promise<void>;
  loadSummary: (forceRefresh?: boolean) => Promise<void>;
  refresh: () => void;
  exportData: () => void;
  setActiveTab: (tab: ActiveTab) => void;
  setCurrentSeverity: (severity: SeverityFilter) => void;
  setSelectedInsights: React.Dispatch<React.SetStateAction<string[]>>;
  toggleAllInsights: () => void;
  toggleInsightSelection: (id: string) => void;
  bulkArchive: () => Promise<void>;
  showToast: (message: string, type: 'success' | 'error') => void;
  hideToast: () => void;
}

const DEFAULT_SUMMARY: PerformanceSummary = {
  performanceScore: 0,
  totalInsights: 0,
  critical: 0,
  warning: 0,
  weakWarning: 0,
};

export const usePerformanceData = ({
  selectedAccountId,
}: UsePerformanceDataOptions): UsePerformanceDataReturn => {
  const [insights, setInsights] = useState<PerformanceInsight[]>([]);
  const [summary, setSummary] = useState<PerformanceSummary>(DEFAULT_SUMMARY);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [isRefreshing, setIsRefreshing] = useState<boolean>(false);
  const [isExporting, setIsExporting] = useState<boolean>(false);
  const [activeTab, setActiveTab] = useState<ActiveTab>('insights');
  const [currentSeverity, setCurrentSeverity] = useState<SeverityFilter>('ALL');
  const [selectedInsights, setSelectedInsights] = useState<string[]>([]);
  const [toast, setToast] = useState<ToastState>({ show: false, message: '', type: 'success' });

  /**
   * Show toast notification
   */
  const showToast = useCallback((message: string, type: 'success' | 'error' = 'success') => {
    setToast({ show: true, message, type });
    setTimeout(() => {
      setToast((prev) => ({ ...prev, show: false }));
    }, 3000);
  }, []);

  /**
   * Hide toast notification
   */
  const hideToast = useCallback(() => {
    setToast((prev) => ({ ...prev, show: false }));
  }, []);

  /**
   * Create a unique key for insight deduplication
   */
  const createInsightKey = (insight: PerformanceInsight): string => {
    return `${insight.id || ''}-${insight.insight || ''}-${insight.resourceId || ''}-${insight.account || ''}-${insight.region || ''}`;
  };

  /**
   * Deduplicate insights
   */
  const deduplicateInsights = useCallback((insightsList: PerformanceInsight[]): PerformanceInsight[] => {
    const seen = new Set<string>();
    const unique: PerformanceInsight[] = [];
    let duplicateCount = 0;

    insightsList.forEach((insight) => {
      const key = createInsightKey(insight);
      if (!seen.has(key)) {
        seen.add(key);
        unique.push(insight);
      } else {
        duplicateCount++;
      }
    });

    if (duplicateCount > 0) {
      showToast(`Removed ${duplicateCount} duplicate insights`, 'success');
    }

    return unique;
  }, [showToast]);

  /**
   * Load performance insights
   */
  const loadInsights = useCallback(
    async (forceRefresh: boolean = false) => {
      if (!selectedAccountId) {
        setIsLoading(false);
        return;
      }

      const cacheKey = `performanceInsights-${selectedAccountId}`;
      const cachedData = sessionStorage.getItem(cacheKey);

      if (cachedData && !forceRefresh) {
        setInsights(deduplicateInsights(JSON.parse(cachedData)));
        setIsLoading(false);
        return;
      }

      if (!cachedData) {
        setIsLoading(true);
      }

      if (forceRefresh) {
        setIsRefreshing(true);
      }

      const url = `/api/xamops/metrics/insights?accountId=${selectedAccountId}&forceRefresh=${forceRefresh}`;

      try {
        const data = await apiClient.get<PerformanceInsight[]>(url);
        const uniqueInsights = deduplicateInsights(data);
        setInsights(uniqueInsights);
        sessionStorage.setItem(cacheKey, JSON.stringify(uniqueInsights));
      } catch (err) {
        console.error('Failed to load insights', err);
        setInsights([]);
        showToast('Failed to load insights', 'error');
      } finally {
        setIsLoading(false);
        setIsRefreshing(false);
      }
    },
    [selectedAccountId, deduplicateInsights, showToast]
  );

  /**
   * Load performance summary
   */
  const loadSummary = useCallback(
    async (forceRefresh: boolean = false) => {
      if (!selectedAccountId) return;

      const cacheKey = `performanceSummary-${selectedAccountId}`;
      const cachedData = sessionStorage.getItem(cacheKey);

      if (cachedData && !forceRefresh) {
        setSummary(JSON.parse(cachedData));
        return;
      }

      if (forceRefresh) {
        sessionStorage.removeItem(cacheKey);
      }

      const url = `/api/xamops/metrics/insights/summary?accountId=${selectedAccountId}&forceRefresh=${forceRefresh}`;

      try {
        const data = await apiClient.get<PerformanceSummary>(url);
        setSummary(data);
        sessionStorage.setItem(cacheKey, JSON.stringify(data));
      } catch (err) {
        console.error('Failed to load summary', err);
      }
    },
    [selectedAccountId]
  );

  /**
   * Refresh all data
   */
  const refresh = useCallback(() => {
    loadInsights(true);
    loadSummary(true);
  }, [loadInsights, loadSummary]);

  /**
   * Filtered insights (excluding best practices, filtered by severity)
   */
  const filteredInsights = useMemo(() => {
    return insights.filter((insight) => {
      if (insight.category === 'BEST_PRACTICE') {
        return false;
      }
      if (currentSeverity === 'ALL') {
        return true;
      }
      return insight.severity === currentSeverity;
    });
  }, [insights, currentSeverity]);

  /**
   * Best practice insights only
   */
  const bestPracticeInsights = useMemo(() => {
    return insights.filter((insight) => insight.category === 'BEST_PRACTICE');
  }, [insights]);

  /**
   * Check if all filtered insights are selected
   */
  const isAllSelected = useMemo(() => {
    return (
      filteredInsights.length > 0 &&
      selectedInsights.length === filteredInsights.length
    );
  }, [filteredInsights, selectedInsights]);

  /**
   * Toggle selection for all insights
   */
  const toggleAllInsights = useCallback(() => {
    if (isAllSelected) {
      setSelectedInsights([]);
    } else {
      setSelectedInsights(filteredInsights.map((i) => i.id));
    }
  }, [isAllSelected, filteredInsights]);

  /**
   * Toggle selection for a single insight
   */
  const toggleInsightSelection = useCallback((id: string) => {
    setSelectedInsights((prev) =>
      prev.includes(id) ? prev.filter((i) => i !== id) : [...prev, id]
    );
  }, []);

  /**
   * Bulk archive selected insights
   */
  const bulkArchive = useCallback(async () => {
    if (selectedInsights.length === 0) return;

    const confirmed = window.confirm(
      `Are you sure you want to archive ${selectedInsights.length} insights?`
    );
    if (!confirmed) return;

    try {
      await apiClient.post('/api/xamops/metrics/insights/bulk-archive', selectedInsights);
      const count = selectedInsights.length;
      setInsights((prev) =>
        prev.filter((insight) => !selectedInsights.includes(insight.id))
      );
      setSelectedInsights([]);
      loadSummary(true);
      showToast(`${count} insights archived successfully`, 'success');
    } catch (err) {
      console.error('Failed to bulk archive insights', err);
      showToast('Failed to archive insights', 'error');
    }
  }, [selectedInsights, loadSummary, showToast]);

  /**
   * Export filtered insights to CSV
   */
  const exportData = useCallback(() => {
    setIsExporting(true);

    const headers = [
      'ID',
      'Insight',
      'Description',
      'Severity',
      'Category',
      'Account',
      'Quantity',
      'Resource Type',
      'Resource ID',
      'Recommendation',
      'Documentation URL',
      'Potential Savings',
      'Region',
      'Created Date',
    ];

    const rows = filteredInsights.map((insight) => [
      `"${insight.id || ''}"`,
      `"${(insight.insight || '').replace(/"/g, '""')}"`,
      `"${(insight.description || '').replace(/"/g, '""')}"`,
      `"${insight.severity || ''}"`,
      `"${insight.category || ''}"`,
      `"${insight.account || ''}"`,
      `"${insight.quantity || ''}"`,
      `"${insight.resourceType || ''}"`,
      `"${insight.resourceId || ''}"`,
      `"${(insight.recommendation || '').replace(/"/g, '""')}"`,
      `"${insight.documentationUrl || ''}"`,
      `"${insight.potentialSavings || 0}"`,
      `"${insight.region || ''}"`,
      `"${insight.createdDate || ''}"`,
    ]);

    const csvContent =
      'data:text/csv;charset=utf-8,' +
      headers.join(',') +
      '\n' +
      rows.map((e) => e.join(',')).join('\n');

    const encodedUri = encodeURI(csvContent);
    const link = document.createElement('a');
    link.setAttribute('href', encodedUri);
    link.setAttribute('download', 'performance_insights.csv');
    document.body.appendChild(link);
    link.click();
    link.remove();

    setIsExporting(false);
    showToast('Report exported successfully', 'success');
  }, [filteredInsights, showToast]);

  // Initialize data on mount and account change
  useEffect(() => {
    if (selectedAccountId) {
      loadInsights(false);
      loadSummary(false);
    } else {
      setIsLoading(false);
    }
  }, [selectedAccountId]); // eslint-disable-line react-hooks/exhaustive-deps

  // Clear selections when severity filter changes
  useEffect(() => {
    setSelectedInsights([]);
  }, [currentSeverity]);

  return {
    insights,
    filteredInsights,
    bestPracticeInsights,
    summary,
    isLoading,
    isRefreshing,
    isExporting,
    activeTab,
    currentSeverity,
    selectedInsights,
    toast,
    isAllSelected,
    loadInsights,
    loadSummary,
    refresh,
    exportData,
    setActiveTab,
    setCurrentSeverity,
    setSelectedInsights,
    toggleAllInsights,
    toggleInsightSelection,
    bulkArchive,
    showToast,
    hideToast,
  };
};

export default usePerformanceData;
