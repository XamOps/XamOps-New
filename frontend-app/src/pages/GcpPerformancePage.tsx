/**
 * GCP Performance Insights Page
 *
 * Converted from: gcp_performance.html
 *
 * Features:
 * - Performance metrics dashboard
 * - Critical insights and best practice audits
 * - Filter by severity
 * - Bulk archive functionality
 * - Export to CSV
 * - Real-time data from backend
 */

import React, { useState, useEffect, useMemo } from 'react';
import { useTopbar } from '../contexts/TopbarContext';
import { useGcpPerformanceData } from '../hooks/useGcpPerformanceData';
import { PerformanceSeverity } from '../types/gcpPerformance';
import MetricsCards from '../components/GcpPerformance/MetricsCards';
import FilterButtons from '../components/GcpPerformance/FilterButtons';
import BulkActionBar from '../components/GcpPerformance/BulkActionBar';
import InsightsTable from '../components/GcpPerformance/InsightsTable';
import BestPracticesTable from '../components/GcpPerformance/BestPracticesTable';
import clsx from 'clsx';
import { AlertDialog } from '../components/ui';
import '../styles/gcpPerformance.css';
import { useTabState } from '../hooks/useTabState';

const GcpPerformancePage: React.FC = () => {
  // Get account ID from session storage
  const [selectedAccountId, setSelectedAccountId] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useTabState<'insights' | 'best_practices'>('insights');
  const [currentSeverity, setCurrentSeverity] = useState<'ALL' | PerformanceSeverity>('ALL');
  const [selectedInsights, setSelectedInsights] = useState<string[]>([]);
  const [notification, setNotification] = useState<{ message: string; type: 'success' | 'error' } | null>(null);
  const [pendingArchive, setPendingArchive] = useState(false);

  // Fetch data using custom hook
  const { insights, summary, loading, error, refresh, archiveInsights } = useGcpPerformanceData({
    accountId: selectedAccountId,
    autoRefresh: false,
  });

  // Topbar Integration
  const { setActions, clearActions } = useTopbar();

  // Load account ID from session storage or URL params
  useEffect(() => {
    const urlParams = new URLSearchParams(window.location.search);
    const accountIdFromUrl = urlParams.get('accountIds');

    if (accountIdFromUrl) {
      setSelectedAccountId(accountIdFromUrl);
    } else {
      const storedIds = sessionStorage.getItem('selectedAccountIds');
      if (storedIds) {
        try {
          const accountIds = JSON.parse(storedIds);
          if (accountIds.length > 0) {
            setSelectedAccountId(accountIds[0]);
          }
        } catch (err) {
          console.error('Failed to parse account IDs:', err);
        }
      }
    }
  }, []);

  // Filter insights by severity and category
  const filteredInsights = useMemo(() => {
    return insights.filter((insight) => {
      // Exclude best practices from main insights tab
      if (insight.category === 'BEST_PRACTICE') {
        return false;
      }

      // Filter by severity
      if (currentSeverity === 'ALL') {
        return true;
      }

      return insight.severity === currentSeverity;
    });
  }, [insights, currentSeverity]);

  // Get best practice insights
  const bestPracticeInsights = useMemo(() => {
    return insights.filter((insight) => insight.category === 'BEST_PRACTICE');
  }, [insights]);

  // Check if all insights are selected
  const isAllSelected = useMemo(() => {
    return filteredInsights.length > 0 && selectedInsights.length === filteredInsights.length;
  }, [filteredInsights, selectedInsights]);

  // Show notification
  const showNotification = (message: string, type: 'success' | 'error' = 'success') => {
    setNotification({ message, type });
    setTimeout(() => setNotification(null), 3000);
  };

  // Toggle single insight selection
  const handleToggleInsight = (insightId: string) => {
    setSelectedInsights((prev) =>
      prev.includes(insightId) ? prev.filter((id) => id !== insightId) : [...prev, insightId]
    );
  };

  // Toggle all insights selection
  const handleToggleAll = () => {
    if (isAllSelected) {
      setSelectedInsights([]);
    } else {
      const allIds = filteredInsights.map((insight) => insight.id || `${insight.resourceId}-${insight.insight}`);
      setSelectedInsights(allIds);
    }
  };

  // Handle bulk archive
  const handleBulkArchive = () => {
    if (selectedInsights.length === 0) return;
    setPendingArchive(true);
  };

  const confirmBulkArchive = async () => {
    setPendingArchive(false);
    try {
      await archiveInsights(selectedInsights);
      showNotification(`${selectedInsights.length} insights archived successfully`, 'success');
      setSelectedInsights([]);
    } catch (err) {
      showNotification('Failed to archive insights', 'error');
    }
  };

  // Export to CSV
  const handleExport = () => {
    if (loading) {
      showNotification('Please wait for data to load before exporting.', 'error');
      return;
    }

    if (filteredInsights.length === 0) {
      showNotification('No insights to export for the current filter.', 'error');
      return;
    }

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
      `"${insight.id || `${insight.resourceId}-${insight.insight}`}"`,
      `"${(insight.insight || '').replace(/"/g, '""')}"`,
      `"${(insight.description || '').replace(/"/g, '""')}"`,
      `"${insight.severity || ''}"`,
      `"${insight.category || ''}"`,
      `"${insight.account || selectedAccountId}"`,
      `"${insight.quantity || 1}"`,
      `"${insight.resourceType || ''}"`,
      `"${insight.resourceId || ''}"`,
      `"${(insight.recommendation || '').replace(/"/g, '""')}"`,
      `"${insight.documentationUrl || ''}"`,
      `"${(insight.potentialSavings || 0).toFixed(2)}"`,
      `"${insight.region || ''}"`,
      `"${insight.createdDate || ''}"`,
    ]);

    const csvContent = 'data:text/csv;charset=utf-8,' + headers.join(',') + '\n' + rows.map((e) => e.join(',')).join('\n');

    const encodedUri = encodeURI(csvContent);
    const link = document.createElement('a');
    link.setAttribute('href', encodedUri);
    link.setAttribute('download', `gcp_performance_insights_${selectedAccountId}.csv`);
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);

    showNotification('Report exported successfully', 'success');
  };

  // Topbar Actions
  useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        <button
          onClick={handleExport}
          className="flex items-center gap-2 px-4 py-2 bg-white text-blue-600 text-xs font-bold rounded-xl border border-blue-100 shadow-sm hover:shadow-md transition-all active:scale-95"
        >
          <i className="fas fa-download"></i>
          <span>Export CSV</span>
        </button>

        <button
          onClick={refresh}
          disabled={loading}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-xs font-bold rounded-xl shadow-lg shadow-indigo-200 hover:bg-indigo-700 transition-all disabled:opacity-50 active:scale-95"
        >
          <i className={clsx('fas fa-sync-alt', loading && 'animate-spin')}></i>
          <span>{loading ? 'Scanning...' : 'Refresh Insights'}</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, loading, refresh]); // Note: handleExport dependency omitted to avoid cycle, usually safe if stable

  // Empty state if no account selected
  if (!selectedAccountId) {
    return (
      <div className="gcp-performance-content">
        <div className="gcp-empty-state">
          <div className="gcp-empty-state-icon">
            <i className="fas fa-tachometer-alt text-6xl text-blue-500"></i>
          </div>
          <h2 className="text-2xl font-bold text-gray-800 mt-4">No GCP Account Selected</h2>
          <p className="text-gray-600 mt-2">Please select a GCP project from the sidebar to view performance insights.</p>
        </div>
      </div>
    );
  }

  // Error state
  if (error && !insights.length) {
    return (
      <div className="gcp-performance-content">
        <div className="gcp-error-state">
          <div className="gcp-error-icon">
            <i className="fas fa-exclamation-triangle text-6xl text-red-500"></i>
          </div>
          <h2 className="text-2xl font-bold text-gray-800 mt-4">Failed to Load Performance Insights</h2>
          <p className="text-gray-600 mt-2">{error.message}</p>
          <button onClick={refresh} className="gcp-retry-button mt-4">
            <i className="fas fa-sync-alt mr-2"></i>Retry
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="gcp-performance-content">
      <AlertDialog
        isOpen={pendingArchive}
        title={`Archive ${selectedInsights.length} insight${selectedInsights.length !== 1 ? 's' : ''}?`}
        description="Archived insights will no longer appear in your active list."
        confirmLabel="Archive"
        variant="warning"
        onConfirm={confirmBulkArchive}
        onCancel={() => setPendingArchive(false)}
      />
      {/* Header Removed - Integrated into Topbar */}

      {/* Main Content */}
      <div className="flex-1 overflow-y-auto p-8 custom-scrollbar bg-gray-50">
        <div className="max-w-7xl mx-auto space-y-8">
          {/* Metrics Cards */}
          <MetricsCards summary={summary} loading={loading} />

          {/* Main Content Card */}
          <div className="glass-card rounded-2xl p-8">
            {/* Tabs */}
            <div className="mb-6 border-b border-gray-200">
              <nav className="flex -mb-px" aria-label="Tabs">
                <button
                  onClick={() => setActiveTab('insights')}
                  className={`tab-button w-1/2 py-4 px-1 text-center border-b-2 font-medium text-sm transition-colors ${activeTab === 'insights'
                    ? 'active border-indigo-500 text-indigo-600'
                    : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
                    }`}
                >
                  Critical Insights
                </button>
                <button
                  onClick={() => setActiveTab('best_practices')}
                  className={`tab-button w-1/2 py-4 px-1 text-center border-b-2 font-medium text-sm transition-colors ${activeTab === 'best_practices'
                    ? 'active border-indigo-500 text-indigo-600'
                    : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
                    }`}
                >
                  Best Practice Audits
                </button>
              </nav>
            </div>

            {/* Critical Insights Tab */}
            {activeTab === 'insights' && (
              <div>
                {/* Filters and Bulk Actions */}
                <div className="flex flex-wrap justify-between items-center gap-6 mb-8">
                  <FilterButtons
                    currentSeverity={currentSeverity}
                    onFilterChange={setCurrentSeverity}
                    selectedCount={selectedInsights.length}
                  />
                  <BulkActionBar
                    selectedCount={selectedInsights.length}
                    onArchive={handleBulkArchive}
                    onClear={() => setSelectedInsights([])}
                  />
                </div>

                {/* Insights Table */}
                <InsightsTable
                  insights={filteredInsights}
                  selectedInsights={selectedInsights}
                  onToggleInsight={handleToggleInsight}
                  onToggleAll={handleToggleAll}
                  isAllSelected={isAllSelected}
                  loading={loading}
                />

                {/* Empty State with Refresh */}
                {!loading && filteredInsights.length === 0 && (
                  <div className="text-center py-16">
                    <div className="w-16 h-16 bg-gray-100 rounded-full flex items-center justify-center mx-auto mb-4">
                      <i className="fas fa-search text-gray-400 text-xl"></i>
                    </div>
                    <h3 className="text-lg font-semibold text-gray-800 mb-2">No insights found</h3>
                    <p className="text-gray-600 mb-4">No performance insights available for the selected severity.</p>
                    <button
                      onClick={refresh}
                      className="px-6 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-all"
                    >
                      <i className="fas fa-sync-alt mr-2"></i>Refresh Insights
                    </button>
                  </div>
                )}
              </div>
            )}

            {/* Best Practices Tab */}
            {activeTab === 'best_practices' && (
              <BestPracticesTable insights={bestPracticeInsights} loading={loading} />
            )}
          </div>
        </div>
      </div>

      {/* Toast Notification */}
      {notification && (
        <div
          className={`toast show ${notification.type === 'error' ? 'error' : ''}`}
        >
          <span>{notification.message}</span>
        </div>
      )}
    </div>
  );
};

export default GcpPerformancePage;
