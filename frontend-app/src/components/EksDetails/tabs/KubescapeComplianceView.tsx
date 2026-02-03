/**
 * Kubescape Security & Compliance View
 *
 * Comprehensive vulnerability scanning and compliance monitoring dashboard
 */

import React, { useState, useMemo } from 'react';

interface KubescapeData {
  totalCritical: number;
  totalHigh: number;
  totalMedium: number;
  totalLow: number;
  totalWorkloads?: number;
  namespaceBreakdown: Record<string, NamespaceVulnerabilities>;
  frameworks: FrameworkScore[];
  vulnerabilities?: KubescapeVulnerableWorkload[];
}

interface NamespaceVulnerabilities {
  critical: number;
  high: number;
  medium: number;
  low: number;
}

interface FrameworkScore {
  name: string;
  score: number;
  passedControls: number;
  failedControls: number;
}

interface KubescapeVulnerableWorkload {
  workloadName: string;
  namespace: string;
  workloadKind: string;
  criticalCount: number;
  highCount: number;
  mediumCount: number;
  lowCount: number;
}

interface KubescapeComplianceViewProps {
  kubescapeData: KubescapeData;
}

// Compliance framework descriptions and remediation guidance
const FRAMEWORK_GUIDANCE: Record<string, {
  description: string;
  commonIssues: string[];
  quickWins: string[];
  references: string[];
}> = {
  'NSA-CISA': {
    description: 'NSA and CISA Kubernetes Hardening Guidance focuses on protecting against common threat actors and attack vectors.',
    commonIssues: [
      'Pods running as root user',
      'Containers with privileged access',
      'Missing network policies',
      'Inadequate RBAC controls',
      'Unencrypted secrets'
    ],
    quickWins: [
      'Set runAsNonRoot: true in pod security context',
      'Remove privileged: true from container specs',
      'Implement default-deny network policies',
      'Enable audit logging',
      'Use secrets encryption at rest'
    ],
    references: ['NSA/CISA Kubernetes Hardening Guide v1.2', 'CISA Alert AA20-245A']
  },
  'MITRE': {
    description: 'MITRE ATT&CK framework for containers maps adversary tactics and techniques specific to containerized environments.',
    commonIssues: [
      'Exposed container runtime sockets',
      'Unrestricted container capabilities',
      'Missing security contexts',
      'Weak pod security policies',
      'Insufficient monitoring'
    ],
    quickWins: [
      'Remove docker.sock mounts',
      'Drop unnecessary Linux capabilities',
      'Implement Pod Security Admission',
      'Enable runtime security monitoring',
      'Implement behavioral analysis'
    ],
    references: ['MITRE ATT&CK for Containers', 'ATT&CK Matrix v12']
  },
  'CIS Kubernetes Benchmark': {
    description: 'CIS Kubernetes Benchmark provides prescriptive guidance for establishing a secure configuration posture.',
    commonIssues: [
      'Default service accounts in use',
      'Anonymous auth enabled',
      'Insecure API server settings',
      'Missing admission controllers',
      'Weak authentication mechanisms'
    ],
    quickWins: [
      'Disable anonymous-auth on API server',
      'Create dedicated service accounts',
      'Enable PodSecurity admission controller',
      'Implement strong authentication',
      'Regular security audits'
    ],
    references: ['CIS Kubernetes Benchmark v1.7', 'CIS Controls v8']
  }
};

