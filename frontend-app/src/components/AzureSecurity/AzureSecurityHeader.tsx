import React, { useEffect } from 'react';
import { Icon } from '@iconify/react';
import { useTopbar } from '../../contexts/TopbarContext';

interface AzureSecurityHeaderProps {
  selectedAccountId: string | null;
  isRefreshing: boolean;
  onRefresh: () => void;
}

const AzureSecurityHeader: React.FC<AzureSecurityHeaderProps> = ({
  selectedAccountId,
  isRefreshing,
  onRefresh,
}) => {
  const { setActions, clearActions } = useTopbar();

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-4">
        <div className="flex items-center gap-1.5 bg-red-50 border border-red-200 text-red-700 text-xs font-semibold px-3 py-1.5 rounded-full">
          <Icon icon="mdi:shield-half-full" width={13} />
          <span>Powered by Microsoft Defender</span>
        </div>
        {selectedAccountId && (
          <button
            onClick={onRefresh}
            disabled={isRefreshing}
            className="flex items-center gap-2 px-4 py-2 bg-red-600 hover:bg-red-700 text-white text-xs font-bold rounded-xl transition-all disabled:opacity-50 active:scale-95"
          >
            <Icon icon="mdi:refresh" width={14} className={isRefreshing ? 'animate-spin' : ''} />
            <span>{isRefreshing ? 'Scanning...' : 'Refresh'}</span>
          </button>
        )}
      </div>
    );
    return () => clearActions();
  }, [selectedAccountId, isRefreshing, onRefresh, setActions, clearActions]);

  return null;
};

export default AzureSecurityHeader;
