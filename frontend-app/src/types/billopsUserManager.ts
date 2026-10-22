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
    clientId?: string | number | null;  // current client id (backend returns Long); used for move-between-clients
    clientName: string;
    username: string;
    email: string;
    password?: string;
    role: BillOpsRole;
    contact?: string;
    designation?: string;
    parentId?: string | number | null;  // null/undefined = primary user; set = sub-user of that parent (backend returns Long)
    allowedTabs?: string[];  // XamOps sidebar tab keys (only meaningful when role === 'ROLE_XAMOPS')
    accountAccessIds?: (string | number)[];  // cloud_account ids this user may access; empty/undefined = all accounts of the client
}

/**
 * Lightweight cloud account used by the user-manager account-access picker.
 * Mirrors the backend ClientCloudAccountDTO.
 */
export interface ClientCloudAccount {
    id: number | string;
    name: string;
    provider: string;
    accountId: string;  // provider-specific id (AWS account / GCP project / Azure subscription)
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
    accountAccessIds: (string | number)[];  // cloud_account ids this user may access; empty = all accounts of the client
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
    /**
     * Moves a user to `targetClientId`. `parentUserId` nests them under that primary user of the
     * destination client; null/omitted places them as a primary user. The destination may be the
     * client the user already belongs to, which re-parents them inside their own client.
     */
    moveUserToClient: (userId: string, targetClientId: string | number, parentUserId?: string | number | null) => Promise<boolean>;
    updateSelectedUser: (field: keyof BillOpsUserFormData, value: string | string[] | (string | number)[]) => void;
    toggleTab: (tabKey: string) => void;
    toggleAccount: (accountId: string | number) => void;
    fetchClientCloudAccounts: (clientId: string | number) => Promise<ClientCloudAccount[]>;
    clearNotification: () => void;
}
