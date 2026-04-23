/**
 * AccessOps Identity Management API client.
 *
 * All calls go to XamOps backend (/api/xamops/identity/*).
 * The backend is the only entity that talks to Authentik — this client
 * never touches auth.xamops.com directly.
 *
 * Authentik API docs (backend reference): https://docs.goauthentik.io/developer-docs/api/
 */

const BASE = '/api/xamops/identity';

// ── Types ──────────────────────────────────────────────────────────────────────

export interface IdentityUser {
  pk: number;
  username: string;
  name: string;
  email: string;
  isActive: boolean;
  lastLogin: string | null;
  groupsObj: Array<{ pk: string; name: string }>;
}

export interface IdentityGroup {
  pk: string;
  name: string;
  numPk: number;
  usersObj: Array<{
    pk: number;
    username: string;
    name: string;
    email: string;
    isActive: boolean;
  }>;
  awsAccountId: string | null;
  awsAccountName: string | null;
  permissionSetName: string | null;
}

export interface AuditLogEntry {
  id: number;
  action: string;
  actorUserId: number;
  targetUserEmail: string | null;
  groupName: string | null;
  timestamp: string;
}

export interface PagedAuditLog {
  content: AuditLogEntry[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface InviteUserPayload {
  name: string;
  email: string;
  groupId: string;
}

// ── Helpers ────────────────────────────────────────────────────────────────────

const getCsrfToken = (): string | undefined =>
  document.cookie.split('; ').find(r => r.startsWith('XSRF-TOKEN='))?.split('=')[1];

async function apiFetch<T>(path: string, options: RequestInit = {}): Promise<T> {
  const csrfToken = getCsrfToken();

  const response = await fetch(`${BASE}${path}`, {
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
      ...(csrfToken ? { 'X-XSRF-TOKEN': csrfToken } : {}),
      ...(options.headers as Record<string, string> ?? {}),
    },
    ...options,
  });

  if (!response.ok) {
    const err = await response.json().catch(() => ({ error: 'Unknown error' }));
    throw new Error(err.error ?? `HTTP ${response.status}`);
  }

  if (response.status === 204) return undefined as unknown as T;
  return response.json();
}

// ── Users ──────────────────────────────────────────────────────────────────────

export const listUsers = (): Promise<IdentityUser[]> =>
  apiFetch<IdentityUser[]>('/users');

export const inviteUser = (payload: InviteUserPayload): Promise<IdentityUser> =>
  apiFetch<IdentityUser>('/users/invite', {
    method: 'POST',
    body: JSON.stringify(payload),
  });

export const deactivateUser = (authentikPk: number): Promise<IdentityUser> =>
  apiFetch<IdentityUser>(`/users/${authentikPk}/deactivate`, { method: 'PATCH' });

// ── Groups ─────────────────────────────────────────────────────────────────────

export const listGroups = (): Promise<IdentityGroup[]> =>
  apiFetch<IdentityGroup[]>('/groups');

export const addUserToGroup = (groupId: string, userId: number): Promise<void> =>
  apiFetch<void>(`/groups/${groupId}/users`, {
    method: 'POST',
    body: JSON.stringify({ userId }),
  });

export const removeUserFromGroup = (groupId: string, userId: number): Promise<void> =>
  apiFetch<void>(`/groups/${groupId}/users/${userId}`, { method: 'DELETE' });

// ── Audit Log ──────────────────────────────────────────────────────────────────

export const listAuditLogs = (page = 0, size = 50): Promise<PagedAuditLog> =>
  apiFetch<PagedAuditLog>(`/audit?page=${page}&size=${size}`);
