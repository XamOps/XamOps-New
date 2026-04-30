/**
 * EKS Karpenter Tab
 *
 * Advanced autoscaling monitoring with modern NodePool visualizations
 */

import React from 'react';

// Derive Karpenter node info from raw node labels when dashboard.karpenter is absent
function deriveKarpenterFromNodes(dashboard: any) {
  const allNodes: any[] = dashboard?.nodeMetrics?.nodes || dashboard?.resourceUtilization?.nodes || [];
  const karpenterNodes = allNodes.filter((n: any) => {
    const labels = n.labels || {};
    return labels['karpenter.sh/nodepool'] || labels['karpenter.sh/registered'] || n.capacityType === 'spot';
  });
  if (karpenterNodes.length === 0) return null;

  // Build a synthetic karpenter summary from node labels
  const nodePools = [...new Set(karpenterNodes.map((n: any) => n.labels?.['karpenter.sh/nodepool'] || n.labels?.['karpenter.sh/provisioner-name'] || 'default'))];
  const spotNodes = karpenterNodes.filter((n: any) => (n.labels?.['karpenter.sh/capacity-type'] || n.capacityType) === 'spot');
  const onDemandNodes = karpenterNodes.filter((n: any) => {
    const ct = n.labels?.['karpenter.sh/capacity-type'] || n.capacityType;
    return ct === 'on-demand' || ct === 'ON_DEMAND';
  });

  return {
    _derived: true, // flag: built from node labels, not from dedicated Karpenter API
    healthy: true,
    nodePools: nodePools.map(np => ({ name: np, consolidationPolicy: 'WhenEmptyOrUnderutilized' })),
    ec2NodeClasses: [],
    nodeClaims: karpenterNodes.map((n: any) => ({
      name: n.name || n.nodeName,
      instanceType: n.instanceType || n.labels?.['node.kubernetes.io/instance-type'] || 'Unknown',
      capacityType: n.labels?.['karpenter.sh/capacity-type'] || n.capacityType || 'on-demand',
      zone: n.zone || n.labels?.['topology.kubernetes.io/zone'] || 'N/A',
      phase: 'Launched',
    })),
    spotNodesCount: spotNodes.length,
    onDemandNodesCount: onDemandNodes.length,
    totalNodesCount: karpenterNodes.length,
  };
}

