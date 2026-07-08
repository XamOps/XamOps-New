/**
 * BillOps User Manager Types
 * 
 * Type definitions for the BillOps Admin User Management feature.
 * Used for managing users with ROLE_BILLOPS and ROLE_BILLOPS_ADMIN roles.
 */

/**
 * Available roles for BillOps users
 */
export type BillOpsRole = 'ROLE_BILLOPS' | 'ROLE_BILLOPS_ADMIN' | 'ROLE_BILLOPS_ADMIN_SUB' | 'ROLE_XAMOPS';

/**
 * BillOps User entity
 */
export interface BillOpsUser {
    id: string;
    clientName: string;
    username: string;
    email: string;
    password?: string;
    role: BillOpsRole;
    contact?: string;
    designation?: string;
    parentId?: string | number | null;  // null/undefined = primary user; set = sub-user of that parent (backend returns Long)
    allowedTabs?: string[];  // XamOps sidebar tab keys (only meaningful when role === 'ROLE_XAMOPS')
}

/**
 * Form state for creating/editing a user
 */
export interface BillOpsUserFormData {
    id: string;
    clientName: string;
    username: string;
    email: string;
    password: string;
    role: BillOpsRole | '';
    contact: string;
    designation: string;
    parentId: string;  // empty string = primary user
    allowedTabs: string[];  // XamOps sidebar tab keys (only meaningful when role === 'ROLE_XAMOPS')
}

/**
 * Hook result type
 */
export interface UseBillOpsUserManagerResult {
    // Data
    users: BillOpsUser[];
    loading: boolean;
    error: string | null;

    // Form state
    isEditing: boolean;
    selectedUser: BillOpsUserFormData;
    notification: {
        type: 'success' | 'error' | 'info';
        title: string;
        message: string;
    } | null;

    // Available roles
    roles: BillOpsRole[];

    // Confirm-delete state (replaces window.confirm)
    pendingDeleteId: string | null;
    confirmDelete: () => Promise<void>;
    cancelDelete: () => void;

    // Actions
    fetchUsers: () => Promise<void>;
    newUser: () => void;
    editUser: (user: BillOpsUser) => void;
    cancelEdit: () => void;
    saveUser: () => Promise<void>;
    deleteUser: (id: string) => void;
    updateSelectedUser: (field: keyof BillOpsUserFormData, value: string | string[]) => void;
    toggleTab: (tabKey: string) => void;
    clearNotification: () => void;
}
