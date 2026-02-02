/**
 * CloudK8s Header Component
 *
 * Displays the page header with title and refresh button
 */

import React, { useEffect } from 'react';
import { useTopbar } from '../../contexts/TopbarContext';

interface CloudK8sHeaderProps {
  isRefreshing: boolean;
  onRefresh: () => void;
}

const CloudK8sHeader: React.FC<CloudK8sHeaderProps> = ({ isRefreshing, onRefresh }) => {
  const { setActions, clearActions } = useTopbar();

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-4">
        <button
          onClick={onRefresh}
          disabled={isRefreshing}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-xs font-bold rounded-xl shadow-lg shadow-indigo-200 hover:bg-indigo-700 transition-all disabled:opacity-50 active:scale-95"
        >
          <i className={`fas fa-sync-alt ${isRefreshing ? 'animate-spin' : ''}`}></i>
          <span>{isRefreshing ? 'Refreshing...' : 'Refresh'}</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, isRefreshing, onRefresh]);

  return null;
};

export default CloudK8sHeader;
