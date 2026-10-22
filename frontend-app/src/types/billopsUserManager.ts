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
    /**
     * Sidebar section keys this user is restricted to; empty/undefined = full sidebar.
     * The vocabulary depends on the role — XamOps module/tab keys for ROLE_XAMOPS, admin section
     * keys for the BillOps admin roles (see constants/billopsAdminTabs.ts) — and the two never
     * mix, which is why the form clears the selection when the role moves between families.
     */
    allowedTabs?: string[];
    /**
     * Cloud-account ids this user may see; empty/undefined = everything available to them.
     * For a customer user that means their own client's accounts; for a BillOps admin it means
     * every account in the platform, and the grant doubles as their client scope.
     */
    accountAccessIds?: (string | number)[];
}

/**
 * A client as the User Manager lists it. Mirrors the backend AdminClientDTO.
 *
 * The page's client rows are otherwise derived from the user list, so a client with no users
 * would never appear — this list is what puts those on screen so they can be given a first user
 * or deleted. The two counts are the delete guard: a client is deletable only at 0 and 0.
 */
export interface BillOpsClient {
    id: number;
    name: string;
    email?: string | null;
    userCount: number;
    cloudAccountCount: number;
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
    /**
     * Owning client. Only populated by the cross-client list used for BillOps admin users
     * (GET /api/billops/users/cloud-accounts), where account names like "prod" repeat across
     * customers and the row is ambiguous without it.
     */
    clientId?: number | string | null;
    clientName?: string | null;
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
    /** Sidebar section keys; empty = full sidebar. Vocabulary follows `role` — see BillOpsUser. */
    allowedTabs: string[];
    /** Cloud-account ids this user may see; empty = everything available to them. */
    accountAccessIds: (string | number)[];
}

/**
 * Hook result type
 */
export interface UseBillOpsUserManagerResult {
    // Data
    users: BillOpsUser[];
    /** Every client the admin may see, including ones with no users at all. */
    clients: BillOpsClient[];
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
    fetchClients: () => Promise<void>;
    /**
     * Deletes an empty client. Resolves false (with a notification carrying the server's reason)
     * when the client still has users or cloud accounts, or when the caller lacks the rights.
     */
    deleteClient: (clientId: number | string) => Promise<boolean>;
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
    /**
     * Sets the role, dropping selections that belonged to the previous role. Use this rather than
     * `updateSelectedUser('role', …)` for two reasons: XamOps section keys and BillOps admin
     * section keys share one storage table, so a leftover `finops-cost` row on an admin user
     * would read as "restricted" while matching no admin section at all; and the two roles pick
     * their cloud accounts from different catalogs (one client's vs. the whole platform), so ids
     * chosen under one are not valid choices under the other.
     */
    changeRole: (role: BillOpsRole | '') => void;
    toggleTab: (tabKey: string) => void;
    toggleAccount: (accountId: string | number) => void;
    /** The accounts of one client — the picker catalog for a customer user. */
    fetchClientCloudAccounts: (clientId: string | number) => Promise<ClientCloudAccount[]>;
    /**
     * Every cloud account the acting admin can see, across all clients — the picker catalog for
     * a BillOps admin user, who is not confined to a single client.
     */
    fetchAllCloudAccounts: () => Promise<ClientCloudAccount[]>;
    clearNotification: () => void;
}
