import { useQuery } from '@tanstack/react-query';
import { getGroupSavings, getLedgerSummary, type LedgerGroupMonth, type LedgerSummary } from '../../api/autospottingApi';
import { autoSpottingKeys } from './useAutoSpottingConfig';

/** The open month's ledger accrual of the account (shadow, not final). */
export function useLedgerSummary(accountId: number | null) {
  return useQuery<LedgerSummary>({
    queryKey: autoSpottingKeys.ledgerSummary(accountId),
    queryFn: () => getLedgerSummary(accountId as number),
    enabled: accountId !== null,
    retry: false,
    staleTime: 60_000,
  });
}

/** A group's measured net per month, each with its label. */
export function useGroupSavings(accountId: number | null, groupId: string | null) {
  return useQuery<LedgerGroupMonth[]>({
    queryKey: autoSpottingKeys.groupSavings(accountId, groupId),
    queryFn: () => getGroupSavings(accountId as number, groupId as string),
    enabled: accountId !== null && groupId !== null,
    retry: false,
    staleTime: 60_000,
  });
}
