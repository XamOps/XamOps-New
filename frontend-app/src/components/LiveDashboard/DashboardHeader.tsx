import React, { useEffect } from 'react';
import { Link } from 'react-router-dom';
import type { Tenant, TenantUser, UserRole } from '../../types/auth';
import { useAuth } from '../../contexts/AuthContext';
import { useTopbarActions } from '../../contexts/TopbarContext';
import { useTheme } from '../../contexts/ThemeContext';
// Hidden from the topbar: it sat beside the ordinary Refresh and read as a second,
// near-identical refresh control. Restore this import with the render below.
// import RefreshFromCurButton from '../Shared/RefreshFromCurButton';
import { setCostSourceMode } from '../../utils/apiClient';

interface DashboardHeaderProps {
  hasAnyRole: (roles: UserRole | UserRole[]) => boolean;
  selectedTenant: string;
  impersonateUser: string;
  tenantList: Tenant[];
  tenantUsers: TenantUser[];
  selectedAccountIds: string[];
  layoutChanged: boolean;
  isRefreshing: boolean;
  lastUpdateTime: string;
  onRefresh: () => void;
  /**
   * Reload after a Cost and Usage Report refresh. Separate from onRefresh because it must
   * not force: see RefreshFromCurButton. Falls back to onRefresh when a page has not
   * distinguished the two yet.
   */
  onCurRefreshed?: () => void;
  /**
   * Single AWS account to offer a CUR-only reload for, or null to hide the button —
   * a CUR belongs to one account, so a multi-account or non-AWS selection has nothing
   * to reload.
   */
  curAccountId?: string | null;
  onSaveLayout: () => void;
  /** Returns null when the feature has no page for the selected provider. */
  getLink: (path: string) => string | null;
}

