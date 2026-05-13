/**
 * My Profile Page - React Component
 *
 * Converted from: my-profile.html
 *
 * Features:
 * - User profile display with cloud accounts
 * - Password change functionality
 * - Team member management (for primary users)
 * - Cloud account access control per team member
 * - Role-based API routing (XAMOPS/BILLOPS)
 *
 * All backend connections and functionality preserved
 */

import React, { useState, useEffect, useMemo } from 'react';
import { useMyProfile } from '../hooks/useMyProfile';
import { DialogRoot, DialogPanel } from '../components/ui';
import { TeamUser, CloudAccount } from '../types/myProfile';

const ProfileSkeleton: React.FC = () => {
  return (
    <div className="w-full space-y-4">
      <div className="bg-white border border-gray-200 rounded-md overflow-hidden">
        <div className="px-6 py-5 border-b border-gray-100 flex items-center gap-4">
          <div className="skeleton h-14 w-14 rounded-md flex-shrink-0"></div>
          <div>
            <div className="skeleton h-5 w-40 rounded mb-2"></div>
            <div className="skeleton h-4 w-56 rounded"></div>
          </div>
        </div>
        <div className="p-5">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4 mb-5">
            <div className="skeleton h-16 rounded-md"></div>
            <div className="skeleton h-16 rounded-md"></div>
          </div>
          <div className="pt-4 border-t border-gray-100">
            <div className="skeleton h-9 w-48 rounded-md"></div>
          </div>
        </div>
      </div>
      <div className="flex justify-between items-center">
        <div className="skeleton h-5 w-48 rounded"></div>
        <div className="skeleton h-9 w-40 rounded-md"></div>
      </div>
    </div>
  );
};

