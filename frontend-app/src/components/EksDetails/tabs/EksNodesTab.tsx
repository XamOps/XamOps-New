/**
 * EKS Nodes Tab
 */

import React from 'react';

const EksNodesTab: React.FC<{ eksData: any }> = ({ eksData }) => {
  const { dashboard, sortedNodesByPressure, getNodeConditionCount } = eksData;
  const nodes = dashboard?.nodeMetrics?.nodes || [];

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-4 gap-4">
        <div className="bg-green-50 rounded-lg p-4 text-center">
          <div className="text-3xl font-black text-green-600">{getNodeConditionCount('ready')}</div>
          <div className="text-sm text-green-700 mt-1">Ready</div>
        </div>
        <div className="bg-amber-50 rounded-lg p-4 text-center">
          <div className="text-3xl font-black text-amber-600">{getNodeConditionCount('diskPressure')}</div>
          <div className="text-sm text-amber-700 mt-1">Disk Pressure</div>
        </div>
        <div className="bg-red-50 rounded-lg p-4 text-center">
          <div className="text-3xl font-black text-red-600">{getNodeConditionCount('memoryPressure')}</div>
          <div className="text-sm text-red-700 mt-1">Memory Pressure</div>
        </div>
        <div className="bg-blue-50 rounded-lg p-4 text-center">
          <div className="text-3xl font-black text-blue-600">{nodes.length}</div>
          <div className="text-sm text-blue-700 mt-1">Total Nodes</div>
        </div>
      </div>

      <div className="bg-white rounded-xl shadow-sm p-6 border border-gray-100">
        <h3 className="text-xl font-bold text-gray-900 mb-4">
          <i className="fas fa-server text-blue-500 mr-2"></i>
          Node Resource Usage
        </h3>
        <div className="overflow-x-auto">
          <table className="w-full data-table">
            <thead>
              <tr>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-600 uppercase">Node Name</th>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-600 uppercase">Instance Type</th>
                <th className="text-right px-4 py-3 text-xs font-semibold text-gray-600 uppercase">CPU Usage</th>
                <th className="text-right px-4 py-3 text-xs font-semibold text-gray-600 uppercase">Memory Usage</th>
                <th className="text-center px-4 py-3 text-xs font-semibold text-gray-600 uppercase">Status</th>
              </tr>
            </thead>
            <tbody>
              {sortedNodesByPressure.map((node: any, index: number) => (
                <tr key={index} className="border-t border-gray-100 hover:bg-gray-50">
                  <td className="px-4 py-3 text-sm font-medium text-gray-900">{node.nodeName}</td>
                  <td className="px-4 py-3 text-sm text-gray-600">
                    {nodes.find((n: any) => n.name === node.nodeName)?.instanceType || 'N/A'}
                  </td>
                  <td className="px-4 py-3 text-sm text-right">
                    <span
                      className={`px-2 py-1 rounded-full text-xs font-semibold ${
                        node.cpuUsagePercent >= 80
                          ? 'bg-red-100 text-red-700'
                          : node.cpuUsagePercent >= 60
                          ? 'bg-amber-100 text-amber-700'
                          : 'bg-green-100 text-green-700'
                      }`}
                    >
                      {node.cpuUsagePercent.toFixed(1)}%
                    </span>
                  </td>
                  <td className="px-4 py-3 text-sm text-right">
                    <span
                      className={`px-2 py-1 rounded-full text-xs font-semibold ${
                        node.memoryUsagePercent >= 80
                          ? 'bg-red-100 text-red-700'
                          : node.memoryUsagePercent >= 60
                          ? 'bg-amber-100 text-amber-700'
                          : 'bg-green-100 text-green-700'
                      }`}
                    >
                      {node.memoryUsagePercent.toFixed(1)}%
                    </span>
                  </td>
                  <td className="px-4 py-3 text-center">
                    {nodes.find((n: any) => n.name === node.nodeName)?.ready ? (
                      <span className="px-2 py-1 bg-green-100 text-green-700 rounded-full text-xs font-semibold">
                        Ready
                      </span>
                    ) : (
                      <span className="px-2 py-1 bg-red-100 text-red-700 rounded-full text-xs font-semibold">
                        Not Ready
                      </span>
                    )}
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

export default EksNodesTab;
