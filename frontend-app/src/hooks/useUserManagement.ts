/**
 * useUserManagement Hook
 *
 * Backs the Security Center's Identity & Access tab: resolves the selected account, fetches its
 * IAM inventory, and derives the filtering and risk tallies the canvas renders.
 *
 * <p>Fetching goes through React Query on the app-wide client rather than through local state.
 * That is not a stylistic preference — the tab is mounted conditionally inside an
 * `AnimatePresence` block, so switching to Findings and back unmounts it and threw every piece of
 * local state away. On remount the hook saw an empty user list and started another scan, which is
 * why opening the tab almost always looked like a cold load. The query cache lives above the
 * component tree, so a remount now reads the previous answer and fires no request at all unless
 * the data has actually gone stale.
 */

import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import type { IamUser, IamRole, IamRiskFields, IdentityInventoryResponse } from '../types/userManagement';

/**
 * Ceiling on a single identity request.
 *
 * <p>Above the proxy's own timeout on purpose: the gateway answers first in the normal slow
 * case, and this only catches a socket that hangs without either side closing it. Without it
 * such a request leaves the tab on its skeleton forever with no way to retry.
 */
const REQUEST_TIMEOUT_MS = 120_000;

/**
 * How long a fetched inventory is treated as current.
 *
 * <p>Matches the client's default rather than the server's snapshot window, because the two
 * govern different things: this decides whether navigating back to the tab re-asks, while the
 * server decides whether the answer it gives is worth rebuilding. Asking again is cheap now that
 * the answer comes from a cache, so the client can afford to be the more eager of the two.
 */
const IDENTITY_STALE_MS = 5 * 60 * 1000;

/** Kept well past the stale window so a tab switch is always a cache read. */
const IDENTITY_GC_MS = 24 * 60 * 60 * 1000;

/** Gap between checks for work the server said it is still doing. */
const PENDING_POLL_INTERVAL_MS = 15_000;

/** How many times to check before leaving the "still working" banner up for good. */
const PENDING_POLL_LIMIT = 4;

/**
 * A failed identity request, carrying whether waiting would help.
 *
 * <p>The distinction drives retry behaviour, and getting it wrong is expensive in both
 * directions: retrying a 403 is pure noise, while not retrying a 503 shows the user an error for
 * a scan that was about to succeed — which is the failure this tab was most often displaying.
 */
class IdentityFetchError extends Error {
  readonly retryable: boolean;

  constructor(message: string, retryable: boolean) {
    super(message);
    this.name = 'IdentityFetchError';
    this.retryable = retryable;
  }
}

/**
 * Turns a failed response into something worth showing a person.
 *
 * <p>The previous version interpolated `text.substring(0, 500)` of the body into the message
 * unconditionally. When the gateway timed out, that body was nginx's HTML error page, so the
 * banner rendered `Status: 504 <html><head><title>504 Gateway Time-out</title>…` as prose.
 * The status code already carries the meaning; the body is only useful when it is our own JSON.
 */