const MyProfilePage: React.FC = () => {
  const {
    profile,
    teamUsers,
    selectedUser,
    isEditing,
    showPasswordModal,
    passwordForm,
    loading,
    error,
    newTeamUser,
    editTeamUser,
    cancelEdit,
    saveTeamUser,
    deleteTeamUser,
    changePassword,
    updateSelectedUser,
    updatePasswordForm,
    toggleAccountId,
    toggleTab,
    setShowPasswordModal
  } = useMyProfile();

  const BILLOPS_ADMIN_TABS = [
    { key: 'admin-dashboard', label: 'Admin Dashboard', icon: 'fas fa-chart-bar' },
    { key: 'support-inbox', label: 'Support Inbox', icon: 'fas fa-headset' },
    { key: 'all-tickets', label: 'All Tickets', icon: 'fas fa-ticket-alt' },
    { key: 'all-invoices', label: 'All Invoices', icon: 'fas fa-file-invoice' },
    { key: 'credit-requests', label: 'Credit Requests', icon: 'fas fa-coins' },
    { key: 'agreement-admin', label: 'Agreement Admin', icon: 'fas fa-file-signature' },
    { key: 'client-details', label: 'Client Details', icon: 'fas fa-users-cog' },
    { key: 'autospotting-admin', label: 'AutoSpotting Admin', icon: 'fas fa-magic' },
    { key: 'user-manager', label: 'User Manager', icon: 'fas fa-user-cog' },
  ];

  // ── Account Access UI state ──────────────────────────────────────────
  const [accountAccessMode, setAccountAccessMode] = useState<'all' | 'selected'>('selected');
  const [accountSearch, setAccountSearch] = useState('');
  const [accountProviderFilter, setAccountProviderFilter] = useState<'All' | 'AWS' | 'GCP' | 'Azure'>('All');

  // When a form opens, restore mode from allAccountsAccess flag
  useEffect(() => {
    if (isEditing) {
      setAccountAccessMode(selectedUser.allAccountsAccess ? 'all' : 'selected');
      setAccountSearch('');
      setAccountProviderFilter('All');
    }
  }, [isEditing]);

  // Accounts visible in the picker after search + provider filter
  const filteredAccounts = useMemo(() => {
    const accounts: CloudAccount[] = profile.cloudAccounts ?? [];
    return accounts.filter(a => {
      const matchProvider = accountProviderFilter === 'All' || a.provider === accountProviderFilter;
      const matchSearch = !accountSearch ||
        a.accountName.toLowerCase().includes(accountSearch.toLowerCase()) ||
        a.provider.toLowerCase().includes(accountSearch.toLowerCase());
      return matchProvider && matchSearch;
    });
  }, [profile.cloudAccounts, accountSearch, accountProviderFilter]);

  const handleAccountModeChange = (mode: 'all' | 'selected') => {
    setAccountAccessMode(mode);
    if (mode === 'all') {
      updateSelectedUser('allAccountsAccess', true);
      updateSelectedUser('accountIds', []);
    } else {
      updateSelectedUser('allAccountsAccess', false);
    }
  };

  const selectVisibleAccounts = () => {
    const visibleIds = filteredAccounts.map(a => a.id);
    const merged = Array.from(new Set([...selectedUser.accountIds, ...visibleIds]));
    updateSelectedUser('accountIds', merged);
  };

  const clearVisibleAccounts = () => {
    const visibleIds = new Set(filteredAccounts.map(a => a.id));
    updateSelectedUser('accountIds', selectedUser.accountIds.filter(id => !visibleIds.has(id)));
  };
  // ─────────────────────────────────────────────────────────────────────

  if (error) {
    return (
      <div className="flex items-center justify-center flex-1 bg-[#f8fafc] p-10">
        <div className="bg-red-50 text-red-700 p-6 rounded-md border border-red-100 max-w-md text-center">
          <i className="fas fa-circle-exclamation text-2xl mb-3"></i>
          <h2 className="text-base font-semibold mb-2">Access Denied</h2>
          <p className="text-sm mb-4">{error}</p>
          <button onClick={() => window.location.reload()} className="px-4 py-2 bg-red-600 text-white rounded-md text-sm font-medium hover:bg-red-700 transition-colors">
            Try Reauthenticating
          </button>
        </div>
      </div>
    );
  }

  return (
    <main id="profile-main-container" className="flex-1 w-full flex flex-col items-stretch p-6 bg-[#f5f6f8] my-profile-page">
      {loading && !profile.username ? (
        <ProfileSkeleton />
      ) : (
        <div className="w-full space-y-4">
          {/* Profile Card */}
          <div className="profile-card-container">
            <div className="profile-header-gradient px-6 py-5">
              <div className="flex items-center gap-4">
                <div className="profile-avatar">
                  <span className="text-xl font-bold text-white">
                    {profile.username ? profile.username.charAt(0).toUpperCase() : 'U'}
                  </span>
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-1">
                    <h2 className="text-[15px] font-semibold text-gray-900 truncate">{profile.username || 'Loading...'}</h2>
                    {profile.isPrimaryUser && (
                      <span className="px-2 py-0.5 rounded text-[10px] font-semibold uppercase tracking-wide border border-amber-200 bg-amber-50 text-amber-700 flex items-center gap-1 flex-shrink-0">
                        <i className="fas fa-crown text-amber-500 text-[9px]"></i>
                        Admin
                      </span>
                    )}
                  </div>
                  <div className="flex items-center gap-3 flex-wrap">
                    <p className="text-xs text-gray-500 flex items-center gap-1.5">
                      <i className="fas fa-envelope text-gray-400 text-[10px]"></i>
                      {profile.email || ''}
                    </p>
                    <span className="inline-flex items-center px-2 py-0.5 bg-white rounded text-[11px] font-medium text-gray-600 border border-gray-200">
                      <i className="fas fa-user-shield mr-1.5 text-indigo-400 text-[10px]"></i>
                      {profile.role || ''}
                    </span>
                  </div>
                </div>
              </div>
            </div>

            <div className="p-5">
              <div className="grid grid-cols-1 md:grid-cols-2 gap-3 mb-5">
                <div className="profile-info-item">
                  <label className="profile-info-label">
                    <i className="fas fa-building-columns mr-2"></i>
                    Organization
                  </label>
                  <p className="profile-info-value">{profile.clientName || 'N/A'}</p>
                </div>
                <div className="profile-info-item">
                  <label className="profile-info-label">
                    <i className="fas fa-fingerprint mr-2"></i>
                    Account Type
                  </label>
                  <p className="profile-info-value text-indigo-600">
                    {profile.isPrimaryUser ? 'Primary Administrator' : 'Standard Team Member'}
                  </p>
                </div>
              </div>

              {/* Connected Cloud Accounts */}
              <div className="mb-5">
                <label className="profile-info-label mb-3 flex items-center">
                  <i className="fas fa-network-wired mr-2"></i>
                  Authorized Cloud Environments
                </label>
                <div className="flex flex-wrap gap-1.5">
                  {profile.cloudAccounts && profile.cloudAccounts.length > 0 ? (
                    profile.cloudAccounts.map((account: CloudAccount) => (
                      <span
                        key={account.id}
                        className={`cloud-account-badge ${account.provider === 'AWS'
                          ? 'cloud-account-aws'
                          : account.provider === 'GCP'
                            ? 'cloud-account-gcp'
                            : 'cloud-account-azure'
                          }`}
                      >
                        <i className={account.provider === 'AWS' ? 'fab fa-aws' : account.provider === 'GCP' ? 'fab fa-google' : 'fab fa-microsoft'}></i>
                        {account.accountName}
                      </span>
                    ))
                  ) : (
                    <div className="p-4 bg-gray-50 rounded-md border border-dashed border-gray-200 text-gray-400 w-full text-center text-sm">
                      <i className="fas fa-cloud-slash mr-2"></i>
                      No cloud environments linked to this profile
                    </div>
                  )}
                </div>
              </div>

              {/* Change Password Button */}
              <div className="pt-4 border-t border-gray-100 flex items-center gap-3">
                <button
                  onClick={() => setShowPasswordModal(true)}
                  className="inline-flex items-center gap-2 px-4 py-2 rounded-md text-sm font-medium text-indigo-700 bg-indigo-50 border border-indigo-200 hover:bg-indigo-100 transition-colors"
                >
                  <i className="fas fa-shield-halved text-indigo-500 text-xs"></i>
                  Security & Password Settings
                </button>
              </div>
            </div>
          </div>

          {/* Team Users Section (Only for Primary Users) */}
          {profile.isPrimaryUser && (
            <div className="team-members-section space-y-3">
              <div className="bg-white border border-gray-200 rounded-md px-5 py-4 flex justify-between items-center shadow-sm">
                <div className="flex items-center gap-3">
                  <div className="w-8 h-8 rounded-md bg-indigo-50 border border-indigo-100 flex items-center justify-center flex-shrink-0">
                    <i className="fas fa-users-gear text-indigo-500 text-xs"></i>
                  </div>
                  <div>
                    <h2 className="text-[13px] font-semibold text-gray-900">Team Management</h2>
                    <p className="text-xs text-gray-400 mt-0.5">Manage access and permissions for your organization's team members.</p>
                  </div>
                </div>
                <button
                  onClick={newTeamUser}
                  className="btn-add-team-member"
                >
                  <i className="fas fa-plus mr-2 text-xs"></i>
                  Add Member
                </button>
              </div>

              {/* Team User Form */}
              {isEditing && (
                <div className="bg-white p-5 rounded-md border border-gray-200 team-user-form relative z-10">
                  <h3 className="text-[13px] font-semibold text-gray-900 mb-4 pb-3 border-b border-gray-100 flex items-center gap-2">
                    <i className="fas fa-user-plus text-indigo-500 text-xs"></i>
                    {selectedUser.id ? 'Modify Permissions' : 'Onboard Team Member'}
                  </h3>
                  <form
                    onSubmit={(e) => {
                      e.preventDefault();
                      saveTeamUser();
                    }}
                  >
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                      <div className="form-group">
                        <label className="form-label">
                          <i className="fas fa-user mr-2"></i>
                          Username
                        </label>
                        <input
                          type="text"
                          value={selectedUser.username}
                          onChange={(e) => updateSelectedUser('username', e.target.value)}
                          required
                          className="form-input"
                          placeholder="Enter username"
                        />
                      </div>
                      <div className="form-group">
                        <label className="form-label">
                          <i className="fas fa-envelope mr-2"></i>
                          Email
                        </label>
                        <input
                          type="email"
                          value={selectedUser.email}
                          onChange={(e) => updateSelectedUser('email', e.target.value)}
                          required
                          className="form-input"
                          placeholder="Enter email address"
                        />
                      </div>
                      <div className="form-group">
                        <label className="form-label">
                          <i className="fas fa-lock mr-2"></i>
                          Password
                        </label>
                        <input
                          type="password"
                          value={selectedUser.password}
                          onChange={(e) => updateSelectedUser('password', e.target.value)}
                          required={!selectedUser.id}
                          className="form-input"
                          placeholder={
                            selectedUser.id
                              ? 'Leave blank to keep current password'
                              : 'Enter password'
                          }
                        />
                      </div>
                      <div className="form-group">
                        <label className="form-label">
                          <i className="fas fa-user-tag mr-2"></i>
                          Role
                        </label>
                        {profile.role === 'ROLE_BILLOPS_ADMIN' ? (
                          <>
                            <select
                              value={selectedUser.role || profile.role}
                              onChange={(e) => updateSelectedUser('role', e.target.value)}
                              className="form-input"
                            >
                              <option value="ROLE_BILLOPS_ADMIN">BILLOPS ADMIN (sub-user, scoped to accounts)</option>
                              <option value="ROLE_BILLOPS_ADMIN_SUB">BILLOPS ADMIN SUB (sub-admin, scoped to assigned clients)</option>
                            </select>
                            {(selectedUser.role || profile.role) === 'ROLE_BILLOPS_ADMIN_SUB' && (
                              <p className="form-helper-text text-amber-600">
                                <i className="fas fa-info-circle mr-1"></i>
                                Sub-admin will only see clients assigned to them. Assign clients after creation via Support → Sub-Admin Clients.
                              </p>
                            )}
                          </>
                        ) : (
                          <>
                            <input
                              type="text"
                              value={profile.role}
                              disabled
                              className="form-input-disabled"
                            />
                            <p className="form-helper-text">Role is inherited from your account</p>
                          </>
                        )}
                      </div>
                    </div>

                    {/* Cloud Account Access Selection — hidden for ROLE_BILLOPS_ADMIN_SUB (uses client scoping instead) */}
                    {(selectedUser.role || profile.role) !== 'ROLE_BILLOPS_ADMIN_SUB' && profile.cloudAccounts && profile.cloudAccounts.length > 0 && (
                      <div className="mt-6">
                        <label className="form-label mb-3 block">
                          <i className="fas fa-cloud mr-2"></i>
                          Cloud Account Access
                        </label>

                        {/* Mode toggle: All vs Selected */}
                        <div className="grid grid-cols-2 gap-3 mb-4">
                          <button
                            type="button"
                            onClick={() => handleAccountModeChange('all')}
                            className={`flex items-center gap-3 p-3 rounded-md border transition-all text-left ${
                              accountAccessMode === 'all'
                                ? 'border-indigo-400 bg-indigo-50'
                                : 'border-gray-200 bg-white hover:border-indigo-300'
                            }`}
                          >
                            <div className={`w-4 h-4 rounded-full border-2 flex items-center justify-center flex-shrink-0 ${
                              accountAccessMode === 'all' ? 'border-indigo-500' : 'border-gray-300'
                            }`}>
                              {accountAccessMode === 'all' && <div className="w-2 h-2 rounded-full bg-indigo-500" />}
                            </div>
                            <div>
                              <p className="text-sm font-semibold text-gray-800">All Accounts</p>
                              <p className="text-xs text-gray-500">Includes future accounts</p>
                            </div>
                          </button>
                          <button
                            type="button"
                            onClick={() => handleAccountModeChange('selected')}
                            className={`flex items-center gap-3 p-3 rounded-md border transition-all text-left ${
                              accountAccessMode === 'selected'
                                ? 'border-indigo-400 bg-indigo-50'
                                : 'border-gray-200 bg-white hover:border-indigo-300'
                            }`}
                          >
                            <div className={`w-4 h-4 rounded-full border-2 flex items-center justify-center flex-shrink-0 ${
                              accountAccessMode === 'selected' ? 'border-indigo-500' : 'border-gray-300'
                            }`}>
                              {accountAccessMode === 'selected' && <div className="w-2 h-2 rounded-full bg-indigo-500" />}
                            </div>
                            <div>
                              <p className="text-sm font-semibold text-gray-800">Selected Accounts</p>
                              <p className="text-xs text-gray-500">
                                {accountAccessMode === 'selected' ? `${selectedUser.accountIds.length} selected` : 'Choose specific accounts'}
                              </p>
                            </div>
                          </button>
                        </div>

                        {/* Selected mode: search + filter + scrollable list */}
                        {accountAccessMode === 'selected' && (
                          <div className="border border-gray-200 rounded-md overflow-hidden">
                            {/* Search bar */}
                            <div className="p-3 border-b border-gray-100 bg-gray-50">
                              <div className="relative">
                                <i className="fas fa-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 text-xs"></i>
                                <input
                                  type="text"
                                  value={accountSearch}
                                  onChange={e => setAccountSearch(e.target.value)}
                                  placeholder="Search by name or provider..."
                                  className="w-full pl-8 pr-8 py-2 text-sm bg-white border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-300 focus:border-indigo-400"
                                />
                                {accountSearch && (
                                  <button
                                    type="button"
                                    onClick={() => setAccountSearch('')}
                                    className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
                                  >
                                    <i className="fas fa-times text-xs"></i>
                                  </button>
                                )}
                              </div>
                            </div>

                            {/* Provider filter pills + shortcuts */}
                            <div className="px-3 py-2 border-b border-gray-100 bg-gray-50 flex items-center justify-between flex-wrap gap-2">
                              <div className="flex items-center gap-1.5">
                                {(['All', 'AWS', 'GCP', 'Azure'] as const).map(p => (
                                  <button
                                    key={p}
                                    type="button"
                                    onClick={() => setAccountProviderFilter(p)}
                                    className={`px-2.5 py-1 rounded-lg text-xs font-semibold transition-all ${
                                      accountProviderFilter === p
                                        ? 'bg-indigo-600 text-white'
                                        : 'bg-white border border-gray-200 text-gray-600 hover:border-indigo-300 hover:text-indigo-600'
                                    }`}
                                  >
                                    {p === 'AWS' && <i className="fab fa-aws mr-1"></i>}
                                    {p === 'GCP' && <i className="fab fa-google mr-1"></i>}
                                    {p === 'Azure' && <i className="fab fa-microsoft mr-1"></i>}
                                    {p}
                                    {p !== 'All' && (
                                      <span className="ml-1 opacity-70">
                                        {(profile.cloudAccounts ?? []).filter(a => a.provider === p).length}
                                      </span>
                                    )}
                                  </button>
                                ))}
                              </div>
                              <div className="flex items-center gap-3 text-xs">
                                <button
                                  type="button"
                                  onClick={selectVisibleAccounts}
                                  className="text-indigo-600 hover:text-indigo-800 font-semibold"
                                >
                                  <i className="fas fa-check-double mr-1"></i>
                                  Select all ({filteredAccounts.length})
                                </button>
                                <span className="text-gray-300">|</span>
                                <button
                                  type="button"
                                  onClick={clearVisibleAccounts}
                                  className="text-red-500 hover:text-red-700 font-semibold"
                                >
                                  <i className="fas fa-times mr-1"></i>
                                  Clear
                                </button>
                                <span className="text-gray-300">|</span>
                                <span className="font-bold text-indigo-700">
                                  {selectedUser.accountIds.length} selected
                                </span>
                              </div>
                            </div>

                            {/* Scrollable account list */}
                            <div className="max-h-64 overflow-y-auto divide-y divide-gray-50">
                              {filteredAccounts.length === 0 ? (
                                <div className="py-8 text-center text-sm text-gray-400">
                                  <i className="fas fa-search mb-2 block text-lg"></i>
                                  No accounts match your search
                                </div>
                              ) : filteredAccounts.map((account: CloudAccount) => (
                                <label
                                  key={account.id}
                                  className={`flex items-center gap-3 px-4 py-2.5 cursor-pointer transition-colors hover:bg-indigo-50/50 ${
                                    selectedUser.accountIds.includes(account.id) ? 'bg-indigo-50/30' : ''
                                  }`}
                                >
                                  <input
                                    type="checkbox"
                                    checked={selectedUser.accountIds.includes(account.id)}
                                    onChange={() => toggleAccountId(account.id)}
                                    className="checkbox-input flex-shrink-0"
                                  />
                                  <i className={`text-sm flex-shrink-0 ${
                                    account.provider === 'AWS' ? 'fab fa-aws text-orange-500'
                                    : account.provider === 'GCP' ? 'fab fa-google text-blue-500'
                                    : 'fab fa-microsoft text-sky-500'
                                  }`}></i>
                                  <span className="text-sm text-gray-700 truncate">{account.accountName}</span>
                                  <span className={`ml-auto text-[10px] font-bold px-1.5 py-0.5 rounded flex-shrink-0 ${
                                    account.provider === 'AWS' ? 'bg-orange-50 text-orange-600'
                                    : account.provider === 'GCP' ? 'bg-blue-50 text-blue-600'
                                    : 'bg-sky-50 text-sky-600'
                                  }`}>{account.provider}</span>
                                </label>
                              ))}
                            </div>
                          </div>
                        )}
                      </div>
                    )}

                    {/* Tab Access Selection — only for ROLE_BILLOPS_ADMIN */}
                    {profile.role === 'ROLE_BILLOPS_ADMIN' && (
                      <div className="mt-6">
                        <label className="form-label mb-3 block">
                          <i className="fas fa-th-large mr-2"></i>
                          Tab Access
                        </label>
                        <p className="text-sm text-gray-500 mb-3">
                          Select which admin tabs this team member can access
                        </p>
                        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                          {BILLOPS_ADMIN_TABS.map(tab => (
                            <label
                              key={tab.key}
                              className="cloud-account-checkbox"
                            >
                              <input
                                type="checkbox"
                                checked={selectedUser.allowedTabs.includes(tab.key)}
                                onChange={() => toggleTab(tab.key)}
                                className="checkbox-input"
                              />
                              <div className="ml-3 flex items-center">
                                <i className={`${tab.icon} mr-2 text-indigo-500`}></i>
                                <span className="text-sm font-medium text-gray-700">
                                  {tab.label}
                                </span>
                              </div>
                            </label>
                          ))}
                        </div>

                        {/* Ticket Category Sub-Access — shown when All Tickets tab is enabled */}
                        {selectedUser.allowedTabs.includes('all-tickets') && (
                          <div className="mt-3 ml-4 p-4 bg-indigo-50 rounded-md border border-indigo-200">
                            <p className="text-xs font-bold text-indigo-700 uppercase tracking-widest mb-3">
                              <i className="fas fa-filter mr-1.5"></i>
                              Ticket Category Access
                            </p>
                            <p className="text-xs text-indigo-500 mb-3">
                              Choose which ticket categories this member can view
                            </p>
                            <div className="grid grid-cols-1 md:grid-cols-3 gap-2">
                              {[
                                { key: 'tickets-account-billing', label: 'Account & Billing', icon: 'fas fa-file-invoice-dollar' },
                                { key: 'tickets-technical', label: 'Technical Support', icon: 'fas fa-server' },
                                { key: 'tickets-all', label: 'All Categories', icon: 'fas fa-list' },
                              ].map(cat => (
                                <label
                                  key={cat.key}
                                  className="flex items-center gap-2 px-3 py-2 bg-white rounded-lg border border-indigo-100 cursor-pointer hover:border-indigo-300 transition-colors"
                                >
                                  <input
                                    type="checkbox"
                                    checked={selectedUser.allowedTabs.includes(cat.key)}
                                    onChange={() => toggleTab(cat.key)}
                                    className="checkbox-input"
                                  />
                                  <i className={`${cat.icon} text-indigo-400 text-xs`}></i>
                                  <span className="text-xs font-medium text-gray-700">{cat.label}</span>
                                </label>
                              ))}
                            </div>
                          </div>
                        )}
                      </div>
                    )}

                    <div className="mt-8 flex justify-end space-x-4">
                      <button
                        type="button"
                        onClick={cancelEdit}
                        className="btn-cancel"
                      >
                        <i className="fas fa-times mr-2"></i>
                        Cancel
                      </button>
                      <button type="submit" className="btn-save">
                        <i className="fas fa-check mr-2"></i>
                        Save Team Member
                      </button>
                    </div>
                  </form>
                </div>
              )}

              {/* Stats bar */}
              {teamUsers.length > 0 && (
                <div className="bg-white border border-gray-200 rounded-md grid grid-cols-3 divide-x divide-gray-100 shadow-sm">
                  <div className="px-5 py-3 flex items-center gap-3">
                    <div className="w-8 h-8 rounded-md bg-indigo-50 border border-indigo-100 flex items-center justify-center flex-shrink-0">
                      <i className="fas fa-users text-indigo-500 text-xs"></i>
                    </div>
                    <div>
                      <p className="text-lg font-bold text-gray-900 leading-none">{teamUsers.length}</p>
                      <p className="text-[11px] text-gray-400 mt-0.5">Members</p>
                    </div>
                  </div>
                  <div className="px-5 py-3 flex items-center gap-3">
                    <div className="w-8 h-8 rounded-md bg-emerald-50 border border-emerald-100 flex items-center justify-center flex-shrink-0">
                      <i className="fas fa-cloud text-emerald-500 text-xs"></i>
                    </div>
                    <div>
                      <p className="text-lg font-bold text-gray-900 leading-none">
                        {teamUsers.filter(u => u.allAccountsAccess).length > 0
                          ? `${teamUsers.filter(u => u.allAccountsAccess).length} all-access`
                          : teamUsers.reduce((sum, u) => sum + (u.cloudAccounts?.length ?? 0), 0)}
                      </p>
                      <p className="text-[11px] text-gray-400 mt-0.5">Env. grants</p>
                    </div>
                  </div>
                  <div className="px-5 py-3 flex items-center gap-3">
                    <div className="w-8 h-8 rounded-md bg-purple-50 border border-purple-100 flex items-center justify-center flex-shrink-0">
                      <i className="fas fa-th-large text-purple-500 text-xs"></i>
                    </div>
                    <div>
                      <p className="text-lg font-bold text-gray-900 leading-none">
                        {teamUsers.reduce((sum, u) => sum + (u.allowedTabs?.filter(t => !t.startsWith('tickets-')).length ?? 0), 0)}
                      </p>
                      <p className="text-[11px] text-gray-400 mt-0.5">Tab grants</p>
                    </div>
                  </div>
                </div>
              )}

              {/* Member cards */}
              {teamUsers.length > 0 ? (
                <div className="space-y-2">
                  {teamUsers.map((user: TeamUser) => {
                    const TICKET_CATEGORY_LABELS: Record<string, { label: string; icon: string }> = {
                      'tickets-account-billing': { label: 'Account & Billing', icon: 'fas fa-file-invoice-dollar' },
                      'tickets-technical':       { label: 'Technical Support', icon: 'fas fa-server' },
                      'tickets-all':             { label: 'All Categories', icon: 'fas fa-list' },
                    };
                    const TAB_COLORS: Record<string, string> = {
                      'admin-dashboard':    'bg-purple-50 text-purple-700 border-purple-200',
                      'support-inbox':      'bg-sky-50 text-sky-700 border-sky-200',
                      'all-tickets':        'bg-blue-50 text-blue-700 border-blue-200',
                      'all-invoices':       'bg-teal-50 text-teal-700 border-teal-200',
                      'credit-requests':    'bg-emerald-50 text-emerald-700 border-emerald-200',
                      'agreement-admin':    'bg-gray-50 text-gray-600 border-gray-300',
                      'client-details':     'bg-indigo-50 text-indigo-700 border-indigo-200',
                      'autospotting-admin': 'bg-orange-50 text-orange-700 border-orange-200',
                      'user-manager':       'bg-rose-50 text-rose-700 border-rose-200',
                    };
                    const mainTabs = (user.allowedTabs ?? []).filter(k => !k.startsWith('tickets-'));
                    const ticketCats = (user.allowedTabs ?? []).filter(k => k.startsWith('tickets-'));
                    const AVATAR_GRADIENTS = [
                      'linear-gradient(135deg, #6366f1, #a855f7)',
                      'linear-gradient(135deg, #ec4899, #f97316)',
                      'linear-gradient(135deg, #06b6d4, #3b82f6)',
                      'linear-gradient(135deg, #84cc16, #10b981)',
                      'linear-gradient(135deg, #f43f5e, #ec4899)',
                      'linear-gradient(135deg, #8b5cf6, #06b6d4)',
                      'linear-gradient(135deg, #f97316, #eab308)',
                      'linear-gradient(135deg, #14b8a6, #6366f1)',
                    ];
                    const avatarColor = AVATAR_GRADIENTS[(user.username?.charCodeAt(0) ?? 0) % AVATAR_GRADIENTS.length];

                    return (
                      <div key={user.id} className="member-card bg-white border border-gray-200 rounded-md shadow-sm overflow-hidden">
                        {/* Card top — identity + actions */}
                        <div className="px-4 py-3 flex items-center gap-3 border-b border-gray-100">
                          {/* Avatar */}
                          <div
                            className="w-9 h-9 rounded-lg flex items-center justify-center flex-shrink-0 text-white text-sm font-bold shadow-sm"
                            style={{ background: avatarColor }}
                          >
                            {user.username ? user.username.charAt(0).toUpperCase() : '?'}
                          </div>
                          {/* Name + email */}
                          <div className="flex-1 min-w-0">
                            <p className="text-sm font-semibold text-gray-900 truncate">{user.username}</p>
                            <p className="text-xs text-gray-400 truncate">{user.email}</p>
                          </div>
                          {/* Role badge */}
                          <span className="role-badge flex-shrink-0">{user.role?.replace('ROLE_', '')}</span>
                          {/* Actions */}
                          <div className="flex items-center gap-1.5 flex-shrink-0 ml-2">
                            <button
                              onClick={() => editTeamUser(user)}
                              className="action-btn action-btn-edit"
                            >
                              <i className="fas fa-pen text-[10px] mr-1"></i>Edit
                            </button>
                            <button
                              onClick={() => deleteTeamUser(user.id)}
                              className="action-btn action-btn-delete"
                            >
                              <i className="fas fa-trash text-[10px] mr-1"></i>Delete
                            </button>
                          </div>
                        </div>

                        {/* Card bottom — environments + tabs */}
                        <div className="px-4 py-2.5 bg-gray-50 flex flex-wrap items-center gap-x-6 gap-y-2">
                          {/* Environments */}
                          <div className="flex items-center gap-1.5 flex-wrap">
                            <span className="text-[10px] font-semibold text-gray-400 uppercase tracking-wider mr-0.5">Env</span>
                            {user.allAccountsAccess ? (
                              <span className="inline-flex items-center px-2 py-0.5 rounded text-[11px] font-medium bg-green-50 text-green-700 border border-green-200">
                                <i className="fas fa-globe mr-1 text-[9px]"></i>All accounts
                              </span>
                            ) : user.cloudAccounts && user.cloudAccounts.length > 0 ? (
                              user.cloudAccounts.map((account: CloudAccount) => (
                                <span
                                  key={account.id}
                                  className={`cloud-account-badge-small ${account.provider === 'AWS' ? 'cloud-account-aws' : account.provider === 'GCP' ? 'cloud-account-gcp' : 'cloud-account-azure'}`}
                                >
                                  <i className={account.provider === 'AWS' ? 'fab fa-aws' : account.provider === 'GCP' ? 'fab fa-google' : 'fab fa-microsoft'}></i>
                                  {account.accountName}
                                </span>
                              ))
                            ) : (
                              <span className="text-[11px] text-gray-400 italic">None</span>
                            )}
                          </div>

                          {/* Tab access */}
                          {profile.role === 'ROLE_BILLOPS_ADMIN' && (
                            <div className="flex items-center gap-1.5 flex-wrap">
                              <span className="text-[10px] font-semibold text-gray-400 uppercase tracking-wider mr-0.5">Tabs</span>
                              {mainTabs.length === 0 && ticketCats.length === 0 ? (
                                <span className="text-[11px] text-gray-400 italic">None</span>
                              ) : (
                                <>
                                  {mainTabs.map(tabKey => {
                                    const tab = BILLOPS_ADMIN_TABS.find(t => t.key === tabKey);
                                    const color = TAB_COLORS[tabKey] ?? 'bg-gray-50 text-gray-600 border-gray-300';
                                    return tab ? (
                                      <span key={tabKey} className={`inline-flex items-center px-2 py-0.5 rounded text-[11px] font-medium border ${color}`}>
                                        <i className={`${tab.icon} mr-1 text-[9px]`}></i>{tab.label}
                                      </span>
                                    ) : null;
                                  })}
                                  {ticketCats.map(catKey => {
                                    const cat = TICKET_CATEGORY_LABELS[catKey];
                                    return cat ? (
                                      <span key={catKey} className="inline-flex items-center px-2 py-0.5 rounded text-[11px] font-medium bg-amber-50 text-amber-700 border border-amber-200">
                                        <i className={`${cat.icon} mr-1 text-[9px]`}></i>{cat.label}
                                      </span>
                                    ) : null;
                                  })}
                                </>
                              )}
                            </div>
                          )}
                        </div>
                      </div>
                    );
                  })}
                </div>
              ) : (
                <div className="bg-white border border-dashed border-gray-200 rounded-md py-12 text-center">
                  <div className="w-12 h-12 rounded-md bg-gray-50 border border-gray-200 flex items-center justify-center mx-auto mb-3">
                    <i className="fas fa-users text-gray-300 text-lg"></i>
                  </div>
                  <p className="text-sm font-medium text-gray-400">No team members yet</p>
                  <p className="text-xs text-gray-300 mt-1">Click "Add Member" to get started</p>
                </div>
              )}
            </div>
          )}

          {/* Password Change Modal */}
          <DialogRoot isOpen={showPasswordModal} onClose={() => setShowPasswordModal(false)}>
            <DialogPanel
              title={<><i className="fas fa-shield-halved mr-3 text-indigo-600"></i>Security Authentication</>}
              onClose={() => setShowPasswordModal(false)}
              footer={
                <>
                  <button type="button" onClick={() => setShowPasswordModal(false)} className="btn-cancel">Cancel</button>
                  <button type="submit" form="password-change-form" className="btn-primary-gradient"><i className="fas fa-check mr-2"></i>Update Password</button>
                </>
              }
            >
                <form
                  id="password-change-form"
                  onSubmit={(e) => {
                    e.preventDefault();
                    changePassword();
                  }}
                >
                  <div className="modal-body">
                    <div className="form-group">
                      <label className="form-label">
                        <i className="fas fa-lock mr-2"></i>
                        Current Password
                      </label>
                      <input
                        type="password"
                        value={passwordForm.oldPassword}
                        onChange={(e) => updatePasswordForm('oldPassword', e.target.value)}
                        required
                        className="form-input"
                        placeholder="Enter current password"
                      />
                    </div>
                    <div className="form-group">
                      <label className="form-label">
                        <i className="fas fa-key mr-2"></i>
                        New Password
                      </label>
                      <input
                        type="password"
                        value={passwordForm.newPassword}
                        onChange={(e) => updatePasswordForm('newPassword', e.target.value)}
                        required
                        className="form-input"
                        placeholder="Enter new password (min 6 characters)"
                      />
                    </div>
                    <div className="form-group">
                      <label className="form-label">
                        <i className="fas fa-check-circle mr-2"></i>
                        Confirm New Password
                      </label>
                      <input
                        type="password"
                        value={passwordForm.confirmPassword}
                        onChange={(e) => updatePasswordForm('confirmPassword', e.target.value)}
                        required
                        className="form-input"
                        placeholder="Re-enter new password"
                      />
                    </div>
                  </div>
                </form>
            </DialogPanel>
          </DialogRoot>
        </div>
      )}
    </main>
  );
};

export default MyProfilePage;
