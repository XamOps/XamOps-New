/**
 * Performance Page
 *
 * Converted from: performance.html
 *
 * This is the Performance Insights page with:
 * - Performance score and summary metrics
 * - Critical insights filtered by severity
 * - Best practice audits tab
 * - Bulk archive functionality
 * - Export to CSV functionality
 *
 * API Endpoints:
 * - GET /api/xamops/metrics/insights?accountId={accountId}&forceRefresh={boolean}
 * - GET /api/xamops/metrics/insights/summary?accountId={accountId}&forceRefresh={boolean}
 * - POST /api/xamops/metrics/insights/bulk-archive
 */

import React, { useEffect } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbar } from '../contexts/TopbarContext';
import { usePerformanceData } from '../hooks/usePerformanceData';
import type { SeverityFilter } from '../types/performance';
import Skeleton from '../components/Shared/Skeleton';
import clsx from 'clsx';
import '../styles/performance.css';

const PerformancePage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds.join(',') : null;

  const {
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
    refresh,
    exportData,
    setActiveTab,
    setCurrentSeverity,
    setSelectedInsights,
    toggleAllInsights,
    bulkArchive,
  } = usePerformanceData({ selectedAccountId });

  // Topbar Integration
  const { setActions, clearActions } = useTopbar();

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        <button
          onClick={exportData}
          disabled={isExporting}
          className="flex items-center gap-2 px-4 py-2 bg-white text-emerald-600 text-xs font-bold rounded-xl border border-emerald-100 shadow-sm hover:shadow-md transition-all disabled:opacity-50"
        >
          <i className={clsx('fas', isExporting ? 'fa-spinner fa-spin' : 'fa-file-excel')}></i>
          <span>{isExporting ? 'Exporting...' : 'Export CSV'}</span>
        </button>

        <button
          onClick={refresh}
          disabled={isRefreshing}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-xs font-bold rounded-xl shadow-lg shadow-indigo-200 hover:bg-indigo-700 transition-all disabled:opacity-50 active:scale-95"
        >
          <i className={clsx('fas fa-sync-alt', isRefreshing && 'animate-spin')}></i>
          <span>{isRefreshing ? 'Scanning...' : 'Refresh Insights'}</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, isRefreshing, isExporting, exportData, refresh]);

  const severityFilters: { value: SeverityFilter; label: string }[] = [
    { value: 'ALL', label: 'All Insights' },
    { value: 'CRITICAL', label: 'Critical' },
    { value: 'WARNING', label: 'Warning' },
    { value: 'WEAK_WARNING', label: 'Weak Warning' },
  ];

  const getSeverityIcon = (severity: string) => {
    switch (severity) {
      case 'CRITICAL':
        return 'fa-exclamation-triangle text-red-500';
      case 'WARNING':
        return 'fa-exclamation-circle text-orange-500';
      case 'WEAK_WARNING':
        return 'fa-info-circle text-yellow-500';
      default:
        return 'fa-info-circle text-gray-500';
    }
  };

  const handleInsightSelection = (id: string) => {
    setSelectedInsights((prev) =>
      prev.includes(id) ? prev.filter((i) => i !== id) : [...prev, id]
    );
  };

  return (
    <main className="performance-page">
      {/* Toast Notification */}
      <div className={`performance-toast ${toast.show ? 'show' : ''} ${toast.type}`}>
        <div className="performance-toast-content">
          <i className={`fas ${toast.type === 'success' ? 'fa-check-circle' : 'fa-exclamation-circle'}`} />
          <span>{toast.message}</span>
        </div>
      </div>

      {/* Header Removed - Integrated into Topbar */}

      <div className="performance-content">
        {/* No Account State */}
        {!selectedAccountId && !isLoading && (
          <div className="performance-no-account">
            <div className="performance-no-account-icon">
              <i className="fas fa-chart-line" />
            </div>
            <h4>No Account Selected</h4>
            <p>Please select an account from the sidebar to view performance insights.</p>
          </div>
        )}

        {/* KPI Cards */}
        {selectedAccountId && (
          <>
            {!isLoading ? (
              <div className="performance-metrics-grid">
                <div className="performance-metric-card">
                  <div className="performance-metric-content">
                    <div>
                      <h3>Performance Score</h3>
                      <p className="performance-metric-value">{summary.performanceScore || 0}</p>
                    </div>
                    <div className="performance-metric-icon bg-blue-100">
                      <i className="fas fa-tachometer-alt text-blue-600" />
                    </div>
                  </div>
                </div>
                <div className="performance-metric-card">
                  <div className="performance-metric-content">
                    <div>
                      <h3>Total Insights</h3>
                      <p className="performance-metric-value">{summary.totalInsights || 0}</p>
                    </div>
                    <div className="performance-metric-icon bg-blue-100">
                      <i className="fas fa-lightbulb text-blue-600" />
                    </div>
                  </div>
                </div>
                <div className="performance-metric-card">
                  <div className="performance-metric-content">
                    <div>
                      <h3>Critical</h3>
                      <p className="performance-metric-value text-red-600">{summary.critical || 0}</p>
                    </div>
                    <div className="performance-metric-icon bg-red-100">
                      <i className="fas fa-exclamation-triangle text-red-600" />
                    </div>
                  </div>
                </div>
                <div className="performance-metric-card">
                  <div className="performance-metric-content">
                    <div>
                      <h3>Warnings</h3>
                      <p className="performance-metric-value text-orange-600">
                        {(summary.warning || 0) + (summary.weakWarning || 0)}
                      </p>
                    </div>
                    <div className="performance-metric-icon bg-orange-100">
                      <i className="fas fa-exclamation-circle text-orange-600" />
                    </div>
                  </div>
                </div>
              </div>
            ) : (
              <div className="performance-metrics-grid">
                {[1, 2, 3, 4].map((i) => (
                  <div key={i} className="performance-metric-card">
                    <div className="performance-metric-content">
                      <div className="flex-1 space-y-2">
                        <Skeleton variant="text" height={16} width={80} />
                        <Skeleton variant="text" height={32} width={64} />
                      </div>
                      <Skeleton variant="rounded" height={48} width={48} className="rounded-xl" />
                    </div>
                  </div>
                ))}
              </div>
            )}

            {/* Main Content Card */}
            <div className="performance-card">
              {/* Tabs */}
              <div className="performance-tabs">
                <button
                  onClick={() => setActiveTab('insights')}
                  className={`performance-tab ${activeTab === 'insights' ? 'active' : ''}`}
                >
                  Critical Insights
                </button>
                <button
                  onClick={() => setActiveTab('best_practices')}
                  className={`performance-tab ${activeTab === 'best_practices' ? 'active' : ''}`}
                >
                  Best Practice Audits
                </button>
              </div>

              {/* Insights Tab Content */}
              {activeTab === 'insights' && (
                <div className="performance-tab-content">
                  {/* Filters or Bulk Actions */}
                  <div className="performance-actions-bar">
                    {selectedInsights.length === 0 ? (
                      <div className="performance-filters">
                        {severityFilters.map((filter) => (
                          <button
                            key={filter.value}
                            onClick={() => setCurrentSeverity(filter.value)}
                            className={`performance-filter-btn ${currentSeverity === filter.value ? 'active' : ''}`}
                          >
                            {filter.label}
                          </button>
                        ))}
                      </div>
                    ) : (
                      <div className="performance-bulk-actions">
                        <div className="performance-bulk-info">
                          <i className="fas fa-check-circle text-blue-600" />
                          <span>
                            {selectedInsights.length} insight{selectedInsights.length > 1 ? 's' : ''} selected
                          </span>
                        </div>
                        <div className="performance-bulk-buttons">
                          <button onClick={bulkArchive} className="performance-btn performance-btn-outline">
                            <i className="fas fa-archive" />
                            Archive Selected
                          </button>
                          <button
                            onClick={() => setSelectedInsights([])}
                            className="performance-btn-icon"
                          >
                            <i className="fas fa-times" />
                          </button>
                        </div>
                      </div>
                    )}
                  </div>

                  {/* Loading State */}
                  {isLoading && (
                    <div className="performance-skeleton-list space-y-4">
                      {[1, 2, 3, 4, 5].map((i) => (
                        <div key={i} className="performance-skeleton-row flex items-center gap-4 p-4 border-b border-gray-100">
                          <Skeleton variant="rounded" width={24} height={24} />
                          <div className="flex-1 space-y-2">
                            <Skeleton variant="text" height={16} width="75%" />
                            <Skeleton variant="text" height={12} width="50%" />
                          </div>
                          <Skeleton variant="rounded" height={24} width={64} className="rounded-full" />
                        </div>
                      ))}
                    </div>
                  )}

                  {/* Insights Table */}
                  {!isLoading && filteredInsights.length > 0 && (
                    <div className="performance-table-container">
                      <table className="performance-table">
                        <thead>
                          <tr>
                            <th className="w-12">
                              <input
                                type="checkbox"
                                onChange={toggleAllInsights}
                                checked={isAllSelected}
                                className="performance-checkbox"
                              />
                            </th>
                            <th>Insight</th>
                            <th className="text-center w-20">Qty</th>
                            <th className="w-48">Account</th>
                          </tr>
                        </thead>
                        <tbody>
                          {filteredInsights.map((insight) => (
                            <tr
                              key={insight.id}
                              className={`performance-insight-row ${selectedInsights.includes(insight.id) ? 'selected' : ''}`}
                            >
                              <td>
                                <input
                                  type="checkbox"
                                  checked={selectedInsights.includes(insight.id)}
                                  onChange={() => handleInsightSelection(insight.id)}
                                  className="performance-checkbox"
                                />
                              </td>
                              <td>
                                <div className="performance-insight-cell">
                                  <i className={`fas ${getSeverityIcon(insight.severity)}`} />
                                  <div>
                                    <p className="performance-insight-title">{insight.insight}</p>
                                    {insight.description && (
                                      <p className="performance-insight-desc">{insight.description}</p>
                                    )}
                                  </div>
                                </div>
                              </td>
                              <td className="text-center">
                                <span className="performance-quantity-badge">{insight.quantity}</span>
                              </td>
                              <td>
                                <span className="performance-account-name">{insight.account}</span>
                                {insight.region && (
                                  <p className="performance-account-region">{insight.region}</p>
                                )}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}

                  {/* Empty State */}
                  {!isLoading && filteredInsights.length === 0 && (
                    <div className="performance-empty">
                      <div className="performance-empty-icon">
                        <i className="fas fa-search" />
                      </div>
                      <h3>No insights found</h3>
                      <p>No performance insights available for the selected severity.</p>
                      <button onClick={refresh} className="performance-btn performance-btn-primary">
                        <i className="fas fa-sync-alt" />
                        Refresh Insights
                      </button>
                    </div>
                  )}
                </div>
              )}

              {/* Best Practices Tab Content */}
              {activeTab === 'best_practices' && (
                <div className="performance-tab-content">
                  {!isLoading && bestPracticeInsights.length > 0 && (
                    <div className="performance-table-container">
                      <table className="performance-table">
                        <thead>
                          <tr>
                            <th>Recommendation</th>
                            <th>Resource</th>
                            <th className="text-center w-32">Savings</th>
                          </tr>
                        </thead>
                        <tbody>
                          {bestPracticeInsights.map((insight) => (
                            <tr key={insight.id} className="performance-insight-row">
                              <td>
                                <p className="performance-insight-title">{insight.insight}</p>
                                {insight.description && (
                                  <p className="performance-insight-desc">{insight.description}</p>
                                )}
                              </td>
                              <td>
                                <code className="performance-resource-id">{insight.resourceId}</code>
                              </td>
                              <td className="text-center">
                                <span className="performance-savings">
                                  ${insight.potentialSavings.toFixed(2)}
                                </span>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}

                  {!isLoading && bestPracticeInsights.length === 0 && (
                    <div className="performance-empty">
                      <div className="performance-empty-icon success">
                        <i className="fas fa-check-circle" />
                      </div>
                      <h3>No Best Practice violations found!</h3>
                      <p>Your infrastructure follows AWS best practices.</p>
                    </div>
                  )}
                </div>
              )}
            </div>
          </>
        )}
      </div>
    </main>
  );
};

export default PerformancePage;
