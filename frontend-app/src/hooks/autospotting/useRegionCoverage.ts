import { useQuery } from '@tanstack/react-query';
import { getRegionCoverage, type RegionCoverage } from '../../api/autospottingApi';
import { autoSpottingKeys } from './useAutoSpottingConfig';
/** The event pipeline's coverage of the account per region, from the daily coverage check. */
export function useRegionCoverage(accountId: number | null) {
  return useQuery<RegionCoverage>({
    queryKey: autoSpottingKeys.regionCoverage(accountId),
    queryFn: () => getRegionCoverage(accountId as number),
    enabled: accountId !== null,
    retry: false,
    staleTime: 60_000,
  });
}
