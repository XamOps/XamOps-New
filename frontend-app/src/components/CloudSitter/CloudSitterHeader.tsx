import React, { useEffect } from 'react';
import type { ViewType } from '../../types/cloudsitter';
import { useTopbar } from '../../contexts/TopbarContext';

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
  const { setActions, clearActions } = useTopbar();

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-4">
        {/* Navigation & Breadcrumbs */}
        <div className="flex items-center gap-2 text-sm text-slate-500 bg-slate-50 border border-slate-200 rounded-lg px-3 py-1.5">
          <button
            onClick={onNavigateToDashboard}
            className={`flex items-center gap-2 transition-colors ${currentView !== 'dashboard' ? 'text-slate-500 hover:text-indigo-600' : 'text-indigo-600 font-bold'
              }`}
          >
            <i className="fas fa-th-large text-xs"></i>
            <span>Overview</span>
          </button>

          {currentView === 'createPolicy' && (
            <>
              <i className="fas fa-chevron-right text-[10px] text-slate-300"></i>
              <span className="flex items-center gap-2 text-slate-800 font-bold">
                <i className={`fas ${editMode ? 'fa-edit' : 'fa-plus-circle'} text-indigo-500 text-xs`}></i>
                {editMode ? 'Edit Policy' : 'New Policy'}
              </span>
            </>
          )}
        </div>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, currentView, editMode, onNavigateToDashboard]);

  return null;
};

export default CloudSitterHeader;
