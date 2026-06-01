import React from 'react';
import { Icon } from '@iconify/react';

interface SecurityEmptyStateProps {
  onRefresh: () => void;
}

const SecurityEmptyState: React.FC<SecurityEmptyStateProps> = ({ onRefresh }) => {
  return (
    <div className="text-center py-20 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm">
      <div className="w-14 h-14 rounded-xl bg-emerald-50 border border-emerald-100 flex items-center justify-center mx-auto mb-4">
        <Icon icon="mdi:shield-check-outline" width={28} color="#10b981" />
      </div>
      <h3 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">All Clear!</h3>
      <p className="text-sm text-[rgba(0,0,0,0.5)] mb-5">No security findings detected. Your environment appears to be secure.</p>
      <button
        onClick={onRefresh}
        className="inline-flex items-center gap-2 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] text-white text-xs font-medium rounded-md hover:bg-black transition-colors"
      >
        <Icon icon="mdi:refresh" width={14} />
        <span>Run Security Scan</span>
      </button>
    </div>
  );
};

export default SecurityEmptyState;