function describeFailure(status: number, body: string): IdentityFetchError {
  switch (status) {
    case 503:
      // Our own deadline, not the proxy's. The scan was not cancelled — it is still running and
      // will write its result to the cache — so waiting and asking again is what resolves this,
      // and the retry policy below does exactly that without involving the user.
      return new IdentityFetchError(
        'The identity scan is taking longer than usual and is still running in the background.',
        true);
    case 504:
      // The proxy gave up before the app could answer, so nothing is known about the scan's fate.
      return new IdentityFetchError(
        'The request timed out before the server responded. Retrying.', true);
    case 502:
      // Not retried: a missing IAM read permission produces this on every attempt, and repeating
      // it only delays showing the user the one thing that would fix it.
      return new IdentityFetchError(
        'The identity scan failed. If this persists, the account\'s role may be missing IAM '
        + 'read permissions.', false);
    case 401:
      return new IdentityFetchError('Your session expired. Sign in again to view identities.', false);
    case 403:
      return new IdentityFetchError('You do not have access to this account.', false);
    case 404:
      return new IdentityFetchError('That account is no longer connected.', false);
    default:
      break;
  }

  const trimmed = body.trim();
  if (!trimmed || /^<(!doctype|html)/i.test(trimmed)) {
    return new IdentityFetchError(`Could not load identities (${status}).`, status >= 500);
  }
  try {
    const parsed = JSON.parse(trimmed);
    const message = parsed?.error ?? parsed?.message;
    if (typeof message === 'string' && message) {
      return new IdentityFetchError(message, status >= 500);
    }
  } catch {
    // Not JSON — fall through rather than surface a parser complaint.
  }
  return new IdentityFetchError(`Could not load identities (${status}).`, status >= 500);
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

/**
 * Query keys for the identity inventory.
 *
 * <p>Exported so anything that changes IAM from inside the app — an access review action, an
 * account reconnect — can invalidate this without reaching into the hook.
 */
export const identityKeys = {
  all: ['security', 'identities'] as const,
  account: (accountId: string) => ['security', 'identities', accountId] as const,
};

/** Shared empties, so a data-less render does not hand `useMemo` a new array each time. */
const NO_USERS: IamUser[] = [];
const NO_ROLES: IamRole[] = [];

/**
 * One identity request.
 *
 * <p>Targets the dedicated identities endpoint rather than the dashboard aggregate. The
 * aggregate joins ~18 AWS collectors and blocks on all of them, so this tab used to inherit
 * Cost Explorer's latency and got the gateway's HTML 504 instead of data whenever the cache
 * was cold. Same reason `forceRefresh` is safe to hand to the Reload button: it re-runs the IAM
 * scan, not the entire dashboard.
 */
async function fetchIdentities(
  accountId: string,
  forceRefresh: boolean,
  outerSignal: AbortSignal | undefined,
): Promise<IdentityInventoryResponse> {
  // Our own controller, chained to React Query's. Its signal handles unmount and account
  // switches; this one adds the wall-clock ceiling, and a socket that hangs open needs both.
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  const relayAbort = () => controller.abort();
  outerSignal?.addEventListener('abort', relayAbort);

  try {
    const url = `/api/xamops/security/identities?accountId=${encodeURIComponent(accountId)}`
      + `&forceRefresh=${forceRefresh}`;

    const response = await fetch(url, {
      method: 'GET',
      headers: { Accept: 'application/json' },
      credentials: 'include',
      // The query cache is the cache now. Leaving the HTTP one in play as well would mean two
      // layers with different lifetimes deciding what "current" means.
      cache: 'no-store',
      signal: controller.signal,
    });

    if (!response.ok) {
      throw describeFailure(response.status, await response.text().catch(() => ''));
    }

    const data: IdentityInventoryResponse | null = await response.json();
    if (!data) {
      throw new IdentityFetchError(
        'Could not load identities: the server returned an empty response.', false);
    }
    return data;
  } catch (err) {
    // A hit on our own ceiling, as opposed to React Query cancelling us. Only the former is the
    // user's problem; a cancelled request is rethrown untouched so the cache discards it rather
    // than storing an error for a request nobody is waiting on.
    if (err instanceof DOMException && err.name === 'AbortError' && !outerSignal?.aborted) {
      throw new IdentityFetchError(
        'The identity scan took too long to respond. Reload identities to try again.', true);
    }
    throw err;
  } finally {
    window.clearTimeout(timeout);
    outerSignal?.removeEventListener('abort', relayAbort);
  }
}

export const useUserManagement = () => {
  const queryClient = useQueryClient();

  const [accountId, setAccountId] = useState('');
  const [storedProvider, setStoredProvider] = useState<'AWS' | 'GCP' | 'Azure'>('AWS');
  const [searchQuery, setSearchQuery] = useState('');

  /**
   * "No account is selected" — kept apart from fetch failures.
   *
   * <p>It is not a fetch failure, it precedes any fetch, and it must survive the query being
   * disabled. Folding it into the query's error would mean either faking an error or losing it.
   */
  const [selectionError, setSelectionError] = useState<string | null>(null);

  /**
   * When true, show only identities carrying a HIGH or CRITICAL risk.
   *
   * <p>Kept in the hook rather than the component so the KPI cards can act as filter
   * controls — clicking "Over-privileged" is the fastest path to the list that matters.
   */
  const [flaggedOnly, setFlaggedOnly] = useState(false);

  /**
   * Set just before a fetch that must bypass the server cache, and consumed by the next one.
   *
   * <p>A flag rather than part of the query key. Putting `forceRefresh` in the key — the pattern
   * the unified dashboard uses — creates a separate cache entry per press, so the tab would
   * accumulate entries and a later navigation could read a stale one. Here every fetch for an
   * account lands in the same entry, and this decides how that entry gets filled.
   */
  const forceNextRef = useRef(false);

  /** Polls spent waiting on server-side work, reset per account and per manual reload. */
  const [pollsSpent, setPollsSpent] = useState(0);

  /** Account whose empty-inventory retry has already been spent. */
  const nullRetryRef = useRef<string | null>(null);

  /**
   * Resolves the selected account from storage.
   *
   * <p>No longer decides whether to fetch, which is the substantive change. It used to call
   * `fetchData` directly under `accountId !== targetId || users.length === 0`, and since it runs
   * on every mount and on every `account-changed` event, the second half of that condition made a
   * remount with no data indistinguishable from an account switch. Setting the id is now the only
   * thing it does; whether that needs a request is the query cache's judgement, not this
   * function's.
   */
  const resolveSelectedAccount = useCallback(() => {
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

    if (!targetId) {
      setSelectionError('Please select an account from the sidebar.');
      setAccountId('');
      return;
    }

    setSelectionError(null);
    setAccountId(targetId);

    // Provider from the account list the sidebar already cached, so labels are right on the
    // first paint instead of after the response lands.
    try {
      const cached = sessionStorage.getItem('cached_accounts');
      if (cached) {
        const accounts = JSON.parse(cached);
        const account = accounts.find((a: any) => a.id === targetId);
        if (account?.provider) setStoredProvider(account.provider);
      }
    } catch (e) {
      console.error('Error determining provider', e);
    }
  }, []);

  useEffect(() => {
    resolveSelectedAccount();

    const handleAccountChange = () => resolveSelectedAccount();
    window.addEventListener('account-changed', handleAccountChange);

    return () => {
      window.removeEventListener('account-changed', handleAccountChange);
    };
  }, [resolveSelectedAccount]);

  const {
    data,
    error: fetchError,
    isFetching,
    refetch,
  } = useQuery({
    queryKey: identityKeys.account(accountId),
    queryFn: ({ signal }) => {
      const force = forceNextRef.current;
      forceNextRef.current = false;
      return fetchIdentities(accountId, force, signal);
    },
    enabled: Boolean(accountId),
    staleTime: IDENTITY_STALE_MS,
    gcTime: IDENTITY_GC_MS,
    /**
     * Waits out a scan rather than reporting it as a failure.
     *
     * <p>This is the fix for the banner users saw most. A 503 here means "the scan is running and
     * will finish", so the honest response is to ask again shortly — and because React Query
     * stays in a fetching state across retries, the tab holds its skeleton instead of flashing an
     * error at someone who only has to wait. Non-retryable statuses opt out via
     * {@link IdentityFetchError}, so a 403 still surfaces immediately.
     */
    retry: (failureCount, error) =>
      error instanceof IdentityFetchError && error.retryable && failureCount < PENDING_POLL_LIMIT,
    retryDelay: PENDING_POLL_INTERVAL_MS,
  });

  const details = data?.iamDetails ?? null;
  const users = details?.users ?? NO_USERS;
  const roles = details?.roles ?? NO_ROLES;
  const hasData = users.length > 0 || roles.length > 0;

  const analysisDegraded = Boolean(details?.permissionAnalysisDegraded);
  const analysisNote = details?.permissionAnalysisNote ?? null;
  const analysisPending = Boolean(details?.permissionAnalysisPending);

  /**
   * Whether the server said it is still working on this account.
   *
   * <p>Two different jobs, one poll. `permissionAnalysisPending` is the risk audit finishing
   * after the inventory; `refreshing` is the whole snapshot being rebuilt because it aged out or
   * because an IAM change event arrived. Either way a better answer is coming without anyone
   * asking, and the only thing the client has to do is look again.
   */
  const awaitingServer = analysisPending || Boolean(details?.refreshing);

  /**
   * Picks up server-side work once it lands.
   *
   * <p>Bounded rather than open-ended: the work either finishes within a minute or it hit a
   * limit it will not clear, and a poll that never gives up would keep a tab making requests for
   * as long as it is open. On the last attempt the banner is left in place, which is honest — the
   * inventory is real, the rest of it is not there yet.
   *
   * <p>A plain refetch on purpose, not a forced one: it has to read the cache the background work
   * writes to, and forcing would start a rival scan and never observe the result it is waiting
   * for.
   */
  useEffect(() => {
    if (!awaitingServer || !accountId) return;
    if (pollsSpent >= PENDING_POLL_LIMIT) return;

    const timer = window.setTimeout(() => {
      setPollsSpent((spent) => spent + 1);
      refetch();
    }, PENDING_POLL_INTERVAL_MS);

    return () => window.clearTimeout(timer);
  }, [awaitingServer, accountId, pollsSpent, refetch]);

  /** A new account gets a fresh budget of polls. */
  useEffect(() => {
    setPollsSpent(0);
  }, [accountId]);

  /**
   * A null inventory means the scan produced nothing rather than that the account is empty, so
   * retry once against a bypassed cache before believing it.
   *
   * <p>Once per account, tracked in a ref: an account that genuinely returns nothing would
   * otherwise have this and the response that triggers it chase each other indefinitely.
   */
  useEffect(() => {
    if (!accountId || !data || data.iamDetails) return;
    if (nullRetryRef.current === accountId) return;
    nullRetryRef.current = accountId;
    forceNextRef.current = true;
    refetch();
  }, [accountId, data, refetch]);

  /**
   * Re-runs the IAM scan.
   *
   * <p>`forceRefresh` bypasses the server's cache; without it this just re-reads whatever the
   * server already has, which is what the pending-work poll above wants and not what the Reload
   * button means.
   */
  const fetchData = useCallback(async (forceRefresh = false) => {
    if (!accountId) return;
    setPollsSpent(0);
    nullRetryRef.current = null;
    forceNextRef.current = forceRefresh;
    await refetch();
  }, [accountId, refetch]);

  /** Drops every cached inventory. For an action that invalidates all accounts at once. */
  const invalidateAll = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: identityKeys.all });
  }, [queryClient]);

  const provider = data?.provider ?? storedProvider;
  const accountName = data?.accountName ?? '';
  const resolvedAccountId = data?.accountId ?? accountId;

  /**
   * What to tell the user, if anything.
   *
   * <p>The "showing the last result" suffix exists because the query cache keeps the previous
   * answer through a failure, so an error can now appear over a populated list. Reporting only
   * "could not load identities" above a full table would read as a contradiction; what matters
   * is that the list is real but not current.
   */
  const error = useMemo(() => {
    if (selectionError) return selectionError;
    if (!fetchError) return null;
    const message = fetchError instanceof Error
      ? fetchError.message
      : 'Failed to load identities';
    return hasData ? `${message} Showing the last result that loaded.` : message;
  }, [selectionError, fetchError, hasData]);

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
    loading: isFetching,
    error,
    accountName,
    accountId: resolvedAccountId,
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
    invalidateAll,
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
     * When the inventory on screen was produced, as an ISO-8601 instant.
     *
     * <p>Shown because this payload is served from a cache that can outlive a working day. An
     * access review with no date on it invites the reader to assume it is live, which for the
     * one question this tab answers — who currently holds administrator access — is the
     * assumption most worth preventing.
     */
    lastUpdated: details?.lastUpdated ?? null,

    /** True when the server is rebuilding this snapshot behind the response. */
    isRefreshingSnapshot: Boolean(details?.refreshing),

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
