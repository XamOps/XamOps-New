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

import React from 'react';
import { Icon } from '@iconify/react';
import { useUserManagement } from '../hooks/useUserManagement';
import type { IamUser, IamRole } from '../types/userManagement';
import { useTopbar } from '../contexts/TopbarContext';
import { useTabState } from '../hooks/useTabState';

const SKELETON_ITEMS = [1, 2, 3, 4, 5];

const KpiSkeleton = () => (
  <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5">
    <div className="h-3 w-24 bg-[rgba(0,0,0,0.06)] animate-pulse rounded mb-3" />
    <div className="h-8 w-16 bg-[rgba(0,0,0,0.06)] animate-pulse rounded" />
  </div>
);

const TableRowSkeleton = () => (
  <tr>
    <td className="px-4 py-3.5">
      <div className="flex items-center gap-3">
        <div className="h-10 w-10 rounded-xl bg-[rgba(0,0,0,0.06)] animate-pulse flex-shrink-0" />
        <div className="flex-1">
          <div className="h-4 w-32 bg-[rgba(0,0,0,0.06)] animate-pulse rounded mb-2" />
          <div className="h-3 w-48 bg-[rgba(0,0,0,0.06)] animate-pulse rounded" />
        </div>
      </div>
    </td>
    <td className="px-4 py-3.5">
      <div className="flex gap-2">
        <div className="h-6 w-16 bg-[rgba(0,0,0,0.06)] animate-pulse rounded-full" />
        <div className="h-6 w-20 bg-[rgba(0,0,0,0.06)] animate-pulse rounded-full" />
      </div>
    </td>
    <td className="px-4 py-3.5">
      <div className="flex flex-col gap-2">
        <div className="h-6 w-40 bg-[rgba(0,0,0,0.06)] animate-pulse rounded" />
        <div className="h-6 w-32 bg-[rgba(0,0,0,0.06)] animate-pulse rounded" />
      </div>
    </td>
    <td className="px-4 py-3.5">
      <div className="flex flex-col gap-2">
        <div className="h-3 w-24 bg-[rgba(0,0,0,0.06)] animate-pulse rounded" />
        <div className="h-3 w-24 bg-[rgba(0,0,0,0.06)] animate-pulse rounded" />
      </div>
    </td>
  </tr>
);