const KubescapeComplianceView: React.FC<KubescapeComplianceViewProps> = ({ kubescapeData }) => {
  const [selectedFramework, setSelectedFramework] = useState<string | null>(null);
  const [workloadPage, setWorkloadPage] = useState(1);
  const [selectedSeverity, setSelectedSeverity] = useState<string>('all');
  const workloadsPerPage = 10;

  const totalVulnerabilities = kubescapeData.totalCritical + kubescapeData.totalHigh +
    kubescapeData.totalMedium + kubescapeData.totalLow;

  // Vulnerability distribution data
  const vulnDistribution = [
    { label: 'Critical', value: kubescapeData.totalCritical, color: '#DC2626', bg: 'bg-red-600' },
    { label: 'High', value: kubescapeData.totalHigh, color: '#EA580C', bg: 'bg-orange-600' },
    { label: 'Medium', value: kubescapeData.totalMedium, color: '#F59E0B', bg: 'bg-amber-500' },
    { label: 'Low', value: kubescapeData.totalLow, color: '#3B82F6', bg: 'bg-blue-600' }
  ].filter(v => v.value > 0);

  // Namespace vulnerability breakdown
  const namespaceVulns = useMemo(() => {
    return Object.entries(kubescapeData.namespaceBreakdown || {})
      .map(([name, vulns]) => ({
        name,
        total: vulns.critical + vulns.high + vulns.medium + vulns.low,
        critical: vulns.critical,
        high: vulns.high,
        medium: vulns.medium,
        low: vulns.low
      }))
      .sort((a, b) => b.total - a.total)
      .slice(0, 5);
  }, [kubescapeData.namespaceBreakdown]);

  // Filter workloads by severity
  const filteredWorkloads = useMemo(() => {
    let workloads = kubescapeData.vulnerabilities || [];

    if (selectedSeverity === 'critical') {
      workloads = workloads.filter(w => w.criticalCount > 0);
    } else if (selectedSeverity === 'high') {
      workloads = workloads.filter(w => w.highCount > 0);
    } else if (selectedSeverity === 'medium') {
      workloads = workloads.filter(w => w.mediumCount > 0);
    }

    return workloads.sort((a, b) => {
      const scoreA = a.criticalCount * 10 + a.highCount * 5 + a.mediumCount * 2 + a.lowCount;
      const scoreB = b.criticalCount * 10 + b.highCount * 5 + b.mediumCount * 2 + b.lowCount;
      return scoreB - scoreA;
    });
  }, [kubescapeData.vulnerabilities, selectedSeverity]);

  // Pagination for workloads
  const totalWorkloadPages = Math.ceil(filteredWorkloads.length / workloadsPerPage);
  const paginatedWorkloads = filteredWorkloads.slice(
    (workloadPage - 1) * workloadsPerPage,
    workloadPage * workloadsPerPage
  );

  // Overall security score
  const overallScore = useMemo(() => {
    if (!kubescapeData.frameworks || kubescapeData.frameworks.length === 0) return 0;
    const avgScore = kubescapeData.frameworks.reduce((sum, f) => sum + f.score, 0) / kubescapeData.frameworks.length;
    return Math.round(avgScore);
  }, [kubescapeData.frameworks]);

  const getScoreColor = (score: number) => {
    if (score >= 80) return { text: 'text-green-600', bg: 'bg-green-100', border: 'border-green-200' };
    if (score >= 60) return { text: 'text-amber-600', bg: 'bg-amber-100', border: 'border-amber-200' };
    return { text: 'text-red-600', bg: 'bg-red-100', border: 'border-red-200' };
  };

  const scoreColors = getScoreColor(overallScore);

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="bg-white rounded-xl shadow-sm p-6 border border-gray-200">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-lg bg-purple-50 flex items-center justify-center">
              <i className="fas fa-shield-alt text-purple-600 text-lg"></i>
            </div>
            <div>
              <h3 className="text-xl font-bold text-gray-900">Security & Compliance</h3>
              <p className="text-sm text-gray-600">Kubescape vulnerability scanning and compliance monitoring</p>
            </div>
          </div>

          <div className={`px-4 py-2 rounded-lg border flex items-center gap-2 ${scoreColors.bg} ${scoreColors.border}`}>
            <span className={`font-bold text-2xl ${scoreColors.text}`}>{overallScore}%</span>
            <span className="text-sm text-gray-600">Overall Score</span>
          </div>
        </div>
      </div>

      {/* Vulnerability Summary Cards */}
      <div className="grid grid-cols-1 md:grid-cols-5 gap-4">
        <div className="bg-white rounded-xl shadow-sm p-5 border border-gray-200">
          <div className="flex items-center justify-between mb-3">
            <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Total Issues</span>
            <div className="w-8 h-8 rounded-lg bg-gray-100 flex items-center justify-center">
              <i className="fas fa-exclamation-circle text-gray-600 text-sm"></i>
            </div>
          </div>
          <div className="text-3xl font-bold text-gray-900 mb-1">{totalVulnerabilities}</div>
          <div className="text-xs text-gray-600">Across {kubescapeData.totalWorkloads || 0} workloads</div>
        </div>

        <div className="bg-white rounded-xl shadow-sm p-5 border border-gray-200">
          <div className="flex items-center justify-between mb-3">
            <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Critical</span>
            <div className="w-8 h-8 rounded-lg bg-red-100 flex items-center justify-center">
              <i className="fas fa-skull-crossbones text-red-600 text-sm"></i>
            </div>
          </div>
          <div className="text-3xl font-bold text-gray-900 mb-1">{kubescapeData.totalCritical}</div>
          <div className="text-xs text-red-600 font-semibold">Fix immediately</div>
        </div>

        <div className="bg-white rounded-xl shadow-sm p-5 border border-gray-200">
          <div className="flex items-center justify-between mb-3">
            <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">High</span>
            <div className="w-8 h-8 rounded-lg bg-orange-100 flex items-center justify-center">
              <i className="fas fa-exclamation-triangle text-orange-600 text-sm"></i>
            </div>
          </div>
          <div className="text-3xl font-bold text-gray-900 mb-1">{kubescapeData.totalHigh}</div>
          <div className="text-xs text-orange-600 font-semibold">Priority fixes</div>
        </div>

        <div className="bg-white rounded-xl shadow-sm p-5 border border-gray-200">
          <div className="flex items-center justify-between mb-3">
            <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Medium</span>
            <div className="w-8 h-8 rounded-lg bg-amber-100 flex items-center justify-center">
              <i className="fas fa-exclamation text-amber-600 text-sm"></i>
            </div>
          </div>
          <div className="text-3xl font-bold text-gray-900 mb-1">{kubescapeData.totalMedium}</div>
          <div className="text-xs text-amber-600 font-semibold">Plan remediation</div>
        </div>

        <div className="bg-white rounded-xl shadow-sm p-5 border border-gray-200">
          <div className="flex items-center justify-between mb-3">
            <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Low</span>
            <div className="w-8 h-8 rounded-lg bg-blue-100 flex items-center justify-center">
              <i className="fas fa-info-circle text-blue-600 text-sm"></i>
            </div>
          </div>
          <div className="text-3xl font-bold text-gray-900 mb-1">{kubescapeData.totalLow}</div>
          <div className="text-xs text-blue-600 font-semibold">Monitor</div>
        </div>
      </div>

      {/* Compliance Frameworks - Moved here after summary cards */}
      {kubescapeData.frameworks && kubescapeData.frameworks.length > 0 && (
        <div className="bg-white rounded-xl shadow-sm p-6 border border-gray-200">
          <h3 className="text-lg font-bold text-gray-900 mb-6 flex items-center gap-2">
            <i className="fas fa-certificate text-indigo-600"></i>
            Compliance Framework Status
          </h3>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            {kubescapeData.frameworks.map((framework, index) => {
              const colors = getScoreColor(framework.score);
              const isSelected = selectedFramework === framework.name;

              return (
                <div
                  key={index}
                  className={`border-2 rounded-lg p-5 cursor-pointer transition-all ${isSelected ? `${colors.border} shadow-md` : 'border-gray-200 hover:border-gray-300'
                    }`}
                  onClick={() => setSelectedFramework(isSelected ? null : framework.name)}
                >
                  <div className="flex items-center justify-between mb-4">
                    <h4 className="font-bold text-gray-900 text-base">{framework.name}</h4>
                    <div className={`px-3 py-1 rounded-lg text-lg font-bold ${colors.bg} ${colors.text}`}>
                      {framework.score.toFixed(0)}%
                    </div>
                  </div>

                  <div className="w-full bg-gray-200 rounded-full h-2.5 mb-4">
                    <div
                      className={`h-2.5 rounded-full transition-all duration-500 ${framework.score >= 80 ? 'bg-green-600' :
                          framework.score >= 60 ? 'bg-amber-500' : 'bg-red-600'
                        }`}
                      style={{ width: `${framework.score}%` }}
                    ></div>
                  </div>

                  <div className="flex items-center justify-between text-sm">
                    <span className="flex items-center gap-1 text-green-600">
                      <i className="fas fa-check-circle"></i>
                      <span className="font-semibold">{framework.passedControls} passed</span>
                    </span>
                    <span className="flex items-center gap-1 text-red-600">
                      <i className="fas fa-times-circle"></i>
                      <span className="font-semibold">{framework.failedControls} failed</span>
                    </span>
                  </div>

                  {isSelected && FRAMEWORK_GUIDANCE[framework.name] && (
                    <div className="mt-4 pt-4 border-t border-gray-200 space-y-3">
                      <p className="text-xs text-gray-700 leading-relaxed">
                        {FRAMEWORK_GUIDANCE[framework.name].description}
                      </p>

                      <div>
                        <h5 className="text-xs font-semibold text-gray-900 mb-2">Common Issues:</h5>
                        <ul className="space-y-1">
                          {FRAMEWORK_GUIDANCE[framework.name].commonIssues.slice(0, 3).map((issue, idx) => (
                            <li key={idx} className="text-xs text-gray-700 flex items-start gap-2">
                              <i className="fas fa-exclamation-circle text-red-500 mt-0.5 flex-shrink-0"></i>
                              <span>{issue}</span>
                            </li>
                          ))}
                        </ul>
                      </div>

                      <div>
                        <h5 className="text-xs font-semibold text-gray-900 mb-2">Quick Wins:</h5>
                        <ul className="space-y-1">
                          {FRAMEWORK_GUIDANCE[framework.name].quickWins.slice(0, 3).map((win, idx) => (
                            <li key={idx} className="text-xs text-gray-700 flex items-start gap-2">
                              <i className="fas fa-check text-green-600 mt-0.5 flex-shrink-0"></i>
                              <span>{win}</span>
                            </li>
                          ))}
                        </ul>
                      </div>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* Vulnerability Distribution & Top Namespaces */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Vulnerability Distribution */}
        <div className="bg-white rounded-xl shadow-sm p-6 border border-gray-200">
          <h3 className="text-lg font-bold text-gray-900 mb-6 flex items-center gap-2">
            <i className="fas fa-chart-bar text-purple-600"></i>
            Vulnerability Distribution
          </h3>

          {vulnDistribution.length > 0 ? (
            <div className="space-y-4">
              {vulnDistribution.map((item, index) => {
                const percentage = ((item.value / totalVulnerabilities) * 100).toFixed(1);

                return (
                  <div key={index}>
                    <div className="flex items-center justify-between mb-2">
                      <span className="text-sm font-semibold text-gray-700">{item.label}</span>
                      <span className="text-sm font-bold text-gray-900">{item.value} ({percentage}%)</span>
                    </div>
                    <div className="w-full bg-gray-200 rounded-full h-3">
                      <div
                        className={`h-3 rounded-full transition-all duration-500 ${item.bg}`}
                        style={{ width: `${percentage}%` }}
                      ></div>
                    </div>
                  </div>
                );
              })}
            </div>
          ) : (
            <div className="text-center py-8">
              <div className="w-16 h-16 bg-green-100 rounded-full flex items-center justify-center mx-auto mb-3">
                <i className="fas fa-check text-green-600 text-2xl"></i>
              </div>
              <p className="text-sm text-gray-600">No vulnerabilities detected</p>
            </div>
          )}
        </div>

        {/* Top Vulnerable Namespaces */}
        <div className="bg-white rounded-xl shadow-sm p-6 border border-gray-200">
          <h3 className="text-lg font-bold text-gray-900 mb-6 flex items-center gap-2">
            <i className="fas fa-layer-group text-blue-600"></i>
            Most Vulnerable Namespaces
          </h3>

          {namespaceVulns.length > 0 ? (
            <div className="space-y-3">
              {namespaceVulns.map((ns, index) => (
                <div key={index} className="border border-gray-200 rounded-lg p-3">
                  <div className="flex items-center justify-between mb-2">
                    <span className="text-sm font-semibold text-gray-900 font-mono">{ns.name}</span>
                    <span className="text-sm font-bold text-gray-900">{ns.total} issues</span>
                  </div>
                  <div className="flex items-center gap-2">
                    {ns.critical > 0 && (
                      <span className="px-2 py-0.5 bg-red-100 text-red-700 rounded text-xs font-semibold">
                        {ns.critical} Critical
                      </span>
                    )}
                    {ns.high > 0 && (
                      <span className="px-2 py-0.5 bg-orange-100 text-orange-700 rounded text-xs font-semibold">
                        {ns.high} High
                      </span>
                    )}
                    {ns.medium > 0 && (
                      <span className="px-2 py-0.5 bg-amber-100 text-amber-700 rounded text-xs font-semibold">
                        {ns.medium} Medium
                      </span>
                    )}
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <div className="text-center py-8">
              <p className="text-sm text-gray-600">No namespace data available</p>
            </div>
          )}
        </div>
      </div>

      {/* Vulnerable Workloads */}
      {kubescapeData.vulnerabilities && kubescapeData.vulnerabilities.length > 0 && (
        <div className="bg-white rounded-xl shadow-sm p-6 border border-gray-200">
          <div className="flex items-center justify-between mb-6">
            <h3 className="text-lg font-bold text-gray-900 flex items-center gap-2">
              <i className="fas fa-cubes text-purple-600"></i>
              Vulnerable Workloads
              <span className="text-sm font-normal text-gray-500 bg-gray-100 px-2 py-1 rounded">
                {filteredWorkloads.length}
              </span>
            </h3>

            <select
              value={selectedSeverity}
              onChange={(e) => {
                setSelectedSeverity(e.target.value);
                setWorkloadPage(1);
              }}
              className="px-3 py-1.5 border border-gray-300 rounded-lg text-sm font-medium text-gray-700 bg-white hover:border-gray-400 focus:outline-none focus:ring-2 focus:ring-purple-500"
            >
              <option value="all">All Severities</option>
              <option value="critical">Critical Only</option>
              <option value="high">High Priority</option>
              <option value="medium">Medium Priority</option>
            </select>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="border-b-2 border-gray-200">
                  <th className="text-left px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                    Workload
                  </th>
                  <th className="text-left px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                    Type
                  </th>
                  <th className="text-left px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                    Namespace
                  </th>
                  <th className="text-center px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                    Critical
                  </th>
                  <th className="text-center px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                    High
                  </th>
                  <th className="text-center px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                    Medium
                  </th>
                  <th className="text-center px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">
                    Low
                  </th>
                </tr>
              </thead>
              <tbody>
                {paginatedWorkloads.map((workload, index) => (
                  <tr key={index} className="border-t border-gray-100 hover:bg-gray-50 transition-colors">
                    <td className="px-4 py-3 text-sm font-semibold text-gray-900">{workload.workloadName}</td>
                    <td className="px-4 py-3 text-sm text-gray-600">
                      <span className="px-2 py-0.5 bg-gray-100 rounded text-xs font-medium">
                        {workload.workloadKind}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-sm text-gray-600 font-mono">{workload.namespace}</td>
                    <td className="px-4 py-3 text-center">
                      {workload.criticalCount > 0 ? (
                        <span className="px-2 py-1 bg-red-100 text-red-700 rounded text-sm font-bold">
                          {workload.criticalCount}
                        </span>
                      ) : (
                        <span className="text-gray-400">-</span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-center">
                      {workload.highCount > 0 ? (
                        <span className="px-2 py-1 bg-orange-100 text-orange-700 rounded text-sm font-bold">
                          {workload.highCount}
                        </span>
                      ) : (
                        <span className="text-gray-400">-</span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-center">
                      {workload.mediumCount > 0 ? (
                        <span className="px-2 py-1 bg-amber-100 text-amber-700 rounded text-sm font-bold">
                          {workload.mediumCount}
                        </span>
                      ) : (
                        <span className="text-gray-400">-</span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-center">
                      {workload.lowCount > 0 ? (
                        <span className="px-2 py-1 bg-blue-100 text-blue-700 rounded text-sm font-bold">
                          {workload.lowCount}
                        </span>
                      ) : (
                        <span className="text-gray-400">-</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Pagination */}
          {totalWorkloadPages > 1 && (
            <div className="flex items-center justify-between mt-4 pt-4 border-t border-gray-200">
              <div className="text-sm text-gray-600">
                Showing {((workloadPage - 1) * workloadsPerPage) + 1}-{Math.min(workloadPage * workloadsPerPage, filteredWorkloads.length)} of {filteredWorkloads.length}
              </div>

              <div className="flex items-center gap-1">
                <button
                  onClick={() => setWorkloadPage(p => Math.max(1, p - 1))}
                  disabled={workloadPage === 1}
                  className="px-3 py-1 border border-gray-300 rounded-lg text-sm font-medium disabled:opacity-50 disabled:cursor-not-allowed hover:bg-gray-50"
                >
                  <i className="fas fa-chevron-left"></i>
                </button>

                {Array.from({ length: Math.min(5, totalWorkloadPages) }, (_, i) => {
                  let page;
                  if (totalWorkloadPages <= 5) {
                    page = i + 1;
                  } else if (workloadPage <= 3) {
                    page = i + 1;
                  } else if (workloadPage >= totalWorkloadPages - 2) {
                    page = totalWorkloadPages - 4 + i;
                  } else {
                    page = workloadPage - 2 + i;
                  }

                  return (
                    <button
                      key={page}
                      onClick={() => setWorkloadPage(page)}
                      className={`w-8 h-8 rounded-lg text-sm font-semibold transition-all ${page === workloadPage
                          ? 'bg-purple-600 text-white'
                          : 'border border-gray-300 text-gray-700 hover:bg-gray-50'
                        }`}
                    >
                      {page}
                    </button>
                  );
                })}

                <button
                  onClick={() => setWorkloadPage(p => Math.min(totalWorkloadPages, p + 1))}
                  disabled={workloadPage === totalWorkloadPages}
                  className="px-3 py-1 border border-gray-300 rounded-lg text-sm font-medium disabled:opacity-50 disabled:cursor-not-allowed hover:bg-gray-50"
                >
                  <i className="fas fa-chevron-right"></i>
                </button>
              </div>
            </div>
          )}
        </div>
      )}

      {/* Info Footer */}
      <div className="bg-purple-50 rounded-lg p-4 border border-purple-200">
        <div className="flex items-start gap-3">
          <div className="w-8 h-8 bg-purple-600 rounded-lg flex items-center justify-center flex-shrink-0">
            <i className="fas fa-info-circle text-white text-sm"></i>
          </div>
          <div>
            <h4 className="font-semibold text-gray-900 text-sm mb-1">About Security & Compliance Scanning</h4>
            <p className="text-xs text-gray-700 leading-relaxed">
              It analyzes your Kubernetes configurations against industry standards including NSA-CISA, MITRE ATT&CK,
              and CIS benchmarks. Regular scans help identify misconfigurations and security vulnerabilities before they can be exploited.
              Focus on critical and high-severity issues first for maximum security impact.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
};

export default KubescapeComplianceView;
