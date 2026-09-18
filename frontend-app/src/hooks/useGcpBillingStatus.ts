/**
 * useGcpBillingStatus Hook
 *
 * Fetches why a GCP project's cost data looks the way it does, so the cost page can tell
 * "billing export was never set up" apart from "this project genuinely spent nothing".
 *
 * Kept separate from useGcpCostData rather than folded into it because /costs/breakdown
 * returns a bare array that existing callers iterate directly — wrapping that response in
 * an envelope would break them.
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import type { GcpBillingStatus } from '../types/gcpCost';

interface UseGcpBillingStatusParams {
  selectedAccountId: string | null;
}

interface UseGcpBillingStatusReturn {
  billingStatus: GcpBillingStatus | null;
  isLoading: boolean;
  reload: (forceRefresh?: boolean) => Promise<void>;
}

export const useGcpBillingStatus = ({
  selectedAccountId,
}: UseGcpBillingStatusParams): UseGcpBillingStatusReturn => {
  const [billingStatus, setBillingStatus] = useState<GcpBillingStatus | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const isFetchingRef = useRef(false);

  const reload = useCallback(async (forceRefresh = false) => {
    if (!selectedAccountId) {
      setBillingStatus(null);
      return;
    }
    if (isFetchingRef.current) return;

    isFetchingRef.current = true;
    setIsLoading(true);

    try {
      const response = await fetch(
        `/api/xamops/gcp/costs/billing-status?accountId=${encodeURIComponent(selectedAccountId)}` +
          `&forceRefresh=${forceRefresh}`
      );

      if (!response.ok) {
        // Leave the status null rather than inventing one. A wrong explanation is worse
        // than none: the page falls back to its neutral empty state.
        console.error('[GCP Billing Status] request failed, status:', response.status);
        setBillingStatus(null);
        return;
      }

      setBillingStatus((await response.json()) as GcpBillingStatus);
    } catch (err) {
      console.error('[GCP Billing Status] request errored:', err);
      setBillingStatus(null);
    } finally {
      setIsLoading(false);
      isFetchingRef.current = false;
    }
  }, [selectedAccountId]);

  useEffect(() => {
    void reload(false);
  }, [reload]);

  return { billingStatus, isLoading, reload };
};
