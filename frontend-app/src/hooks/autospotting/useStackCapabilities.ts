import { useQuery } from '@tanstack/react-query';
import { getStackCapabilities, type StackCapabilities } from '../../api/autospottingApi';
import { autoSpottingKeys } from './useAutoSpottingConfig';
/** The capabilities the account's stack lacks for AutoSpotting; read from the stored template version. */
export function useStackCapabilities(accountId: number | null) {
  return useQuery<StackCapabilities>({
    queryKey: autoSpottingKeys.stackCapabilities(accountId),
    queryFn: () => getStackCapabilities(accountId as number),
    enabled: accountId !== null,
    retry: false,
    staleTime: 60_000,
  });
}
