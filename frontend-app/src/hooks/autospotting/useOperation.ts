import { useEffect } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { getOperation, type Operation } from '../../api/autospottingApi';
import { autoSpottingKeys } from './useAutoSpottingConfig';

export const TERMINAL_STATUSES = ['SUCCEEDED', 'FAILED', 'CANCELLED'];
export const isTerminal = (status: string | null | undefined): boolean =>
  !!status && TERMINAL_STATUSES.includes(status);

export const RUNNING_POLL_MS = 2_000;
export const WAITING_POLL_MS = 10_000;
export const HIDDEN_POLL_MS = 60_000;
const BACKOFF_START_MS = 5_000;
const BACKOFF_MAX_MS = 60_000;

/**
 * How long to wait before the next poll of an operation (design-ux-ops.md §4.1): 2 s while it is
 * queued or running, 10 s while waiting, 60 s in a hidden tab, exponential 5 s to 60 s after request
 * errors, and `false` (stop) once it is terminal.
 */
export function operationPollInterval(
  status: string | undefined,
  opts: { hidden?: boolean; errorCount?: number } = {},
): number | false {
  if (isTerminal(status)) return false;
  const errors = opts.errorCount ?? 0;
  if (errors > 0) return Math.min(BACKOFF_MAX_MS, BACKOFF_START_MS * 2 ** (errors - 1));
  if (opts.hidden) return HIDDEN_POLL_MS;
  return status === 'WAITING' ? WAITING_POLL_MS : RUNNING_POLL_MS;
}

/** Poll one operation until it is terminal, then refetch the group list once. */
export function useOperation(accountId: number | null, operationId: string | null) {
  const client = useQueryClient();
  const query = useQuery<Operation>({
    queryKey: autoSpottingKeys.operation(accountId, operationId),
    queryFn: () => getOperation(accountId as number, operationId as string),
    enabled: accountId !== null && !!operationId,
    retry: false,
    refetchInterval: q =>
      operationPollInterval(q.state.data?.status, {
        hidden: typeof document !== 'undefined' && document.visibilityState === 'hidden',
        errorCount: q.state.status === 'error' ? q.state.errorUpdateCount : 0,
      }),
  });
  const terminal = isTerminal(query.data?.status);
  useEffect(() => {
    if (terminal) {
      void client.invalidateQueries({ queryKey: autoSpottingKeys.groups(accountId) });
      void client.invalidateQueries({ queryKey: ['autospotting', 'group', accountId] });
    }
  }, [terminal, accountId, client]);
  return { ...query, terminal };
}
