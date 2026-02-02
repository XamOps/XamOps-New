/**
 * UserManagementPage
 *
 * IAM User and Role Management page
 * Converted from: Xamops_User_management.html
 *
 * Features:
 * - View IAM Users with groups, policies, and activity
 * - View IAM Roles with descriptions and attached policies
 * - Search and filter users and roles
 * - Multi-cloud support (AWS, GCP, Azure)
 * - MFA status tracking for AWS
 * - Provider-specific labeling
 *
 * All API endpoints and functionality preserved from original.
 */

import React, { useState } from 'react';
import { useUserManagement } from '../hooks/useUserManagement';
import type { IamUser, IamRole } from '../types/userManagement';
import { useTopbar } from '../contexts/TopbarContext';
import '../styles/userManagement.css';

const SKELETON_ITEMS = [1, 2, 3, 4, 5];

const KpiSkeleton = () => (
  <div className="kpi-card">
    <div className="kpi-content">
      <div className="flex-1">
        <div className="skeleton h-4 w-24 mb-2 rounded" />
        <div className="skeleton h-8 w-16 rounded" />
      </div>
      <div className="skeleton h-12 w-12 rounded-xl" />
    </div>
  </div>
);

const TableRowSkeleton = () => (
  <tr className="table-row">
    <td>
      <div className="user-identity">
        <div className="skeleton h-10 w-10 rounded-xl mr-4" />
        <div className="flex-1">
          <div className="skeleton h-4 w-32 mb-2 rounded" />
          <div className="skeleton h-3 w-48 rounded" />
        </div>
      </div>
    </td>
    <td>
      <div className="flex gap-2">
        <div className="skeleton h-6 w-16 rounded-full" />
        <div className="skeleton h-6 w-20 rounded-full" />
      </div>
    </td>
    <td>
      <div className="flex flex-col gap-2">
        <div className="skeleton h-6 w-40 rounded" />
        <div className="skeleton h-6 w-32 rounded" />
      </div>
    </td>
    <td>
      <div className="flex flex-col gap-2">
        <div className="skeleton h-3 w-24 rounded" />
        <div className="skeleton h-3 w-24 rounded" />
      </div>
    </td>
  </tr>
);

