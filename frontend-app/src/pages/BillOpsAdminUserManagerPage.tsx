import React, { useEffect, useState, useMemo } from 'react';
import ReactDOM from 'react-dom';
import { Icon } from '@iconify/react';
import { useTopbar } from '../contexts/TopbarContext';
import { useBillOpsUserManager } from '../hooks/useBillOpsUserManager';
import type { BillOpsUser, BillOpsRole } from '../types/billopsUserManager';
import NotificationModal from '../components/Shared/NotificationModal';
import { AlertDialog } from '../components/ui';
import { XAMOPS_MODULES, ALL_XAMOPS_TAB_KEYS } from '../constants/xamopsTabs';

const PAGE_SIZE = 10;

const ROLE_META: Record<string, { label: string; icon: string; badge: string }> = {
  ROLE_BILLOPS_ADMIN:     { label: 'BILLOPS ADMIN',     icon: 'mdi:shield-crown-outline',  badge: 'bg-amber-50 border-amber-200 text-amber-700' },
  ROLE_BILLOPS_ADMIN_SUB: { label: 'ADMIN SUB',         icon: 'mdi:shield-account-outline', badge: 'bg-orange-50 border-orange-200 text-orange-700' },
  ROLE_BILLOPS:           { label: 'BILLOPS',            icon: 'mdi:account-outline',        badge: 'bg-[rgba(0,0,0,0.04)] border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.6)]' },
  ROLE_XAMOPS:            { label: 'XAMOPS',             icon: 'mdi:cloud-outline',          badge: 'bg-sky-50 border-sky-200 text-sky-700' },
};

const getRoleMeta = (role: string) =>
  ROLE_META[role] ?? { label: role.replace('ROLE_', ''), icon: 'mdi:account-outline', badge: 'bg-[rgba(0,0,0,0.04)] border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.6)]' };

const formatRole = (role: string) => role.replace('ROLE_', '').replace(/_/g, ' ');

const getInitials = (name: string) => (name || '?').substring(0, 2).toUpperCase();

/* ── Inline label ── */
const FormLabel: React.FC<{ icon: string; text: string }> = ({ icon, text }) => (
  <label className="flex items-center gap-1.5 text-[10px] font-semibold text-[rgba(0,0,0,0.5)] uppercase tracking-wider mb-1.5">
    <Icon icon={icon} width={11} />
    {text}
  </label>
);

/* ── Field input ── */
const FieldInput: React.FC<React.InputHTMLAttributes<HTMLInputElement>> = (props) => (
  <input
    {...props}
    className="w-full px-3 py-2 text-xs bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] rounded-lg text-[rgba(0,0,0,0.88)] placeholder-[rgba(0,0,0,0.3)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)] focus:border-[rgba(0,0,0,0.2)] disabled:opacity-60 transition-colors"
  />
);

/* ── Field select ── */
const FieldSelect: React.FC<React.SelectHTMLAttributes<HTMLSelectElement>> = ({ children, ...props }) => (
  <select
    {...props}
    className="w-full px-3 py-2 text-xs bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] rounded-lg text-[rgba(0,0,0,0.88)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)] focus:border-[rgba(0,0,0,0.2)] transition-colors"
  >
    {children}
  </select>
);

