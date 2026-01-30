/**
 * BillOps User Manager Types
 * 
 * Type definitions for the BillOps Admin User Management feature.
 * Used for managing users with ROLE_BILLOPS and ROLE_BILLOPS_ADMIN roles.
 */

/**
 * Available roles for BillOps users
 */
export type BillOpsRole = 'ROLE_BILLOPS' | 'ROLE_BILLOPS_ADMIN';

/**
 * BillOps User entity
 */
export interface BillOpsUser {
    id: string;
    clientName: string;
    username: string;
    email: string;
    password?: string; // Only used for create/update, not returned from API
    role: BillOpsRole;
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

    // Available roles
    roles: BillOpsRole[];

    // Actions
    fetchUsers: () => Promise<void>;
    newUser: () => void;
    editUser: (user: BillOpsUser) => void;
    cancelEdit: () => void;
    saveUser: () => Promise<void>;
    deleteUser: (id: string) => Promise<void>;
    updateSelectedUser: (field: keyof BillOpsUserFormData, value: string) => void;
}
