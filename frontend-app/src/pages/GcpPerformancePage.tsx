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
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { useTopbar } from '../contexts/TopbarContext';
import { useGcpPerformanceData } from '../hooks/useGcpPerformanceData';
import { PerformanceSeverity } from '../types/gcpPerformance';
import MetricsCards from '../components/GcpPerformance/MetricsCards';
import FilterButtons from '../components/GcpPerformance/FilterButtons';
import BulkActionBar from '../components/GcpPerformance/BulkActionBar';
import InsightsTable from '../components/GcpPerformance/InsightsTable';
import BestPracticesTable from '../components/GcpPerformance/BestPracticesTable';
import { AlertDialog } from '../components/ui';
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
          <Icon icon="mdi:download" width={14} />
          <span>Export CSV</span>
        </button>

        <button
          onClick={refresh}
          disabled={loading}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-xs font-bold rounded-xl shadow-lg shadow-indigo-200 hover:bg-indigo-700 transition-all disabled:opacity-50 active:scale-95"
        >
          <Icon icon="mdi:refresh" width={14} className={loading ? 'animate-spin' : ''} />
          <span>{loading ? 'Scanning...' : 'Refresh Insights'}</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, loading, refresh]); // Note: handleExport dependency omitted to avoid cycle, usually safe if stable

  // Empty state if no account selected
  if (!selectedAccountId) {
    return (
      <main className="flex-1 p-6 bg-white min-h-screen">
        <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }}>
          <div className="flex flex-col items-center justify-center py-20">
            <div className="w-14 h-14 rounded-xl bg-indigo-50 border border-indigo-100 flex items-center justify-center mb-4">
              <Icon icon="mdi:speedometer" width={28} color="#4f46e5" />
            </div>
            <h3 className="text-base font-bold text-gray-700 mb-1">No GCP Account Selected</h3>
            <p className="text-sm text-gray-500">Please select a GCP project from the sidebar to view performance insights.</p>
          </div>
        </motion.div>
      </main>
    );
  }

  // Error state
  if (error && !insights.length) {
    return (
      <main className="flex-1 p-6 bg-white min-h-screen">
        <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }}>
          <div className="flex flex-col items-center justify-center py-20">
            <div className="w-14 h-14 rounded-xl bg-red-50 border border-red-100 flex items-center justify-center mb-4">
              <Icon icon="mdi:alert" width={28} color="#ef4444" />
            </div>
            <h3 className="text-base font-bold text-gray-700 mb-1">Failed to Load Performance Insights</h3>
            <p className="text-sm text-gray-500 mb-4">{error.message}</p>
            <button
              onClick={refresh}
              className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white rounded-xl text-xs font-bold hover:bg-indigo-700 transition-colors active:scale-95"
            >
              <Icon icon="mdi:refresh" width={14} />
              Retry
            </button>
          </div>
        </motion.div>
      </main>
    );
  }

  return (
    <main className="flex-1 p-6 bg-white min-h-screen">
      <AlertDialog
        isOpen={pendingArchive}
        title={`Archive ${selectedInsights.length} insight${selectedInsights.length !== 1 ? 's' : ''}?`}
        description="Archived insights will no longer appear in your active list."
        confirmLabel="Archive"
        variant="warning"
        onConfirm={confirmBulkArchive}
        onCancel={() => setPendingArchive(false)}
      />

      {/* Toast Notification */}
      {notification && (
        <div className={`fixed top-4 right-4 z-50 flex items-center gap-2 px-4 py-3 rounded-xl shadow-lg text-sm font-medium ${
          notification.type === 'error' ? 'bg-red-600 text-white' : 'bg-emerald-600 text-white'
        }`}>
          <Icon
            icon={notification.type === 'error' ? 'mdi:alert-circle-outline' : 'mdi:check-circle-outline'}
            width={16}
          />
          <span>{notification.message}</span>
        </div>
      )}

      <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }} className="space-y-6">
        {/* Metrics Cards */}
        <MetricsCards summary={summary} loading={loading} />

        {/* Main Content Card */}
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
          {/* Tabs */}
          <div className="border-b border-gray-100 px-6">
            <nav className="flex gap-6">
              {[
                { key: 'insights', label: 'Critical Insights', icon: 'mdi:alert-circle-outline' },
                { key: 'best_practices', label: 'Best Practice Audits', icon: 'mdi:check-circle-outline' },
              ].map(tab => (
                <button
                  key={tab.key}
                  onClick={() => setActiveTab(tab.key as any)}
                  className={`flex items-center gap-2 py-4 px-1 text-sm font-medium border-b-2 transition-colors ${
                    activeTab === tab.key
                      ? 'border-indigo-600 text-indigo-600'
                      : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
                  }`}
                >
                  <Icon icon={tab.icon} width={14} />
                  {tab.label}
                </button>
              ))}
            </nav>
          </div>

          <AnimatePresence mode="wait">
          <motion.div key={activeTab} initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
          {/* Critical Insights Tab */}
          {activeTab === 'insights' && (
            <div className="p-6 space-y-4">
              {/* Filters and Bulk Actions */}
              <div className="flex flex-wrap justify-between items-center gap-6">
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
                <div className="flex flex-col items-center py-16">
                  <div className="w-12 h-12 bg-gray-100 rounded-xl flex items-center justify-center mb-3">
                    <Icon icon="mdi:magnify" width={24} className="text-gray-400" />
                  </div>
                  <h3 className="text-base font-bold text-gray-700 mb-1">No insights found</h3>
                  <p className="text-sm text-gray-500 mb-4">No performance insights available for the selected severity.</p>
                  <button
                    onClick={refresh}
                    className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white rounded-xl text-xs font-bold hover:bg-indigo-700 transition-colors"
                  >
                    <Icon icon="mdi:refresh" width={14} />
                    Refresh Insights
                  </button>
                </div>
              )}
            </div>
          )}

          {/* Best Practices Tab */}
          {activeTab === 'best_practices' && (
            <div className="p-6">
              <BestPracticesTable insights={bestPracticeInsights} loading={loading} />
            </div>
          )}
          </motion.div>
          </AnimatePresence>
        </div>
      </motion.div>
    </main>
  );
};

export default GcpPerformancePage;
