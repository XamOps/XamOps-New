import React from 'react';
import { useTopbar } from '../../contexts/TopbarContext';
import { useTheme } from '../../contexts/ThemeContext';

interface CostHeaderProps {
  isLoading: boolean;
  onRefresh: () => void;
}

const CostHeader: React.FC<CostHeaderProps> = ({ isLoading, onRefresh }) => {
  const { setActions, clearActions } = useTopbar();
  const { theme } = useTheme();
  const dark = theme === 'dark';

  React.useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        <div className="flex items-center gap-1.5">
          <span className="w-1.5 h-1.5 rounded-full bg-[#1BE68B] animate-pulse flex-shrink-0"></span>
          <span className={`text-[11px] font-medium uppercase tracking-wide ${dark ? 'text-[rgba(255,255,255,0.4)]' : 'text-[rgba(0,0,0,0.4)]'}`}>
            Live data
          </span>
        </div>
        <button
          onClick={onRefresh}
          className={`flex items-center gap-1.5 text-[12px] font-medium transition-colors ${dark ? 'text-[rgba(255,255,255,0.5)] hover:text-white' : 'text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)]'}`}
        >
          <i className={`fas fa-sync-alt text-[10px] ${isLoading ? 'animate-spin' : ''}`} />
          {isLoading ? 'Refreshing…' : 'Refresh'}
        </button>
      </div>
    );
    return () => clearActions();
  }, [isLoading, onRefresh, setActions, clearActions, dark]);

  return null;
};

export default CostHeader;
