import { useQuery } from '@tanstack/react-query';
import { getAnalytics, type Analytics } from '../../api/autospottingApi';
import { autoSpottingKeys } from './useAutoSpottingConfig';

/** The Analytics view's read: counts per group and type for `from` to `to` (`YYYY-MM-DD`, UTC). */
export function useAnalytics(accountId: number | null, from: string, to: string) {
  return useQuery<Analytics>({
    queryKey: autoSpottingKeys.analytics(accountId, from, to),
    queryFn: () => getAnalytics(accountId as number, { from, to }),
    enabled: accountId !== null,
    retry: false,
    staleTime: 60_000,
  });
}