const EksKarpenterTab: React.FC<{ eksData: any }> = ({ eksData }) => {
  const { dashboard, karpenterProvisioningStatus } = eksData;
  const karpenter = dashboard?.karpenter || deriveKarpenterFromNodes(dashboard);

  if (!karpenter) {
    // Check if Karpenter controller pods are running (installed but no managed nodes yet)
    const allPods: any[] = dashboard?.k8sPods || [];
    const karpenterController = allPods.find((p: any) =>
      p.namespace === 'kube-system' &&
      p.name?.toLowerCase().includes('karpenter') &&
      p.phase === 'Running'
    );

    if (karpenterController) {
      return (
        <div className="space-y-4">
          <div className="bg-white rounded-xl shadow-sm p-10 border border-gray-200 text-center">
            <div className="w-20 h-20 rounded-xl bg-blue-50 flex items-center justify-center mx-auto mb-5 border border-blue-100">
              <i className="fas fa-dharmachakra text-blue-500 text-3xl"></i>
            </div>
            <h3 className="text-xl font-bold text-gray-900 mb-2">Karpenter Installed</h3>
            <p className="text-[#57606a] text-sm max-w-md mx-auto">
              Karpenter controller is running but no Karpenter-managed nodes have been provisioned yet.
              Current nodes are managed by your EKS managed node group.
            </p>
            <div className="mt-5 inline-flex items-center gap-2 px-4 py-2 bg-green-50 border border-green-200 rounded-lg">
              <div className="w-2 h-2 rounded-full bg-green-500 animate-pulse"></div>
              <span className="text-sm font-semibold text-green-700">Controller Running — {karpenterController.name}</span>
            </div>
            <div className="mt-5 p-4 bg-[#f6f8fa] border border-[#e8ecf0] rounded-lg max-w-lg mx-auto text-left">
              <p className="text-xs font-semibold text-[#1a1f2e] mb-2">To enable Karpenter provisioning:</p>
              <ul className="text-xs text-[#57606a] space-y-1.5 list-disc list-inside">
                <li>Create a <code className="bg-white border border-[#d0d7de] px-1 rounded">NodePool</code> resource to define provisioning rules</li>
                <li>Create an <code className="bg-white border border-[#d0d7de] px-1 rounded">EC2NodeClass</code> with your AMI and subnet settings</li>
                <li>Karpenter-provisioned nodes (with <code className="bg-white border border-[#d0d7de] px-1 rounded">karpenter.sh/nodepool</code> labels) will appear here automatically</li>
              </ul>
            </div>
          </div>
        </div>
      );
    }

    return (
      <div className="bg-white rounded-xl shadow-sm p-12 border border-gray-200 text-center">
        <div className="w-20 h-20 rounded-lg bg-amber-100 flex items-center justify-center mx-auto mb-6">
          <i className="fas fa-exclamation-triangle text-amber-600 text-3xl"></i>
        </div>
        <h3 className="text-xl font-bold text-gray-900 mb-3">Karpenter Not Detected</h3>
        <p className="text-gray-600">Karpenter autoscaling is not installed on this cluster.</p>
        <div className="mt-6 p-4 bg-amber-50 rounded-lg border border-amber-200">
          <p className="text-sm text-gray-700">
            <i className="fas fa-info-circle text-amber-600 mr-2"></i>
            Install Karpenter to enable intelligent node autoscaling
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-8">
      {/* Notice banner when data is derived from node labels instead of the Karpenter API */}
      {karpenter._derived && (
        <div className="bg-blue-50 border border-blue-200 rounded-xl p-4 flex items-start gap-3">
          <i className="fas fa-info-circle text-blue-500 mt-0.5"></i>
          <div>
            <p className="font-semibold text-blue-800 text-sm">Showing node-label data</p>
            <p className="text-blue-700 text-xs mt-0.5">
              The backend has not yet returned Karpenter API data (NodePools / NodeClaims). The view below is derived from Karpenter node labels collected by the agent. NodePool limits and EC2NodeClass details will appear once the backend syncs Karpenter CRD data.
            </p>
          </div>
        </div>
      )}
      {/* Karpenter Status Hero Section */}
      <section className="bg-white rounded-xl shadow-sm p-6 border border-gray-200">
        <div className="flex items-center justify-between mb-6">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-lg bg-purple-50 flex items-center justify-center">
              <i className="fas fa-rocket text-purple-600 text-lg"></i>
            </div>
            <div>
              <h3 className="text-lg font-bold text-gray-900">Karpenter Autoscaler</h3>
              <p className="text-gray-600 text-sm">Intelligent node provisioning and optimization</p>
            </div>
          </div>

          <div className={`px-4 py-2 rounded-full flex items-center gap-2 ${karpenter.healthy ? 'bg-green-100 border border-green-200' : 'bg-red-100 border border-red-200'
            }`}>
            <div className={`w-2 h-2 rounded-full ${karpenter.healthy ? 'bg-green-600' : 'bg-red-600'
              }`}></div>
            <span className={`font-semibold text-sm ${karpenter.healthy ? 'text-green-700' : 'text-red-700'
              }`}>
              {karpenter.healthy ? 'Active' : 'Offline'}
            </span>
          </div>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 pt-6 border-t border-gray-200">
          {/* Controller Status */}
          <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
            <div className="flex items-center gap-2 mb-3">
              <div className="w-8 h-8 rounded-lg bg-white flex items-center justify-center border border-gray-200">
                <i className="fas fa-cogs text-purple-600"></i>
              </div>
              <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Controller</span>
            </div>
            <div className="text-2xl font-bold text-gray-900 mb-1">
              {karpenter.healthy ? 'Healthy' : 'Unhealthy'}
            </div>
            <div className="text-xs text-gray-600">System status</div>
          </div>

          {/* Provisioning Status */}
          <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
            <div className="flex items-center gap-2 mb-3">
              <div className="w-8 h-8 rounded-lg bg-white flex items-center justify-center border border-gray-200">
                <i className="fas fa-plus-circle text-purple-600"></i>
              </div>
              <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Provisioning</span>
            </div>
            <div className={`text-2xl font-bold mb-1 ${karpenterProvisioningStatus.canProvision ? 'text-gray-900' : 'text-amber-600'
              }`}>
              {karpenterProvisioningStatus.canProvision ? 'Enabled' : 'Disabled'}
            </div>
            <div className="text-xs text-gray-600">{karpenterProvisioningStatus.reason}</div>
          </div>

          {/* Version */}
          <div className="bg-gray-50 rounded-lg p-4 border border-gray-200 overflow-hidden">
            <div className="flex items-center gap-2 mb-3">
              <div className="w-8 h-8 rounded-lg bg-white flex items-center justify-center border border-gray-200">
                <i className="fas fa-code-branch text-purple-600"></i>
              </div>
              <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Version</span>
            </div>
            <div className="text-xl font-bold text-gray-900 mb-1 font-mono truncate" title={karpenter.version || 'N/A'}>
              {karpenter.version || 'N/A'}
            </div>
            <div className="text-xs text-gray-600">Installed release</div>
          </div>
        </div>
      </section>

      {/* Summary Metrics Cards */}
      <section className="bg-white rounded-xl shadow-sm p-6 border border-gray-200">
        <h3 className="text-lg font-bold text-gray-900 mb-6 flex items-center gap-2">
          <div className="w-8 h-8 rounded-lg bg-purple-50 flex items-center justify-center">
            <i className="fas fa-chart-pie text-purple-600"></i>
          </div>
          Karpenter Metrics Overview
        </h3>

        <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-4">
          {/* Total NodePools */}
          <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
            <div className="flex items-center gap-2 mb-2">
              <div className="w-8 h-8 rounded-lg bg-purple-100 flex items-center justify-center">
                <i className="fas fa-layer-group text-purple-600"></i>
              </div>
            </div>
            <div className="text-2xl font-bold text-gray-900">{karpenter.nodePools?.length || 0}</div>
            <div className="text-xs text-gray-600 mt-1">Node Pools</div>
          </div>

          {/* Total EC2NodeClasses */}
          <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
            <div className="flex items-center gap-2 mb-2">
              <div className="w-8 h-8 rounded-lg bg-indigo-100 flex items-center justify-center">
                <i className="fas fa-microchip text-indigo-600"></i>
              </div>
            </div>
            <div className="text-2xl font-bold text-gray-900">{karpenter.ec2NodeClasses?.length || 0}</div>
            <div className="text-xs text-gray-600 mt-1">EC2 Node Classes</div>
          </div>

          {/* Active Claims */}
          <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
            <div className="flex items-center gap-2 mb-2">
              <div className="w-8 h-8 rounded-lg bg-green-100 flex items-center justify-center">
                <i className="fas fa-tasks text-green-600"></i>
              </div>
            </div>
            <div className="text-2xl font-bold text-gray-900">{karpenter.totalActiveClaims || 0}</div>
            <div className="text-xs text-gray-600 mt-1">Active Claims</div>
          </div>

          {/* Pending Claims */}
          <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
            <div className="flex items-center gap-2 mb-2">
              <div className="w-8 h-8 rounded-lg bg-amber-100 flex items-center justify-center">
                <i className="fas fa-clock text-amber-600"></i>
              </div>
            </div>
            <div className="text-2xl font-bold text-gray-900">{karpenter.totalPendingClaims || 0}</div>
            <div className="text-xs text-gray-600 mt-1">Pending Claims</div>
          </div>

          {/* Spot Nodes */}
          <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
            <div className="flex items-center gap-2 mb-2">
              <div className="w-8 h-8 rounded-lg bg-purple-100 flex items-center justify-center">
                <i className="fas fa-bolt text-purple-600"></i>
              </div>
            </div>
            <div className="text-2xl font-bold text-gray-900">{karpenter.spotNodesCount || 0}</div>
            <div className="text-xs text-gray-600 mt-1">Spot Nodes</div>
          </div>

          {/* On-Demand Nodes */}
          <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
            <div className="flex items-center gap-2 mb-2">
              <div className="w-8 h-8 rounded-lg bg-blue-100 flex items-center justify-center">
                <i className="fas fa-server text-blue-600"></i>
              </div>
            </div>
            <div className="text-2xl font-bold text-gray-900">{karpenter.onDemandNodesCount || 0}</div>
            <div className="text-xs text-gray-600 mt-1">On-Demand Nodes</div>
          </div>
        </div>

        {karpenter.dailyCostKarpenterNodes > 0 && (
          <div className="mt-4 p-4 bg-blue-50 rounded-lg border border-blue-200">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <i className="fas fa-dollar-sign text-blue-600"></i>
                <span className="text-sm font-semibold text-gray-700">Daily Cost (Karpenter Nodes)</span>
              </div>
              <div className="text-xl font-bold text-gray-900">
                ${karpenter.dailyCostKarpenterNodes.toFixed(2)}
              </div>
            </div>
          </div>
        )}
      </section>

      {/* Node Claims Table */}
      {karpenter.nodeClaims && karpenter.nodeClaims.length > 0 && (
        <section className="bg-white rounded-xl shadow-sm p-6 border border-gray-200">
          <h3 className="text-lg font-bold text-gray-900 mb-6 flex items-center gap-2">
            <div className="w-8 h-8 rounded-lg bg-green-50 flex items-center justify-center">
              <i className="fas fa-list text-green-600"></i>
            </div>
            Node Claims
            <span className="text-sm font-normal text-gray-600">
              ({karpenter.nodeClaims.length} claims)
            </span>
          </h3>

          <div className="overflow-x-auto">
            <table className="w-full">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  <th className="px-4 py-3 text-left text-xs font-semibold text-gray-600 uppercase tracking-wide">Claim Name</th>
                  <th className="px-4 py-3 text-left text-xs font-semibold text-gray-600 uppercase tracking-wide">NodePool</th>
                  <th className="px-4 py-3 text-left text-xs font-semibold text-gray-600 uppercase tracking-wide">Phase</th>
                  <th className="px-4 py-3 text-left text-xs font-semibold text-gray-600 uppercase tracking-wide">Instance Type</th>
                  <th className="px-4 py-3 text-left text-xs font-semibold text-gray-600 uppercase tracking-wide">Capacity</th>
                  <th className="px-4 py-3 text-left text-xs font-semibold text-gray-600 uppercase tracking-wide">Zone</th>
                  <th className="px-4 py-3 text-left text-xs font-semibold text-gray-600 uppercase tracking-wide">Node Name</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {karpenter.nodeClaims.map((claim: any, index: number) => (
                  <tr key={index} className="hover:bg-gray-50 transition-colors">
                    <td className="px-4 py-3 text-sm font-medium text-gray-900">{claim.name}</td>
                    <td className="px-4 py-3 text-sm text-gray-700">{claim.nodePoolName}</td>
                    <td className="px-4 py-3">
                      <span className={`px-2 py-1 rounded-full text-xs font-semibold ${claim.phase === 'Provisioned' || claim.phase === 'Active'
                        ? 'bg-green-100 text-green-700'
                        : 'bg-amber-100 text-amber-700'
                        }`}>
                        {claim.phase}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-sm font-mono text-gray-700">{claim.instanceType}</td>
                    <td className="px-4 py-3">
                      <span className={`px-2 py-1 rounded-full text-xs font-semibold ${claim.capacityType === 'spot'
                        ? 'bg-purple-100 text-purple-700'
                        : 'bg-blue-100 text-blue-700'
                        }`}>
                        {claim.capacityType === 'spot' ? '⚡ Spot' : '💎 On-Demand'}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-sm text-gray-700">{claim.zone}</td>
                    <td className="px-4 py-3 text-sm text-gray-700">{claim.nodeName || '-'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {/* Node Pools */}
      <section className="bg-white rounded-xl shadow-sm p-6 border border-gray-200">
        <h3 className="text-lg font-bold text-gray-900 mb-6 flex items-center gap-2">
          <div className="w-8 h-8 rounded-lg bg-purple-50 flex items-center justify-center">
            <i className="fas fa-layer-group text-purple-600"></i>
          </div>
          Node Pools
          <span className="text-sm font-normal text-gray-600">
            ({karpenter.nodePools?.length || 0} pools)
          </span>
        </h3>

        {karpenter.nodePools && karpenter.nodePools.length > 0 ? (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {karpenter.nodePools.map((pool: any, index: number) => (
              <div key={index} className="bg-white rounded-lg p-6 border border-gray-200 hover:border-gray-300 hover:shadow-md transition-all">
                <div className="flex items-start justify-between gap-4 mb-4">
                  <div className="flex items-center gap-3">
                    <div className="w-10 h-10 rounded-lg bg-purple-100 flex items-center justify-center">
                      <i className="fas fa-swimming-pool text-purple-600"></i>
                    </div>
                    <div>
                      <h4 className="font-bold text-gray-900">{pool.name}</h4>
                      <p className="text-xs text-gray-600 uppercase">NodePool</p>
                    </div>
                  </div>
                  <div className="text-right">
                    <div className="text-3xl font-bold text-gray-900">{pool.nodeCount}</div>
                    <div className="text-xs text-gray-600 uppercase">Nodes</div>
                  </div>
                </div>

                <div className="grid grid-cols-2 gap-3 mb-4">
                  <div className="bg-gray-50 rounded-lg p-3 border border-gray-200">
                    <div className="text-[10px] text-gray-500 uppercase mb-1 font-bold">Consolidation</div>
                    <div className="text-sm font-semibold text-gray-900">{pool.consolidationPolicy || 'N/A'}</div>
                  </div>
                  <div className="bg-gray-50 rounded-lg p-3 border border-gray-200">
                    <div className="text-[10px] text-gray-500 uppercase mb-1 font-bold">Creation Status</div>
                    <div className="text-sm font-semibold text-green-600">Active</div>
                  </div>
                </div>

                <div className="space-y-2">
                  <div className="flex items-center justify-between text-xs">
                    <span className="text-gray-500">Capacity Types:</span>
                    <div className="flex gap-1">
                      {pool.capacityTypes?.map((t: string) => (
                        <span key={t} className="px-1.5 py-0.5 bg-blue-50 text-blue-700 rounded text-[10px] font-bold uppercase">{t}</span>
                      ))}
                    </div>
                  </div>
                  <div className="flex items-center justify-between text-xs">
                    <span className="text-gray-500">Instance Types:</span>
                    <span className="text-gray-900 font-medium truncate max-w-[150px]" title={pool.instanceTypes?.join(', ')}>
                      {pool.instanceTypes?.slice(0, 3).join(', ')}{pool.instanceTypes?.length > 3 ? '...' : ''}
                    </span>
                  </div>
                  <div className="flex items-center justify-between text-xs">
                    <span className="text-gray-500">Limits:</span>
                    <span className="text-gray-900 font-bold">{pool.cpuLimit} CPU / {pool.memoryLimit}</span>
                  </div>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <div className="text-center py-12 bg-gray-50 rounded-lg border border-dashed border-gray-300">
            <div className="w-16 h-16 rounded-lg bg-gray-200 flex items-center justify-center mx-auto mb-3">
              <i className="fas fa-layer-group text-gray-400 text-2xl"></i>
            </div>
            <h3 className="text-lg font-bold text-gray-700">No Node Pools</h3>
            <p className="text-gray-600 text-sm mt-1">No node pools are currently configured</p>
          </div>
        )}
      </section>

      {/* EC2 Node Classes */}
      <section className="bg-white rounded-xl shadow-sm p-6 border border-gray-200">
        <h3 className="text-lg font-bold text-gray-900 mb-6 flex items-center gap-2">
          <div className="w-8 h-8 rounded-lg bg-indigo-50 flex items-center justify-center">
            <i className="fas fa-microchip text-indigo-600"></i>
          </div>
          EC2 Node Classes
          <span className="text-sm font-normal text-gray-600">
            ({karpenter.ec2NodeClasses?.length || 0} classes)
          </span>
        </h3>

        {karpenter.ec2NodeClasses && karpenter.ec2NodeClasses.length > 0 ? (
          <div className="space-y-4">
            {karpenter.ec2NodeClasses.map((nodeClass: any, index: number) => (
              <div key={index} className="bg-white rounded-lg p-6 border border-gray-200 hover:border-gray-300 hover:shadow-md transition-all">
                <div className="flex items-start gap-4">
                  <div className={`w-12 h-12 rounded-lg flex items-center justify-center flex-shrink-0 ${nodeClass.ready ? 'bg-green-100' : 'bg-red-100'
                    }`}>
                    <i className={`fas fa-server text-lg ${nodeClass.ready ? 'text-green-600' : 'text-red-600'
                      }`}></i>
                  </div>

                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 mb-3 flex-wrap">
                      <h4 className="font-bold text-gray-900">{nodeClass.name}</h4>
                      <span className={`px-2 py-1 rounded-full text-xs font-semibold ${nodeClass.ready ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'
                        }`}>
                        {nodeClass.ready ? 'Ready' : 'Not Ready'}
                      </span>
                    </div>

                    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4 text-sm">
                      <div className="flex flex-col gap-1">
                        <div className="flex items-center gap-2">
                          <i className="fas fa-compact-disc text-indigo-600 w-4"></i>
                          <span className="text-gray-500 text-xs uppercase font-bold">AMI Family</span>
                        </div>
                        <span className="font-semibold text-gray-900 ml-6">{nodeClass.amiFamily}</span>
                      </div>

                      <div className="flex flex-col gap-1">
                        <div className="flex items-center gap-2">
                          <i className="fas fa-network-wired text-indigo-600 w-4"></i>
                          <span className="text-gray-500 text-xs uppercase font-bold">Subnets</span>
                        </div>
                        <div className="flex flex-wrap gap-1 ml-6">
                          {nodeClass.subnets?.slice(0, 2).map((s: any) => (
                            <span key={s.id} className="text-[10px] bg-gray-100 px-1.5 py-0.5 rounded font-mono">{s.id}</span>
                          ))}
                          {nodeClass.subnets?.length > 2 && <span className="text-[10px] text-gray-400">+{nodeClass.subnets.length - 2} more</span>}
                        </div>
                      </div>

                      <div className="flex flex-col gap-1">
                        <div className="flex items-center gap-2">
                          <i className="fas fa-shield-alt text-indigo-600 w-4"></i>
                          <span className="text-gray-500 text-xs uppercase font-bold">Sec Groups</span>
                        </div>
                        <div className="flex flex-wrap gap-1 ml-6">
                          {nodeClass.securityGroups?.slice(0, 2).map((sg: any) => (
                            <span key={sg.id} className="text-[10px] bg-gray-100 px-1.5 py-0.5 rounded font-mono">{sg.id}</span>
                          ))}
                          {nodeClass.securityGroups?.length > 2 && <span className="text-[10px] text-gray-400">+{nodeClass.securityGroups.length - 2} more</span>}
                        </div>
                      </div>
                    </div>

                    {!nodeClass.ready && nodeClass.readyMessage && (
                      <div className="mt-3 text-sm text-red-700 bg-red-50 border border-red-200 p-3 rounded-lg flex items-start gap-2">
                        <i className="fas fa-exclamation-circle flex-shrink-0 mt-0.5"></i>
                        <span>{nodeClass.readyMessage}</span>
                      </div>
                    )}
                  </div>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <div className="text-center py-12 bg-gray-50 rounded-lg border border-dashed border-gray-300">
            <div className="w-16 h-16 rounded-lg bg-gray-200 flex items-center justify-center mx-auto mb-3">
              <i className="fas fa-microchip text-gray-400 text-2xl"></i>
            </div>
            <h3 className="text-lg font-bold text-gray-700">No EC2 Node Classes</h3>
            <p className="text-gray-600 text-sm mt-1">No EC2 node classes are configured</p>
          </div>
        )}
      </section>
    </div>
  );
};

export default EksKarpenterTab;
