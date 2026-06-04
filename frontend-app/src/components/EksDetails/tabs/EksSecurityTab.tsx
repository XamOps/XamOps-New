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

  // Compute overall security score for hero
  const totalCritical = trivyData?.summary?.totalCritical || 0;
  const totalHigh = trivyData?.summary?.totalHigh || 0;
  const totalMedium = trivyData?.summary?.totalMedium || 0;
  const totalLow = trivyData?.summary?.totalLow || 0;
  const totalVulns = totalCritical + totalHigh + totalMedium + totalLow;
  const securityScore = Math.max(0, 100 - (totalCritical * 10 + totalHigh * 3 + totalMedium * 1));
  // securityMetrics uses totalEvents + eventsByPriority (not criticalAlerts/highAlerts)
  const falcoTotal = dashboard.securityMetrics?.totalEvents ||
    Object.values(dashboard.securityMetrics?.eventsByPriority || {})
      .reduce((sum: number, v: any) => sum + (Number(v) || 0), 0);
  const kubescapeScore = dashboard.kubescape?.complianceScore || 0;

  return (
    <div className="space-y-5">

      {/* ── KPI Strip ── */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-lg px-5 py-4">
          <div className="text-[11px] font-medium uppercase tracking-wider text-[rgba(0,0,0,0.4)] mb-1">SECURITY SCORE</div>
          <div className={`text-[26px] font-semibold leading-none ${securityScore >= 80 ? 'text-[#1a7f37]' : securityScore >= 50 ? 'text-[#9a6700]' : 'text-[#cf222e]'}`}>{securityScore}</div>
          <div className="text-[11px] text-[rgba(0,0,0,0.4)] mt-1">out of 100</div>
        </div>
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-lg px-5 py-4">
          <div className="text-[11px] font-medium uppercase tracking-wider text-[rgba(0,0,0,0.4)] mb-1">CRITICAL CVEs</div>
          <div className={`text-[26px] font-semibold leading-none ${totalCritical > 0 ? 'text-[#cf222e]' : 'text-[#1a7f37]'}`}>{totalCritical}</div>
          <div className="flex items-center gap-1.5 mt-1 flex-wrap">
            {trivyData && <>
              {totalCritical > 0 && <span className="rounded-full px-2 py-0.5 text-[11px] font-semibold border" style={{ color: '#cf222e', background: '#ffebe9', borderColor: 'rgba(255,129,130,0.4)' }}>{totalCritical} Crit</span>}
              {totalHigh > 0 && <span className="rounded-full px-2 py-0.5 text-[11px] font-semibold border" style={{ color: '#9a6700', background: '#fff8c5', borderColor: 'rgba(212,167,44,0.4)' }}>{totalHigh} High</span>}
              {totalVulns === 0 && <span className="rounded-full px-2 py-0.5 text-[11px] font-semibold border" style={{ color: '#1a7f37', background: '#dafbe1', borderColor: 'rgba(74,194,107,0.4)' }}>Clean</span>}
            </>}
          </div>
        </div>
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-lg px-5 py-4">
          <div className="text-[11px] font-medium uppercase tracking-wider text-[rgba(0,0,0,0.4)] mb-1">RUNTIME ALERTS</div>
          <div className={`text-[26px] font-semibold leading-none ${falcoTotal > 0 ? 'text-[#cf222e]' : 'text-[#1a7f37]'}`}>{falcoTotal}</div>
          <div className="text-[11px] text-[rgba(0,0,0,0.4)] mt-1">Falco events</div>
        </div>
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-lg px-5 py-4">
          <div className="text-[11px] font-medium uppercase tracking-wider text-[rgba(0,0,0,0.4)] mb-1">COMPLIANCE</div>
          <div className={`text-[26px] font-semibold leading-none ${kubescapeScore >= 80 ? 'text-[#1a7f37]' : kubescapeScore >= 50 ? 'text-[#9a6700]' : kubescapeScore > 0 ? 'text-[#cf222e]' : 'text-[rgba(0,0,0,0.4)]'}`}>
            {kubescapeScore > 0 ? `${kubescapeScore.toFixed(0)}%` : 'N/A'}
          </div>
          <div className="text-[11px] text-[rgba(0,0,0,0.4)] mt-1">Kubescape score</div>
        </div>
      </div>

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
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-lg overflow-hidden">
          <div className="px-5 py-3.5 border-b border-[rgba(0,0,0,0.06)] flex items-center justify-between">
            <span className="text-sm font-semibold text-[rgba(0,0,0,0.88)]">Container Vulnerability Scanning</span>
            <span className="text-[11px] text-[rgba(0,0,0,0.4)]">Trivy-powered analysis</span>
          </div>

          {/* Summary KPIs */}
          <div className="grid grid-cols-2 md:grid-cols-4 divide-x divide-[rgba(0,0,0,0.06)]">
            {[
              { label: 'Images Scanned', val: trivyData.summary?.uniqueImagesScanned || 0, sub: `${trivyData.summary?.namespacesCovered || 0} namespaces` },
              { label: 'Critical', val: trivyData.summary?.totalCritical || 0, sub: 'Immediate action', color: (trivyData.summary?.totalCritical || 0) > 0 ? '#cf222e' : undefined },
              { label: 'High', val: trivyData.summary?.totalHigh || 0, sub: 'High priority', color: (trivyData.summary?.totalHigh || 0) > 0 ? '#9a6700' : undefined },
              { label: 'Exposed Secrets', val: trivyData.summary?.totalExposedSecrets || 0, sub: 'Credential risk', color: (trivyData.summary?.totalExposedSecrets || 0) > 0 ? '#cf222e' : undefined },
            ].map((item, i) => (
              <div key={i} className="px-5 py-4">
                <div className="text-[11px] font-medium uppercase tracking-wider text-[rgba(0,0,0,0.4)] mb-1">{item.label}</div>
                <div className="text-[26px] font-semibold leading-none" style={{ color: item.color || 'rgba(0,0,0,0.88)' }}>{item.val}</div>
                <div className="text-[11px] text-[rgba(0,0,0,0.4)] mt-1">{item.sub}</div>
              </div>
            ))}
          </div>

          {/* Severity Breakdown */}
          <div className="px-5 py-4 border-t border-[rgba(0,0,0,0.06)]">
            <div className="text-[11px] font-medium uppercase tracking-wider text-[rgba(0,0,0,0.4)] mb-3">CVE BREAKDOWN</div>
            <div className="space-y-2">
              {[
                { label: 'Critical', val: trivyData.summary?.totalCritical || 0, color: '#cf222e' },
                { label: 'High',     val: trivyData.summary?.totalHigh || 0,     color: '#9a6700' },
                { label: 'Medium',   val: trivyData.summary?.totalMedium || 0,   color: '#0550ae' },
                { label: 'Low',      val: trivyData.summary?.totalLow || 0,      color: '#1a7f37' },
              ].map((row) => {
                const pct = totalVulns > 0 ? (row.val / totalVulns) * 100 : 0;
                return (
                  <div key={row.label} className="flex items-center gap-3">
                    <div className="w-16 text-[13px] font-semibold" style={{ color: row.color }}>{row.label}</div>
                    <div className="flex-1 h-1.5 bg-[rgba(0,0,0,0.06)] rounded-full overflow-hidden">
                      <div className="h-full rounded-full transition-all duration-700" style={{ width: `${pct}%`, background: row.color }} />
                    </div>
                    <div className="text-[11px] font-semibold text-[rgba(0,0,0,0.88)] w-8 text-right">{row.val}</div>
                    <div className="text-[11px] text-[rgba(0,0,0,0.4)] w-10">{pct.toFixed(0)}%</div>
                  </div>
                );
              })}
            </div>
            <div className="mt-3 pt-3 border-t border-[rgba(0,0,0,0.06)] flex items-center justify-between">
              <span className="text-[11px] font-medium text-[rgba(0,0,0,0.4)]">Total Vulnerabilities</span>
              <span className="text-[20px] font-semibold text-[rgba(0,0,0,0.88)]">{totalVulns}</span>
            </div>
          </div>

          {/* Most Vulnerable Images */}
          {vulnerableImages.length > 0 && (
            <div className="border-t border-[rgba(0,0,0,0.06)]">
              <div className="px-5 py-3 flex items-center justify-between">
                <span className="text-sm font-semibold text-[rgba(0,0,0,0.88)]">Most Vulnerable Images</span>
                <div className="flex items-center gap-2">
                  <span className="rounded-full px-2.5 py-0.5 text-[11px] font-semibold border"
                    style={{ color: '#cf222e', background: '#ffebe9', borderColor: 'rgba(255,129,130,0.4)' }}>
                    {vulnerableImages.length} images
                  </span>
                  {totalVulnPages > 1 && (
                    <>
                      <button onClick={() => setVulnImagePage(p => Math.max(1, p - 1))} disabled={vulnImagePage === 1}
                        className="px-2.5 py-1 text-[12px] font-semibold bg-white border border-[rgba(0,0,0,0.12)] rounded-lg hover:bg-[rgba(0,0,0,0.02)] disabled:opacity-40 text-[rgba(0,0,0,0.88)]">‹</button>
                      <span className="text-[11px] text-[rgba(0,0,0,0.4)]">{vulnImagePage}/{totalVulnPages}</span>
                      <button onClick={() => setVulnImagePage(p => Math.min(totalVulnPages, p + 1))} disabled={vulnImagePage === totalVulnPages}
                        className="px-2.5 py-1 text-[12px] font-semibold bg-white border border-[rgba(0,0,0,0.12)] rounded-lg hover:bg-[rgba(0,0,0,0.02)] disabled:opacity-40 text-[rgba(0,0,0,0.88)]">›</button>
                    </>
                  )}
                </div>
              </div>
              <div className="divide-y divide-[rgba(0,0,0,0.06)]">
                {paginatedImages.map((workload: any, index: number) => {
                  const isExpanded = selectedWorkload && selectedWorkload.name === workload.name && showVulnDetails;
                  return (
                    <div key={index}>
                      <div className={`px-5 py-3 cursor-pointer transition-colors ${isExpanded ? 'bg-[rgba(0,0,0,0.02)]' : 'hover:bg-[rgba(0,0,0,0.02)]'}`}
                        onClick={() => { if (isExpanded) { setShowVulnDetails(false); setSelectedWorkload(null); } else { setSelectedWorkload(workload); setShowVulnDetails(true); } }}>
                        <div className="flex items-center justify-between">
                          <div className="flex-1 min-w-0">
                            <div className="flex items-center gap-2 mb-1 flex-wrap">
                              <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] truncate max-w-[200px]">{workload.name}</span>
                              <span className="text-[11px] font-mono text-[rgba(0,0,0,0.5)]">{workload.namespace}</span>
                            </div>
                            <div className="flex items-center gap-1.5 flex-wrap">
                              {workload.critical > 0 && <span className="rounded-full px-2 py-0.5 text-[11px] font-semibold border" style={{ color: '#cf222e', background: '#ffebe9', borderColor: 'rgba(255,129,130,0.4)' }}>{workload.critical} Crit</span>}
                              {workload.high > 0 && <span className="rounded-full px-2 py-0.5 text-[11px] font-semibold border" style={{ color: '#9a6700', background: '#fff8c5', borderColor: 'rgba(212,167,44,0.4)' }}>{workload.high} High</span>}
                              {workload.medium > 0 && <span className="rounded-full px-2 py-0.5 text-[11px] font-semibold border" style={{ color: '#0550ae', background: '#ddf4ff', borderColor: 'rgba(84,174,255,0.4)' }}>{workload.medium} Med</span>}
                              {workload.low > 0 && <span className="rounded-full px-2 py-0.5 text-[11px] font-semibold border" style={{ color: '#1a7f37', background: '#dafbe1', borderColor: 'rgba(74,194,107,0.4)' }}>{workload.low} Low</span>}
                            </div>
                          </div>
                          <i className={`fas fa-chevron-${isExpanded ? 'up' : 'down'} text-[rgba(0,0,0,0.4)] text-[11px] ml-3`}></i>
                        </div>
                      </div>
                      {isExpanded && workload.vulnerabilities?.length > 0 && (
                        <div className="px-5 pb-4 overflow-x-auto">
                          <table className="w-full">
                            <thead>
                              <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.12)]">
                                {['CVE ID','Severity','Package','Installed','Fixed In','Score'].map(h => (
                                  <th key={h} className="px-3 py-2 text-left text-[11px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.4)]">{h}</th>
                                ))}
                              </tr>
                            </thead>
                            <tbody>
                              {workload.vulnerabilities.map((vuln: any, idx: number) => {
                                const sevStyle = vuln.severity === 'CRITICAL' ? { color: '#cf222e', bg: '#ffebe9', border: 'rgba(255,129,130,0.4)' }
                                  : vuln.severity === 'HIGH' ? { color: '#9a6700', bg: '#fff8c5', border: 'rgba(212,167,44,0.4)' }
                                  : vuln.severity === 'MEDIUM' ? { color: '#0550ae', bg: '#ddf4ff', border: 'rgba(84,174,255,0.4)' }
                                  : { color: '#1a7f37', bg: '#dafbe1', border: 'rgba(74,194,107,0.4)' };
                                return (
                                  <tr key={idx} className="border-b border-[rgba(0,0,0,0.06)] hover:bg-[rgba(0,0,0,0.02)]">
                                    <td className="px-3 py-2 text-[11px] font-mono">
                                      {vuln.primaryLink
                                        ? <a href={vuln.primaryLink} target="_blank" rel="noopener noreferrer" className="text-[rgba(0,0,0,0.88)] hover:underline">{vuln.vulnerabilityId}</a>
                                        : <span className="text-[rgba(0,0,0,0.88)]">{vuln.vulnerabilityId}</span>}
                                    </td>
                                    <td className="px-3 py-2">
                                      <span className="rounded-full px-2 py-0.5 text-[11px] font-semibold border"
                                        style={{ color: sevStyle.color, background: sevStyle.bg, borderColor: sevStyle.border }}>
                                        {vuln.severity}
                                      </span>
                                    </td>
                                    <td className="px-3 py-2 text-[11px] font-mono text-[rgba(0,0,0,0.88)]">{vuln.packageName}</td>
                                    <td className="px-3 py-2 text-[11px] font-mono text-[rgba(0,0,0,0.5)]">{vuln.installedVersion}</td>
                                    <td className="px-3 py-2 text-[11px] font-mono" style={{ color: vuln.fixedVersion ? '#1a7f37' : 'rgba(0,0,0,0.4)' }}>{vuln.fixedVersion || 'No fix'}</td>
                                    <td className="px-3 py-2 text-[11px] font-semibold" style={{ color: vuln.score >= 9 ? '#cf222e' : vuln.score >= 7 ? '#9a6700' : 'rgba(0,0,0,0.88)' }}>{vuln.score ? vuln.score.toFixed(1) : '-'}</td>
                                  </tr>
                                );
                              })}
                            </tbody>
                          </table>
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default EksSecurityTab;
