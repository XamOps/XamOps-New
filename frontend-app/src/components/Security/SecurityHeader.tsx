import React from 'react';
import { Icon } from '@iconify/react';
import type { ProwlerStatus } from '../../types/security';

interface SecurityHeaderProps {
  prowlerStatus: ProwlerStatus;
  isRefreshing: boolean;
  isExporting: boolean;
  onExport: () => void;
  onRefresh: () => void;
}

const SecurityHeader: React.FC<SecurityHeaderProps> = ({
  prowlerStatus,
  isRefreshing,
  isExporting,
  onExport,
  onRefresh,
}) => {
  return (
    <div className="flex items-center justify-between px-6 py-4 bg-white border-b border-gray-100">
      <div className="flex items-center gap-3">
        <div className="w-9 h-9 rounded-lg bg-indigo-50 border border-indigo-100 flex items-center justify-center">
          <Icon icon="mdi:shield-half-full" width={18} color="#4f46e5" />
        </div>
        <div>
          <h1 className="text-sm font-bold text-gray-800">Security Center</h1>
          <p className="text-xs text-gray-500">Monitor security findings and compliance status</p>
        </div>
      </div>

      <div className="flex items-center gap-3">
        {prowlerStatus.status === 'RUNNING' && (
          <div className="flex items-center gap-2 px-3 py-1.5 bg-blue-50 text-blue-700 rounded-lg text-xs font-medium border border-blue-100 animate-pulse">
            <div className="w-2 h-2 bg-blue-500 rounded-full" />
            <span>Deep Scan Running...</span>
          </div>
        )}
        <button
          onClick={onExport}
          disabled={isExporting}
          className="flex items-center gap-2 px-4 py-2 bg-white text-emerald-600 text-xs font-bold rounded-xl border border-emerald-100 shadow-sm hover:shadow-md transition-all disabled:opacity-50"
        >
          <Icon icon={isExporting ? 'mdi:loading' : 'mdi:microsoft-excel'} width={14} className={isExporting ? 'animate-spin' : ''} />
          <span>{isExporting ? 'Exporting...' : 'Export Report'}</span>
        </button>
        <button
          onClick={onRefresh}
          disabled={isRefreshing}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-xs font-bold rounded-xl hover:bg-indigo-700 transition-all disabled:opacity-50"
        >
          <Icon icon="mdi:refresh" width={14} className={isRefreshing ? 'animate-spin' : ''} />
          <span>{isRefreshing ? 'Scanning...' : 'Refresh Scan'}</span>
        </button>
      </div>
    </div>
  );
};

export default SecurityHeader;
