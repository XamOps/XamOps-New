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
import { useTopbar } from '../contexts/TopbarContext';
import { useBillOpsUserManager } from '../hooks/useBillOpsUserManager';
import type { BillOpsUser, BillOpsRole } from '../types/billopsUserManager';
import NotificationModal from '../components/Shared/NotificationModal';
import { AlertDialog } from '../components/ui';
import '../styles/billopsAdminUserManager.css';

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

    /**
     * Get initials for avatar
     */
    const getInitials = (name: string): string => {
        return name.substring(0, 2).toUpperCase();
    };

    /**
     * Format role for display
     */
    const formatRole = (role: BillOpsRole): string => {
        return role.replace('ROLE_', '').replace(/_/g, ' ');
    };

    /**
     * Get role badge class
     */
    const getRoleBadgeClass = (role: BillOpsRole): string => {
        if (role === 'ROLE_BILLOPS_ADMIN') return 'admin';
        if (role === 'ROLE_XAMOPS') return 'xamops';
        return 'user';
    };

    /**
     * Client-side filtered + searched users
     */
    const filteredUsers = useMemo(() => {
        const q = searchQuery.toLowerCase().trim();
        return users.filter((user) => {
            const matchesRole = filterRole === '' || user.role === filterRole;
            if (!matchesRole) return false;
            if (!q) return true;
            return (
                user.clientName?.toLowerCase().includes(q) ||
                user.username?.toLowerCase().includes(q) ||
                user.email?.toLowerCase().includes(q) ||
                user.designation?.toLowerCase().includes(q)
            );
        });
    }, [users, searchQuery, filterRole]);

    /**
     * Stats counts
     */
    const stats = useMemo(() => ({
        total: users.length,
        admin: users.filter(u => u.role === 'ROLE_BILLOPS_ADMIN').length,
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

            {/* Edit/Create Form */}
            {isEditing && (
                <div className="user-manager-form-card">
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
                                    disabled={!!selectedUser.id}
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
                                {filteredUsers.length === users.length
                                    ? `${users.length} user${users.length !== 1 ? 's' : ''}`
                                    : `${filteredUsers.length} of ${users.length} users`}
                            </span>
                        </div>

                        {filteredUsers.length === 0 ? (
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
                                        {filteredUsers.map((user: BillOpsUser) => (
                                            <tr key={user.id}>
                                                <td>
                                                    <div className="user-manager-user-info">
                                                        <div className="user-manager-avatar">
                                                            {getInitials(user.clientName || user.username)}
                                                        </div>
                                                        <div className="user-manager-user-details">
                                                            <span className="user-manager-client-name">
                                                                {user.clientName}
                                                            </span>
                                                            <span className="user-manager-username">
                                                                @{user.username}
                                                            </span>
                                                        </div>
                                                    </div>
                                                </td>
                                                <td>{user.email}</td>
                                                <td className="text-sm text-gray-600">
                                                    {user.contact || <span className="text-gray-400">—</span>}
                                                </td>
                                                <td className="text-sm text-gray-600">
                                                    {user.designation || <span className="text-gray-400">—</span>}
                                                </td>
                                                <td>
                                                    <span className={`user-manager-role-badge ${getRoleBadgeClass(user.role)}`}>
                                                        <i className={`fas ${user.role === 'ROLE_BILLOPS_ADMIN' ? 'fa-crown' : user.role === 'ROLE_XAMOPS' ? 'fa-cloud' : 'fa-user'}`}></i>
                                                        {formatRole(user.role)}
                                                    </span>
                                                </td>
                                                <td>
                                                    <div className="user-manager-actions">
                                                        <button
                                                            onClick={() => editUser(user)}
                                                            className="user-manager-action-btn edit"
                                                            title="Edit User"
                                                        >
                                                            <i className="fas fa-edit"></i>
                                                        </button>
                                                        <button
                                                            onClick={() => deleteUser(user.id)}
                                                            className="user-manager-action-btn delete"
                                                            title="Delete User"
                                                        >
                                                            <i className="fas fa-trash"></i>
                                                        </button>
                                                    </div>
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                        )}
                    </>
                )}
            </div>

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
