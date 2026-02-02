import React, { useEffect } from 'react';
import { Link } from 'react-router-dom';
import type { Tenant, TenantUser, UserRole } from '../../types/auth';
import { useAuth } from '../../contexts/AuthContext';
import { useTopbar } from '../../contexts/TopbarContext';

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
  onSaveLayout: () => void;
  getLink: (path: string) => string;
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
  onSaveLayout,
  getLink,
}) => {
  const { switchTenant, switchUser, resetContext, fetchTenantUsers } = useAuth();
  const { setActions, clearActions } = useTopbar();

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
    if (userId) {
      switchUser(userId);
    }
  };

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-4">
        {/* Live Status Indicator */}
        <div className="flex items-center space-x-2 mr-2">
          <div className="w-2 h-2 bg-green-500 rounded-full animate-pulse shadow-[0_0_8px_rgba(34,197,94,0.6)]"></div>
          <p className="text-[10px] uppercase tracking-wider font-bold text-slate-400">{lastUpdateTime}</p>
        </div>

        {/* Super Admin Selectors */}
        {hasAnyRole('ROLE_SUPER_ADMIN') && (
          <div className="flex items-center gap-2 bg-slate-100/50 p-1 rounded-xl border border-slate-200/50">
            <select
              value={selectedTenant}
              onChange={handleTenantChange}
              className="text-xs bg-transparent border-0 focus:ring-0 py-1 px-2 font-semibold text-slate-700 outline-none"
            >
              <option value="">-- Select Customer --</option>
              {tenantList.map(tenant => (
                <option key={tenant.id} value={tenant.id}>
                  {tenant.name}
                </option>
              ))}
            </select>

            {selectedTenant && (
              <select
                value={impersonateUser}
                onChange={handleUserChange}
                className="text-xs bg-transparent border-0 focus:ring-0 py-1 px-2 font-semibold text-slate-700 outline-none"
              >
                <option value="">-- Impersonate User --</option>
                {tenantUsers.map(user => (
                  <option key={user.id} value={user.id}>
                    {user.username}
                  </option>
                ))}
              </select>
            )}

            {selectedTenant && (
              <button
                onClick={resetContext}
                className="text-slate-400 hover:text-rose-500 p-1 transition-colors"
                title="Reset Context"
              >
                <i className="fas fa-times-circle"></i>
              </button>
            )}
          </div>
        )}

        {/* Navigation Actions */}
        <div className="flex items-center gap-2 pr-2 border-r border-slate-200/50">
          <Link
            to={getLink('/cloudmap')}
            className={`flex items-center gap-2 px-3 py-1.5 rounded-lg text-xs font-bold transition-all ${selectedAccountIds.length === 0
              ? 'bg-slate-100 text-slate-400 pointer-events-none'
              : 'bg-indigo-50 text-indigo-600 hover:bg-indigo-100'
              }`}
          >
            <i className="fas fa-map"></i>
            <span>CloudMap</span>
          </Link>

          <Link
            to={getLink('/calculator')}
            className="w-8 h-8 flex items-center justify-center rounded-lg bg-slate-100 text-slate-600 hover:bg-purple-50 hover:text-purple-600 transition-all"
            title="Pricing Calculator"
          >
            <i className="fas fa-calculator"></i>
          </Link>

          {hasAnyRole(['ROLE_XAMOPS']) && (
            <Link
              to="/cloudshell"
              className="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-slate-100 text-slate-600 hover:bg-slate-200 transition-all text-xs font-bold"
            >
              <i className="fas fa-terminal text-[10px]"></i>
              <span>Shell</span>
            </Link>
          )}
        </div>

        {/* Control Actions */}
        <div className="flex items-center gap-2">
          {layoutChanged && (
            <button
              onClick={onSaveLayout}
              className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white rounded-xl text-xs font-bold shadow-lg shadow-indigo-500/20 hover:bg-indigo-700 transition-all"
            >
              <i className="fas fa-save"></i>
              <span>Save Layout</span>
            </button>
          )}

          <button
            onClick={onRefresh}
            disabled={isRefreshing}
            className="flex items-center gap-2 px-4 py-2 bg-white border border-slate-200 rounded-xl text-xs font-bold text-slate-600 hover:bg-slate-50 transition-all disabled:opacity-50"
          >
            <i className={`fas fa-sync-alt ${isRefreshing ? 'animate-spin' : ''}`}></i>
            <span>{isRefreshing ? 'Refreshing...' : 'Refresh'}</span>
          </button>
        </div>
      </div>
    );
    return () => clearActions();
  }, [
    setActions, clearActions, hasAnyRole, selectedTenant, impersonateUser,
    tenantList, tenantUsers, selectedAccountIds, layoutChanged,
    isRefreshing, lastUpdateTime, onRefresh, onSaveLayout, getLink
  ]);

  return null;
};

export default DashboardHeader;
