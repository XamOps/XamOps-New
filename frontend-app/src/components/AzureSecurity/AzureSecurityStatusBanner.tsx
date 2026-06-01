import React from 'react';
import { Icon } from '@iconify/react';
import type { AzureSecurityStatus } from '../../types/azureSecurity';

interface AzureSecurityStatusBannerProps {
  status: AzureSecurityStatus | null;
}

const AzureSecurityStatusBanner: React.FC<AzureSecurityStatusBannerProps> = ({ status }) => {
  if (!status || status === 'OK') return null;

  if (status === 'PERMISSION_ERROR') {
    return (
      <div className="flex items-start gap-3 bg-amber-50 border border-amber-200 text-amber-800 rounded-xl px-5 py-4 text-sm shadow-sm">
        <Icon icon="mdi:lock-outline" width={16} className="text-amber-500 mt-0.5 shrink-0" />
        <div>
          <p className="font-semibold">Security Reader role required</p>
          <p className="text-amber-700 text-xs mt-0.5">
            Assign the <strong>Security Reader</strong> role to your service principal on this subscription
            to enable Microsoft Defender for Cloud data (Secure Score, Alerts, Assessments).
          </p>
        </div>
      </div>
    );
  }

  if (status === 'NO_DATA') {
    return (
      <div className="flex items-start gap-3 bg-blue-50 border border-blue-200 text-blue-800 rounded-xl px-5 py-4 text-sm shadow-sm">
        <Icon icon="mdi:information-outline" width={16} className="text-blue-500 mt-0.5 shrink-0" />
        <div>
          <p className="font-semibold">No security data available</p>
          <p className="text-blue-700 text-xs mt-0.5">
            Microsoft Defender for Cloud may not be enabled for this subscription, or no findings were detected.
          </p>
        </div>
      </div>
    );
  }

  if (status === 'API_ERROR') {
    return (
      <div className="flex items-start gap-3 bg-red-50 border border-red-200 text-red-800 rounded-xl px-5 py-4 text-sm shadow-sm">
        <Icon icon="mdi:alert-circle-outline" width={16} className="text-red-500 mt-0.5 shrink-0" />
        <div>
          <p className="font-semibold">Failed to load security data</p>
          <p className="text-red-700 text-xs mt-0.5">
            An error occurred while fetching data from Microsoft Defender for Cloud. Try refreshing.
          </p>
        </div>
      </div>
    );
  }

  return null;
};

export default AzureSecurityStatusBanner;
