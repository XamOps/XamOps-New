import React, { useEffect } from 'react';
import { Icon } from '@iconify/react';
import { useTopbar } from '../../contexts/TopbarContext';

interface Props {
  isLoading: boolean;
  onRefresh: () => void;
}

const XamSecureHeader: React.FC<Props> = ({ isLoading, onRefresh }) => {
  const { setTitle, setActions, clearActions } = useTopbar();

  useEffect(() => {
    setTitle('XamSecure');
    setActions(
      <button
        onClick={onRefresh}
        disabled={isLoading}
        className="flex items-center gap-2 px-4 py-2 bg-gray-900 hover:bg-black text-white text-xs font-bold rounded-xl transition-all disabled:opacity-50 active:scale-95"
      >
        <Icon icon="mdi:refresh" width={14} className={isLoading ? 'animate-spin' : ''} />
        <span>{isLoading ? 'Loading...' : 'Refresh'}</span>
      </button>
    );
    return () => { clearActions(); setTitle(null); };
  }, [isLoading, onRefresh, setTitle, setActions, clearActions]);

  return null;
};

export default XamSecureHeader;
