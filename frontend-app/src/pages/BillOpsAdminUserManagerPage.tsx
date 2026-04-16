/**
 * BillOps Admin User Manager Page
 *
 * Admin interface for managing BillOps users
 * Converted from: user-manager.html
 * 
 * Features:
 * - View all BillOps users
 * - Create new users with clientName, username, email, password, role
 * - Edit existing users
 * - Delete users
 * - Modern glassmorphism UI design
 * - React sidebar integration
 *
 * Role: ROLE_BILLOPS_ADMIN only
 *
 * What stayed the same:
 * - Same API endpoints
 * - Same business logic
 * - Same data structure
 *
 * What changed:
 * - Migrated from Alpine.js to React with TypeScript
 * - Enhanced modern UI with smooth animations
 * - Uses React sidebar component
 */

import React, { useEffect, useState, useMemo } from 'react';
import ReactDOM from 'react-dom';
import { useTopbar } from '../contexts/TopbarContext';
import { useBillOpsUserManager } from '../hooks/useBillOpsUserManager';
import type { BillOpsUser, BillOpsRole } from '../types/billopsUserManager';
import NotificationModal from '../components/Shared/NotificationModal';
import { AlertDialog } from '../components/ui';
import '../styles/billopsAdminUserManager.css';

const PAGE_SIZE = 10;

