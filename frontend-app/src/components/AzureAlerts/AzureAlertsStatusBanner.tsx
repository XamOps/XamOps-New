import React from 'react';
import type { AzureAlertsStatus } from '../../types/azureAlerts';

interface AzureAlertsStatusBannerProps {
  status: AzureAlertsStatus | null;
}

const AzureAlertsStatusBanner: React.FC<AzureAlertsStatusBannerProps> = ({ status }) => {
  if (!status || status === 'OK') return null;

  if (status === 'PERMISSION_ERROR') {
    return (
      <div className="flex items-start gap-3 bg-amber-50 border border-amber-200 text-amber-800 rounded-xl px-5 py-4 text-sm shadow-sm">
        <i className="fas fa-lock text-amber-500 mt-0.5 flex-shrink-0"></i>
        <div>
          <p className="font-semibold">Monitoring Reader role required</p>
          <p className="text-amber-700 text-xs mt-0.5">
            Assign the <strong>Monitoring Reader</strong> role to your service principal on this subscription
            to enable Azure Monitor alerts data.
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
          <p className="font-semibold">No active alerts</p>
          <p className="text-green-700 text-xs mt-0.5">
            Azure Monitor found no alerts for this subscription. All systems appear to be healthy.
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
          <p className="font-semibold">Failed to load alerts</p>
          <p className="text-red-700 text-xs mt-0.5">
            An error occurred while fetching data from Azure Monitor. Try refreshing.
          </p>
        </div>
      </div>
    );
  }

  return null;
};

export default AzureAlertsStatusBanner;
