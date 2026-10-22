/**
 * My Profile Page - React Component
 * Converted from: my-profile.html
 */

import React, { useState, useEffect, useMemo } from 'react';
import { Icon } from '@iconify/react';
import { useMyProfile } from '../hooks/useMyProfile';
import { DialogRoot, DialogPanel } from '../components/ui';
import { TeamUser, CloudAccount } from '../types/myProfile';
import { XAMOPS_MODULES, normalizeAllowedTabs, selectAllXamTabs, toggleAllowedTab } from '../constants/xamopsTabs';
import { ALL_BILLOPS_ADMIN_TABS } from '../constants/billopsAdminTabs';
import XamTabPermissionToggle from '../components/Shared/XamTabPermissionToggle';

const PROVIDER_DOT: Record<string, string> = {
  AWS:   'bg-amber-400',
  GCP:   'bg-blue-400',
  Azure: 'bg-sky-500',
};

const CloudBadge: React.FC<{ account: CloudAccount }> = ({ account }) => (
  <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.7)] text-xs font-medium whitespace-nowrap max-w-[200px]">
    <span className={`w-1.5 h-1.5 rounded-full flex-shrink-0 ${PROVIDER_DOT[account.provider] ?? 'bg-[rgba(0,0,0,0.3)]'}`} />
    <span className="truncate">{account.accountName || account.accountId || `Account #${account.id}`}</span>
    {account.accountId && (
      <code className="text-[9px] font-mono text-[rgba(0,0,0,0.35)] flex-shrink-0">· {account.accountId}</code>
    )}
  </span>
);

const ProfileSkeleton: React.FC = () => (
  <div className="space-y-4">
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl overflow-hidden shadow-sm">
      <div className="px-6 py-5 border-b border-[rgba(0,0,0,0.06)] flex items-center gap-4">
        <div className="h-16 w-16 rounded-2xl bg-[rgba(0,0,0,0.06)] animate-pulse flex-shrink-0" />
        <div className="flex-1">
          <div className="h-5 w-40 bg-[rgba(0,0,0,0.06)] animate-pulse rounded mb-2" />
          <div className="h-4 w-56 bg-[rgba(0,0,0,0.06)] animate-pulse rounded" />
        </div>
      </div>
      <div className="p-5 grid grid-cols-2 gap-4">
        <div className="h-20 bg-[rgba(0,0,0,0.06)] animate-pulse rounded-xl" />
        <div className="h-20 bg-[rgba(0,0,0,0.06)] animate-pulse rounded-xl" />
      </div>
    </div>
  </div>
);

