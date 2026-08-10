/**
 * GKE Nodes Tab
 *
 * Section 1: Node Pools (always available from GCP API)
 * Section 2: Individual node health and metrics (Prometheus, connected clusters only)
 */

import React, { useState } from 'react';

const NODES_PER_PAGE = 5;

const getPoolStatusChip = (status: string) => {
  const s = status?.toUpperCase();
  if (s === 'RUNNING') return 'bg-green-100 text-green-700';
  if (s === 'PROVISIONING') return 'bg-blue-100 text-blue-700';
  if (s === 'RECONCILING') return 'bg-amber-100 text-amber-700';
  if (s === 'STOPPING') return 'bg-red-100 text-red-700';
  return 'bg-gray-100 text-gray-600';
};

const UtilBar: React.FC<{ value: number }> = ({ value }) => {
  const pct = Math.min(100, value);
  const color = pct >= 80 ? 'bg-red-500' : pct >= 60 ? 'bg-amber-500' : 'bg-indigo-500';
  return (
    <div className="flex items-center gap-1.5">
      <div className="flex-1 h-1.5 bg-gray-100 rounded-full overflow-hidden">
        <div className={`h-full rounded-full ${color}`} style={{ width: `${pct}%` }} />
      </div>
      <span className="text-xs text-gray-600 w-7 text-right">{Math.round(pct)}%</span>
    </div>
  );
};

