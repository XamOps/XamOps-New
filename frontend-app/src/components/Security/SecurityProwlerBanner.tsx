/**
 * Security Prowler Status Banner Component
 *
 * Shows a banner when Prowler deep scan is running
 */

import React from 'react';
import type { ProwlerStatus } from '../../types/security';

interface SecurityProwlerBannerProps {
  prowlerStatus: ProwlerStatus;
}

const SecurityProwlerBanner: React.FC<SecurityProwlerBannerProps> = ({ prowlerStatus }) => {
  if (prowlerStatus.status !== 'RUNNING') {
    return null;
  }

  return (
    <div className="security-prowler-banner">
      <div className="security-prowler-banner-content">
        <div className="security-prowler-banner-icon">
          <i className="fas fa-circle-notch fa-spin"></i>
        </div>
        <div className="security-prowler-banner-text">
          <p className="security-prowler-banner-title">Deep Scan in Progress (Prowler)</p>
          <p className="security-prowler-banner-message">
            {prowlerStatus.message || 'Scanning cloud resources...'}
          </p>
        </div>
      </div>
    </div>
  );
};

export default SecurityProwlerBanner;