const UserManagementPage: React.FC = () => {
  const {
    loading,
    error,
    accountName,
    accountId,
    users,
    roles,
    searchQuery,
    setSearchQuery,
    filteredUsers,
    filteredRoles,
    formatDate,
    isOldDate,
    countNoMfa,
    copyToClipboard,
    labels,
    fetchData,
    isAws,
  } = useUserManagement();

  const { setActions, clearActions } = useTopbar();
  const [activeTab, setActiveTab] = useState<'users' | 'roles'>('users');

  // Register actions in Topbar
  React.useEffect(() => {
    setActions(
      <div className="flex items-center gap-4">
        <div className="flex flex-col items-end mr-2">
          <p className="text-sm font-bold text-slate-700 leading-none">{accountName || 'Select Account'}</p>
          <p className="text-[10px] text-slate-400 font-mono mt-1">{accountId || '---'}</p>
        </div>
        <button
          onClick={() => fetchData(true)}
          disabled={loading || !accountId}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-sm font-semibold rounded-xl shadow-lg shadow-indigo-200 hover:bg-indigo-700 transition-all disabled:opacity-50"
        >
          <i className={`fas fa-sync-alt ${loading ? 'fa-spin' : ''}`} />
          <span>{loading ? 'Refreshing...' : 'Refresh'}</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [accountName, accountId, loading, fetchData, setActions, clearActions]);

  const renderUsersTableBody = () => {
    if (loading && !users.length) {
      return SKELETON_ITEMS.map((item) => <TableRowSkeleton key={`skeleton-user-${item}`} />);
    }

    if (filteredUsers.length > 0) {
      return filteredUsers.map((user: IamUser) => (
        <tr key={user.userId} className="table-row">
          <td>
            <div className="user-identity">
              <div className="user-avatar">
                {user.userName.substring(0, 2).toUpperCase()}
              </div>
              <div>
                <div className="user-name">{user.userName}</div>
                <div className="flex items-center gap-2">
                  <div className="user-id" title={user.arn}>
                    {user.userId}
                  </div>
                  {isAws && (
                    <span className={`mfa-badge ${user.mfaEnabled ? 'enabled' : 'disabled'}`}>
                      <i className={`fas ${user.mfaEnabled ? 'fa-shield-alt' : 'fa-exclamation-triangle'} mr-1`} />
                      {user.mfaEnabled ? 'MFA' : 'No MFA'}
                    </span>
                  )}
                </div>
              </div>
            </div>
          </td>
          <td>
            <div className="groups-container">
              {user.groups && user.groups.length > 0 ? (
                user.groups.map((group) => (
                  <span key={`${user.userId}-${group}`} className="group-badge">
                    <i className="fas fa-users mr-1.5 text-[10px]" />
                    {group}
                  </span>
                ))
              ) : (
                <span className="empty-text">No Groups</span>
              )}
            </div>
          </td>
          <td>
            <div className="policies-container">
              {user.attachedPolicies && user.attachedPolicies.length > 0 ? (
                user.attachedPolicies.map((policy) => (
                  <div key={`${user.userId}-${policy.policyName}`} className="policy-item">
                    <i className="fas fa-file-contract text-gray-400 mr-2" />
                    <span className="policy-name">{policy.policyName}</span>
                    {policy.type === 'Managed' && (
                      <span className="policy-badge managed">Managed</span>
                    )}
                    {policy.type === 'Inline' && (
                      <span className="policy-badge inline">Inline</span>
                    )}
                  </div>
                ))
              ) : (
                <span className="empty-text">No direct policies</span>
              )}
            </div>
          </td>
          <td>
            <div className="activity-info">
              <div className="activity-row">
                <span className="activity-label">Created:</span>
                <span>{formatDate(user.createDate)}</span>
              </div>
              <div className="activity-row">
                <span className="activity-label">Last Pwd Use:</span>
                <span className={isOldDate(user.passwordLastUsed) ? 'text-red-500 font-semibold' : ''}>
                  {formatDate(user.passwordLastUsed)}
                </span>
              </div>
            </div>
          </td>
        </tr>
      ));
    }

    if (!loading && users.length > 0) {
      return (
        <tr>
          <td colSpan={4} className="empty-state">
            <p>No users match your search.</p>
          </td>
        </tr>
      );
    }

    if (!loading) {
      return (
        <tr>
          <td colSpan={4} className="empty-state">
            <i className="fas fa-ghost text-4xl text-gray-300 mb-2" />
            <p className="font-semibold text-gray-600">No IAM Users found</p>
          </td>
        </tr>
      );
    }

    return null;
  };

  const renderRolesTableBody = () => {
    if (loading && !roles.length) {
      return SKELETON_ITEMS.map((item) => <TableRowSkeleton key={`skeleton-role-${item}`} />);
    }

    if (filteredRoles.length > 0) {
      return filteredRoles.map((role: IamRole) => (
        <tr key={role.roleId} className="table-row">
          <td>
            <div className="role-identity">
              <div className="role-icon">
                <i className="fas fa-robot" />
              </div>
              <div>
                <div className="role-name">{role.roleName}</div>
                <button
                  className="role-arn-btn"
                  title={`Copy ARN: ${role.arn}`}
                  onClick={() => copyToClipboard(role.arn)}
                >
                  <i className="far fa-copy mr-1.5" />
                  {role.arn.split('/').pop()}
                </button>
              </div>
            </div>
          </td>
          <td>
            <div className="role-description">
              {role.description || '-'}
            </div>
          </td>
          <td>
            <div className="policies-container">
              {role.attachedPolicies && role.attachedPolicies.length > 0 ? (
                role.attachedPolicies.map((policy) => (
                  <div key={`${role.roleId}-${policy.policyName}`} className="policy-item">
                    <i className="fas fa-shield-alt text-gray-400 mr-2.5 text-xs" />
                    <span className="policy-name">{policy.policyName}</span>
                    {policy.type === 'Managed' && (
                      <span className="policy-badge aws">AWS</span>
                    )}
                  </div>
                ))
              ) : (
                <span className="empty-text">No policies attached</span>
              )}
            </div>
          </td>
          <td className="text-sm text-gray-500">
            {formatDate(role.createDate)}
          </td>
        </tr>
      ));
    }

    if (!loading && roles.length > 0) {
      return (
        <tr>
          <td colSpan={4} className="empty-state">
            <p>No roles match your search.</p>
          </td>
        </tr>
      );
    }

    if (!loading) {
      return (
        <tr>
          <td colSpan={4} className="empty-state">
            <i className="fas fa-ghost text-4xl text-gray-300 mb-2" />
            <p className="font-semibold text-gray-600">No IAM Roles found</p>
          </td>
        </tr>
      );
    }

    return null;
  };

  return (
    <div className="user-management-page">
      {/* Page Content */}
      <div className="page-content pt-4">
        {/* Error Alert */}
        {error && (
          <div className="error-alert">
            <div className="flex items-center">
              <div className="flex-shrink-0">
                <i className="fas fa-exclamation-circle text-red-500" />
              </div>
              <div className="ml-3">
                <p className="text-sm text-red-700 font-semibold">Status</p>
                <p className="text-sm text-red-600">{error}</p>
              </div>
            </div>
          </div>
        )}

        {/* Loading & Content */}
        {loading && !users.length ? (
          <>
            <div className="kpi-grid">
              <KpiSkeleton />
              <KpiSkeleton />
              <KpiSkeleton />
            </div>
            <div className="table-container">
              <div className="skeleton-table-header h-16 w-full skeleton mb-1" />
              <div className="table-wrapper">
                <table className="data-table">
                  <tbody>
                    {SKELETON_ITEMS.map((item) => <TableRowSkeleton key={`main-skeleton-${item}`} />)}
                  </tbody>
                </table>
              </div>
            </div>
          </>
        ) : (
          <>
            {/* KPI Cards */}
            <div className="kpi-grid">
              <div className="kpi-card">
                <div className="kpi-content">
                  <div>
                    <p className="kpi-label">{`Total ${labels.users}`}</p>
                    <h3 className="kpi-value">{users.length}</h3>
                  </div>
                  <div className="kpi-icon bg-blue-100 text-blue-600">
                    <i className="fas fa-user" />
                  </div>
                </div>
              </div>

              <div className="kpi-card">
                <div className="kpi-content">
                  <div>
                    <p className="kpi-label">Total IAM Roles</p>
                    <h3 className="kpi-value">{roles.length}</h3>
                  </div>
                  <div className="kpi-icon bg-indigo-100 text-indigo-600">
                    <i className="fas fa-user-shield" />
                  </div>
                </div>
              </div>

              {isAws && (
                <div className="kpi-card">
                  <div className="kpi-content">
                    <div>
                      <p className="kpi-label">MFA Status</p>
                      <h3 className="kpi-value">{countNoMfa}</h3>
                      {countNoMfa > 0 && (
                        <p className="kpi-warning">Users potentially missing MFA</p>
                      )}
                    </div>
                    <div className="kpi-icon bg-red-100 text-red-600">
                      <i className="fas fa-shield-alt" />
                    </div>
                  </div>
                </div>
              )}
            </div>

            {/* Main Table Container */}
            <div className="table-container">
              {/* Tabs */}
              <div className="tabs-header">
                <nav className="tabs-nav">
                  <button
                    onClick={() => setActiveTab('users')}
                    className={`tab-button ${activeTab === 'users' ? 'active' : ''}`}
                  >
                    <i className="fas fa-user mr-2" />
                    <span>{labels.users}</span>
                    <span className="tab-badge">{users.length}</span>
                  </button>
                  <button
                    onClick={() => setActiveTab('roles')}
                    className={`tab-button ${activeTab === 'roles' ? 'active' : ''}`}
                  >
                    <i className="fas fa-user-shield mr-2" />
                    <span>IAM Roles</span>
                    <span className="tab-badge">{roles.length}</span>
                  </button>
                </nav>
              </div>

              {/* Search Bar */}
              <div className="search-bar">
                <div className="search-input-wrapper">
                  <input
                    type="text"
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                    placeholder="Search name or ID..."
                    className="search-input"
                  />
                  <div className="search-icon">
                    <i className="fas fa-search text-gray-400" />
                  </div>
                </div>
              </div>

              {/* Users Tab */}
              {activeTab === 'users' && (
                <div className="table-wrapper">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>{labels.userIdentity}</th>
                        <th>Groups</th>
                        <th>Attached Policies</th>
                        <th>Activity</th>
                      </tr>
                    </thead>
                    <tbody>
                      {renderUsersTableBody()}
                    </tbody>
                  </table>
                </div>
              )}

              {/* Roles Tab */}
              {activeTab === 'roles' && (
                <div className="table-wrapper">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>Role Details</th>
                        <th>Description</th>
                        <th>Attached Policies</th>
                        <th>Created</th>
                      </tr>
                    </thead>
                    <tbody>
                      {renderRolesTableBody()}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </>
        )}
      </div>
    </div>
  );
};

export default UserManagementPage;