const BillOpsAdminUserManagerPage: React.FC = () => {
    const {
        users,
        loading,
        error,
        isEditing,
        selectedUser,
        roles,
        newUser,
        editUser,
        cancelEdit,
        saveUser,
        deleteUser,
        updateSelectedUser,
        notification,
        clearNotification,
        pendingDeleteId,
        confirmDelete,
        cancelDelete,
    } = useBillOpsUserManager();

    const [searchQuery, setSearchQuery] = useState('');
    const [filterRole, setFilterRole] = useState<BillOpsRole | ''>('');
    const [sortBy, setSortBy] = useState<'name-asc' | 'name-desc' | 'count-desc'>('name-asc');
    const [expandedClients, setExpandedClients] = useState<Set<string>>(new Set());
    const [currentPage, setCurrentPage] = useState(1);

    /**
     * Get initials for avatar
     */
    const getInitials = (name: string): string => {
        return name.substring(0, 2).toUpperCase();
    };

    /**
     * Format role for display
     */
    const formatRole = (role: string): string => {
        return role.replace('ROLE_', '').replace(/_/g, ' ');
    };

    /**
     * Get role badge class
     */
    const getRoleBadgeClass = (role: BillOpsRole): string => {
        if (role === 'ROLE_BILLOPS_ADMIN') return 'admin';
        if (role === 'ROLE_BILLOPS_ADMIN_SUB') return 'admin';
        if (role === 'ROLE_XAMOPS') return 'xamops';
        return 'user';
    };

    /** Toggle a client group open/closed */
    const toggleClient = (clientName: string) => {
        setExpandedClients(prev => {
            const next = new Set(prev);
            if (next.has(clientName)) next.delete(clientName);
            else next.add(clientName);
            return next;
        });
    };

    /** Toggle a primary user's sub-user list open/closed */
    const [expandedPrimaryUsers, setExpandedPrimaryUsers] = useState<Set<string>>(new Set());
    const togglePrimaryUser = (userId: string) => {
        setExpandedPrimaryUsers(prev => {
            const next = new Set(prev);
            if (next.has(userId)) next.delete(userId);
            else next.add(userId);
            return next;
        });
    };

    /** Open create form pre-filled with clientName */
    const handleNewUserForClient = (clientName: string) => {
        newUser();
        updateSelectedUser('clientName', clientName);
    };

    /** Open create form pre-filled as sub-user under a primary user */
    const handleNewSubUser = (primaryUser: BillOpsUser) => {
        newUser();
        updateSelectedUser('clientName', primaryUser.clientName);
        updateSelectedUser('parentId', primaryUser.id);
    };

    /**
     * Build 3-level tree: client → primary users → sub-users
     * Primary = no parentId; sub-user = has parentId pointing to a primary user's id
     */
    const clientGroups = useMemo(() => {
        const q = searchQuery.toLowerCase().trim();

        const matchesSearch = (user: BillOpsUser) => {
            if (!q) return true;
            return (
                user.clientName?.toLowerCase().includes(q) ||
                user.username?.toLowerCase().includes(q) ||
                user.email?.toLowerCase().includes(q) ||
                user.designation?.toLowerCase().includes(q)
            );
        };

        // Build sub-user lookup map: parentId → sub-users[]
        // String() normalises: backend returns Long (number), frontend id is string
        const subUserMap = new Map<string, BillOpsUser[]>();
        for (const user of users.filter(u => !!u.parentId)) {
            const key = String(user.parentId!);
            if (!subUserMap.has(key)) subUserMap.set(key, []);
            subUserMap.get(key)!.push(user);
        }

        // Group primary users (no parentId) by clientName
        const clientMap = new Map<string, BillOpsUser[]>();
        for (const user of users.filter(u => !u.parentId)) {
            const key = user.clientName || 'Unknown';
            if (!clientMap.has(key)) clientMap.set(key, []);
            clientMap.get(key)!.push(user);
        }

        // Apply search + role filter, keeping primaries that match or have matching sub-users
        let entries = Array.from(clientMap.entries()).map(([clientName, primaries]) => {
            const filtered = primaries.filter(p => {
                if (filterRole !== '' && p.role !== filterRole) return false;
                const subs = subUserMap.get(String(p.id)) || [];
                return matchesSearch(p) || subs.some(s => matchesSearch(s));
            });
            return { clientName, primaries: filtered, subUserMap } as const;
        }).filter(g => g.primaries.length > 0);

        if (sortBy === 'name-asc') entries.sort((a, b) => a.clientName.localeCompare(b.clientName));
        else if (sortBy === 'name-desc') entries.sort((a, b) => b.clientName.localeCompare(a.clientName));
        else if (sortBy === 'count-desc') entries.sort((a, b) => b.primaries.length - a.primaries.length);

        return entries;
    }, [users, searchQuery, filterRole, sortBy]);

    // Reset to page 1 whenever filters/sort change
    useEffect(() => { setCurrentPage(1); }, [searchQuery, filterRole, sortBy]);

    const totalPages = Math.ceil(clientGroups.length / PAGE_SIZE);
    const paginatedGroups = useMemo(() => {
        const start = (currentPage - 1) * PAGE_SIZE;
        return clientGroups.slice(start, start + PAGE_SIZE);
    }, [clientGroups, currentPage]);

    const totalFilteredCount = useMemo(() =>
        clientGroups.reduce((sum, g) =>
            sum + g.primaries.length + g.primaries.reduce((s, p) => s + (g.subUserMap.get(String(p.id))?.length ?? 0), 0), 0),
        [clientGroups]);

    const isClientExpanded = (clientName: string) =>
        expandedClients.has(clientName) || !!searchQuery || !!filterRole;

    const isPrimaryExpanded = (userId: string, subs: BillOpsUser[]) =>
        subs.length > 0 && (expandedPrimaryUsers.has(userId) || !!searchQuery || !!filterRole);

    /**
     * Stats counts
     */
    const stats = useMemo(() => ({
        total: users.length,
        admin: users.filter(u => u.role === 'ROLE_BILLOPS_ADMIN' || u.role === 'ROLE_BILLOPS_ADMIN_SUB').length,
        billops: users.filter(u => u.role === 'ROLE_BILLOPS').length,
        xamops: users.filter(u => u.role === 'ROLE_XAMOPS').length,
    }), [users]);

    const { setActions, clearActions } = useTopbar();

    // Set TopBar actions
    useEffect(() => {
        setActions(
            <div className="flex items-center gap-3">
                <button
                    onClick={newUser}
                    className="flex items-center gap-2 px-4 py-2 bg-gradient-to-r from-indigo-500 to-purple-600 text-white rounded-xl text-sm font-bold hover:shadow-lg hover:shadow-indigo-500/20 active:scale-95 transition-all"
                >
                    <i className="fas fa-user-plus"></i>
                    <span>Create New User</span>
                </button>
            </div>
        );

        return () => clearActions();
    }, [setActions, clearActions, newUser]);

    return (
        <div className="user-manager-page">
            <AlertDialog
                isOpen={!!pendingDeleteId}
                title="Delete user?"
                description="This action cannot be undone."
                confirmLabel="Delete"
                variant="danger"
                onConfirm={confirmDelete}
                onCancel={cancelDelete}
            />
            {/* Error State */}
            {error && (
                <div className="user-manager-error">
                    <i className="fas fa-exclamation-circle"></i>
                    <p>{error}</p>
                </div>
            )}

            {/* Edit/Create Form — portal so it's always centered in viewport regardless of scroll */}
            {isEditing && ReactDOM.createPortal(
                <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
                    <div className="fixed inset-0 bg-black/50 backdrop-blur-sm" onClick={cancelEdit}></div>
                    <div className="relative user-manager-form-card z-10 w-full max-w-2xl max-h-[90vh] overflow-y-auto" onClick={(e) => e.stopPropagation()}>
                        <div className="user-manager-form-header">
                            <i className="fas fa-user-edit"></i>
                            <h2>{selectedUser.id ? 'Edit User' : 'Create User'}</h2>
                        </div>

                        <form onSubmit={(e) => { e.preventDefault(); saveUser(); }}>
                            <div className="user-manager-form-grid">
                                <div className="user-manager-form-group">
                                    <label className="user-manager-form-label">Client Name</label>
                                    <input
                                        type="text"
                                        value={selectedUser.clientName}
                                        required
                                        className="user-manager-form-input"
                                        placeholder="Enter client name"
                                        onChange={(e) => updateSelectedUser('clientName', e.target.value)}
                                    />
                                </div>

                                <div className="user-manager-form-group">
                                    <label className="user-manager-form-label">Username</label>
                                    <input
                                        type="text"
                                        value={selectedUser.username}
                                        required
                                        className="user-manager-form-input"
                                        placeholder="Enter username"
                                        onChange={(e) => updateSelectedUser('username', e.target.value)}
                                    />
                                </div>

                                <div className="user-manager-form-group">
                                    <label className="user-manager-form-label">Email</label>
                                    <input
                                        type="email"
                                        value={selectedUser.email}
                                        required
                                        className="user-manager-form-input"
                                        placeholder="Enter email address"
                                        onChange={(e) => updateSelectedUser('email', e.target.value)}
                                    />
                                </div>

                                <div className="user-manager-form-group">
                                    <label className="user-manager-form-label">Role</label>
                                    <select
                                        value={selectedUser.role}
                                        required
                                        className="user-manager-form-select"
                                        onChange={(e) => updateSelectedUser('role', e.target.value)}
                                    >
                                        <option value="" disabled>Select Role</option>
                                        {roles.map((role) => (
                                            <option key={role} value={role}>
                                                {formatRole(role)}
                                            </option>
                                        ))}
                                    </select>
                                </div>

                                <div className="user-manager-form-group">
                                    <label className="user-manager-form-label">Contact Number</label>
                                    <input
                                        type="tel"
                                        value={selectedUser.contact}
                                        className="user-manager-form-input"
                                        placeholder="Enter contact number"
                                        onChange={(e) => updateSelectedUser('contact', e.target.value)}
                                    />
                                </div>

                                <div className="user-manager-form-group">
                                    <label className="user-manager-form-label">Designation</label>
                                    <input
                                        type="text"
                                        value={selectedUser.designation}
                                        className="user-manager-form-input"
                                        placeholder="e.g. Cloud Engineer"
                                        onChange={(e) => updateSelectedUser('designation', e.target.value)}
                                    />
                                </div>

                                <div className="user-manager-form-group full-width">
                                    <label className="user-manager-form-label">Password</label>
                                    <input
                                        type="password"
                                        value={selectedUser.password}
                                        required={!selectedUser.id}
                                        className="user-manager-form-input"
                                        placeholder={selectedUser.id ? 'Leave blank to keep current password' : 'Enter password'}
                                        onChange={(e) => updateSelectedUser('password', e.target.value)}
                                    />
                                </div>

                                {/* Sub-user indicator — shown read-only when parentId is pre-filled */}
                                {selectedUser.parentId && (
                                    <div className="user-manager-form-group full-width">
                                        <label className="user-manager-form-label">Sub-user of</label>
                                        <div className="flex items-center gap-2 px-3 py-2 bg-emerald-50 border border-emerald-200 rounded-lg text-xs text-emerald-700 font-semibold">
                                            <i className="fas fa-sitemap text-emerald-500"></i>
                                            This user will be created as a sub-user (Parent ID: {selectedUser.parentId})
                                            <button type="button" onClick={() => updateSelectedUser('parentId', '')} className="ml-auto text-emerald-400 hover:text-rose-500">
                                                <i className="fas fa-times"></i>
                                            </button>
                                        </div>
                                    </div>
                                )}
                            </div>

                            <div className="user-manager-form-actions">
                                <button
                                    type="button"
                                    onClick={cancelEdit}
                                    className="user-manager-btn-cancel"
                                >
                                    Cancel
                                </button>
                                <button
                                    type="submit"
                                    className="user-manager-btn-save"
                                >
                                    Save User
                                </button>
                            </div>
                        </form>
                    </div>
                </div>,
                document.body
            )}

            {/* Stats Summary */}
            {!loading && users.length > 0 && (
                <div className="user-manager-stats">
                    <div className="user-manager-stat-card total">
                        <i className="fas fa-users"></i>
                        <div>
                            <span className="user-manager-stat-value">{stats.total}</span>
                            <span className="user-manager-stat-label">Total Users</span>
                        </div>
                    </div>
                    <div className="user-manager-stat-card admin">
                        <i className="fas fa-crown"></i>
                        <div>
                            <span className="user-manager-stat-value">{stats.admin}</span>
                            <span className="user-manager-stat-label">Admins</span>
                        </div>
                    </div>
                    <div className="user-manager-stat-card billops">
                        <i className="fas fa-user"></i>
                        <div>
                            <span className="user-manager-stat-value">{stats.billops}</span>
                            <span className="user-manager-stat-label">BillOps</span>
                        </div>
                    </div>
                    <div className="user-manager-stat-card xamops">
                        <i className="fas fa-cloud"></i>
                        <div>
                            <span className="user-manager-stat-value">{stats.xamops}</span>
                            <span className="user-manager-stat-label">XamOps</span>
                        </div>
                    </div>
                </div>
            )}

            {/* Users Table */}
            <div className="user-manager-table-card">
                {loading ? (
                    <div className="user-manager-loading">
                        <div className="user-manager-spinner"></div>
                        <p>Loading users...</p>
                    </div>
                ) : users.length === 0 ? (
                    <div className="user-manager-empty">
                        <div className="user-manager-empty-icon">
                            <i className="fas fa-users"></i>
                        </div>
                        <h3>No Users Found</h3>
                        <p>Create your first user to get started.</p>
                    </div>
                ) : (
                    <>
                        {/* Search & Filter Toolbar */}
                        <div className="user-manager-toolbar">
                            <div className="user-manager-search-wrapper">
                                <i className="fas fa-search user-manager-search-icon"></i>
                                <input
                                    type="text"
                                    className="user-manager-search-input"
                                    placeholder="Search by name, username, email or designation..."
                                    value={searchQuery}
                                    onChange={(e) => setSearchQuery(e.target.value)}
                                />
                                {searchQuery && (
                                    <button
                                        className="user-manager-search-clear"
                                        onClick={() => setSearchQuery('')}
                                        title="Clear search"
                                    >
                                        <i className="fas fa-times"></i>
                                    </button>
                                )}
                            </div>

                            {/* Role filter */}
                            <select
                                className="user-manager-filter-select"
                                value={filterRole}
                                onChange={(e) => setFilterRole(e.target.value as BillOpsRole | '')}
                            >
                                <option value="">All Roles</option>
                                {roles.map((role) => (
                                    <option key={role} value={role}>{formatRole(role)}</option>
                                ))}
                            </select>

                            {/* Sort dropdown */}
                            <select
                                className="user-manager-filter-select"
                                value={sortBy}
                                onChange={(e) => setSortBy(e.target.value as typeof sortBy)}
                            >
                                <option value="name-asc">Name A → Z</option>
                                <option value="name-desc">Name Z → A</option>
                                <option value="count-desc">Most Users First</option>
                            </select>

                            {/* Expand / Collapse All */}
                            <div className="flex items-center gap-1.5">
                                <button
                                    className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-[10px] font-bold uppercase tracking-widest border border-slate-200 bg-white text-slate-600 hover:bg-slate-50 transition-all active:scale-95"
                                    onClick={() => setExpandedClients(new Set(clientGroups.map(g => g.clientName)))}
                                    title="Expand all clients"
                                >
                                    <i className="fas fa-expand-alt text-[9px]"></i>
                                    All
                                </button>
                                <button
                                    className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-[10px] font-bold uppercase tracking-widest border border-slate-200 bg-white text-slate-600 hover:bg-slate-50 transition-all active:scale-95"
                                    onClick={() => setExpandedClients(new Set())}
                                    title="Collapse all clients"
                                >
                                    <i className="fas fa-compress-alt text-[9px]"></i>
                                    None
                                </button>
                            </div>

                            {(searchQuery || filterRole) && (
                                <button
                                    className="user-manager-clear-filters"
                                    onClick={() => { setSearchQuery(''); setFilterRole(''); }}
                                >
                                    <i className="fas fa-filter-circle-xmark"></i>
                                    Clear Filters
                                </button>
                            )}
                            <span className="user-manager-results-count">
                                {totalFilteredCount === users.length
                                    ? `${clientGroups.length} client${clientGroups.length !== 1 ? 's' : ''} · ${users.length} users`
                                    : `${totalFilteredCount} of ${users.length} users`}
                                {totalPages > 1 && ` (page ${currentPage}/${totalPages})`}
                            </span>
                        </div>

                        {clientGroups.length === 0 ? (
                            <div className="user-manager-empty">
                                <div className="user-manager-empty-icon">
                                    <i className="fas fa-search"></i>
                                </div>
                                <h3>No Results Found</h3>
                                <p>Try adjusting your search or filter criteria.</p>
                            </div>
                        ) : (
                            <div className="user-manager-table-container">
                                <table className="user-manager-table">
                                    <thead>
                                        <tr>
                                            <th>User</th>
                                            <th>Email</th>
                                            <th>Contact</th>
                                            <th>Designation</th>
                                            <th>Role</th>
                                            <th>Actions</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {paginatedGroups.map(({ clientName, primaries, subUserMap }) => {
                                            const clientExpanded = isClientExpanded(clientName);
                                            const totalInClient = primaries.reduce((s, p) => s + 1 + (subUserMap.get(String(p.id))?.length ?? 0), 0);
                                            return (
                                                <React.Fragment key={clientName}>
                                                    {/* ── Level 1: Client header ── */}
                                                    <tr
                                                        className="cursor-pointer select-none bg-slate-100/70 hover:bg-indigo-50/50 transition-colors border-t-2 border-slate-200"
                                                        onClick={() => toggleClient(clientName)}
                                                    >
                                                        <td colSpan={6} className="px-4 py-3">
                                                            <div className="flex items-center justify-between">
                                                                <div className="flex items-center gap-3">
                                                                    <i className={`fas fa-chevron-${clientExpanded ? 'down' : 'right'} text-[10px] text-slate-400 w-3`}></i>
                                                                    <div className="user-manager-avatar">
                                                                        {getInitials(clientName)}
                                                                    </div>
                                                                    <span className="font-bold text-slate-800 text-sm">{clientName}</span>
                                                                    <span className="px-2 py-0.5 bg-indigo-50 text-indigo-600 rounded-full text-[10px] font-black border border-indigo-100 leading-none">
                                                                        {primaries.length} primary · {totalInClient} total
                                                                    </span>
                                                                </div>
                                                                <button
                                                                    type="button"
                                                                    onClick={(e) => { e.stopPropagation(); handleNewUserForClient(clientName); }}
                                                                    className="flex items-center gap-1.5 px-3 py-1.5 bg-indigo-600 text-white rounded-lg text-[10px] font-bold hover:bg-indigo-700 transition-all active:scale-95 shadow-sm shadow-indigo-200"
                                                                >
                                                                    <i className="fas fa-user-plus text-[9px]"></i>
                                                                    Add User
                                                                </button>
                                                            </div>
                                                        </td>
                                                    </tr>

                                                    {/* ── Level 2: Primary users ── */}
                                                    {clientExpanded && primaries.map((primary: BillOpsUser) => {
                                                        const subs = subUserMap.get(String(primary.id)) || [];
                                                        const primaryExpanded = isPrimaryExpanded(primary.id, subs);
                                                        return (
                                                            <React.Fragment key={primary.id}>
                                                                <tr className="bg-white border-l-4 border-l-indigo-300/60 hover:bg-indigo-50/20 transition-colors">
                                                                    <td className="pl-10 pr-4 py-2.5">
                                                                        <div className="flex items-center gap-2.5">
                                                                            {/* expand/collapse sub-users if any */}
                                                                            {subs.length > 0 ? (
                                                                                <button
                                                                                    type="button"
                                                                                    onClick={() => togglePrimaryUser(primary.id)}
                                                                                    className="w-4 h-4 flex items-center justify-center text-indigo-400 hover:text-indigo-600 flex-shrink-0"
                                                                                >
                                                                                    <i className={`fas fa-chevron-${primaryExpanded ? 'down' : 'right'} text-[9px]`}></i>
                                                                                </button>
                                                                            ) : (
                                                                                <span className="w-4 flex-shrink-0"></span>
                                                                            )}
                                                                            <div className="w-8 h-8 rounded-xl bg-gradient-to-br from-indigo-100 to-purple-100 text-indigo-700 flex items-center justify-center text-[10px] font-black flex-shrink-0">
                                                                                {getInitials(primary.username)}
                                                                            </div>
                                                                            <div className="flex flex-col leading-tight">
                                                                                <span className="text-xs font-bold text-slate-700">@{primary.username}</span>
                                                                                {subs.length > 0 && (
                                                                                    <span className="text-[9px] text-indigo-400 font-semibold">{subs.length} sub-user{subs.length !== 1 ? 's' : ''}</span>
                                                                                )}
                                                                            </div>
                                                                        </div>
                                                                    </td>
                                                                    <td className="px-4 py-2.5 text-sm text-slate-600">{primary.email}</td>
                                                                    <td className="px-4 py-2.5 text-sm text-slate-600">{primary.contact || <span className="text-gray-400">—</span>}</td>
                                                                    <td className="px-4 py-2.5 text-sm text-slate-600">{primary.designation || <span className="text-gray-400">—</span>}</td>
                                                                    <td className="px-4 py-2.5">
                                                                        <span className={`user-manager-role-badge ${getRoleBadgeClass(primary.role)}`}>
                                                                            <i className={`fas ${primary.role === 'ROLE_BILLOPS_ADMIN' ? 'fa-crown' : primary.role === 'ROLE_BILLOPS_ADMIN_SUB' ? 'fa-user-shield' : primary.role === 'ROLE_XAMOPS' ? 'fa-cloud' : 'fa-user'}`}></i>
                                                                            {formatRole(primary.role)}
                                                                        </span>
                                                                    </td>
                                                                    <td className="px-4 py-2.5">
                                                                        <div className="user-manager-actions">
                                                                            <button
                                                                                onClick={() => handleNewSubUser(primary)}
                                                                                className="user-manager-action-btn edit"
                                                                                title="Add sub-user"
                                                                                style={{ background: '#f0fdf4', color: '#16a34a', borderColor: '#bbf7d0' }}
                                                                            >
                                                                                <i className="fas fa-user-plus"></i>
                                                                            </button>
                                                                            <button onClick={() => editUser(primary)} className="user-manager-action-btn edit" title="Edit User">
                                                                                <i className="fas fa-edit"></i>
                                                                            </button>
                                                                            <button onClick={() => deleteUser(primary.id)} className="user-manager-action-btn delete" title="Delete User">
                                                                                <i className="fas fa-trash"></i>
                                                                            </button>
                                                                        </div>
                                                                    </td>
                                                                </tr>

                                                                {/* ── Level 3: Sub-users ── */}
                                                                {primaryExpanded && subs.map((sub: BillOpsUser) => (
                                                                    <tr key={sub.id} className="bg-slate-50/50 border-l-4 border-l-emerald-200/60 hover:bg-emerald-50/20 transition-colors">
                                                                        <td className="pl-20 pr-4 py-2">
                                                                            <div className="flex items-center gap-2.5">
                                                                                <div className="w-6 h-6 rounded-lg bg-gradient-to-br from-emerald-100 to-teal-100 text-emerald-700 flex items-center justify-center text-[9px] font-black flex-shrink-0">
                                                                                    {getInitials(sub.username)}
                                                                                </div>
                                                                                <span className="text-xs text-slate-500 font-semibold">@{sub.username}</span>
                                                                            </div>
                                                                        </td>
                                                                        <td className="px-4 py-2 text-sm text-slate-500">{sub.email}</td>
                                                                        <td className="px-4 py-2 text-sm text-slate-500">{sub.contact || <span className="text-gray-400">—</span>}</td>
                                                                        <td className="px-4 py-2 text-sm text-slate-500">{sub.designation || <span className="text-gray-400">—</span>}</td>
                                                                        <td className="px-4 py-2">
                                                                            <span className={`user-manager-role-badge ${getRoleBadgeClass(sub.role)}`}>
                                                                                <i className={`fas ${sub.role === 'ROLE_BILLOPS_ADMIN' ? 'fa-crown' : sub.role === 'ROLE_BILLOPS_ADMIN_SUB' ? 'fa-user-shield' : sub.role === 'ROLE_XAMOPS' ? 'fa-cloud' : 'fa-user'}`}></i>
                                                                                {formatRole(sub.role)}
                                                                            </span>
                                                                        </td>
                                                                        <td className="px-4 py-2">
                                                                            <div className="user-manager-actions">
                                                                                <button onClick={() => editUser(sub)} className="user-manager-action-btn edit" title="Edit User">
                                                                                    <i className="fas fa-edit"></i>
                                                                                </button>
                                                                                <button onClick={() => deleteUser(sub.id)} className="user-manager-action-btn delete" title="Delete User">
                                                                                    <i className="fas fa-trash"></i>
                                                                                </button>
                                                                            </div>
                                                                        </td>
                                                                    </tr>
                                                                ))}
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

            {/* Pagination Controls */}
            {totalPages > 1 && (
                <div className="flex items-center justify-center gap-2 py-2">
                    <button
                        onClick={() => setCurrentPage(p => Math.max(1, p - 1))}
                        disabled={currentPage === 1}
                        className="flex items-center gap-1.5 px-4 py-2 bg-white border border-slate-200 rounded-lg shadow-sm text-sm font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-40 disabled:cursor-not-allowed transition-all"
                    >
                        <i className="fas fa-chevron-left text-xs"></i>
                        Prev
                    </button>

                    {Array.from({ length: totalPages }, (_, i) => i + 1).map(page => {
                        const isActive = page === currentPage;
                        const isEdge = page === 1 || page === totalPages;
                        const isNearCurrent = Math.abs(page - currentPage) <= 1;
                        if (!isEdge && !isNearCurrent) {
                            if (page === 2 || page === totalPages - 1) {
                                return <span key={page} className="px-1 text-slate-400 text-sm select-none">…</span>;
                            }
                            return null;
                        }
                        return (
                            <button
                                key={page}
                                onClick={() => setCurrentPage(page)}
                                className={`w-9 h-9 rounded-lg text-sm font-bold transition-all ${
                                    isActive
                                        ? 'bg-indigo-600 text-white shadow-md shadow-indigo-200'
                                        : 'bg-white border border-slate-200 text-slate-700 hover:bg-slate-50 shadow-sm'
                                }`}
                            >
                                {page}
                            </button>
                        );
                    })}

                    <button
                        onClick={() => setCurrentPage(p => Math.min(totalPages, p + 1))}
                        disabled={currentPage === totalPages}
                        className="flex items-center gap-1.5 px-4 py-2 bg-white border border-slate-200 rounded-lg shadow-sm text-sm font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-40 disabled:cursor-not-allowed transition-all"
                    >
                        Next
                        <i className="fas fa-chevron-right text-xs"></i>
                    </button>
                </div>
            )}

            {/* Notification Modal */}
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
