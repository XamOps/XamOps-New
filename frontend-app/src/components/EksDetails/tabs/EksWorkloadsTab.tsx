/**
 * EKS Workloads Tab - Placeholder
 */

import React from 'react';

const EksWorkloadsTab: React.FC<{ eksData: any }> = ({ eksData }) => {
  const { dashboard } = eksData;

  return (
    <div className="bg-white rounded-xl shadow-sm p-8 border border-gray-100">
      <h3 className="text-xl font-bold text-gray-900 mb-4">
        <i className="fas fa-cubes text-purple-500 mr-2"></i>
        Workloads
      </h3>
      <p className="text-gray-600 mb-6">Deployment and workload monitoring coming soon...</p>
      <div className="text-center p-8 bg-gray-50 rounded-lg">
        <div className="text-4xl font-black text-gray-900 mb-2">
          {dashboard?.workloadHealth?.deployments?.length || 0}
        </div>
        <div className="text-gray-600">Total Deployments</div>
      </div>
    </div>
  );
};

export default EksWorkloadsTab;
