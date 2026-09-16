/**
 * useUserManagement Hook
 *
 * Custom hook for managing IAM users and roles data
 * Handles fetching, filtering, and formatting of user management data
 */

import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import type { IamUser, IamRole, IamRiskFields, IdentityInventoryResponse } from '../types/userManagement';

/**
 * Ceiling on a single identity request.
 *
 * <p>Above the proxy's own timeout on purpose: the gateway answers first in the normal slow
 * case, and this only catches a socket that hangs without either side closing it. Without it
 * such a request leaves the tab on its skeleton forever with no way to retry.
 */
const REQUEST_TIMEOUT_MS = 120_000;

/** Gap between checks for the background privilege audit's result. */
const PENDING_POLL_INTERVAL_MS = 15_000;

/** How many times to check before leaving the "still scoring" banner up for good. */
const PENDING_POLL_LIMIT = 4;

/**
 * Turns a failed response into something worth showing a person.
 *
 * <p>The previous version interpolated `text.substring(0, 500)` of the body into the message
 * unconditionally. When the gateway timed out, that body was nginx's HTML error page, so the
 * banner rendered `Status: 504 <html><head><title>504 Gateway Time-out</title>…` as prose.
 * The status code already carries the meaning; the body is only useful when it is our own JSON.
 */
function describeFailure(status: number, body: string): string {
  switch (status) {
    case 503:
      // Our own deadline, not the proxy's. The scan was not cancelled — it is still running and
      // will write its result to the cache — so a retry shortly is genuinely the right advice.
      return 'The identity scan is taking longer than usual and is still running in the '
        + 'background. Reload identities in a moment to pick up the result.';
    case 504:
      // The proxy gave up before the app could answer, so nothing is known about the scan's fate.
      return 'The request timed out before the server responded. Reload identities to try again.';
    case 502:
      return 'The identity scan failed. If this persists, the account\'s role may be missing IAM '
        + 'read permissions.';
    case 401:
      return 'Your session expired. Sign in again to view identities.';
    case 403:
      return 'You do not have access to this account.';
    case 404:
      return 'That account is no longer connected.';
    default:
      break;
  }

  const trimmed = body.trim();
  if (!trimmed || /^<(!doctype|html)/i.test(trimmed)) {
    return `Could not load identities (${status}).`;
  }
  try {
    const parsed = JSON.parse(trimmed);
    const message = parsed?.error ?? parsed?.message;
    if (typeof message === 'string' && message) return message;
  } catch {
    // Not JSON — fall through rather than surface a parser complaint.
  }
  return `Could not load identities (${status}).`;
}

/**
 * Orders identities worst-risk-first, then alphabetically within equal risk.
 *
 * <p>The name tiebreak matters: without it, every unassessed identity shares score 0 and
 * the browser's sort is free to reshuffle them between renders, so cards appear to jump
 * around while the user is reading them.
 */
function byRiskThenName<T extends IamRiskFields>(name: (item: T) => string) {
  return (a: T, b: T): number => {
    const delta = (b.riskScore ?? 0) - (a.riskScore ?? 0);
    if (delta !== 0) return delta;
    return name(a).localeCompare(name(b));
  };
}

