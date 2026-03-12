/**
 * useBillOpsUserManager Hook
 *
 * Custom hook for BillOps Admin User Management operations (CRUD)
 * Converted from: user-manager.html
 * 
 * Features:
 * - Fetch all BillOps users
 * - Create new users
 * - Edit existing users
 * - Delete users
 * - CSRF token handling
 *
 * Role: ROLE_BILLOPS_ADMIN only
 *
 * What stayed the same:
 * - Same API endpoints as original HTML
 * - Same data structure
 * - Same business logic
 *
 * What changed:
 * - Migrated from Alpine.js to React with TypeScript
 * - Uses React state management
 * - Proper TypeScript types
 */

import { useState, useEffect, useCallback } from 'react';
import type { BillOpsUser, BillOpsUserFormData, BillOpsRole, UseBillOpsUserManagerResult } from '../types/billopsUserManager';

const EMPTY_FORM: BillOpsUserFormData = {
    id: '',
    clientName: '',
    username: '',
    email: '',
    password: '',
    role: '',
    contact: '',
    designation: '',
};

const AVAILABLE_ROLES: BillOpsRole[] = ['ROLE_BILLOPS', 'ROLE_BILLOPS_ADMIN', 'ROLE_XAMOPS'];

export const useBillOpsUserManager = (): UseBillOpsUserManagerResult => {
    // State
    const [users, setUsers] = useState<BillOpsUser[]>([]);
    const [loading, setLoading] = useState<boolean>(true);
    const [error, setError] = useState<string | null>(null);
    const [isEditing, setIsEditing] = useState<boolean>(false);
    const [selectedUser, setSelectedUser] = useState<BillOpsUserFormData>(EMPTY_FORM);
    const [notification, setNotification] = useState<{
        type: 'success' | 'error' | 'info';
        title: string;
        message: string;
    } | null>(null);
    const [pendingDeleteId, setPendingDeleteId] = useState<string | null>(null);

    /**
     * Clear current notification
     */
    const clearNotification = useCallback(() => {
        setNotification(null);
    }, []);

    /**
     * Get CSRF token from cookies
     */
    const getCsrfToken = useCallback((): string => {
        return document.cookie
            .split('; ')
            .find(row => row.startsWith('XSRF-TOKEN='))
            ?.split('=')[1] || '';
    }, []);

    /**
     * Fetch all users from API
     */
    const fetchUsers = useCallback(async () => {
        setLoading(true);
        setError(null);

        console.log('[UserManager] Fetching users...');

        try {
            const response = await fetch('/api/billops/users', {
                method: 'GET',
                credentials: 'include'
            });

            if (!response.ok) {
                throw new Error(`HTTP ${response.status}`);
            }

            const data = await response.json();
            console.log('[UserManager] Users fetched:', data.length);
            setUsers(data);
        } catch (err) {
            console.error('[UserManager] Failed to fetch users:', err);
            setError(err instanceof Error ? err.message : 'Failed to fetch users');
        } finally {
            setLoading(false);
        }
    }, []);

    /**
     * Initialize - fetch users on mount
     */
    useEffect(() => {
        fetchUsers();
    }, [fetchUsers]);

    /**
     * Start creating a new user
     */
    const newUser = useCallback(() => {
        setSelectedUser(EMPTY_FORM);
        setIsEditing(true);
    }, []);

    /**
     * Start editing an existing user
     */
    const editUser = useCallback((user: BillOpsUser) => {
        setSelectedUser({
            id: user.id,
            clientName: user.clientName,
            username: user.username,
            email: user.email,
            password: '',
            role: user.role,
            contact: user.contact || '',
            designation: user.designation || '',
        });
        setIsEditing(true);
    }, []);

    /**
     * Cancel editing
     */
    const cancelEdit = useCallback(() => {
        setIsEditing(false);
        setSelectedUser(EMPTY_FORM);
    }, []);

    /**
     * Save user (create or update)
     */
    const saveUser = useCallback(async () => {
        // Validation
        if (!selectedUser.username?.trim()) {
            alert('Username is required!');
            return;
        }
        if (!selectedUser.email?.trim()) {
            alert('Email is required!');
            return;
        }
        if (!selectedUser.role?.trim()) {
            alert('Role is required!');
            return;
        }
        if (!selectedUser.clientName?.trim()) {
            alert('Client Name is required!');
            return;
        }
        if (!selectedUser.id && !selectedUser.password?.trim()) {
            alert('Password is required for new users!');
            return;
        }

        console.log('[UserManager] Saving user:', selectedUser.id ? 'UPDATE' : 'CREATE');

        // Prepare payload
        const payload: Partial<BillOpsUserFormData> = { ...selectedUser };
        if (payload.id && !payload.password?.trim()) {
            delete payload.password;
        }

        const url = payload.id
            ? `/api/billops/users/${payload.id}`
            : '/api/billops/users';
        const method = payload.id ? 'PUT' : 'POST';

        try {
            const response = await fetch(url, {
                method,
                credentials: 'include',
                headers: {
                    'Content-Type': 'application/json',
                    'X-XSRF-TOKEN': getCsrfToken()
                },
                body: JSON.stringify(payload)
            });

            if (!response.ok) {
                const errorText = await response.text();
                console.error('[UserManager] Save failed:', response.status, errorText);
                setNotification({
                    type: 'error',
                    title: 'Save Failed',
                    message: errorText || 'An unexpected error occurred while saving the user.'
                });
                return; // Keep form open on error
            }

            console.log('[UserManager] User saved successfully');

            // Refresh users list and close form
            await fetchUsers();
            cancelEdit();

            setNotification({
                type: 'success',
                title: 'User Saved',
                message: `User ${selectedUser.username} has been saved successfully.`
            });
        } catch (err) {
            console.error('[UserManager] Save error:', err);
            setNotification({
                type: 'error',
                title: 'Operation Error',
                message: err instanceof Error ? err.message : 'An unexpected error occurred.'
            });
            // Don't close form on error
        }
    }, [selectedUser, getCsrfToken, fetchUsers, cancelEdit]);

    /**
     * Delete a user — opens confirmation dialog
     */
    const deleteUser = useCallback((id: string) => {
        setPendingDeleteId(id);
    }, []);

    const cancelDelete = useCallback(() => {
        setPendingDeleteId(null);
    }, []);

    const confirmDelete = useCallback(async () => {
        if (!pendingDeleteId) return;
        const id = pendingDeleteId;
        setPendingDeleteId(null);

        console.log('[UserManager] Deleting user:', id);

        try {
            const response = await fetch(`/api/billops/users/${id}`, {
                method: 'DELETE',
                credentials: 'include',
                headers: {
                    'X-XSRF-TOKEN': getCsrfToken()
                }
            });

            if (!response.ok) {
                throw new Error(`HTTP ${response.status}`);
            }

            console.log('[UserManager] User deleted successfully');
            await fetchUsers();

            setNotification({
                type: 'success',
                title: 'User Deleted',
                message: 'The user has been deleted successfully.'
            });
        } catch (err) {
            console.error('[UserManager] Delete failed:', err);
            setNotification({
                type: 'error',
                title: 'Delete Failed',
                message: err instanceof Error ? err.message : 'An error occurred while deleting the user.'
            });
        }
    }, [pendingDeleteId, getCsrfToken, fetchUsers]);

    /**
     * Update a field in the selected user form
     */
    const updateSelectedUser = useCallback((field: keyof BillOpsUserFormData, value: string) => {
        setSelectedUser(prev => ({
            ...prev,
            [field]: value
        }));
    }, []);

    return {
        // Data
        users,
        loading,
        error,

        // Form state
        isEditing,
        selectedUser,
        notification,

        // Confirm-delete state
        pendingDeleteId,
        confirmDelete,
        cancelDelete,

        // Available roles
        roles: AVAILABLE_ROLES,

        // Actions
        fetchUsers,
        newUser,
        editUser,
        cancelEdit,
        saveUser,
        deleteUser,
        updateSelectedUser,
        clearNotification
    };
};

export default useBillOpsUserManager;
