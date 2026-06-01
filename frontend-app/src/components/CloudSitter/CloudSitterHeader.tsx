import React, { useEffect } from 'react';
import { Icon } from '@iconify/react';
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
      <div className="flex items-center gap-2">
        <span className="w-1.5 h-1.5 rounded-full bg-indigo-500" />
        <span className="text-[11px] font-medium uppercase tracking-wide text-[rgba(0,0,0,0.4)]">CloudSitter</span>
        <span className="text-[rgba(0,0,0,0.15)] text-[12px] select-none">|</span>
        <button
          onClick={onNavigateToDashboard}
          className={`flex items-center gap-1 text-[11px] font-semibold transition-colors ${
            currentView === 'dashboard'
              ? 'text-indigo-600'
              : 'text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)]'
          }`}
        >
          <Icon icon="mdi:view-dashboard-outline" width={12} />
          Overview
        </button>

        {currentView === 'createPolicy' && (
          <>
            <Icon icon="mdi:chevron-right" width={12} color="rgba(0,0,0,0.25)" />
            <span className="flex items-center gap-1 text-[11px] font-semibold text-indigo-600">
              <Icon icon={editMode ? 'mdi:pencil-outline' : 'mdi:plus-circle-outline'} width={12} />
              {editMode ? 'Edit Policy' : 'New Policy'}
            </span>
          </>
        )}
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, currentView, editMode, onNavigateToDashboard]);

  return null;
};

export default CloudSitterHeader;