export const useUserManagement = () => {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [accountName, setAccountName] = useState('');
  const [accountId, setAccountId] = useState('');
  const [provider, setProvider] = useState<'AWS' | 'GCP' | 'Azure'>('AWS');
  const [users, setUsers] = useState<IamUser[]>([]);
  const [roles, setRoles] = useState<IamRole[]>([]);
  const [searchQuery, setSearchQuery] = useState('');

  /** Abort handle for the in-flight identity request, so account switches cannot race. */
  const inFlightRef = useRef<AbortController | null>(null);

  /**
   * When true, show only identities carrying a HIGH or CRITICAL risk.
   *
   * <p>Kept in the hook rather than the component so the KPI cards can act as filter
   * controls — clicking "Over-privileged" is the fastest path to the list that matters.
   */
  const [flaggedOnly, setFlaggedOnly] = useState(false);

  /**
   * Whether the permission audit could read every policy document.
   *
   * <p>Surfaced because an unaudited identity and a least-privilege identity both render
   * as an unflagged card. Without this the console would quietly imply the former is safe.
   */
  const [analysisDegraded, setAnalysisDegraded] = useState(false);
  const [analysisNote, setAnalysisNote] = useState<string | null>(null);

  /**
   * Whether the backend is still scoring this account in the background.
   *
   * <p>The audit is the expensive half of the scan, so a large account gets its inventory
   * returned first and its risk fields shortly after. This drives both the banner and the
   * bounded poll below, so the badges arrive without the user being told to press anything.
   */
  const [analysisPending, setAnalysisPending] = useState(false);

  /** Poll attempts spent waiting for the audit, reset per account. */
  const pendingPollsRef = useRef(0);

  /**
   * Determine provider from cached accounts
   */
  const determineProvider = useCallback((accountId: string) => {
    try {
      const cached = sessionStorage.getItem('cached_accounts');
      if (cached) {
        const accounts = JSON.parse(cached);
        const account = accounts.find((a: any) => a.id === accountId);
        if (account && account.provider) {
          setProvider(account.provider);
          return;
        }
      }
    } catch (e) {
      console.error('Error determining provider', e);
    }
  }, []);

  /**
   * Load account from storage
   */
  const loadAccountFromStorage = useCallback(() => {
    let targetId: string | null = null;
    const storedIds = sessionStorage.getItem('selectedAccountIds');

    if (storedIds) {
      try {
        const ids = JSON.parse(storedIds);
        if (Array.isArray(ids) && ids.length > 0) {
          targetId = ids[0];
        }
      } catch (e) {
        console.error('Error parsing IDs', e);
      }
    }

    if (!targetId) {
      targetId = localStorage.getItem('selectedAccountId');
    }

    if (!targetId) {
      targetId = new URLSearchParams(window.location.search).get('accountId');
    }

    if (targetId) {
      if (accountId !== targetId || users.length === 0) {
        setAccountId(targetId);
        determineProvider(targetId);
        fetchData(false, targetId);
      }
    } else {
      setError('Please select an account from the sidebar.');
      setUsers([]);
      setRoles([]);
    }
  }, [accountId, users.length, determineProvider]);

  /**
   * Fetch the identity inventory.
   *
   * <p>Targets the dedicated identities endpoint rather than the dashboard aggregate. The
   * aggregate joins ~18 AWS collectors and blocks on all of them, so this tab used to inherit
   * Cost Explorer's latency and got the gateway's HTML 504 instead of data whenever the cache
   * was cold. Same reason `forceRefresh` is now safe to hand to the Reload button: it re-runs
   * the IAM scan, not the entire dashboard.
   */
  const fetchData = useCallback(async (forceRefresh = false, specificAccountId: string | null = null) => {
    const idToFetch = specificAccountId || accountId;
    if (!idToFetch) return;

    // One request in flight at a time. Switching accounts mid-scan otherwise races, and the
    // slower response wins — showing one account's identities under another's name.
    inFlightRef.current?.abort();
    const controller = new AbortController();
    inFlightRef.current = controller;
    const timeout = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

    setLoading(true);
    setError(null);

    try {
      const url = `/api/xamops/security/identities?accountId=${encodeURIComponent(idToFetch)}`
        + `&forceRefresh=${forceRefresh}`;

      const response = await fetch(url, {
        method: 'GET',
        headers: { 'Accept': 'application/json' },
        credentials: 'include',
        cache: 'no-store',
        signal: controller.signal,
      });

      if (!response.ok) {
        throw new Error(describeFailure(response.status, await response.text().catch(() => '')));
      }

      const data: IdentityInventoryResponse = await response.json();
      if (!data) {
        throw new Error('Could not load identities: the server returned an empty response.');
      }

      setProvider(data.provider || 'AWS');
      if (data.accountName) setAccountName(data.accountName);
      if (data.accountId) setAccountId(data.accountId);

      // A null inventory means the scan produced nothing rather than that the account is empty,
      // so retry once against a bypassed cache before believing it.
      if (!data.iamDetails && !forceRefresh) {
        await fetchData(true, idToFetch);
        return;
      }

      if (data.iamDetails) {
        setUsers(data.iamDetails.users || []);
        setRoles(data.iamDetails.roles || []);
        setAnalysisDegraded(Boolean(data.iamDetails.permissionAnalysisDegraded));
        setAnalysisNote(data.iamDetails.permissionAnalysisNote ?? null);
        setAnalysisPending(Boolean(data.iamDetails.permissionAnalysisPending));
      } else {
        setUsers([]);
        setRoles([]);
        setError('The identity scan returned no data for this account. Reload identities to retry.');
      }
    } catch (err) {
      // An abort is either our own timeout or a superseded request; only the former is worth
      // reporting, and a superseded request must not overwrite the newer one's state.
      if (err instanceof DOMException && err.name === 'AbortError') {
        if (inFlightRef.current === controller) {
          setError('The identity scan took too long to respond. Reload identities to try again.');
        }
        return;
      }
      console.error('Identity fetch failed:', err);
      setError(err instanceof Error ? err.message : 'Failed to load identities');
    } finally {
      window.clearTimeout(timeout);
      if (inFlightRef.current === controller) {
        inFlightRef.current = null;
        setLoading(false);
      }
    }
  }, [accountId]);

  /**
   * Pick up the risk scores once the background audit lands.
   *
   * <p>Bounded rather than open-ended: the audit either finishes within a minute or it hit the
   * analyzer's own document cap, and a poll that never gives up would keep a tab making requests
   * for as long as it is open. On the last attempt the banner is left in place, which is honest —
   * the inventory is real, the scoring is not there yet.
   *
   * <p>`fetchData(false)` on purpose: the refresh must read the cache the audit writes to. Forcing
   * a refresh would start a whole new scan and never observe the result it is waiting for.
   */
  useEffect(() => {
    if (!analysisPending || !accountId) return;
    if (pendingPollsRef.current >= PENDING_POLL_LIMIT) return;

    const timer = window.setTimeout(() => {
      pendingPollsRef.current += 1;
      fetchData(false, accountId);
    }, PENDING_POLL_INTERVAL_MS);

    return () => window.clearTimeout(timer);
  }, [analysisPending, accountId, fetchData]);

  /** A new account gets a fresh budget of polls. */
  useEffect(() => {
    pendingPollsRef.current = 0;
  }, [accountId]);

  /**
   * Initialize data on mount
   */
  useEffect(() => {
    loadAccountFromStorage();

    // Listen for account changes
    const handleAccountChange = () => loadAccountFromStorage();
    window.addEventListener('account-changed', handleAccountChange);

    return () => {
      window.removeEventListener('account-changed', handleAccountChange);
    };
  }, [loadAccountFromStorage]);

  /**
   * Filtered users based on search query, worst risk first.
   *
   * <p>Risk-descending rather than alphabetical because the canvas shows cards, and a card
   * grid has no scannable column to sort by eye. The one identity holding
   * AdministratorAccess has to be in the first row or the view is decorative.
   */
  const filteredUsers = useMemo(() => {
    const lower = searchQuery.trim().toLowerCase();
    return users
      .filter(u => !flaggedOnly || u.excessPermissions)
      .filter(u => !lower
        || (u.userName && u.userName.toLowerCase().includes(lower))
        || (u.userId && u.userId.toLowerCase().includes(lower)))
      .slice()
      .sort(byRiskThenName(u => u.userName));
  }, [users, searchQuery, flaggedOnly]);

  /**
   * Filtered roles based on search query, worst risk first.
   */
  const filteredRoles = useMemo(() => {
    const lower = searchQuery.trim().toLowerCase();
    return roles
      .filter(r => !flaggedOnly || r.excessPermissions)
      .filter(r => !lower || r.roleName.toLowerCase().includes(lower))
      .slice()
      .sort(byRiskThenName(r => r.roleName));
  }, [roles, searchQuery, flaggedOnly]);

  /**
   * Risk tallies across users and roles combined.
   *
   * <p>Combined deliberately: "how exposed is this account's identity surface" is one
   * question, and splitting it per tab would let a critical role hide behind a clean
   * user list.
   */
  const riskCounts = useMemo(() => {
    const all: Array<IamUser | IamRole> = [...users, ...roles];
    const counts = { critical: 0, high: 0, medium: 0, low: 0, flagged: 0, assessed: 0 };
    for (const identity of all) {
      if (identity.riskLevel) counts.assessed += 1;
      if (identity.excessPermissions) counts.flagged += 1;
      switch (identity.riskLevel) {
        case 'CRITICAL': counts.critical += 1; break;
        case 'HIGH': counts.high += 1; break;
        case 'MEDIUM': counts.medium += 1; break;
        case 'LOW': counts.low += 1; break;
        default: break;
      }
    }
    return counts;
  }, [users, roles]);

  /**
   * Format date string
   */
  const formatDate = useCallback((dateString: string | null): string => {
    if (!dateString || dateString === 'Never') return 'Never';
    try {
      const date = new Date(dateString);
      if (isNaN(date.getTime())) return dateString;
      return date.toLocaleDateString('en-US', {
        year: 'numeric',
        month: 'short',
        day: 'numeric'
      });
    } catch (e) {
      return dateString;
    }
  }, []);

  /**
   * Check if date is older than 90 days
   */
  const isOldDate = useCallback((dateString: string | null): boolean => {
    if (!dateString || dateString === 'Never') return true;
    const date = new Date(dateString);
    const ninetyDaysAgo = new Date();
    ninetyDaysAgo.setDate(ninetyDaysAgo.getDate() - 90);
    return date < ninetyDaysAgo;
  }, []);

  /**
   * Count users without MFA (AWS only)
   */
  const countNoMfa = useMemo(() => {
    if (provider !== 'AWS') return 0;
    return users.filter(u => !u.mfaEnabled).length;
  }, [provider, users]);

  /**
   * Copy text to clipboard
   */
  const copyToClipboard = useCallback(async (text: string) => {
    try {
      await navigator.clipboard.writeText(text);
    } catch (e) {
      console.error('Failed to copy to clipboard', e);
    }
  }, []);

  /**
   * Provider-appropriate names for the two identity kinds.
   *
   * <p>GCP says "Principals" rather than "Service Accounts" because the list is no longer only
   * service accounts: the analyzer adds the human users and groups that appear in the project
   * IAM policy, which are invisible to the service-account API. Roles differ too — GCP lists
   * only custom roles (the ~1,500 predefined ones are identical everywhere and unchangeable),
   * while Azure lists role definitions.
   */
  const labels = useMemo(() => ({
    users: provider === 'AWS' ? 'IAM Users' : 'Principals',
    roles: provider === 'GCP' ? 'Custom Roles' : provider === 'Azure' ? 'Role Definitions' : 'IAM Roles',
    userIdentity: provider === 'AWS' ? 'User Identity' : 'Principal',
  }), [provider]);

  return {
    loading,
    error,
    accountName,
    accountId,
    provider,
    users,
    roles,
    searchQuery,
    setSearchQuery,
    filteredUsers,
    filteredRoles,
    formatDate,
    isOldDate,
    countNoMfa,
    copyToClipboard,
    labels,
    fetchData,
    isAws: provider === 'AWS',
    isGcp: provider === 'GCP',
    isAzure: provider === 'Azure',

    // Permission-risk surface, consumed by the Security Center's Identity tab.
    riskCounts,
    flaggedOnly,
    setFlaggedOnly,
    analysisDegraded,
    analysisNote,
    analysisPending,
    /**
     * Whether risk scoring ran at all for this provider.
     *
     * <p>GCP and Azure return the inventory shape without risk fields, so the canvas hides
     * the risk KPIs and red flags entirely there rather than rendering a misleading
     * "0 over-privileged".
     */
    riskAnalysisAvailable: riskCounts.assessed > 0,
  };
};
