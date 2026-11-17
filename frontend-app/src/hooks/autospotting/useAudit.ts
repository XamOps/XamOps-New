import { useQuery } from '@tanstack/react-query';
import { getAudit, type AuditEntry } from '../../api/autospottingApi';
import { autoSpottingKeys } from './useAutoSpottingConfig';

/** The Activity view's read of the account's audit rows, newest first. */
export function useAudit(accountId: number | null, opts: { groupId?: string; from: string; to: string; limit: number }) {
  return useQuery<AuditEntry[]>({
    queryKey: autoSpottingKeys.audit(accountId, opts.groupId ?? '', opts.from, opts.to, opts.limit),
    queryFn: () => getAudit(accountId as number, opts),
    enabled: accountId !== null,
    retry: false,
    staleTime: 30_000,
  });
}
