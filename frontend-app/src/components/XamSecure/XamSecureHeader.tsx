import React, { useEffect } from 'react';
import { Icon } from '@iconify/react';
import { useTopbar } from '../../contexts/TopbarContext';
import PoweredByProvider from './PoweredByProvider';
import AddIntegrationMenu from './AddIntegrationMenu';

interface Props {
  isLoading: boolean;
  onRefresh: () => void;
}

const XamSecureHeader: React.FC<Props> = ({ isLoading, onRefresh }) => {
  const { setTitle, setTitleAdornment, setActions, clearActions } = useTopbar();

  useEffect(() => {
    setTitle('XamSecure');
    // Beside the title, so the attribution covers the whole section rather than appearing to
    // belong to whichever tab happens to be open.
    setTitleAdornment(<PoweredByProvider />);
    setActions(
      <div className="flex items-center gap-2">
        {/* Add before Refresh: onboarding is a deliberate action, refresh is incidental. */}
        <AddIntegrationMenu />
        <button
          onClick={onRefresh}
          disabled={isLoading}
          className="flex items-center gap-2 px-4 py-2 bg-gray-900 hover:bg-black text-white text-xs font-bold rounded-xl transition-all disabled:opacity-50 active:scale-95"
        >
          <Icon icon="mdi:refresh" width={14} className={isLoading ? 'animate-spin' : ''} />
          <span>{isLoading ? 'Loading...' : 'Refresh'}</span>
        </button>
      </div>
    );
    // Adornment must be cleared with the title, or it persists into the next page's topbar and
    // credits the provider for something it has nothing to do with.
    return () => { clearActions(); setTitle(null); setTitleAdornment(null); };
  }, [isLoading, onRefresh, setTitle, setTitleAdornment, setActions, clearActions]);

  return null;
};

export default XamSecureHeader;