const DashboardHeader: React.FC<DashboardHeaderProps> = ({
  hasAnyRole,
  selectedTenant,
  impersonateUser,
  tenantList,
  tenantUsers,
  selectedAccountIds,
  layoutChanged,
  isRefreshing,
  lastUpdateTime,
  onRefresh,
  // Still accepted by the props type so callers need no change, but left undestructured
  // while the CUR button below is commented out.
  // onCurRefreshed,
  curAccountId = null,
  onSaveLayout,
  getLink,
}) => {
  const { switchTenant, switchUser, resetContext, fetchTenantUsers } = useAuth();
  const { setActions, clearActions } = useTopbarActions();
  const { theme } = useTheme();
  const dark = theme === 'dark';

  // Resolved once so the link and its trailing separator share a single decision. Null means the
  // selected provider has no Cloud Map page, in which case neither is rendered.
  const cloudMapLink = getLink('/cloudmap');

  const handleTenantChange = async (e: React.ChangeEvent<HTMLSelectElement>) => {
    const tenantId = e.target.value;
    if (tenantId) {
      await fetchTenantUsers(tenantId);
      switchTenant(tenantId);
    } else {
      resetContext();
    }
  };

  const handleUserChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const userId = e.target.value;
    if (userId) switchUser(userId);
  };

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-2">

        {/* Live status */}
        <div className="flex items-center gap-1.5 mr-1">
          <span className="w-1.5 h-1.5 rounded-full bg-[#1BE68B] animate-pulse flex-shrink-0"></span>
          <span className={`text-[11px] font-medium uppercase tracking-wide ${dark ? 'text-[rgba(255,255,255,0.4)]' : 'text-[rgba(0,0,0,0.4)]'}`}>{lastUpdateTime}</span>
        </div>

        {/* Super Admin tenant/user selectors */}
        {hasAnyRole('ROLE_SUPER_ADMIN') && (
          <div className={`flex items-center gap-1 border rounded-md px-1 py-0.5 ${dark ? 'border-[rgba(255,255,255,0.12)]' : 'border-[rgba(0,0,0,0.08)]'}`}>
            <select
              value={selectedTenant}
              onChange={handleTenantChange}
              className={`text-[12px] bg-transparent border-0 focus:ring-0 py-0.5 px-1 font-medium outline-none ${dark ? 'text-[rgba(255,255,255,0.7)]' : 'text-[rgba(0,0,0,0.7)]'}`}
            >
              <option value="">-- Customer --</option>
              {tenantList.map(tenant => (
                <option key={tenant.id} value={tenant.id}>{tenant.name}</option>
              ))}
            </select>
            {selectedTenant && (
              <select
                value={impersonateUser}
                onChange={handleUserChange}
                className={`text-[12px] bg-transparent border-0 focus:ring-0 py-0.5 px-1 font-medium outline-none ${dark ? 'text-[rgba(255,255,255,0.7)]' : 'text-[rgba(0,0,0,0.7)]'}`}
              >
                <option value="">-- User --</option>
                {tenantUsers.map(user => (
                  <option key={user.id} value={user.id}>{user.username}</option>
                ))}
              </select>
            )}
            {selectedTenant && (
              <button
                onClick={resetContext}
                className={`transition-colors p-0.5 hover:text-rose-500 ${dark ? 'text-[rgba(255,255,255,0.3)]' : 'text-[rgba(0,0,0,0.3)]'}`}
                title="Reset"
              >
                <i className="fas fa-times text-[10px]"></i>
              </button>
            )}
          </div>
        )}

        {/* CloudMap — omitted entirely for providers without one (Azure), rather than linking to
            the AWS page and querying it with a subscription id. The trailing separator goes with
            it so the row does not end up with a dangling divider. */}
        {cloudMapLink && (
          <>
            <Link
              to={cloudMapLink}
              className={`flex items-center gap-1.5 text-[12px] font-medium transition-colors ${
                selectedAccountIds.length === 0
                  ? dark ? 'text-[rgba(255,255,255,0.2)] pointer-events-none' : 'text-[rgba(0,0,0,0.25)] pointer-events-none'
                  : dark ? 'text-[rgba(255,255,255,0.5)] hover:text-white' : 'text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)]'
              }`}
            >
              <i className="fas fa-map text-[10px]"></i>
              CloudMap
            </Link>

            <span className={`text-[12px] select-none ${dark ? 'text-[rgba(255,255,255,0.15)]' : 'text-[rgba(0,0,0,0.15)]'}`}>|</span>
          </>
        )}

        {/* Shell */}
        {hasAnyRole(['ROLE_XAMOPS']) && (
          <>
            <Link
              to="/cloudshell"
              className={`flex items-center gap-1.5 text-[12px] font-medium transition-colors ${dark ? 'text-[rgba(255,255,255,0.5)] hover:text-white' : 'text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)]'}`}
            >
              <i className="fas fa-terminal text-[10px]"></i>
              Shell
            </Link>
            <span className={`text-[12px] select-none ${dark ? 'text-[rgba(255,255,255,0.15)]' : 'text-[rgba(0,0,0,0.15)]'}`}>|</span>
          </>
        )}

        {/* Save Layout — primary button (only when changed) */}
        {layoutChanged && (
          <button
            onClick={onSaveLayout}
            className={`flex items-center gap-1.5 px-3 py-1 rounded-md text-[12px] font-medium transition-colors ${dark ? 'bg-white text-[rgba(0,0,0,0.88)] hover:bg-[rgba(255,255,255,0.9)]' : 'bg-[rgba(0,0,0,0.88)] text-white hover:bg-black'}`}
          >
            <i className="fas fa-save text-[10px]"></i>
            Save Layout
          </button>
        )}

        {/* Refresh */}
        <button
          onClick={() => { setCostSourceMode('CE'); onRefresh(); }}
          disabled={isRefreshing}
          className={`flex items-center gap-1.5 text-[12px] font-medium transition-colors disabled:opacity-40 ${dark ? 'text-[rgba(255,255,255,0.5)] hover:text-white' : 'text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)]'}`}
        >
          <i className={`fas fa-sync-alt text-[10px] ${isRefreshing ? 'animate-spin' : ''}`}></i>
          {isRefreshing ? 'Refreshing…' : 'Refresh'}
        </button>

        {/* Refresh from CUR — hidden: two adjacent refresh controls in the topbar.
            Re-enable together with the import at the top of this file.
        <RefreshFromCurButton
          accountId={curAccountId}
          onRefreshed={onCurRefreshed ?? onRefresh}
          dark={dark}
        />
        */}
      </div>
    );
    return () => clearActions();
  }, [
    setActions, clearActions, hasAnyRole, selectedTenant, impersonateUser,
    tenantList, tenantUsers, selectedAccountIds, layoutChanged,
    isRefreshing, lastUpdateTime, onRefresh, curAccountId,
    onSaveLayout, getLink, theme, dark,
  ]);

  return null;
};

export default DashboardHeader;
