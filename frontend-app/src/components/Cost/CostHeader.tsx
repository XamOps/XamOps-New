import React from 'react';
import { useTopbar } from '../../contexts/TopbarContext';
import { useTheme } from '../../contexts/ThemeContext';
// Hidden from the topbar: it sat beside the ordinary Refresh and read as a second,
// near-identical refresh control. Restore this import with the render below.
// import RefreshFromCurButton from '../Shared/RefreshFromCurButton';
import { setCostSourceMode } from '../../utils/apiClient';

interface CostHeaderProps {
  isLoading: boolean;
  onRefresh: () => void;
  /**
   * Single AWS account to offer a CUR-only reload for, or null to hide the button.
   * A CUR belongs to one account, so a multi-account or non-AWS selection has nothing
   * to reload.
   */
  curAccountId?: string | null;
}

const CostHeader: React.FC<CostHeaderProps> = ({ isLoading, onRefresh, curAccountId = null }) => {
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
          onClick={() => { setCostSourceMode('CE'); onRefresh(); }}
          className={`flex items-center gap-1.5 text-[12px] font-medium transition-colors ${dark ? 'text-[rgba(255,255,255,0.5)] hover:text-white' : 'text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)]'}`}
        >
          <i className={`fas fa-sync-alt text-[10px] ${isLoading ? 'animate-spin' : ''}`} />
          {isLoading ? 'Refreshing…' : 'Refresh'}
        </button>

        {/* Same reload as the button above, but from the Cost and Usage Report with no
            Cost Explorer fallback — so it also answers whether this account is really
            reading the report, which the ordinary Refresh cannot.

            Hidden: two adjacent refresh controls in the topbar. Re-enable together with
            the import at the top of this file.
        <RefreshFromCurButton accountId={curAccountId} onRefreshed={onRefresh} dark={dark} />
        */}
      </div>
    );
    return () => clearActions();
  }, [isLoading, onRefresh, curAccountId, setActions, clearActions, dark]);

  return null;
};

export default CostHeader;
