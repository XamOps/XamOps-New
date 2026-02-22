import React from 'react';
import type { AzurePerformanceStatus } from '../../types/azurePerformance';

interface AzurePerformanceStatusBannerProps {
  status: AzurePerformanceStatus | null;
}

const AzurePerformanceStatusBanner: React.FC<AzurePerformanceStatusBannerProps> = ({ status }) => {
  if (!status || status === 'OK') return null;

  if (status === 'PERMISSION_ERROR') {
    return (
      <div className="flex items-start gap-3 bg-amber-50 border border-amber-200 text-amber-800 rounded-xl px-5 py-4 text-sm shadow-sm">
        <i className="fas fa-lock text-amber-500 mt-0.5 flex-shrink-0"></i>
        <div>
          <p className="font-semibold">Advisor Reviews Reader role required</p>
          <p className="text-amber-700 text-xs mt-0.5">
            Assign the <strong>Advisor Reviews Reader</strong> or <strong>Reader</strong> role to your service principal
            on this subscription to enable Azure Advisor Performance insights.
          </p>
        </div>
      </div>
    );
  }

  if (status === 'NO_DATA') {
    return (
      <div className="flex items-start gap-3 bg-green-50 border border-green-200 text-green-800 rounded-xl px-5 py-4 text-sm shadow-sm">
        <i className="fas fa-check-circle text-green-500 mt-0.5 flex-shrink-0"></i>
        <div>
          <p className="font-semibold">No performance issues detected</p>
          <p className="text-green-700 text-xs mt-0.5">
            Azure Advisor found no performance recommendations for this subscription. Your resources are well-optimised.
          </p>
        </div>
      </div>
    );
  }

  if (status === 'API_ERROR') {
    return (
      <div className="flex items-start gap-3 bg-red-50 border border-red-200 text-red-800 rounded-xl px-5 py-4 text-sm shadow-sm">
        <i className="fas fa-exclamation-circle text-red-500 mt-0.5 flex-shrink-0"></i>
        <div>
          <p className="font-semibold">Failed to load performance data</p>
          <p className="text-red-700 text-xs mt-0.5">
            An error occurred while fetching data from Azure Advisor. Try refreshing.
          </p>
        </div>
      </div>
    );
  }

  return null;
};

export default AzurePerformanceStatusBanner;
