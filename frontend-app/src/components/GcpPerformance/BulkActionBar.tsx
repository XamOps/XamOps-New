/**
 * Bulk Action Bar Component
 * Displays actions for selected insights
 */

import React from 'react';

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
    <div className="bulk-action-bar flex items-center gap-4 p-4 rounded-xl w-full">
      <div className="flex items-center space-x-2">
        <i className="fas fa-check-circle text-blue-600"></i>
        <span className="text-sm font-bold text-blue-800">
          {selectedCount} insight{selectedCount > 1 ? 's' : ''} selected
        </span>
      </div>
      <div className="flex items-center gap-3 ml-auto">
        <button
          onClick={onArchive}
          className="px-4 py-2 text-sm font-semibold text-gray-700 bg-white border border-gray-300 rounded-lg hover:bg-gray-50 transition-all"
        >
          <i className="fas fa-archive mr-2"></i>Archive Selected
        </button>
        <button
          onClick={onClear}
          className="text-gray-400 hover:text-gray-600 transition-colors"
        >
          <i className="fas fa-times"></i>
        </button>
      </div>
    </div>
  );
};

export default BulkActionBar;
