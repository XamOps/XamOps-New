/**
 * GKE Performance Tab
 *
 * Shows cluster-level resource gauges and per-node CPU/memory utilization.
 */

import React from 'react';

const DisconnectedPlaceholder: React.FC = () => (
  <div className="bg-white rounded-xl border border-amber-200 p-12 flex flex-col items-center text-center">
    <i className="fas fa-robot text-amber-300 text-5xl mb-4"></i>
    <h3 className="text-lg font-bold text-gray-700 mb-2">XamOps Agent Not Deployed</h3>
    <p className="text-gray-400 text-sm max-w-md">
      Deploy the XamOps agent on this cluster to see performance metrics.
    </p>
  </div>
);

const GaugeCircle: React.FC<{ value: number; label: string; color: string }> = ({ value, label, color }) => {
  const r = 54;
  const circumference = 2 * Math.PI * r;
  const offset = circumference * (1 - Math.min(value, 100) / 100);
  return (
    <div className="flex flex-col items-center">
      <div className="relative w-32 h-32">
        <svg viewBox="0 0 120 120" className="w-full h-full -rotate-90">
          <circle cx="60" cy="60" r={r} fill="none" stroke="#e5e7eb" strokeWidth="10" />
          <circle
            cx="60" cy="60" r={r} fill="none"
            className={color}
            strokeWidth="10"
            strokeLinecap="round"
            strokeDasharray={circumference}
            strokeDashoffset={offset}
            style={{ transition: 'stroke-dashoffset 0.8s ease' }}
          />
        </svg>
        <div className="absolute inset-0 flex flex-col items-center justify-center">
          <span className="text-2xl font-bold text-gray-900">{Math.round(value)}%</span>
        </div>
      </div>
      <span className="mt-2 text-sm font-semibold text-gray-600">{label}</span>
    </div>
  );
};

const UtilBar: React.FC<{ value: number; max?: number }> = ({ value, max = 100 }) => {
  const pct = Math.min(100, (value / (max || 1)) * 100);
  const color = pct >= 80 ? 'bg-red-500' : pct >= 60 ? 'bg-amber-500' : 'bg-indigo-500';
  return (
    <div className="flex items-center gap-2">
      <div className="flex-1 h-1.5 bg-gray-100 rounded-full overflow-hidden">
        <div className={`h-full rounded-full ${color}`} style={{ width: `${pct}%` }} />
      </div>
      <span className="text-xs font-bold text-gray-700 w-9 text-right">{Math.round(value)}%</span>
    </div>
  );
};