const CloudSVG = () => (
  <svg className="absolute right-2 bottom-2 opacity-[0.07]" width="80" height="60" viewBox="0 0 80 60">
    <path d="M65 48H20a18 18 0 010-36c1 0 2 0 3 .1A24 24 0 0168 28h-.5A16 16 0 0165 48z" fill="currentColor" />
  </svg>
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
    copyToClipboard,
    labels,
    fetchData,
    isAws,
  } = useUserManagement();

  const { setActions, clearActions } = useTopbar();
  const [activeTab, setActiveTab] = useTabState<'users' | 'roles'>('users');

  React.useEffect(() => {
    setActions(
      <div className="flex items-center gap-4">
        <div className="flex flex-col items-end mr-2">
          <p className="text-sm font-bold text-[rgba(0,0,0,0.88)] leading-none">{accountName || 'Select Account'}</p>
          <p className="text-[10px] text-[rgba(0,0,0,0.4)] font-mono mt-1">{accountId || '---'}</p>
        </div>
        <button
          onClick={() => fetchData(true)}
          disabled={loading || !accountId}
          className="flex items-center gap-2 px-4 py-2 bg-[rgba(0,0,0,0.88)] hover:bg-black text-white text-xs font-semibold rounded-md transition-colors disabled:opacity-50"
        >
          <Icon icon="mdi:sync" width={14} className={loading ? 'animate-spin' : ''} />
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
        <tr key={user.userId} className="hover:bg-[rgba(0,0,0,0.025)] transition-colors">
          <td className="px-4 py-3.5 whitespace-nowrap">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-indigo-50 border border-indigo-100 flex items-center justify-center flex-shrink-0">
                <span className="text-xs font-bold text-indigo-600">{user.userName.substring(0, 2).toUpperCase()}</span>
              </div>
              <div>
                <p className="text-sm font-bold text-[rgba(0,0,0,0.88)] leading-tight">{user.userName}</p>
                <div className="flex items-center gap-2 mt-0.5">
                  <code className="text-[10px] font-mono text-[rgba(0,0,0,0.4)]" title={user.arn}>{user.userId}</code>
                  {isAws && (
                    <span className={`inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[9px] font-bold uppercase tracking-wider ${
                      user.mfaEnabled
                        ? 'bg-emerald-50 border border-emerald-100 text-emerald-600'
                        : 'bg-rose-50 border border-rose-100 text-rose-500'
                    }`}>
                      <Icon icon={user.mfaEnabled ? 'mdi:shield-check' : 'mdi:alert'} width={9} />
                      {user.mfaEnabled ? 'MFA' : 'No MFA'}
                    </span>
                  )}
                </div>
              </div>
            </div>
          </td>
          <td className="px-4 py-3.5">
            <div className="flex flex-wrap gap-1.5">
              {user.groups && user.groups.length > 0 ? (
                user.groups.map((group) => (
                  <span key={`${user.userId}-${group}`} className="inline-flex items-center gap-1 px-2 py-1 bg-blue-50 border border-blue-100 text-blue-700 text-[10px] font-semibold rounded-full">
                    <Icon icon="mdi:account-group-outline" width={10} />
                    {group}
                  </span>
                ))
              ) : (
                <span className="text-xs text-[rgba(0,0,0,0.3)] italic">No Groups</span>
              )}
            </div>
          </td>
          <td className="px-4 py-3.5">
            <div className="flex flex-col gap-1.5">
              {user.attachedPolicies && user.attachedPolicies.length > 0 ? (
                user.attachedPolicies.map((policy) => (
                  <div key={`${user.userId}-${policy.policyName}`} className="flex items-center gap-2">
                    <Icon icon="mdi:file-document-outline" width={12} color="rgba(0,0,0,0.3)" />
                    <span className="text-xs text-[rgba(0,0,0,0.7)] font-medium">{policy.policyName}</span>
                    {policy.type === 'Managed' && (
                      <span className="px-1.5 py-0.5 bg-indigo-50 border border-indigo-100 text-indigo-600 text-[9px] font-bold rounded uppercase tracking-wider">Managed</span>
                    )}
                    {policy.type === 'Inline' && (
                      <span className="px-1.5 py-0.5 bg-amber-50 border border-amber-100 text-amber-600 text-[9px] font-bold rounded uppercase tracking-wider">Inline</span>
                    )}
                  </div>
                ))
              ) : (
                <span className="text-xs text-[rgba(0,0,0,0.3)] italic">No direct policies</span>
              )}
            </div>
          </td>
          <td className="px-4 py-3.5 whitespace-nowrap">
            <div className="flex flex-col gap-1">
              <div className="flex items-center gap-2">
                <span className="text-[10px] font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider w-20">Created:</span>
                <span className="text-xs text-[rgba(0,0,0,0.7)]">{formatDate(user.createDate)}</span>
              </div>
              <div className="flex items-center gap-2">
                <span className="text-[10px] font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider w-20">Last Pwd:</span>
                <span className={`text-xs font-medium ${isOldDate(user.passwordLastUsed) ? 'text-red-500 font-semibold' : 'text-[rgba(0,0,0,0.7)]'}`}>
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
          <td colSpan={4} className="py-16 text-center">
            <p className="text-sm text-[rgba(0,0,0,0.4)]">No users match your search.</p>
          </td>
        </tr>
      );
    }

    if (!loading) {
      return (
        <tr>
          <td colSpan={4} className="py-16 text-center">
            <div className="flex flex-col items-center gap-3">
              <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-xl flex items-center justify-center">
                <Icon icon="mdi:account-off-outline" width={28} color="rgba(0,0,0,0.25)" />
              </div>
              <p className="text-sm font-bold text-[rgba(0,0,0,0.5)]">No IAM Users found</p>
            </div>
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
        <tr key={role.roleId} className="hover:bg-[rgba(0,0,0,0.025)] transition-colors">
          <td className="px-4 py-3.5 whitespace-nowrap">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-violet-50 border border-violet-100 flex items-center justify-center flex-shrink-0">
                <Icon icon="mdi:robot-outline" width={16} color="#7C3AED" />
              </div>
              <div>
                <p className="text-sm font-bold text-[rgba(0,0,0,0.88)] leading-tight">{role.roleName}</p>
                <button
                  className="inline-flex items-center gap-1 text-[10px] font-bold text-indigo-600 hover:text-indigo-700 mt-0.5 uppercase tracking-wider"
                  title={`Copy ARN: ${role.arn}`}
                  onClick={() => copyToClipboard(role.arn)}
                >
                  <Icon icon="mdi:content-copy" width={9} />
                  {role.arn.split('/').pop()}
                </button>
              </div>
            </div>
          </td>
          <td className="px-4 py-3.5">
            <span className="text-xs text-[rgba(0,0,0,0.6)]">{role.description || <span className="text-[rgba(0,0,0,0.3)] italic">—</span>}</span>
          </td>
          <td className="px-4 py-3.5">
            <div className="flex flex-col gap-1.5">
              {role.attachedPolicies && role.attachedPolicies.length > 0 ? (
                role.attachedPolicies.map((policy) => (
                  <div key={`${role.roleId}-${policy.policyName}`} className="flex items-center gap-2">
                    <Icon icon="mdi:shield-outline" width={12} color="rgba(0,0,0,0.3)" />
                    <span className="text-xs text-[rgba(0,0,0,0.7)] font-medium">{policy.policyName}</span>
                    {policy.type === 'Managed' && (
                      <span className="px-1.5 py-0.5 bg-orange-50 border border-orange-100 text-orange-600 text-[9px] font-bold rounded uppercase tracking-wider">AWS</span>
                    )}
                  </div>
                ))
              ) : (
                <span className="text-xs text-[rgba(0,0,0,0.3)] italic">No policies attached</span>
              )}
            </div>
          </td>
          <td className="px-4 py-3.5 whitespace-nowrap">
            <span className="text-xs text-[rgba(0,0,0,0.6)]">{formatDate(role.createDate)}</span>
          </td>
        </tr>
      ));
    }

    if (!loading && roles.length > 0) {
      return (
        <tr>
          <td colSpan={4} className="py-16 text-center">
            <p className="text-sm text-[rgba(0,0,0,0.4)]">No roles match your search.</p>
          </td>
        </tr>
      );
    }

    if (!loading) {
      return (
        <tr>
          <td colSpan={4} className="py-16 text-center">
            <div className="flex flex-col items-center gap-3">
              <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-xl flex items-center justify-center">
                <Icon icon="mdi:account-off-outline" width={28} color="rgba(0,0,0,0.25)" />
              </div>
              <p className="text-sm font-bold text-[rgba(0,0,0,0.5)]">No IAM Roles found</p>
            </div>
          </td>
        </tr>
      );
    }

    return null;
  };

  return (
    <div className="px-6 pb-12 pt-6 space-y-6">
      {/* Error Alert */}
      {error && (
        <div className="flex items-start gap-3 px-4 py-3 bg-rose-50 border border-rose-100 rounded-xl">
          <Icon icon="mdi:alert-circle-outline" width={16} color="#F43F5E" className="flex-shrink-0 mt-0.5" />
          <div>
            <p className="text-sm font-semibold text-rose-700">Status</p>
            <p className="text-sm text-rose-600">{error}</p>
          </div>
        </div>
      )}

      {/* KPI Cards */}
      {loading && !users.length ? (
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <KpiSkeleton />
          <KpiSkeleton />
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          {/* Total Users */}
          <div className="relative bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 overflow-hidden">
            <CloudSVG />
            <div className="flex items-start justify-between">
              <div>
                <p className="text-[10px] font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider mb-2">{`Total ${labels.users}`}</p>
                <p style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>
                  {users.length}
                </p>
              </div>
              <div className="w-10 h-10 bg-blue-50 border border-blue-100 rounded-xl flex items-center justify-center flex-shrink-0">
                <Icon icon="mdi:account-outline" width={18} color="#2563EB" />
              </div>
            </div>
          </div>

          {/* Total Roles */}
          <div className="relative bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 overflow-hidden">
            <CloudSVG />
            <div className="flex items-start justify-between">
              <div>
                <p className="text-[10px] font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider mb-2">Total IAM Roles</p>
                <p style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>
                  {roles.length}
                </p>
              </div>
              <div className="w-10 h-10 bg-violet-50 border border-violet-100 rounded-xl flex items-center justify-center flex-shrink-0">
                <Icon icon="mdi:shield-account-outline" width={18} color="#7C3AED" />
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Main Table Container */}
      {!(loading && !users.length) && (
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
          {/* Tabs Header */}
          <div className="flex items-center justify-between px-6 py-0 border-b border-[rgba(0,0,0,0.06)]">
            <nav className="flex">
              <button
                onClick={() => setActiveTab('users')}
                className={`flex items-center gap-2 px-4 py-4 text-xs font-semibold border-b-2 transition-colors ${
                  activeTab === 'users'
                    ? 'border-[rgba(0,0,0,0.88)] text-[rgba(0,0,0,0.88)]'
                    : 'border-transparent text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.7)]'
                }`}
              >
                <Icon icon="mdi:account-outline" width={14} />
                <span>{labels.users}</span>
                <span className="text-[10px] px-1.5 py-0.5 bg-[rgba(0,0,0,0.06)] rounded-full font-bold text-[rgba(0,0,0,0.5)]">{users.length}</span>
              </button>
              <button
                onClick={() => setActiveTab('roles')}
                className={`flex items-center gap-2 px-4 py-4 text-xs font-semibold border-b-2 transition-colors ${
                  activeTab === 'roles'
                    ? 'border-[rgba(0,0,0,0.88)] text-[rgba(0,0,0,0.88)]'
                    : 'border-transparent text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.7)]'
                }`}
              >
                <Icon icon="mdi:shield-account-outline" width={14} />
                <span>IAM Roles</span>
                <span className="text-[10px] px-1.5 py-0.5 bg-[rgba(0,0,0,0.06)] rounded-full font-bold text-[rgba(0,0,0,0.5)]">{roles.length}</span>
              </button>
            </nav>
          </div>

          {/* Search Bar */}
          <div className="px-4 py-3 border-b border-[rgba(0,0,0,0.06)]">
            <div className="relative">
              <div className="absolute inset-y-0 left-3 flex items-center pointer-events-none">
                <Icon icon="mdi:magnify" width={14} color="rgba(0,0,0,0.35)" />
              </div>
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Search name or ID..."
                className="w-full pl-9 pr-4 py-2 text-xs bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] rounded-lg text-[rgba(0,0,0,0.88)] placeholder-[rgba(0,0,0,0.3)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)] focus:border-[rgba(0,0,0,0.2)] transition-colors"
              />
            </div>
          </div>

          {/* Users Tab */}
          {activeTab === 'users' && (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)] text-left">
                    {[labels.userIdentity, 'Groups', 'Attached Policies', 'Activity'].map((h) => (
                      <th key={h} className="px-4 py-3 text-xs font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider whitespace-nowrap">
                        {h}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-[rgba(0,0,0,0.04)]">
                  {renderUsersTableBody()}
                </tbody>
              </table>
            </div>
          )}

          {/* Roles Tab */}
          {activeTab === 'roles' && (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)] text-left">
                    {['Role Details', 'Description', 'Attached Policies', 'Created'].map((h) => (
                      <th key={h} className="px-4 py-3 text-xs font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider whitespace-nowrap">
                        {h}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-[rgba(0,0,0,0.04)]">
                  {renderRolesTableBody()}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {/* Loading skeleton for table */}
      {loading && !users.length && (
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
          <div className="px-6 py-4 border-b border-[rgba(0,0,0,0.06)]">
            <div className="h-4 w-32 bg-[rgba(0,0,0,0.06)] animate-pulse rounded" />
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <tbody className="divide-y divide-[rgba(0,0,0,0.04)]">
                {SKELETON_ITEMS.map((item) => <TableRowSkeleton key={`main-skeleton-${item}`} />)}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
};

export default UserManagementPage;
