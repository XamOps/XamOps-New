import { useCallback, useEffect, useState } from 'react';
import apiClient from '../utils/apiClient';
import type {
  DspmAuditPage, DspmClientOption, DspmMappingAdminView, DspmMappingRequest,
} from '../types/dspm';

const BASE = '/api/xamops/billops-admin/dspm';

/**
 * Extracts a readable message from an apiClient error.
 *
 * `apiFetchJson` throws `Error("HTTP 409: <body>")`, so the useful part — the backend's message or the
 * `ResponseStatusException` reason — is buried inside a JSON blob in the message string. Without this
 * an operator who maps an already-taken project sees "HTTP 409: {...}" instead of the sentence naming
 * the conflicting customer.
 */
function readError(err: unknown): string {
  const raw = err instanceof Error ? err.message : String(err);
  const brace = raw.indexOf('{');
  if (brace >= 0) {
    try {
      const parsed = JSON.parse(raw.slice(brace));
      return parsed.message || parsed.error || parsed.detail || raw;
    } catch {
      /* fall through to the raw text */
    }
  }
  return raw;
}

/**
 * Operator-side DSPM data: the org → project mappings and the access audit trail.
 *
 * No caching here, deliberately. Every other hook in this app caches aggressively, but this one shows
 * credential state that an operator is actively changing — a stale "CONNECTED" next to a key they just
 * replaced is worse than a brief spinner, and the audit trail is only useful current.
 */
export function useDspmAdmin() {
  const [mappings, setMappings] = useState<DspmMappingAdminView[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  /** Per-client in-flight flag, so one row's Verify does not disable every other row's buttons. */
  const [busyClientId, setBusyClientId] = useState<number | null>(null);

  const loadMappings = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setMappings(await apiClient.get<DspmMappingAdminView[]>(`${BASE}/mappings`));
    } catch (err) {
      setError(readError(err));
      setMappings(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadMappings();
  }, [loadMappings]);

  /**
   * Creates or replaces a mapping.
   *
   * Returns the saved view rather than only refreshing, so the caller can close a form on success and
   * keep it open with the error on failure.
   */
  const saveMapping = useCallback(
    async (clientId: number, request: DspmMappingRequest): Promise<DspmMappingAdminView> => {
      setBusyClientId(clientId);
      try {
        const saved = await apiClient.put<DspmMappingAdminView>(
          `${BASE}/mappings/${clientId}`, request);
        await loadMappings();
        return saved;
      } finally {
        setBusyClientId(null);
      }
    }, [loadMappings]);

  const deleteMapping = useCallback(async (clientId: number) => {
    setBusyClientId(clientId);
    try {
      await apiClient.delete(`${BASE}/mappings/${clientId}`);
      await loadMappings();
    } finally {
      setBusyClientId(null);
    }
  }, [loadMappings]);

  /**
   * Tests the stored credential.
   *
   * Never throws for a failed connection — the endpoint answers 200 with the recorded verdict, because
   * "the key is invalid" is a result to display, not a request error. Only a transport failure rejects.
   */
  const verifyMapping = useCallback(
    async (clientId: number): Promise<DspmMappingAdminView | null> => {
      setBusyClientId(clientId);
      try {
        const result = await apiClient.post<DspmMappingAdminView>(
          `${BASE}/mappings/${clientId}/verify`);
        await loadMappings();
        return result;
      } catch (err) {
        setError(readError(err));
        return null;
      } finally {
        setBusyClientId(null);
      }
    }, [loadMappings]);

  /**
   * Grants or withdraws the add-on.
   *
   * Sends the intended state rather than toggling, so a double-click cannot silently reverse a decision
   * about a paid capability. Optimistic, with a rollback on failure.
   */
  const setEntitlement = useCallback(async (clientId: number, enabled: boolean) => {
    setMappings(prev => prev?.map(m =>
      m.clientId === clientId ? { ...m, entitled: enabled } : m) ?? prev);
    try {
      await apiClient.post(`${BASE}/clients/${clientId}/entitlement`, { enabled });
    } catch (err) {
      setMappings(prev => prev?.map(m =>
        m.clientId === clientId ? { ...m, entitled: !enabled } : m) ?? prev);
      setError(readError(err));
    }
  }, []);

  return {
    mappings, loading, error, busyClientId,
    loadMappings, saveMapping, deleteMapping, verifyMapping, setEntitlement,
    clearError: () => setError(null),
  };
}

/**
 * Clients for the mapping picker.
 *
 * Fetched lazily, when the picker is first opened, rather than with the page. It is only needed to add a
 * mapping, which is a rare action, and the list grows with the customer base.
 */
export function useDspmClientOptions() {
  const [clients, setClients] = useState<DspmClientOption[] | null>(null);
  const [loading, setLoading] = useState(false);

  const loadClients = useCallback(async () => {
    if (clients !== null) return;
    setLoading(true);
    try {
      setClients(await apiClient.get<DspmClientOption[]>(`${BASE}/clients`));
    } catch {
      setClients([]);
    } finally {
      setLoading(false);
    }
  }, [clients]);

  return { clients, loadingClients: loading, loadClients };
}

/** Filters for the audit viewer. All optional. */
export interface DspmAuditFilters {
  clientId?: number | null;
  action?: string | null;
  outcome?: string | null;
  days?: number | null;
}

/**
 * The access audit trail, paginated.
 *
 * Defaults to excluding nothing, but the admin page defaults its `action` filter to the mutations —
 * credential-use rows are produced on every customer page load and would otherwise bury the handful of
 * configuration changes an operator actually came to review.
 */
export function useDspmAudit() {
  const [audit, setAudit] = useState<DspmAuditPage | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadAudit = useCallback(async (filters: DspmAuditFilters = {}, page = 0, size = 50) => {
    setLoading(true);
    setError(null);
    try {
      const params = new URLSearchParams();
      params.set('page', String(page));
      params.set('size', String(size));
      if (filters.clientId) params.set('clientId', String(filters.clientId));
      if (filters.action) params.set('action', filters.action);
      if (filters.outcome) params.set('outcome', filters.outcome);
      if (filters.days) params.set('days', String(filters.days));
      setAudit(await apiClient.get<DspmAuditPage>(`${BASE}/audit?${params.toString()}`));
    } catch (err) {
      setError(readError(err));
      setAudit(null);
    } finally {
      setLoading(false);
    }
  }, []);

  return { audit, loading, error, loadAudit };
}
