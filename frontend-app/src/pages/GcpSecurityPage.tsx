/**
 * GCP Security Page
 *
 * Converted from: gcp_security.html
 *
 * This is the GCP Security Center page with:
 * - Security findings by severity (CRITICAL, HIGH, MEDIUM, LOW)
 * - Category filtering
 * - IAM Policy Drift section
 * - Container Vulnerabilities section
 * - Export functionality
 * - Real-time data refresh
 *
 * API Endpoints:
 * - GET /api/xamops/gcp/security/findings?accountId={accountId}
 * - GET /api/xamops/gcp/security/iam-drift?accountId={accountId}
 * - GET /api/xamops/gcp/security/container-vulnerabilities?accountId={accountId}
 */

import React from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useGcpSecurityData } from '../hooks/useGcpSecurityData';
import type { GcpSeverityLevel, GcpSecurityFinding } from '../types/gcpSecurity';
import '../styles/gcpSecurity.css';

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

  /**
   * Get severity header color
   */
  const getSeverityHeaderColor = (severity: GcpSeverityLevel): string => {
    const colors: Record<GcpSeverityLevel, string> = {
      CRITICAL: 'bg-gradient-to-r from-red-100 to-red-200 text-red-800',
      HIGH: 'bg-gradient-to-r from-orange-100 to-orange-200 text-orange-800',
      MEDIUM: 'bg-gradient-to-r from-yellow-100 to-yellow-200 text-yellow-800',
      LOW: 'bg-gradient-to-r from-blue-100 to-blue-200 text-blue-800',
    };
    return colors[severity] || 'bg-gradient-to-r from-gray-100 to-gray-200 text-gray-800';
  };

  /**
   * Get severity pill color
   */
  const getSeverityPillColor = (severity: GcpSeverityLevel): string => {
    const colors: Record<GcpSeverityLevel, string> = {
      CRITICAL: 'bg-red-500 text-white border-red-600',
      HIGH: 'bg-orange-500 text-white border-orange-600',
      MEDIUM: 'bg-yellow-500 text-white border-yellow-600',
      LOW: 'bg-blue-500 text-white border-blue-600',
    };
    return colors[severity] || 'bg-gray-500 text-white border-gray-600';
  };

  /**
   * Get severity icon
   */
  const getSeverityIcon = (severity: GcpSeverityLevel): string => {
    const icons: Record<GcpSeverityLevel, string> = {
      CRITICAL: 'fa-skull-crossbones',
      HIGH: 'fa-exclamation-triangle',
      MEDIUM: 'fa-exclamation-circle',
      LOW: 'fa-info-circle',
    };
    return icons[severity] || 'fa-question-circle';
  };

  /**
   * Get severity icon background
   */
  const getSeverityIconBg = (severity: GcpSeverityLevel): string => {
    const colors: Record<GcpSeverityLevel, string> = {
      CRITICAL: 'bg-red-600',
      HIGH: 'bg-orange-600',
      MEDIUM: 'bg-yellow-600',
      LOW: 'bg-blue-600',
    };
    return colors[severity] || 'bg-gray-600';
  };

  return (
    <main className="gcp-security-page">
      {/* Header */}
      <header className="header-gradient h-20 flex items-center justify-between px-8 sticky top-0 z-20 flex-shrink-0">
        <div className="flex items-center space-x-4">
          <div className="w-12 h-12 bg-gradient-to-br from-red-500 to-yellow-500 rounded-xl flex items-center justify-center">
            <i className="fas fa-shield-alt text-white text-xl"></i>
          </div>
          <div>
            <h1 className="text-2xl font-bold text-gray-800">GCP Security Center</h1>
            <p className="text-sm text-gray-600">Monitor security findings and compliance status</p>
          </div>
        </div>
        <div className="flex items-center space-x-4">
          <button
            onClick={exportData}
            disabled={isExporting}
            className="btn-success flex items-center space-x-2 px-6 py-3 text-sm font-semibold rounded-lg shadow-md"
          >
            <i className={`fas ${isExporting ? 'fa-spinner fa-spin' : 'fa-file-excel'}`}></i>
            <span>{isExporting ? 'Exporting...' : 'Export Report'}</span>
          </button>
          <button
            onClick={() => loadAllData(true)}
            disabled={isRefreshing}
            className="btn-primary flex items-center space-x-2 px-6 py-3 text-sm font-semibold rounded-lg shadow-md"
          >
            <i className={`fas fa-sync-alt ${isRefreshing ? 'fa-spin' : ''}`}></i>
            <span>{isRefreshing ? 'Scanning...' : 'Refresh Scan'}</span>
          </button>
        </div>
      </header>

      <div className="flex-1 overflow-y-auto p-8 space-y-8 custom-scrollbar bg-gray-50">
        {/* No Account Selected */}
        {!selectedAccountId && !isLoading && (
          <div className="empty-state text-center py-20 px-8 rounded-2xl">
            <div className="w-20 h-20 bg-gradient-to-br from-green-500 to-emerald-600 rounded-full flex items-center justify-center mx-auto mb-6">
              <i className="fas fa-shield-alt text-white text-3xl"></i>
            </div>
            <h3 className="text-2xl font-bold text-gray-800 mb-2">No GCP Account Selected</h3>
            <p className="text-gray-600 mb-6">
              Please select a GCP account from the sidebar to view security findings.
            </p>
          </div>
        )}

        {/* KPI Cards */}
        {selectedAccountId && (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
            {isLoading ? (
              <>
                <div className="skeleton h-36 rounded-2xl"></div>
                <div className="skeleton h-36 rounded-2xl"></div>
                <div className="skeleton h-36 rounded-2xl"></div>
                <div className="skeleton h-36 rounded-2xl"></div>
              </>
            ) : (
              <>
                <div className="security-card rounded-2xl p-6 fade-in">
                  <div className="flex justify-between items-start mb-4">
                    <div className="flex-1">
                      <h3 className="text-gray-600 text-sm font-medium mb-2">Total Findings</h3>
                      <p className="text-4xl font-bold gradient-text mb-1">
                        {filteredFindings.length}
                      </p>
                      <p className="text-xs text-gray-500">security issues detected</p>
                    </div>
                    <div className="w-14 h-14 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-xl flex items-center justify-center">
                      <i className="fas fa-search text-white text-xl"></i>
                    </div>
                  </div>
                </div>

                <div className="security-card rounded-2xl p-6 fade-in">
                  <div className="flex justify-between items-start mb-4">
                    <div className="flex-1">
                      <h3 className="text-gray-600 text-sm font-medium mb-2">Critical</h3>
                      <p className="text-4xl font-bold text-red-600 mb-1">
                        {severityCounts.CRITICAL || 0}
                      </p>
                      <p className="text-xs text-gray-500">immediate attention</p>
                    </div>
                    <div className="w-14 h-14 bg-gradient-to-br from-red-500 to-red-600 rounded-xl flex items-center justify-center">
                      <i className="fas fa-exclamation-triangle text-white text-xl"></i>
                    </div>
                  </div>
                </div>

                <div className="security-card rounded-2xl p-6 fade-in">
                  <div className="flex justify-between items-start mb-4">
                    <div className="flex-1">
                      <h3 className="text-gray-600 text-sm font-medium mb-2">High Priority</h3>
                      <p className="text-4xl font-bold text-orange-500 mb-1">
                        {severityCounts.HIGH || 0}
                      </p>
                      <p className="text-xs text-gray-500">requires action</p>
                    </div>
                    <div className="w-14 h-14 bg-gradient-to-br from-orange-500 to-orange-600 rounded-xl flex items-center justify-center">
                      <i className="fas fa-fire text-white text-xl"></i>
                    </div>
                  </div>
                </div>

                <div className="security-card rounded-2xl p-6 fade-in">
                  <div className="flex justify-between items-start mb-4">
                    <div className="flex-1">
                      <h3 className="text-gray-600 text-sm font-medium mb-2">Medium & Low</h3>
                      <p className="text-4xl font-bold text-yellow-500 mb-1">
                        {(severityCounts.MEDIUM || 0) + (severityCounts.LOW || 0)}
                      </p>
                      <p className="text-xs text-gray-500">monitor closely</p>
                    </div>
                    <div className="w-14 h-14 bg-gradient-to-br from-yellow-500 to-yellow-600 rounded-xl flex items-center justify-center">
                      <i className="fas fa-eye text-white text-xl"></i>
                    </div>
                  </div>
                </div>
              </>
            )}
          </div>
        )}

        {/* Category Filter */}
        {selectedAccountId && !isLoading && filteredFindings.length > 0 && (
          <div className="bg-white/70 backdrop-blur-sm rounded-2xl p-4 border border-gray-200/50 shadow-lg">
            <div className="flex items-center space-x-4">
              <i className="fas fa-filter text-indigo-600"></i>
              <label htmlFor="compliance-filter" className="text-sm font-semibold text-gray-700">
                Filter by Category:
              </label>
              <select
                id="compliance-filter"
                value={selectedCategory}
                onChange={(e) => setSelectedCategory(e.target.value)}
                className="form-select px-4 py-2 rounded-lg border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200"
              >
                <option value="All">All Categories</option>
                {categories.map((category) => (
                  <option key={category} value={category}>
                    {category}
                  </option>
                ))}
              </select>
            </div>
          </div>
        )}

        {/* Findings Sections */}
        {selectedAccountId && (
          <div className="space-y-6">
            {/* IAM Drift Section */}
            {!isLoading && iamDrift.length > 0 && (
              <div className="severity-section rounded-2xl shadow-lg overflow-hidden">
                <button
                  onClick={() => toggleSection('iamDrift')}
                  className="w-full flex justify-between items-center p-6 bg-gradient-to-r from-purple-100 to-purple-200 text-purple-800"
                >
                  <div className="flex items-center space-x-4">
                    <div className="w-8 h-8 rounded-lg flex items-center justify-center bg-purple-600">
                      <i className="fas fa-user-shield text-white"></i>
                    </div>
                    <h3 className="font-bold text-xl">IAM Policy Drift</h3>
                  </div>
                  <div className="flex items-center space-x-4">
                    <span className="severity-badge bg-purple-500 text-white border-purple-600">
                      {iamDrift.length}
                    </span>
                    <i
                      className={`fas fa-chevron-down text-xl transition-transform duration-200 ${openSections.includes('iamDrift') ? 'rotate-180' : ''
                        }`}
                    ></i>
                  </div>
                </button>
                {openSections.includes('iamDrift') && (
                  <div className="p-6 bg-white">
                    <p className="text-sm text-gray-500 italic">IAM Drift details would appear here.</p>
                  </div>
                )}
              </div>
            )}

            {/* Container Vulnerabilities Section */}
            {!isLoading && vulnerabilities.length > 0 && (
              <div className="severity-section rounded-2xl shadow-lg overflow-hidden">
                <button
                  onClick={() => toggleSection('vulnerabilities')}
                  className="w-full flex justify-between items-center p-6 bg-gradient-to-r from-cyan-100 to-cyan-200 text-cyan-800"
                >
                  <div className="flex items-center space-x-4">
                    <div className="w-8 h-8 rounded-lg flex items-center justify-center bg-cyan-600">
                      <i className="fas fa-box-open text-white"></i>
                    </div>
                    <h3 className="font-bold text-xl">Container Vulnerabilities</h3>
                  </div>
                  <div className="flex items-center space-x-4">
                    <span className="severity-badge bg-cyan-500 text-white border-cyan-600">
                      {vulnerabilities.length}
                    </span>
                    <i
                      className={`fas fa-chevron-down text-xl transition-transform duration-200 ${openSections.includes('vulnerabilities') ? 'rotate-180' : ''
                        }`}
                    ></i>
                  </div>
                </button>
                {openSections.includes('vulnerabilities') && (
                  <div className="p-6 bg-white">
                    <p className="text-sm text-gray-500 italic">
                      Vulnerability details would appear here.
                    </p>
                  </div>
                )}
              </div>
            )}

            {/* Loading State */}
            {isLoading && <div className="skeleton h-96 rounded-2xl"></div>}

            {/* Empty State */}
            {!isLoading && filteredFindings.length === 0 && (
              <div className="empty-state text-center py-20 px-8 rounded-2xl">
                <div className="w-20 h-20 bg-gradient-to-br from-green-500 to-emerald-600 rounded-full flex items-center justify-center mx-auto mb-6">
                  <i className="fas fa-shield-alt text-white text-3xl"></i>
                </div>
                <h3 className="text-2xl font-bold text-gray-800 mb-2">All Clear!</h3>
                <p className="text-gray-600 mb-6">
                  No security findings detected. Your environment appears to be secure.
                </p>
                <button
                  onClick={() => loadAllData(true)}
                  className="btn-primary px-6 py-3 text-sm font-semibold rounded-lg"
                >
                  <i className="fas fa-sync-alt mr-2"></i>Run Security Scan
                </button>
              </div>
            )}

            {/* Severity Sections */}
            {(['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'] as GcpSeverityLevel[]).map((severity) => {
              const findingsForSeverity = getFindingsBySeverity(severity);
              if (isLoading || findingsForSeverity.length === 0) return null;

              return (
                <div key={severity} className="fade-in">
                  <div className="severity-section rounded-2xl shadow-lg overflow-hidden">
                    <button
                      onClick={() => toggleSeverity(severity)}
                      className={`w-full flex justify-between items-center p-6 transition-all hover:bg-opacity-80 ${getSeverityHeaderColor(
                        severity
                      )}`}
                    >
                      <div className="flex items-center space-x-4">
                        <div
                          className={`w-8 h-8 rounded-lg flex items-center justify-center ${getSeverityIconBg(
                            severity
                          )}`}
                        >
                          <i className={`fas ${getSeverityIcon(severity)} text-white`}></i>
                        </div>
                        <h3 className="font-bold text-xl">{severity} Severity Findings</h3>
                      </div>
                      <div className="flex items-center space-x-4">
                        <span
                          className={`severity-badge ${getSeverityPillColor(severity)}`}
                        >
                          {findingsForSeverity.length}
                        </span>
                        <i
                          className={`fas fa-chevron-down text-xl transition-transform duration-200 ${openSeverities.includes(severity) ? 'rotate-180' : ''
                            }`}
                        ></i>
                      </div>
                    </button>

                    {openSeverities.includes(severity) && (
                      <div className="bg-white">
                        <div className="p-6">
                          <div className="table-enhanced">
                            <table>
                              <thead>
                                <tr>
                                  <th>Category</th>
                                  <th>Description</th>
                                  <th>Resource</th>
                                  <th>Compliance</th>
                                  <th>Action</th>
                                </tr>
                              </thead>
                              <tbody>
                                {findingsForSeverity.map((finding, index) => (
                                  <tr key={`${finding.resourceName}-${index}`}>
                                    <td>
                                      <div className="category-display">{finding.category}</div>
                                    </td>
                                    <td>
                                      <div className="description-text">{finding.description}</div>
                                    </td>
                                    <td>
                                      <div className="resource-info">
                                        <div className="resource-id">{finding.resourceName}</div>
                                      </div>
                                    </td>
                                    <td>
                                      <span className="compliance-badge">GCP Best Practice</span>
                                    </td>
                                    <td>
                                      <button
                                        onClick={() => openRemediationModal(finding)}
                                        className="fix-now-btn"
                                      >
                                        Fix Now
                                      </button>
                                    </td>
                                  </tr>
                                ))}
                              </tbody>
                            </table>
                          </div>
                        </div>
                      </div>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Remediation Modal */}
      {isModalOpen && (
        <div
          className="modal-overlay fixed inset-0 flex items-center justify-center z-50"
          onClick={closeModal}
        >
          <div
            className="modal-content rounded-2xl p-8 w-full max-w-4xl mx-4 max-h-[90vh] overflow-y-auto"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex justify-between items-start pb-6 border-b border-gray-200">
              <h3 className="text-2xl font-bold text-gray-800">Remediation Guide</h3>
              <button
                onClick={closeModal}
                className="text-gray-400 hover:text-gray-600 transition-colors"
              >
                <i className="fas fa-times text-2xl"></i>
              </button>
            </div>
            <div className="mt-8">
              <div
                className="remediation-steps"
                dangerouslySetInnerHTML={{ __html: remediationSteps }}
              ></div>
              <div className="flex justify-end mt-8 pt-6 border-t border-gray-200">
                <button
                  onClick={closeModal}
                  className="px-6 py-3 text-sm font-semibold text-gray-700 bg-gray-200 rounded-lg hover:bg-gray-300 transition-colors"
                >
                  Close Guide
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </main>
  );
};

export default GcpSecurityPage;
