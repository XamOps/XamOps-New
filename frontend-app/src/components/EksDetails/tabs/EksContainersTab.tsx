/**
 * EKS Containers Tab
 */

import React from 'react';

const EksContainersTab: React.FC<{ eksData: any }> = ({ eksData }) => {
  const {
    filteredContainers,
    containerSearchQuery,
    setContainerSearchQuery,
    containerNamespaceFilter,
    setContainerNamespaceFilter,
    uniqueNamespaces,
    getContainersCountByState
  } = eksData;

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-4 gap-4">
        <div className="bg-green-50 rounded-lg p-4 text-center">
          <div className="text-3xl font-black text-green-600">{getContainersCountByState('running')}</div>
          <div className="text-sm text-green-700 mt-1">Running</div>
        </div>
        <div className="bg-amber-50 rounded-lg p-4 text-center">
          <div className="text-3xl font-black text-amber-600">{getContainersCountByState('waiting')}</div>
          <div className="text-sm text-amber-700 mt-1">Waiting</div>
        </div>
        <div className="bg-red-50 rounded-lg p-4 text-center">
          <div className="text-3xl font-black text-red-600">{getContainersCountByState('terminated')}</div>
          <div className="text-sm text-red-700 mt-1">Terminated</div>
        </div>
        <div className="bg-blue-50 rounded-lg p-4 text-center">
          <div className="text-3xl font-black text-blue-600">{filteredContainers.length}</div>
          <div className="text-sm text-blue-700 mt-1">Total</div>
        </div>
      </div>

      <div className="bg-white rounded-xl shadow-sm p-6 border border-gray-100">
        <div className="flex gap-4 mb-4">
          <input
            type="text"
            placeholder="Search containers..."
            value={containerSearchQuery}
            onChange={(e) => setContainerSearchQuery(e.target.value)}
            className="flex-1 px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500"
          />
          <select
            value={containerNamespaceFilter}
            onChange={(e) => setContainerNamespaceFilter(e.target.value)}
            className="px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500"
          >
            <option value="">All Namespaces</option>
            {uniqueNamespaces.map((ns) => (
              <option key={ns} value={ns}>
                {ns}
              </option>
            ))}
          </select>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full data-table">
            <thead>
              <tr>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-600 uppercase">Container</th>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-600 uppercase">Pod</th>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-600 uppercase">Namespace</th>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-600 uppercase">State</th>
                <th className="text-right px-4 py-3 text-xs font-semibold text-gray-600 uppercase">Restarts</th>
              </tr>
            </thead>
            <tbody>
              {filteredContainers.slice(0, 50).map((container) => (
                <tr key={container.id} className="border-t border-gray-100 hover:bg-gray-50">
                  <td className="px-4 py-3 text-sm font-medium text-gray-900">{container.name}</td>
                  <td className="px-4 py-3 text-sm text-gray-600">{container.pod}</td>
                  <td className="px-4 py-3 text-sm text-gray-600">{container.namespace}</td>
                  <td className="px-4 py-3 text-sm">
                    <span
                      className={`px-2 py-1 rounded-full text-xs font-semibold ${
                        container.state === 'running'
                          ? 'bg-green-100 text-green-700'
                          : container.state === 'waiting'
                          ? 'bg-amber-100 text-amber-700'
                          : 'bg-red-100 text-red-700'
                      }`}
                    >
                      {container.state}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-sm text-right font-medium">{container.restartCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
};

export default EksContainersTab;