const BillOpsAdminUserManagerPage: React.FC = () => {
  const {
    users, loading, error, isEditing, selectedUser, roles,
    newUser, editUser, cancelEdit, saveUser, deleteUser, updateSelectedUser, toggleTab,
    notification, clearNotification, pendingDeleteId, confirmDelete, cancelDelete,
  } = useBillOpsUserManager();

  const [searchQuery, setSearchQuery]     = useState('');
  const [filterRole, setFilterRole]       = useState<BillOpsRole | ''>('');
  const [sortBy, setSortBy]               = useState<'name-asc' | 'name-desc' | 'count-desc'>('name-asc');
  const [expandedClients, setExpandedClients]           = useState<Set<string>>(new Set());
  const [expandedPrimaryUsers, setExpandedPrimaryUsers] = useState<Set<string>>(new Set());
  const [currentPage, setCurrentPage]    = useState(1);

  const toggleClient      = (k: string) => setExpandedClients(prev      => { const n = new Set(prev); n.has(k) ? n.delete(k) : n.add(k); return n; });
  const togglePrimaryUser = (k: string) => setExpandedPrimaryUsers(prev => { const n = new Set(prev); n.has(k) ? n.delete(k) : n.add(k); return n; });

  const handleNewUserForClient = (clientName: string) => { newUser(); updateSelectedUser('clientName', clientName); };
  const handleNewSubUser = (p: BillOpsUser) => { newUser(); updateSelectedUser('clientName', p.clientName); updateSelectedUser('parentId', p.id); };

  /* ── Build 3-level tree ── */
  const clientGroups = useMemo(() => {
    const q = searchQuery.toLowerCase().trim();
    const match = (u: BillOpsUser) => !q || [u.clientName, u.username, u.email, u.designation].some(v => v?.toLowerCase().includes(q));

    const subMap = new Map<string, BillOpsUser[]>();
    for (const u of users.filter(u => !!u.parentId)) {
      const k = String(u.parentId!);
      if (!subMap.has(k)) subMap.set(k, []);
      subMap.get(k)!.push(u);
    }

    const clientMap = new Map<string, BillOpsUser[]>();
    for (const u of users.filter(u => !u.parentId)) {
      const k = u.clientName || 'Unknown';
      if (!clientMap.has(k)) clientMap.set(k, []);
      clientMap.get(k)!.push(u);
    }

    let entries = Array.from(clientMap.entries()).map(([clientName, primaries]) => {
      const filtered = primaries.filter(p => {
        if (filterRole !== '' && p.role !== filterRole) return false;
        return match(p) || (subMap.get(String(p.id)) || []).some(s => match(s));
      });
      return { clientName, primaries: filtered, subMap } as const;
    }).filter(g => g.primaries.length > 0);

    if (sortBy === 'name-asc')    entries.sort((a, b) => a.clientName.localeCompare(b.clientName));
    else if (sortBy === 'name-desc') entries.sort((a, b) => b.clientName.localeCompare(a.clientName));
    else if (sortBy === 'count-desc') entries.sort((a, b) => b.primaries.length - a.primaries.length);

    return entries;
  }, [users, searchQuery, filterRole, sortBy]);

  useEffect(() => { setCurrentPage(1); }, [searchQuery, filterRole, sortBy]);

  const totalPages     = Math.ceil(clientGroups.length / PAGE_SIZE);
  const paginatedGroups = useMemo(() => clientGroups.slice((currentPage - 1) * PAGE_SIZE, currentPage * PAGE_SIZE), [clientGroups, currentPage]);

  const totalFilteredCount = useMemo(() =>
    clientGroups.reduce((s, g) => s + g.primaries.length + g.primaries.reduce((ss, p) => ss + (g.subMap.get(String(p.id))?.length ?? 0), 0), 0),
    [clientGroups]);

  const isClientExpanded  = (k: string) => expandedClients.has(k) || !!searchQuery || !!filterRole;
  const isPrimaryExpanded = (k: string, subs: BillOpsUser[])  => subs.length > 0 && (expandedPrimaryUsers.has(k) || !!searchQuery || !!filterRole);

  const stats = useMemo(() => ({
    total:   users.length,
    admin:   users.filter(u => u.role === 'ROLE_BILLOPS_ADMIN' || u.role === 'ROLE_BILLOPS_ADMIN_SUB').length,
    billops: users.filter(u => u.role === 'ROLE_BILLOPS').length,
    xamops:  users.filter(u => u.role === 'ROLE_XAMOPS').length,
  }), [users]);

  const { setActions, clearActions } = useTopbar();
  useEffect(() => {
    setActions(
      <button
        onClick={newUser}
        className="flex items-center gap-2 px-4 py-2 bg-[rgba(0,0,0,0.88)] hover:bg-black text-white text-xs font-semibold rounded-md transition-colors"
      >
        <Icon icon="mdi:account-plus-outline" width={14} />
        Create New User
      </button>
    );
    return () => clearActions();
  }, [setActions, clearActions, newUser]);

  return (
    <div className="px-6 pb-12 pt-6 space-y-5">
      <AlertDialog
        isOpen={!!pendingDeleteId}
        title="Delete user?"
        description="This action cannot be undone."
        confirmLabel="Delete"
        variant="danger"
        onConfirm={confirmDelete}
        onCancel={cancelDelete}
      />

      {/* Error */}
      {error && (
        <div className="flex items-center gap-3 p-4 bg-rose-50 border border-rose-100 rounded-xl text-sm text-rose-600">
          <Icon icon="mdi:alert-circle-outline" width={16} className="flex-shrink-0" />
          {error}
        </div>
      )}

      {/* ── Edit / Create Modal ── */}
      {isEditing && ReactDOM.createPortal(
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
          <div className="fixed inset-0 bg-black/40 backdrop-blur-sm" onClick={cancelEdit} />
          <div
            className="relative z-10 w-full max-w-2xl max-h-[90vh] overflow-y-auto bg-white border border-[rgba(0,0,0,0.12)] rounded-2xl shadow-xl"
            onClick={e => e.stopPropagation()}
          >
            {/* Modal header */}
            <div className="flex items-center gap-3 px-6 py-4 border-b border-[rgba(0,0,0,0.06)]">
              <div className="w-10 h-10 rounded-xl bg-[rgba(0,0,0,0.88)] flex items-center justify-center flex-shrink-0">
                <Icon icon={selectedUser.id ? 'mdi:account-edit-outline' : 'mdi:account-plus-outline'} width={20} color="white" />
              </div>
              <div>
                <h2 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">{selectedUser.id ? 'Edit User' : 'Create User'}</h2>
                <p className="text-xs text-[rgba(0,0,0,0.4)]">{selectedUser.id ? 'Update user details below' : 'Fill in details to create a new user'}</p>
              </div>
              <button onClick={cancelEdit} className="ml-auto p-2 rounded-lg hover:bg-[rgba(0,0,0,0.05)] text-[rgba(0,0,0,0.4)] transition-colors">
                <Icon icon="mdi:close" width={16} />
              </button>
            </div>

            <form onSubmit={e => { e.preventDefault(); saveUser(); }} className="p-6 space-y-4">
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div>
                  <FormLabel icon="mdi:domain" text="Client Name" />
                  <FieldInput type="text" value={selectedUser.clientName} required placeholder="Enter client name" onChange={e => updateSelectedUser('clientName', e.target.value)} />
                </div>
                <div>
                  <FormLabel icon="mdi:account-outline" text="Username" />
                  <FieldInput type="text" value={selectedUser.username} required placeholder="Enter username" onChange={e => updateSelectedUser('username', e.target.value)} />
                </div>
                <div>
                  <FormLabel icon="mdi:email-outline" text="Email" />
                  <FieldInput type="email" value={selectedUser.email} required placeholder="Enter email address" onChange={e => updateSelectedUser('email', e.target.value)} />
                </div>
                <div>
                  <FormLabel icon="mdi:shield-account-outline" text="Role" />
                  <FieldSelect value={selectedUser.role} required onChange={e => updateSelectedUser('role', e.target.value)}>
                    <option value="" disabled>Select Role</option>
                    {roles.map(r => <option key={r} value={r}>{formatRole(r)}</option>)}
                  </FieldSelect>
                </div>
                <div>
                  <FormLabel icon="mdi:phone-outline" text="Mobile Number" />
                  <FieldInput type="tel" value={selectedUser.contact} placeholder="9876543210 or +91 98765 43210" onChange={e => updateSelectedUser('contact', e.target.value)} />
                  {/* Optional, but it is the only route to SMS/WhatsApp alerts, so say so rather
                      than leaving an unlabelled field people skip. */}
                  <p className="mt-1 text-[11px] text-gray-400">
                    Used for SMS and WhatsApp alerts. Leave blank for email-only.
                  </p>
                </div>
                <div>
                  <FormLabel icon="mdi:briefcase-outline" text="Designation" />
                  <FieldInput type="text" value={selectedUser.designation} placeholder="e.g. Cloud Engineer" onChange={e => updateSelectedUser('designation', e.target.value)} />
                </div>
                <div className="sm:col-span-2">
                  <FormLabel icon="mdi:lock-outline" text="Password" />
                  <FieldInput type="password" value={selectedUser.password} required={!selectedUser.id} placeholder={selectedUser.id ? 'Leave blank to keep current password' : 'Enter password'} onChange={e => updateSelectedUser('password', e.target.value)} />
                </div>

                {selectedUser.parentId && (
                  <div className="sm:col-span-2">
                    <div className="flex items-center gap-2 px-3 py-2.5 bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.08)] rounded-lg">
                      <Icon icon="mdi:sitemap-outline" width={13} color="rgba(0,0,0,0.4)" />
                      <span className="text-xs text-[rgba(0,0,0,0.6)]">Will be created as a <strong className="text-[rgba(0,0,0,0.88)]">sub-user</strong> (Parent ID: {selectedUser.parentId})</span>
                      <button type="button" onClick={() => updateSelectedUser('parentId', '')} className="ml-auto p-1 hover:text-rose-500 text-[rgba(0,0,0,0.3)] transition-colors">
                        <Icon icon="mdi:close" width={13} />
                      </button>
                    </div>
                  </div>
                )}
              </div>

              {/* ── XamOps module / tab access (only for ROLE_XAMOPS) ── */}
              {selectedUser.role === 'ROLE_XAMOPS' && (
                <div className="pt-1">
                  <div className="flex items-center justify-between mb-2">
                    <FormLabel icon="mdi:view-grid-outline" text="Sidebar Module & Tab Access" />
                    <div className="flex items-center gap-2 mb-1.5">
                      <button
                        type="button"
                        onClick={() => updateSelectedUser('allowedTabs', [...ALL_XAMOPS_TAB_KEYS])}
                        className="text-[10px] font-semibold text-[rgba(0,0,0,0.6)] hover:text-black"
                      >
                        Select all
                      </button>
                      <span className="text-[rgba(0,0,0,0.15)]">|</span>
                      <button
                        type="button"
                        onClick={() => updateSelectedUser('allowedTabs', [])}
                        className="text-[10px] font-semibold text-rose-500 hover:text-rose-700"
                      >
                        Clear
                      </button>
                    </div>
                  </div>
                  <p className="text-[11px] text-[rgba(0,0,0,0.4)] mb-3">
                    Leave everything unchecked to grant full sidebar access. Selecting specific tabs restricts the user to only those.
                  </p>
                  <div className="space-y-3 max-h-[320px] overflow-y-auto pr-1">
                    {XAMOPS_MODULES.map(mod => {
                      const moduleTabKeys = mod.tabs.map(t => t.key);
                      const allChecked = moduleTabKeys.every(k => selectedUser.allowedTabs.includes(k));
                      const toggleModule = () => {
                        if (allChecked) {
                          updateSelectedUser('allowedTabs', selectedUser.allowedTabs.filter(k => !moduleTabKeys.includes(k)));
                        } else {
                          updateSelectedUser('allowedTabs', Array.from(new Set([...selectedUser.allowedTabs, ...moduleTabKeys])));
                        }
                      };
                      return (
                        <div key={mod.key} className="border border-[rgba(0,0,0,0.08)] rounded-xl overflow-hidden">
                          <label className="flex items-center gap-2.5 px-3 py-2.5 bg-[rgba(0,0,0,0.025)] cursor-pointer">
                            <input
                              type="checkbox"
                              checked={allChecked}
                              onChange={toggleModule}
                              className="w-4 h-4 rounded accent-[rgba(0,0,0,0.88)] cursor-pointer flex-shrink-0"
                            />
                            <Icon icon={mod.icon} width={14} color="rgba(0,0,0,0.6)" />
                            <span className="text-xs font-bold text-[rgba(0,0,0,0.8)]">{mod.label}</span>
                          </label>
                          <div className="grid grid-cols-1 sm:grid-cols-2 gap-1.5 p-2.5">
                            {mod.tabs.map(tab => (
                              <label key={tab.key} className="flex items-center gap-2 px-2.5 py-2 border border-[rgba(0,0,0,0.06)] rounded-lg cursor-pointer hover:bg-[rgba(0,0,0,0.02)] transition-colors">
                                <input
                                  type="checkbox"
                                  checked={selectedUser.allowedTabs.includes(tab.key)}
                                  onChange={() => toggleTab(tab.key)}
                                  className="w-3.5 h-3.5 rounded accent-[rgba(0,0,0,0.88)] cursor-pointer flex-shrink-0"
                                />
                                <Icon icon={tab.icon} width={13} color="rgba(0,0,0,0.45)" />
                                <span className="text-[11px] font-medium text-[rgba(0,0,0,0.7)]">{tab.label}</span>
                              </label>
                            ))}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}

              <div className="flex items-center justify-end gap-3 pt-2 border-t border-[rgba(0,0,0,0.06)]">
                <button type="button" onClick={cancelEdit} className="inline-flex items-center gap-2 px-4 py-2 border border-[rgba(0,0,0,0.12)] rounded-md bg-white text-[rgba(0,0,0,0.5)] text-xs font-medium hover:bg-[rgba(0,0,0,0.03)] transition-colors">
                  <Icon icon="mdi:close" width={13} />
                  Cancel
                </button>
                <button type="submit" className="inline-flex items-center gap-2 px-4 py-2 bg-[rgba(0,0,0,0.88)] hover:bg-black text-white rounded-md text-xs font-semibold transition-colors">
                  <Icon icon="mdi:content-save-outline" width={13} />
                  Save User
                </button>
              </div>
            </form>
          </div>
        </div>,
        document.body
      )}

      {/* ── KPI Cards ── */}
      {!loading && users.length > 0 && (
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
          {[
            { icon: 'mdi:account-group-outline', label: 'Total Users',  value: stats.total,   color: 'rgba(0,0,0,0.88)' },
            { icon: 'mdi:shield-crown-outline',   label: 'Admins',       value: stats.admin,   color: 'rgba(0,0,0,0.88)' },
            { icon: 'mdi:account-outline',         label: 'BillOps',      value: stats.billops, color: 'rgba(0,0,0,0.88)' },
            { icon: 'mdi:cloud-outline',           label: 'XamOps',       value: stats.xamops,  color: 'rgba(0,0,0,0.88)' },
          ].map((s, i) => (
            <div key={i} className="relative bg-white border border-[rgba(0,0,0,0.08)] rounded-xl p-4 overflow-hidden">
              {/* Watermark */}
              <svg className="absolute right-2 bottom-1 opacity-[0.04] pointer-events-none" width="56" height="56" viewBox="0 0 24 24" fill="none">
                <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 14H9V8h2v8zm4 0h-2V8h2v8z" fill="black" />
              </svg>
              <div className="flex items-start gap-3">
                <div className="w-9 h-9 rounded-xl bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.06)] flex items-center justify-center flex-shrink-0">
                  <Icon icon={s.icon} width={16} color="rgba(0,0,0,0.5)" />
                </div>
                <div>
                  <p style={{ fontFamily: "'Bebas Neue','Barlow Condensed',sans-serif", fontSize: '28px', fontWeight: 400, lineHeight: '28px', color: 'rgb(10,10,10)' }}>{s.value}</p>
                  <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-0.5 leading-tight">{s.label}</p>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* ── Main Table Card ── */}
      <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl shadow-sm overflow-hidden">
        {loading ? (
          <div className="flex flex-col items-center justify-center py-16 gap-3">
            <Icon icon="mdi:loading" width={28} color="rgba(0,0,0,0.25)" className="animate-spin" />
            <p className="text-sm text-[rgba(0,0,0,0.4)]">Loading users…</p>
          </div>
        ) : users.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-16 gap-3">
            <div className="w-14 h-14 rounded-xl bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] flex items-center justify-center">
              <Icon icon="mdi:account-group-outline" width={28} color="rgba(0,0,0,0.2)" />
            </div>
            <h3 className="text-sm font-bold text-[rgba(0,0,0,0.7)]">No Users Found</h3>
            <p className="text-xs text-[rgba(0,0,0,0.4)]">Create your first user to get started.</p>
          </div>
        ) : (
          <>
            {/* Toolbar */}
            <div className="flex flex-wrap items-center gap-2 px-4 py-3 border-b border-[rgba(0,0,0,0.06)]">
              {/* Search */}
              <div className="relative flex-1 min-w-[180px]">
                <Icon icon="mdi:magnify" width={14} color="rgba(0,0,0,0.35)" className="absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" />
                <input
                  type="text"
                  value={searchQuery}
                  onChange={e => setSearchQuery(e.target.value)}
                  placeholder="Search name, email, designation…"
                  className="w-full pl-8 pr-8 py-2 text-xs bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] rounded-lg text-[rgba(0,0,0,0.88)] placeholder-[rgba(0,0,0,0.3)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.15)] transition-colors"
                />
                {searchQuery && (
                  <button onClick={() => setSearchQuery('')} className="absolute right-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)] hover:text-[rgba(0,0,0,0.6)]">
                    <Icon icon="mdi:close" width={12} />
                  </button>
                )}
              </div>

              {/* Role filter */}
              <select
                value={filterRole}
                onChange={e => setFilterRole(e.target.value as BillOpsRole | '')}
                className="px-3 py-2 text-xs bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] rounded-lg text-[rgba(0,0,0,0.7)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.15)] transition-colors"
              >
                <option value="">All Roles</option>
                {roles.map(r => <option key={r} value={r}>{formatRole(r)}</option>)}
              </select>

              {/* Sort */}
              <select
                value={sortBy}
                onChange={e => setSortBy(e.target.value as typeof sortBy)}
                className="px-3 py-2 text-xs bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] rounded-lg text-[rgba(0,0,0,0.7)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.15)] transition-colors"
              >
                <option value="name-asc">Name A → Z</option>
                <option value="name-desc">Name Z → A</option>
                <option value="count-desc">Most Users First</option>
              </select>

              {/* Expand / Collapse */}
              <div className="flex items-center gap-1">
                <button
                  onClick={() => setExpandedClients(new Set(clientGroups.map(g => g.clientName)))}
                  className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-[10px] font-bold uppercase tracking-widest border border-[rgba(0,0,0,0.08)] bg-white text-[rgba(0,0,0,0.5)] hover:bg-[rgba(0,0,0,0.03)] transition-colors"
                >
                  <Icon icon="mdi:arrow-expand-all" width={11} />All
                </button>
                <button
                  onClick={() => setExpandedClients(new Set())}
                  className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-[10px] font-bold uppercase tracking-widest border border-[rgba(0,0,0,0.08)] bg-white text-[rgba(0,0,0,0.5)] hover:bg-[rgba(0,0,0,0.03)] transition-colors"
                >
                  <Icon icon="mdi:arrow-collapse-all" width={11} />None
                </button>
              </div>

              {(searchQuery || filterRole) && (
                <button
                  onClick={() => { setSearchQuery(''); setFilterRole(''); }}
                  className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-[10px] font-semibold text-rose-500 border border-rose-100 bg-rose-50 hover:bg-rose-100 transition-colors"
                >
                  <Icon icon="mdi:filter-remove-outline" width={11} />
                  Clear
                </button>
              )}

              <span className="ml-auto text-[11px] text-[rgba(0,0,0,0.4)] whitespace-nowrap">
                {totalFilteredCount === users.length
                  ? `${clientGroups.length} client${clientGroups.length !== 1 ? 's' : ''} · ${users.length} users`
                  : `${totalFilteredCount} of ${users.length} users`}
                {totalPages > 1 && ` · p.${currentPage}/${totalPages}`}
              </span>
            </div>

            {/* Table */}
            {clientGroups.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-12 gap-2">
                <Icon icon="mdi:magnify" width={28} color="rgba(0,0,0,0.15)" />
                <p className="text-sm font-semibold text-[rgba(0,0,0,0.5)]">No Results Found</p>
                <p className="text-xs text-[rgba(0,0,0,0.35)]">Try adjusting your search or filter.</p>
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left border-collapse">
                  <thead>
                    <tr className="border-b border-[rgba(0,0,0,0.06)]">
                      {['User', 'Email', 'Contact', 'Designation', 'Role', 'Actions'].map(h => (
                        <th key={h} className="px-4 py-2.5 text-[10px] font-bold text-[rgba(0,0,0,0.4)] uppercase tracking-wider whitespace-nowrap">
                          {h}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {paginatedGroups.map(({ clientName, primaries, subMap }) => {
                      const clientExpanded = isClientExpanded(clientName);
                      const totalInClient  = primaries.reduce((s, p) => s + 1 + (subMap.get(String(p.id))?.length ?? 0), 0);
                      return (
                        <React.Fragment key={clientName}>
                          {/* ── Level 1: Client row ── */}
                          <tr
                            className="cursor-pointer select-none bg-[rgba(0,0,0,0.025)] hover:bg-[rgba(0,0,0,0.04)] transition-colors border-t border-[rgba(0,0,0,0.06)]"
                            onClick={() => toggleClient(clientName)}
                          >
                            <td colSpan={6} className="px-4 py-3">
                              <div className="flex items-center justify-between">
                                <div className="flex items-center gap-3">
                                  <Icon
                                    icon={clientExpanded ? 'mdi:chevron-down' : 'mdi:chevron-right'}
                                    width={14}
                                    color="rgba(0,0,0,0.35)"
                                  />
                                  <div className="w-8 h-8 rounded-xl bg-[rgba(0,0,0,0.88)] text-white flex items-center justify-center text-[11px] font-bold flex-shrink-0">
                                    {getInitials(clientName)}
                                  </div>
                                  <span className="text-sm font-bold text-[rgba(0,0,0,0.88)]">{clientName}</span>
                                  <span className="px-2 py-0.5 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.5)] rounded-full text-[10px] font-bold">
                                    {primaries.length} primary · {totalInClient} total
                                  </span>
                                </div>
                                <button
                                  type="button"
                                  onClick={e => { e.stopPropagation(); handleNewUserForClient(clientName); }}
                                  className="flex items-center gap-1.5 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] hover:bg-black text-white rounded-lg text-[10px] font-bold transition-colors"
                                >
                                  <Icon icon="mdi:account-plus-outline" width={11} />
                                  Add User
                                </button>
                              </div>
                            </td>
                          </tr>

                          {/* ── Level 2: Primary users ── */}
                          {clientExpanded && primaries.map((primary: BillOpsUser) => {
                            const subs = subMap.get(String(primary.id)) || [];
                            const primaryExpanded = isPrimaryExpanded(primary.id, subs);
                            const meta = getRoleMeta(primary.role);
                            return (
                              <React.Fragment key={primary.id}>
                                <tr className="bg-white border-b border-[rgba(0,0,0,0.04)] border-l-2 border-l-[rgba(0,0,0,0.12)] hover:bg-[rgba(0,0,0,0.01)] transition-colors">
                                  <td className="pl-10 pr-4 py-3">
                                    <div className="flex items-center gap-2.5">
                                      {subs.length > 0 ? (
                                        <button
                                          type="button"
                                          onClick={() => togglePrimaryUser(primary.id)}
                                          className="w-4 h-4 flex items-center justify-center text-[rgba(0,0,0,0.35)] hover:text-[rgba(0,0,0,0.7)] flex-shrink-0"
                                        >
                                          <Icon icon={primaryExpanded ? 'mdi:chevron-down' : 'mdi:chevron-right'} width={13} />
                                        </button>
                                      ) : (
                                        <span className="w-4 flex-shrink-0" />
                                      )}
                                      <div className="w-8 h-8 rounded-xl bg-[rgba(0,0,0,0.88)] text-white flex items-center justify-center text-[10px] font-bold flex-shrink-0">
                                        {getInitials(primary.username)}
                                      </div>
                                      <div className="flex flex-col leading-tight">
                                        <span className="text-xs font-bold text-[rgba(0,0,0,0.88)]">@{primary.username}</span>
                                        {subs.length > 0 && (
                                          <span className="text-[9px] text-[rgba(0,0,0,0.4)] font-semibold">{subs.length} sub-user{subs.length !== 1 ? 's' : ''}</span>
                                        )}
                                      </div>
                                    </div>
                                  </td>
                                  <td className="px-4 py-3 text-xs text-[rgba(0,0,0,0.6)]">{primary.email}</td>
                                  <td className="px-4 py-3 text-xs text-[rgba(0,0,0,0.5)]">{primary.contact || <span className="text-[rgba(0,0,0,0.2)]">—</span>}</td>
                                  <td className="px-4 py-3 text-xs text-[rgba(0,0,0,0.5)]">{primary.designation || <span className="text-[rgba(0,0,0,0.2)]">—</span>}</td>
                                  <td className="px-4 py-3">
                                    <span className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded-md border text-[10px] font-bold uppercase tracking-wide ${meta.badge}`}>
                                      <Icon icon={meta.icon} width={10} />
                                      {meta.label}
                                    </span>
                                  </td>
                                  <td className="px-4 py-3">
                                    <div className="flex items-center gap-1.5">
                                      <button
                                        onClick={() => handleNewSubUser(primary)}
                                        title="Add sub-user"
                                        className="inline-flex items-center gap-1 px-2 py-1.5 text-[10px] font-semibold bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.06)] rounded-md transition-colors"
                                      >
                                        <Icon icon="mdi:account-plus-outline" width={11} />
                                      </button>
                                      <button
                                        onClick={() => editUser(primary)}
                                        title="Edit user"
                                        className="inline-flex items-center gap-1 px-2 py-1.5 text-[10px] font-semibold bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.06)] rounded-md transition-colors"
                                      >
                                        <Icon icon="mdi:pencil-outline" width={11} />
                                      </button>
                                      <button
                                        onClick={() => deleteUser(primary.id)}
                                        title="Delete user"
                                        className="inline-flex items-center gap-1 px-2 py-1.5 text-[10px] font-semibold bg-rose-50 border border-rose-100 text-rose-500 hover:bg-rose-100 rounded-md transition-colors"
                                      >
                                        <Icon icon="mdi:trash-can-outline" width={11} />
                                      </button>
                                    </div>
                                  </td>
                                </tr>

                                {/* ── Level 3: Sub-users ── */}
                                {primaryExpanded && subs.map((sub: BillOpsUser) => {
                                  const subMeta = getRoleMeta(sub.role);
                                  return (
                                    <tr key={sub.id} className="bg-[rgba(0,0,0,0.01)] border-b border-[rgba(0,0,0,0.04)] border-l-2 border-l-[rgba(0,0,0,0.06)] hover:bg-[rgba(0,0,0,0.02)] transition-colors">
                                      <td className="pl-20 pr-4 py-2.5">
                                        <div className="flex items-center gap-2">
                                          <Icon icon="mdi:subdirectory-arrow-right" width={12} color="rgba(0,0,0,0.2)" className="flex-shrink-0" />
                                          <div className="w-6 h-6 rounded-lg bg-[rgba(0,0,0,0.06)] text-[rgba(0,0,0,0.6)] flex items-center justify-center text-[9px] font-bold flex-shrink-0">
                                            {getInitials(sub.username)}
                                          </div>
                                          <span className="text-xs text-[rgba(0,0,0,0.6)] font-semibold">@{sub.username}</span>
                                        </div>
                                      </td>
                                      <td className="px-4 py-2.5 text-xs text-[rgba(0,0,0,0.5)]">{sub.email}</td>
                                      <td className="px-4 py-2.5 text-xs text-[rgba(0,0,0,0.4)]">{sub.contact || <span className="text-[rgba(0,0,0,0.2)]">—</span>}</td>
                                      <td className="px-4 py-2.5 text-xs text-[rgba(0,0,0,0.4)]">{sub.designation || <span className="text-[rgba(0,0,0,0.2)]">—</span>}</td>
                                      <td className="px-4 py-2.5">
                                        <span className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded-md border text-[10px] font-bold uppercase tracking-wide ${subMeta.badge}`}>
                                          <Icon icon={subMeta.icon} width={10} />
                                          {subMeta.label}
                                        </span>
                                      </td>
                                      <td className="px-4 py-2.5">
                                        <div className="flex items-center gap-1.5">
                                          <button
                                            onClick={() => editUser(sub)}
                                            title="Edit user"
                                            className="inline-flex items-center gap-1 px-2 py-1.5 text-[10px] font-semibold bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.06)] rounded-md transition-colors"
                                          >
                                            <Icon icon="mdi:pencil-outline" width={11} />
                                          </button>
                                          <button
                                            onClick={() => deleteUser(sub.id)}
                                            title="Delete user"
                                            className="inline-flex items-center gap-1 px-2 py-1.5 text-[10px] font-semibold bg-rose-50 border border-rose-100 text-rose-500 hover:bg-rose-100 rounded-md transition-colors"
                                          >
                                            <Icon icon="mdi:trash-can-outline" width={11} />
                                          </button>
                                        </div>
                                      </td>
                                    </tr>
                                  );
                                })}
                              </React.Fragment>
                            );
                          })}
                        </React.Fragment>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </>
        )}
      </div>

      {/* ── Pagination ── */}
      {totalPages > 1 && (
        <div className="flex items-center justify-center gap-1.5">
          <button
            onClick={() => setCurrentPage(p => Math.max(1, p - 1))}
            disabled={currentPage === 1}
            className="flex items-center gap-1 px-3 py-2 bg-white border border-[rgba(0,0,0,0.1)] rounded-lg text-xs font-semibold text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.03)] disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
          >
            <Icon icon="mdi:chevron-left" width={14} /> Prev
          </button>
          {Array.from({ length: totalPages }, (_, i) => i + 1).map(page => {
            const isActive = page === currentPage;
            const show = page === 1 || page === totalPages || Math.abs(page - currentPage) <= 1;
            if (!show) {
              if (page === 2 || page === totalPages - 1) return <span key={page} className="text-[rgba(0,0,0,0.3)] text-xs select-none">…</span>;
              return null;
            }
            return (
              <button
                key={page}
                onClick={() => setCurrentPage(page)}
                className={`w-9 h-9 rounded-lg text-xs font-bold transition-colors ${isActive ? 'bg-[rgba(0,0,0,0.88)] text-white' : 'bg-white border border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.03)]'}`}
              >
                {page}
              </button>
            );
          })}
          <button
            onClick={() => setCurrentPage(p => Math.min(totalPages, p + 1))}
            disabled={currentPage === totalPages}
            className="flex items-center gap-1 px-3 py-2 bg-white border border-[rgba(0,0,0,0.1)] rounded-lg text-xs font-semibold text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.03)] disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
          >
            Next <Icon icon="mdi:chevron-right" width={14} />
          </button>
        </div>
      )}

      {/* Notification */}
      {notification && (
        <NotificationModal
          isOpen={!!notification}
          onClose={clearNotification}
          type={notification.type}
          title={notification.title}
          message={notification.message}
        />
      )}
    </div>
  );
};

export default BillOpsAdminUserManagerPage;
