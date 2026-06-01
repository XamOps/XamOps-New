/**
 * AlertsEmptyState Component
 *
 * Empty state for when no alerts are found
 */

import React from 'react';
import { Icon } from '@iconify/react';

interface AlertsEmptyStateProps {
  onRefresh: () => void;
}

const AlertsEmptyState: React.FC<AlertsEmptyStateProps> = ({ onRefresh }) => {
  return (
    <div className="py-16 text-center">
      <div className="w-14 h-14 bg-emerald-50 border border-emerald-100 rounded-xl flex items-center justify-center mx-auto mb-3">
        <Icon icon="mdi:check-circle-outline" width={28} className="text-emerald-500" />
      </div>
      <h3 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">All Clear!</h3>
      <p className="text-[13px] text-[rgba(0,0,0,0.45)]">No critical alerts found for the selected filter.</p>
      <button
        onClick={onRefresh}
        className="mt-4 flex items-center gap-2 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] text-white text-xs font-medium rounded-md hover:bg-black transition-colors mx-auto"
      >
        <Icon icon="mdi:refresh" width={15} />
        <span>Refresh Check</span>
      </button>
    </div>
  );
};

export default AlertsEmptyState;
