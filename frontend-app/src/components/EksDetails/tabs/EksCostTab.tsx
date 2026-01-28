/**
 * EKS Cost Tab
 *
 * Cost analysis and node cost breakdown
 */

import React from 'react';

interface EksCostTabProps {
  eksData: any;
}

const EksCostTab: React.FC<EksCostTabProps> = ({ eksData }) => {
  const { dashboard, sortedNodesByCost } = eksData;

  if (!dashboard) return null;

  return (
    <div className="space-y-6">
      {/* Cost Summary */}
      <div className="bg-white rounded-xl shadow-sm p-6 border border-gray-100">
        <h3 className="text-xl font-bold text-gray-900 mb-4 flex items-center gap-2">
          <i className="fas fa-dollar-sign text-green-600"></i>
          Cost Summary
        </h3>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div className="p-4 bg-blue-50 rounded-lg">
            <div className="text-sm text-blue-600 font-semibold mb-1">Daily Cost</div>
            <div className="text-3xl font-black text-blue-900">
              ${(dashboard.costMetrics?.dailyCost || 0).toFixed(2)}
            </div>
          </div>
          <div className="p-4 bg-purple-50 rounded-lg">
            <div className="text-sm text-purple-600 font-semibold mb-1">Monthly Projection</div>
            <div className="text-3xl font-black text-purple-900">
              ${((dashboard.costMetrics?.dailyCost || 0) * 30).toFixed(2)}
            </div>
          </div>
        </div>
      </div>

      {/* Node Costs Table */}
      <div className="bg-white rounded-xl shadow-sm p-6 border border-gray-100">
        <h3 className="text-xl font-bold text-gray-900 mb-4 flex items-center gap-2">
          <i className="fas fa-server text-indigo-600"></i>
          Node Cost Breakdown
        </h3>
        <div className="overflow-x-auto">
          <table className="w-full data-table">
            <thead>
              <tr>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-600 uppercase">
                  Node Name
                </th>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-600 uppercase">
                  Instance Type
                </th>
                <th className="text-right px-4 py-3 text-xs font-semibold text-gray-600 uppercase">
                  CPU Usage
                </th>
                <th className="text-right px-4 py-3 text-xs font-semibold text-gray-600 uppercase">
                  Hourly Rate
                </th>
                <th className="text-right px-4 py-3 text-xs font-semibold text-gray-600 uppercase">
                  Daily Cost
                </th>
              </tr>
            </thead>
            <tbody>
              {sortedNodesByCost.map((node: any, index: number) => (
                <tr key={index} className="border-t border-gray-100 hover:bg-gray-50">
                  <td className="px-4 py-3 text-sm font-medium text-gray-900">
                    {node.nodeName}
                  </td>
                  <td className="px-4 py-3 text-sm text-gray-600">{node.instanceType}</td>
                  <td className="px-4 py-3 text-sm text-right">
                    <span
                      className={`px-2 py-1 rounded-full text-xs font-semibold ${
                        (node.cpuUsagePercent || 0) >= 70
                          ? 'bg-red-100 text-red-700'
                          : (node.cpuUsagePercent || 0) >= 50
                          ? 'bg-amber-100 text-amber-700'
                          : 'bg-green-100 text-green-700'
                      }`}
                    >
                      {(node.cpuUsagePercent || 0).toFixed(1)}%
                    </span>
                  </td>
                  <td className="px-4 py-3 text-sm text-right font-medium">
                    ${(node.hourlyRate || 0).toFixed(4)}
                  </td>
                  <td className="px-4 py-3 text-sm text-right font-bold text-indigo-600">
                    ${(node.dailyCost || 0).toFixed(2)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
};

export default EksCostTab;
