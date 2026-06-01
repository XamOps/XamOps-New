import React from 'react';
import { Icon } from '@iconify/react';
import type { ProwlerStatus } from '../../types/security';

interface SecurityProwlerBannerProps {
  prowlerStatus: ProwlerStatus;
}

const SecurityProwlerBanner: React.FC<SecurityProwlerBannerProps> = ({ prowlerStatus }) => {
  if (prowlerStatus.status !== 'RUNNING') return null;

  return (
    <div className="flex items-center gap-3 bg-blue-50 border-b border-blue-100 px-6 py-3 text-sm text-blue-800">
      <Icon icon="mdi:loading" width={16} className="animate-spin text-blue-500 shrink-0" />
      <div>
        <span className="font-semibold">Deep Scan in Progress (Prowler) — </span>
        <span className="text-blue-700">{prowlerStatus.message || 'Scanning cloud resources...'}</span>
      </div>
    </div>
  );
};

export default SecurityProwlerBanner;
