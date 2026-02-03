/**
 * EKS Security Tab - Comprehensive Security Dashboard
 *
 * Professional security monitoring with Falco runtime security, Kubescape compliance,
 * and Trivy vulnerability scanning with detailed CVE information
 */

import React, { useState } from 'react';
import RuntimeSecurityView from './RuntimeSecurityView';
import KubescapeComplianceView from './KubescapeComplianceView';

interface EksSecurityTabProps {
  eksData: any;
}

const EksSecurityTab: React.FC<EksSecurityTabProps> = ({ eksData }) => {
  const { dashboard } = eksData;
  const [selectedWorkload, setSelectedWorkload] = useState<any>(null);
  const [showVulnDetails, setShowVulnDetails] = useState(false);
  const [vulnImagePage, setVulnImagePage] = useState(1);
  const imagesPerPage = 5;

  if (!dashboard) return null;

  const trivyData = dashboard.trivy;

  // Pagination for vulnerable images
  const vulnerableImages = trivyData?.workloads?.filter((w: any) =>
    (w.critical || 0) + (w.high || 0) + (w.medium || 0) + (w.low || 0) > 0
  ) || [];

  const totalVulnPages = Math.ceil(vulnerableImages.length / imagesPerPage);
  const paginatedImages = vulnerableImages.slice(
    (vulnImagePage - 1) * imagesPerPage,
    vulnImagePage * imagesPerPage
  );

  // Generate namespace-specific colors for charts
  const generateNamespaceColors = (count: number) => {
    const baseColors = [
      '#3B82F6', '#8B5CF6', '#EC4899', '#F59E0B', '#10B981',
      '#EF4444', '#06B6D4', '#F97316', '#14B8A6', '#A855F7'
    ];
    return Array.from({ length: count }, (_, i) => baseColors[i % baseColors.length]);
  };

  return (
    <div className="space-y-8">
      {/* Runtime Security Section - Powered by Falco */}
      {dashboard?.securityMetrics && (
        <RuntimeSecurityView securityMetrics={dashboard.securityMetrics} />
      )}

      {/* Security & Compliance Section - Powered by Kubescape */}
      {dashboard?.kubescape && (
        <KubescapeComplianceView kubescapeData={dashboard.kubescape} />
      )}

      {/* Trivy Image Scanning Section */}
      {dashboard?.trivy && (
        <section className="bg-white rounded-xl shadow-sm border border-gray-200 p-6">
          {/* Header */}
          <div className="flex items-center justify-between mb-6">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 bg-purple-50 rounded-lg flex items-center justify-center">
                <i className="fas fa-shield-virus text-purple-600 text-lg"></i>
              </div>
              <div>
                <h3 className="text-xl font-bold text-gray-900">Container Vulnerability Scanning</h3>
                <p className="text-gray-600 text-sm">Trivy-powered deep security analysis</p>
              </div>
            </div>
          </div>

          {/* Summary Cards */}
          <div className="grid grid-cols-1 md:grid-cols-4 gap-4 mb-6">
            <div className="bg-white rounded-xl shadow-sm p-5 border border-gray-200">
              <div className="flex items-center justify-between mb-3">
                <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Images Scanned</span>
                <div className="w-8 h-8 rounded-lg bg-purple-100 flex items-center justify-center">
                  <i className="fas fa-layer-group text-purple-600 text-sm"></i>
                </div>
              </div>
              <div className="text-3xl font-bold text-gray-900 mb-1">
                {trivyData.summary?.uniqueImagesScanned || 0}
              </div>
              <div className="text-xs text-gray-600">
                Across {trivyData.summary?.namespacesCovered || 0} namespaces
              </div>
            </div>

            <div className="bg-white rounded-xl shadow-sm p-5 border border-gray-200">
              <div className="flex items-center justify-between mb-3">
                <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Critical</span>
                <div className="w-8 h-8 rounded-lg bg-red-100 flex items-center justify-center">
                  <i className="fas fa-radiation text-red-600 text-sm"></i>
                </div>
              </div>
              <div className="text-3xl font-bold text-gray-900 mb-1">
                {trivyData.summary?.totalCritical || 0}
              </div>
              <div className="text-xs text-red-600 font-semibold">Immediate action required</div>
            </div>

            <div className="bg-white rounded-xl shadow-sm p-5 border border-gray-200">
              <div className="flex items-center justify-between mb-3">
                <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">High</span>
                <div className="w-8 h-8 rounded-lg bg-orange-100 flex items-center justify-center">
                  <i className="fas fa-exclamation-circle text-orange-600 text-sm"></i>
                </div>
              </div>
              <div className="text-3xl font-bold text-gray-900 mb-1">
                {trivyData.summary?.totalHigh || 0}
              </div>
              <div className="text-xs text-orange-600 font-semibold">High priority fixes</div>
            </div>

            <div className="bg-white rounded-xl shadow-sm p-5 border border-gray-200">
              <div className="flex items-center justify-between mb-3">
                <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Exposed Secrets</span>
                <div className="w-8 h-8 rounded-lg bg-pink-100 flex items-center justify-center">
                  <i className="fas fa-key text-pink-600 text-sm"></i>
                </div>
              </div>
              <div className="text-3xl font-bold text-gray-900 mb-1">
                {trivyData.summary?.totalExposedSecrets || 0}
              </div>
              <div className="text-xs text-pink-600 font-semibold">Credential leak risk</div>
            </div>
          </div>

          {/* Vulnerability Severity Breakdown & Scan Summary */}
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 mb-6">
            {/* Vulnerability Severity Breakdown Bar Chart */}
            <div className="bg-white rounded-xl shadow-sm p-6 border border-gray-200">
              <h3 className="text-lg font-bold text-gray-900 mb-6 flex items-center gap-2">
                <i className="fas fa-chart-bar text-purple-600"></i>
                Vulnerability Severity Breakdown
              </h3>

              <div className="space-y-4">
                {/* Critical */}
                <div>
                  <div className="flex items-center justify-between mb-2">
                    <div className="flex items-center gap-2">
                      <div className="w-4 h-4 bg-red-600 rounded"></div>
                      <span className="text-sm font-bold text-gray-700">Critical</span>
                    </div>
                    <span className="text-sm font-black text-gray-900">{trivyData.summary?.totalCritical || 0}</span>
                  </div>
                  <div className="w-full bg-gray-200 rounded-full h-4">
                    <div
                      className="bg-gradient-to-r from-red-600 to-red-500 h-4 rounded-full transition-all duration-500 flex items-center justify-end pr-2"
                      style={{
                        width: `${Math.min(100, ((trivyData.summary?.totalCritical || 0) / Math.max(1, (trivyData.summary?.totalCritical || 0) + (trivyData.summary?.totalHigh || 0) + (trivyData.summary?.totalMedium || 0) + (trivyData.summary?.totalLow || 0))) * 100)}%`
                      }}
                    >
                      {(trivyData.summary?.totalCritical || 0) > 0 && (
                        <span className="text-xs font-bold text-white">
                          {((trivyData.summary?.totalCritical || 0) / Math.max(1, (trivyData.summary?.totalCritical || 0) + (trivyData.summary?.totalHigh || 0) + (trivyData.summary?.totalMedium || 0) + (trivyData.summary?.totalLow || 0)) * 100).toFixed(1)}%
                        </span>
                      )}
                    </div>
                  </div>
                </div>

                {/* High */}
                <div>
                  <div className="flex items-center justify-between mb-2">
                    <div className="flex items-center gap-2">
                      <div className="w-4 h-4 bg-orange-600 rounded"></div>
                      <span className="text-sm font-bold text-gray-700">High</span>
                    </div>
                    <span className="text-sm font-black text-gray-900">{trivyData.summary?.totalHigh || 0}</span>
                  </div>
                  <div className="w-full bg-gray-200 rounded-full h-4">
                    <div
                      className="bg-gradient-to-r from-orange-600 to-orange-500 h-4 rounded-full transition-all duration-500 flex items-center justify-end pr-2"
                      style={{
                        width: `${Math.min(100, ((trivyData.summary?.totalHigh || 0) / Math.max(1, (trivyData.summary?.totalCritical || 0) + (trivyData.summary?.totalHigh || 0) + (trivyData.summary?.totalMedium || 0) + (trivyData.summary?.totalLow || 0))) * 100)}%`
                      }}
                    >
                      {(trivyData.summary?.totalHigh || 0) > 0 && (
                        <span className="text-xs font-bold text-white">
                          {((trivyData.summary?.totalHigh || 0) / Math.max(1, (trivyData.summary?.totalCritical || 0) + (trivyData.summary?.totalHigh || 0) + (trivyData.summary?.totalMedium || 0) + (trivyData.summary?.totalLow || 0)) * 100).toFixed(1)}%
                        </span>
                      )}
                    </div>
                  </div>
                </div>

                {/* Medium */}
                <div>
                  <div className="flex items-center justify-between mb-2">
                    <div className="flex items-center gap-2">
                      <div className="w-4 h-4 bg-yellow-500 rounded"></div>
                      <span className="text-sm font-bold text-gray-700">Medium</span>
                    </div>
                    <span className="text-sm font-black text-gray-900">{trivyData.summary?.totalMedium || 0}</span>
                  </div>
                  <div className="w-full bg-gray-200 rounded-full h-4">
                    <div
                      className="bg-gradient-to-r from-yellow-500 to-yellow-400 h-4 rounded-full transition-all duration-500 flex items-center justify-end pr-2"
                      style={{
                        width: `${Math.min(100, ((trivyData.summary?.totalMedium || 0) / Math.max(1, (trivyData.summary?.totalCritical || 0) + (trivyData.summary?.totalHigh || 0) + (trivyData.summary?.totalMedium || 0) + (trivyData.summary?.totalLow || 0))) * 100)}%`
                      }}
                    >
                      {(trivyData.summary?.totalMedium || 0) > 0 && (
                        <span className="text-xs font-bold text-white">
                          {((trivyData.summary?.totalMedium || 0) / Math.max(1, (trivyData.summary?.totalCritical || 0) + (trivyData.summary?.totalHigh || 0) + (trivyData.summary?.totalMedium || 0) + (trivyData.summary?.totalLow || 0)) * 100).toFixed(1)}%
                        </span>
                      )}
                    </div>
                  </div>
                </div>

                {/* Low */}
                <div>
                  <div className="flex items-center justify-between mb-2">
                    <div className="flex items-center gap-2">
                      <div className="w-4 h-4 bg-blue-600 rounded"></div>
                      <span className="text-sm font-bold text-gray-700">Low</span>
                    </div>
                    <span className="text-sm font-black text-gray-900">{trivyData.summary?.totalLow || 0}</span>
                  </div>
                  <div className="w-full bg-gray-200 rounded-full h-4">
                    <div
                      className="bg-gradient-to-r from-blue-600 to-blue-500 h-4 rounded-full transition-all duration-500 flex items-center justify-end pr-2"
                      style={{
                        width: `${Math.min(100, ((trivyData.summary?.totalLow || 0) / Math.max(1, (trivyData.summary?.totalCritical || 0) + (trivyData.summary?.totalHigh || 0) + (trivyData.summary?.totalMedium || 0) + (trivyData.summary?.totalLow || 0))) * 100)}%`
                      }}
                    >
                      {(trivyData.summary?.totalLow || 0) > 0 && (
                        <span className="text-xs font-bold text-white">
                          {((trivyData.summary?.totalLow || 0) / Math.max(1, (trivyData.summary?.totalCritical || 0) + (trivyData.summary?.totalHigh || 0) + (trivyData.summary?.totalMedium || 0) + (trivyData.summary?.totalLow || 0)) * 100).toFixed(1)}%
                        </span>
                      )}
                    </div>
                  </div>
                </div>
              </div>

              <div className="mt-6 pt-4 border-t border-gray-300">
                <div className="flex items-center justify-between">
                  <span className="text-sm font-bold text-gray-700">Total Vulnerabilities</span>
                  <span className="text-2xl font-black text-gray-900">
                    {(trivyData.summary?.totalCritical || 0) + (trivyData.summary?.totalHigh || 0) + (trivyData.summary?.totalMedium || 0) + (trivyData.summary?.totalLow || 0)}
                  </span>
                </div>
              </div>
            </div>

            {/* Scan Summary Details */}
            <div className="bg-white rounded-xl shadow-sm p-6 border border-gray-200">
              <h3 className="text-lg font-bold text-gray-900 mb-6 flex items-center gap-2">
                <i className="fas fa-clipboard-list text-purple-600"></i>
                Scan Summary
              </h3>

              <div className="space-y-4">
                <div className="bg-white rounded-xl p-4 border border-gray-200">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-3">
                      <div className="w-10 h-10 bg-purple-100 rounded-lg flex items-center justify-center">
                        <i className="fas fa-images text-purple-600"></i>
                      </div>
                      <div>
                        <p className="text-xs text-gray-600 font-semibold uppercase tracking-wide">Unique Images</p>
                        <p className="text-2xl font-black text-gray-900">{trivyData.summary?.uniqueImagesScanned || 0}</p>
                      </div>
                    </div>
                    <div className="text-right">
                      <p className="text-xs text-gray-600 font-semibold">Coverage</p>
                      <p className="text-lg font-bold text-purple-600">
                        {trivyData.summary?.namespacesCovered || 0} NS
                      </p>
                    </div>
                  </div>
                </div>

                <div className="bg-white rounded-xl p-4 border border-gray-200">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-3">
                      <div className="w-10 h-10 bg-red-100 rounded-lg flex items-center justify-center">
                        <i className="fas fa-bug text-red-600"></i>
                      </div>
                      <div>
                        <p className="text-xs text-gray-600 font-semibold uppercase tracking-wide">Vulnerability Reports</p>
                        <p className="text-2xl font-black text-gray-900">{trivyData.workloads?.length || 0}</p>
                      </div>
                    </div>
                    <div className="text-right">
                      <p className="text-xs text-gray-600 font-semibold">Vulnerable</p>
                      <p className="text-lg font-bold text-red-600">
                        {vulnerableImages.length}
                      </p>
                    </div>
                  </div>
                </div>

                <div className="bg-white rounded-xl p-4 border border-gray-200">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-3">
                      <div className="w-10 h-10 bg-amber-100 rounded-lg flex items-center justify-center">
                        <i className="fas fa-exclamation-triangle text-amber-600"></i>
                      </div>
                      <div>
                        <p className="text-xs text-gray-600 font-semibold uppercase tracking-wide">Risk Score</p>
                        <p className="text-2xl font-black text-gray-900">
                          {Math.min(100, ((trivyData.summary?.totalCritical || 0) * 10 + (trivyData.summary?.totalHigh || 0) * 5))}
                        </p>
                      </div>
                    </div>
                    <div className="text-right">
                      <p className="text-xs text-gray-600 font-semibold">Priority</p>
                      <p className="text-lg font-bold text-amber-600">
                        {((trivyData.summary?.totalCritical || 0) > 0 || (trivyData.summary?.totalHigh || 0) > 5) ? 'HIGH' : 'MEDIUM'}
                      </p>
                    </div>
                  </div>
                </div>

              </div>
            </div>
          </div>

          {/* Most Vulnerable Images - Paginated (5 per page) */}
          {vulnerableImages.length > 0 && (
            <div className="bg-white rounded-xl shadow-sm p-6 border border-gray-200 mb-6">
              <div className="flex items-center justify-between mb-6">
                <h3 className="text-lg font-bold text-gray-900 flex items-center gap-2">
                  <i className="fas fa-exclamation-triangle text-red-600"></i>
                  Most Vulnerable Images
                  <span className="ml-2 px-2 py-1 bg-red-100 text-red-700 rounded-lg text-xs font-bold">
                    {vulnerableImages.length} Images
                  </span>
                </h3>

                {totalVulnPages > 1 && (
                  <div className="flex items-center gap-2">
                    <button
                      onClick={() => setVulnImagePage(p => Math.max(1, p - 1))}
                      disabled={vulnImagePage === 1}
                      className="px-3 py-1.5 border border-gray-300 rounded-lg text-sm font-semibold text-gray-700 disabled:opacity-40 disabled:cursor-not-allowed hover:bg-gray-100 transition-colors"
                    >
                      <i className="fas fa-chevron-left"></i>
                    </button>
                    <span className="text-sm font-semibold text-gray-700">
                      Page {vulnImagePage} of {totalVulnPages}
                    </span>
                    <button
                      onClick={() => setVulnImagePage(p => Math.min(totalVulnPages, p + 1))}
                      disabled={vulnImagePage === totalVulnPages}
                      className="px-3 py-1.5 border border-gray-300 rounded-lg text-sm font-semibold text-gray-700 disabled:opacity-40 disabled:cursor-not-allowed hover:bg-gray-100 transition-colors"
                    >
                      <i className="fas fa-chevron-right"></i>
                    </button>
                  </div>
                )}
              </div>

              <div className="space-y-4">
                {paginatedImages.map((workload: any, index: number) => {
                  const isExpanded = selectedWorkload && selectedWorkload.name === workload.name && showVulnDetails;

                  return (
                    <div key={index} className="space-y-2">
                      <div
                        className={`rounded-xl p-4 border transition-all cursor-pointer ${isExpanded
                          ? 'bg-purple-50 border-purple-500 shadow-md transform scale-[1.01]'
                          : 'bg-gray-50 border-gray-200 hover:border-purple-300 hover:shadow-sm'
                          }`}
                        onClick={() => {
                          if (isExpanded) {
                            setShowVulnDetails(false);
                            setSelectedWorkload(null);
                          } else {
                            setSelectedWorkload(workload);
                            setShowVulnDetails(true);
                          }
                        }}
                      >
                        <div className="flex items-center justify-between">
                          <div className="flex-1">
                            <div className="flex items-center gap-3 mb-2">
                              <div className={`w-10 h-10 rounded-lg flex items-center justify-center transition-colors ${isExpanded ? 'bg-purple-600' : 'bg-purple-100'
                                }`}>
                                <i className={`fas fa-cube ${isExpanded ? 'text-white' : 'text-purple-600'}`}></i>
                              </div>
                              <div>
                                <h5 className="text-base font-bold text-gray-900">{workload.name}</h5>
                                <p className="text-xs text-gray-600 font-mono">
                                  {workload.image}:{workload.tag || 'latest'}
                                </p>
                              </div>
                            </div>

                            <div className="flex items-center gap-3 flex-wrap">
                              <span className="inline-flex items-center gap-1 px-2 py-1 bg-blue-50 text-blue-700 rounded-lg text-xs font-semibold">
                                <i className="fas fa-cube text-xs"></i>
                                {workload.namespace}
                              </span>
                              {workload.critical > 0 && (
                                <span className="px-3 py-1 bg-red-100 text-red-700 rounded-full text-sm font-bold">
                                  {workload.critical} Critical
                                </span>
                              )}
                              {workload.high > 0 && (
                                <span className="px-3 py-1 bg-orange-100 text-orange-700 rounded-full text-sm font-bold">
                                  {workload.high} High
                                </span>
                              )}
                              {workload.medium > 0 && (
                                <span className="px-3 py-1 bg-yellow-100 text-yellow-700 rounded-full text-sm font-bold">
                                  {workload.medium} Medium
                                </span>
                              )}
                              {workload.low > 0 && (
                                <span className="px-2 py-1 bg-blue-100 text-blue-700 rounded-full text-xs font-semibold">
                                  {workload.low} Low
                                </span>
                              )}
                              <span className="text-xs text-gray-500 ml-auto flex items-center gap-1">
                                <i className="far fa-clock"></i>
                                {workload.lastScanTime ? new Date(workload.lastScanTime).toLocaleString() : 'Recently scanned'}
                              </span>
                            </div>
                          </div>

                          <div className="ml-4">
                            <button className={`w-10 h-10 rounded-lg flex items-center justify-center transition-all ${isExpanded ? 'bg-purple-600 text-white rotate-90' : 'bg-purple-100 text-purple-600 hover:bg-purple-200'
                              }`}>
                              <i className="fas fa-chevron-right"></i>
                            </button>
                          </div>
                        </div>
                      </div>

                      {/* Expanded Details - Rendered Inline */}
                      {isExpanded && (
                        <div className="ml-4 mr-1 bg-white rounded-xl border-l-4 border-purple-500 shadow-md p-6 animate-fadeIn">
                          {/* CVE Table */}
                          {workload.vulnerabilities && workload.vulnerabilities.length > 0 ? (
                            <div className="overflow-x-auto max-h-96 overflow-y-auto border border-gray-200 rounded-xl">
                              <table className="w-full text-sm">
                                <thead className="sticky top-0 bg-gray-50 text-gray-700 shadow-sm">
                                  <tr>
                                    <th className="text-left py-3 px-4 font-bold border-b">CVE ID</th>
                                    <th className="text-left py-3 px-4 font-bold border-b">Severity</th>
                                    <th className="text-left py-3 px-4 font-bold border-b">Package</th>
                                    <th className="text-left py-3 px-4 font-bold border-b">Installed</th>
                                    <th className="text-left py-3 px-4 font-bold border-b">Fixed In</th>
                                    <th className="text-left py-3 px-4 font-bold border-b">Score</th>
                                    <th className="text-left py-3 px-4 font-bold border-b">Title</th>
                                  </tr>
                                </thead>
                                <tbody>
                                  {workload.vulnerabilities.map((vuln: any, idx: number) => (
                                    <tr key={idx} className="border-b border-gray-100 hover:bg-purple-50 transition-colors">
                                      <td className="py-3 px-4">
                                        {vuln.primaryLink ? (
                                          <a
                                            href={vuln.primaryLink}
                                            target="_blank"
                                            rel="noopener noreferrer"
                                            className="text-blue-600 hover:text-blue-700 hover:underline font-mono text-xs font-bold"
                                          >
                                            {vuln.vulnerabilityId}
                                          </a>
                                        ) : (
                                          <span className="font-mono text-xs font-bold text-gray-700">
                                            {vuln.vulnerabilityId}
                                          </span>
                                        )}
                                      </td>
                                      <td className="py-3 px-4">
                                        <span className={`px-2 py-1 rounded-full text-xs font-bold ${vuln.severity === 'CRITICAL' ? 'bg-red-100 text-red-700' :
                                          vuln.severity === 'HIGH' ? 'bg-orange-100 text-orange-700' :
                                            vuln.severity === 'MEDIUM' ? 'bg-yellow-100 text-yellow-700' :
                                              'bg-blue-100 text-blue-700'
                                          }`}>
                                          {vuln.severity}
                                        </span>
                                      </td>
                                      <td className="py-3 px-4 font-mono text-xs text-gray-700">{vuln.packageName}</td>
                                      <td className="py-3 px-4 font-mono text-xs text-gray-600">{vuln.installedVersion}</td>
                                      <td className="py-3 px-4 font-mono text-xs">
                                        <span className={vuln.fixedVersion ? 'text-green-700 font-semibold' : 'text-gray-400'}>
                                          {vuln.fixedVersion || 'No fix'}
                                        </span>
                                      </td>
                                      <td className="py-3 px-4">
                                        {vuln.score ? (
                                          <span className={`font-bold ${vuln.score >= 9 ? 'text-red-600' :
                                            vuln.score >= 7 ? 'text-orange-600' :
                                              'text-gray-600'
                                            }`}>
                                            {vuln.score.toFixed(1)}
                                          </span>
                                        ) : (
                                          <span className="text-gray-400">-</span>
                                        )}
                                      </td>
                                      <td className="py-3 px-4 text-gray-700 max-w-xs truncate" title={vuln.title}>
                                        {vuln.title || '-'}
                                      </td>
                                    </tr>
                                  ))}
                                </tbody>
                              </table>
                            </div>
                          ) : (
                            <div className="text-center py-8 bg-gray-50 rounded-xl border border-gray-200">
                              <i className="fas fa-info-circle text-gray-300 text-3xl mb-2"></i>
                              <p className="text-gray-600 font-semibold">No detailed vulnerability data available</p>
                            </div>
                          )}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            </div>
          )}

          {/* Info Footer */}
          <div className="bg-purple-50 rounded-lg p-4 border border-purple-200">
            <div className="flex items-start gap-3">
              <div className="w-8 h-8 bg-purple-600 rounded-lg flex items-center justify-center flex-shrink-0">
                <i className="fas fa-info-circle text-white text-sm"></i>
              </div>
              <div>
                <h4 className="font-semibold text-gray-900 text-sm mb-1">About Container Vulnerability Scanning</h4>
                <p className="text-xs text-gray-700 leading-relaxed">
                  It provides comprehensive vulnerability scanning for container images, detecting CVEs in OS packages and application dependencies.
                  Regular scans help identify and remediate security issues before deployment. Focus on critical and high-severity vulnerabilities first for maximum security impact.
                </p>
              </div>
            </div>
          </div>
        </section>
      )}
    </div>
  );
};

export default EksSecurityTab;
