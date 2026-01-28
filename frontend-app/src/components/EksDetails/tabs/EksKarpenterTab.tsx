/**
 * EKS Karpenter Tab
 */

import React from 'react';

const EksKarpenterTab: React.FC<{ eksData: any }> = ({ eksData }) => {
  const { dashboard, karpenterOverallStatus, karpenterProvisioningStatus } = eksData;
  const karpenter = dashboard?.karpenter;

  if (!karpenter) {
    return (
      <div className="bg-amber-50 rounded-xl shadow-sm p-8 border border-amber-200 text-center">
        <i className="fas fa-exclamation-triangle text-amber-500 text-4xl mb-4"></i>
        <h3 className="text-xl font-bold text-gray-900 mb-2">Karpenter Not Detected</h3>
        <p className="text-gray-600">Karpenter autoscaling is not installed on this cluster.</p>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Status Overview */}
      <div className="bg-white rounded-xl shadow-sm p-6 border border-gray-100">
        <h3 className="text-xl font-bold text-gray-900 mb-4">
          <i className="fas fa-rocket text-purple-500 mr-2"></i>
          Karpenter Status
        </h3>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <div className="p-4 bg-gray-50 rounded-lg">
            <div className="text-sm text-gray-600 mb-1">Controller Status</div>
            <div className="flex items-center gap-2">
              <span
                className={`w-3 h-3 rounded-full ${
                  karpenter.healthy ? 'bg-green-500' : 'bg-red-500'
                } animate-pulse`}
              ></span>
              <span className="font-bold text-gray-900">
                {karpenter.healthy ? 'Healthy' : 'Unhealthy'}
              </span>
            </div>
          </div>

          <div className="p-4 bg-gray-50 rounded-lg">
            <div className="text-sm text-gray-600 mb-1">Provisioning</div>
            <div
              className={`font-bold ${
                karpenterProvisioningStatus.canProvision ? 'text-green-600' : 'text-amber-600'
              }`}
            >
              {karpenterProvisioningStatus.canProvision ? 'Enabled' : 'Disabled'}
            </div>
            <div className="text-xs text-gray-500 mt-1">{karpenterProvisioningStatus.reason}</div>
          </div>

          <div className="p-4 bg-gray-50 rounded-lg">
            <div className="text-sm text-gray-600 mb-1">Version</div>
            <div className="font-bold text-gray-900">{karpenter.version || 'N/A'}</div>
          </div>
        </div>
      </div>

      {/* Node Pools */}
      <div className="bg-white rounded-xl shadow-sm p-6 border border-gray-100">
        <h3 className="text-xl font-bold text-gray-900 mb-4">Node Pools</h3>
        {karpenter.nodePools && karpenter.nodePools.length > 0 ? (
          <div className="space-y-3">
            {karpenter.nodePools.map((pool: any, index: number) => (
              <div key={index} className="p-4 bg-gray-50 rounded-lg flex justify-between items-center">
                <div>
                  <div className="font-bold text-gray-900">{pool.name}</div>
                  <div className="text-sm text-gray-600 mt-1">
                    CPU: {pool.cpuLimit} | Memory: {pool.memoryLimit}
                  </div>
                </div>
                <div className="text-right">
                  <div className="text-2xl font-black text-indigo-600">{pool.nodeCount}</div>
                  <div className="text-xs text-gray-500">Nodes</div>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <p className="text-gray-500 text-center py-8">No node pools configured</p>
        )}
      </div>

      {/* EC2 Node Classes */}
      <div className="bg-white rounded-xl shadow-sm p-6 border border-gray-100">
        <h3 className="text-xl font-bold text-gray-900 mb-4">EC2 Node Classes</h3>
        {karpenter.ec2NodeClasses && karpenter.ec2NodeClasses.length > 0 ? (
          <div className="space-y-3">
            {karpenter.ec2NodeClasses.map((nodeClass: any, index: number) => (
              <div key={index} className="p-4 bg-gray-50 rounded-lg">
                <div className="flex justify-between items-start">
                  <div>
                    <div className="font-bold text-gray-900">{nodeClass.name}</div>
                    <div className="text-sm text-gray-600 mt-1">
                      AMI Family: {nodeClass.amiFamily}
                    </div>
                    <div className="text-sm text-gray-600">
                      Instance Profile: {nodeClass.instanceProfile}
                    </div>
                  </div>
                  <span
                    className={`px-3 py-1 rounded-full text-xs font-bold ${
                      nodeClass.ready
                        ? 'bg-green-100 text-green-700'
                        : 'bg-red-100 text-red-700'
                    }`}
                  >
                    {nodeClass.ready ? 'Ready' : 'Not Ready'}
                  </span>
                </div>
                {!nodeClass.ready && nodeClass.readyMessage && (
                  <div className="mt-2 text-xs text-red-600 bg-red-50 p-2 rounded">
                    {nodeClass.readyMessage}
                  </div>
                )}
              </div>
            ))}
          </div>
        ) : (
          <p className="text-gray-500 text-center py-8">No EC2 node classes configured</p>
        )}
      </div>
    </div>
  );
};

export default EksKarpenterTab;
