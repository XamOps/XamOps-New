import { useQuery } from '@tanstack/react-query';
import { queryClient } from '../lib/queryClient';

/**
 * Shared caching for the Cost and Usage Report sections.
 *
 * <p>Without it every one of these re-ran its Athena query on each mount, so simply
 * navigating away from the Cost page and back re-scanned the report and put the loading
 * skeletons back on screen. That is wrong twice over: the numbers had not changed, and
 * Athena bills per byte scanned, so page navigation was quietly costing money.
 *
 * <p>The report itself is rebuilt by AWS a few times a day at most, which is what makes a
 * long stale time correct here rather than merely convenient — refetching more often than
 * the underlying data changes cannot produce a different answer. On-demand freshness comes
 * from {@link invalidateCurInsights}, wired to the Refresh from CUR button.
 */

const CUR_INSIGHTS_KEY = 'cur-insights';

/** Longer than the client default of 5 minutes. See the note above on why that is safe. */
const CUR_STALE_TIME = 30 * 60 * 1000;

/**
 * Fetches one CUR insight, cached per account and period.
 *
 * <p>`data` is `null` when the account has no usable report — the fetchers return null
 * rather than throwing on 409, so callers hide the section instead of showing an error
 * nobody can act on.
 */
export function useCurInsight<T>(
  /** Distinguishes this insight from the others sharing the cache. */
  name: string,
  accountId: string | null,
  period: string | undefined,
  fetcher: (accountId: string, period?: string) => Promise<T | null>,
) {
  return useQuery({
    queryKey: [CUR_INSIGHTS_KEY, name, accountId, period ?? 'current'],
    // Only ever called with `enabled` satisfied, so accountId is non-null here.
    queryFn: () => fetcher(accountId as string, period),
    enabled: Boolean(accountId),
    staleTime: CUR_STALE_TIME,
    // A failure here means the report cannot be read at all — a missing table, no Athena
    // permission. Retrying delays the empty state without changing it.
    retry: false,
  });
}

/**
 * Drops every cached insight, so the sections refetch.
 *
 * <p>Called after a CUR refresh: that rewrites the figures these sections read, and
 * without this the page would keep showing the pre-refresh numbers for half an hour.
 */
export const invalidateCurInsights = (): void => {
  void queryClient.invalidateQueries({ queryKey: [CUR_INSIGHTS_KEY] });
};
