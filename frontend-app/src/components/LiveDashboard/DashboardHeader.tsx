/**
 * DashboardHeader Component
 *
 * Converted from: dashboard.html (header section)
 *
 * Displays dashboard title, controls, and super admin tenant/user selectors.
 * All functionality preserved exactly from original.
 */

import React from 'react';
import { Link } from 'react-router-dom';
import type { Tenant, TenantUser, UserRole } from '../../types/auth';
import { useAuth } from '../../contexts/AuthContext';

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

  return (
    <header className="h-20 flex items-center justify-between glass-card px-6 -mx-6 -mt-6 mb-6 border-b sticky top-0 z-10 fade-in relative">
      <div className="flex items-center space-x-4">
        {/* Regular User Header */}
        {!hasAnyRole('ROLE_SUPER_ADMIN') && (
          <div className="flex items-center space-x-2">
            <div className="w-10 h-10 bg-gradient-to-br from-indigo-500 to-purple-600 rounded-lg flex items-center justify-center shadow-lg shadow-indigo-500/20">
              <svg className="w-6 h-6 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 15a4 4 0 004 4h9a5 5 0 10-.1-9.999 5.002 5.002 0 10-9.78 2.096A4.001 4.001 0 003 15z" />
              </svg>
            </div>
            <div>
              <h1 className="text-xl font-black text-gray-900 tracking-tight">Live Dashboard</h1>
              <div className="flex items-center space-x-2 mt-0.5">
                <div className="w-1.5 h-1.5 bg-green-500 rounded-full animate-pulse shadow-[0_0_8px_rgba(34,197,94,0.6)]"></div>
                <p className="text-[10px] uppercase tracking-wider font-bold text-gray-400">{lastUpdateTime}</p>
              </div>
            </div>
          </div>
        )}

        {/* Super Admin Header with Tenant/User Selectors */}
        {hasAnyRole('ROLE_SUPER_ADMIN') && (
          <div className="flex items-center space-x-3">
            <div className="bg-white bg-opacity-90 backdrop-blur-sm p-1.5 rounded-xl shadow-sm border border-gray-200 flex items-center space-x-2">
              <select
                value={selectedTenant}
                onChange={handleTenantChange}
                className="text-sm border border-gray-300 rounded-lg shadow-sm focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200 focus:ring-opacity-50 py-2 px-3 outline-none hover:border-gray-400 bg-white font-medium text-gray-700"
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
                  className="text-sm border border-gray-300 rounded-lg shadow-sm focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200 focus:ring-opacity-50 py-2 px-3 outline-none hover:border-gray-400 bg-white font-medium text-gray-700"
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
                  className="text-red-500 hover:text-red-700 hover:bg-red-50 p-2 rounded-lg transition-colors"
                  title="Reset Context"
                >
                  <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 14l2-2m0 0l2-2m-2 2l-2-2m2 2l2 2m7-2a9 9 0 11-18 0 9 9 0 0118 0z" />
                  </svg>
                </button>
              )}
            </div>
          </div>
        )}
      </div>

      <div className="flex items-center space-x-4">
        {/* CloudMap Button with Animation */}
        <Link
          to={getLink('/cloudmap')}
          className={`my-auto nav-item cursor-pointer ${selectedAccountIds.length === 0 ? 'opacity-50 pointer-events-none' : ''
            }`}
        >
          <div className="map-btn-wrapper">
            <div className="map-btn">CloudMap</div>
            <div className="pinpoint"></div>
            <div className="map-container">
              <div className="map fold-1"></div>
              <div className="map fold-2"></div>
              <div className="map fold-3"></div>
              <div className="map fold-4"></div>
            </div>
          </div>
        </Link>

        {/* Calculator Button */}
        <Link
          to={getLink('/calculator')}
          className="flex items-center justify-center w-10 h-10 text-gray-600 hover:text-purple-600 bg-white hover:bg-purple-50 rounded-lg transition-all border border-gray-200 hover:border-purple-200 shadow-sm hover:shadow-md group cursor-pointer"
          title="Pricing Calculator"
        >
          <svg className="w-6 h-6 group-hover:scale-110 transition-transform" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 7h6m0 10v-3m-3 3h.01M9 17h.01M9 14h.01M12 14h.01M15 11h.01M12 11h.01M9 11h.01M7 21h10a2 2 0 002-2V5a2 2 0 00-2-2H7a2 2 0 00-2 2v14a2 2 0 002 2z" />
          </svg>
        </Link>

        {/* CloudShell Button (ROLE_XAMOPS only) */}
        {hasAnyRole(['ROLE_XAMOPS']) && (
          <Link
            to="/cloudshell"
            className="flex items-center text-sm font-semibold text-gray-600 hover:text-indigo-600 px-4 py-2 rounded-lg hover:bg-indigo-50 transition-all border border-transparent hover:border-indigo-100 group cursor-pointer"
            title="Launch CloudShell"
          >
            <svg className="w-6 h-6 mr-2 text-gray-400 group-hover:text-indigo-600 transition-colors" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 9l3 3-3 3m5 0h3M5 20h14a2 2 0 002-2V6a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" />
            </svg>
            <span>CloudShell</span>
          </Link>
        )}

        {/* Save Layout Button */}
        {layoutChanged && (
          <button
            onClick={onSaveLayout}
            className="flex items-center text-sm font-semibold text-white bg-gradient-to-r from-indigo-600 to-purple-600 hover:from-indigo-700 hover:to-purple-700 px-6 py-2.5 rounded-lg shadow-md transition-all transform hover:scale-105 hover:shadow-lg"
          >
            <svg className="w-4 h-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 7H5a2 2 0 00-2 2v9a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-3m-1 4l-3 3m0 0l-3-3m3 3V4" />
            </svg>
            Save Layout
          </button>
        )}

        {/* Refresh Button */}
        <button
          onClick={onRefresh}
          disabled={isRefreshing}
          className="flex items-center text-sm text-indigo-600 hover:text-indigo-800 px-4 py-2 rounded-lg hover:bg-indigo-50 transition-all disabled:opacity-50 disabled:cursor-not-allowed"
        >
          <svg className={`w-4 h-4 mr-2 ${isRefreshing ? 'animate-spin' : ''}`} fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
          </svg>
          <span>{isRefreshing ? 'Refreshing...' : 'Refresh'}</span>
        </button>
      </div>
    </header>
  );
};

export default DashboardHeader;
