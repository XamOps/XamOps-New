import React, { useEffect } from 'react';
import { useTopbar } from '../../contexts/TopbarContext';
import { useTheme } from '../../contexts/ThemeContext';
import clsx from 'clsx';

interface AzureFinOpsHeaderProps {
  selectedAccountId: string | null;
  isRefreshing: boolean;
  onRefresh: () => void;
}

const AzureFinOpsHeader: React.FC<AzureFinOpsHeaderProps> = ({
  selectedAccountId,
  isRefreshing,
  onRefresh,
}) => {
  const { setActions } = useTopbar();
  const { theme } = useTheme();

  useEffect(() => {
    setActions(
      <button
        onClick={onRefresh}
        disabled={isRefreshing || !selectedAccountId}
        className={clsx(
          'flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-all',
          'bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed'
        )}
      >
        <i className={clsx('fas fa-sync-alt text-xs', isRefreshing && 'animate-spin')} />
        {isRefreshing ? 'Refreshing...' : 'Refresh'}
      </button>
    );
    return () => setActions(null);
  }, [isRefreshing, selectedAccountId, onRefresh, setActions]);

  return (
    <div className={clsx(
      'glass-card rounded-xl p-6',
      theme === 'dark' ? 'bg-slate-800/50' : ''
    )}>
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-4">
          <div className="w-12 h-12 bg-gradient-to-br from-blue-500 to-teal-600 rounded-xl flex items-center justify-center shadow-lg">
            <i className="fas fa-chart-pie text-white text-xl" />
          </div>
          <div>
            <h1 className={clsx(
              'text-2xl font-bold',
              theme === 'dark' ? 'text-slate-100' : 'text-gray-900'
            )}>
              Azure FinOps Report
            </h1>
            <p className={clsx(
              'text-sm mt-0.5',
              theme === 'dark' ? 'text-slate-400' : 'text-gray-500'
            )}>
              Cloud financial operations &amp; cost optimization
            </p>
          </div>
        </div>

        <div className="flex items-center gap-3">
          <span className={clsx(
            'inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-medium',
            'bg-blue-100 text-blue-700 border border-blue-200'
          )}>
            <i className="fas fa-cloud text-blue-500 text-[10px]" />
            Powered by Azure Cost Management
          </span>
          {selectedAccountId && (
            <div className="flex items-center gap-1.5">
              <div className="w-2 h-2 bg-green-500 rounded-full animate-pulse" />
              <span className={clsx('text-xs', theme === 'dark' ? 'text-slate-400' : 'text-gray-500')}>
                Live
              </span>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

export default AzureFinOpsHeader;
