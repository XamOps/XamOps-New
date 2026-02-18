/**
 * GKE Security Tab
 *
 * Falco runtime security events, Kubescape compliance scanner, Trivy image vulnerability scanner.
 * Each section shows 3 states: not installed → running (no data) → live data.
 */

import React from 'react';

const DisconnectedPlaceholder: React.FC = () => (
  <div className="bg-white rounded-xl border border-amber-200 p-12 flex flex-col items-center text-center">
    <i className="fas fa-plug text-amber-300 text-5xl mb-4"></i>
    <h3 className="text-lg font-bold text-gray-700 mb-2">Monitoring Not Connected</h3>
    <p className="text-gray-400 text-sm max-w-md">
      Enable monitoring on this cluster to see security events.
    </p>
  </div>
);

const NotInstalledCard: React.FC<{ tool: string; description: string; icon: string }> = ({ tool, description, icon }) => (
  <div className="bg-white rounded-xl border border-gray-200 p-8 flex flex-col items-center text-center">
    <div className="w-14 h-14 bg-gray-50 rounded-2xl flex items-center justify-center mb-4 border border-gray-200">
      <i className={`fas ${icon} text-gray-300 text-2xl`}></i>
    </div>
    <h4 className="font-bold text-gray-700 mb-1">{tool} Not Installed</h4>
    <p className="text-gray-400 text-sm">{description}</p>
  </div>
);

const PRIORITY_CONFIG: Record<string, { color: string; bg: string }> = {
  Critical: { color: 'text-red-700', bg: 'bg-red-100' },
  Error:    { color: 'text-red-600', bg: 'bg-red-50' },
  Warning:  { color: 'text-amber-700', bg: 'bg-amber-100' },
  Notice:   { color: 'text-blue-700', bg: 'bg-blue-100' },
  Info:     { color: 'text-gray-600', bg: 'bg-gray-100' },
  Debug:    { color: 'text-gray-500', bg: 'bg-gray-50' },
};

const getPriorityStyle = (priority: string) =>
  PRIORITY_CONFIG[priority] ?? { color: 'text-gray-600', bg: 'bg-gray-100' };

const SEVERITY_CONFIG: Record<string, { color: string; bg: string }> = {
  CRITICAL: { color: 'text-red-700',    bg: 'bg-red-100' },
  HIGH:     { color: 'text-orange-700', bg: 'bg-orange-100' },
  MEDIUM:   { color: 'text-amber-700',  bg: 'bg-amber-100' },
  LOW:      { color: 'text-blue-700',   bg: 'bg-blue-100' },
  UNKNOWN:  { color: 'text-gray-600',   bg: 'bg-gray-100' },
  Critical: { color: 'text-red-700',    bg: 'bg-red-100' },
  High:     { color: 'text-orange-700', bg: 'bg-orange-100' },
  Medium:   { color: 'text-amber-700',  bg: 'bg-amber-100' },
  Low:      { color: 'text-blue-700',   bg: 'bg-blue-100' },
};

const getSeverityStyle = (s: string) =>
  SEVERITY_CONFIG[s] ?? { color: 'text-gray-600', bg: 'bg-gray-100' };

