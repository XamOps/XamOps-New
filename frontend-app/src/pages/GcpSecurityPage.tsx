import React, { useEffect } from 'react';
import { Icon } from '@iconify/react';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbar } from '../contexts/TopbarContext';
import { useGcpSecurityData } from '../hooks/useGcpSecurityData';
import type { GcpSeverityLevel } from '../types/gcpSecurity';
import { DialogRoot, DialogPanel } from '../components/ui';

const SEVERITY_HEADER: Record<GcpSeverityLevel, string> = {
  CRITICAL: 'bg-red-50 text-red-800',
  HIGH:     'bg-orange-50 text-orange-800',
  MEDIUM:   'bg-yellow-50 text-yellow-800',
  LOW:      'bg-blue-50 text-blue-800',
};

const SEVERITY_BADGE: Record<GcpSeverityLevel, string> = {
  CRITICAL: 'bg-red-100 text-red-700 border-red-200',
  HIGH:     'bg-orange-100 text-orange-700 border-orange-200',
  MEDIUM:   'bg-yellow-100 text-yellow-700 border-yellow-200',
  LOW:      'bg-blue-100 text-blue-700 border-blue-200',
};

const SEVERITY_ICON: Record<GcpSeverityLevel, string> = {
  CRITICAL: 'mdi:skull-crossbones-outline',
  HIGH:     'mdi:alert-triangle',
  MEDIUM:   'mdi:alert-circle-outline',
  LOW:      'mdi:information-outline',
};

const SEVERITY_DOT: Record<GcpSeverityLevel, string> = {
  CRITICAL: 'bg-red-500',
  HIGH:     'bg-orange-500',
  MEDIUM:   'bg-yellow-500',
  LOW:      'bg-blue-500',
};

const GcpSecurityPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const {
    filteredFindings,
    iamDrift,
    vulnerabilities,
    isLoading,
    isRefreshing,
    isExporting,
    selectedCategory,
    categories,
    severityCounts,
    openSeverities,
    openSections,
    isModalOpen,
    remediationSteps,
    loadAllData,
    exportData,
    setSelectedCategory,
    toggleSeverity,
    toggleSection,
    openRemediationModal,
    closeModal,
    getFindingsBySeverity,
  } = useGcpSecurityData({ selectedAccountId });

  const { setActions, clearActions } = useTopbar();

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        <button
          onClick={exportData}
          disabled={isExporting}
          className="flex items-center gap-2 px-4 py-2 bg-white text-emerald-600 text-xs font-bold rounded-xl border border-emerald-100 shadow-sm hover:shadow-md transition-all disabled:opacity-50"
        >
          <Icon icon={isExporting ? 'mdi:loading' : 'mdi:microsoft-excel'} width={14} className={isExporting ? 'animate-spin' : ''} />
          <span>{isExporting ? 'Exporting...' : 'Export Report'}</span>
        </button>
        <button
          onClick={() => loadAllData(true)}
          disabled={isRefreshing}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-xs font-bold rounded-xl shadow-lg shadow-indigo-200 hover:bg-indigo-700 transition-all disabled:opacity-50 active:scale-95"
        >
          <Icon icon="mdi:refresh" width={14} className={isRefreshing ? 'animate-spin' : ''} />
          <span>{isRefreshing ? 'Scanning...' : 'Refresh Scan'}</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, isRefreshing, isExporting, exportData, loadAllData]);

  return (
    <main className="flex-1 flex flex-col min-h-screen bg-gray-50">
      <div className="p-6 space-y-6">

        {/* No Account Selected */}
        {!selectedAccountId && !isLoading && (
          <div className="text-center py-24 bg-white border border-gray-200 rounded-xl shadow-sm">
            <div className="w-16 h-16 rounded-xl bg-emerald-50 border border-emerald-100 flex items-center justify-center mx-auto mb-4">
              <Icon icon="mdi:shield-half-full" width={32} color="#10b981" />
            </div>
            <h3 className="text-lg font-bold text-gray-800 mb-2">No GCP Account Selected</h3>
            <p className="text-sm text-gray-500">Please select a GCP account from the sidebar to view security findings.</p>
          </div>
        )}

        {/* KPI Cards */}
        {selectedAccountId && (
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
            {isLoading ? (
              <>
                {[...Array(4)].map((_, i) => (
                  <div key={i} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 animate-pulse">
                    <div className="h-3 bg-gray-200 rounded w-24 mb-3" />
                    <div className="h-7 bg-gray-200 rounded w-16 mb-2" />
                    <div className="h-3 bg-gray-100 rounded w-28" />
                  </div>
                ))}
              </>
            ) : (
              <>
                {[
                  { label: 'Total Findings', value: filteredFindings.length,                               sub: 'security issues detected', valueClass: 'text-gray-900' },
                  { label: 'Critical',       value: severityCounts.CRITICAL || 0,                         sub: 'immediate attention',       valueClass: 'text-red-600' },
                  { label: 'High Priority',  value: severityCounts.HIGH || 0,                             sub: 'requires action',           valueClass: 'text-orange-500' },
                  { label: 'Medium & Low',   value: (severityCounts.MEDIUM || 0) + (severityCounts.LOW || 0), sub: 'monitor closely',       valueClass: 'text-yellow-600' },
                ].map((c) => (
                  <div key={c.label} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5">
                    <p className="text-[11px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.45)] mb-2 leading-tight">{c.label}</p>
                    <p className={`text-[22px] font-bold leading-tight tabular-nums ${c.valueClass}`}>{c.value}</p>
                    <p className="text-[11px] text-gray-400 mt-1">{c.sub}</p>
                  </div>
                ))}
              </>
            )}
          </div>
        )}

        {/* Category Filter */}
        {selectedAccountId && !isLoading && filteredFindings.length > 0 && (
          <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm px-5 py-3">
            <div className="flex items-center gap-3">
              <Icon icon="mdi:filter-outline" width={16} color="#6366f1" />
              <label htmlFor="gcp-category-filter" className="text-sm font-semibold text-gray-700">
                Filter by Category:
              </label>
              <select
                id="gcp-category-filter"
                value={selectedCategory}
                onChange={(e) => setSelectedCategory(e.target.value)}
                className="flex-1 max-w-xs px-3 py-1.5 text-sm border border-gray-200 rounded-lg focus:outline-none focus:border-indigo-400 focus:ring-1 focus:ring-indigo-200 bg-white"
              >
                <option value="All">All Categories</option>
                {categories.map((category) => (
                  <option key={category} value={category}>{category}</option>
                ))}
              </select>
            </div>
          </div>
        )}

        {selectedAccountId && (
          <div className="space-y-4">

            {/* IAM Drift Section */}
            {!isLoading && iamDrift.length > 0 && (
              <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
                <button
                  onClick={() => toggleSection('iamDrift')}
                  className="w-full flex items-center justify-between px-5 py-3.5 bg-purple-50 text-purple-800 transition-colors"
                >
                  <div className="flex items-center gap-3">
                    <span className="w-2 h-2 rounded-full bg-purple-500" />
                    <Icon icon="mdi:account-shield-outline" width={15} />
                    <span className="text-sm font-bold">IAM Policy Drift</span>
                  </div>
                  <div className="flex items-center gap-3">
                    <span className="text-xs font-bold px-2 py-0.5 rounded-full bg-purple-100 text-purple-700 border border-purple-200">{iamDrift.length}</span>
                    <Icon icon="mdi:chevron-down" width={16} className={`transition-transform duration-200 ${openSections.includes('iamDrift') ? 'rotate-180' : ''}`} />
                  </div>
                </button>
                {openSections.includes('iamDrift') && (
                  <div className="p-6">
                    <p className="text-sm text-gray-500 italic">IAM Drift details would appear here.</p>
                  </div>
                )}
              </div>
            )}

            {/* Container Vulnerabilities Section */}
            {!isLoading && vulnerabilities.length > 0 && (
              <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
                <button
                  onClick={() => toggleSection('vulnerabilities')}
                  className="w-full flex items-center justify-between px-5 py-3.5 bg-cyan-50 text-cyan-800 transition-colors"
                >
                  <div className="flex items-center gap-3">
                    <span className="w-2 h-2 rounded-full bg-cyan-500" />
                    <Icon icon="mdi:package-variant-outline" width={15} />
                    <span className="text-sm font-bold">Container Vulnerabilities</span>
                  </div>
                  <div className="flex items-center gap-3">
                    <span className="text-xs font-bold px-2 py-0.5 rounded-full bg-cyan-100 text-cyan-700 border border-cyan-200">{vulnerabilities.length}</span>
                    <Icon icon="mdi:chevron-down" width={16} className={`transition-transform duration-200 ${openSections.includes('vulnerabilities') ? 'rotate-180' : ''}`} />
                  </div>
                </button>
                {openSections.includes('vulnerabilities') && (
                  <div className="p-6">
                    <p className="text-sm text-gray-500 italic">Vulnerability details would appear here.</p>
                  </div>
                )}
              </div>
            )}

            {/* Loading State */}
            {isLoading && (
              <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6 animate-pulse">
                <div className="h-4 bg-gray-200 rounded w-40 mb-4" />
                <div className="space-y-3">
                  {[...Array(5)].map((_, i) => <div key={i} className="h-10 bg-gray-100 rounded" />)}
                </div>
              </div>
            )}

            {/* Empty State */}
            {!isLoading && filteredFindings.length === 0 && (
              <div className="text-center py-20 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm">
                <div className="w-16 h-16 rounded-xl bg-emerald-50 border border-emerald-100 flex items-center justify-center mx-auto mb-4">
                  <Icon icon="mdi:shield-check-outline" width={32} color="#10b981" />
                </div>
                <h3 className="text-lg font-bold text-gray-800 mb-2">All Clear!</h3>
                <p className="text-sm text-gray-500 mb-5">No security findings detected. Your environment appears to be secure.</p>
                <button
                  onClick={() => loadAllData(true)}
                  className="inline-flex items-center gap-2 px-5 py-2 bg-indigo-600 text-white text-xs font-bold rounded-xl hover:bg-indigo-700 transition-colors"
                >
                  <Icon icon="mdi:refresh" width={14} />
                  <span>Run Security Scan</span>
                </button>
              </div>
            )}

            {/* Severity Sections */}
            {(['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'] as GcpSeverityLevel[]).map((severity) => {
              const findingsForSeverity = getFindingsBySeverity(severity);
              if (isLoading || findingsForSeverity.length === 0) return null;

              return (
                <div key={severity} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
                  <button
                    onClick={() => toggleSeverity(severity)}
                    className={`w-full flex items-center justify-between px-5 py-3.5 ${SEVERITY_HEADER[severity]} transition-colors`}
                  >
                    <div className="flex items-center gap-3">
                      <span className={`w-2 h-2 rounded-full ${SEVERITY_DOT[severity]}`} />
                      <Icon icon={SEVERITY_ICON[severity]} width={15} />
                      <span className="text-sm font-bold">{severity} Severity Findings</span>
                    </div>
                    <div className="flex items-center gap-3">
                      <span className={`text-xs font-bold px-2 py-0.5 rounded-full border ${SEVERITY_BADGE[severity]}`}>
                        {findingsForSeverity.length}
                      </span>
                      <Icon icon="mdi:chevron-down" width={16} className={`transition-transform duration-200 ${openSeverities.includes(severity) ? 'rotate-180' : ''}`} />
                    </div>
                  </button>

                  {openSeverities.includes(severity) && (
                    <div className="overflow-x-auto">
                      <table className="w-full text-sm">
                        <thead className="bg-gray-50 border-b border-gray-100">
                          <tr>
                            <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Category</th>
                            <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Description</th>
                            <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Resource</th>
                            <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Compliance</th>
                            <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Action</th>
                          </tr>
                        </thead>
                        <tbody>
                          {findingsForSeverity.map((finding, index) => (
                            <tr key={`${finding.resourceName}-${index}`} className="border-t border-gray-100 hover:bg-gray-50 transition-colors">
                              <td className="px-4 py-3">
                                <span className="text-xs font-medium text-gray-700 bg-gray-100 px-2 py-0.5 rounded">{finding.category}</span>
                              </td>
                              <td className="px-4 py-3">
                                <span className="text-xs text-gray-700 line-clamp-2">{finding.description}</span>
                              </td>
                              <td className="px-4 py-3">
                                <span className="text-xs font-medium text-gray-800 truncate max-w-[160px] block">{finding.resourceName}</span>
                              </td>
                              <td className="px-4 py-3">
                                <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-semibold bg-indigo-50 text-indigo-700 border border-indigo-200">
                                  {finding.complianceControl || 'GCP Best Practice'}
                                </span>
                              </td>
                              <td className="px-4 py-3">
                                <button
                                  onClick={() => openRemediationModal(finding)}
                                  className="px-3 py-1 text-xs font-semibold text-indigo-600 border border-indigo-200 rounded-lg hover:bg-indigo-50 transition-colors"
                                >
                                  Fix Now
                                </button>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Remediation Modal */}
      <DialogRoot isOpen={isModalOpen} onClose={closeModal} maxWidth="max-w-4xl">
        <DialogPanel
          title="Remediation Guide"
          onClose={closeModal}
          footer={
            <button
              onClick={closeModal}
              className="px-6 py-3 text-sm font-semibold text-gray-700 bg-gray-100 rounded-lg hover:bg-gray-200 transition-colors"
            >
              Close Guide
            </button>
          }
        >
          <div className="overflow-y-auto max-h-[60vh]">
            <div
              className="prose prose-sm max-w-none"
              dangerouslySetInnerHTML={{ __html: remediationSteps }}
            />
          </div>
        </DialogPanel>
      </DialogRoot>
    </main>
  );
};

export default GcpSecurityPage;
