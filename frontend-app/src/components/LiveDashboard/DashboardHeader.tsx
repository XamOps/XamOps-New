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
            <div className="w-10 h-10 bg-gradient-to-br from-indigo-500 to-purple-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-cloud text-white text-lg"></i>
            </div>
            <div>
              <h1 className="text-xl font-bold text-gray-800">Live Dashboard</h1>
              <div className="flex items-center space-x-2">
                <div className="w-2 h-2 bg-green-500 rounded-full pulse-dot"></div>
                <p className="text-xs text-gray-500">{lastUpdateTime}</p>
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
                  <option key={tenant.tenantId} value={tenant.tenantId}>
                    {tenant.companyName}
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
                  <i className="fas fa-times-circle text-lg"></i>
                </button>
              )}
            </div>
          </div>
        )}
      </div>

      <div className="flex items-center space-x-4">
        {/* CloudMap Button with Animation */}
        <a
          href={getLink('/cloudmap.html')}
          className={`my-auto nav-item ${selectedAccountIds.length === 0 ? 'opacity-50 pointer-events-none' : ''
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
        </a>

        {/* Calculator Button */}
        <a
          href={getLink('/calculator.html')}
          className="flex items-center justify-center w-10 h-10 text-gray-600 hover:text-purple-600 bg-white hover:bg-purple-50 rounded-lg transition-all border border-gray-200 hover:border-purple-200 shadow-sm hover:shadow-md group"
          title="Pricing Calculator"
        >
          <i className="fas fa-calculator text-lg group-hover:scale-110 transition-transform"></i>
        </a>

        {/* CloudShell Button (ROLE_XAMOPS only) */}
        {hasAnyRole(['ROLE_XAMOPS']) && (
          <Link
            to="/cloudshell"
            className="flex items-center text-sm font-semibold text-gray-600 hover:text-indigo-600 px-4 py-2 rounded-lg hover:bg-indigo-50 transition-all border border-transparent hover:border-indigo-100 group"
            title="Launch CloudShell"
          >
            <i className="fas fa-laptop-code mr-2 text-gray-500 group-hover:text-indigo-600 transition-colors text-lg drop-shadow-sm group-hover:drop-shadow-md"></i>
            <span>CloudShell</span>
          </Link>
        )}

        {/* Save Layout Button */}
        {layoutChanged && (
          <button
            onClick={onSaveLayout}
            className="flex items-center text-sm font-semibold text-white bg-gradient-to-r from-indigo-600 to-purple-600 hover:from-indigo-700 hover:to-purple-700 px-6 py-2.5 rounded-lg shadow-md transition-all transform hover:scale-105 hover:shadow-lg"
          >
            <i className="fas fa-save mr-2"></i> Save Layout
          </button>
        )}

        {/* Refresh Button */}
        <button
          onClick={onRefresh}
          disabled={isRefreshing}
          className="flex items-center text-sm text-indigo-600 hover:text-indigo-800 px-4 py-2 rounded-lg hover:bg-indigo-50 transition-all disabled:opacity-50 disabled:cursor-not-allowed"
        >
          <i className={`fas fa-sync-alt mr-2 ${isRefreshing ? 'fa-spin' : ''}`}></i>
          <span>{isRefreshing ? 'Refreshing...' : 'Refresh'}</span>
        </button>
      </div>
    </header>
  );
};

export default DashboardHeader;
