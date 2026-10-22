import React, { useEffect, useState, useMemo } from 'react';
import ReactDOM from 'react-dom';
import { Icon } from '@iconify/react';
import { useTopbar } from '../contexts/TopbarContext';
import { useBillOpsUserManager } from '../hooks/useBillOpsUserManager';
import type { BillOpsUser, BillOpsRole, ClientCloudAccount } from '../types/billopsUserManager';
import NotificationModal from '../components/Shared/NotificationModal';
import TrialManagementSection from '../components/BillOps/TrialManagementSection';
import { AlertDialog } from '../components/ui';
import { XAMOPS_MODULES, normalizeAllowedTabs, selectAllXamTabs } from '../constants/xamopsTabs';
import { BILLOPS_ADMIN_MODULES } from '../constants/billopsAdminTabs';
import XamTabPermissionToggle from '../components/Shared/XamTabPermissionToggle';
import ModuleTabAccessPicker from '../components/Shared/ModuleTabAccessPicker';

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
    users, clients, loading, error, isEditing, selectedUser, roles,
    newUser, editUser, cancelEdit, saveUser, deleteUser, deleteClient, moveUserToClient, updateSelectedUser,
    changeRole, toggleAccount, fetchClientCloudAccounts, fetchAllCloudAccounts,
    notification, clearNotification, pendingDeleteId, confirmDelete, cancelDelete,
  } = useBillOpsUserManager();

  /* ── Cloud-account access picker state ── */
  const [clientAccounts, setClientAccounts] = useState<ClientCloudAccount[]>([]);
  // In-flight flag for the account catalog fetch, so the picker can show a spinner rather than a
  // blank. Without it an empty response and a still-loading one look identical.
  const [accountsLoading, setAccountsLoading] = useState(false);
  // True when the catalog fetch failed outright (most often a 404 from a backend that predates
  // the /cloud-accounts endpoint). Kept distinct from an empty result so the picker can say
  // "couldn't load" rather than wrongly implying no accounts exist.
  const [accountsError, setAccountsError] = useState(false);
  // Search term for filtering the account-access list (scales to hundreds of accounts).
  const [accountSearch, setAccountSearch]   = useState('');

  /**
   * Whether the user being edited holds one of the BillOps admin roles. They are the only ones
   * with an admin sidebar to scope, and the only ones whose cloud accounts are chosen from every
   * client rather than from their own.
   */
  const isAdminRole = selectedUser.role === 'ROLE_BILLOPS_ADMIN'
    || selectedUser.role === 'ROLE_BILLOPS_ADMIN_SUB';

  // Resolve the client id of the user being edited. The form keys off clientName, so map the
  // selected form id → its user row to read clientId; fall back to matching clientName.
  const editingClientId = useMemo<string | number | null>(() => {
    if (!isEditing) return null;
    if (selectedUser.id) {
      const u = users.find(x => String(x.id) === String(selectedUser.id));
      if (u?.clientId != null) return u.clientId;
    }
    if (selectedUser.clientName) {
      const u = users.find(x => x.clientName === selectedUser.clientName && x.clientId != null);
      if (u?.clientId != null) return u.clientId;
    }
    return null;
  }, [isEditing, selectedUser.id, selectedUser.clientName, users]);

  /**
   * Load the catalog for the account picker, which depends on the role being edited.
   *
   * A BillOps admin manages accounts across customers, so theirs is every account the acting
   * admin can see — available immediately, including while creating the user. Everyone else is
   * confined to their own client, so theirs needs that client resolved first, which is why their
   * picker only appears once the user exists.
   */
  useEffect(() => {
    let cancelled = false;
    const load = (fetcher: () => Promise<ClientCloudAccount[]>) => {
      setAccountsLoading(true);
      setAccountsError(false);
      fetcher()
        .then(accounts => { if (!cancelled) setClientAccounts(accounts); })
        .catch(() => {
          if (!cancelled) {
            setClientAccounts([]);
            setAccountsError(true);
          }
        })
        .finally(() => { if (!cancelled) setAccountsLoading(false); });
    };

    setAccountsError(false);
    if (!isEditing) {
      setClientAccounts([]);
      setAccountsLoading(false);
    } else if (isAdminRole) {
      load(fetchAllCloudAccounts);
    } else if (editingClientId != null) {
      load(() => fetchClientCloudAccounts(editingClientId));
    } else {
      setClientAccounts([]);
      setAccountsLoading(false);
    }
    // Reset the account search each time the picker's data source changes — a stale term would
    // otherwise hide most of the new list behind an empty state.
    setAccountSearch('');
    return () => { cancelled = true; };
  }, [isEditing, isAdminRole, editingClientId, fetchClientCloudAccounts, fetchAllCloudAccounts]);

  // Accounts shown in the picker, filtered by the search term (name / provider / accountId).
  // Case-insensitive substring match; keeps all accounts when the search is empty.
  const filteredClientAccounts = useMemo(() => {
    const q = accountSearch.trim().toLowerCase();
    if (!q) return clientAccounts;
    return clientAccounts.filter(acc =>
      (acc.name || '').toLowerCase().includes(q) ||
      (acc.provider || '').toLowerCase().includes(q) ||
      // Only set on the cross-client list, which is the one long enough to need it.
      (acc.clientName || '').toLowerCase().includes(q) ||
      (acc.accountId ? String(acc.accountId).toLowerCase().includes(q) : false)
    );
  }, [clientAccounts, accountSearch]);

  /* ── Move-user-between-clients modal state ── */
  const [moveUser, setMoveUser]             = useState<BillOpsUser | null>(null);
  const [moveTargetClientId, setMoveTargetClientId] = useState<string>('');
  // Where the moved user lands in the destination client's tree: on its own, or under a parent.
  const [movePlacement, setMovePlacement]   = useState<'primary' | 'sub'>('primary');
  const [moveParentId, setMoveParentId]     = useState<string>('');
  const [moveSubmitting, setMoveSubmitting] = useState(false);

  // Distinct clients (id + name). Seeded from the clients endpoint so a client with no users is
  // still a valid destination, then topped up from the user list in case that call failed.
  const clientOptions = useMemo(() => {
    const map = new Map<string, string>();
    for (const c of clients) {
      if (c.id != null && c.name) map.set(String(c.id), c.name);
    }
    for (const u of users) {
      if (u.clientId != null && u.clientName) map.set(String(u.clientId), u.clientName);
    }
    return Array.from(map.entries())
      .map(([id, name]) => ({ id, name }))
      .sort((a, b) => a.name.localeCompare(b.name));
  }, [clients, users]);

  // How many sub-users the user being moved owns. A user with sub-users travels as a family and
  // can never be nested under someone else (the tree is two levels deep), so the placement choice
  // is offered only for individual users.
  const moveUserSubCount = useMemo(
    () => (moveUser ? users.filter(u => u.parentId != null && String(u.parentId) === String(moveUser.id)).length : 0),
    [moveUser, users]
  );
  const moveUserIsIndividual = !!moveUser && moveUserSubCount === 0;

  // Destination clients. An individual user can also be re-parented inside its own client, so the
  // current client stays selectable for them; for a family move it would be a no-op.
  const moveClientOptions = useMemo(() => {
    if (!moveUser) return [];
    return moveUserIsIndividual
      ? clientOptions
      : clientOptions.filter(c => String(c.id) !== String(moveUser.clientId));
  }, [clientOptions, moveUser, moveUserIsIndividual]);

  // Primary users of the selected destination client that this user can be attached to. Excludes
  // the user itself and anyone who is already a sub-user, matching what the backend accepts.
  const moveParentCandidates = useMemo(() => {
    if (!moveUser || !moveTargetClientId) return [];
    return users
      .filter(u =>
        u.clientId != null && String(u.clientId) === String(moveTargetClientId) &&
        u.parentId == null &&
        String(u.id) !== String(moveUser.id))
      .sort((a, b) => a.username.localeCompare(b.username));
  }, [users, moveTargetClientId, moveUser]);

  // True when the chosen destination + placement is exactly where the user already sits.
  const moveIsNoOp = useMemo(() => {
    if (!moveUser || !moveTargetClientId) return false;
    if (String(moveUser.clientId ?? '') !== String(moveTargetClientId)) return false;
    return movePlacement === 'sub'
      ? !!moveParentId && String(moveUser.parentId ?? '') === String(moveParentId)
      : moveUser.parentId == null;
  }, [moveUser, moveTargetClientId, movePlacement, moveParentId]);

  const openMoveModal = (user: BillOpsUser) => {
    setMoveUser(user);
    setMoveTargetClientId('');
    setMovePlacement('primary');
    setMoveParentId('');
  };
  const closeMoveModal = () => {
    setMoveUser(null);
    setMoveTargetClientId('');
    setMovePlacement('primary');
    setMoveParentId('');
    setMoveSubmitting(false);
  };
  // Switching client invalidates the parent picker — its candidates belong to the old selection.
  const changeMoveTargetClient = (clientId: string) => {
    setMoveTargetClientId(clientId);
    setMovePlacement('primary');
    setMoveParentId('');
  };
  const confirmMove = async () => {
    if (!moveUser || !moveTargetClientId || moveIsNoOp) return;
    if (movePlacement === 'sub' && !moveParentId) return;
    const newParentId = movePlacement === 'sub' ? moveParentId : null;
    const targetClientName = moveClientOptions.find(c => String(c.id) === String(moveTargetClientId))?.name;
    setMoveSubmitting(true);
    const ok = await moveUserToClient(moveUser.id, moveTargetClientId, newParentId);
    setMoveSubmitting(false);
    if (!ok) return;
    // Show where the user landed — a sub-user is only visible once its client group and its new
    // parent row are both expanded, so the move would otherwise look like nothing happened.
    if (targetClientName) setExpandedClients(prev => new Set(prev).add(targetClientName));
    if (newParentId) setExpandedPrimaryUsers(prev => new Set(prev).add(String(newParentId)));
    closeMoveModal();
  };

  /* ── Delete-client confirmation state ── */
  // Name is kept alongside the id so the dialog can quote it after the row is gone.
  const [pendingDeleteClient, setPendingDeleteClient] = useState<{ id: number | string; name: string } | null>(null);
  const confirmDeleteClient = async () => {
    if (!pendingDeleteClient) return;
    const target = pendingDeleteClient;
    setPendingDeleteClient(null);
    await deleteClient(target.id);
  };

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

    // Seed with every known client so a client with no users still gets a row — that row is the
    // only place it can be given a first user or be deleted. Client names are unique server-side,
    // so the name is a safe key; ids come from the clients endpoint, or from the users otherwise.
    const clientMap = new Map<string, BillOpsUser[]>();
    const idByName = new Map<string, number | string>();
    const totalUsersByName = new Map<string, number>();
    const accountsByName = new Map<string, number>();
    for (const c of clients) {
      if (!c.name) continue;
      clientMap.set(c.name, []);
      if (c.id != null) idByName.set(c.name, c.id);
      totalUsersByName.set(c.name, c.userCount ?? 0);
      accountsByName.set(c.name, c.cloudAccountCount ?? 0);
    }
    for (const u of users) {
      const k = u.clientName || 'Unknown';
      if (!clientMap.has(k)) clientMap.set(k, []);
      if (u.clientId != null && !idByName.has(k)) idByName.set(k, u.clientId);
      // Only trust the user list for a count the clients endpoint did not provide.
      if (!totalUsersByName.has(k)) totalUsersByName.set(k, users.filter(x => (x.clientName || 'Unknown') === k).length);
      if (!u.parentId) clientMap.get(k)!.push(u);
    }

    let entries = Array.from(clientMap.entries()).map(([clientName, primaries]) => {
      const filtered = primaries.filter(p => {
        if (filterRole !== '' && p.role !== filterRole) return false;
        return match(p) || (subMap.get(String(p.id)) || []).some(s => match(s));
      });
      return {
        clientName,
        clientId: idByName.get(clientName) ?? null,
        primaries: filtered,
        subMap,
        totalUsers: totalUsersByName.get(clientName) ?? 0,
        cloudAccountCount: accountsByName.get(clientName) ?? 0,
      } as const;
    }).filter(g =>
      // Clients with matching users, as before — plus genuinely empty clients, which have no user
      // to match on. A role filter is a filter on users, so it hides them; a text search still
      // applies, matched against the client name.
      g.primaries.length > 0
      || (g.totalUsers === 0 && filterRole === '' && (!q || g.clientName.toLowerCase().includes(q))));

    if (sortBy === 'name-asc')    entries.sort((a, b) => a.clientName.localeCompare(b.clientName));
    else if (sortBy === 'name-desc') entries.sort((a, b) => b.clientName.localeCompare(a.clientName));
    else if (sortBy === 'count-desc') entries.sort((a, b) => b.primaries.length - a.primaries.length);

    return entries;
  }, [users, clients, searchQuery, filterRole, sortBy]);

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

      {/* Only offered on clients with no users and no cloud accounts, so the copy can promise
          exactly what goes: the client record itself. */}
      <AlertDialog
        isOpen={pendingDeleteClient !== null}
        title="Delete client?"
        description={pendingDeleteClient
          ? `Remove the client "${pendingDeleteClient.name}". It has no users and no cloud accounts, so only the client record goes. This cannot be undone.`
          : ''}
        confirmLabel="Delete client"
        variant="danger"
        onConfirm={() => { void confirmDeleteClient(); }}
        onCancel={() => setPendingDeleteClient(null)}
      />

      {/* ── Move user to another client ── */}
      {moveUser && ReactDOM.createPortal(
        <div
          className="fixed inset-0 z-[1000] flex items-center justify-center bg-black/40 p-4"
          onClick={closeMoveModal}
        >
          <div
            className="w-full max-w-md bg-white rounded-2xl shadow-xl border border-[rgba(0,0,0,0.08)] overflow-hidden"
            onClick={e => e.stopPropagation()}
          >
            <div className="px-5 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center gap-2">
              <Icon icon="mdi:swap-horizontal" width={18} className="text-indigo-600" />
              <h3 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">
                {moveUserIsIndividual ? 'Move or re-assign user' : 'Move user to another client'}
              </h3>
            </div>

            <div className="px-5 py-4 space-y-4">
              <div className="text-xs text-[rgba(0,0,0,0.6)]">
                Moving <strong className="text-[rgba(0,0,0,0.88)]">@{moveUser.username}</strong> from{' '}
                <strong className="text-[rgba(0,0,0,0.88)]">{moveUser.clientName}</strong>. The user keeps all
                their tickets, credits and access — only where they sit changes.
                {moveUserIsIndividual
                  ? ' Pick the same client to re-assign them under a different primary user.'
                  : ` Their ${moveUserSubCount} sub-user${moveUserSubCount !== 1 ? 's' : ''} move along with them.`}
              </div>

              <div>
                <FormLabel icon="mdi:office-building-outline" text="Destination client" />
                <FieldSelect
                  value={moveTargetClientId}
                  onChange={e => changeMoveTargetClient(e.target.value)}
                >
                  <option value="">Select a client…</option>
                  {moveClientOptions.map(c => (
                    <option key={c.id} value={c.id}>
                      {c.name}{String(c.id) === String(moveUser.clientId) ? ' (current client)' : ''}
                    </option>
                  ))}
                </FieldSelect>
              </div>

              {/* Placement picker — individual users only; a family always lands as a primary. */}
              {moveUserIsIndividual && moveTargetClientId && (
                <div>
                  <FormLabel icon="mdi:file-tree-outline" text="Placement in that client" />
                  <div className="space-y-2">
                    <label
                      className={`flex items-start gap-2.5 px-3 py-2.5 rounded-lg border cursor-pointer transition-colors ${
                        movePlacement === 'primary'
                          ? 'bg-indigo-50 border-indigo-200'
                          : 'bg-[rgba(0,0,0,0.02)] border-[rgba(0,0,0,0.08)] hover:bg-[rgba(0,0,0,0.04)]'
                      }`}
                    >
                      <input
                        type="radio"
                        name="move-placement"
                        value="primary"
                        checked={movePlacement === 'primary'}
                        onChange={() => { setMovePlacement('primary'); setMoveParentId(''); }}
                        className="mt-0.5 accent-indigo-600"
                      />
                      <span className="flex flex-col leading-tight">
                        <span className="text-xs font-semibold text-[rgba(0,0,0,0.88)]">Another primary user</span>
                        <span className="text-[10px] text-[rgba(0,0,0,0.5)]">
                          Sits directly under the client, with no parent user.
                        </span>
                      </span>
                    </label>

                    <label
                      className={`flex items-start gap-2.5 px-3 py-2.5 rounded-lg border transition-colors ${
                        moveParentCandidates.length === 0
                          ? 'bg-[rgba(0,0,0,0.02)] border-[rgba(0,0,0,0.06)] opacity-60 cursor-not-allowed'
                          : movePlacement === 'sub'
                            ? 'bg-indigo-50 border-indigo-200 cursor-pointer'
                            : 'bg-[rgba(0,0,0,0.02)] border-[rgba(0,0,0,0.08)] hover:bg-[rgba(0,0,0,0.04)] cursor-pointer'
                      }`}
                    >
                      <input
                        type="radio"
                        name="move-placement"
                        value="sub"
                        checked={movePlacement === 'sub'}
                        disabled={moveParentCandidates.length === 0}
                        onChange={() => setMovePlacement('sub')}
                        className="mt-0.5 accent-indigo-600"
                      />
                      <span className="flex flex-col leading-tight">
                        <span className="text-xs font-semibold text-[rgba(0,0,0,0.88)]">Sub-user of an existing user</span>
                        <span className="text-[10px] text-[rgba(0,0,0,0.5)]">
                          {moveParentCandidates.length === 0
                            ? 'That client has no other primary user to attach to.'
                            : 'Nests this user under one of the client’s primary users.'}
                        </span>
                      </span>
                    </label>

                    {movePlacement === 'sub' && moveParentCandidates.length > 0 && (
                      <FieldSelect
                        value={moveParentId}
                        aria-label="Parent primary user"
                        onChange={e => setMoveParentId(e.target.value)}
                      >
                        <option value="">Select a primary user…</option>
                        {moveParentCandidates.map(p => (
                          <option key={p.id} value={p.id}>
                            @{p.username}{p.email ? ` · ${p.email}` : ''}
                          </option>
                        ))}
                      </FieldSelect>
                    )}
                  </div>
                </div>
              )}

              {moveIsNoOp && (
                <div className="flex items-start gap-2 px-3 py-2 bg-amber-50 border border-amber-100 rounded-lg text-[11px] text-amber-700">
                  <Icon icon="mdi:information-outline" width={13} className="mt-0.5 flex-shrink-0" />
                  That is where this user already sits — pick a different client or placement.
                </div>
              )}
            </div>

            <div className="px-5 py-4 border-t border-[rgba(0,0,0,0.06)] flex items-center justify-end gap-2">
              <button
                onClick={closeMoveModal}
                className="px-3 py-2 text-xs font-semibold text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.04)] rounded-lg transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={confirmMove}
                disabled={
                  !moveTargetClientId ||
                  moveIsNoOp ||
                  (movePlacement === 'sub' && !moveParentId) ||
                  moveSubmitting
                }
                className="inline-flex items-center gap-1.5 px-4 py-2 text-xs font-semibold bg-indigo-600 text-white rounded-lg hover:bg-indigo-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
              >
                <Icon icon="mdi:swap-horizontal" width={13} />
                {moveSubmitting
                  ? 'Saving…'
                  : String(moveUser.clientId ?? '') === String(moveTargetClientId)
                    ? 'Re-assign user'
                    : 'Move user'}
              </button>
            </div>
          </div>
        </div>,
        document.body
      )}

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
                  {/* changeRole, not updateSelectedUser: switching role families has to drop the
                      section selection, which is written in the outgoing role's key vocabulary. */}
                  <FieldSelect value={selectedUser.role} required onChange={e => changeRole(e.target.value as BillOpsRole | '')}>
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

              {/* ── Sidebar access: XamOps modules for a XamOps user, admin sections for an
                   admin user. Same picker, same "nothing checked = full access" rule; only the
                   catalog differs, because the two roles see entirely different sidebars. ── */}
              {selectedUser.role === 'ROLE_XAMOPS' && (
                <ModuleTabAccessPicker
                  label="Sidebar Module & Tab Access"
                  modules={XAMOPS_MODULES}
                  selected={selectedUser.allowedTabs}
                  onChange={next => updateSelectedUser('allowedTabs', next)}
                  normalize={normalizeAllowedTabs}
                  selectAll={selectAllXamTabs}
                  renderTabExtra={tabKey => (
                    <XamTabPermissionToggle
                      parentKey={tabKey}
                      allowedTabs={selectedUser.allowedTabs}
                      onChange={next => updateSelectedUser('allowedTabs', next)}
                    />
                  )}
                />
              )}

              {isAdminRole && (
                <ModuleTabAccessPicker
                  label="Admin Section Access"
                  icon="mdi:shield-crown-outline"
                  modules={BILLOPS_ADMIN_MODULES}
                  selected={selectedUser.allowedTabs}
                  onChange={next => updateSelectedUser('allowedTabs', next)}
                  helpText="Leave everything unchecked to grant the full admin sidebar. Selecting specific sections restricts the user to only those."
                />
              )}

              {/* ── Cloud Account Access ──
                   The accounts the user may see and "Login As". One picker, two catalogs:
                   - Admin roles work across customers, so the catalog is every account in the
                     platform; the section always renders (including while creating the user),
                     with loading and empty states so it never silently vanishes.
                   - Customer users are confined to their own client, which only exists once the
                     user does, so theirs shows only while editing and only when that client has
                     accounts. */}
              {(isAdminRole || clientAccounts.length > 0 || accountsLoading || accountsError) && (
                <div className="pt-1">
                  <div className="flex items-center justify-between mb-2">
                    <FormLabel icon="mdi:cloud-lock-outline" text="Cloud Account Access" />
                    <div className="flex items-center gap-2 mb-1.5">
                      <button
                        type="button"
                        onClick={() => {
                          // Select all currently-visible (filtered) accounts, merged with any
                          // already-selected accounts hidden by the active search.
                          const visibleIds = filteredClientAccounts.map(a => a.id);
                          const merged = Array.from(new Set([...selectedUser.accountAccessIds, ...visibleIds]));
                          updateSelectedUser('accountAccessIds', merged);
                        }}
                        className="text-[10px] font-semibold text-[rgba(0,0,0,0.6)] hover:text-black"
                      >
                        {accountSearch.trim() ? 'Select shown' : 'Select all'}
                      </button>
                      <span className="text-[rgba(0,0,0,0.15)]">|</span>
                      <button
                        type="button"
                        onClick={() => updateSelectedUser('accountAccessIds', [])}
                        className="text-[10px] font-semibold text-rose-500 hover:text-rose-700"
                      >
                        Clear
                      </button>
                    </div>
                  </div>
                  <p className="text-[11px] text-[rgba(0,0,0,0.4)] mb-3">
                    {isAdminRole
                      ? 'Leave all unchecked to let this admin see and Login As every cloud account. Selecting specific accounts restricts their Accounts, Tickets, Billing and Credit views — and who they can Login As — to those accounts and the clients that own them.'
                      : 'Leave all unchecked to grant access to every cloud account for this client. Selecting specific accounts restricts the user to only those.'}
                  </p>

                  {accountsLoading ? (
                    <div className="flex flex-col items-center justify-center gap-2 py-8 text-[rgba(0,0,0,0.35)]">
                      <Icon icon="mdi:loading" width={22} className="animate-spin" />
                      <span className="text-[11px]">Loading cloud accounts…</span>
                    </div>
                  ) : accountsError ? (
                    <div className="flex flex-col items-center justify-center gap-1 py-8 text-rose-500">
                      <Icon icon="mdi:cloud-alert" width={22} />
                      <span className="text-[11px]">Couldn't load cloud accounts</span>
                      <span className="text-[10px] text-center max-w-[360px] text-[rgba(0,0,0,0.45)]">
                        The account list is temporarily unavailable. You can still save the user now and set their account access later by editing them.
                      </span>
                    </div>
                  ) : clientAccounts.length === 0 ? (
                    <div className="flex flex-col items-center justify-center gap-1 py-8 text-[rgba(0,0,0,0.35)]">
                      <Icon icon="mdi:cloud-off-outline" width={22} />
                      <span className="text-[11px]">No cloud accounts available to assign</span>
                      <span className="text-[10px] text-center max-w-[360px]">
                        The user will be able to see every account once some are connected. Add cloud accounts from the Accounts page.
                      </span>
                    </div>
                  ) : (
                    <>
                      {/* Search box — filters the list below; scales to hundreds of accounts. */}
                      <div className="relative mb-2">
                        <Icon
                          icon="mdi:magnify"
                          width={14}
                          color="rgba(0,0,0,0.35)"
                          className="absolute left-2.5 top-1/2 -translate-y-1/2 pointer-events-none"
                        />
                        <input
                          type="text"
                          value={accountSearch}
                          onChange={e => setAccountSearch(e.target.value)}
                          placeholder={isAdminRole
                            ? 'Search accounts by name, client, provider, or account ID'
                            : 'Search accounts by name, provider, or account ID'}
                          className="w-full pl-8 pr-8 py-2 text-[11px] border border-[rgba(0,0,0,0.12)] rounded-lg focus:outline-none focus:border-[rgba(0,0,0,0.4)] placeholder:text-[rgba(0,0,0,0.35)]"
                        />
                        {accountSearch && (
                          <button
                            type="button"
                            onClick={() => setAccountSearch('')}
                            className="absolute right-2 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.35)] hover:text-black"
                            aria-label="Clear account search"
                          >
                            <Icon icon="mdi:close-circle" width={14} />
                          </button>
                        )}
                      </div>
                      <p className="text-[10px] text-[rgba(0,0,0,0.4)] mb-2">
                        Showing {filteredClientAccounts.length} of {clientAccounts.length} account{clientAccounts.length === 1 ? '' : 's'}
                        {selectedUser.accountAccessIds.length > 0 ? ` · ${selectedUser.accountAccessIds.length} selected` : ''}
                      </p>
                      {filteredClientAccounts.length === 0 ? (
                        <div className="flex flex-col items-center justify-center gap-1 py-6 text-[rgba(0,0,0,0.35)]">
                          <Icon icon="mdi:cloud-search-outline" width={22} />
                          <span className="text-[11px]">No accounts match "{accountSearch}"</span>
                        </div>
                      ) : (
                        <div className="grid grid-cols-1 sm:grid-cols-2 gap-1.5 max-h-[280px] overflow-y-auto pr-1">
                          {filteredClientAccounts.map(acc => {
                            const checked = selectedUser.accountAccessIds.some(id => String(id) === String(acc.id));
                            return (
                              <label
                                key={acc.id}
                                className="flex items-center gap-2 px-2.5 py-2 border border-[rgba(0,0,0,0.06)] rounded-lg cursor-pointer hover:bg-[rgba(0,0,0,0.02)] transition-colors"
                              >
                                <input
                                  type="checkbox"
                                  checked={checked}
                                  onChange={() => toggleAccount(acc.id)}
                                  className="w-3.5 h-3.5 rounded accent-[rgba(0,0,0,0.88)] cursor-pointer flex-shrink-0"
                                />
                                <Icon icon="mdi:cloud-outline" width={13} color="rgba(0,0,0,0.45)" />
                                <span className="flex flex-col min-w-0">
                                  <span className="text-[11px] font-medium text-[rgba(0,0,0,0.7)] truncate">{acc.name}</span>
                                  <span className="text-[10px] text-[rgba(0,0,0,0.4)] truncate">
                                    {/* Client first on the cross-client list: it is what
                                        disambiguates two accounts both called "prod". Absent on
                                        the single-client list, where it would just repeat the
                                        form's Client Name. */}
                                    {acc.clientName ? `${acc.clientName} · ` : ''}
                                    {acc.provider}{acc.accountId ? ` · ${acc.accountId}` : ''}
                                  </span>
                                </span>
                              </label>
                            );
                          })}
                        </div>
                      )}
                    </>
                  )}
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

      {/* ── Trial Management (tenant-level, keyed by clientId) ── */}
      <TrialManagementSection />

      {/* ── Main Table Card ── */}
      <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl shadow-sm overflow-hidden">
        {loading ? (
          <div className="flex flex-col items-center justify-center py-16 gap-3">
            <Icon icon="mdi:loading" width={28} color="rgba(0,0,0,0.25)" className="animate-spin" />
            <p className="text-sm text-[rgba(0,0,0,0.4)]">Loading users…</p>
          </div>
        ) : users.length === 0 && clients.length === 0 ? (
          // Only when there is genuinely nothing: an empty client still deserves its row, so it
          // can be given a first user or removed.
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
                    {paginatedGroups.map(({ clientName, clientId, primaries, subMap, totalUsers, cloudAccountCount }) => {
                      const clientExpanded = isClientExpanded(clientName);
                      const totalInClient  = primaries.reduce((s, p) => s + 1 + (subMap.get(String(p.id))?.length ?? 0), 0);
                      // Deletable only when genuinely empty — the server refuses anything else,
                      // because deleting a populated client would cascade through its users and
                      // skip the per-user reference cleanup. Say why instead of offering a 409.
                      const deleteBlockedReason =
                        clientId == null     ? 'These users have no client record to delete.'
                        : totalUsers > 0     ? `Delete its ${totalUsers} user${totalUsers !== 1 ? 's' : ''} first.`
                        : cloudAccountCount > 0 ? `Disconnect its ${cloudAccountCount} cloud account${cloudAccountCount !== 1 ? 's' : ''} first.`
                        : null;
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
                                  {totalUsers === 0 ? (
                                    // Nothing to expand — keep the row's alignment without a chevron.
                                    <span className="w-3.5 flex-shrink-0" />
                                  ) : (
                                    <Icon
                                      icon={clientExpanded ? 'mdi:chevron-down' : 'mdi:chevron-right'}
                                      width={14}
                                      color="rgba(0,0,0,0.35)"
                                    />
                                  )}
                                  <div className="w-8 h-8 rounded-xl bg-[rgba(0,0,0,0.88)] text-white flex items-center justify-center text-[11px] font-bold flex-shrink-0">
                                    {getInitials(clientName)}
                                  </div>
                                  <span className="text-sm font-bold text-[rgba(0,0,0,0.88)]">{clientName}</span>
                                  <span className="px-2 py-0.5 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.5)] rounded-full text-[10px] font-bold">
                                    {totalUsers === 0
                                      ? 'No users yet'
                                      : `${primaries.length} primary · ${totalInClient} total`}
                                  </span>
                                </div>
                                <div className="flex items-center gap-1.5 flex-shrink-0">
                                  <button
                                    type="button"
                                    onClick={e => { e.stopPropagation(); handleNewUserForClient(clientName); }}
                                    className="flex items-center gap-1.5 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] hover:bg-black text-white rounded-lg text-[10px] font-bold transition-colors"
                                  >
                                    <Icon icon="mdi:account-plus-outline" width={11} />
                                    Add User
                                  </button>
                                  <button
                                    type="button"
                                    disabled={deleteBlockedReason !== null}
                                    title={deleteBlockedReason ?? `Delete client ${clientName}`}
                                    aria-label={`Delete client ${clientName}`}
                                    onClick={e => {
                                      e.stopPropagation();
                                      if (clientId != null) setPendingDeleteClient({ id: clientId, name: clientName });
                                    }}
                                    className="flex items-center gap-1.5 px-3 py-1.5 bg-rose-50 border border-rose-100 text-rose-500 hover:bg-rose-100 rounded-lg text-[10px] font-bold transition-colors disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:bg-rose-50"
                                  >
                                    <Icon icon="mdi:trash-can-outline" width={11} />
                                    Delete Client
                                  </button>
                                </div>
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
                                        onClick={() => openMoveModal(primary)}
                                        title="Move to another client"
                                        className="inline-flex items-center gap-1 px-2 py-1.5 text-[10px] font-semibold bg-indigo-50 border border-indigo-100 text-indigo-600 hover:bg-indigo-100 rounded-md transition-colors"
                                      >
                                        <Icon icon="mdi:swap-horizontal" width={11} />
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
                                            onClick={() => openMoveModal(sub)}
                                            title="Move to another client"
                                            className="inline-flex items-center gap-1 px-2 py-1.5 text-[10px] font-semibold bg-indigo-50 border border-indigo-100 text-indigo-600 hover:bg-indigo-100 rounded-md transition-colors"
                                          >
                                            <Icon icon="mdi:swap-horizontal" width={11} />
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