const GkeSecurityTab: React.FC<{ gkeData: any }> = ({ gkeData }) => {
  const { dashboard } = gkeData;

  if (!dashboard?.connected) {
    return <DisconnectedPlaceholder />;
  }

  const sec = dashboard.securityMetrics;
  const falcoRunning = sec?.falcoRunning === true;
  const falcoHasData = sec && (sec.totalEvents > 0 || (sec.alerts && sec.alerts.length > 0));

  const kubescapeRunning = sec?.kubescapeRunning === true;
  const kubescapeHasData = kubescapeRunning && (sec?.kubescapeTotalFailed > 0 || (sec?.kubescapeFindings && sec.kubescapeFindings.length > 0));

  const trivyRunning = sec?.trivyRunning === true;
  const trivyHasData = trivyRunning && sec?.trivyTotalVulnerabilities > 0;

  return (
    <div className="space-y-6">
      {/* Falco Section */}
      <div>
        <h3 className="text-sm font-semibold text-gray-500 uppercase tracking-wide mb-3">Runtime Security (Falco)</h3>
        {falcoHasData ? (
          <div className="space-y-4">
            {/* Priority Breakdown */}
            <div className="grid grid-cols-2 md:grid-cols-3 gap-3">
              {Object.entries(sec.eventsByPriority ?? {}).map(([priority, count]: [string, any]) => {
                const style = getPriorityStyle(priority);
                return (
                  <div key={priority} className="bg-white p-4 rounded-xl border border-gray-200 shadow-sm flex items-center gap-3">
                    <span className={`inline-flex items-center justify-center w-10 h-10 rounded-lg font-bold text-lg ${style.bg} ${style.color}`}>
                      {count}
                    </span>
                    <div>
                      <div className={`text-xs font-bold uppercase tracking-wide ${style.color}`}>{priority}</div>
                      <div className="text-xs text-gray-400">events</div>
                    </div>
                  </div>
                );
              })}
            </div>

            {/* Total events summary */}
            <div className="bg-white rounded-xl border border-gray-200 p-4 flex items-center gap-4">
              <div className="w-12 h-12 bg-red-50 rounded-xl flex items-center justify-center flex-shrink-0">
                <i className="fas fa-bell text-red-500 text-lg"></i>
              </div>
              <div>
                <div className="text-2xl font-bold text-gray-900">{sec.totalEvents.toFixed ? sec.totalEvents.toFixed(0) : sec.totalEvents}</div>
                <div className="text-xs text-gray-400">Total Falco events detected</div>
              </div>
            </div>

            {/* Alerts Table */}
            {sec.alerts && sec.alerts.length > 0 && (
              <div className="bg-white rounded-xl border border-gray-200 shadow-sm overflow-hidden">
                <div className="px-6 py-4 border-b border-gray-100">
                  <h4 className="font-bold text-gray-800">Recent Alerts</h4>
                </div>
                <div className="overflow-x-auto">
                  <table className="w-full text-left border-collapse">
                    <thead>
                      <tr className="bg-gray-50">
                        <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Rule</th>
                        <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Priority</th>
                        <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider text-center">Count</th>
                        <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Namespace</th>
                        <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Last Seen</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100">
                      {sec.alerts.map((alert: any, i: number) => {
                        const style = getPriorityStyle(alert.priority);
                        return (
                          <tr key={i} className="hover:bg-gray-50 transition-colors">
                            <td className="px-6 py-3">
                              <span className="text-sm font-semibold text-gray-800">{alert.rule}</span>
                            </td>
                            <td className="px-6 py-3">
                              <span className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-bold ${style.bg} ${style.color}`}>
                                <div className="w-1.5 h-1.5 rounded-full bg-current"></div>
                                {alert.priority}
                              </span>
                            </td>
                            <td className="px-6 py-3 text-center">
                              <span className="text-sm font-bold text-gray-900">{alert.count}</span>
                            </td>
                            <td className="px-6 py-3">
                              <span className="text-sm text-gray-600 bg-gray-100 px-2 py-1 rounded">{alert.namespace || '—'}</span>
                            </td>
                            <td className="px-6 py-3">
                              <span className="text-xs text-gray-500">{alert.lastSeen || '—'}</span>
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </div>
            )}
          </div>
        ) : falcoRunning ? (
          /* Falco pods are running but Prometheus isn't scraping yet */
          <div className="bg-white rounded-xl border border-blue-200 p-8 flex flex-col items-center text-center">
            <div className="w-14 h-14 bg-blue-50 rounded-2xl flex items-center justify-center mb-4 border border-blue-200">
              <i className="fas fa-shield-alt text-blue-400 text-2xl"></i>
            </div>
            <h4 className="font-bold text-gray-700 mb-1">Falco Running — Enable Prometheus Metrics</h4>
            <p className="text-gray-500 text-sm mb-4 max-w-md">
              Falco pods are running in the <code className="bg-gray-100 px-1 rounded text-xs">falco</code> namespace,
              but Prometheus isn't scraping Falcosidekick yet. Run this command to enable the ServiceMonitor:
            </p>
            <pre className="bg-gray-900 text-gray-100 p-3 rounded-lg text-xs font-mono text-left w-full max-w-lg whitespace-pre-wrap">
{`helm upgrade falco falcosecurity/falco -n falco \\
  --reuse-values \\
  --set falcosidekick.serviceMonitor.enabled=true`}
            </pre>
            <p className="text-gray-400 text-xs mt-3">Then click Refresh — events will appear within 1–2 minutes.</p>
          </div>
        ) : (
          <NotInstalledCard
            tool="Falco"
            description="Install Falco to get runtime security event monitoring for this cluster."
            icon="fa-shield-alt"
          />
        )}
      </div>

      {/* Kubescape Section */}
      <div>
        <h3 className="text-sm font-semibold text-gray-500 uppercase tracking-wide mb-3">Compliance Scanner (Kubescape)</h3>
        {kubescapeHasData ? (
          <div className="space-y-4">
            {/* Pass / Fail summary */}
            <div className="grid grid-cols-2 gap-3">
              <div className="bg-white p-4 rounded-xl border border-red-200 shadow-sm flex items-center gap-3">
                <span className="inline-flex items-center justify-center w-10 h-10 rounded-lg font-bold text-lg bg-red-100 text-red-700">
                  {sec.kubescapeTotalFailed}
                </span>
                <div>
                  <div className="text-xs font-bold uppercase tracking-wide text-red-700">Failed</div>
                  <div className="text-xs text-gray-400">resources</div>
                </div>
              </div>
              <div className="bg-white p-4 rounded-xl border border-green-200 shadow-sm flex items-center gap-3">
                <span className="inline-flex items-center justify-center w-10 h-10 rounded-lg font-bold text-lg bg-green-100 text-green-700">
                  {sec.kubescapeTotalPassed}
                </span>
                <div>
                  <div className="text-xs font-bold uppercase tracking-wide text-green-700">Passed</div>
                  <div className="text-xs text-gray-400">resources</div>
                </div>
              </div>
            </div>

            {/* Severity breakdown */}
            {sec.kubescapeFailsBySeverity && Object.keys(sec.kubescapeFailsBySeverity).length > 0 && (
              <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                {Object.entries(sec.kubescapeFailsBySeverity).map(([sev, count]: [string, any]) => {
                  const style = getSeverityStyle(sev);
                  return (
                    <div key={sev} className="bg-white p-4 rounded-xl border border-gray-200 shadow-sm flex items-center gap-3">
                      <span className={`inline-flex items-center justify-center w-10 h-10 rounded-lg font-bold text-lg ${style.bg} ${style.color}`}>
                        {count}
                      </span>
                      <div>
                        <div className={`text-xs font-bold uppercase tracking-wide ${style.color}`}>{sev}</div>
                        <div className="text-xs text-gray-400">controls</div>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}

            {/* Failed controls table */}
            {sec.kubescapeFindings && sec.kubescapeFindings.length > 0 && (
              <div className="bg-white rounded-xl border border-gray-200 shadow-sm overflow-hidden">
                <div className="px-6 py-4 border-b border-gray-100">
                  <h4 className="font-bold text-gray-800">Failed Controls</h4>
                </div>
                <div className="overflow-x-auto">
                  <table className="w-full text-left border-collapse">
                    <thead>
                      <tr className="bg-gray-50">
                        <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Control</th>
                        <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Severity</th>
                        <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider text-center">Failed Resources</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100">
                      {sec.kubescapeFindings.map((f: any, i: number) => {
                        const style = getSeverityStyle(f.severity);
                        return (
                          <tr key={i} className="hover:bg-gray-50 transition-colors">
                            <td className="px-6 py-3">
                              <span className="text-sm font-semibold text-gray-800">{f.controlName}</span>
                            </td>
                            <td className="px-6 py-3">
                              <span className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-bold ${style.bg} ${style.color}`}>
                                <div className="w-1.5 h-1.5 rounded-full bg-current"></div>
                                {f.severity}
                              </span>
                            </td>
                            <td className="px-6 py-3 text-center">
                              <span className="text-sm font-bold text-gray-900">{f.failedResources}</span>
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </div>
            )}
          </div>
        ) : kubescapeRunning ? (
          <div className="bg-white rounded-xl border border-green-200 p-8 flex flex-col items-center text-center">
            <div className="w-14 h-14 bg-green-50 rounded-2xl flex items-center justify-center mb-4 border border-green-200">
              <i className="fas fa-clipboard-check text-green-400 text-2xl"></i>
            </div>
            <h4 className="font-bold text-gray-700 mb-1">Kubescape Running — All Controls Passing</h4>
            <p className="text-gray-500 text-sm max-w-md">
              Kubescape is running in the <code className="bg-gray-100 px-1 rounded text-xs">kubescape</code> namespace.
              No failed controls detected — your cluster is compliant.
            </p>
          </div>
        ) : (
          <NotInstalledCard
            tool="Kubescape"
            description="Install Kubescape to get CIS benchmark and compliance scan results for this cluster."
            icon="fa-clipboard-check"
          />
        )}
      </div>

      {/* Trivy Section */}
      <div>
        <h3 className="text-sm font-semibold text-gray-500 uppercase tracking-wide mb-3">Image Vulnerability Scanner (Trivy)</h3>
        {trivyHasData ? (
          <div className="space-y-4">
            {/* Total count */}
            <div className="bg-white rounded-xl border border-gray-200 p-4 flex items-center gap-4">
              <div className="w-12 h-12 bg-red-50 rounded-xl flex items-center justify-center flex-shrink-0">
                <i className="fas fa-bug text-red-500 text-lg"></i>
              </div>
              <div>
                <div className="text-2xl font-bold text-gray-900">{Math.round(sec.trivyTotalVulnerabilities)}</div>
                <div className="text-xs text-gray-400">Total vulnerabilities detected</div>
              </div>
            </div>

            {/* Severity breakdown */}
            {sec.trivyBySeverity && Object.keys(sec.trivyBySeverity).length > 0 && (
              <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                {Object.entries(sec.trivyBySeverity).map(([sev, count]: [string, any]) => {
                  const style = getSeverityStyle(sev);
                  return (
                    <div key={sev} className="bg-white p-4 rounded-xl border border-gray-200 shadow-sm flex items-center gap-3">
                      <span className={`inline-flex items-center justify-center w-10 h-10 rounded-lg font-bold text-lg ${style.bg} ${style.color}`}>
                        {count}
                      </span>
                      <div>
                        <div className={`text-xs font-bold uppercase tracking-wide ${style.color}`}>{sev}</div>
                        <div className="text-xs text-gray-400">vulns</div>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}

            {/* Affected images table */}
            {sec.trivyFindings && sec.trivyFindings.length > 0 && (
              <div className="bg-white rounded-xl border border-gray-200 shadow-sm overflow-hidden">
                <div className="px-6 py-4 border-b border-gray-100">
                  <h4 className="font-bold text-gray-800">Affected Images</h4>
                </div>
                <div className="overflow-x-auto">
                  <table className="w-full text-left border-collapse">
                    <thead>
                      <tr className="bg-gray-50">
                        <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Image</th>
                        <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Namespace</th>
                        <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Severity</th>
                        <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider text-center">Count</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100">
                      {sec.trivyFindings.map((f: any, i: number) => {
                        const style = getSeverityStyle(f.severity);
                        return (
                          <tr key={i} className="hover:bg-gray-50 transition-colors">
                            <td className="px-6 py-3">
                              <span className="text-sm font-mono text-gray-800 truncate max-w-xs block">{f.image}</span>
                            </td>
                            <td className="px-6 py-3">
                              <span className="text-sm text-gray-600 bg-gray-100 px-2 py-1 rounded">{f.namespace || '—'}</span>
                            </td>
                            <td className="px-6 py-3">
                              <span className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-bold ${style.bg} ${style.color}`}>
                                <div className="w-1.5 h-1.5 rounded-full bg-current"></div>
                                {f.severity}
                              </span>
                            </td>
                            <td className="px-6 py-3 text-center">
                              <span className="text-sm font-bold text-gray-900">{f.count}</span>
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </div>
            )}
          </div>
        ) : trivyRunning ? (
          <div className="bg-white rounded-xl border border-green-200 p-8 flex flex-col items-center text-center">
            <div className="w-14 h-14 bg-green-50 rounded-2xl flex items-center justify-center mb-4 border border-green-200">
              <i className="fas fa-bug text-green-400 text-2xl"></i>
            </div>
            <h4 className="font-bold text-gray-700 mb-1">Trivy Running — No Vulnerabilities Detected</h4>
            <p className="text-gray-500 text-sm max-w-md">
              Trivy Operator is running. No image vulnerabilities found — your images are clean.
            </p>
          </div>
        ) : (
          <NotInstalledCard
            tool="Trivy"
            description="Install Trivy Operator to get container image vulnerability reports for this cluster."
            icon="fa-bug"
          />
        )}
      </div>
    </div>
  );
};

export default GkeSecurityTab;
