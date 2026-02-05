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

import React, { useEffect } from 'react';
import { useTopbar } from '../contexts/TopbarContext';
import { useBillOpsUserManager } from '../hooks/useBillOpsUserManager';
import type { BillOpsUser, BillOpsRole } from '../types/billopsUserManager';
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
    } = useBillOpsUserManager();

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
        return role.replace('ROLE_', '').replace('_', ' ');
    };

    /**
     * Check if role is admin
     */
    const isAdminRole = (role: BillOpsRole): boolean => {
        return role === 'ROLE_BILLOPS_ADMIN';
    };

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
                    <div className="user-manager-table-container">
                        <table className="user-manager-table">
                            <thead>
                                <tr>
                                    <th>User</th>
                                    <th>Email</th>
                                    <th>Role</th>
                                    <th>Actions</th>
                                </tr>
                            </thead>
                            <tbody>
                                {users.map((user: BillOpsUser) => (
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
                                        <td>
                                            <span className={`user-manager-role-badge ${isAdminRole(user.role) ? 'admin' : 'user'}`}>
                                                <i className={`fas ${isAdminRole(user.role) ? 'fa-crown' : 'fa-user'}`}></i>
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
            </div>
        </div>
    );
};

export default BillOpsAdminUserManagerPage;