const GkePerformanceTab: React.FC<{ gkeData: any }> = ({ gkeData }) => {
  const { dashboard } = gkeData;

  if (!dashboard?.connected || !dashboard?.resourceUtilization) {
    return <DisconnectedPlaceholder />;
  }

  const nodes = dashboard.resourceUtilization.nodes ?? [];
  const overview = dashboard.overview;

  const avgCpu = nodes.length > 0
    ? nodes.reduce((s: number, n: any) => s + (n.cpuUsagePercent ?? 0), 0) / nodes.length
    : overview?.cpuUsagePercent ?? 0;
  const avgMem = nodes.length > 0
    ? nodes.reduce((s: number, n: any) => s + (n.memoryUsagePercent ?? 0), 0) / nodes.length
    : overview?.memoryUsagePercent ?? 0;
  const avgCpuReq = nodes.length > 0
    ? nodes.reduce((s: number, n: any) => s + (n.cpuRequestsPercent ?? 0), 0) / nodes.length
    : 0;
  const avgMemReq = nodes.length > 0
    ? nodes.reduce((s: number, n: any) => s + (n.memoryRequestsPercent ?? 0), 0) / nodes.length
    : 0;
  const totalPods = overview?.totalPods ?? 0;
  const podDensity = nodes.length > 0 ? Math.round(totalPods / nodes.length) : 0;

  const cpuGaugeColor = avgCpu >= 80 ? 'stroke-red-500' : avgCpu >= 60 ? 'stroke-amber-500' : 'stroke-indigo-500';
  const memGaugeColor = avgMem >= 80 ? 'stroke-red-500' : avgMem >= 60 ? 'stroke-amber-500' : 'stroke-emerald-500';

  return (
    <div className="space-y-6">
      {/* Overview Cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        {[
          { label: 'Avg CPU Usage', value: `${Math.round(avgCpu)}%`, icon: 'fa-microchip', color: 'bg-indigo-50 text-indigo-600' },
          { label: 'Avg Memory Usage', value: `${Math.round(avgMem)}%`, icon: 'fa-memory', color: 'bg-emerald-50 text-emerald-600' },
          { label: 'Node Count', value: nodes.length, icon: 'fa-server', color: 'bg-blue-50 text-blue-600' },
          { label: 'Pod Density', value: `${podDensity}/node`, icon: 'fa-box', color: 'bg-purple-50 text-purple-600' },
        ].map(({ label, value, icon, color }) => (
          <div key={label} className="bg-white p-5 rounded-xl border border-gray-200 shadow-sm">
            <div className="flex items-center gap-3 mb-2">
              <div className={`w-10 h-10 rounded-lg flex items-center justify-center ${color}`}>
                <i className={`fas ${icon}`}></i>
              </div>
              <span className="text-xs font-semibold text-gray-500 uppercase tracking-wide">{label}</span>
            </div>
            <div className="text-2xl font-bold text-gray-900">{value}</div>
          </div>
        ))}
      </div>

      {/* Cluster Gauges */}
      <div className="bg-white rounded-xl border border-gray-200 shadow-sm p-6">
        <h3 className="text-sm font-semibold text-gray-500 uppercase tracking-wide mb-6">Cluster Resource Utilization</h3>
        <div className="flex flex-wrap justify-around gap-8">
          <GaugeCircle value={avgCpu} label="CPU Usage" color={cpuGaugeColor} />
          <GaugeCircle value={avgMem} label="Memory Usage" color={memGaugeColor} />
          <GaugeCircle value={avgCpuReq} label="CPU Requests" color="stroke-blue-500" />
          <GaugeCircle value={avgMemReq} label="Memory Requests" color="stroke-teal-500" />
        </div>
      </div>

      {/* Per-Node Utilization Table */}
      <div className="bg-white rounded-xl border border-gray-200 shadow-sm overflow-hidden">
        <div className="px-6 py-4 border-b border-gray-100">
          <h3 className="font-bold text-gray-800">Per-Node Utilization</h3>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-left border-collapse">
            <thead>
              <tr className="bg-gray-50">
                <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Node</th>
                <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">CPU Usage</th>
                <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Memory Usage</th>
                <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">CPU Requests</th>
                <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Memory Requests</th>
                <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider text-right">CPU (m)</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {nodes.map((node: any, i: number) => (
                <tr key={i} className="hover:bg-gray-50 transition-colors">
                  <td className="px-6 py-3">
                    <div className="flex items-center gap-2">
                      <div className="w-7 h-7 rounded bg-gray-100 flex items-center justify-center flex-shrink-0">
                        <i className="fas fa-server text-gray-400 text-xs"></i>
                      </div>
                      <span className="text-sm font-semibold text-gray-800 font-mono truncate max-w-[180px]">{node.nodeName}</span>
                    </div>
                  </td>
                  <td className="px-6 py-3 w-36"><UtilBar value={node.cpuUsagePercent ?? 0} /></td>
                  <td className="px-6 py-3 w-36"><UtilBar value={node.memoryUsagePercent ?? 0} /></td>
                  <td className="px-6 py-3 w-36"><UtilBar value={node.cpuRequestsPercent ?? 0} /></td>
                  <td className="px-6 py-3 w-36"><UtilBar value={node.memoryRequestsPercent ?? 0} /></td>
                  <td className="px-6 py-3 text-right text-sm text-gray-600">{Math.round(node.cpuUsageMillicores ?? 0)}</td>
                </tr>
              ))}
              {nodes.length === 0 && (
                <tr><td colSpan={6} className="px-6 py-10 text-center text-gray-400">No node utilization data</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
};

export default GkePerformanceTab;
