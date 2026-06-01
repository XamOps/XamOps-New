import React, { useEffect } from 'react';
import { Icon } from '@iconify/react';
import { useTopbar } from '../../contexts/TopbarContext';

interface CloudK8sHeaderProps {
  isRefreshing: boolean;
  onRefresh: () => void;
}

const CloudK8sHeader: React.FC<CloudK8sHeaderProps> = ({ isRefreshing, onRefresh }) => {
  const { setActions, clearActions } = useTopbar();

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-2">
        <span className="w-2 h-2 rounded-full bg-amber-400" />
        <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.65)]">CloudK8s</span>
        <span className="text-[rgba(0,0,0,0.2)]">|</span>
        <button
          onClick={onRefresh}
          disabled={isRefreshing}
          className="flex items-center gap-1.5 px-3 py-1.5 text-[12px] font-semibold text-[rgba(0,0,0,0.65)] hover:bg-[rgba(0,0,0,0.04)] rounded-lg transition-colors disabled:opacity-50"
        >
          <svg xmlns="http://www.w3.org/2000/svg" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" className={isRefreshing ? 'animate-spin' : ''}>
            <polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/>
            <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/>
          </svg>
          {isRefreshing ? 'Refreshing…' : 'Refresh'}
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, isRefreshing, onRefresh]);

  return null;
};

export default CloudK8sHeader;
