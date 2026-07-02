import React, { useEffect } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbar } from '../contexts/TopbarContext';
import { useGcpSecurityData } from '../hooks/useGcpSecurityData';
import type { GcpSeverityLevel } from '../types/gcpSecurity';
import { DialogRoot, DialogPanel } from '../components/ui';

const SEVERITY_ICON: Record<GcpSeverityLevel, string> = {
  CRITICAL: 'mdi:alert-circle',
  HIGH:     'mdi:alert',
  MEDIUM:   'mdi:minus-circle',
  LOW:      'mdi:information',
};

const SEVERITY_ICON_BG: Record<GcpSeverityLevel, string> = {
  CRITICAL: 'bg-red-500',
  HIGH:     'bg-orange-500',
  MEDIUM:   'bg-yellow-400',
  LOW:      'bg-blue-500',
};

const SEVERITY_ICON_COLOR: Record<GcpSeverityLevel, string> = {
  CRITICAL: 'text-white',
  HIGH:     'text-white',
  MEDIUM:   'text-gray-900',
  LOW:      'text-white',
};

const SEVERITY_BADGE_BG: Record<GcpSeverityLevel, string> = {
  CRITICAL: 'bg-red-500 text-white',
  HIGH:     'bg-orange-500 text-white',
  MEDIUM:   'bg-yellow-400 text-gray-900',
  LOW:      'bg-blue-500 text-white',
};

type BadgeStyle = { label?: string; bg: string; text: string; border: string; dot: string };

const IAM_RISK_CONFIG: Record<string, BadgeStyle> = {
  PUBLIC_ACCESS:     { label: 'Public access',       bg: 'bg-red-50',    text: 'text-red-700',    border: 'border-red-200',    dot: 'bg-red-500' },
  SA_PRIMITIVE_ROLE: { label: 'SA over-privileged',  bg: 'bg-orange-50', text: 'text-orange-700', border: 'border-orange-200', dot: 'bg-orange-500' },
  PRIMITIVE_ROLE:    { label: 'Primitive role',      bg: 'bg-amber-50',  text: 'text-amber-700',  border: 'border-amber-200',  dot: 'bg-amber-500' },
};

