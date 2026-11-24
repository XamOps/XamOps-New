import { useQuery } from '@tanstack/react-query';
import { getGroup, type GroupDetail } from '../../api/autospottingApi';
import { autoSpottingKeys } from './useAutoSpottingConfig';

/** One group with its transitions and current operation. Disabled for a group with no row yet. */
export function useSpotGroup(accountId: number | null, groupId: string | null) {
  return useQuery<GroupDetail>({
    queryKey: autoSpottingKeys.group(accountId, groupId),
    queryFn: () => getGroup(accountId as number, groupId as string),
    enabled: accountId !== null && !!groupId,
    retry: false,
    staleTime: 10_000,
  });
}
