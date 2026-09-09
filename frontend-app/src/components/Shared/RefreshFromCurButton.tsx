import React, { useCallback, useState } from 'react';
import { apiFetch, setCostSourceMode } from '../../utils/apiClient';
import { invalidateCurInsights } from '../../hooks/useCurInsight';

/**
 * Reloads this month's costs from the Cost and Usage Report only, with no Cost
 * Explorer fallback.
 *
 * <p>Additional to each page's ordinary Refresh rather than replacing it. That one
 * already prefers the report and drops to the Cost Explorer API on any problem —
 * right for serving a page, but it means a successful fallback looks exactly like a
 * working CUR once numbers are on screen. This one refuses to fall back, so it
 * answers "is this account really reading the report?", which the other cannot.
 *
 * <p>Shared across the Dashboard, Cost and FinOps headers because all three read the
 * same cache: the endpoint writes into the keys every one of them reads, so a refresh
 * here updates whichever page you happen to be on. Three copies of this would have
 * been three places for the error handling to drift.
 */

interface RefreshFromCurButtonProps {
  /** AWS account to reload. Render nothing when there isn't exactly one. */
  accountId: string | null;
  /** Called after a successful reload, so the page can refetch its now-CUR-backed cache. */
  onRefreshed: () => void | Promise<void>;
  /** Matches the surrounding header's colour scheme. */
  dark?: boolean;
}

interface CurRefreshResult {
  monthToDateCost?: number;
  serviceCount?: number;
  s3Bucket?: string;
  timeUnit?: string | null;
  error?: string;
  detail?: string;
}

export const RefreshFromCurButton: React.FC<RefreshFromCurButtonProps> = ({
  accountId,
  onRefreshed,
  dark = false,
}) => {
  const [isRefreshing, setIsRefreshing] = useState(false);

  const handleClick = useCallback(async () => {
    if (!accountId) return;
    setIsRefreshing(true);
    try {
      const res = await apiFetch(
        `/api/xamops/costs/refresh-from-cur?accountId=${encodeURIComponent(accountId)}`,
        { method: 'POST' },
      );
      const body: CurRefreshResult = await res.json();

      if (!res.ok) {
        // The endpoint deliberately refuses to fall back, so its message names the real
        // reason — surfacing it beats a generic failure.
        window.alert(
          `Could not read the Cost and Usage Report\n\n${body.detail ?? body.error ?? res.status}`,
        );
        return;
      }

      window.alert(
        'Loaded from the Cost and Usage Report\n\n' +
          `Month to date: $${Number(body.monthToDateCost ?? 0).toFixed(2)}\n` +
          `Services: ${body.serviceCount}\n` +
          `Bucket: ${body.s3Bucket}\n` +
          `Granularity: ${body.timeUnit ?? 'unknown'}`,
      );
      // The report figures are already in the shared cache, so this reload only has to
      // re-read it. Switching the mode first stops the page's forced refresh being tagged
      // as Cost Explorer — without it the reload overwrote what was just written and the
      // button silently undid its own work.
      setCostSourceMode('CUR');
      // The insight sections cache for 30 minutes, which is right for page navigation and
      // wrong here: this refresh rewrote the figures they read, so without dropping the
      // cache they would keep showing pre-refresh numbers on a page that just said it
      // reloaded.
      invalidateCurInsights();
      await onRefreshed();
    } catch (e) {
      window.alert(`Could not read the Cost and Usage Report\n\n${(e as Error).message}`);
    } finally {
      setIsRefreshing(false);
    }
  }, [accountId, onRefreshed]);

  // A CUR belongs to one account, so there is nothing to reload for a multi-account
  // selection or a non-AWS provider. Hiding beats offering a button that can only error.
  if (!accountId) return null;

  return (
    <button
      type="button"
      onClick={handleClick}
      disabled={isRefreshing}
      title="Reload this month's costs from the Cost and Usage Report only — no Cost Explorer fallback"
      className={`flex items-center gap-1.5 text-[12px] font-medium transition-colors disabled:opacity-40 ${
        dark
          ? 'text-[rgba(255,255,255,0.5)] hover:text-white'
          : 'text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)]'
      }`}
    >
      <i className={`fas fa-database text-[10px] ${isRefreshing ? 'animate-pulse' : ''}`} />
      {isRefreshing ? 'Reading CUR…' : 'Refresh from CUR'}
    </button>
  );
};

export default RefreshFromCurButton;
