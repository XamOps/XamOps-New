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
import type { IamUser, IamRole, Policy } from '../types/userManagement';
import '../styles/userManagement.css';

const UserManagementPage: React.FC = () => {
  const {
    loading,
    error,
    accountName,
    accountId,
    provider,
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

  const [activeTab, setActiveTab] = useState<'users' | 'roles'>('users');

  return (
    <div className="user-management-page">
      {/* Header */}
      <header className="page-header">
        <div>
          <h1 className="page-title">
            <i className="fas fa-users-cog text-purple-600 mr-2" />
            User Management
          </h1>
          <p className="page-subtitle">Manage IAM Users, Roles, and Permissions</p>
        </div>
        <div className="header-actions">
          <div className="account-info">
            <p className="account-name">{accountName || 'Select an Account'}</p>
            <p className="account-id">{accountId || '...'}</p>
          </div>
          <button
            onClick={() => fetchData(true)}
            disabled={loading || !accountId}
            className="refresh-button"
          >
            <i className={`fas fa-sync-alt ${loading ? 'fa-spin' : ''}`} />
            <span>{loading ? 'Refreshing...' : 'Refresh Data'}</span>
          </button>
        </div>
      </header>

      {/* Main Content */}
      <div className="page-content">
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

        {/* Loading State */}
        {loading && !users.length && (
          <div className="loading-container">
            <div className="spinner" />
            <p className="loading-text">Fetching IAM details from {provider}...</p>
            <p className="loading-subtext">Account ID: {accountId}</p>
          </div>
        )}

        {/* Content */}
        {(!loading || users.length > 0) && (
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
                  <div className="kpi-icon bg-purple-100 text-purple-600">
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
                      {filteredUsers.length > 0 ? (
                        filteredUsers.map((user: IamUser) => (
                          <tr key={user.userId} className="table-row">
                            <td>
                              <div className="user-identity">
                                <div className="user-avatar">
                                  {user.userName.substring(0, 2).toUpperCase()}
                                </div>
                                <div>
                                  <div className="user-name">{user.userName}</div>
                                  <div className="user-id" title={user.arn}>
                                    {user.userId}
                                  </div>
                                </div>
                              </div>
                            </td>
                            <td>
                              <div className="groups-container">
                                {user.groups && user.groups.length > 0 ? (
                                  user.groups.map((group, idx) => (
                                    <span key={idx} className="group-badge">
                                      <i className="fas fa-users mr-1 text-[10px]" />
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
                                  user.attachedPolicies.map((policy: Policy, idx) => (
                                    <div key={idx} className="policy-item">
                                      <i className="fas fa-file-contract text-gray-400 mr-1.5" />
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
                        ))
                      ) : users.length > 0 ? (
                        <tr>
                          <td colSpan={4} className="empty-state">
                            No users match your search.
                          </td>
                        </tr>
                      ) : (
                        <tr>
                          <td colSpan={4} className="empty-state">
                            <i className="fas fa-ghost text-4xl text-gray-300 mb-2" />
                            <p className="font-semibold text-gray-600">No IAM Users found</p>
                          </td>
                        </tr>
                      )}
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
                      {filteredRoles.length > 0 ? (
                        filteredRoles.map((role: IamRole) => (
                          <tr key={role.roleId} className="table-row">
                            <td>
                              <div className="role-identity">
                                <div className="role-icon">
                                  <i className="fas fa-robot" />
                                </div>
                                <div>
                                  <div className="role-name">{role.roleName}</div>
                                  <div
                                    className="role-arn"
                                    title={role.arn}
                                    onClick={() => copyToClipboard(role.arn)}
                                  >
                                    <i className="far fa-copy mr-1" />
                                    {role.arn.split('/').pop()}
                                  </div>
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
                                  role.attachedPolicies.map((policy: Policy, idx) => (
                                    <div key={idx} className="policy-item">
                                      <i className="fas fa-shield-alt text-gray-400 mr-2 text-xs" />
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
                        ))
                      ) : roles.length > 0 ? (
                        <tr>
                          <td colSpan={4} className="empty-state">
                            No roles match your search.
                          </td>
                        </tr>
                      ) : (
                        <tr>
                          <td colSpan={4} className="empty-state">
                            <i className="fas fa-ghost text-4xl text-gray-300 mb-2" />
                            <p className="font-semibold text-gray-600">No IAM Roles found</p>
                          </td>
                        </tr>
                      )}
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
