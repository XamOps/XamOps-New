// ─── Google Workspace Integration Types ──────────────────────────────────────

/** Connection status returned by GET /api/workspace/status */
export interface WorkspaceStatus {
  connected: boolean;
  domain?: string;
  adminEmail?: string;
  totalUsers?: number;
  activeUsers?: number;
  inactiveUsers?: number;
  suspendedUsers?: number;
  lastSyncedAt?: string;
  errorMessage?: string;
}

/** Request body for POST /api/workspace/connect */
export interface WorkspaceConnectRequest {
  domain: string;
  adminEmail: string;
  serviceAccountKey: string;
}

/** A single Google Workspace user (GET /api/workspace/users) */
export interface WorkspaceUserEntry {
  email: string;
  fullName: string;
  orgUnit: string;
  status: 'ACTIVE' | 'INACTIVE' | 'SUSPENDED';
  lastLoginTime: string | null;   // ISO-8601 UTC string or null
  daysSinceLogin: number | null;  // null = never logged in
  isAdmin: boolean;
}

/** License/seat utilization summary (GET /api/workspace/licenses/summary) */
export interface WorkspaceLicenseSummary {
  totalSeats: number;       // = totalUsers
  activeSeats: number;
  inactiveSeats: number;
  suspendedSeats: number;
  utilizationPercent: number;
  neverLoggedIn: number;
}

/** A single optimization recommendation (GET /api/workspace/optimization) */
export interface WorkspaceOptimizationItem {
  type: 'INACTIVE_USER' | 'SUSPENDED_WITH_LICENSE' | 'NEVER_LOGGED_IN';
  email: string;
  fullName: string;
  orgUnit: string;
  daysSinceLogin: number | null;
  recommendation: string;
}

/** Result of POST /api/workspace/sync */
export interface WorkspaceSyncResult {
  status: string;
  domain: string;
  totalUsers: number;
  activeUsers: number;
  inactiveUsers: number;
  suspendedUsers: number;
  syncedAt: string;
}

/** Tabs on the connected dashboard */
export type WorkspaceTab = 'overview' | 'users' | 'optimization' | 'cost';

/** Filter option for user table */
export type UserStatusFilter = 'ALL' | 'ACTIVE' | 'INACTIVE' | 'SUSPENDED';