const MyProfilePage: React.FC = () => {
  const {
    profile, teamUsers, selectedUser, isEditing,
    showPasswordModal, passwordForm, loading, error,
    newTeamUser, editTeamUser, cancelEdit, saveTeamUser,
    deleteTeamUser, changePassword, updateSelectedUser,
    updatePasswordForm, toggleAccountId, toggleTab, setShowPasswordModal,
  } = useMyProfile();

  /**
   * The admin sections a sub-user can be granted, taken from the shared catalog rather than
   * listed here.
   *
   * The hand-maintained copy this replaces had drifted four sections behind the sidebar
   * (demo-users, historical-billing, client-intake, dspm-admin): they rendered in the nav and
   * were gated on keys that no picker could hand out, so a restricted sub-user could never be
   * given them.
   */
  const BILLOPS_ADMIN_TABS = ALL_BILLOPS_ADMIN_TABS;

  const TAB_COLORS: Record<string, string> = {
    'admin-dashboard':    'bg-purple-50 text-purple-700 border-purple-100',
    'support-inbox':      'bg-sky-50 text-sky-700 border-sky-100',
    'all-tickets':        'bg-blue-50 text-blue-700 border-blue-100',
    'all-invoices':       'bg-teal-50 text-teal-700 border-teal-100',
    'credit-requests':    'bg-emerald-50 text-emerald-700 border-emerald-100',
    'historical-billing': 'bg-cyan-50 text-cyan-700 border-cyan-100',
    'agreement-admin':    'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.6)] border-[rgba(0,0,0,0.08)]',
    'client-details':     'bg-indigo-50 text-indigo-700 border-indigo-100',
    'client-intake':      'bg-violet-50 text-violet-700 border-violet-100',
    'demo-users':         'bg-amber-50 text-amber-700 border-amber-100',
    'autospotting-admin': 'bg-orange-50 text-orange-700 border-orange-100',
    'dspm-admin':         'bg-fuchsia-50 text-fuchsia-700 border-fuchsia-100',
    'user-manager':       'bg-rose-50 text-rose-700 border-rose-100',
  };

  const [accountAccessMode, setAccountAccessMode] = useState<'all' | 'selected'>('selected');
  const [accountSearch, setAccountSearch] = useState('');
  const [accountProviderFilter, setAccountProviderFilter] = useState<'All' | 'AWS' | 'GCP' | 'Azure'>('All');

  useEffect(() => {
    if (isEditing) {
      setAccountAccessMode(selectedUser.allAccountsAccess ? 'all' : 'selected');
      setAccountSearch('');
      setAccountProviderFilter('All');
    }
  }, [isEditing]);

  const filteredAccounts = useMemo(() => {
    const accounts: CloudAccount[] = profile.cloudAccounts ?? [];
    return accounts.filter(a => {
      const matchProvider = accountProviderFilter === 'All' || a.provider === accountProviderFilter;
      const matchSearch = !accountSearch ||
        (a.accountName ?? '').toLowerCase().includes(accountSearch.toLowerCase()) ||
        (a.accountId ?? '').toLowerCase().includes(accountSearch.toLowerCase()) ||
        a.provider.toLowerCase().includes(accountSearch.toLowerCase());
      return matchProvider && matchSearch;
    });
  }, [profile.cloudAccounts, accountSearch, accountProviderFilter]);

  const handleAccountModeChange = (mode: 'all' | 'selected') => {
    setAccountAccessMode(mode);
    updateSelectedUser('allAccountsAccess', mode === 'all');
    if (mode === 'all') updateSelectedUser('accountIds', []);
  };

  const selectVisibleAccounts = () => {
    const merged = Array.from(new Set([...selectedUser.accountIds, ...filteredAccounts.map(a => a.id)]));
    updateSelectedUser('accountIds', merged);
  };

  const clearVisibleAccounts = () => {
    const visibleIds = new Set(filteredAccounts.map(a => a.id));
    updateSelectedUser('accountIds', selectedUser.accountIds.filter(id => !visibleIds.has(id)));
  };

  if (error) {
    return (
      <div className="flex items-center justify-center flex-1 p-10">
        <div className="bg-rose-50 border border-rose-100 rounded-xl p-6 max-w-md text-center">
          <Icon icon="mdi:alert-circle-outline" width={32} color="#F43F5E" className="mx-auto mb-3" />
          <h2 className="text-sm font-bold text-rose-700 mb-2">Access Denied</h2>
          <p className="text-sm text-rose-600 mb-4">{error}</p>
          <button onClick={() => window.location.reload()} className="px-4 py-2 bg-rose-500 hover:bg-rose-600 text-white rounded-md text-xs font-semibold transition-colors">
            Try Reauthenticating
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="px-6 pb-12 pt-6 space-y-5">
      {loading && !profile.username ? <ProfileSkeleton /> : (
        <>
          {/* ── Profile Card ───────────────────────────────── */}
          <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
            {/* Identity row */}
            <div className="px-6 py-5 flex items-center gap-5 border-b border-[rgba(0,0,0,0.06)]">
              <div className="w-14 h-14 rounded-xl flex items-center justify-center flex-shrink-0 bg-[rgba(0,0,0,0.88)]">
                <span className="text-xl font-bold text-white tracking-tight">
                  {profile.username ? profile.username.charAt(0).toUpperCase() : 'U'}
                </span>
              </div>
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 mb-1.5">
                  <h2 className="text-base font-black text-[rgba(0,0,0,0.88)] truncate">{profile.username || 'Loading...'}</h2>
                  {profile.isPrimaryUser && (
                    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-[10px] font-black uppercase tracking-wider border border-amber-200 bg-amber-50 text-amber-700 flex-shrink-0">
                      <Icon icon="mdi:crown" width={9} color="#D97706" />
                      ADMIN
                    </span>
                  )}
                </div>
                <div className="flex items-center gap-3 flex-wrap">
                  <span className="flex items-center gap-1.5 text-xs text-[rgba(0,0,0,0.5)]">
                    <Icon icon="mdi:email-outline" width={12} color="rgba(0,0,0,0.35)" />
                    {profile.email || ''}
                  </span>
                  <code className="px-2 py-0.5 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded text-[11px] font-mono text-[rgba(0,0,0,0.5)]">
                    {profile.role || ''}
                  </code>
                </div>
              </div>
              <button
                onClick={() => setShowPasswordModal(true)}
                className="hidden sm:flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold text-[rgba(0,0,0,0.6)] bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] hover:bg-[rgba(0,0,0,0.07)] transition-colors flex-shrink-0"
              >
                <Icon icon="mdi:lock-outline" width={13} />
                Security Settings
              </button>
            </div>

            {/* Info + Environments body */}
            <div className="px-6 py-5 space-y-5">
              {/* Org + Account type */}
              <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                <div className="p-4 bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.07)] rounded-xl group hover:border-indigo-200 hover:bg-indigo-50/20 transition-colors">
                  <p className="flex items-center gap-1.5 text-[10px] font-bold text-[rgba(0,0,0,0.4)] uppercase tracking-widest mb-1.5">
                    <Icon icon="mdi:bank-outline" width={11} />
                    Organization
                  </p>
                  <p className="text-sm font-bold text-[rgba(0,0,0,0.88)]">{profile.clientName || 'N/A'}</p>
                </div>
                <div className="p-4 bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.07)] rounded-xl group hover:border-indigo-200 hover:bg-indigo-50/20 transition-colors">
                  <p className="flex items-center gap-1.5 text-[10px] font-bold text-[rgba(0,0,0,0.4)] uppercase tracking-widest mb-1.5">
                    <Icon icon="mdi:fingerprint" width={11} />
                    Account Type
                  </p>
                  <p className="text-sm font-bold text-indigo-600">
                    {profile.isPrimaryUser ? 'Primary Administrator' : 'Standard Team Member'}
                  </p>
                </div>
              </div>

              {/* Authorized Cloud Environments */}
              <div>
                <p className="flex items-center gap-1.5 text-[10px] font-bold text-[rgba(0,0,0,0.4)] uppercase tracking-widest mb-3">
                  <Icon icon="mdi:server-network-outline" width={11} />
                  Authorized Cloud Environments
                  {profile.cloudAccounts?.length > 0 && (
                    <span className="ml-1 px-1.5 py-0.5 bg-[rgba(0,0,0,0.06)] rounded-full text-[9px] font-bold text-[rgba(0,0,0,0.4)]">
                      {profile.cloudAccounts.length}
                    </span>
                  )}
                </p>
                {profile.cloudAccounts && profile.cloudAccounts.length > 0 ? (
                  <div className="flex flex-wrap gap-2">
                    {profile.cloudAccounts.map((account: CloudAccount) => (
                      <CloudBadge key={account.id} account={account} />
                    ))}
                  </div>
                ) : (
                  <div className="flex items-center gap-2 p-4 bg-[rgba(0,0,0,0.02)] border border-dashed border-[rgba(0,0,0,0.1)] rounded-xl text-[rgba(0,0,0,0.35)] text-xs">
                    <Icon icon="mdi:cloud-off-outline" width={16} />
                    No cloud environments linked to this profile
                  </div>
                )}
              </div>

              {/* Mobile security button */}
              <div className="sm:hidden pt-2 border-t border-[rgba(0,0,0,0.06)]">
                <button
                  onClick={() => setShowPasswordModal(true)}
                  className="flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold text-[rgba(0,0,0,0.6)] bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] hover:bg-[rgba(0,0,0,0.07)] transition-colors"
                >
                  <Icon icon="mdi:lock-outline" width={13} />
                  Security & Password Settings
                </button>
              </div>
            </div>
          </div>

          {/* ── Team Management (Primary Users only) ───────── */}
          {profile.isPrimaryUser && (
            <div className="space-y-3">
              {/* Section header */}
              <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl px-5 py-4 flex items-center justify-between shadow-sm">
                <div className="flex items-center gap-3">
                  <div className="w-9 h-9 rounded-lg bg-indigo-50 border border-indigo-100 flex items-center justify-center flex-shrink-0">
                    <Icon icon="mdi:account-group-outline" width={17} color="#4F46E5" />
                  </div>
                  <div>
                    <h2 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">Team Management</h2>
                    <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-0.5">Manage access and permissions for your team.</p>
                  </div>
                </div>
                <button
                  onClick={newTeamUser}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] hover:bg-black text-white text-xs font-semibold rounded-lg transition-colors"
                >
                  <Icon icon="mdi:plus" width={14} />
                  Add Member
                </button>
              </div>

              {/* Edit form */}
              {isEditing && (
                <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
                  <div className="px-6 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center gap-2">
                    <Icon icon="mdi:account-plus-outline" width={14} color="#4F46E5" />
                    <h3 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">
                      {selectedUser.id ? 'Modify Permissions' : 'Onboard Team Member'}
                    </h3>
                  </div>
                  <form onSubmit={(e) => { e.preventDefault(); saveTeamUser(); }} className="p-6">
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      {[
                        { key: 'username',  label: 'Username', icon: 'mdi:account-outline',  type: 'text',     placeholder: 'Enter username', required: true },
                        { key: 'email',     label: 'Email',    icon: 'mdi:email-outline',      type: 'email',    placeholder: 'Enter email', required: true },
                        { key: 'password',  label: 'Password', icon: 'mdi:lock-outline',       type: 'password', placeholder: selectedUser.id ? 'Leave blank to keep current' : 'Enter password', required: !selectedUser.id },
                      ].map(field => (
                        <div key={field.key}>
                          <label className="flex items-center gap-1.5 text-[10px] font-bold text-[rgba(0,0,0,0.45)] uppercase tracking-wider mb-1.5">
                            <Icon icon={field.icon} width={11} />
                            {field.label}
                          </label>
                          <input
                            type={field.type}
                            value={(selectedUser as any)[field.key]}
                            onChange={(e) => updateSelectedUser(field.key as any, e.target.value)}
                            required={field.required}
                            placeholder={field.placeholder}
                            className="w-full px-3 py-2 text-xs bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] rounded-lg text-[rgba(0,0,0,0.88)] placeholder-[rgba(0,0,0,0.3)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)] focus:border-[rgba(0,0,0,0.2)] transition-colors"
                          />
                        </div>
                      ))}
                      {/* Role */}
                      <div>
                        <label className="flex items-center gap-1.5 text-[10px] font-bold text-[rgba(0,0,0,0.45)] uppercase tracking-wider mb-1.5">
                          <Icon icon="mdi:account-tag-outline" width={11} />
                          Role
                        </label>
                        {profile.role === 'ROLE_BILLOPS_ADMIN' ? (
                          <>
                            <select
                              value={selectedUser.role || profile.role}
                              onChange={(e) => updateSelectedUser('role', e.target.value)}
                              className="w-full px-3 py-2 text-xs bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] rounded-lg text-[rgba(0,0,0,0.88)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)] transition-colors"
                            >
                              <option value="ROLE_BILLOPS_ADMIN">BILLOPS ADMIN</option>
                              <option value="ROLE_BILLOPS_ADMIN_SUB">BILLOPS ADMIN SUB</option>
                            </select>
                            {(selectedUser.role || profile.role) === 'ROLE_BILLOPS_ADMIN_SUB' && (
                              <p className="mt-1.5 text-[11px] text-amber-600 flex items-center gap-1">
                                <Icon icon="mdi:information-outline" width={11} />
                                Sub-admin sees only assigned clients.
                              </p>
                            )}
                          </>
                        ) : (
                          <input type="text" value={profile.role} disabled className="w-full px-3 py-2 text-xs bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.06)] rounded-lg text-[rgba(0,0,0,0.35)] cursor-not-allowed" />
                        )}
                      </div>
                    </div>

                    {/* Cloud Account Access */}
                    {(selectedUser.role || profile.role) !== 'ROLE_BILLOPS_ADMIN_SUB' && profile.cloudAccounts && profile.cloudAccounts.length > 0 && (
                      <div className="mt-6">
                        <p className="flex items-center gap-1.5 text-[10px] font-bold text-[rgba(0,0,0,0.45)] uppercase tracking-wider mb-3">
                          <Icon icon="mdi:cloud-outline" width={11} />
                          Cloud Account Access
                        </p>
                        <div className="grid grid-cols-2 gap-3 mb-4">
                          {(['all', 'selected'] as const).map(mode => (
                            <button key={mode} type="button" onClick={() => handleAccountModeChange(mode)}
                              className={`flex items-center gap-3 p-3 rounded-xl border transition-all text-left ${accountAccessMode === mode ? 'border-[rgba(0,0,0,0.3)] bg-[rgba(0,0,0,0.03)]' : 'border-[rgba(0,0,0,0.08)] bg-white hover:border-[rgba(0,0,0,0.15)]'}`}>
                              <div className={`w-4 h-4 rounded-full border-2 flex items-center justify-center flex-shrink-0 ${accountAccessMode === mode ? 'border-[rgba(0,0,0,0.88)]' : 'border-[rgba(0,0,0,0.2)]'}`}>
                                {accountAccessMode === mode && <div className="w-2 h-2 rounded-full bg-[rgba(0,0,0,0.88)]" />}
                              </div>
                              <div>
                                <p className="text-xs font-bold text-[rgba(0,0,0,0.88)]">{mode === 'all' ? 'All Accounts' : 'Selected Accounts'}</p>
                                <p className="text-[11px] text-[rgba(0,0,0,0.4)]">{mode === 'all' ? 'Includes future accounts' : accountAccessMode === 'selected' ? `${selectedUser.accountIds.length} selected` : 'Choose specific accounts'}</p>
                              </div>
                            </button>
                          ))}
                        </div>
                        {accountAccessMode === 'selected' && (
                          <div className="border border-[rgba(0,0,0,0.1)] rounded-xl overflow-hidden">
                            <div className="p-3 border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.02)]">
                              <div className="relative">
                                <div className="absolute inset-y-0 left-3 flex items-center pointer-events-none"><Icon icon="mdi:magnify" width={13} color="rgba(0,0,0,0.35)" /></div>
                                <input type="text" value={accountSearch} onChange={e => setAccountSearch(e.target.value)} placeholder="Search accounts..." className="w-full pl-8 pr-3 py-2 text-xs bg-white border border-[rgba(0,0,0,0.08)] rounded-lg text-[rgba(0,0,0,0.88)] placeholder-[rgba(0,0,0,0.3)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)]" />
                              </div>
                            </div>
                            <div className="px-3 py-2 border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.02)] flex items-center justify-between flex-wrap gap-2">
                              <div className="flex items-center gap-1.5">
                                {(['All', 'AWS', 'GCP', 'Azure'] as const).map(p => (
                                  <button key={p} type="button" onClick={() => setAccountProviderFilter(p)}
                                    className={`inline-flex items-center gap-1 px-2.5 py-1 rounded-lg text-[11px] font-semibold transition-colors ${accountProviderFilter === p ? 'bg-[rgba(0,0,0,0.88)] text-white' : 'bg-white border border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.6)] hover:border-[rgba(0,0,0,0.2)]'}`}>
                                    {p !== 'All' && <span className={`w-1.5 h-1.5 rounded-full flex-shrink-0 ${accountProviderFilter === p ? 'bg-white' : (PROVIDER_DOT[p] ?? 'bg-[rgba(0,0,0,0.3)]')}`} />}
                                    {p}
                                  </button>
                                ))}
                              </div>
                              <div className="flex items-center gap-3 text-[11px]">
                                <button type="button" onClick={selectVisibleAccounts} className="font-semibold text-[rgba(0,0,0,0.88)] hover:text-black flex items-center gap-1"><Icon icon="mdi:check-all" width={12} />Select all</button>
                                <span className="text-[rgba(0,0,0,0.15)]">|</span>
                                <button type="button" onClick={clearVisibleAccounts} className="font-semibold text-rose-500 hover:text-rose-700 flex items-center gap-1"><Icon icon="mdi:close" width={11} />Clear</button>
                                <span className="text-[rgba(0,0,0,0.15)]">|</span>
                                <span className="font-bold text-[rgba(0,0,0,0.88)]">{selectedUser.accountIds.length} selected</span>
                              </div>
                            </div>
                            <div className="max-h-56 overflow-y-auto divide-y divide-[rgba(0,0,0,0.04)]">
                              {filteredAccounts.length === 0 ? (
                                <div className="py-6 text-center text-xs text-[rgba(0,0,0,0.4)]">No accounts match your search</div>
                              ) : filteredAccounts.map((account: CloudAccount) => (
                                <label key={account.id} className={`flex items-center gap-3 px-4 py-3 cursor-pointer transition-colors hover:bg-[rgba(0,0,0,0.025)] ${selectedUser.accountIds.includes(account.id) ? 'bg-[rgba(0,0,0,0.02)]' : ''}`}>
                                  <input type="checkbox" checked={selectedUser.accountIds.includes(account.id)} onChange={() => toggleAccountId(account.id)} className="w-4 h-4 rounded accent-[rgba(0,0,0,0.88)] cursor-pointer flex-shrink-0" />
                                  <span className={`w-2 h-2 rounded-full flex-shrink-0 ${PROVIDER_DOT[account.provider] ?? 'bg-[rgba(0,0,0,0.3)]'}`} />
                                  <div className="flex-1 min-w-0">
                                    <p className="text-xs font-semibold text-[rgba(0,0,0,0.88)] truncate">{account.accountName || account.accountId || `Account #${account.id}`}</p>
                                    {account.accountId && (
                                      <p className="text-[10px] font-mono text-[rgba(0,0,0,0.4)] truncate mt-0.5">{account.accountId}</p>
                                    )}
                                  </div>
                                  <span className={`text-[10px] font-bold px-1.5 py-0.5 rounded-md flex-shrink-0 ${account.provider === 'AWS' ? 'bg-amber-50 text-amber-600' : account.provider === 'GCP' ? 'bg-blue-50 text-blue-600' : 'bg-sky-50 text-sky-600'}`}>{account.provider}</span>
                                </label>
                              ))}
                            </div>
                          </div>
                        )}
                      </div>
                    )}

                    {/* Tab Access (BILLOPS only) */}
                    {profile.role === 'ROLE_BILLOPS_ADMIN' && (
                      <div className="mt-6">
                        <p className="flex items-center gap-1.5 text-[10px] font-bold text-[rgba(0,0,0,0.45)] uppercase tracking-wider mb-3">
                          <Icon icon="mdi:view-grid-outline" width={11} />
                          Tab Access
                        </p>
                        <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
                          {BILLOPS_ADMIN_TABS.map(tab => (
                            <label key={tab.key} className="flex items-center gap-3 p-3 border border-[rgba(0,0,0,0.08)] rounded-xl cursor-pointer hover:bg-[rgba(0,0,0,0.025)] transition-colors bg-white">
                              <input type="checkbox" checked={selectedUser.allowedTabs.includes(tab.key)} onChange={() => toggleTab(tab.key)} className="w-4 h-4 rounded accent-[rgba(0,0,0,0.88)] cursor-pointer flex-shrink-0" />
                              <Icon icon={tab.icon} width={14} color="rgba(99,102,241,0.7)" />
                              <span className="text-xs font-medium text-[rgba(0,0,0,0.7)]">{tab.label}</span>
                            </label>
                          ))}
                        </div>
                        {selectedUser.allowedTabs.includes('all-tickets') && (
                          <div className="mt-3 ml-4 p-4 bg-[rgba(0,0,0,0.02)] rounded-xl border border-[rgba(0,0,0,0.08)]">
                            <p className="text-[10px] font-bold text-[rgba(0,0,0,0.5)] uppercase tracking-widest mb-2 flex items-center gap-1.5"><Icon icon="mdi:filter-outline" width={11} />Ticket Category Access</p>
                            <div className="grid grid-cols-1 md:grid-cols-3 gap-2">
                              {[
                                { key: 'tickets-account-billing', label: 'Account & Billing', icon: 'mdi:file-chart-outline' },
                                { key: 'tickets-technical', label: 'Technical Support', icon: 'mdi:server-outline' },
                                { key: 'tickets-all', label: 'All Categories', icon: 'mdi:format-list-bulleted' },
                              ].map(cat => (
                                <label key={cat.key} className="flex items-center gap-2 px-3 py-2 bg-white rounded-lg border border-[rgba(0,0,0,0.08)] cursor-pointer hover:border-[rgba(0,0,0,0.15)] transition-colors">
                                  <input type="checkbox" checked={selectedUser.allowedTabs.includes(cat.key)} onChange={() => toggleTab(cat.key)} className="w-4 h-4 rounded accent-[rgba(0,0,0,0.88)] cursor-pointer flex-shrink-0" />
                                  <Icon icon={cat.icon} width={12} color="rgba(99,102,241,0.6)" />
                                  <span className="text-[11px] font-medium text-[rgba(0,0,0,0.7)]">{cat.label}</span>
                                </label>
                              ))}
                            </div>
                          </div>
                        )}
                      </div>
                    )}

                    {/* Sidebar Module & Tab Access (XAMOPS only) */}
                    {profile.role === 'ROLE_XAMOPS' && (
                      <div className="mt-6">
                        <div className="flex items-center justify-between mb-2">
                          <p className="flex items-center gap-1.5 text-[10px] font-bold text-[rgba(0,0,0,0.45)] uppercase tracking-wider">
                            <Icon icon="mdi:view-grid-outline" width={11} />
                            Sidebar Module &amp; Tab Access
                          </p>
                          <div className="flex items-center gap-2">
                            <button type="button" onClick={() => updateSelectedUser('allowedTabs', selectAllXamTabs(selectedUser.allowedTabs))} className="text-[10px] font-semibold text-[rgba(0,0,0,0.6)] hover:text-black">Select all</button>
                            <span className="text-[rgba(0,0,0,0.15)]">|</span>
                            <button type="button" onClick={() => updateSelectedUser('allowedTabs', [])} className="text-[10px] font-semibold text-rose-500 hover:text-rose-700">Clear</button>
                          </div>
                        </div>
                        <p className="text-[11px] text-[rgba(0,0,0,0.4)] mb-3">
                          Leave everything unchecked to grant full sidebar access. Selecting specific tabs restricts this member to only those.
                        </p>
                        <div className="space-y-3">
                          {XAMOPS_MODULES.map(mod => {
                            const moduleTabKeys = mod.tabs.map(t => t.key);
                            const allChecked = moduleTabKeys.every(k => selectedUser.allowedTabs.includes(k));
                            // Every change goes through normalizeAllowedTabs, so unchecking a tab or its
                            // module also removes a permission nested under it (D2).
                            const toggleModule = () => {
                              if (allChecked) {
                                updateSelectedUser('allowedTabs', normalizeAllowedTabs(selectedUser.allowedTabs.filter(k => !moduleTabKeys.includes(k))));
                              } else {
                                updateSelectedUser('allowedTabs', normalizeAllowedTabs(Array.from(new Set([...selectedUser.allowedTabs, ...moduleTabKeys]))));
                              }
                            };
                            return (
                              <div key={mod.key} className="border border-[rgba(0,0,0,0.08)] rounded-xl overflow-hidden">
                                <label className="flex items-center gap-2.5 px-3 py-2.5 bg-[rgba(0,0,0,0.025)] cursor-pointer">
                                  <input type="checkbox" checked={allChecked} onChange={toggleModule} className="w-4 h-4 rounded accent-[rgba(0,0,0,0.88)] cursor-pointer flex-shrink-0" />
                                  <Icon icon={mod.icon} width={14} color="rgba(0,0,0,0.6)" />
                                  <span className="text-xs font-bold text-[rgba(0,0,0,0.8)]">{mod.label}</span>
                                </label>
                                <div className="grid grid-cols-1 md:grid-cols-2 gap-1.5 p-2.5">
                                  {mod.tabs.map(tab => (
                                    <div key={tab.key} className="flex flex-col gap-1">
                                      <label className="flex items-center gap-2 px-2.5 py-2 border border-[rgba(0,0,0,0.06)] rounded-lg cursor-pointer hover:bg-[rgba(0,0,0,0.02)] transition-colors">
                                        <input type="checkbox" checked={selectedUser.allowedTabs.includes(tab.key)} onChange={() => updateSelectedUser('allowedTabs', toggleAllowedTab(selectedUser.allowedTabs, tab.key))} className="w-3.5 h-3.5 rounded accent-[rgba(0,0,0,0.88)] cursor-pointer flex-shrink-0" />
                                        <Icon icon={tab.icon} width={13} color="rgba(0,0,0,0.45)" />
                                        <span className="text-[11px] font-medium text-[rgba(0,0,0,0.7)]">{tab.label}</span>
                                      </label>
                                      <XamTabPermissionToggle
                                        parentKey={tab.key}
                                        allowedTabs={selectedUser.allowedTabs}
                                        onChange={next => updateSelectedUser('allowedTabs', next)}
                                      />
                                    </div>
                                  ))}
                                </div>
                              </div>
                            );
                          })}
                        </div>
                      </div>
                    )}

                    <div className="mt-6 pt-4 border-t border-[rgba(0,0,0,0.06)] flex justify-end gap-3">
                      <button type="button" onClick={cancelEdit} className="inline-flex items-center gap-2 px-4 py-2 border border-[rgba(0,0,0,0.12)] rounded-lg bg-white text-[rgba(0,0,0,0.5)] text-xs font-semibold hover:bg-[rgba(0,0,0,0.03)] transition-colors">
                        <Icon icon="mdi:close" width={13} />Cancel
                      </button>
                      <button type="submit" className="inline-flex items-center gap-2 px-4 py-2 bg-[rgba(0,0,0,0.88)] hover:bg-black text-white rounded-lg text-xs font-semibold transition-colors">
                        <Icon icon="mdi:check" width={13} />Save Member
                      </button>
                    </div>
                  </form>
                </div>
              )}

              {/* Stats bar */}
              {teamUsers.length > 0 && (
                <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden grid grid-cols-3 divide-x divide-[rgba(0,0,0,0.06)]">
                  {[
                    { icon: 'mdi:account-group-outline', color: '#4F46E5', bg: 'bg-indigo-50 border-indigo-100',
                      value: teamUsers.length, label: 'Members' },
                    { icon: 'mdi:cloud-outline', color: '#10B981', bg: 'bg-emerald-50 border-emerald-100',
                      value: teamUsers.filter(u => u.allAccountsAccess).length > 0
                        ? `${teamUsers.filter(u => u.allAccountsAccess).length} all`
                        : teamUsers.reduce((s, u) => s + (u.cloudAccounts?.length ?? 0), 0),
                      label: 'Env. grants' },
                    { icon: 'mdi:view-grid-outline', color: '#7C3AED', bg: 'bg-purple-50 border-purple-100',
                      value: teamUsers.reduce((s, u) => s + (u.allowedTabs?.filter(t => !t.startsWith('tickets-')).length ?? 0), 0),
                      label: 'Tab grants' },
                  ].map((stat, i) => (
                    <div key={i} className="px-5 py-4 flex items-center gap-3">
                      <div className={`w-9 h-9 rounded-xl ${stat.bg} border flex items-center justify-center flex-shrink-0`}>
                        <Icon icon={stat.icon} width={16} color={stat.color} />
                      </div>
                      <div>
                        <p style={{ fontFamily: "'Bebas Neue','Barlow Condensed',sans-serif", fontSize: '24px', fontWeight: 400, lineHeight: '24px', color: 'rgb(10,10,10)' }}>{stat.value}</p>
                        <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-0.5">{stat.label}</p>
                      </div>
                    </div>
                  ))}
                </div>
              )}

              {/* Member cards */}
              {teamUsers.length > 0 ? (
                <div className="space-y-2">
                  {teamUsers.map((user: TeamUser) => {
                    const TICKET_LABELS: Record<string, { label: string; icon: string }> = {
                      'tickets-account-billing': { label: 'Account & Billing', icon: 'mdi:file-chart-outline' },
                      'tickets-technical':       { label: 'Technical Support', icon: 'mdi:server-outline' },
                      'tickets-all':             { label: 'All Categories', icon: 'mdi:format-list-bulleted' },
                    };
                    const mainTabs = (user.allowedTabs ?? []).filter(k => !k.startsWith('tickets-'));
                    const ticketCats = (user.allowedTabs ?? []).filter(k => k.startsWith('tickets-'));
                    return (
                      <div key={user.id} className="bg-white border border-[rgba(0,0,0,0.1)] rounded-xl shadow-sm overflow-hidden hover:border-[rgba(0,0,0,0.18)] transition-colors">
                        {/* Top row */}
                        <div className="px-4 py-3 flex items-center gap-3">
                          <div className="w-9 h-9 rounded-xl flex items-center justify-center flex-shrink-0 bg-[rgba(0,0,0,0.88)] text-white text-sm font-bold">
                            {user.username ? user.username.charAt(0).toUpperCase() : '?'}
                          </div>
                          <div className="flex-1 min-w-0">
                            <p className="text-sm font-bold text-[rgba(0,0,0,0.88)] truncate leading-tight">{user.username}</p>
                            <p className="text-[11px] text-[rgba(0,0,0,0.4)] truncate">{user.email}</p>
                          </div>
                          <span className="px-2 py-0.5 bg-indigo-50 border border-indigo-100 text-indigo-700 text-[10px] font-black uppercase tracking-wider rounded-md flex-shrink-0">
                            {user.role?.replace('ROLE_', '')}
                          </span>
                          <div className="flex items-center gap-1.5 flex-shrink-0 ml-1">
                            <button onClick={() => editTeamUser(user)} className="inline-flex items-center gap-1 px-2.5 py-1.5 text-[11px] font-semibold bg-blue-50 border border-blue-100 text-blue-700 hover:bg-blue-100 rounded-lg transition-colors">
                              <Icon icon="mdi:pencil-outline" width={11} />Edit
                            </button>
                            <button onClick={() => deleteTeamUser(user.id)} className="inline-flex items-center gap-1 px-2.5 py-1.5 text-[11px] font-semibold bg-rose-50 border border-rose-100 text-rose-600 hover:bg-rose-100 rounded-lg transition-colors">
                              <Icon icon="mdi:trash-can-outline" width={11} />Delete
                            </button>
                          </div>
                        </div>

                        {/* Bottom env row */}
                        <div className="px-4 py-2.5 bg-[rgba(0,0,0,0.02)] border-t border-[rgba(0,0,0,0.05)] flex flex-wrap items-center gap-x-5 gap-y-2">
                          <div className="flex items-center gap-1.5 flex-wrap">
                            <span className="text-[10px] font-bold text-[rgba(0,0,0,0.3)] uppercase tracking-wider">Env</span>
                            {user.allAccountsAccess ? (
                              <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-[11px] font-semibold bg-emerald-50 border border-emerald-100 text-emerald-700">
                                <Icon icon="mdi:earth" width={10} />All accounts
                              </span>
                            ) : user.cloudAccounts && user.cloudAccounts.length > 0 ? (
                              user.cloudAccounts.map((a: CloudAccount) => (
                                <CloudBadge key={a.id} account={a} />
                              ))
                            ) : (
                              <span className="text-[11px] text-[rgba(0,0,0,0.3)] italic">None</span>
                            )}
                          </div>

                          {profile.role === 'ROLE_BILLOPS_ADMIN' && (mainTabs.length > 0 || ticketCats.length > 0) && (
                            <div className="flex items-center gap-1.5 flex-wrap">
                              <span className="text-[10px] font-bold text-[rgba(0,0,0,0.3)] uppercase tracking-wider">Tabs</span>
                              {mainTabs.map(tabKey => {
                                const tab = BILLOPS_ADMIN_TABS.find(t => t.key === tabKey);
                                const color = TAB_COLORS[tabKey] ?? 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.6)] border-[rgba(0,0,0,0.08)]';
                                return tab ? (
                                  <span key={tabKey} className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-[10px] font-semibold border ${color}`}>
                                    <Icon icon={tab.icon} width={9} />{tab.label}
                                  </span>
                                ) : null;
                              })}
                              {ticketCats.map(k => {
                                const cat = TICKET_LABELS[k];
                                return cat ? (
                                  <span key={k} className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-[10px] font-semibold border bg-amber-50 border-amber-100 text-amber-700">
                                    <Icon icon={cat.icon} width={9} />{cat.label}
                                  </span>
                                ) : null;
                              })}
                            </div>
                          )}
                        </div>
                      </div>
                    );
                  })}
                </div>
              ) : (
                <div className="bg-white border border-dashed border-[rgba(0,0,0,0.1)] rounded-xl py-14 text-center">
                  <div className="w-14 h-14 rounded-2xl bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] flex items-center justify-center mx-auto mb-3">
                    <Icon icon="mdi:account-group-outline" width={28} color="rgba(0,0,0,0.22)" />
                  </div>
                  <p className="text-sm font-bold text-[rgba(0,0,0,0.4)]">No team members yet</p>
                  <p className="text-xs text-[rgba(0,0,0,0.3)] mt-1">Click "Add Member" to get started</p>
                </div>
              )}
            </div>
          )}

          {/* ── Password Modal ─────────────────────────────── */}
          <DialogRoot isOpen={showPasswordModal} onClose={() => setShowPasswordModal(false)}>
            <DialogPanel
              title={<span className="flex items-center gap-2"><Icon icon="mdi:shield-lock-outline" width={16} color="#4F46E5" />Security Authentication</span>}
              onClose={() => setShowPasswordModal(false)}
              footer={
                <>
                  <button type="button" onClick={() => setShowPasswordModal(false)} className="inline-flex items-center gap-2 px-4 py-2 border border-[rgba(0,0,0,0.12)] rounded-lg bg-white text-[rgba(0,0,0,0.5)] text-xs font-semibold hover:bg-[rgba(0,0,0,0.03)] transition-colors">
                    Cancel
                  </button>
                  <button type="submit" form="password-change-form" className="inline-flex items-center gap-2 px-4 py-2 bg-[rgba(0,0,0,0.88)] hover:bg-black text-white rounded-lg text-xs font-semibold transition-colors">
                    <Icon icon="mdi:check" width={13} />Update Password
                  </button>
                </>
              }
            >
              <form id="password-change-form" onSubmit={(e) => { e.preventDefault(); changePassword(); }}>
                <div className="space-y-4 p-4">
                  {[
                    { key: 'oldPassword',     label: 'Current Password',     icon: 'mdi:lock-outline',         placeholder: 'Enter current password' },
                    { key: 'newPassword',     label: 'New Password',         icon: 'mdi:key-outline',           placeholder: 'Min 6 characters' },
                    { key: 'confirmPassword', label: 'Confirm New Password', icon: 'mdi:check-circle-outline',  placeholder: 'Re-enter new password' },
                  ].map(f => (
                    <div key={f.key}>
                      <label className="flex items-center gap-1.5 text-[10px] font-bold text-[rgba(0,0,0,0.45)] uppercase tracking-wider mb-1.5">
                        <Icon icon={f.icon} width={11} />{f.label}
                      </label>
                      <input
                        type="password"
                        value={passwordForm[f.key as keyof typeof passwordForm]}
                        onChange={(e) => updatePasswordForm(f.key as keyof typeof passwordForm, e.target.value)}
                        required
                        placeholder={f.placeholder}
                        className="w-full px-3 py-2 text-xs bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] rounded-lg text-[rgba(0,0,0,0.88)] placeholder-[rgba(0,0,0,0.3)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)] transition-colors"
                      />
                    </div>
                  ))}
                </div>
              </form>
            </DialogPanel>
          </DialogRoot>
        </>
      )}
    </div>
  );
};

export default MyProfilePage;
