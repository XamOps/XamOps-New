/**
 * Bulk Action Bar Component
 * Displays actions for selected insights
 */

import React from 'react';
import { Icon } from '@iconify/react';

interface BulkActionBarProps {
  selectedCount: number;
  onArchive: () => void;
  onClear: () => void;
}

const BulkActionBar: React.FC<BulkActionBarProps> = ({
  selectedCount,
  onArchive,
  onClear
}) => {
  if (selectedCount === 0) {
    return null;
  }

  return (
    <div className="flex items-center gap-4 bg-blue-50 border border-blue-200 rounded-xl px-4 py-2.5 w-full">
      <div className="flex items-center gap-2">
        <Icon icon="mdi:check-circle-outline" width={16} className="text-blue-600" />
        <span className="text-sm font-bold text-blue-800">
          {selectedCount} insight{selectedCount > 1 ? 's' : ''} selected
        </span>
      </div>
      <div className="flex items-center gap-3 ml-auto">
        <button
          onClick={onArchive}
          className="flex items-center gap-2 px-3 py-1.5 text-xs font-semibold text-gray-700 border border-[rgba(0,0,0,0.12)] rounded-lg bg-white hover:bg-gray-50 transition-all"
        >
          <Icon icon="mdi:archive-outline" width={13} />
          Archive Selected
        </button>
        <button onClick={onClear} className="text-gray-400 hover:text-gray-600 transition-colors">
          <Icon icon="mdi:close" width={16} />
        </button>
      </div>
    </div>
  );
};

export default BulkActionBar;