const GkeNodesTab: React.FC<{ gkeData: any }> = ({ gkeData }) => {
  const { dashboard, sortedNodesByPressure, getNodeConditionCount } = gkeData;
  const [nodePage, setNodePage] = useState(0);

  const nodePools = dashboard?.nodePools ?? [];
  const hasAgentData = dashboard?.connected && dashboard?.nodeMetrics;
  const nodes = sortedNodesByPressure ?? [];
  const totalNodePages = Math.ceil(nodes.length / NODES_PER_PAGE);
  const paginatedNodes = nodes.slice(nodePage * NODES_PER_PAGE, (nodePage + 1) * NODES_PER_PAGE);

  return (
    <div className="space-y-8">
      {/* ====== Section 1: Node Pools ====== */}
      <section>
        <h2 className="text-base font-bold text-gray-700 mb-4 flex items-center gap-2">
          <i className="fas fa-layer-group text-indigo-500"></i>
          Node Pools
          <span className="text-xs font-normal text-gray-400 ml-1">(from GCP API)</span>
        </h2>

        {/* Summary Cards */}
        <div className="grid grid-cols-2 md:grid-cols-3 gap-4 mb-4">
          <div className="bg-white p-5 rounded-xl border border-gray-200 shadow-sm">
            <div className="flex items-center gap-3 mb-2">
              <div className="w-10 h-10 bg-blue-50 text-blue-600 rounded-lg flex items-center justify-center">
                <i className="fas fa-server"></i>
              </div>
              <span className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Node Pools</span>
            </div>
            <div className="text-2xl font-bold text-gray-900">{nodePools.length}</div>
          </div>
          <div className="bg-white p-5 rounded-xl border border-gray-200 shadow-sm">
            <div className="flex items-center gap-3 mb-2">
              <div className="w-10 h-10 bg-green-50 text-green-600 rounded-lg flex items-center justify-center">
                <i className="fas fa-check-circle"></i>
              </div>
              <span className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Total Nodes</span>
            </div>
            <div className="text-2xl font-bold text-gray-900">
              {nodePools.reduce((s: number, np: any) => s + (np.nodeCount || 0), 0)}
            </div>
          </div>
          <div className="bg-white p-5 rounded-xl border border-gray-200 shadow-sm">
            <div className="flex items-center gap-3 mb-2">
              <div className="w-10 h-10 bg-purple-50 text-purple-600 rounded-lg flex items-center justify-center">
                <i className="fas fa-expand-arrows-alt"></i>
              </div>
              <span className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Autoscaling</span>
            </div>
            <div className="text-2xl font-bold text-gray-900">
              {nodePools.filter((np: any) => np.autoscalingEnabled).length}
            </div>
          </div>
        </div>

        {nodePools.length === 0 ? (
          <div className="bg-white rounded-xl border border-gray-200 p-10 text-center text-gray-400">
            <i className="fas fa-server text-3xl mb-3 block"></i>
            No node pool data available
          </div>
        ) : (
          <div className="bg-white rounded-xl border border-gray-200 shadow-sm overflow-hidden">
            <div className="overflow-x-auto">
              <table className="w-full text-left border-collapse">
                <thead>
                  <tr className="bg-gray-50">
                    <th className="px-6 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider">Pool Name</th>
                    <th className="px-6 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider">Machine Type</th>
                    <th className="px-6 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider text-center">Nodes</th>
                    <th className="px-6 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider">Autoscaling</th>
                    <th className="px-6 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider">K8s Version</th>
                    <th className="px-6 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider">Disk</th>
                    <th className="px-6 py-4 text-xs font-bold text-gray-500 uppercase tracking-wider">Status</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {nodePools.map((pool: any, idx: number) => (
                    <tr key={idx} className="hover:bg-gray-50 transition-colors">
                      <td className="px-6 py-4">
                        <div className="flex items-center gap-3">
                          <div className="w-8 h-8 rounded bg-blue-50 flex items-center justify-center flex-shrink-0">
                            <i className="fas fa-server text-blue-500 text-sm"></i>
                          </div>
                          <span className="font-bold text-gray-900">{pool.name}</span>
                        </div>
                      </td>
                      <td className="px-6 py-4">
                        <span className="text-sm text-gray-700 font-mono bg-gray-100 px-2 py-1 rounded">{pool.machineType || '—'}</span>
                      </td>
                      <td className="px-6 py-4 text-center">
                        <span className="text-lg font-bold text-gray-900">{pool.nodeCount}</span>
                      </td>
                      <td className="px-6 py-4">
                        {pool.autoscalingEnabled ? (
                          <div>
                            <span className="inline-flex items-center gap-1 px-2 py-1 bg-purple-100 text-purple-700 text-xs font-bold rounded-full">
                              <i className="fas fa-expand-arrows-alt text-xs"></i> Enabled
                            </span>
                            <p className="text-xs text-gray-500 mt-1">{pool.minNodes} – {pool.maxNodes} nodes</p>
                          </div>
                        ) : (
                          <span className="text-xs text-gray-400">Disabled</span>
                        )}
                      </td>
                      <td className="px-6 py-4">
                        <span className="text-sm text-gray-600">{pool.version || '—'}</span>
                      </td>
                      <td className="px-6 py-4">
                        <span className="text-sm text-gray-600">{pool.diskSizeGb ? `${pool.diskSizeGb} GB` : '—'}</span>
                      </td>
                      <td className="px-6 py-4">
                        <span className={`inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-bold ${getPoolStatusChip(pool.status)}`}>
                          <div className="w-2 h-2 rounded-full bg-current"></div>
                          {pool.status || '—'}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </section>

      {/* ====== Section 2: Individual Nodes ====== */}
      <section>
        <h2 className="text-base font-bold text-gray-700 mb-4 flex items-center gap-2">
          <i className="fas fa-hdd text-indigo-500"></i>
          Individual Nodes
          <span className="text-xs font-normal text-gray-400 ml-1">(from agent)</span>
        </h2>

        {!hasAgentData ? (
          <div className="bg-white rounded-xl border border-amber-200 p-10 flex flex-col items-center text-center">
            <i className="fas fa-robot text-amber-300 text-4xl mb-3"></i>
            <h4 className="font-bold text-gray-700 mb-1">XamOps Agent Not Deployed</h4>
            <p className="text-gray-400 text-sm">Deploy the XamOps agent to see per-node health and resource metrics.</p>
          </div>
        ) : (
          <>
            {/* Health summary */}
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-4">
              {[
                { label: 'Ready', value: getNodeConditionCount('ready'), color: 'bg-green-50 text-green-600', icon: 'fa-check-circle' },
                { label: 'Disk Pressure', value: getNodeConditionCount('diskPressure'), color: 'bg-red-50 text-red-600', icon: 'fa-hdd' },
                { label: 'Memory Pressure', value: getNodeConditionCount('memoryPressure'), color: 'bg-amber-50 text-amber-600', icon: 'fa-memory' },
                { label: 'Total Nodes', value: nodes.length, color: 'bg-blue-50 text-blue-600', icon: 'fa-server' },
              ].map(({ label, value, color, icon }) => (
                <div key={label} className="bg-white p-4 rounded-xl border border-gray-200 shadow-sm">
                  <div className="flex items-center gap-3 mb-2">
                    <div className={`w-9 h-9 rounded-lg flex items-center justify-center ${color}`}>
                      <i className={`fas ${icon} text-sm`}></i>
                    </div>
                    <span className="text-xs font-semibold text-gray-500 uppercase tracking-wide">{label}</span>
                  </div>
                  <div className="text-2xl font-bold text-gray-900">{value}</div>
                </div>
              ))}
            </div>

            {/* Node Cards */}
            <div className="space-y-3">
              {paginatedNodes.map((node: any, i: number) => {
                const hasPressure = node.diskPressure || node.memoryPressure;
                return (
                  <div key={i} className={`bg-white rounded-xl border shadow-sm p-5 ${hasPressure ? 'border-red-200' : 'border-gray-200'}`}>
                    <div className="flex flex-col md:flex-row md:items-center gap-4">
                      <div className="flex items-center gap-3 flex-1 min-w-0">
                        <div className={`w-10 h-10 rounded-lg flex items-center justify-center flex-shrink-0 ${node.ready ? 'bg-green-50' : 'bg-red-50'}`}>
                          <i className={`fas fa-server text-sm ${node.ready ? 'text-green-600' : 'text-red-500'}`}></i>
                        </div>
                        <div className="min-w-0">
                          <div className="font-bold text-gray-900 text-sm truncate">{node.name}</div>
                          <div className="flex flex-wrap gap-1.5 mt-1">
                            <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-bold ${node.ready ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'}`}>
                              <div className="w-1.5 h-1.5 rounded-full bg-current"></div>
                              {node.ready ? 'Ready' : 'Not Ready'}
                            </span>
                            {node.diskPressure && (
                              <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-bold bg-red-100 text-red-700">
                                <i className="fas fa-hdd text-xs"></i> Disk Pressure
                              </span>
                            )}
                            {node.memoryPressure && (
                              <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-bold bg-amber-100 text-amber-700">
                                <i className="fas fa-memory text-xs"></i> Memory Pressure
                              </span>
                            )}
                          </div>
                        </div>
                      </div>

                      {/* Meta info */}
                      <div className="flex flex-wrap gap-x-6 gap-y-1 text-xs text-gray-500 md:text-right">
                        {node.zone && <span><i className="fas fa-map-marker-alt mr-1"></i>{node.zone}</span>}
                        {node.nodePool && <span><i className="fas fa-layer-group mr-1"></i>{node.nodePool}</span>}
                        {node.instanceType && <span><i className="fas fa-cog mr-1"></i>{node.instanceType}</span>}
                        {node.podCount !== undefined && <span><i className="fas fa-box mr-1"></i>{node.podCount} pods</span>}
                      </div>
                    </div>

                    {/* Resource bars */}
                    <div className="grid grid-cols-2 gap-4 mt-4">
                      <div>
                        <div className="flex justify-between text-xs text-gray-500 mb-1">
                          <span>CPU</span>
                          <span className="font-medium text-gray-700">{(node.cpuUsageMillicores ?? 0).toFixed(0)}m</span>
                        </div>
                        {/* cpuUsage/memoryUsage are already percentages (0-100) — both the
                            Prometheus path and the agent mapper store them that way. */}
                        <UtilBar value={Math.round(node.cpuUsage ?? 0)} />
                      </div>
                      <div>
                        <div className="flex justify-between text-xs text-gray-500 mb-1">
                          <span>Memory</span>
                          <span className="font-medium text-gray-700">{((node.memoryCapacity ?? 0) / 1073741824).toFixed(1)} GB cap.</span>
                        </div>
                        <UtilBar value={Math.round(node.memoryUsage ?? 0)} />
                      </div>
                    </div>
                  </div>
                );
              })}

              {nodes.length === 0 && (
                <div className="bg-white rounded-xl border border-gray-200 p-10 text-center text-gray-400">
                  <i className="fas fa-server text-3xl mb-3 block"></i>
                  No individual node data available
                </div>
              )}
            </div>

            {totalNodePages > 1 && (
              <div className="mt-4 flex items-center justify-between">
                <span className="text-sm text-gray-500">
                  {nodePage * NODES_PER_PAGE + 1}–{Math.min((nodePage + 1) * NODES_PER_PAGE, nodes.length)} of {nodes.length} nodes
                </span>
                <div className="flex gap-2">
                  <button disabled={nodePage === 0} onClick={() => setNodePage(p => p - 1)}
                    className="px-4 py-2 bg-white border border-gray-300 rounded-lg text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50">
                    Previous
                  </button>
                  <button disabled={nodePage === totalNodePages - 1} onClick={() => setNodePage(p => p + 1)}
                    className="px-4 py-2 bg-white border border-gray-300 rounded-lg text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50">
                    Next
                  </button>
                </div>
              </div>
            )}
          </>
        )}
      </section>
    </div>
  );
};

export default GkeNodesTab;
