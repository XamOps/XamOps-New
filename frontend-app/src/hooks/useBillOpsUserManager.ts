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
import type { BillOpsUser, BillOpsClient, BillOpsUserFormData, BillOpsRole, ClientCloudAccount, UseBillOpsUserManagerResult } from '../types/billopsUserManager';

const EMPTY_FORM: BillOpsUserFormData = {
    id: '',
    clientName: '',
    username: '',
    email: '',
    password: '',
    role: '',
    contact: '',
    designation: '',
    parentId: '',
    allowedTabs: [],
    accountAccessIds: [],
};

/**
 * Which section-key vocabulary a role's `allowedTabs` is written in.
 *
 * XamOps keys and BillOps admin keys are stored in the same table, so switching a user's role
 * between the two families has to drop the selection — see `changeRole`. Roles with no scoped
 * sidebar map to null and carry no selection at all.
 */
const tabVocabularyOf = (role: BillOpsRole | ''): 'xamops' | 'billops-admin' | null => {
    if (role === 'ROLE_XAMOPS') return 'xamops';
    if (role === 'ROLE_BILLOPS_ADMIN' || role === 'ROLE_BILLOPS_ADMIN_SUB') return 'billops-admin';
    return null;
};

/**
 * Which set of cloud accounts a role's `accountAccessIds` is chosen from.
 *
 * A BillOps admin works across customers, so their picker offers every account in the platform.
 * Everyone else is confined to their own client and picks from that client's accounts. Changing
 * role between the two means the previous selection is no longer on offer — see `changeRole`.
 */
const accountCatalogOf = (role: BillOpsRole | ''): 'all-clients' | 'own-client' => (
    role === 'ROLE_BILLOPS_ADMIN' || role === 'ROLE_BILLOPS_ADMIN_SUB' ? 'all-clients' : 'own-client'
);

const AVAILABLE_ROLES: BillOpsRole[] = ['ROLE_BILLOPS', 'ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB', 'ROLE_XAMOPS'];

/**
 * Turns a failed move response into something readable in the notification dialog.
 *
 * The move endpoint answers with a plain-text reason, which is shown as-is. Anything that does
 * not reach that handler answers with Spring's JSON error body instead
 * ({@code {"timestamp":…,"status":404,"error":"Not Found","path":"…"}}), and dumping that raw
 * leaves an admin staring at JSON. A 404 in particular never means "bad input" here — the route
 * itself is missing, which in practice means the BillOps service is older than the UI calling it.
 */
const describeMoveFailure = (status: number, body: string): string => {
    if (status === 404) {
        return 'The server has no handler for this request (HTTP 404). The BillOps service is likely '
            + 'running an older build than this page — restart it and try again.';
    }
    return extractServerMessage(body) ?? `The move failed (HTTP ${status}).`;
};

/**
 * Pulls a readable message out of a response body.
 *
 * These endpoints answer with plain text in some places and JSON in others — the controller's own
 * `Map.of("message", …)` for the client conflict, Spring's `{"error":…}` for anything that never
 * reached a handler — so both keys are tried before giving up. Returns null when there is nothing
 * worth showing, letting the caller pick a status-specific fallback.
 */
const extractServerMessage = (body: string): string | null => {
    const text = (body || '').trim();
    if (!text) return null;
    if (text.startsWith('{') || text.startsWith('[')) {
        try {
            const parsed = JSON.parse(text);
            const message = parsed?.message ?? parsed?.error;
            return typeof message === 'string' && message.trim() ? message : null;
        } catch {
            // Not valid JSON after all — the raw text is still better copy than a status code.
            return text;
        }
    }
    return text;
};

/**
 * Validates the optional mobile number used for SMS / WhatsApp alerts.
 *
 * Mirrors the server-side rules in AppUserService.validateContact, which in turn defer to
 * PhoneNumbers.toE164 — the same code the sender runs. Kept deliberately loose on punctuation
 * because stored numbers legitimately contain spaces, dashes, brackets and a leading + or 0.
 *
 * @returns an error message, or null when the value is acceptable (including empty)
 */
const validateContactNumber = (contact: string | undefined): string | null => {
    if (!contact || !contact.trim()) return null; // optional — email-only user
    const raw = contact.trim();

    if (raw.length > 20) return 'Contact number must be 20 characters or fewer.';
    if (/[a-zA-Z]/.test(raw)) {
        return 'Contact number cannot contain letters. Remove any extension or note.';
    }

    // Strip punctuation the normaliser tolerates, then check it is a plausible subscriber number.
    let digits = raw.replace(/\D/g, '');
    if (digits.startsWith('00')) digits = digits.slice(2);
    else if (!raw.startsWith('+') && digits.startsWith('0')) digits = digits.slice(1);
    // Bare national numbers get the default dial code prepended server-side, so 10 digits is fine.
    if (digits.length < 8 || digits.length > 15) {
        return 'Enter a valid mobile number — for example 9876543210 or +91 98765 43210.';
    }
    return null;
};

