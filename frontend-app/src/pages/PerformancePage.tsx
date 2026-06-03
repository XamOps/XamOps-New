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
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbar } from '../contexts/TopbarContext';
import { usePerformanceData } from '../hooks/usePerformanceData';
import type { SeverityFilter } from '../types/performance';
import { AlertDialog } from '../components/ui';

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
    pendingBulkArchive,
    requestBulkArchive,
    cancelBulkArchive,
  } = usePerformanceData({ selectedAccountId });

  // Topbar Integration
  const { setActions, clearActions } = useTopbar();

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        <button
          onClick={exportData}
          disabled={isExporting}
          className="flex items-center gap-2 px-3 py-1.5 bg-white text-emerald-600 text-xs font-medium rounded-md border border-[rgba(0,0,0,0.12)] hover:shadow-sm transition-all disabled:opacity-50"
        >
          {isExporting ? (
            <Icon icon="mdi:loading" width={14} className="animate-spin" />
          ) : (
            <Icon icon="mdi:microsoft-excel" width={14} />
          )}
          <span>{isExporting ? 'Exporting...' : 'Export CSV'}</span>
        </button>

        <button
          onClick={refresh}
          disabled={isRefreshing}
          className="flex items-center gap-2 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] text-white text-[12px] font-medium rounded-md hover:bg-black transition-colors disabled:opacity-50"
        >
          <Icon icon="mdi:refresh" width={14} className={isRefreshing ? 'animate-spin' : ''} />
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

  const handleInsightSelection = (id: string) => {
    setSelectedInsights((prev) =>
      prev.includes(id) ? prev.filter((i) => i !== id) : [...prev, id]
    );
  };

  return (
    <main className="flex-1 p-6 bg-white min-h-screen">
      <AlertDialog
        isOpen={pendingBulkArchive}
        title={`Archive ${selectedInsights.length} insights?`}
        description="Archived insights will no longer appear in your active list."
        confirmLabel="Archive"
        variant="warning"
        onConfirm={bulkArchive}
        onCancel={cancelBulkArchive}
      />

      {/* Toast */}
      {toast.show && (
        <div className={`fixed top-4 right-4 z-50 flex items-center gap-2 px-4 py-3 rounded-md shadow-md text-sm font-medium ${
          toast.type === 'success' ? 'bg-emerald-600 text-white' : 'bg-red-600 text-white'
        }`}>
          <Icon icon={toast.type === 'success' ? 'mdi:check-circle-outline' : 'mdi:alert-circle-outline'} width={16} />
          {toast.message}
        </div>
      )}

      <div className="space-y-6">
        {/* No account state */}
        {!selectedAccountId && !isLoading && (
          <div className="flex flex-col items-center justify-center py-20">
            <div className="w-14 h-14 rounded-xl bg-indigo-50 border border-indigo-100 flex items-center justify-center mb-4">
              <Icon icon="mdi:chart-line" width={28} color="#4f46e5" />
            </div>
            <h3 className="text-base font-bold text-[rgba(0,0,0,0.88)] mb-1">No Account Selected</h3>
            <p className="text-sm text-[rgba(0,0,0,0.5)]">Select an account from the sidebar to view performance insights.</p>
          </div>
        )}

        {selectedAccountId && (
          <>
            {/* KPI Cards */}
            {isLoading ? (
              <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                {[1, 2, 3, 4].map(i => (
                  <div key={i} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 animate-pulse">
                    <div className="h-3 bg-[rgba(0,0,0,0.06)] rounded w-24 mb-3" />
                    <div className="h-7 bg-[rgba(0,0,0,0.06)] rounded w-16 mb-2" />
                    <div className="h-3 bg-[rgba(0,0,0,0.04)] rounded w-20" />
                  </div>
                ))}
              </div>
            ) : (
              <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                {[
                  { label: 'Performance Score', value: summary.performanceScore || 0, sub: 'Overall score', valueColor: 'rgb(10,10,10)' },
                  { label: 'Total Insights', value: summary.totalInsights || 0, sub: 'All findings', valueColor: 'rgb(10,10,10)' },
                  { label: 'Critical', value: summary.critical || 0, sub: 'Immediate attention', valueColor: 'rgb(220,38,38)' },
                  { label: 'Warnings', value: (summary.warning || 0) + (summary.weakWarning || 0), sub: 'Review needed', valueColor: 'rgb(234,88,12)' },
                ].map((c, i) => (
                  <div key={c.label} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 card-hover-lift" style={{ animation: `fadeInUp 0.4s cubic-bezier(0.22,1,0.36,1) ${i * 0.06}s both` }}>
                    <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.5)] mb-2">{c.label}</p>
                    <p style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px', color: c.valueColor, fontVariantNumeric: 'tabular-nums' }}>{c.value}</p>
                    <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-2">{c.sub}</p>
                  </div>
                ))}
              </div>
            )}

            {/* Main content card */}
            <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
              {/* Tabs */}
              <div className="border-b border-[rgba(0,0,0,0.06)] px-6">
                <nav className="flex gap-6">
                  {[
                    { key: 'insights', label: 'Critical Insights', icon: 'mdi:alert-circle-outline' },
                    { key: 'best_practices', label: 'Best Practice Audits', icon: 'mdi:check-circle-outline' },
                  ].map(tab => (
                    <button
                      key={tab.key}
                      onClick={() => setActiveTab(tab.key as any)}
                      className={`flex items-center gap-2 py-4 px-1 text-[13px] font-medium border-b-2 transition-colors ${
                        activeTab === tab.key
                          ? 'border-indigo-600 text-indigo-600'
                          : 'border-transparent text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.7)] hover:border-[rgba(0,0,0,0.2)]'
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
              {/* Insights Tab */}
              {activeTab === 'insights' && (
                <div className="p-6 space-y-4">
                  {/* Filter / Bulk action bar */}
                  <div className="flex flex-wrap items-center gap-3">
                    {selectedInsights.length === 0 ? (
                      <div className="flex flex-wrap gap-2">
                        {severityFilters.map(f => (
                          <button
                            key={f.value}
                            onClick={() => setCurrentSeverity(f.value)}
                            className={`px-3 py-1.5 text-xs font-semibold rounded-lg transition-all ${
                              currentSeverity === f.value
                                ? 'bg-[rgba(0,0,0,0.88)] text-white'
                                : 'border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.04)]'
                            }`}
                          >
                            {f.label}
                          </button>
                        ))}
                      </div>
                    ) : (
                      <div className="flex items-center gap-4 w-full bg-blue-50 border border-blue-100 rounded-lg px-4 py-2.5">
                        <div className="flex items-center gap-2">
                          <Icon icon="mdi:check-circle-outline" width={16} className="text-blue-600" />
                          <span className="text-sm font-bold text-blue-800">
                            {selectedInsights.length} insight{selectedInsights.length > 1 ? 's' : ''} selected
                          </span>
                        </div>
                        <div className="flex items-center gap-3 ml-auto">
                          <button
                            onClick={requestBulkArchive}
                            className="flex items-center gap-2 px-3 py-1.5 text-xs font-medium text-[rgba(0,0,0,0.7)] border border-[rgba(0,0,0,0.12)] rounded-md hover:bg-white transition-all"
                          >
                            <Icon icon="mdi:archive-outline" width={13} />
                            Archive Selected
                          </button>
                          <button onClick={() => setSelectedInsights([])} className="text-[rgba(0,0,0,0.3)] hover:text-[rgba(0,0,0,0.6)]">
                            <Icon icon="mdi:close" width={16} />
                          </button>
                        </div>
                      </div>
                    )}
                  </div>

                  {/* Loading */}
                  {isLoading && (
                    <div className="space-y-3">
                      {[1, 2, 3, 4, 5].map(i => (
                        <div key={i} className="flex items-center gap-4 p-4 border-b border-gray-100 animate-pulse">
                          <div className="w-5 h-5 bg-[rgba(0,0,0,0.06)] rounded" />
                          <div className="flex-1 space-y-2">
                            <div className="h-3 bg-[rgba(0,0,0,0.06)] rounded w-3/4" />
                            <div className="h-2.5 bg-[rgba(0,0,0,0.04)] rounded w-1/2" />
                          </div>
                          <div className="h-5 w-16 bg-[rgba(0,0,0,0.06)] rounded-full" />
                        </div>
                      ))}
                    </div>
                  )}

                  {/* Table */}
                  {!isLoading && filteredInsights.length > 0 && (
                    <div className="overflow-x-auto rounded-xl border border-[rgba(0,0,0,0.12)]">
                      <table className="w-full text-sm">
                        <thead className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
                          <tr>
                            <th className="px-4 py-2 w-12">
                              <input type="checkbox" onChange={toggleAllInsights} checked={isAllSelected} className="rounded cursor-pointer" />
                            </th>
                            <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-[rgba(0,0,0,0.4)]">Insight</th>
                            <th className="px-4 py-2 text-center text-xs font-bold uppercase tracking-wider text-[rgba(0,0,0,0.4)] w-20">Qty</th>
                            <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-[rgba(0,0,0,0.4)] w-48">Account</th>
                          </tr>
                        </thead>
                        <tbody>
                          {filteredInsights.map((insight, idx) => (
                            <tr
                              key={insight.id}
                              className={`border-t border-[rgba(0,0,0,0.04)] transition-colors ${
                                selectedInsights.includes(insight.id) ? 'bg-blue-50/60 border-l-2 border-l-indigo-500' : 'hover:bg-[rgba(0,0,0,0.025)]'
                              }`}
                              style={{ animation: `fadeInUp 0.3s ease ${Math.min(idx * 0.04, 0.4)}s both` }}
                            >
                              <td className="px-4 py-3">
                                <input
                                  type="checkbox"
                                  checked={selectedInsights.includes(insight.id)}
                                  onChange={() => handleInsightSelection(insight.id)}
                                  className="rounded cursor-pointer"
                                />
                              </td>
                              <td className="px-4 py-3">
                                <div className="flex items-start gap-3">
                                  <Icon
                                    icon={insight.severity === 'CRITICAL' ? 'mdi:alert' : insight.severity === 'WARNING' ? 'mdi:alert-circle-outline' : 'mdi:information-outline'}
                                    width={16}
                                    className={`mt-0.5 flex-shrink-0 ${insight.severity === 'CRITICAL' ? 'text-red-500' : insight.severity === 'WARNING' ? 'text-orange-500' : 'text-yellow-500'}`}
                                  />
                                  <div>
                                    <p className="font-medium text-[rgba(0,0,0,0.88)] text-sm">{insight.insight}</p>
                                    {insight.description && <p className="text-xs text-[rgba(0,0,0,0.5)] mt-0.5">{insight.description}</p>}
                                  </div>
                                </div>
                              </td>
                              <td className="px-4 py-3 text-center">
                                <span className="inline-flex items-center justify-center w-6 h-6 text-xs font-bold text-[rgba(0,0,0,0.7)] bg-[rgba(0,0,0,0.06)] rounded-full">{insight.quantity}</span>
                              </td>
                              <td className="px-4 py-3">
                                <span className="text-sm font-medium text-[rgba(0,0,0,0.88)]">{insight.account}</span>
                                {insight.region && <p className="text-xs text-[rgba(0,0,0,0.4)] mt-0.5">{insight.region}</p>}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}

                  {/* Empty */}
                  {!isLoading && filteredInsights.length === 0 && (
                    <div className="flex flex-col items-center py-16">
                      <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-xl flex items-center justify-center mb-3">
                        <Icon icon="mdi:magnify" width={24} className="text-[rgba(0,0,0,0.3)]" />
                      </div>
                      <h3 className="text-base font-bold text-[rgba(0,0,0,0.88)] mb-1">No insights found</h3>
                      <p className="text-sm text-[rgba(0,0,0,0.5)] mb-4">No performance insights available for the selected severity.</p>
                      <button
                        onClick={refresh}
                        className="flex items-center gap-2 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] text-white rounded-md text-[12px] font-medium hover:bg-black transition-colors"
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
                  {!isLoading && bestPracticeInsights.length > 0 && (
                    <div className="overflow-x-auto rounded-xl border border-[rgba(0,0,0,0.12)]">
                      <table className="w-full text-sm">
                        <thead className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
                          <tr>
                            <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-[rgba(0,0,0,0.4)]">Recommendation</th>
                            <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-[rgba(0,0,0,0.4)]">Resource</th>
                            <th className="px-4 py-2 text-center text-xs font-bold uppercase tracking-wider text-[rgba(0,0,0,0.4)] w-32">Savings</th>
                          </tr>
                        </thead>
                        <tbody>
                          {bestPracticeInsights.map((insight, idx) => (
                            <tr key={insight.id} className="border-t border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.025)] transition-colors" style={{ animation: `fadeInUp 0.3s ease ${Math.min(idx * 0.04, 0.4)}s both` }}>
                              <td className="px-4 py-3">
                                <p className="font-medium text-[rgba(0,0,0,0.88)] text-sm">{insight.insight}</p>
                                {insight.description && <p className="text-xs text-[rgba(0,0,0,0.5)] mt-0.5">{insight.description}</p>}
                              </td>
                              <td className="px-4 py-3">
                                <code className="text-xs font-mono text-[rgba(0,0,0,0.7)] bg-[rgba(0,0,0,0.04)] px-1.5 py-0.5 rounded">{insight.resourceId}</code>
                              </td>
                              <td className="px-4 py-3 text-center">
                                <span className="text-sm font-bold text-emerald-600">${insight.potentialSavings.toFixed(2)}</span>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}

                  {!isLoading && bestPracticeInsights.length === 0 && (
                    <div className="flex flex-col items-center py-16">
                      <div className="w-14 h-14 bg-emerald-50 border border-emerald-100 rounded-xl flex items-center justify-center mb-3">
                        <Icon icon="mdi:check-circle-outline" width={24} className="text-emerald-600" />
                      </div>
                      <h3 className="text-base font-bold text-[rgba(0,0,0,0.88)] mb-1">No Best Practice violations found!</h3>
                      <p className="text-sm text-[rgba(0,0,0,0.5)]">Your infrastructure follows AWS best practices.</p>
                    </div>
                  )}
                </div>
              )}
              </motion.div>
              </AnimatePresence>
            </div>
          </>
        )}
      </div>
    </main>
  );
};

export default PerformancePage;