const VULN_SEVERITY_CONFIG: Record<string, BadgeStyle> = {
  CRITICAL: { bg: 'bg-red-50',    text: 'text-red-700',    border: 'border-red-200',    dot: 'bg-red-500' },
  HIGH:     { bg: 'bg-orange-50', text: 'text-orange-700', border: 'border-orange-200', dot: 'bg-orange-500' },
  MEDIUM:   { bg: 'bg-amber-50',  text: 'text-amber-700',  border: 'border-amber-200',  dot: 'bg-amber-500' },
  LOW:      { bg: 'bg-blue-50',   text: 'text-blue-700',   border: 'border-blue-200',   dot: 'bg-blue-400' },
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
                ].map((c, i) => (
                  <div key={c.label} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 card-hover-lift" style={{ animation: `fadeInUp 0.4s cubic-bezier(0.22,1,0.36,1) ${i * 0.06}s both` }}>
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
              <div className="rounded-xl overflow-hidden shadow-sm border border-[rgba(255,255,255,0.06)]">
                <button
                  onClick={() => toggleSection('iamDrift')}
                  className="w-full flex items-center justify-between px-5 py-3.5 bg-[#111111] hover:bg-[#1a1a1a] transition-colors"
                >
                  <div className="flex items-center gap-3">
                    <div className="w-7 h-7 rounded-full flex items-center justify-center bg-purple-500">
                      <Icon icon="mdi:account-shield" width={16} className="text-white" />
                    </div>
                    <span className="text-sm font-bold text-white">IAM Policy Drift</span>
                  </div>
                  <div className="flex items-center gap-3">
                    <span className="text-xs font-bold px-2.5 py-0.5 rounded-full bg-purple-500 text-white">{iamDrift.length}</span>
                    <Icon icon="mdi:chevron-down" width={16} className={`text-white/60 transition-transform duration-300 ease-in-out ${openSections.includes('iamDrift') ? 'rotate-180' : 'rotate-0'}`} />
                  </div>
                </button>
                <div style={{ display: 'grid', gridTemplateRows: openSections.includes('iamDrift') ? '1fr' : '0fr', transition: 'grid-template-rows 320ms cubic-bezier(0.4, 0, 0.2, 1)' }}>
                  <div style={{ overflow: 'hidden', minHeight: 0 }}>
                    <div style={{ opacity: openSections.includes('iamDrift') ? 1 : 0, transform: openSections.includes('iamDrift') ? 'translateY(0)' : 'translateY(-6px)', transition: 'opacity 280ms ease, transform 280ms ease' }} className="bg-white border-t border-[rgba(0,0,0,0.08)]">
                      <div className="px-5 pt-4 pb-2">
                        <p className="text-xs text-gray-500">Over-privileged or publicly-exposed IAM bindings on this project. Replace primitive roles with least-privilege predefined roles and remove public members.</p>
                      </div>
                      <div className="overflow-x-auto">
                        <table className="w-full text-sm">
                          <thead className="bg-gray-50 border-y border-gray-100">
                            <tr>
                              <th className="px-5 py-2.5 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Member</th>
                              <th className="px-5 py-2.5 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Role</th>
                              <th className="px-5 py-2.5 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Risk</th>
                            </tr>
                          </thead>
                          <tbody>
                            {iamDrift.map((drift, di) => (drift.driftDetails || []).map((d, idx) => {
                              const risk = IAM_RISK_CONFIG[d.status] ?? { label: d.status, bg: 'bg-gray-50', text: 'text-gray-600', border: 'border-gray-200', dot: 'bg-gray-400' };
                              return (
                                <tr key={`${di}-${idx}`} className="border-b border-gray-100 hover:bg-gray-50 transition-colors">
                                  <td className="px-5 py-3">
                                    <span className="text-gray-800 font-medium text-xs break-all">{d.member}</span>
                                  </td>
                                  <td className="px-5 py-3">
                                    <span className="text-xs font-mono text-gray-600 bg-gray-100 px-2 py-0.5 rounded">{d.role}</span>
                                  </td>
                                  <td className="px-5 py-3">
                                    <span className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-xs font-semibold ${risk.bg} ${risk.text} border ${risk.border}`}>
                                      <span className={`w-1.5 h-1.5 rounded-full ${risk.dot}`} />
                                      {risk.label}
                                    </span>
                                  </td>
                                </tr>
                              );
                            }))}
                          </tbody>
                        </table>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            )}

            {/* Container Vulnerabilities Section */}
            {!isLoading && vulnerabilities.length > 0 && (
              <div className="rounded-xl overflow-hidden shadow-sm border border-[rgba(255,255,255,0.06)]">
                <button
                  onClick={() => toggleSection('vulnerabilities')}
                  className="w-full flex items-center justify-between px-5 py-3.5 bg-[#111111] hover:bg-[#1a1a1a] transition-colors"
                >
                  <div className="flex items-center gap-3">
                    <div className="w-7 h-7 rounded-full flex items-center justify-center bg-cyan-500">
                      <Icon icon="mdi:package-variant" width={16} className="text-white" />
                    </div>
                    <span className="text-sm font-bold text-white">Container Vulnerabilities</span>
                  </div>
                  <div className="flex items-center gap-3">
                    <span className="text-xs font-bold px-2.5 py-0.5 rounded-full bg-cyan-500 text-white">{vulnerabilities.length}</span>
                    <Icon icon="mdi:chevron-down" width={16} className={`text-white/60 transition-transform duration-300 ease-in-out ${openSections.includes('vulnerabilities') ? 'rotate-180' : 'rotate-0'}`} />
                  </div>
                </button>
                <div style={{ display: 'grid', gridTemplateRows: openSections.includes('vulnerabilities') ? '1fr' : '0fr', transition: 'grid-template-rows 320ms cubic-bezier(0.4, 0, 0.2, 1)' }}>
                  <div style={{ overflow: 'hidden', minHeight: 0 }}>
                    <div style={{ opacity: openSections.includes('vulnerabilities') ? 1 : 0, transform: openSections.includes('vulnerabilities') ? 'translateY(0)' : 'translateY(-6px)', transition: 'opacity 280ms ease, transform 280ms ease' }} className="bg-white border-t border-[rgba(0,0,0,0.08)]">
                      <div className="px-5 pt-4 pb-2">
                        <p className="text-xs text-gray-500">CVEs detected by Artifact Analysis in container images stored in this project. Rebuild images with patched packages to remediate.</p>
                      </div>
                      <div className="overflow-x-auto">
                        <table className="w-full text-sm">
                          <thead className="bg-gray-50 border-y border-gray-100">
                            <tr>
                              <th className="px-5 py-2.5 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Severity</th>
                              <th className="px-5 py-2.5 text-left text-xs font-bold uppercase tracking-wider text-gray-500">CVE</th>
                              <th className="px-5 py-2.5 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Package</th>
                              <th className="px-5 py-2.5 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Image</th>
                            </tr>
                          </thead>
                          <tbody>
                            {vulnerabilities.map((v, idx) => {
                              const sev = VULN_SEVERITY_CONFIG[(v.severity || '').toUpperCase()] ?? { bg: 'bg-gray-50', text: 'text-gray-600', border: 'border-gray-200', dot: 'bg-gray-400' };
                              return (
                                <tr key={idx} className="border-b border-gray-100 hover:bg-gray-50 transition-colors">
                                  <td className="px-5 py-3">
                                    <span className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-xs font-semibold ${sev.bg} ${sev.text} border ${sev.border}`}>
                                      <span className={`w-1.5 h-1.5 rounded-full ${sev.dot}`} />
                                      {v.severity || '—'}
                                    </span>
                                  </td>
                                  <td className="px-5 py-3">
                                    <span className="text-xs font-mono text-gray-700">{v.cveId || '—'}</span>
                                  </td>
                                  <td className="px-5 py-3">
                                    <span className="text-xs text-gray-700">{v.packageName || '—'}{v.version ? ` (${v.version})` : ''}</span>
                                  </td>
                                  <td className="px-5 py-3">
                                    <span className="text-xs text-gray-500 break-all" title={v.imageName}>{v.imageName || '—'}</span>
                                  </td>
                                </tr>
                              );
                            })}
                          </tbody>
                        </table>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            )}

            <AnimatePresence mode="wait">
            {/* Loading State */}
            {isLoading && (
              <motion.div key="loading" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={{ duration: 0.15 }}>
                <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6 animate-pulse">
                  <div className="h-4 bg-gray-200 rounded w-40 mb-4" />
                  <div className="space-y-3">
                    {[...Array(5)].map((_, i) => <div key={i} className="h-10 bg-gray-100 rounded" />)}
                  </div>
                </div>
              </motion.div>
            )}

            {/* Empty State */}
            {!isLoading && filteredFindings.length === 0 && (
              <motion.div key="empty" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
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
              </motion.div>
            )}

            {/* Severity Sections */}
            {!isLoading && filteredFindings.length > 0 && (
              <motion.div key="findings" initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }} className="space-y-4">
            {(['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'] as GcpSeverityLevel[]).map((severity, idx) => {
              const findingsForSeverity = getFindingsBySeverity(severity);
              if (findingsForSeverity.length === 0) return null;

              return (
                <div key={severity} className="rounded-xl overflow-hidden shadow-sm border border-[rgba(255,255,255,0.06)]" style={{ animation: `fadeInUp 0.3s ease ${idx * 0.06}s both` }}>
                  <button
                    onClick={() => toggleSeverity(severity)}
                    className="w-full flex items-center justify-between px-5 py-3.5 bg-[#111111] hover:bg-[#1a1a1a] transition-colors"
                  >
                    <div className="flex items-center gap-3">
                      <div className={`w-7 h-7 rounded-full flex items-center justify-center ${SEVERITY_ICON_BG[severity]}`}>
                        <Icon icon={SEVERITY_ICON[severity]} width={16} className={SEVERITY_ICON_COLOR[severity]} />
                      </div>
                      <span className="text-sm font-bold text-white">{severity} Severity Findings</span>
                    </div>
                    <div className="flex items-center gap-3">
                      <span className={`text-xs font-bold px-2.5 py-0.5 rounded-full ${SEVERITY_BADGE_BG[severity]}`}>
                        {findingsForSeverity.length}
                      </span>
                      <Icon icon="mdi:chevron-down" width={16} className={`text-white/60 transition-transform duration-300 ease-in-out ${openSeverities.includes(severity) ? 'rotate-180' : 'rotate-0'}`} />
                    </div>
                  </button>

                  <div
                    style={{
                      display: 'grid',
                      gridTemplateRows: openSeverities.includes(severity) ? '1fr' : '0fr',
                      transition: 'grid-template-rows 320ms cubic-bezier(0.4, 0, 0.2, 1)',
                    }}
                  >
                    <div style={{ overflow: 'hidden', minHeight: 0 }}>
                      <div
                        style={{
                          opacity: openSeverities.includes(severity) ? 1 : 0,
                          transform: openSeverities.includes(severity) ? 'translateY(0)' : 'translateY(-6px)',
                          transition: 'opacity 280ms ease, transform 280ms ease',
                        }}
                        className="overflow-x-auto bg-white border-t border-[rgba(0,0,0,0.08)]"
                      >
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
                    </div>
                  </div>
                </div>
              );
            })}
              </motion.div>
            )}
            </AnimatePresence>
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
