import { useCallback } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { listGroups, type GroupList } from '../../api/autospottingApi';
import { autoSpottingKeys } from './useAutoSpottingConfig';

const TRANSIENT_STATES = ['APPLYING', 'REVERTING'];
const FRESH_MS = 30_000;
const TRANSIENT_REFETCH_MS = 60_000;

/**
 * The account's group list. Keeps `partial` and `failedLocations`, never serves a partial response
 * as fresh (it is refetched on the next read), and refetches every 60 s while any row is transient.
 * `refresh` bypasses the backend cache (AC-OBS-05).
 */
export function useSpotGroups(accountId: number | null) {
  const client = useQueryClient();
  const key = autoSpottingKeys.groups(accountId);
  const query = useQuery<GroupList>({
    queryKey: key,
    queryFn: () => listGroups(accountId as number),
    enabled: accountId !== null,
    retry: false,
    staleTime: q => (q.state.data?.partial ? 0 : FRESH_MS),
    refetchInterval: q =>
      q.state.data?.items.some(r => TRANSIENT_STATES.includes(r.state)) ? TRANSIENT_REFETCH_MS : false,
  });
  const refresh = useCallback(async () => {
    if (accountId === null) return;
    const fresh = await listGroups(accountId, { noCache: true });
    client.setQueryData(key, fresh);
  }, [accountId, client, key]);
  return { ...query, refresh };
}