export const useBillOpsUserManager = (): UseBillOpsUserManagerResult => {
    // State
    const [users, setUsers] = useState<BillOpsUser[]>([]);
    const [clients, setClients] = useState<BillOpsClient[]>([]);
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
     * Fetch every client the admin may see — including those with no users, which the
     * user-derived client rows can never show.
     *
     * A failure here is deliberately not pushed into `error`: that banner replaces the whole
     * table, and the user list is still perfectly usable without the empty clients.
     */
    const fetchClients = useCallback(async () => {
        try {
            const response = await fetch('/api/billops/users/clients', {
                method: 'GET',
                credentials: 'include'
            });

            if (!response.ok) {
                throw new Error(`HTTP ${response.status}`);
            }

            const data = await response.json();
            setClients(Array.isArray(data) ? data : []);
        } catch (err) {
            console.error('[UserManager] Failed to fetch clients:', err);
        }
    }, []);

    /**
     * Initialize - fetch users and clients on mount
     */
    useEffect(() => {
        fetchUsers();
        fetchClients();
    }, [fetchUsers, fetchClients]);

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
            parentId: user.parentId != null ? String(user.parentId) : '',
            allowedTabs: user.allowedTabs || [],
            accountAccessIds: user.accountAccessIds || [],
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
        // Contact is optional, but a malformed one is worse than none: the backend would store it,
        // the notification sender would silently skip it, and it would surface only as "the
        // customer never got their alerts". Mirrors AppUserService.validateContact.
        const contactError = validateContactNumber(selectedUser.contact);
        if (contactError) {
            alert(contactError);
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

            // Refresh both lists and close the form. Clients too: saving a user against an unknown
            // client name creates that client server-side, and the counts on existing ones change.
            await Promise.all([fetchUsers(), fetchClients()]);
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
    }, [selectedUser, getCsrfToken, fetchUsers, fetchClients, cancelEdit]);

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
            // Clients too: deleting the last user of a client removes that client server-side.
            await Promise.all([fetchUsers(), fetchClients()]);

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
    }, [pendingDeleteId, getCsrfToken, fetchUsers, fetchClients]);

    /**
     * Move a user to a client and place them in its user tree. Returns true on success.
     *
     * `parentUserId` nests the user under that primary user of the destination client; omitting it
     * (or passing null) places them as a primary user. Passing the client the user already belongs
     * to re-parents them inside their own client.
     */
    const moveUserToClient = useCallback(async (
        userId: string,
        targetClientId: string | number,
        parentUserId?: string | number | null
    ): Promise<boolean> => {
        const asSubUser = parentUserId != null && parentUserId !== '';
        try {
            const response = await fetch(`/api/billops/users/${userId}/client`, {
                method: 'PATCH',
                credentials: 'include',
                headers: {
                    'Content-Type': 'application/json',
                    'X-XSRF-TOKEN': getCsrfToken()
                },
                body: JSON.stringify({
                    clientId: targetClientId,
                    parentUserId: asSubUser ? parentUserId : null
                })
            });

            if (!response.ok) {
                const errorText = await response.text();
                setNotification({
                    type: 'error',
                    title: 'Move Failed',
                    message: describeMoveFailure(response.status, errorText)
                });
                return false;
            }

            await fetchUsers();
            setNotification({
                type: 'success',
                title: 'User Moved',
                message: asSubUser
                    ? 'The user is now a sub-user of the selected primary user.'
                    : 'The user has been moved to the selected client.'
            });
            return true;
        } catch (err) {
            setNotification({
                type: 'error',
                title: 'Move Failed',
                message: err instanceof Error ? err.message : 'An unexpected error occurred.'
            });
            return false;
        }
    }, [getCsrfToken, fetchUsers]);

    /**
     * Delete an empty client. Returns true on success.
     *
     * The server refuses with 409 while the client still has users or cloud accounts — deleting a
     * populated client would cascade through its users and skip the per-user reference cleanup —
     * so that reason is surfaced verbatim rather than flattened into "delete failed".
     */
    const deleteClient = useCallback(async (clientId: number | string): Promise<boolean> => {
        try {
            const response = await fetch(`/api/billops/users/clients/${clientId}`, {
                method: 'DELETE',
                credentials: 'include',
                headers: {
                    'X-XSRF-TOKEN': getCsrfToken()
                }
            });

            if (!response.ok) {
                const body = await response.text();
                setNotification({
                    type: 'error',
                    title: response.status === 409 ? 'Client Not Empty' : 'Delete Failed',
                    message: extractServerMessage(body)
                        ?? (response.status === 403
                            ? 'You need full BillOps admin rights to delete a client.'
                            : `The client could not be deleted (HTTP ${response.status}).`)
                });
                return false;
            }

            await Promise.all([fetchUsers(), fetchClients()]);
            setNotification({
                type: 'success',
                title: 'Client Deleted',
                message: 'The client has been removed.'
            });
            return true;
        } catch (err) {
            console.error('[UserManager] Client delete failed:', err);
            setNotification({
                type: 'error',
                title: 'Delete Failed',
                message: err instanceof Error ? err.message : 'An unexpected error occurred.'
            });
            return false;
        }
    }, [getCsrfToken, fetchUsers, fetchClients]);

    /**
     * Update a field in the selected user form
     */
    const updateSelectedUser = useCallback((field: keyof BillOpsUserFormData, value: string | string[] | (string | number)[]) => {
        setSelectedUser(prev => ({
            ...prev,
            [field]: value
        }));
    }, []);

    /**
     * Toggle a XamOps sidebar tab key in the selected user's allowedTabs list.
     */
    const toggleTab = useCallback((tabKey: string) => {
        setSelectedUser(prev => {
            const allowedTabs = [...prev.allowedTabs];
            const index = allowedTabs.indexOf(tabKey);
            if (index === -1) allowedTabs.push(tabKey);
            else allowedTabs.splice(index, 1);
            return { ...prev, allowedTabs };
        });
    }, []);

    /**
     * Set the role, discarding selections that belonged to the outgoing role.
     *
     * Two things have to go. Section keys, because switching XAMOPS → BILLOPS ADMIN would
     * otherwise carry the XamOps keys over and save them as the admin's sections: the user would
     * count as restricted while matching no admin section, leaving them with an empty sidebar.
     * And cloud accounts, because the two roles pick from different catalogs — one client's
     * accounts vs. every account in the platform — so ids chosen under the old role are not
     * offered under the new one and would persist as invisible grants.
     */
    const changeRole = useCallback((role: BillOpsRole | '') => {
        setSelectedUser(prev => {
            const next = { ...prev, role };
            if (tabVocabularyOf(role) !== tabVocabularyOf(prev.role)) {
                next.allowedTabs = [];
            }
            if (accountCatalogOf(role) !== accountCatalogOf(prev.role)) {
                next.accountAccessIds = [];
            }
            return next;
        });
    }, []);

    /**
     * Toggle a cloud-account id in the selected user's accountAccessIds list.
     * Empty list = access to every cloud account of the client (see ClientDetailsPage.handleLoginAs).
     */
    const toggleAccount = useCallback((accountId: string | number) => {
        setSelectedUser(prev => {
            const accountAccessIds = [...prev.accountAccessIds];
            const index = accountAccessIds.findIndex(id => String(id) === String(accountId));
            if (index === -1) accountAccessIds.push(accountId);
            else accountAccessIds.splice(index, 1);
            return { ...prev, accountAccessIds };
        });
    }, []);



    /**
     * Fetch the cloud accounts belonging to a client, for the account-access picker.
     * This GET relies on the session cookie; no CSRF token is needed.
     */
    const fetchClientCloudAccounts = useCallback(async (clientId: string | number): Promise<ClientCloudAccount[]> => {
        try {
            const response = await fetch(`/api/billops/users/clients/${clientId}/cloud-accounts`, {
                method: 'GET',
                credentials: 'include'
            });
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}`);
            }
            return await response.json();
        } catch (err) {
            console.error('[UserManager] Failed to fetch client cloud accounts:', err);
            return [];
        }
    }, []);

    /**
     * Every cloud account the acting admin can see, across all clients — the catalog for a
     * BillOps admin user, who manages accounts rather than belonging to one client.
     *
     * Server-side the list is itself scoped to the caller's own account access, so a restricted
     * admin can only hand out a subset of what they hold.
     */
    const fetchAllCloudAccounts = useCallback(async (): Promise<ClientCloudAccount[]> => {
        // Unlike fetchClientCloudAccounts, this rethrows on failure rather than swallowing to [].
        // An empty list and a failed request are different states the picker must show
        // differently — "no accounts connected yet" vs. "couldn't load, try again" — and the most
        // common failure here is a 404 from a backend that predates this endpoint, which must not
        // masquerade as "there are no accounts".
        const response = await fetch('/api/billops/users/cloud-accounts', {
            method: 'GET',
            credentials: 'include'
        });
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
        return await response.json();
    }, []);

    return {
        // Data
        users,
        clients,
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
        fetchClients,
        deleteClient,
        newUser,
        editUser,
        cancelEdit,
        saveUser,
        deleteUser,
        moveUserToClient,
        updateSelectedUser,
        changeRole,
        toggleTab,
        toggleAccount,
        fetchClientCloudAccounts,
        fetchAllCloudAccounts,
        clearNotification
    };
};

export default useBillOpsUserManager;
