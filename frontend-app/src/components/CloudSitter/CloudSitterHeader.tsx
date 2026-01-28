/**
 * CloudSitter Header Component
 *
 * Top header with breadcrumb navigation and title
 */

import React from 'react';
import type { ViewType } from '../../types/cloudsitter';

interface CloudSitterHeaderProps {
  currentView: ViewType;
  editMode: boolean;
  onNavigateToDashboard: () => void;
}

const CloudSitterHeader: React.FC<CloudSitterHeaderProps> = ({
  currentView,
  editMode,
  onNavigateToDashboard,
}) => {
  return (
    <header className="bg-white border-b px-8 py-4 flex items-center justify-between sticky top-0 z-20 shadow-sm">
      <div className="flex items-center gap-4">
        {currentView !== 'dashboard' && (
          <button
            onClick={onNavigateToDashboard}
            className="text-gray-400 hover:text-gray-600 transition-colors"
          >
            <i className="fas fa-arrow-left"></i>
          </button>
        )}
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-bold text-gray-900">CloudSitter</h1>
            <span className="text-xs font-medium text-gray-500 bg-gray-100 px-2.5 py-1 rounded-full">
              EC2 Scheduling
            </span>
          </div>
          <nav className="flex text-sm text-gray-500 mt-1 items-center gap-2">
            <span
              className="hover:text-indigo-600 cursor-pointer"
              onClick={onNavigateToDashboard}
            >
              Overview
            </span>
            {currentView === 'createPolicy' && (
              <>
                <i className="fas fa-chevron-right text-xs mx-1"></i>
                <span>{editMode ? 'Edit Policy' : 'New Policy'}</span>
              </>
            )}
          </nav>
        </div>
      </div>
    </header>
  );
};

export default CloudSitterHeader;
