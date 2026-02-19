/**
 * GKE Cost Tab
 *
 * Estimated cost breakdown by namespace and workload from Prometheus resource usage.
 */

import React, { useState } from 'react';

const ITEMS_PER_PAGE = 10;

const DisconnectedPlaceholder: React.FC = () => (
  <div className="bg-white rounded-xl border border-amber-200 p-12 flex flex-col items-center text-center">
    <i className="fas fa-plug text-amber-300 text-5xl mb-4"></i>
    <h3 className="text-lg font-bold text-gray-700 mb-2">Monitoring Not Connected</h3>
    <p className="text-gray-400 text-sm max-w-md">
      Enable monitoring on this cluster to see cost estimates. Use the "Enable Monitoring" button on the clusters page.
    </p>
  </div>
);

const GkeCostTab: React.FC<{ gkeData: any }> = ({ gkeData }) => {
  const { dashboard } = gkeData;
  const [nsPage, setNsPage] = useState(0);
  const [wlPage, setWlPage] = useState(0);

  if (!dashboard?.connected || !dashboard?.costMetrics) {
    return <DisconnectedPlaceholder />;
  }

  const { dailyCost, namespaceCosts = [], workloadCosts = [] } = dashboard.costMetrics;
  const monthlyCost = dailyCost * 30;

  const nsTotalPages = Math.ceil(namespaceCosts.length / ITEMS_PER_PAGE);
  const wlTotalPages = Math.ceil(workloadCosts.length / ITEMS_PER_PAGE);
  const paginatedNs = namespaceCosts.slice(nsPage * ITEMS_PER_PAGE, (nsPage + 1) * ITEMS_PER_PAGE);
  const paginatedWl = workloadCosts.slice(wlPage * ITEMS_PER_PAGE, (wlPage + 1) * ITEMS_PER_PAGE);

  return (
    <div className="space-y-6">
      {/* Summary Cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        {[
          { label: 'Daily Cost', value: `$${dailyCost.toFixed(2)}`, icon: 'fa-calendar-day', color: 'bg-green-50 text-green-600' },
          { label: 'Monthly Est.', value: `$${monthlyCost.toFixed(2)}`, icon: 'fa-calendar', color: 'bg-blue-50 text-blue-600' },
          { label: 'Namespaces', value: namespaceCosts.length, icon: 'fa-folder', color: 'bg-purple-50 text-purple-600' },
          { label: 'Workloads', value: workloadCosts.length, icon: 'fa-cubes', color: 'bg-indigo-50 text-indigo-600' },
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

      {/* Namespace Costs Table */}
      <div className="bg-white rounded-xl border border-gray-200 shadow-sm overflow-hidden">
        <div className="px-6 py-4 border-b border-gray-100">
          <h3 className="font-bold text-gray-800">Cost by Namespace</h3>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-left border-collapse">
            <thead>
              <tr className="bg-gray-50">
                <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Namespace</th>
                <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider text-right">CPU (m)</th>
                <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider text-right">Memory (MB)</th>
                <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider text-center">Pods</th>
                <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider text-right">Daily Cost</th>
                <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider text-right">Monthly Est.</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {paginatedNs.map((ns: any, i: number) => (
                <tr key={i} className="hover:bg-gray-50 transition-colors">
                  <td className="px-6 py-3">
                    <span className="font-semibold text-gray-800 text-sm bg-gray-100 px-2 py-1 rounded">{ns.namespace}</span>
                  </td>
                  <td className="px-6 py-3 text-right text-sm text-gray-700">{Math.round(ns.cpuUsageMillicores)}</td>
                  <td className="px-6 py-3 text-right text-sm text-gray-700">{Math.round(ns.memoryUsageMb)}</td>
                  <td className="px-6 py-3 text-center text-sm text-gray-700">{ns.podCount}</td>
                  <td className="px-6 py-3 text-right text-sm font-bold text-gray-900">${ns.dailyCost.toFixed(3)}</td>
                  <td className="px-6 py-3 text-right text-sm font-bold text-green-700">${ns.monthlyCost.toFixed(2)}</td>
                </tr>
              ))}
              {paginatedNs.length === 0 && (
                <tr><td colSpan={6} className="px-6 py-10 text-center text-gray-400">No namespace cost data</td></tr>
              )}
            </tbody>
          </table>
        </div>
        {nsTotalPages > 1 && (
          <div className="px-6 py-3 bg-gray-50 border-t border-gray-200 flex items-center justify-between">
            <span className="text-sm text-gray-500">{nsPage * ITEMS_PER_PAGE + 1}–{Math.min((nsPage + 1) * ITEMS_PER_PAGE, namespaceCosts.length)} of {namespaceCosts.length}</span>
            <div className="flex gap-2">
              <button disabled={nsPage === 0} onClick={() => setNsPage(p => p - 1)} className="px-3 py-1.5 bg-white border border-gray-300 rounded-lg text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50">Previous</button>
              <button disabled={nsPage === nsTotalPages - 1} onClick={() => setNsPage(p => p + 1)} className="px-3 py-1.5 bg-white border border-gray-300 rounded-lg text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50">Next</button>
            </div>
          </div>
        )}
      </div>

      {/* Workload Costs Table */}
      <div className="bg-white rounded-xl border border-gray-200 shadow-sm overflow-hidden">
        <div className="px-6 py-4 border-b border-gray-100">
          <h3 className="font-bold text-gray-800">Cost by Workload</h3>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-left border-collapse">
            <thead>
              <tr className="bg-gray-50">
                <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Workload</th>
                <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Namespace</th>
                <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Kind</th>
                <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider text-right">CPU (m)</th>
                <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider text-right">Memory (MB)</th>
                <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider text-right">Daily Cost</th>
                <th className="px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider text-right">Monthly Est.</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {paginatedWl.map((wl: any, i: number) => (
                <tr key={i} className="hover:bg-gray-50 transition-colors">
                  <td className="px-6 py-3">
                    <span className="font-bold text-gray-900 text-sm">{wl.name}</span>
                  </td>
                  <td className="px-6 py-3">
                    <span className="text-sm text-gray-600 bg-gray-100 px-2 py-1 rounded">{wl.namespace}</span>
                  </td>
                  <td className="px-6 py-3">
                    <span className="text-xs font-semibold text-indigo-700 bg-indigo-50 px-2 py-1 rounded-full">{wl.kind}</span>
                  </td>
                  <td className="px-6 py-3 text-right text-sm text-gray-700">{Math.round(wl.cpuUsageMillicores)}</td>
                  <td className="px-6 py-3 text-right text-sm text-gray-700">{Math.round(wl.memoryUsageMb)}</td>
                  <td className="px-6 py-3 text-right text-sm font-bold text-gray-900">${wl.dailyCost.toFixed(3)}</td>
                  <td className="px-6 py-3 text-right text-sm font-bold text-green-700">${wl.monthlyCost.toFixed(2)}</td>
                </tr>
              ))}
              {paginatedWl.length === 0 && (
                <tr><td colSpan={7} className="px-6 py-10 text-center text-gray-400">No workload cost data</td></tr>
              )}
            </tbody>
          </table>
        </div>
        {wlTotalPages > 1 && (
          <div className="px-6 py-3 bg-gray-50 border-t border-gray-200 flex items-center justify-between">
            <span className="text-sm text-gray-500">{wlPage * ITEMS_PER_PAGE + 1}–{Math.min((wlPage + 1) * ITEMS_PER_PAGE, workloadCosts.length)} of {workloadCosts.length}</span>
            <div className="flex gap-2">
              <button disabled={wlPage === 0} onClick={() => setWlPage(p => p - 1)} className="px-3 py-1.5 bg-white border border-gray-300 rounded-lg text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50">Previous</button>
              <button disabled={wlPage === wlTotalPages - 1} onClick={() => setWlPage(p => p + 1)} className="px-3 py-1.5 bg-white border border-gray-300 rounded-lg text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50">Next</button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default GkeCostTab;
